package com.loudbook.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One chapter read by Gemini: the chapter goes up in a single request (the free tier allows only a
 * few a day), the recording is cut into its sentences so highlighting works like with every other
 * voice, and it's kept on disk: going back, replaying, or opening the chapter again later costs
 * nothing. If Google stops a long recording early, the rest is asked for in a second request.
 *
 * Sentences are filed by their text, not just their number, so a page that comes back slightly
 * different (an extra line somewhere) still finds its recording.
 */
final class GeminiBook {
    static final class Seg {
        final int idx; final int hash; final int part; volatile long start, end; boolean joined;
        Seg(int idx, int hash, int part, long s, long e, boolean j) { this.idx = idx; this.hash = hash; this.part = part; start = s; end = e; joined = j; }
    }

    final File dir;
    private final List<Seg> all = new ArrayList<>();                   // in chapter order
    private final List<CloudVoice.Pcm> parts = new ArrayList<>();
    private final Map<Integer, Seg> bound = new HashMap<>();           // chapter sentence -> recording
    private final java.util.Set<Integer> issued = new java.util.HashSet<>();   // handed out to play (timing frozen)
    private Chapter boundTo;
    private int version;                                                // 2: timed with the passed-over instructions in mind
    static final int VERSION = 2;

    private GeminiBook(File dir) { this.dir = dir; }

