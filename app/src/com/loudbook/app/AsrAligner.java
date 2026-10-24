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

    private OnlineRecognizer rec;

    static boolean downloaded(File d) { for (String f : FILES) if (!new File(d, f).isFile()) return false; return true; }

    static void fetch(File d, Net.Progress p) throws Exception {
        for (int i = 0; i < FILES.length; i++) {
            final int k = i;
            Net.download(BuildInfo.RELEASES + "download/asr-1/" + FILES[i], new File(d, FILES[i]), 0,
                (done, t) -> { if (p != null) p.on(k * 100L + (t > 0 ? 100 * done / t : 0), FILES.length * 100L); });
        }
    }

    synchronized void load(File d) {
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
    synchronized List<Word> words(CloudVoice.Pcm pcm, long from, long n) throws IOException {
        OnlineStream s = rec.createStream();
        int step = pcm.rate / 2;                                    // half a second at a time
        double ratio = (double) pcm.rate / RATE;
        for (long at = 0; at < n; at += step) {
            float[] x = pcm.read(from + at, (int) Math.min(step, n - at));
            int m = (int) (x.length / ratio);
            float[] y = new float[m];
            for (int i = 0; i < m; i++) {
                double p = i * ratio; int j = (int) p; double f = p - j;
                y[i] = (float) (x[Math.min(j, x.length - 1)] * (1 - f) + x[Math.min(j + 1, x.length - 1)] * f);
            }
            s.acceptWaveform(y, RATE);
            while (rec.isReady(s)) rec.decode(s);
        }
        s.acceptWaveform(new float[RATE / 2], RATE);                // a little silence so the last word comes out
        s.inputFinished();
        while (rec.isReady(s)) rec.decode(s);
        OnlineRecognizerResult r = rec.getResult(s);
        s.release();
        List<Word> out = new ArrayList<>();
        String[] tok = r.getTokens();
        float[] ts = r.getTimestamps();
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
            // cut in the middle of the pause just before the first word, if there is one
            double c = word - 0.05, best = Double.MAX_VALUE;
            for (double[] p : pauses) {
                double end = p[0] + p[1] / 2;                       // where the pause ends
                if (end > word + 0.25 || end < word - 0.6) continue;
                double d = Math.abs(end - word);
                if (d < best) { best = d; c = p[0]; }
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
                double c = guess, best = 0.6;
                for (double[] p : pauses) { double d = Math.abs(p[0] - guess); if (d < best) { best = d; c = p[0]; } }
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
        return out;
    }
}
