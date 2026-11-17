package com.loudbook.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PC's recording of a book chapter (BookPages), sentence by sentence: a book chapter's text names the recording
 * ("recording": "<book>/<NNNN - name>", its .json and .mp3 copied from the PC like any audiobook chapter). Each of the
 * reader's sentences is found in the recording's sentences by its words; one that's there is played from the recording,
 * and any that isn't (or a chapter with no recording on the phone) is read by the reader's own voice. The MP3 is decoded
 * once into Loudbook's cache (the last few chapters are kept), as it's needed: the first sentence is ready straight away.
 */
final class BookAudio {
    static final String TAG = "Loudbook";

    static final class Rec {
        final String key; final Uri mp3; final List<String> words = new ArrayList<>(); final List<double[]> times = new ArrayList<>();
        Chapter boundTo; final Map<Integer, Integer> bound = new HashMap<>();
        File pcm; volatile long frames; volatile int rate; volatile boolean done, failed; boolean started;
        volatile long failedAt;
        /** Made on the phone: samples written to the encoder (0: unknown), and the decoder's extra at the start found
         *  from it (AAC encoders put ~2048 samples of priming in front, which some decoders keep: every sentence came
         *  later than its times by more than their margin). */
        long written; volatile long shift;
        /** Made on the phone ahead (AheadMaker): its voice and the speed it was made at; -1 / 1 for the PC's recordings. */
        int sid = -1; float madeAt = 1f; String stamp = "";
        /** Its voice in words, for "Which voice?" */
        String who = "";
        /** For "Which voice?": when its decoding began, how long it took (0: decoded before, from the cache; -1: not yet),
         *  and the longest a sentence waited for it (ms). */
        volatile long decodeFrom, decodeMs = -1, waitedMs;
        /** Sentences the voice read because the unpacking was still far from them. */
        volatile int farMisses;
        /** When the decoder gave its first sound (ms): its speed is measured from there, not from opening the file. */
        volatile long firstOutAt;
        Rec(String key, Uri mp3) { this.key = key; this.mp3 = mp3; }
    }

    /** By chapter address; null = no recording (asked again after a minute: it may have been copied meanwhile). */
    private static final Map<String, Rec> recs = new HashMap<>();
    private static final Map<String, Long> none = new HashMap<>();

    /** Letters and digits only, lower case: how a sentence is matched to the recording's. */
    static String words(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(Character::isLetterOrDigit).forEach(c -> b.appendCodePoint(Character.toLowerCase(c)));
        return b.toString();
    }

    private static synchronized Rec rec(Context ctx, Chapter ch) {
        Rec r = recs.get(ch.url);
        // (a recording that failed to decode is tried again after a minute: it may have been copied again meanwhile)
        if (r != null && r.failed && r.failedAt > 0 && System.currentTimeMillis() - r.failedAt > 60_000) { recs.remove(ch.url); r = null; }
        if (r != null) return r;
        Long no = none.get(ch.url);
        if (no != null && System.currentTimeMillis() - no < 60_000) return null;
        // (the PC's recording first: made ahead on the phone was looked at first and kept, so a book the PC had recorded
        // played in the phone's voice from then on, or, made with another voice, was read live with the PC's never tried)
        String[] bf = BookPages.parse(ch.url);
        if (bf != null) r = load(ctx, bf[0], bf[1]);
        if (r == null) r = made(ctx, ch.url);
        if (r == null) { none.put(ch.url, System.currentTimeMillis()); return null; }
        recs.put(ch.url, r);
        return r;
    }

    /** The PC recorded this chapter and the recording is on the phone: nothing to make ahead for it. */
    static boolean recordedOnPc(Context ctx, Chapter ch) {
        Rec r = rec(ctx, ch);
        return r != null && r.sid < 0 && !r.failed;
    }

