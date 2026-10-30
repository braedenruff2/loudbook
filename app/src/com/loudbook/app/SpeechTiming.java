package com.loudbook.app;

import java.util.ArrayList;
import java.util.List;

/**
 * How far through a piece of text the voice is, from the sound itself rather than from the clock:
 * the silence before and after the speech doesn't count, and the pauses the voice makes at commas
 * and full stops are matched to those marks in the text, so the highlighted word keeps up with the
 * voice through a long sentence instead of drifting (a straight line through the clip runs ahead
 * at every pause and behind after it). Plain Java (for the unit tests).
 */
final class SpeechTiming {
    /** Anchor points: frame in the clip -> fraction of the text's letters said by then. */
    final long[] at;
    final float[] frac;
    /** Where the speech starts and ends in the clip (frames), and how many pauses were matched to the text. */
    final long speechStart, speechEnd;
    final int matched;

    /** How the said text's length maps onto the text's letters: {said fraction, text fraction} per word boundary (null: the same). */
    private float[][] said2text;

    private SpeechTiming(long[] at, float[] frac, long s, long e, int matched) {
        this.at = at; this.frac = frac; speechStart = s; speechEnd = e; this.matched = matched;
    }

    /** The fraction of the text's letters said by a frame of the clip (0..1). */
    float fracAt(long frame) { return toText(saidAt(frame)); }

    private float toText(float w) {
        float[][] m = said2text;
        if (m == null) return w;
        for (int i = 1; i < m.length; i++) {
            if (w <= m[i][0]) {
                float span = m[i][0] - m[i - 1][0];
                return span <= 0 ? m[i][1] : m[i - 1][1] + (m[i][1] - m[i - 1][1]) * (w - m[i - 1][0]) / span;
            }
        }
        return 1f;
    }
    private static float fromText(float[][] m, float t) {
        if (m == null) return t;
        for (int i = 1; i < m.length; i++) {
            if (t <= m[i][1]) {
                float span = m[i][1] - m[i - 1][1];
                return span <= 0 ? m[i][0] : m[i - 1][0] + (m[i][0] - m[i - 1][0]) * (t - m[i - 1][1]) / span;
            }
        }
        return 1f;
    }

    /** How far through the said text (by its letters) the voice is at a frame. */
    private float saidAt(long frame) {
        if (frame <= at[0]) return frac[0];
        for (int i = 1; i < at.length; i++) {
            if (frame <= at[i]) {
                long span = at[i] - at[i - 1];
                if (span <= 0) return frac[i];
                return frac[i - 1] + (frac[i] - frac[i - 1]) * (frame - at[i - 1]) / (float) span;
            }
        }
        return frac[frac.length - 1];
    }

    /** The frame where the voice gets to a fraction of the text's letters (the reverse of fracAt). */
    long frameAt(float t) {
        float f = fromText(said2text, t);
        if (f <= frac[0]) return at[0];
        for (int i = 1; i < at.length; i++) {
            if (f < frac[i]) {                                               // (at a pause: where it ends, when the words go on)
                float span = frac[i] - frac[i - 1];
                if (span <= 0) return at[i - 1];
                return at[i - 1] + Math.round((at[i] - at[i - 1]) * (f - frac[i - 1]) / span);
            }
        }
        return at[at.length - 1];
    }

    private static boolean letter(char c) { return Character.isLetterOrDigit(c); }