    /** The chapter's key: the page and the voice settings (a different voice is a new recording). */
    static String key(String url, String voice, String model, String style) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            d.update((url + "|" + voice + "|" + model + "|" + style).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            return sb.substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final java.util.LinkedHashMap<String, GeminiBook> live = new java.util.LinkedHashMap<>();

    /** The same object for the same chapter, so nothing recorded is ever missed. */
    static synchronized GeminiBook open(File root, String key) {
        GeminiBook b = live.get(key);
        if (b != null && (b.all.isEmpty() || b.dir.isDirectory())) return b;
        b = new GeminiBook(new File(root, key));
        b.load();
        live.put(key, b);
        while (live.size() > 4) live.remove(live.keySet().iterator().next());
        return b;
    }

    static int hash(Chapter.Chunk c) { return c.say.trim().hashCode(); }

    /** Matches the recording to this version of the chapter's text. */
    synchronized void bind(Chapter ch) {
        if (boundTo == ch) return;
        boundTo = ch;
        bound.clear();
        int s = 0;
        for (int i = 0; i < ch.size() && s < all.size(); i++) {
            int h = hash(ch.chunks.get(i));
            for (int look = s; look < Math.min(all.size(), s + 6); look++)
                if (all.get(look).hash == h) { bound.put(i, all.get(look)); s = look + 1; break; }
        }
    }

    /** How long a recording is, in seconds. */
    synchronized double seconds(int part) { CloudVoice.Pcm p = parts.get(part); return p.samples() / (double) p.rate; }

    /**
     * Files sentences recorded in another chapter's request (packed in at its end): samples
     * [a, b) of that recording are copied here; local[k] is where sentence from+k starts in them.
     */
    void addFrom(CloudVoice.Pcm src, long a, long b, Chapter ch, int from, long[] local) throws IOException {
        dir.mkdirs();
        File f;
        synchronized (this) { f = new File(dir, "part" + parts.size() + "-" + System.currentTimeMillis() + ".pcm"); }
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(src.file, "r");
             java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f), 1 << 16)) {
            in.seek(src.offset + a * 2);
            byte[] buf = new byte[1 << 16];
            long left = (b - a) * 2;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        CloudVoice.Pcm p = new CloudVoice.Pcm(f, 0, f.length() & ~1L, src.rate);
        int n = local.length - 1;
        synchronized (this) {
            if (all.isEmpty()) version = VERSION;
            int part = parts.size();
            parts.add(p);
            for (int k = 0; k < n; k++)
                all.add(new Seg(from + k, hash(ch.chunks.get(from + k)), part, local[k], Math.min(local[k + 1], p.samples()), k < n - 1));
            all.sort((x, y) -> Integer.compare(x.idx, y.idx));
            boundTo = null; bind(ch);
            save();
        }
    }

    /** How long sentence i's recording is (seconds), 0 if it has none. */
    synchronized double secondsOf(int i) { Seg s = bound.get(i); return s == null ? 0 : (s.end - s.start) / (double) parts.get(s.part).rate; }

    /** The first sentence from i on that isn't recorded yet (or n). */
    synchronized int nextMissing(int i, int n) { for (int k = i; k < n; k++) if (!bound.containsKey(k)) return k; return n; }

    /** Where sentence i starts in its recording (samples), or -1. */
    synchronized long startOf(int i) { Seg s = bound.get(i); return s == null ? -1 : s.start; }

    synchronized boolean has(int i) { return bound.containsKey(i); }
    synchronized boolean joined(int i) { Seg s = bound.get(i); return s != null && s.joined && bound.containsKey(i + 1); }
    synchronized int recorded() { return all.size(); }

    /** The first sentence from i on that's already recorded (or n). */
    synchronized int nextHave(int i, int n) { for (int k = i; k < n; k++) if (bound.containsKey(k)) return k; return n; }
    /** The first sentence that's recorded at all (or -1). */
    synchronized int firstHave(int n) { for (int k = 0; k < n; k++) if (bound.containsKey(k)) return k; return -1; }

    synchronized Voice.Clip clip(int i) throws IOException {
        Seg s = bound.get(i);
        issued.add(s.idx);
        CloudVoice.Pcm p = parts.get(s.part);
        return new Voice.Clip(p.read(s.start, (int) (s.end - s.start)), p.rate, 1f, 0);
    }

    /**
     * Recordings made before sentence timing allowed for Gemini reading its instructions out: each
     * part, once, to be timed again by the recogniser (it costs no request).
     */
    synchronized List<Made> needsRetiming() {
        List<Made> out = new ArrayList<>();
        if (version >= VERSION) return out;
        version = VERSION;
        for (int p = 0; p < parts.size(); p++) {
            Made m = new Made(); m.part = p; m.from = Integer.MAX_VALUE;
            for (Seg s : all) if (s.part == p) { m.from = Math.min(m.from, s.idx); m.count++; }
            if (m.count > 0) out.add(m);
        }
        if (!all.isEmpty()) save();
        return out;
    }

    /** A recording just made, for sentence timing. timed: its sentences were already timed by the words heard. */
    static final class Made {
        int part; int from; int count; boolean timed; boolean streamed;
        int asked;            // sentences asked for (all chapters)
        int chars;            // letters of the ones recorded
        int extra;            // of them, in the chapters after the first
        double seconds;       // how long the recording is
        long firstAudioMs = -1, totalMs;   // when its first audio arrived, and when it was all in (from asking)
        long firstReadyMs = -1;            // when its first sentence was ready to play
        boolean broke;                     // the connection broke part way (not Google stopping early)
    }

    /** Part of a request: sentences [from, to) of a chapter, filed in that chapter's book. */
    static final class Span {
        final Chapter ch; final GeminiBook book; final int from, to;
        Span(Chapter ch, GeminiBook book, int from, int to) { this.ch = ch; this.book = book; this.from = from; this.to = to; }
    }

    /**
     * Asks Google for sentences [from, to) in one request, and files what comes back. paceOut[0]:
     * the reader's pace (0 if the recording was cut short).
     *
     * With the recogniser (asr, loaded) the audio is asked for as a stream and listened to as it
     * arrives: each sentence is filed, ready to play, as soon as the first words of the one after
     * it are heard, so reading starts within seconds instead of after the whole chapter is made.
     * Its timing comes from the words themselves, so it can't be a sentence off.
     */
    Made fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut) throws IOException {
        return fetch(ch, from, to, key, model, voice, style, pace, paceOut, null);
    }

    Made fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut, AsrAligner asr) throws IOException {
        return fetch(ch, from, to, key, model, voice, style, pace, paceOut, asr, new ArrayList<>());
    }

    /**
     * more: the next chapters' sentences, packed into the same request when this chapter's part
     * is shorter than a request can hold (nothing of a request goes unused). Their recording is
     * filed in their own books once it's complete.
     */
    Made fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut, AsrAligner asr,
               List<Span> more) throws IOException {
        return fetch(ch, from, to, key, model, voice, style, pace, paceOut, asr, more, true);
    }

    /**
     * stream: ask for the audio as it's made. Either way, with the recogniser the words are
     * listened to as the audio comes in, and each sentence is timed by its own first words before
     * it's handed out to play (a recording that comes all at once is listened to straight away,
     * much faster than it plays, so reading starts in a moment).
     */
    Made fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut, AsrAligner asr,
               List<Span> more, boolean stream) throws IOException {
        StringBuilder text = new StringBuilder();
        List<String> texts = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(ch, this, from, to));
        spans.addAll(more);
        for (Span sp : spans) {
            if (text.length() > 0) text.append("\n\n");
            for (int k = sp.from; k < sp.to; k++) {
                Chapter.Chunk c = sp.ch.chunks.get(k);
                if (k > sp.from) text.append(c.block != sp.ch.chunks.get(k - 1).block ? "\n\n" : " ");
                text.append(c.say);
                texts.add(c.say);
            }
        }
        int[] chars = new int[texts.size()];
        for (int k = 0; k < chars.length; k++) chars[k] = texts.get(k).length();
        int n0 = to - from;                                       // (this chapter's part)
        dir.mkdirs();
        int partNo;
        File f;
        synchronized (this) {
            partNo = parts.size();
            f = new File(dir, "part" + partNo + "-" + System.currentTimeMillis() + ".pcm");
            parts.add(new CloudVoice.Pcm(f, 0, 0, 24000));             // (its place, while it arrives)
        }
        Follow fo = asr != null && asr.loaded() ? new Follow(ch, from, texts, n0, partNo, f, asr) : null;
        if (fo != null) fo.start();
        CloudVoice.Pcm pcm;
        final long t0 = System.currentTimeMillis();
        final long[] firstAudio = {-1};
        CloudVoice.Progress prog = (bytes) -> {
            if (firstAudio[0] < 0 && bytes > 0) firstAudio[0] = System.currentTimeMillis() - t0;
            if (fo != null) fo.arrived(bytes);
        };
        boolean broke = false;
        try {
            pcm = CloudVoice.speakToFile(key, model, voice, style, text.toString(), f, 600_000, fo != null && stream, prog);
        } catch (IOException e) {
            if (fo != null) fo.finish();
            if (fo != null && fo.filed > 0) {
                broke = true;
                // the connection broke part way: keep what was heard (the rest is asked for again)
                pcm = new CloudVoice.Pcm(f, 0, fo.bytes & ~1L, 24000);
            } else {
                synchronized (this) { if (parts.size() == partNo + 1) parts.remove(partNo); }
                f.delete();
                throw e;
            }
        }
        if (fo != null) fo.finish();
        long total = pcm.samples(), minGap = pcm.rate / 5;
        int filed = fo == null ? 0 : fo.filed;
        int count;
        long[] starts;
        if (fo == null) {
            // no recogniser: the cuts go at the pauses that fit the sentence lengths best
            Align.Result r = Align.align(pcm, chars, pace, CloudVoice.preambleChars(style));
            count = r.count;
            if (paceOut != null) paceOut[0] = r.cutShort ? 0 : r.secPerChar;
            starts = new long[count + 1];
            for (int k = 0; k < count; k++) starts[k] = Math.max(k == 0 ? 0 : starts[k - 1] + 1, r.start[k]);
            starts[count] = count == 0 ? 0 : r.cutShort ? r.end[count - 1] : total;
        } else {
            // the words heard say how far the recording got and where every sentence starts. (The
            // pause fit isn't used here: on a long chapter it can lose its place, and it used to
            // decide how many sentences there were, so the end of every recording was dropped and
            // asked for again, as if Google had stopped early.)
            int n = texts.size();
            double[] st = AsrAligner.sentenceStarts(fo.words, texts);
            int lastHeard = -1;
            for (int k = 0; k < n; k++) if (!Double.isNaN(st[k])) lastHeard = k;
            boolean ended = lastHeard >= 0 && !broke && AsrAligner.endHeard(fo.words, texts.get(lastHeard), st[lastHeard], total / (double) pcm.rate);
            count = Math.max(filed, lastHeard < 0 ? 0 : lastHeard + (ended ? 1 : 0));
            if (count > 0) {
                int m1 = Math.min(n, count + 1);
                double[] st1 = java.util.Arrays.copyOf(st, m1);
                long[] guess = new long[m1];
                double spc = AsrAligner.pace(st, texts);
                double at = Double.isNaN(st[0]) ? 0 : st[0];
                for (int k = 0; k < m1; k++) { guess[k] = Math.round(at * pcm.rate); at += Math.max(1, texts.get(k).length()) * spc; }
                long[] byWords = AsrAligner.cuts(st1, texts.subList(0, m1), Align.pausesOf(fo.rms()), pcm.rate, guess);
                if (paceOut != null) paceOut[0] = count == n ? spc : 0;
                starts = new long[count + 1];
                for (int k = 0; k <= count; k++) {
                    if (k < filed) { starts[k] = fo.starts.get(k); continue; }
                    if (k == filed && filed > 0) { starts[k] = fo.lastEnd; continue; }
                    if (k == count) { starts[k] = count == lastHeard + 1 ? total : byWords[k] >= 0 ? byWords[k] : total; break; }
                    starts[k] = k < byWords.length ? byWords[k] : -1;
                    if (k == 0 && starts[k] < 0) starts[k] = 0;
                }
                // any not placed (or out of order): shared out by length between the ones around them
                for (int k = Math.max(1, filed + 1); k < count; k++) {
                    if (starts[k] > starts[k - 1] + minGap / 2) continue;
                    int b = k + 1;
                    while (b < count && starts[b] <= starts[k - 1] + minGap / 2) b++;
                    long hi = starts[b];
                    double tot = 0, upto = 0;
                    for (int q = k - 1; q < b; q++) { double l = Math.max(1, texts.get(q).length()); tot += l; if (q < k) upto += l; }
                    starts[k] = starts[k - 1] + Math.round((hi - starts[k - 1]) * upto / tot);
                }
                for (int k = 1; k <= count; k++) starts[k] = Math.max(starts[k], starts[k - 1] + 1);
                starts[count] = Math.min(total, Math.max(starts[count], starts[count - 1] + 1));
            } else starts = new long[]{0};
        }
        if (count == 0) {
            synchronized (this) { if (parts.size() == partNo + 1) parts.remove(partNo); }
            throw new IOException("Gemini's recording didn't match the chapter text");
        }
        Made m = new Made();
        m.firstAudioMs = firstAudio[0];
        m.broke = broke;
        m.totalMs = System.currentTimeMillis() - t0;
        m.firstReadyMs = fo != null && fo.firstFiledAt > 0 ? fo.firstFiledAt - t0 : m.totalMs;
        int count0 = Math.min(count, n0);
        m.asked = texts.size(); m.seconds = total / (double) pcm.rate;
        for (int k = 0; k < count; k++) m.chars += chars[k];
        m.extra = count - count0;
        synchronized (this) {
            m.part = partNo; m.from = from; m.count = count0; m.timed = fo != null; m.streamed = CloudVoice.lastWay == 0 || CloudVoice.lastWay == 3;
            boolean onlyThis = true;
            for (Seg sg : all) if (sg.part != partNo) onlyThis = false;
            if (onlyThis) version = VERSION;                                   // (a new recording is timed the new way)
            parts.set(partNo, pcm);
            for (int k = filed; k < count0; k++)
                all.add(new Seg(from + k, hash(ch.chunks.get(from + k)), partNo, starts[k], starts[k + 1], k < count0 - 1));
            // (the ones filed while it arrived: all but the last are followed by the next in the same recording)
            for (Seg sg : all) if (sg.part == partNo) sg.joined = sg.idx < from + count0 - 1;
            all.sort((a, b) -> Integer.compare(a.idx, b.idx));
            boundTo = null; bind(ch);
            save();
        }
        // the next chapters' part of the recording goes to their own books
        int g = n0;
        for (int si = 1; si < spans.size(); si++) {
            Span sp = spans.get(si);
            int g0 = g, g1 = g + (sp.to - sp.from);
            g = g1;
            if (count <= g0) break;
            int c1 = Math.min(count, g1);
            long[] local = new long[c1 - g0 + 1];
            for (int k = g0; k <= c1; k++) local[k - g0] = starts[k] - starts[g0];
            try { sp.book.addFrom(pcm, starts[g0], starts[c1], sp.ch, sp.from, local); }
            catch (IOException e) { System.err.println("Loudbook: next chapter's recording: " + e); }
        }
        return m;
    }

    /**
     * Listens to a recording as it arrives and files each sentence once the next one is heard
     * starting. Runs on its own thread; the download goes on undisturbed.
     */
    private final class Follow implements Runnable {
        final Chapter ch; final int from; final List<String> texts; final int limit, part; final File f;
        final AsrAligner.Live live;
        final int rate = 24000, frame = rate * Align.FRAME_MS / 1000;
        volatile long bytes;
        volatile boolean ended;
        private final Object lock = new Object();
        private Thread thread;
        long fed;                                          // samples listened to
        float[] rms = new float[4096]; int nf;             // loudness every 20 ms
        private double acc; private int accN;
        int filed;                                         // sentences filed so far
        final List<Long> starts = new ArrayList<>();
        long lastEnd;
        volatile long firstFiledAt;
        List<AsrAligner.Word> words = new ArrayList<>();

        Follow(Chapter ch, int from, List<String> texts, int limit, int part, File f, AsrAligner asr) {
            this.ch = ch; this.from = from; this.texts = texts; this.limit = limit; this.part = part; this.f = f;
            live = asr.new Live(rate);
        }
        void start() { thread = new Thread(this, "lb-gemini-follow"); thread.start(); }
        void arrived(long b) { bytes = b; synchronized (lock) { lock.notifyAll(); } }
        /** The recording is complete (or stopped): finish listening, at most a little while longer. */
        void finish() {
            ended = true;
            synchronized (lock) { lock.notifyAll(); }
            try { thread.join(180_000); } catch (InterruptedException ignored) { }
            synchronized (GeminiBook.this) { closed = true; }   // (from here on the rest is filed by fetch, not here)
        }
        volatile boolean closed;
        float[] rms() { return java.util.Arrays.copyOf(rms, nf); }

        @Override public void run() {
            long lastLook = 0;
            try {
                while (true) {
                    long have = bytes / 2;
                    if (have > fed) {
                        int n = (int) Math.min(rate / 2, have - fed);
                        float[] x = new CloudVoice.Pcm(f, 0, have * 2, rate).read(fed, n);
                        live.feed(x);
                        for (float v : x) {
                            acc += v * v;
                            if (++accN == frame) {
                                if (nf == rms.length) rms = java.util.Arrays.copyOf(rms, nf * 2);
                                rms[nf++] = (float) Math.sqrt(acc / frame); acc = 0; accN = 0;
                            }
                        }
                        fed += x.length;
                        if (x.length == 0) break;
                    }
                    boolean caughtUp = fed >= bytes / 2;
                    if (fed - lastLook >= rate * 3 / 2 || caughtUp && ended) { lastLook = fed; look(); }
                    if (caughtUp && ended) break;
                    if (caughtUp) synchronized (lock) { if (bytes / 2 <= fed && !ended) lock.wait(200); }
                }
                live.finish();
                words = live.words();
            } catch (Throwable t) {
                System.err.println("Loudbook: gemini follow: " + t);
            } finally { live.release(); }
        }

        /** Files every sentence whose end is known now (the next one's first words were heard). */
        private void look() {
            words = live.words();
            double[] st = AsrAligner.sentenceStarts(words, texts);
            int last = -1;
            for (int k = 0; k < st.length; k++) if (!Double.isNaN(st[k])) last = k;
            if (last < 1 || last <= filed || filed >= limit) return;
            long[] cut = AsrAligner.cuts(java.util.Arrays.copyOf(st, last + 1), texts.subList(0, last + 1), Align.pausesOf(rms()), rate, new long[last + 1]);
            if (filed == 0 && cut[0] < 0) cut[0] = 0;                      // (the first words weren't made out: it starts at the start)
            if (filed > 0) cut[filed] = lastEnd;
            List<Seg> add = new ArrayList<>();
            List<Long> st2 = new ArrayList<>();
            long end = lastEnd;
            int k = filed;
            while (k < last && k < limit && cut[k] >= 0 && cut[k + 1] > cut[k] + rate / 5) {
                add.add(new Seg(from + k, hash(ch.chunks.get(from + k)), part, cut[k], cut[k + 1], true));
                st2.add(cut[k]);
                end = cut[k + 1];
                k++;
            }
            if (add.isEmpty()) return;
            synchronized (GeminiBook.this) {
                if (closed) return;
                if (filed == 0) firstFiledAt = System.currentTimeMillis();
                starts.addAll(st2); lastEnd = end; filed = k;
                parts.set(part, new CloudVoice.Pcm(f, 0, (fed * 2), rate));
                all.addAll(add);
                all.sort((a, b) -> Integer.compare(a.idx, b.idx));
                boundTo = null; bind(ch);
            }
        }
    }

    /**
     * Exact timing: the recogniser hears the words, and each sentence's cut moves to where its
     * first word starts (into the pause just before it). The whole recording is listened to, so
     * anything Gemini said before the text (its instructions, read out by mistake) is passed over
     * and the first sentence starts at its own first word. When most sentences are heard, all the
     * cuts are rebuilt from the words, which also fixes timing that was a whole sentence off.
     * Sentences already handed out to play keep theirs, so nothing is heard twice or skipped.
     */
    void refine(Chapter ch, Made m, AsrAligner asr) throws IOException {
        CloudVoice.Pcm pcm;
        List<Seg> segs = new ArrayList<>();
        synchronized (this) {
            pcm = parts.get(m.part);
            for (Seg s : all) if (s.part == m.part) segs.add(s);
        }
        if (segs.isEmpty()) return;
        long to = segs.get(segs.size() - 1).end;
        List<AsrAligner.Word> heard = asr.words(pcm, 0, to);
        List<String> texts = new ArrayList<>();
        for (Seg s : segs) texts.add(ch.chunks.get(Math.min(s.idx, ch.size() - 1)).say);
        double[] st = AsrAligner.sentenceStarts(heard, texts);
        long[] old = new long[segs.size()];
        synchronized (this) { for (int k = 0; k < old.length; k++) old[k] = segs.get(k).start; }
        long[] cut = AsrAligner.cuts(st, texts, Align.pauses(pcm), pcm.rate, old);
        int moved = 0, n = segs.size();
        long gap = pcm.rate / 5;
        synchronized (this) {
            for (int k = 0; k < n; k++) {
                long at = cut[k];
                if (at < 0) continue;
                Seg cur = segs.get(k);
                if (issued.contains(cur.idx)) continue;
                // the end of this sentence: the next cut if that one will be made, else as it is
                long limit = cur.end;
                if (k + 1 < n && cut[k + 1] >= 0 && !issued.contains(segs.get(k + 1).idx)) limit = Math.max(limit, cut[k + 1]);
                if (at >= limit - gap) continue;
                if (k == 0) {
                    // the first sentence: where its first word is (after anything said before it)
                    if (Math.abs(at - cur.start) > pcm.rate / 20) moved++;
                    cur.start = at;
                    continue;
                }
                Seg prev = segs.get(k - 1);
                if (issued.contains(prev.idx) || at <= prev.start + gap) continue;
                if (Math.abs(at - cur.start) > pcm.rate / 20) moved++;
                prev.end = at; cur.start = at;
            }
            save();
        }
        lastRefined = moved;
    }

    volatile int lastRefined;

    // ---------------------------------------------------------------- on disk
    private void save() {
        StringBuilder sb = new StringBuilder("{\"v\":" + version + ",\"parts\":[");
        for (int p = 0; p < parts.size(); p++) {
            CloudVoice.Pcm x = parts.get(p);
            if (p > 0) sb.append(',');
            sb.append('[').append(PcLink.quote(x.file.getName())).append(',').append(x.offset).append(',').append(x.bytes).append(',').append(x.rate).append(']');
        }
        sb.append("],\"segs\":[");
        for (int k = 0; k < all.size(); k++) {
            Seg s = all.get(k);
            if (k > 0) sb.append(',');
            sb.append('[').append(s.idx).append(',').append(s.hash).append(',').append(s.part).append(',').append(s.start).append(',').append(s.end).append(',').append(s.joined ? 1 : 0).append(']');
        }
        sb.append("]}");
        try {
            File tmp = new File(dir, "index.json.tmp");
            java.nio.file.Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            File idx = new File(dir, "index.json");
            if (idx.exists()) idx.delete();
            if (!tmp.renameTo(idx)) throw new IOException("rename");
        } catch (IOException ignored) { }
        dir.setLastModified(System.currentTimeMillis());
    }

    private void load() {
        File idx = new File(dir, "index.json");
        if (!idx.isFile()) return;
        try {
            String j = new String(java.nio.file.Files.readAllBytes(idx.toPath()), StandardCharsets.UTF_8);
            int cut = j.indexOf("\"segs\"");
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("^\\{\"v\":(\\d+)").matcher(j);
            version = vm.find() ? Integer.parseInt(vm.group(1)) : 0;
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\\[\"([^\"]+)\",(\\d+),(\\d+),(\\d+)\\]").matcher(j.substring(0, cut));
            while (pm.find()) {
                File f = new File(dir, pm.group(1));
                if (!f.isFile()) { parts.clear(); return; }
                parts.add(new CloudVoice.Pcm(f, Long.parseLong(pm.group(2)), Long.parseLong(pm.group(3)), Integer.parseInt(pm.group(4))));
            }
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\\[(\\d+),(-?\\d+),(\\d+),(\\d+),(\\d+),(\\d)\\]").matcher(j.substring(cut));
            while (sm.find()) {
                int part = Integer.parseInt(sm.group(3));
                if (part >= parts.size()) continue;
                all.add(new Seg(Integer.parseInt(sm.group(1)), Integer.parseInt(sm.group(2)), part,
                    Long.parseLong(sm.group(4)), Long.parseLong(sm.group(5)), sm.group(6).equals("1")));
            }
            dir.setLastModified(System.currentTimeMillis());
        } catch (Exception e) { all.clear(); parts.clear(); }
    }

    /** Keeps the recordings on the phone under a size limit, dropping the least recently heard. */
    static void trim(File root, long maxBytes) {
        File[] ds = root.listFiles();
        if (ds == null) return;
        java.util.Arrays.sort(ds, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File d : ds) {
            long n = 0;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) n += f.length();
            total += n;
            if (total > maxBytes && fs != null) { for (File f : fs) f.delete(); d.delete(); }
        }
    }
}
