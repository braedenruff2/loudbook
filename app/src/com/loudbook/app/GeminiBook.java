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
        // how its start was found (for the timing report): w = its first words, heard as the audio
        // came in; W = its first words, heard once all of it was in; l = not heard, shared out by
        // length between ones that were; a = by the pauses alone; m = moved by hand; ? = from before
        char how = '?';
        Seg(int idx, int hash, int part, long s, long e, boolean j) { this.idx = idx; this.hash = hash; this.part = part; start = s; end = e; joined = j; }
        Seg how(char c) { how = c; return this; }
    }

    final File dir;
    private final List<Seg> all = new ArrayList<>();                   // in chapter order
    private final List<CloudVoice.Pcm> parts = new ArrayList<>();
    private final Map<Integer, Seg> bound = new HashMap<>();           // chapter sentence -> recording
    private final java.util.Set<Integer> issued = new java.util.HashSet<>();   // handed out to play (timing frozen)
    private Chapter boundTo;
    /** What the recogniser heard in each recording (this session only), for the timing report. */
    private final Map<Integer, List<AsrAligner.Word>> heardIn = new HashMap<>();
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
    void addFrom(CloudVoice.Pcm src, long a, long b, Chapter ch, int from, long[] local) throws IOException { addFrom(src, a, b, ch, from, local, null, null); }

    /** words: what was heard in the source recording (seconds from its start); how: each sentence's timing (see Seg.how). */
    void addFrom(CloudVoice.Pcm src, long a, long b, Chapter ch, int from, long[] local, List<AsrAligner.Word> words, char[] how) throws IOException {
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
                all.add(new Seg(from + k, hash(ch.chunks.get(from + k)), part, local[k], Math.min(local[k + 1], p.samples()), k < n - 1)
                    .how(how != null && k < how.length ? how[k] : 'p'));
            if (words != null) {
                List<AsrAligner.Word> mine = new ArrayList<>();
                double t0 = a / (double) src.rate, t1 = b / (double) src.rate;
                for (AsrAligner.Word w : words) if (w.t >= t0 && w.t < t1) mine.add(new AsrAligner.Word(w.w, w.t - t0));
                heardIn.put(part, mine);
            }
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

    /**
     * For the timing report: where sentence i is in its recording, how that was found, and the
     * words the recogniser heard there (what Gemini really said at that place), e.g.
     * "part 0, 12.3-15.8 s, by its words; heard: they all studied this place".
     */
    synchronized String describe(int i) {
        Seg s = bound.get(i);
        if (s == null) return "not recorded";
        int rate = parts.get(s.part).rate;
        double a = s.start / (double) rate, b = s.end / (double) rate;
        String how = s.how == 'w' ? "by its words as it arrived" : s.how == 'W' ? "by its words" : s.how == 'l' ? "by length (its start wasn't heard)"
            : s.how == 'a' ? "by the pauses" : s.how == 'm' ? "moved by hand" : s.how == 'p' ? "packed in another chapter's request" : "from an earlier session";
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.US, "part %d, %.1f-%.1f s, %s", s.part, a, b, how));
        List<AsrAligner.Word> ws = heardIn.get(s.part);
        if (ws != null) {
            StringBuilder h = new StringBuilder();
            int n = 0;
            for (AsrAligner.Word w : ws) if (w.t >= a - 0.1 && w.t < b - 0.1 && n++ < 7) h.append(n > 1 ? " " : "").append(w.w.toLowerCase(java.util.Locale.US));
            sb.append("; heard: ").append(n == 0 ? "(nothing)" : h + (n > 7 ? " ..." : ""));
        }
        return sb.toString();
    }

    /**
     * You moved the highlight: at sample x of sentence i's recording, the voice is really saying
     * sentence j. Re-cuts the recording around there to match: j starts at the pause just before
     * x (the tap comes a moment after the voice got there) when moving on, or just after x when
     * moving back, and the sentences squeezed or pushed by that are shared out by their lengths
     * up to the next one that still fits. Returns where j starts now (samples), or -1 if the
     * recording can't be moved there (another recording, or not recorded).
     */
    long resync(int i, int j, long x) throws IOException {
        Seg si, sj;
        CloudVoice.Pcm pcm;
        synchronized (this) {
            si = bound.get(i); sj = bound.get(j);
            if (si == null || sj == null || si.part != sj.part || i == j) return -1;
            for (int q = Math.min(i, j); q <= Math.max(i, j); q++) { Seg sq = bound.get(q); if (sq == null || sq.part != si.part) return -1; }
            pcm = parts.get(si.part);
        }
        final int target = j;
        double[][] ps = Align.pauses(pcm);              // (outside the lock: it reads the whole recording)
        int rate = pcm.rate;
        long gap = rate / 5;
        synchronized (this) {
            int lo = Math.min(i, j), hi = Math.max(i, j);
            long b;
            if (j > i) {
                // on: j started at the last pause before x (up to 4 s back), not before i's own start
                long min = si.start + gap;
                double xs = x / (double) rate;
                double w = heardAt(j, si.part, Math.max(si.start / (double) rate + 0.2, xs - 8), xs + 0.6, true);
                if (!Double.isNaN(w)) b = Math.max(min, Math.round(cutAt(w, ps) * rate));
                else {
                    // no words to go by: the clearest pause in the last 4 s (a long one, between
                    // sentences, rather than the comma just before the tap)
                    b = Math.max(min, x - rate / 2);
                    double best = -1;
                    for (double[] p : ps) {
                        double end = p[0] + p[1] / 2, d = xs - end;
                        if (d < -0.1 || d > 4 || Math.round((end - 0.1) * rate) <= min) continue;
                        double score = Math.min(p[1], 1.0) - 0.08 * Math.max(0, d);
                        if (score > best) { best = score; b = Math.round((end - 0.1) * rate); }
                    }
                }
                // i .. j-1 share what's before b (they were said under the wrong highlight)
                spread(lo, j, si.start, b);
            } else {
                // back: j goes on to the first pause after x (up to 4 s on), then i starts
                double xs = x / (double) rate;
                double w = heardAt(i, si.part, xs - 0.3, xs + 10, false);
                if (!Double.isNaN(w)) b = Math.round(cutAt(w, ps) * rate);
                else {
                    b = x + rate / 2;
                    double best = -1;
                    for (double[] p : ps) {
                        double st = p[0] - p[1] / 2, d = st - xs;
                        if (d < -0.1 || d > 4) continue;
                        double score = Math.min(p[1], 1.0) - 0.08 * Math.max(0, d);
                        if (score > best) { best = score; b = Math.round(Math.max(p[0], p[0] + p[1] / 2 - 0.15) * rate); }
                    }
                }
                b = Math.max(b, sj.start + gap);
                // the ones between j and i keep their own order inside j's new end
                if (hi - lo > 1) spread(lo, hi, sj.start, b);
                else { sj.end = b; sj.how = 'm'; }
                j = i;                                     // (from here on: i starts at b)
            }
            Seg first = bound.get(j);
            first.start = b; first.how = 'm';
            Seg prev = bound.get(j - 1);
            if (prev != null && prev.part == first.part) prev.end = b;
            // the ones after: any that now start too soon are shared out up to the next that fits
            double spc = paceOf(first.part);
            long need = b;
            int q = j + 1, stop = -1;
            for (; bound.containsKey(q) && bound.get(q).part == first.part; q++) {
                need += Math.round(0.6 * Math.max(1, textLen(q - 1)) * spc * rate);
                if (bound.get(q).start >= need) { stop = q; break; }
            }
            long endAll = stop >= 0 ? bound.get(stop).start : bound.get(q - 1).end;
            if (q - 1 > j || stop >= 0) spread(j, stop >= 0 ? stop : q, b, endAll);
            else first.end = Math.max(first.end, b + gap);
            for (int k = Math.min(lo, j); k < (stop >= 0 ? stop : q); k++) issued.remove(k);
            save();
            return bound.get(target).start;
        }
    }

    /**
     * When the recogniser heard sentence k's first words in that recording between lo and hi
     * seconds (its first two words in a row, or its first word if that's a long one): the time
     * of the match nearest the end (latest) when moving on, the earliest when moving back. NaN if
     * it wasn't heard there.
     */
    private double heardAt(int k, int part, double lo, double hi, boolean latest) {
        List<AsrAligner.Word> ws = heardIn.get(part);
        Chapter c = boundTo;
        if (ws == null || c == null || k < 0 || k >= c.size()) return Double.NaN;
        List<String> tw = new ArrayList<>();
        for (String t : c.chunks.get(k).say.split("\\s+")) { String nt = AsrAligner.norm(t); if (!nt.isEmpty()) tw.add(nt); if (tw.size() == 2) break; }
        if (tw.isEmpty()) return Double.NaN;
        double found = Double.NaN;
        for (int q = 0; q < ws.size(); q++) {
            AsrAligner.Word a = ws.get(q);
            if (a.t < lo || a.t > hi || !AsrAligner.norm(a.w).equals(tw.get(0))) continue;
            boolean ok = tw.size() < 2 ? tw.get(0).length() >= 3
                : q + 1 < ws.size() && AsrAligner.norm(ws.get(q + 1).w).equals(tw.get(1)) || tw.get(0).length() >= 5;
            if (!ok) continue;
            found = a.t;
            if (!latest) break;
        }
        return found;
    }

    /** Where to cut for a sentence whose first word was heard at t (as cuts() does): seconds. */
    private static double cutAt(double t, double[][] ps) {
        double word = t - 0.22, c = word - 0.05, best = Double.MAX_VALUE;
        for (double[] p : ps) {
            double end = p[0] + p[1] / 2;
            if (end > word + 0.25 || end < word - 0.6) continue;
            double d = Math.abs(end - word);
            if (d < best) { best = d; c = Math.max(p[0], end - 0.15); }
        }
        return Math.max(0, c);
    }

    /** Sentences [a, b) share samples [from, to) by their lengths (all in one recording). */
    private void spread(int a, int b, long from, long to) {
        double tot = 0;
        for (int k = a; k < b; k++) tot += Math.max(1, textLen(k));
        long at = from;
        double upto = 0;
        for (int k = a; k < b; k++) {
            Seg s = bound.get(k);
            upto += Math.max(1, textLen(k));
            long end = k == b - 1 ? to : from + Math.round((to - from) * upto / tot);
            s.start = at; s.end = Math.max(at + 1, end); s.how = 'm';
            at = s.end;
        }
    }

    /** A sentence's length in letters (its text as recorded). */
    private int textLen(int i) { Chapter c = boundTo; return c == null || i < 0 || i >= c.size() ? 40 : c.chunks.get(i).say.length(); }

    /** This recording's pace, seconds a letter (0.065 if it can't be told). */
    private double paceOf(int part) {
        long samples = 0; double chars = 0;
        for (Seg s : all) if (s.part == part) { samples += s.end - s.start; chars += Math.max(1, textLen(s.idx)); }
        double spc = chars > 0 ? samples / (double) parts.get(part).rate / chars : 0;
        return spc > 0.02 && spc < 0.2 ? spc : 0.065;
    }
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
        String note = "";                  // for the timing report: how the end of the recording was told
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
        Live before = streaming;
        try { return fetchNow(ch, from, to, key, model, voice, style, pace, paceOut, asr, more, stream); }
        finally { Live l = streaming; if (l != null && l != before) l.done = true; }
    }

    /**
     * A recording being streamed right now, for playing it as it arrives (from its first sentence,
     * before any sentence in it has been told apart: the highlight follows as they are).
     */
    static final class Live {
        final int from, to, part; final File f;
        volatile long bytes;            // of audio saved so far
        volatile boolean done;          // all in, and every sentence in it filed
        Live(int from, int to, int part, File f) { this.from = from; this.to = to; this.part = part; this.f = f; }
    }
    volatile Live streaming;

    /** The recording streaming in now that starts at sentence i (or null). */
    Live liveAt(int i) { Live l = streaming; return l != null && !l.done && l.from == i ? l : null; }

    /** Which recording sentence i is in (-1 if none), and where it ends there (samples). */
    synchronized int partOf(int i) { Seg s = bound.get(i); return s == null ? -1 : s.part; }
    synchronized long endOf(int i) { Seg s = bound.get(i); return s == null ? -1 : s.end; }

    private Made fetchNow(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut, AsrAligner asr,
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
        final Live lv = fo != null && stream ? new Live(from, from + n0, partNo, f) : null;
        if (lv != null) streaming = lv;
        CloudVoice.Pcm pcm;
        final long t0 = System.currentTimeMillis();
        final long[] firstAudio = {-1};
        CloudVoice.Progress prog = (bytes) -> {
            if (firstAudio[0] < 0 && bytes > 0) firstAudio[0] = System.currentTimeMillis() - t0;
            if (fo != null) fo.arrived(bytes);
            if (lv != null) lv.bytes = bytes;
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
        String note = "";
        char[] how = new char[texts.size()];
        java.util.Arrays.fill(how, fo == null ? 'a' : 'l');
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
            double secs = total / (double) pcm.rate;
            boolean ended = lastHeard >= 0 && !broke && AsrAligner.endHeard(fo.words, texts.get(lastHeard), st[lastHeard], secs);
            count = Math.max(filed, lastHeard < 0 ? 0 : lastHeard + (ended ? 1 : 0));
            for (int k = 0; k < n; k++) if (!Double.isNaN(st[k])) how[k] = 'W';
            // the last sentence or two may be said but not made out (the recogniser trails off at
            // the very end): if the recording goes on long enough after the last one heard for
            // them at this reader's pace, they're there. (Each recording used to come out one
            // sentence short this way, "Google stopped early", which cost a request for that one
            // sentence, shrank the requests and stopped streaming for the day.)
            if (ended && count < n && !broke) {
                double spc0 = AsrAligner.pace(st, texts);
                double need = 0.85 * Math.max(1, texts.get(lastHeard).length()) * spc0 + 0.3;
                int c2 = count;
                for (int k = lastHeard + 1; k < n && k < lastHeard + 3; k++) {
                    need += 0.75 * Math.max(1, texts.get(k).length()) * spc0;
                    if (secs - st[lastHeard] >= need) c2 = k + 1; else break;
                }
                if (c2 > count) note = "the last " + (c2 - count) + " not made out by the recogniser, but the recording is long enough for " + (c2 - count == 1 ? "it" : "them");
                count = c2;
            }
            if (count < n) {
                // what the report says about where it ended (to tell Google stopping from not hearing)
                List<AsrAligner.Word> ws = fo.words;
                StringBuilder tail = new StringBuilder();
                for (int k = Math.max(0, ws.size() - 6); k < ws.size(); k++) tail.append(tail.length() > 0 ? " " : "").append(ws.get(k).w.toLowerCase(java.util.Locale.US));
                String nx = texts.get(Math.min(n - 1, Math.max(0, count)));
                note = String.format(java.util.Locale.US, "last sentence heard: %d of %d, at %.1f s of %.1f s; the recogniser's last words: \"%s\"; not in it: \"%s\"",
                    lastHeard + 1, n, lastHeard >= 0 ? st[lastHeard] : 0, secs, tail, nx.length() > 50 ? nx.substring(0, 50) + "..." : nx);
            }
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
        if (count == 0 && fo != null && !broke && total > pcm.rate / 2) {
            // the recogniser made out none of it (a sentence or two too short or unclear for it:
            // "She left." heard as "she laughed"): if the recording is about as long as the text at
            // this reader's pace, cut it at its pauses by length, as without a recogniser. (This
            // used to throw the recording away, so the request was wasted and Gemini stopped for a
            // minute: sentences it had recorded already were read by the phone's voice, gemini-test
            // 2026-10-31)
            int letters = 0; for (int c : chars) letters += Math.max(1, c);
            double secs = total / (double) pcm.rate, want = letters * (pace > 0 ? pace : 0.065);
            if (secs > want * 0.5 && secs < want * 2 + 1.5) {
                Align.Result r = Align.align(pcm, chars, pace, CloudVoice.preambleChars(style));
                count = r.count;
                starts = new long[count + 1];
                for (int k = 0; k < count; k++) starts[k] = Math.max(k == 0 ? 0 : starts[k - 1] + 1, r.start[k]);
                if (count > 0) starts[count] = r.cutShort ? r.end[count - 1] : total;
                java.util.Arrays.fill(how, 'a');
                if (paceOut != null) paceOut[0] = 0;
                note = "the recogniser made out none of it: cut by length";
            }
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
        m.note = note;
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
                all.add(new Seg(from + k, hash(ch.chunks.get(from + k)), partNo, starts[k], starts[k + 1], k < count0 - 1).how(how[k]));
            if (fo != null) heardIn.put(partNo, fo.words);
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
            char[] h2 = java.util.Arrays.copyOfRange(how, g0, c1);
            try { sp.book.addFrom(pcm, starts[g0], starts[c1], sp.ch, sp.from, local, fo != null ? fo.words : null, h2); }
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
        volatile long fed;                                 // samples listened to
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
            // (as long as it keeps getting on: a long recording that came all at once takes the
            // recogniser a while; giving up after three minutes dropped the rest of a long chapter)
            try {
                long seen = -1, since = System.currentTimeMillis();
                while (thread.isAlive()) {
                    thread.join(2000);
                    if (fed != seen) { seen = fed; since = System.currentTimeMillis(); }
                    else if (System.currentTimeMillis() - since > 60_000) break;
                }
            } catch (InterruptedException ignored) { }
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
                add.add(new Seg(from + k, hash(ch.chunks.get(from + k)), part, cut[k], cut[k + 1], true).how(Double.isNaN(st[k]) ? 'l' : 'w'));
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
