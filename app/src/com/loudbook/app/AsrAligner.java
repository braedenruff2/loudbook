package com.loudbook.app;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact sentence timing for Gemini recordings: a small speech recogniser (Zipformer, trained on
 * audiobooks) listens to the recording once, on the phone, and notes when each word is said. The
 * words are matched to the chapter's text, so each sentence starts exactly where its first word
 * does: tapping a sentence, or skipping back, lands in the right place. Plain Java (no Android).
 */
final class AsrAligner {
    static final String[] FILES = {"asr-encoder.onnx", "asr-decoder.onnx", "asr-joiner.onnx", "asr-tokens.txt"};
    static final int RATE = 16000;

    static final class Word { final String w; final double t; Word(String w, double t) { this.w = w; this.t = t; } public String toString() { return w + "@" + String.format(java.util.Locale.US, "%.2f", t); } }

    private volatile OnlineRecognizer rec;
    /** Being loaded now (on another thread): a request can go without waiting for it. */
    volatile boolean loading;

    static boolean downloaded(File d) { for (String f : FILES) if (!new File(d, f).isFile()) return false; return true; }

    static void fetch(File d, Net.Progress p) throws Exception {
        for (int i = 0; i < FILES.length; i++) {
            final int k = i;
            Net.download(BuildInfo.RELEASES + "download/asr-1/" + FILES[i], new File(d, FILES[i]), 0,
                (done, t) -> { if (p != null) p.on(k * 100L + (t > 0 ? 100 * done / t : 0), FILES.length * 100L); });
        }
    }

