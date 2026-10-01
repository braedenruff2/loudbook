package com.loudbook.app;

import java.io.IOException;

/**
 * Finds where each sentence starts in a long recording of a whole chapter (Gemini reads a chapter
 * in one go and says nothing about timing). Sentences end in pauses, and each sentence lasts
 * roughly in proportion to its length, so the cuts are chosen among the pauses to make every
 * sentence's length fit best (a Viterbi search over the pauses; it can't drift, because each
 * sentence is judged on its own length). It also notices when the recording stops early, so the
 * rest can be asked for. Plain Java, so it can be tested on its own.
 */
final class Align {
    static final class Result {
        int count;            // sentences found, from the first one asked for
        long[] start;         // sample where each starts
        long[] end;           // and ends
        boolean cutShort;     // the recording stopped before the text did
        double secPerChar;    // the reader's pace in this recording
        double score;         // average misfit per sentence (lower is better)
    }

    static final int FRAME_MS = 20;

    static Result align(CloudVoice.Pcm pcm, int[] chars, double secPerChar) throws IOException {
        int rate = pcm.rate, frame = rate * FRAME_MS / 1000;
        long total = pcm.samples();
        int nf = (int) (total / frame);
        float[] rms = new float[Math.max(1, nf)];
        // loudness of every 20 ms, read in pieces
        int per = 2000 * frame;
        for (long at = 0, f = 0; at < (long) nf * frame; at += per) {
            float[] x = pcm.read(at, (int) Math.min(per, (long) nf * frame - at));
            for (int i = 0; i + frame <= x.length && f < nf; i += frame, f++) {
                double s = 0;
                for (int k = i; k < i + frame; k++) s += x[k] * x[k];
                rms[(int) f] = (float) Math.sqrt(s / frame);
            }
        }
        return alignFrames(rms, chars, secPerChar, rate, total);
    }

    static Result alignFrames(float[] rms, int[] chars, double secPerChar, int rate, long totalSamples) {
        int nf = rms.length;
        double fs = FRAME_MS / 1000.0;
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float loud = sorted[(int) (sorted.length * 0.9)];
        float quiet = Math.max(0.0015f, loud * 0.05f);

        // the pauses: runs of quiet of 100 ms or more; a cut goes in the middle of one
        java.util.ArrayList<double[]> c = new java.util.ArrayList<>();   // {time s, strength}
        c.add(new double[]{0, 1});
        int i = 0;
        while (i < nf) {
            if (rms[i] >= quiet) { i++; continue; }
            int j = i;
            while (j < nf && rms[j] < quiet) j++;
            double len = (j - i) * fs;
            if (len >= 0.1 && i > 0 && j < nf) c.add(new double[]{(i + j) / 2.0 * fs, len});
            i = j;
        }
        double end = nf * fs;
        c.add(new double[]{end, 1});
        int C = c.size(), N = chars.length;
        double[] t = new double[C], st = new double[C];
        for (int k = 0; k < C; k++) { t[k] = c.get(k)[0]; st[k] = c.get(k)[1]; }
        int[] strong = new int[C + 1];                 // how many long pauses come before each one
        for (int k = 0; k < C; k++) strong[k + 1] = strong[k] + (k > 0 && k < C - 1 && st[k] >= 0.45 ? 1 : 0);
        // a recording that ends mid-word was cut off: then the last piece can't be a whole sentence
        boolean abrupt = nf > 10 && rms[nf - 1] >= quiet && rms[nf - 3] >= quiet;
        if (abrupt) st[C - 1] = -1;

        // the pace isn't known well (voices differ, and Google may cut a long recording short), so
        // try a range and keep the one where sentence lengths line up best: a wrong pace shifts
        // which sentence lands where, and long and short sentences stop matching
        Result best = null;
        double bestScore = Double.MAX_VALUE;
        double lo = secPerChar > 0 ? secPerChar * 0.7 : 0.04, hi = secPerChar > 0 ? secPerChar * 1.4 : 0.11;
        for (double spc = lo; spc <= hi; spc *= 1.04) {
            Result r = viterbi(t, st, strong, chars, spc, end, rate);
            if (best == null || r.score < bestScore) { bestScore = r.score; best = r; }
        }
        for (double spc = best.secPerChar * 0.97; spc <= best.secPerChar * 1.03; spc *= 1.01) {   // finer
            Result r = viterbi(t, st, strong, chars, spc, end, rate);
            if (r.score < bestScore) { bestScore = r.score; best = r; }
        }
        for (int k = 0; k < best.count; k++) {
            best.start[k] = Math.min(totalSamples, best.start[k]);
            best.end[k] = Math.min(totalSamples, best.end[k]);
        }
        if (best.count > 0 && !best.cutShort) best.end[best.count - 1] = totalSamples;
        return best;
    }