    /**
     * The places in the text where a voice may pause: after a comma, colon, full stop and so on
     * (or a dash between words), as fractions of the text's letters; with how strong a pause each
     * usually is (2 = the end of a sentence, 1 = a comma).
     */
    static List<float[]> breaks(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (letter(text.charAt(i))) total++;
        List<float[]> out = new ArrayList<>();
        if (total == 0) return out;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (letter(c)) { n++; continue; }
            if (n == 0 || n >= total) continue;
            boolean spaceAfter = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1))
                || "\"'”’)]".indexOf(text.charAt(i + 1)) >= 0;
            int strength = 0;
            if (".!?…".indexOf(c) >= 0 && spaceAfter) strength = 2;
            else if (",;:".indexOf(c) >= 0 && spaceAfter) strength = 1;
            else if ((c == '—' || c == '–') ) strength = 1;
            else if (c == '-' && i > 0 && Character.isWhitespace(text.charAt(i - 1))) strength = 1;
            if (strength == 0) continue;
            float f = n / (float) total;
            if (!out.isEmpty() && Math.abs(out.get(out.size() - 1)[0] - f) < 1e-6) { out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], strength); continue; }
            out.add(new float[]{f, strength});
        }
        return out;
    }

    /** Works out the timing of a clip of speech (mono samples) of the text. */
    static SpeechTiming of(String text, float[] s, int rate) { return of(text, null, s, rate); }

    private static int lettersIn(String w) { int n = 0; for (int i = 0; i < w.length(); i++) if (letter(w.charAt(i))) n++; return n; }
    private static String key(String w) { StringBuilder b = new StringBuilder(); for (int i = 0; i < w.length(); i++) if (letter(w.charAt(i))) b.append(Character.toLowerCase(w.charAt(i))); return b.toString(); }

    /**
     * The voice says some words longer or shorter than they're written ("Mr." is "Mister", "12" is
     * "twelve", "STR" is "S-T-R"): each written word's share of the time, from what's said for it.
     * The two are lined up word by word; where they differ, a run of written words shares what's
     * said between the matching words around it. null if they're the same.
     */
    static float[][] said2text(String text, String said) {
        if (said == null || said.equals(text)) return null;
        List<String> tw = new ArrayList<>(), sw = new ArrayList<>();
        for (String w : text.trim().split("\\s+")) if (lettersIn(w) > 0) tw.add(w);
        for (String w : said.trim().split("\\s+")) if (lettersIn(w) > 0) sw.add(w);
        if (tw.isEmpty() || sw.isEmpty()) return null;
        float[] weight = new float[tw.size()];
        int i = 0, j = 0;
        while (i < tw.size() && j < sw.size()) {
            if (key(tw.get(i)).equals(key(sw.get(j)))) { weight[i] += lettersIn(sw.get(j)); i++; j++; continue; }
            // the nearest place they line up again (within a few words)
            int bi = -1, bj = -1;
            for (int d = 1; d <= 12 && bi < 0; d++)
                for (int a = 0; a <= d; a++) {
                    int ii = i + a, jj = j + (d - a);
                    if (ii < tw.size() && jj < sw.size() && key(tw.get(ii)).equals(key(sw.get(jj))) && key(tw.get(ii)).length() > 0) { bi = ii; bj = jj; break; }
                }
            if (bi < 0) { bi = tw.size(); bj = sw.size(); }
            int said_ = 0, written = 0;
            for (int k = j; k < bj; k++) said_ += lettersIn(sw.get(k));
            for (int k = i; k < bi; k++) written += lettersIn(tw.get(k));
            if (bi == i) { if (i > 0) weight[i - 1] += said_; }                // (said, but nothing written: goes with the word before)
            else for (int k = i; k < bi; k++) weight[k] += written > 0 ? said_ * lettersIn(tw.get(k)) / (float) written : 0;
            i = bi; j = bj;
        }
        float tTotal = 0, wTotal = 0;
        for (int k = 0; k < tw.size(); k++) { tTotal += lettersIn(tw.get(k)); wTotal += weight[k]; }
        if (wTotal <= 0 || tTotal <= 0) return null;
        float[][] m = new float[tw.size() + 1][2];
        float tc = 0, wc = 0;
        for (int k = 0; k < tw.size(); k++) {
            tc += lettersIn(tw.get(k)); wc += weight[k];
            m[k + 1][0] = wc / wTotal; m[k + 1][1] = tc / tTotal;
        }
        return m;
    }

    /** As above, with the text as the voice is given it (numbers in words, "Mister"...), for each word's share of the time. */
    static SpeechTiming of(String text, String said, float[] s, int rate) {
        SpeechTiming st = timed(text, said2text(text, said), s, rate);
        return st;
    }

    private static SpeechTiming timed(String text, float[][] map, float[] s, int rate) {
        SpeechTiming made = timed0(text, map, s, rate);
        made.said2text = map;
        return made;
    }

    private static SpeechTiming timed0(String text, float[][] map, float[] s, int rate) {
        int win = Math.max(1, rate / 100);                                   // 10 ms
        int nw = s.length / win;
        if (nw < 3) return new SpeechTiming(new long[]{0, Math.max(1, s.length)}, new float[]{0f, 1f}, 0, s.length, 0);
        float[] rms = new float[nw];
        for (int w = 0; w < nw; w++) {
            double sum = 0;
            for (int k = w * win; k < (w + 1) * win; k++) sum += s[k] * (double) s[k];
            rms[w] = (float) Math.sqrt(sum / win);
        }
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float loud = sorted[(int) (sorted.length * 0.9)];
        float thr = Math.max(loud * 0.07f, 1e-4f);
        int first = 0, last = nw - 1;
        while (first < nw && rms[first] < thr) first++;
        while (last > first && rms[last] < thr) last--;
        if (first >= last) return new SpeechTiming(new long[]{0, Math.max(1, s.length)}, new float[]{0f, 1f}, 0, s.length, 0);
        // pauses inside the speech: 70 ms or more of quiet
        List<int[]> pauses = new ArrayList<>();
        int run = -1;
        for (int w = first; w <= last; w++) {
            if (rms[w] < thr) { if (run < 0) run = w; }
            else { if (run >= 0 && w - run >= 7) pauses.add(new int[]{run, w}); run = -1; }
        }
        // speech time (pauses taken out) up to each pause, as a fraction of all of it
        int speech = last + 1 - first;
        for (int[] p : pauses) speech -= p[1] - p[0];
        speech = Math.max(1, speech);
        List<float[]> br = breaks(text);
        for (float[] b : br) b[0] = fromText(map, b[0]);                       // (where the voice says them)
        // match pauses to the text's breaks in order: each pause to the nearest break not yet used,
        // if it's near where the speech so far says it should be; longer pauses prefer sentence ends
        List<long[]> anchors = new ArrayList<>();
        List<Float> fr = new ArrayList<>();
        anchors.add(new long[]{(long) first * win}); fr.add(0f);
        int said = 0, prevEnd = first, nextBreak = 0, matched = 0;
        for (int[] p : pauses) {
            said += p[0] - prevEnd;
            prevEnd = p[1];
            float expect = said / (float) speech;
            int best = -1; float bestCost = Float.MAX_VALUE;
            for (int b = nextBreak; b < br.size(); b++) {
                float d = Math.abs(br.get(b)[0] - expect);
                if (br.get(b)[0] - expect > 0.25f) break;
                float cost = d - (p[1] - p[0] >= 20 && br.get(b)[1] >= 2 ? 0.03f : 0f);
                if (d <= 0.2f && cost < bestCost) { bestCost = cost; best = b; }
            }
            if (best < 0) continue;                                          // (a breath mid-phrase: the line just pauses there)
            float f = br.get(best)[0];
            if (f <= fr.get(fr.size() - 1)) continue;
            anchors.add(new long[]{(long) p[0] * win}); fr.add(f);
            anchors.add(new long[]{(long) p[1] * win}); fr.add(f);
            nextBreak = best + 1;
            matched++;
        }
        anchors.add(new long[]{(long) (last + 1) * win}); fr.add(1f);
        long[] at = new long[anchors.size()];
        float[] f = new float[fr.size()];
        for (int i = 0; i < at.length; i++) { at[i] = anchors.get(i)[0]; f[i] = fr.get(i); }
        return new SpeechTiming(at, f, (long) first * win, (long) (last + 1) * win, matched);
    }
}