    void load(File d) {
        if (rec != null) return;
        loading = true;
        try { loadNow(d); } finally { loading = false; }
    }
    private synchronized void loadNow(File d) {
        if (rec != null) return;
        OnlineTransducerModelConfig tr = OnlineTransducerModelConfig.builder()
            .setEncoder(new File(d, "asr-encoder.onnx").getPath()).setDecoder(new File(d, "asr-decoder.onnx").getPath())
            .setJoiner(new File(d, "asr-joiner.onnx").getPath()).build();
        OnlineModelConfig m = OnlineModelConfig.builder().setTransducer(tr).setTokens(new File(d, "asr-tokens.txt").getPath())
            .setNumThreads(2).setProvider("cpu").setDebug(false).build();
        rec = new OnlineRecognizer(OnlineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(80).build())
            .setOnlineModelConfig(m).setEndpointConfig(EndpointConfig.builder().build())
            .setEnableEndpoint(false).setDecodingMethod("greedy_search").build());
    }

    /** The words heard in samples [from, from+n) of a recording, with times in seconds from `from`. */
    List<Word> words(CloudVoice.Pcm pcm, long from, long n) throws IOException {
        // (the recogniser is shared with the live listener: it's held a half second at a time)
        Live l = new Live(pcm.rate);
        try {
            int step = pcm.rate / 2;                                // half a second at a time
            for (long at = 0; at < n; at += step) l.feed(pcm.read(from + at, (int) Math.min(step, n - at)));
            l.finish();                                             // (a little silence so the last word comes out)
            return l.words();
        } finally { l.release(); }
    }

    /**
     * Listening as a recording arrives: audio is fed in as it comes, and the words heard so far
     * (times in seconds from the start) can be asked for at any moment.
     */
    final class Live {
        private final OnlineStream s;
        private final int rate;
        private boolean done;
        // The recogniser doesn't make out the first two or three seconds of a stream: "Then a man
        // stepped out of the fog. He was tall and thin" came out as "and thin", "They all studied
        // this place with fascination" as "ination" (so the first sentences of every recording were
        // placed by their length, not by their words). So it hears a few seconds of other speech
        // first (the warm-up), then half a second of quiet, then the recording; what it heard of the
        // warm-up is dropped, and times are from the recording's start. (Without the warm-up: the
        // first 3 s of the recording, quiet, then the recording from its start. That still missed
        // the start of 1 recording in 10, the warm-up none of 60, 2026-10-31.)
        private static final int PRIME = RATE * 3;
        private float[] head = new float[0];              // the first 3 s (16 kHz), until they're fed
        private double offset = -1;                        // where the recording itself starts in the stream (s); -1: not yet
        Live(int rate) {
            this.rate = rate;
            synchronized (AsrAligner.this) {
                s = rec.createStream();
                if (warm != null) {
                    s.acceptWaveform(warm, RATE);
                    s.acceptWaveform(new float[RATE / 2], RATE);
                    offset = (warm.length + RATE / 2) / (double) RATE;
                    while (rec.isReady(s)) rec.decode(s);
                }
            }
        }

        void feed(float[] x) {
            double ratio = (double) rate / RATE;
            int m = (int) (x.length / ratio);
            float[] y = new float[m];
            for (int i = 0; i < m; i++) {
                double p = i * ratio; int j = (int) p; double f = p - j;
                y[i] = (float) (x[Math.min(j, x.length - 1)] * (1 - f) + x[Math.min(j + 1, x.length - 1)] * f);
            }
            synchronized (AsrAligner.this) {
                if (offset < 0) {
                    float[] h = java.util.Arrays.copyOf(head, head.length + y.length);
                    System.arraycopy(y, 0, h, head.length, y.length);
                    head = h;
                    if (head.length >= PRIME) prime();
                    return;
                }
                s.acceptWaveform(y, RATE);
                while (rec.isReady(s)) rec.decode(s);
            }
        }

        /** The first seconds, then quiet, then the recording from its start (held: AsrAligner's lock). */
        private void prime() {
            int n = Math.min(head.length, PRIME);
            s.acceptWaveform(java.util.Arrays.copyOf(head, n), RATE);
            s.acceptWaveform(new float[RATE / 2], RATE);
            offset = (n + RATE / 2) / (double) RATE;
            s.acceptWaveform(head, RATE);
            head = new float[0];
            while (rec.isReady(s)) rec.decode(s);
        }

        /** The recording is complete: the last words come out. */
        void finish() {
            synchronized (AsrAligner.this) {
                if (done) return;
                done = true;
                if (offset < 0) { if (head.length > 0) prime(); else offset = 0; }
                s.acceptWaveform(new float[RATE / 2], RATE);
                s.inputFinished();
                while (rec.isReady(s)) rec.decode(s);
            }
        }

        List<Word> words() {
            OnlineRecognizerResult r;
            double off;
            synchronized (AsrAligner.this) { if (offset < 0) return new ArrayList<>(); off = offset; r = rec.getResult(s); }
            List<Word> out = new ArrayList<>();
            for (Word w : wordsOf(r.getTokens(), r.getTimestamps())) if (w.t >= off - 0.05) out.add(new Word(w.w, Math.max(0, w.t - off)));
            return out;
        }

        void release() { synchronized (AsrAligner.this) { s.release(); } }
    }

    boolean loaded() { return rec != null; }

    // A few seconds of other speech (Kokoro: "Here is a little speech, so the listener is warmed up
    // before the recording starts."), heard before each recording: see Live.
    private float[] warm;
    static final String WARM = "web/asr-warm.pcm";            // (in the app's assets; 16-bit, 16 kHz, mono)

    /** The warm-up speech, 16-bit little-endian PCM at 16 kHz. */
    synchronized void warm(byte[] pcm) {
        if (pcm == null || pcm.length < 2) { warm = null; return; }
        float[] w = new float[pcm.length / 2];
        for (int i = 0; i < w.length; i++) w[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768f;
        warm = w;
    }

    static List<Word> wordsOf(String[] tok, float[] ts) {
        List<Word> out = new ArrayList<>();
        StringBuilder w = null; double t = 0;
        for (int i = 0; i < tok.length; i++) {
            String k = tok[i];
            // a new word starts with the word-boundary mark (or a space, once decoded)
            boolean starts = k.startsWith("\u2581") || k.startsWith(" ");
            if (starts && w != null && w.length() > 0) out.add(new Word(w.toString(), t));
            if (starts || w == null) { w = new StringBuilder(); t = i < ts.length ? ts[i] : t; }
            w.append(k.replace("\u2581", "").trim());
        }
        if (w != null && w.length() > 0) out.add(new Word(w.toString(), t));
        return out;
    }

    static String norm(String w) { return w.toUpperCase(java.util.Locale.US).replaceAll("[^A-Z']", "").replaceAll("^'+|'+$", ""); }

    /**
     * When each sentence starts (seconds), from the words heard; NaN where it couldn't be told.
     * Text words are matched to heard words in order, looking a little ahead each time, and
     * re-finding the place by a run of three matching words if the two drift apart.
     */
    static double[] sentenceStarts(List<Word> heard, List<String> sentences) {
        List<String> tw = new ArrayList<>();
        List<Integer> ts = new ArrayList<>();
        for (int k = 0; k < sentences.size(); k++)
            for (String w : sentences.get(k).split("[\\s\\u2014\\u2013-]+")) { String n = norm(w); if (!n.isEmpty()) { tw.add(n); ts.add(k); } }
        if (tw.size() < 8 || heard.size() < 8) return greedyStarts(heard, sentences);
        return anchoredStarts(heard, tw, ts, sentences.size());
    }

    /**
     * Text and words heard lined up by runs of three words in a row that agree (rare enough in a
     * chapter to say where they are), keeping the longest chain that goes forwards in both; the
     * words between two such runs are matched in order. A word the recogniser missed or got wrong,
     * a title it didn't make out, a sentence Gemini skipped, or instructions it read out first,
     * can't pull everything after it out of place. (Matching word by word, a missed "Chapter Two"
     * matched the title's "the Mill" to the first sentence's, and from there on almost no sentence
     * could be timed until the recording was complete.)
     */
    static double[] anchoredStarts(List<Word> heard, List<String> tw, List<Integer> ts, int nSent) {
        int T = tw.size(), H = heard.size();
        String[] hw = new String[H];
        for (int i = 0; i < H; i++) hw[i] = norm(heard.get(i).w);
        java.util.Map<String, List<Integer>> grams = new java.util.HashMap<>();
        for (int i = 0; i + 2 < T; i++) grams.computeIfAbsent(tw.get(i) + " " + tw.get(i + 1) + " " + tw.get(i + 2), x -> new ArrayList<>()).add(i);
        // candidate pairs {text i, heard m}, by m, then i falling (so one m can't take two)
        List<int[]> pairs = new ArrayList<>();
        for (int m = 0; m + 2 < H; m++) {
            List<Integer> at = grams.get(hw[m] + " " + hw[m + 1] + " " + hw[m + 2]);
            if (at == null || at.size() > 3) continue;
            for (int q = at.size() - 1; q >= 0; q--) pairs.add(new int[]{at.get(q), m});
        }
        // the longest chain going forwards in both (patience sorting)
        int P = pairs.size();
        int[] tail = new int[P + 1], prev = new int[P];
        int len = 0;
        for (int p = 0; p < P; p++) {
            int i = pairs.get(p)[0], lo = 1, hi = len;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (pairs.get(tail[mid])[0] < i) lo = mid + 1; else hi = mid - 1; }
            prev[p] = lo > 1 ? tail[lo - 1] : -1;
            tail[lo] = p;
            if (lo > len) len = lo;
        }
        int[] match = new int[T];                         // heard word for each text word, or -1
        java.util.Arrays.fill(match, -1);
        boolean[] sure = new boolean[T];
        for (int p = len > 0 ? tail[len] : -1; p >= 0; p = prev[p]) {
            int i = pairs.get(p)[0], m = pairs.get(p)[1];
            for (int d = 0; d < 3; d++) if (match[i + d] < 0) { match[i + d] = m + d; sure[i + d] = true; }
        }
        // between two sure stretches: the words in order (only short gaps, so a common word can't wander)
        int a = -1;
        for (int i = 0; i <= T; i++) {
            if (i < T && !sure[i]) continue;
            int ha = a < 0 ? -1 : match[a], hb = i < T ? match[i] : H;
            if (i - a > 1 && hb - ha > 1 && i - a <= 12 && hb - ha <= 16) {
                int m = ha + 1;
                for (int k = a + 1; k < i && m < hb; k++)
                    for (int mm = m; mm < Math.min(hb, m + 4); mm++) if (hw[mm].equals(tw.get(k))) { match[k] = mm; m = mm + 1; break; }
            }
            a = i;
        }
        double[] start = new double[nSent];
        java.util.Arrays.fill(start, Double.NaN);
        int[] first = new int[nSent];
        java.util.Arrays.fill(first, -1);
        for (int i = 0; i < T; i++) if (first[ts.get(i)] < 0) first[ts.get(i)] = i;
        for (int k = 0; k < nSent; k++) {
            int f = first[k];
            if (f < 0) continue;
            // its first word, or its second (a moment later), as long as a word near it agrees too
            for (int d = 0; d <= 1 && f + d < T && ts.get(f + d) == k; d++) {
                int i = f + d;
                if (match[i] < 0) continue;
                boolean backed = sure[i] || (i + 1 < T && match[i + 1] == match[i] + 1) || (i > 0 && match[i - 1] >= 0 && match[i - 1] == match[i] - 1);
                if (!backed) continue;
                start[k] = heard.get(match[i]).t - d * 0.3;
                break;
            }
        }
        double last = -1;
        for (int k = 0; k < nSent; k++) {
            if (Double.isNaN(start[k])) continue;
            if (start[k] <= last + 0.15) start[k] = Double.NaN; else last = start[k];
        }
        return start;
    }

    /** Word-by-word matching (for a text too short for runs of three). */
    static double[] greedyStarts(List<Word> heard, List<String> sentences) {
        List<String> tw = new ArrayList<>();
        List<Integer> ts = new ArrayList<>();
        for (int k = 0; k < sentences.size(); k++)
            for (String w : sentences.get(k).split("[\\s\\u2014\\u2013-]+")) { String n = norm(w); if (!n.isEmpty()) { tw.add(n); ts.add(k); } }
        String[] hw = new String[heard.size()];
        for (int i = 0; i < hw.length; i++) hw[i] = norm(heard.get(i).w);
        double[] start = new double[sentences.size()];
        java.util.Arrays.fill(start, Double.NaN);
        int[] firstWordOf = new int[sentences.size()];
        java.util.Arrays.fill(firstWordOf, -1);
        for (int i = 0; i < tw.size(); i++) if (firstWordOf[ts.get(i)] < 0) firstWordOf[ts.get(i)] = i;
        int j = 0, misses = 0;
        for (int i = 0; i < tw.size() && j < hw.length; i++) {
            int found = -1;
            // (at the very start, look further, as Gemini may have said its instructions first; and
            // there a word counts only with the next word after it too, so a "the" in the
            // instructions, or further into the first sentence, isn't taken for the first word)
            boolean atStart = j == 0 && i < 6;
            int reach = atStart ? 150 : 12;
            for (int m = j; m < Math.min(hw.length, j + reach); m++)
                if (hw[m].equals(tw.get(i)) && (!atStart || i + 1 >= tw.size() || (m + 1 < hw.length && hw[m + 1].equals(tw.get(i + 1))))) { found = m; break; }
            if (found < 0 && ++misses > 20) {
                // lost: find the next place where three words in a row agree
                outer:
                for (int a = i; a + 2 < tw.size() && a < i + 40; a++)
                    for (int m = j; m + 2 < hw.length && m < j + 300; m++)
                        if (hw[m].equals(tw.get(a)) && hw[m + 1].equals(tw.get(a + 1)) && hw[m + 2].equals(tw.get(a + 2))) { i = a; found = m; break outer; }
            }
            if (found < 0) continue;
            misses = 0;
            j = found + 1;
            int k = ts.get(i);
            // a sentence's time comes from its first or second word (not from deep inside it), and
            // only when the word after it matches too, so a common word heard elsewhere can't fool it
            boolean pair = i + 1 >= tw.size() || ts.get(i + 1) != k || (found + 1 < hw.length && hw[found + 1].equals(tw.get(i + 1)));
            if (Double.isNaN(start[k]) && i - firstWordOf[k] <= 1 && pair) start[k] = heard.get(found).t - (i - firstWordOf[k]) * 0.3;
        }
        // keep them in order: drop any that would go backwards
        double last = -1;
        for (int k = 0; k < start.length; k++) {
            if (Double.isNaN(start[k])) continue;
            if (start[k] <= last + 0.15) start[k] = Double.NaN; else last = start[k];
        }
        return start;
    }

    /**
     * Whether a sentence was said to its end: some of its last few words were heard after it
     * started, or (if the recogniser didn't make them out) the recording goes on long enough
     * after its start for the whole sentence. A recording that Google stopped early ends inside
     * its last sentence.
     */
    static boolean endHeard(List<Word> heard, String sentence, double start, double recordingSecs) {
        List<String> tw = new ArrayList<>();
        for (String w : sentence.split("[\\s\\u2014\\u2013-]+")) { String n = norm(w); if (!n.isEmpty()) tw.add(n); }
        if (tw.isEmpty()) return true;
        String last = tw.get(tw.size() - 1), before = tw.size() >= 2 ? tw.get(tw.size() - 2) : null;
        int seen = 0;
        for (Word w : heard) {
            if (w.t < start - 0.1) continue;
            String n = norm(w.w);
            // (near its end: not the same word earlier in the sentence)
            if (++seen < tw.size() * 0.6) continue;
            // its last word, or the one before it with time left in the recording for the last
            if (n.equals(last) || n.equals(before) && recordingSecs - w.t >= 0.25 + 0.06 * last.length()) return true;
        }
        // not made out: long enough for the whole sentence at an ordinary pace (0.055 s a letter)?
        return recordingSecs - start >= 0.75 * Math.max(0.6, sentence.length() * 0.055);
    }

    /** The reader's pace in this recording (seconds a letter), from the sentences whose starts were heard; 0.065 if too few. */
    static double pace(double[] st, List<String> texts) {
        int a = -1, b = -1;
        for (int k = 0; k < st.length; k++) if (!Double.isNaN(st[k])) { if (a < 0) a = k; b = k; }
        if (a < 0 || b <= a) return 0.065;
        double chars = 0;
        for (int k = a; k < b; k++) chars += Math.max(1, texts.get(k).length());
        double spc = (st[b] - st[a]) / chars;
        return spc > 0.02 && spc < 0.2 ? spc : 0.065;
    }

    /**
     * Where each sentence should start (samples; -1 where it can't be told), from the times the
     * recogniser heard their first words. If most were heard, the rest are filled in between them
     * (by length, at the nearest pause) so the whole recording is re-cut from the words.
     */
    static long[] cuts(double[] st, List<String> texts, double[][] pauses, int rate, long[] old) {
        int n = st.length;
        double[] at = new double[n];
        int known = 0;
        for (int k = 0; k < n; k++) {
            if (Double.isNaN(st[k])) { at[k] = Double.NaN; continue; }
            known++;
            // (the recogniser reports a word about a quarter second after it begins)
            double word = st[k] - 0.22;
            // cut in the pause just before the first word, if there is one: near its end (a moment
            // before the voice), so the highlight moves to the sentence as it's heard, not half a
            // pause early (a long pause between paragraphs put it most of a second ahead)
            double c = word - 0.05, best = Double.MAX_VALUE;
            for (double[] p : pauses) {
                double end = p[0] + p[1] / 2;                       // where the pause ends
                if (end > word + 0.25 || end < word - 0.6) continue;
                double d = Math.abs(end - word);
                if (d < best) { best = d; c = Math.max(p[0], end - 0.15); }
            }
            at[k] = Math.max(0, c);
        }
        if (known * 2 >= n && known >= 2) {
            // fill the gaps: between two heard sentences, by their lengths, snapped to a pause
            int[] len = new int[n];
            for (int k = 0; k < n; k++) len[k] = Math.max(1, texts.get(k).length());
            for (int k = 0; k < n; k++) {
                if (!Double.isNaN(at[k])) continue;
                int a = k - 1; while (a >= 0 && Double.isNaN(at[a])) a--;
                int b = k + 1; while (b < n && Double.isNaN(st[b])) b++;
                double guess;
                if (a >= 0 && b < n) {
                    double tot = 0, upto = 0;
                    for (int q = a; q < b; q++) { tot += len[q]; if (q < k) upto += len[q]; }
                    guess = at[a] + (at[b] - at[a]) * upto / tot;
                } else if (a >= 0) guess = at[a] + (old[k] - old[a]) / (double) rate;       // after the last heard one: keep the spacing
                else guess = Double.NaN;                                                     // before the first heard one: leave it
                if (Double.isNaN(guess)) continue;
                // (at a pause near the guess, but not the one the next heard sentence starts at: a
                // short sentence just before it, "II.", would otherwise get no length at all)
                double lo = a >= 0 ? at[a] + 0.2 : 0, hi = b < n ? at[b] - 0.2 : Double.MAX_VALUE;
                double c = guess, best = 0.6;
                for (double[] p : pauses) { double d = Math.abs(p[0] - guess); if (d < best && p[0] > lo && p[0] < hi) { best = d; c = p[0]; } }
                if (c <= lo || c >= hi) c = Math.max(lo, Math.min(hi, guess));
                at[k] = c;
            }
        }
        long[] out = new long[n];
        double last = -1;
        for (int k = 0; k < n; k++) {
            if (Double.isNaN(at[k]) || at[k] <= last + 0.2) { out[k] = -1; continue; }
            out[k] = Math.round(at[k] * rate);
            last = at[k];
        }
        // any left between two known ones (a word heard out of place): shared out by length, at a pause
        for (int k = 1; k < n - 1; k++) {
            if (out[k] >= 0) continue;
            int a = k - 1, b = k + 1;
            while (b < n && out[b] < 0) b++;
            if (out[a] < 0 || b >= n) continue;
            double tot = 0, upto = 0;
            for (int q = a; q < b; q++) { double l = Math.max(1, texts.get(q).length()); tot += l; if (q < k) upto += l; }
            double ta = out[a] / (double) rate, tb = out[b] / (double) rate;
            double guess = ta + (tb - ta) * upto / tot, c = guess, best = 0.6;
            for (double[] p : pauses) { double d = Math.abs(p[0] - guess); if (d < best && p[0] > ta + 0.2 && p[0] < tb - 0.2) { best = d; c = p[0]; } }
            if (c > ta + 0.2 && c < tb - 0.2) out[k] = Math.round(c * rate);
        }
        return out;
    }
}