    private static Result viterbi(double[] t, double[] st, int[] strong, int[] chars, double spc, double end, int rate) {
        int C = t.length, N = chars.length;
        final float INF = Float.MAX_VALUE / 4;
        final double REWARD = 1.5;
        float[][] dp = new float[N + 1][C];
        int[][] from = new int[N + 1][C];
        for (float[] row : dp) java.util.Arrays.fill(row, INF);
        dp[0][0] = 0;
        boolean abrupt = st[C - 1] < 0;
        for (int k = 0; k < N; k++) {
            double e = Math.max(0.35, Math.max(1, chars[k]) * spc);
            double lo = 0.4 * e, hi = 2.2 * e + 1.2, sd = 0.22 * e + 0.3;
            boolean any = false;
            for (int j = 0; j < C; j++) {
                float here = dp[k][j];
                if (here >= INF) continue;
                any = true;
                for (int j2 = j + 1; j2 < C; j2++) {
                    double dur = t[j2] - t[j];
                    if (dur < lo) continue;
                    if (dur > hi) break;
                    if (j2 == C - 1 && abrupt) continue;           // a cut-off ending isn't a sentence end
                    double z = (dur - e) / sd;
                    int skipped = strong[j2] - strong[j + 1];      // long pauses inside this sentence
                    double cost = z * z - REWARD - 2.5 * Math.min(Math.max(st[j2], 0), 0.6) + 2.0 * skipped;
                    float v = (float) (here + cost);
                    if (v < dp[k + 1][j2]) { dp[k + 1][j2] = v; from[k + 1][j2] = j; }
                }
            }
            if (!any) break;
        }
        // how it ends: at the end of the audio after a whole sentence, or (cut off) with part of
        // the next sentence left over, no longer than that sentence would take
        double bestV = Double.MAX_VALUE; int bk = 0, bj = 0;
        for (int k = 0; k <= N; k++) for (int j = 0; j < C; j++) {
            if (dp[k][j] >= INF) continue;
            double tail = end - t[j], v;
            if (j == C - 1) v = dp[k][j];
            else {
                if (k >= N) continue;
                double e = Math.max(0.35, chars[k] * spc);
                if (tail > 1.15 * e + 0.5) continue;               // more left over than the next sentence: wrong
                v = dp[k][j] + 0.3;
            }
            if (v < bestV) { bestV = v; bk = k; bj = j; }
        }
        Result r = new Result();
        r.count = bk;
        r.start = new long[bk]; r.end = new long[bk];
        r.secPerChar = spc;
        r.score = bk == 0 ? Double.MAX_VALUE : (bestV + REWARD * bk) / bk;   // average misfit per sentence
        int j = bj;
        for (int k = bk; k > 0; k--) {
            int p = from[k][j];
            r.start[k - 1] = Math.round(t[p] * rate);
            r.end[k - 1] = Math.round(t[j] * rate);
            j = p;
        }
        r.cutShort = bk < N;
        return r;
    }
}
