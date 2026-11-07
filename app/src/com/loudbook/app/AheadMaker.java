package com.loudbook.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Makes chapters' audio ahead of time on the phone ("Make audio ahead"), for a phone that can't make the voice as fast as
 * it's heard: each chapter's sentences are made with the reader's voice (Kokoro, or the PC's when paired), at the voice and
 * speed set now, into one small file (AAC, about 20 MB an hour) with where each sentence is. When the chapter is read, its
 * sentences come from that file (BookAudio), straight away. A sentence that changed since, or a different voice, is made
 * live as usual.
 *
 * Two ways in: a whole book from the Audiobooks page (its chapters in order), or, with the setting on, the next few chapters
 * after the one being read (following each chapter's "next"). One chapter at a time, one sentence at a time on the reader's
 * own voice thread, so reading always comes first.
 */
final class AheadMaker {
    static final String TAG = "Loudbook";
    static final int RATE = 24000;

    private final ReaderService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Object[]> todo = new ArrayDeque<>();    // {url, how many more to follow after it}
    private Thread worker;
    private volatile boolean stop;
    private volatile String line = "";
    private volatile String book = "";
    private ChapterFetcher fetcher;
    private int done, total;

    AheadMaker(ReaderService svc) { this.svc = svc; }

    static File dir(Context ctx) { return new File(ctx.getExternalFilesDir(null), "made"); }
    static String key(String url) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest(url.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static File audioFile(Context ctx, String url) { return new File(dir(ctx), key(url) + ".m4a"); }
    static File infoFile(Context ctx, String url) { return new File(dir(ctx), key(url) + ".json"); }

    /** Made already, with this voice and speed? */
    static boolean have(Context ctx, String url, int sid, float speed) {
        File j = infoFile(ctx, url);
        if (!j.isFile() || !audioFile(ctx, url).isFile()) return false;
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
            return o.optInt("sid", -1) == sid && Math.abs(o.optDouble("speed", 1) - speed) < 0.01;
        } catch (Exception e) { return false; }
    }

    /** The chapters made for these addresses go (a book deleted from the phone). */
    static void delete(Context ctx, List<String> urls) {
        for (String u : urls) { audioFile(ctx, u).delete(); infoFile(ctx, u).delete(); }
    }

    // ---------------------------------------------------------------- what's asked for

    /** A whole book's chapters, in order (replaces what was waiting). */
    synchronized void book(String name, List<String> urls) {
        todo.clear();
        for (String u : urls) todo.add(new Object[]{u, 0});
        book = name; done = 0; total = urls.size();
        start();
    }

    /** The next `count` chapters after this one (the setting): added if nothing else is being made. */
    synchronized void after(Chapter ch, int count) {
        if (ch == null || ch.nextUrl == null || count <= 0) return;
        if (worker != null && worker.isAlive()) return;
        todo.clear();
        todo.add(new Object[]{ch.nextUrl, count - 1});
        book = ""; done = 0; total = count;
        start();
    }

    synchronized void cancel() { todo.clear(); stop = true; line = "Stopped."; }
    boolean busy() { Thread t = worker; return t != null && t.isAlive(); }
    String line() { return line; }
    String bookName() { return book; }

    private void start() {
        stop = false;
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::run, "lb-ahead");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    // ---------------------------------------------------------------- making

    private void run() {
        PowerManager.WakeLock wake = ((PowerManager) svc.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loudbook:ahead");
        try {
            wake.acquire(12 * 3600_000L);
            main.post(svc::holdForeground);
            while (!stop) {
                Object[] next;
                synchronized (this) { next = todo.poll(); }
                if (next == null) break;
                String url = (String) next[0]; int more = (Integer) next[1];
                Chapter ch = load(url);
                if (ch == null) { line = "Couldn't open a chapter to make it ahead (" + url + ")."; continue; }
                done++;
                int sid = svc.voiceId(); float speed = svc.speed();
                if (!have(svc, url, sid, speed)) makeChapter(ch, sid, speed);
                if (stop) break;
                if (more > 0 && ch.nextUrl != null) synchronized (this) { todo.addFirst(new Object[]{ch.nextUrl, more - 1}); }
            }
            if (!stop) line = total > 0 ? "Made ahead: " + Math.min(done, total) + " chapter" + (total == 1 ? "" : "s") + (book.isEmpty() ? "" : " of " + book) + ". They play straight away." : "";
        } catch (Throwable t) {
            Log.w(TAG, "ahead", t);
            line = "Making audio ahead stopped: " + t.getMessage();
        } finally {
            try { if (wake.isHeld()) wake.release(); } catch (Exception ignored) { }
            main.post(() -> { if (fetcher != null) { fetcher = null; } });
        }
    }

    /** The chapter as the reader would read it (its page, with the same page script and pronunciation fixes). */
    private Chapter load(String url) throws InterruptedException {
        final Chapter[] got = new Chapter[1];
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> {
            if (fetcher == null) fetcher = new ChapterFetcher(svc);
            fetcher.get(url, (c, err) -> { got[0] = c; l.countDown(); });
        });
        l.await(90, java.util.concurrent.TimeUnit.SECONDS);
        return got[0];
    }

    private void makeChapter(Chapter ch, int sid, float speed) throws Exception {
        File d = dir(svc);
        d.mkdirs();
        File part = new File(d, key(ch.url) + ".part.m4a");
        JSONArray marks = new JSONArray();
        long t0 = System.currentTimeMillis();
        try (Aac out = new Aac(part)) {
            short[] pad = new short[(int) (0.12 * RATE)];
            for (int i = 0; i < ch.size() && !stop; i++) {
                if (svc.voiceId() != sid || Math.abs(svc.speed() - speed) > 0.01) { line = "The voice or speed changed: making it again."; return; }
                Chapter.Chunk c = ch.chunks.get(i);
                line = "Making ahead" + (book.isEmpty() ? "" : " (" + book + ")") + ": chapter " + done + " of " + total + ", " + ch.title
                    + " — sentence " + (i + 1) + " of " + ch.size();
                Voice.Clip clip = svc.makeAhead(ch, i, sid, speed);
                if (clip == null || Voice.silent(clip)) continue;
                short[] s = toRate(clip.samples, clip.rate);
                out.write(pad);
                double start = out.frames / (double) RATE;
                out.write(s);
                double end = out.frames / (double) RATE;
                out.write(pad);
                // (a little of the pause on each side: the decoder may start a few hundredths of a second later)
                marks.put(new JSONObject().put("text", c.say).put("start", Math.max(0, start - 0.08)).put("end", end + 0.08));
            }
        }
        if (stop) { part.delete(); return; }
        JSONObject o = new JSONObject().put("url", ch.url).put("title", ch.title).put("sid", sid).put("speed", (double) speed)
            .put("rate", RATE).put("sentences", marks);
        File info = infoFile(svc, ch.url), audio = audioFile(svc, ch.url);
        java.nio.file.Files.write(info.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        audio.delete();
        if (!part.renameTo(audio)) throw new java.io.IOException("couldn't keep " + ch.title);
        BookAudio.forget(ch.url);
        Log.i(TAG, "ahead: " + ch.title + " made (" + ch.size() + " sentences in " + (System.currentTimeMillis() - t0) / 1000 + " s)");
        trim(d);
    }

    /** Float samples at any rate -> 16-bit at 24 kHz. */
    private static short[] toRate(float[] x, int rate) {
        int n = rate == RATE ? x.length : (int) ((long) x.length * RATE / rate);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float v;
            if (rate == RATE) v = x[i];
            else {
                double p = (double) i * rate / RATE; int a = (int) p; double f = p - a;
                v = (float) (x[Math.min(a, x.length - 1)] * (1 - f) + x[Math.min(a + 1, x.length - 1)] * f);
            }
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
        }
        return out;
    }

    /** At most 4 GB of made chapters: the oldest go first. */
    private static void trim(File d) {
        File[] fs = d.listFiles((x, n) -> n.endsWith(".m4a") && !n.contains(".part"));
        if (fs == null) return;
        long sum = 0;
        for (File f : fs) sum += f.length();
        if (sum < 4L << 30) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) {
            if (sum < 3L << 30) break;
            sum -= f.length();
            f.delete();
            new File(d, f.getName().replaceFirst("\\.m4a$", ".json")).delete();
        }
    }

    /** 16-bit mono PCM at 24 kHz -> AAC in an .m4a. */
    static final class Aac implements AutoCloseable {
        private final MediaCodec enc;
        private final MediaMuxer mux;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int track = -1;
        long frames;

        Aac(File f) throws Exception {
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 48000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(f.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void write(short[] s) {
            int off = 0;
            while (off < s.length) {
                int ii = enc.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer in = enc.getInputBuffer(ii);
                    in.clear();
                    int n = Math.min(s.length - off, in.remaining() / 2);
                    ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int k = 0; k < n; k++) b.putShort(s[off + k]);
                    in.put(b.array());
                    enc.queueInputBuffer(ii, 0, n * 2, frames * 1_000_000L / RATE, 0);
                    frames += n; off += n;
                }
                drain(false);
            }
        }

        private void drain(boolean end) {
            int waits = 0;
            while (true) {
                int oi = enc.dequeueOutputBuffer(info, end ? 10_000 : 0);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || ++waits > 300) return; continue; }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.getOutputFormat()); mux.start(); continue; }
                if (oi < 0) continue;
                ByteBuffer o = enc.getOutputBuffer(oi);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && track >= 0 && o != null) { o.position(info.offset); o.limit(info.offset + info.size); mux.writeSampleData(track, o, info); }
                enc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        @Override public void close() {
            try {
                int ii;
                while ((ii = enc.dequeueInputBuffer(10_000)) < 0) drain(false);
                enc.queueInputBuffer(ii, 0, 0, frames * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
            } catch (Exception e) { Log.w(TAG, "ahead: finishing the file", e); }
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            try { if (track >= 0) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }
}