    /** The chapter made ahead on the phone (AheadMaker), or null. */
    private static Rec made(Context ctx, String url) {
        File info = AheadMaker.infoFile(ctx, url), audio = AheadMaker.audioFile(ctx, url);
        if (!info.isFile() || !audio.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(info.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            JSONArray ss = o.optJSONArray("sentences");
            if (ss == null || ss.length() == 0) return null;
            Rec r = new Rec("made:" + AheadMaker.key(url), Uri.fromFile(audio));
            r.sid = o.optInt("sid", -1); r.madeAt = (float) o.optDouble("speed", 1);
            r.written = o.optLong("frames", 0);
            r.stamp = audio.length() + "-" + audio.lastModified();
            r.who = "made ahead on this phone: Kokoro, " + Voice.name(r.sid) + (Math.abs(r.madeAt - 1f) > 0.01f ? String.format(java.util.Locale.US, " at %.2fx", r.madeAt) : "");
            for (int i = 0; i < ss.length(); i++) {
                JSONObject x = ss.getJSONObject(i);
                r.words.add(words(x.optString("text", "")));
                r.times.add(new double[]{x.optDouble("start", 0), x.optDouble("end", 0)});
            }
            return r;
        } catch (Exception e) { Log.w(TAG, "ahead: reading what was made for " + url, e); return null; }
    }

    private static Rec load(Context ctx, String book, String file) {
        try {
            String raw = Books.chapterText(ctx, book, file);
            if (raw.isEmpty()) return null;
            String rp = new JSONObject(raw).optString("recording", "");
            int slash = rp.indexOf('/');
            if (rp.isEmpty() || rp.equals("null") || slash <= 0) return null;
            String rb = rp.substring(0, slash), stem = rp.substring(slash + 1);
            String json = Books.chapterText(ctx, rb, stem + ".json");
            if (json.isEmpty()) return null;
            Uri mp3 = Books.audio(ctx, rb, new Books.Chapter(stem + ".json", "", 0));
            if (mp3 == null) return null;
            JSONArray ss = new JSONObject(json).optJSONArray("sentences");
            if (ss == null || ss.length() == 0) return null;
            Rec r = new Rec(rp, mp3);
            // (recorded again on the PC (its studio) and copied again: decoded afresh, not the old take from the cache)
            r.stamp = json.length() + "-" + json.hashCode();
            List<String> voices = new ArrayList<>();
            for (int i = 0; i < ss.length(); i++) {
                JSONObject s = ss.getJSONObject(i);
                r.words.add(words(s.optString("text", "")));
                r.times.add(new double[]{s.optDouble("start", 0), s.optDouble("end", 0)});
                if (s.has("voice")) voices.add(s.optString("voice", ""));
            }
            r.who = "your PC's recording: " + VoiceNames.summary(voices);
            Log.i(TAG, "book: " + file + " has the PC's recording (" + r.words.size() + " sentences)");
            return r;
        } catch (Exception e) { Log.w(TAG, "book: recording of " + file, e); return null; }
    }

    /** The reader's sentences -> the recording's, in order (each found a little ahead of the last one found). */
    private static synchronized Integer boundAt(Rec r, Chapter ch, int i) {
        if (r.boundTo != ch) {
            r.boundTo = ch; r.bound.clear();
            List<String> mine = new ArrayList<>();
            for (int k = 0; k < ch.size(); k++) mine.add(words(ch.chunks.get(k).say));
            int[] at = RecMatch.align(mine, r.words);
            for (int k = 0; k < at.length; k++) if (at[k] >= 0) r.bound.put(k, at[k]);
            Log.i(TAG, "book: " + r.bound.size() + " of " + ch.size() + " sentences are in the PC's recording");
        }
        return r.bound.get(i);
    }

    /** Sentence i of this chapter is in a recording on this phone (made ahead with this voice, or the PC's). */
    static boolean covers(Context ctx, Chapter ch, int i, int sid) {
        Rec r = rec(ctx, ch);
        return r != null && !r.failed && (r.sid < 0 || r.sid == sid) && boundAt(r, ch, i) != null;
    }

    /** A sentence the decoding would take longer than this to reach is read by the voice instead (ms). */
    static final long FAR_MS = 3000;

    /** Sentence i from the recording, or null (not in it, or the recording can't be played): the voice reads it. */
    static Voice.Clip clip(Context ctx, Chapter ch, int i, int sid) {
        long t0 = System.currentTimeMillis();
        Rec r = rec(ctx, ch);
        if (r == null || (r.sid >= 0 && r.sid != sid)) return null;
        Integer at = boundAt(r, ch, i);
        if (at == null) return null;
        decode(ctx, r);
        double[] t = r.times.get(at);
        try {
            // (the decoding runs ahead from the start: wait until it has got this far)
            // (a decoder that stops moving for 6 s gives way to the voice at once: each sentence waited the whole 90 s)
            long until = System.currentTimeMillis() + 90_000, seen = -1, moved = System.currentTimeMillis();
            // (made on the phone: the AAC decoder's delay at the start is only known once the whole file is decoded. It used
            // to wait for all of it, which for a long chapter was half a minute of silence at every new chapter; now it
            // goes on with the delay this phone's decoder had last time (each sentence has 80 ms of pause on each side))
            long guess = r.written > 0 ? knownShift(ctx) : 0;   // (in 24 kHz samples: used only at that rate, below)
            while (!r.failed && (r.rate == 0 || (!r.done && r.frames < (long) (t[1] * r.rate) + guess + r.rate / 10))) {
                long now = System.currentTimeMillis(), f = r.frames;
                if (f != seen) { seen = f; moved = now; }
                // (far ahead of the decoding, as when a long chapter is opened part way in: the voice reads this sentence
                //  now rather than leaving silence while the recording is unpacked up to it; the sentences after come
                //  from the recording again once it has got there)
                long ran = now - (r.firstOutAt > 0 ? r.firstOutAt : r.decodeFrom);
                if (r.rate > 0 && r.decodeFrom > 0 && now - r.decodeFrom > 1500 && ran > 700 && f > 0) {
                    long need = (long) (t[1] * r.rate) + guess + r.rate / 10 - f;
                    if (need * ran / f > FAR_MS) {
                        Log.i("LoudbookTest", "book: sentence " + i + " is " + need / r.rate + " s of sound past the unpacking (about "
                            + need * ran / f / 1000 + " s more): the voice reads it");
                        r.farMisses++;
                        return null;
                    }
                }
                if (now > until || now - moved > 6_000) {
                    r.waitedMs = Math.max(r.waitedMs, now - t0);
                    Log.w(TAG, "book: the recording's decoding is stuck or slow: the voice reads sentence " + i);
                    return null;
                }
                Thread.sleep(40);
            }
            long waited = System.currentTimeMillis() - t0;
            if (waited > 1000) {
                r.waitedMs = Math.max(r.waitedMs, waited);
                Log.i("LoudbookTest", "book: sentence " + i + " waited " + waited + " ms for the recording to be unpacked (" + (r.frames / Math.max(1, r.rate)) + " s of it so far)");
            }
            if (r.failed) return null;
            int rate = r.rate;
            long sh = r.done ? r.shift : rate == AheadMaker.RATE ? guess : 0;
            long from = Math.max(0, (long) (t[0] * rate) + sh), to = Math.min(r.frames, (long) (t[1] * rate) + sh);
            if (to <= from) return null;
            float[] out = new float[(int) (to - from)];
            byte[] b = new byte[out.length * 2];
            try (RandomAccessFile f = new RandomAccessFile(r.pcm, "r")) { f.seek(from * 2); f.readFully(b); }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < out.length; k++) out[k] = bb.getShort() / 32768f;
            return new Voice.Clip(out, rate, r.madeAt, System.currentTimeMillis() - t0).from(r.who);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { Log.w(TAG, "book: reading the recording", e); return null; }
    }

    /** The recording of this chapter on the phone, in words ("your PC's recording: Onyx"), or null. */
    static String describe(Context ctx, Chapter ch, int sid) {
        Rec r = rec(ctx, ch);
        if (r == null) return null;
        return r.who + (r.failed ? " (it couldn't be played: the voice reads instead)"
            : r.sid >= 0 && r.sid != sid ? " (another voice than the one chosen now, so it isn't used)" : "") + unpacking(r);
    }

    /** How the recording's decoding went, for "Which voice?" (a slow start may be waiting for it). */
    static String unpacking(Rec r) {
        if (r.failed || !r.started) return "";
        String w = (r.waitedMs > 1000 ? String.format(java.util.Locale.US, "; a sentence waited %.1f s for it", r.waitedMs / 1000.0) : "")
            + (r.farMisses > 0 ? "; " + r.farMisses + (r.farMisses == 1 ? " sentence was" : " sentences were") + " read by the voice while it was still being unpacked" : "");
        long d = r.decodeMs;
        if (r.done && d == 0) return ". Unpacked before (ready at once)" + w;
        if (r.done && d > 0) return String.format(java.util.Locale.US, ". Unpacked in %.1f s (%d min of sound)", d / 1000.0, r.frames / Math.max(1, r.rate) / 60) + w;
        long so = r.rate > 0 ? r.frames / r.rate : 0;
        long took = r.decodeFrom > 0 ? (System.currentTimeMillis() - r.decodeFrom) / 1000 : 0;
        return String.format(java.util.Locale.US, ". Unpacking it now: %d:%02d of it in %d s", so / 60, so % 60, took) + w;
    }

    /** This chapter was made again (AheadMaker): looked up afresh next time. */
    static synchronized void forget(String url) { recs.remove(url); none.remove(url); }

    /** New files came from the PC (BookCopy): every chapter is looked up afresh (a recording copied again has new
     *  timings and a new place on the phone; the old one was kept until Loudbook restarted). */
    static synchronized void forgetAll() { recs.clear(); none.clear(); }

    /** A book was deleted: nothing remembered or decoded from before is used again. */
    static synchronized void forget(Context ctx) {
        recs.clear(); none.clear();
        File[] fs = dir(ctx).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ---------------------------------------------------------------- the MP3, decoded once into the cache

    private static final LinkedHashMap<String, Long> made = new LinkedHashMap<>();
    /** Cache files being decoded now, by name (a copy from the PC forgets every Rec: a new one for the same recording
     *  must not start a second decoder writing the same file over the first one's). */
    private static final Map<String, Rec> decoding = new HashMap<>();

    private static File dir(Context ctx) { return new File(ctx.getCacheDir(), "bookaudio"); }

    private static long shiftOf(Context ctx, Rec r, long decoded, int rate) {
        long s = rate == AheadMaker.RATE ? RecMatch.shift(r.written, decoded, rate) : 0;
        if (r.written > 0) {
            Log.i(TAG, "book: made-ahead chapter decoded: " + decoded + " samples for " + r.written + " written: times moved " + s);
            if (rate == AheadMaker.RATE && s != shiftSeen) { shiftSeen = s; try { Voice.prefs(ctx).edit().putLong("aacShift", s).apply(); } catch (Exception ignored) { } }
        }
        return s;
    }
    private static volatile long shiftSeen = -1;
    /** The AAC decoder's delay this phone had on the last chapter made ahead (samples; 0 until one was decoded). */
    static long knownShift(Context ctx) {
        long s = shiftSeen;
        if (s >= 0) return s;
        // (none measured yet: half the usual 2048-sample priming, so either way a cut is within ~45 ms, inside the quiet
        // each sentence has at both ends; a guess of 0 cut a phone that keeps the priming ~85 ms early)
        try { s = Voice.prefs(ctx).getLong("aacShift", -1); } catch (Exception e) { s = -1; }
        if (s < 0) return 1024;
        return shiftSeen = s;
    }

    private static synchronized void decode(Context ctx, Rec r) {
        if (r.started) return;
        r.started = true;
        File d = dir(ctx);
        d.mkdirs();
        String name = Integer.toHexString(r.key.hashCode()) + "-" + Integer.toHexString((r.mp3.toString() + r.stamp).hashCode());
        r.pcm = new File(d, name + ".pcm");
        File ok = new File(d, name + ".ok");
        // (decoded before: its rate is in the .ok file)
        if (r.pcm.isFile() && ok.isFile()) {
            try {
                r.rate = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(ok.toPath())).trim());
                r.frames = r.pcm.length() / 2; r.shift = shiftOf(ctx, r, r.frames, r.rate); r.decodeMs = 0; r.done = true;
                // (used now: trim keeps the newest files, and this one, decoded long ago, was the oldest while it played)
                r.pcm.setLastModified(System.currentTimeMillis());
                return;
            } catch (Exception ignored) { }
        }
        Rec twin = decoding.get(name);
        if (twin != null) {
            r.decodeFrom = twin.decodeFrom; r.firstOutAt = twin.firstOutAt;
            // (the same recording, already being decoded for a Rec forgotten meanwhile: follow that one)
            Thread w = new Thread(() -> {
                while (!twin.done && !twin.failed) {
                    r.rate = twin.rate; r.frames = twin.frames;
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                r.rate = twin.rate; r.frames = twin.frames; r.shift = twin.shift; r.decodeMs = twin.decodeMs; r.failed = twin.failed; r.done = twin.done;
            }, "lb-bookaudio-twin");
            w.setDaemon(true);
            w.start();
            return;
        }
        trim(d, name);
        decoding.put(name, r);
        r.decodeFrom = System.currentTimeMillis();
        Thread th = new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(r.pcm)) {
                long frames = decodeInto(ctx, r.mp3, out, r);
                int rate = r.rate;
                out.flush();
                r.rate = rate;
                r.shift = shiftOf(ctx, r, frames, rate);
                r.decodeMs = Math.max(1, System.currentTimeMillis() - r.decodeFrom);
                r.done = true;
                try (FileOutputStream w = new FileOutputStream(ok)) { w.write(String.valueOf(rate).getBytes()); }
                Log.i(TAG, "book: recording decoded (" + frames / Math.max(1, rate) + " s of sound in " + r.decodeMs + " ms)");
            } catch (Exception e) {
                Log.w(TAG, "book: decoding the recording", e);
                r.failedAt = System.currentTimeMillis();
                r.failed = true;
            } finally {
                synchronized (BookAudio.class) { decoding.remove(name); }
            }
        }, "lb-bookaudio");
        th.setPriority(Thread.NORM_PRIORITY - 1);
        th.start();
    }

    /** Decodes the recording src into out as 16-bit mono samples, keeping r.rate / r.frames up to date as it goes (the
     *  sentences wait on them). The number of samples. */
    static long decodeInto(Context ctx, Uri src, java.io.OutputStream out, Rec r) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, src, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IllegalStateException("no audio in the recording");
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 24000;
            boolean floats = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long frames = 0;
            byte[] buf = new byte[0];
            // (every free input buffer is filled, then every ready output taken, without waiting while either moves: one
            //  frame in and a 10 ms wait for one out per turn kept a long chapter's first sentences waiting)
            long moved = System.currentTimeMillis();
            while (!outDone) {
                // (a decoder that gives nothing for half a minute is broken: the recording fails, the voice reads)
                if (System.currentTimeMillis() - moved > 30_000) throw new IllegalStateException("the decoder stopped");
                boolean fed = false;
                while (!inDone) {
                    int ii = dec.dequeueInputBuffer(0);
                    if (ii < 0) break;
                    ByteBuffer in = dec.getInputBuffer(ii);
                    if (in == null) throw new IllegalStateException("no input buffer from the decoder");
                    int n = ex.readSampleData(in, 0);
                    if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    fed = true;
                }
                boolean got = false;
                while (true) {
                    int oi = dec.dequeueOutputBuffer(info, got || fed ? 0 : 5_000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) floats = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
                        continue;
                    }
                    if (oi < 0) break;                     // (none ready: feed more, or wait a little next turn)
                    got = true;
                    moved = System.currentTimeMillis();
                    ByteBuffer o = dec.getOutputBuffer(oi);
                    if (info.size > 0 && o != null) {
                        o.position(info.offset); o.limit(info.offset + info.size);
                        o.order(ByteOrder.LITTLE_ENDIAN);
                        int per = floats ? 4 : 2, ch = Math.max(1, channels);
                        int n = info.size / per / ch;
                        if (buf.length < n * 2) buf = new byte[n * 2];
                        if (!floats && ch == 1) o.get(buf, 0, n * 2);         // (already 16-bit mono: as it is)
                        else {
                            for (int k = 0; k < n; k++) {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += floats ? o.getFloat() * 32767f : o.getShort();
                                int v = Math.max(-32768, Math.min(32767, Math.round(sum / ch)));
                                buf[2 * k] = (byte) v; buf[2 * k + 1] = (byte) (v >> 8);
                            }
                        }
                        out.write(buf, 0, n * 2);
                        if (frames == 0) r.firstOutAt = System.currentTimeMillis();
                        frames += n;
                        r.rate = rate;
                        r.frames = frames;
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { outDone = true; break; }
                }
            }
            r.rate = rate;
            return frames;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) { }
            ex.release();
        }
    }

    /** Test builds (chapter-test): how fast this phone unpacks a recording, the way a chapter's is (into a cache file). */
    static String speedTest(Context ctx, File f) {
        Rec r = new Rec("speedtest", Uri.fromFile(f));
        File tmp = new File(ctx.getCacheDir(), "speedtest.pcm");
        long t0 = System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            long n = decodeInto(ctx, r.mp3, out, r);
            long ms = Math.max(1, System.currentTimeMillis() - t0);
            double secs = n / (double) Math.max(1, r.rate);
            return String.format(java.util.Locale.US, "%.0f s of sound in %d ms (%.0fx)", secs, ms, secs * 1000 / ms);
        } catch (Exception e) {
            return "failed: " + e;
        } finally { tmp.delete(); }
    }

    /** Only the last few chapters' sound is kept (each is about 2.6 MB a minute). */
    private static void trim(File d, String keep) {
        File[] fs = d.listFiles((x, n) -> n.endsWith(".pcm"));
        if (fs == null || fs.length < 3) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - 2; i++) {
            if (fs[i].getName().startsWith(keep)) continue;
            String base = fs[i].getName().replaceFirst("\\.pcm$", "");
            synchronized (BookAudio.class) {
                boolean inUse = decoding.containsKey(base);
                for (Rec r : recs.values()) if (r.pcm != null && r.pcm.equals(fs[i]) && !r.done) inUse = true;
                if (inUse) continue;
                for (Rec r : recs.values()) if (r.pcm != null && r.pcm.equals(fs[i])) { r.started = false; r.done = false; r.frames = 0; r.rate = 0; r.decodeMs = -1; r.waitedMs = 0; r.farMisses = 0; r.firstOutAt = 0; }
            }
            fs[i].delete();
            new File(d, base + ".ok").delete();
        }
    }

    private BookAudio() { }
}
