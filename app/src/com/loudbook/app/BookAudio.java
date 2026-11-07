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
        String[] bf = BookPages.parse(ch.url);
        if (bf == null) return null;
        Rec r = recs.get(ch.url);
        if (r != null) return r;
        Long no = none.get(ch.url);
        if (no != null && System.currentTimeMillis() - no < 60_000) return null;
        r = load(ctx, bf[0], bf[1]);
        if (r == null) { none.put(ch.url, System.currentTimeMillis()); return null; }
        recs.put(ch.url, r);
        return r;
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
            for (int i = 0; i < ss.length(); i++) {
                JSONObject s = ss.getJSONObject(i);
                r.words.add(words(s.optString("text", "")));
                r.times.add(new double[]{s.optDouble("start", 0), s.optDouble("end", 0)});
            }
            Log.i(TAG, "book: " + file + " has the PC's recording (" + r.words.size() + " sentences)");
            return r;
        } catch (Exception e) { Log.w(TAG, "book: recording of " + file, e); return null; }
    }

    /** The reader's sentences -> the recording's, in order (each found a little ahead of the last one found). */
    private static synchronized Integer boundAt(Rec r, Chapter ch, int i) {
        if (r.boundTo != ch) {
            r.boundTo = ch; r.bound.clear();
            int s = 0;
            for (int k = 0; k < ch.size() && s < r.words.size(); k++) {
                String w = words(ch.chunks.get(k).say);
                if (w.isEmpty()) continue;
                for (int look = s; look < Math.min(r.words.size(), s + 8); look++)
                    if (r.words.get(look).equals(w)) { r.bound.put(k, look); s = look + 1; break; }
            }
            Log.i(TAG, "book: " + r.bound.size() + " of " + ch.size() + " sentences are in the PC's recording");
        }
        return r.bound.get(i);
    }

    /** Sentence i of this chapter is in a recording on this phone. */
    static boolean covers(Context ctx, Chapter ch, int i) {
        if (!BookPages.isBook(ch.url)) return false;
        Rec r = rec(ctx, ch);
        return r != null && !r.failed && boundAt(r, ch, i) != null;
    }

    /** Sentence i from the recording, or null (not in it, or the recording can't be played): the voice reads it. */
    static Voice.Clip clip(Context ctx, Chapter ch, int i) {
        if (!BookPages.isBook(ch.url)) return null;
        long t0 = System.currentTimeMillis();
        Rec r = rec(ctx, ch);
        if (r == null) return null;
        Integer at = boundAt(r, ch, i);
        if (at == null) return null;
        decode(ctx, r);
        double[] t = r.times.get(at);
        try {
            // (the decoding runs ahead from the start: wait until it has got this far)
            long until = System.currentTimeMillis() + 90_000;
            while (!r.failed && (r.rate == 0 || (!r.done && r.frames < (long) (t[1] * r.rate)))) {
                if (System.currentTimeMillis() > until) return null;
                Thread.sleep(40);
            }
            if (r.failed) return null;
            int rate = r.rate;
            long from = Math.max(0, (long) (t[0] * rate)), to = Math.min(r.frames, (long) (t[1] * rate));
            if (to <= from) return null;
            float[] out = new float[(int) (to - from)];
            byte[] b = new byte[out.length * 2];
            try (RandomAccessFile f = new RandomAccessFile(r.pcm, "r")) { f.seek(from * 2); f.readFully(b); }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < out.length; k++) out[k] = bb.getShort() / 32768f;
            return new Voice.Clip(out, rate, 1f, System.currentTimeMillis() - t0);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { Log.w(TAG, "book: reading the recording", e); return null; }
    }

    /** A book was deleted: nothing remembered or decoded from before is used again. */
    static synchronized void forget(Context ctx) {
        recs.clear(); none.clear();
        File[] fs = dir(ctx).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ---------------------------------------------------------------- the MP3, decoded once into the cache

    private static final LinkedHashMap<String, Long> made = new LinkedHashMap<>();

    private static File dir(Context ctx) { return new File(ctx.getCacheDir(), "bookaudio"); }

    private static synchronized void decode(Context ctx, Rec r) {
        if (r.started) return;
        r.started = true;
        File d = dir(ctx);
        d.mkdirs();
        String name = Integer.toHexString(r.key.hashCode()) + "-" + Integer.toHexString(r.mp3.toString().hashCode());
        r.pcm = new File(d, name + ".pcm");
        File ok = new File(d, name + ".ok");
        // (decoded before: its rate is in the .ok file)
        if (r.pcm.isFile() && ok.isFile()) {
            try {
                r.rate = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(ok.toPath())).trim());
                r.frames = r.pcm.length() / 2; r.done = true;
                return;
            } catch (Exception ignored) { }
        }
        trim(d, name);
        Thread th = new Thread(() -> {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null;
            try (FileOutputStream out = new FileOutputStream(r.pcm)) {
                ex.setDataSource(ctx, r.mp3, null);
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
                boolean inDone = false;
                long frames = 0;
                while (true) {
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(10_000);
                        if (ii >= 0) {
                            ByteBuffer in = dec.getInputBuffer(ii);
                            int n = ex.readSampleData(in, 0);
                            if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(info, 10_000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) floats = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
                        continue;
                    }
                    if (oi < 0) continue;
                    ByteBuffer o = dec.getOutputBuffer(oi);
                    if (info.size > 0 && o != null) {
                        o.position(info.offset); o.limit(info.offset + info.size);
                        o.order(ByteOrder.LITTLE_ENDIAN);
                        int per = floats ? 4 : 2;
                        int n = info.size / per / Math.max(1, channels);
                        ByteBuffer mono = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
                        for (int k = 0; k < n; k++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) sum += floats ? o.getFloat() * 32767f : o.getShort();
                            mono.putShort((short) Math.max(-32768, Math.min(32767, Math.round(sum / channels))));
                        }
                        out.write(mono.array());
                        frames += n;
                        r.rate = rate;
                        r.frames = frames;
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                out.flush();
                r.rate = rate;
                r.done = true;
                try (FileOutputStream w = new FileOutputStream(ok)) { w.write(String.valueOf(rate).getBytes()); }
                Log.i(TAG, "book: recording decoded (" + frames / Math.max(1, rate) + " s)");
            } catch (Exception e) {
                Log.w(TAG, "book: decoding the recording", e);
                r.failed = true;
            } finally {
                try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) { }
                ex.release();
            }
        }, "lb-bookaudio");
        th.setPriority(Thread.NORM_PRIORITY - 1);
        th.start();
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
                boolean inUse = false;
                for (Rec r : recs.values()) if (r.pcm != null && r.pcm.equals(fs[i]) && !r.done) inUse = true;
                if (inUse) continue;
                for (Rec r : recs.values()) if (r.pcm != null && r.pcm.equals(fs[i])) { r.started = false; r.done = false; r.frames = 0; r.rate = 0; }
            }
            fs[i].delete();
            new File(d, base + ".ok").delete();
        }
    }

    private BookAudio() { }
}
