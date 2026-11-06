package com.loudbook.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Where things are on the Kindle page: the lines of text and their place on screen. Finds where a
 * sentence (or one word of it) is, for the highlight, and which sentence a tap on the page points
 * at. Text is matched on its letters and digits only, so spacing, hyphens and quote marks don't
 * matter. Plain Java (rectangles are {left, top, right, bottom}).
 */
final class KindleLayout {
    final List<String> lines;
    final List<float[]> boxes;
    /** Per line, each character's box if Kindle shares them (null otherwise: then it's estimated). */
    final List<float[][]> chars;
    /** How many lines Kindle gave exact character boxes for (the rest are estimated). */
    int exactLines;
    private final StringBuilder norm = new StringBuilder();
    private int[] nLine = new int[0], nPos = new int[0];

    KindleLayout(List<String> lines, List<float[]> boxes, List<float[][]> chars) {
        this.lines = lines; this.boxes = boxes; this.chars = chars;
        List<Integer> li = new ArrayList<>(), po = new ArrayList<>();
        for (int l = 0; l < lines.size(); l++) {
            String t = lines.get(l);
            for (int c = 0; c < t.length(); c++) {
                char ch = t.charAt(c);
                if (Character.isLetterOrDigit(ch)) { norm.append(Character.toLowerCase(ch)); li.add(l); po.add(c); }
            }
        }
        nLine = new int[li.size()]; nPos = new int[po.size()];
        for (int i = 0; i < nLine.length; i++) { nLine[i] = li.get(i); nPos[i] = po.get(i); }
    }

    static String normOf(String s) {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < s.length(); c++) { char ch = s.charAt(c); if (Character.isLetterOrDigit(ch)) b.append(Character.toLowerCase(ch)); }
        return b.toString();
    }

    int length() { return norm.length(); }

    /**
     * How many of a page's first sentences are only the end of the sentence {@code lead} (it began on the page before: Kindle laid
     * the page out again while paused, starting mid-sentence, lab c1106a "of his face changed..."), or 0. Then the whole of
     * lead is read in their place, not its tail alone. At least 12 letters must match, and lead must be longer than them.
     */
    static int leadCover(String lead, List<String> first) {
        if (lead == null || first == null) return 0;
        String nl = normOf(lead);
        StringBuilder s = new StringBuilder();
        int best = 0;
        for (int k = 0; k < first.size() && k < 6; k++) {
            s.append(normOf(first.get(k)));
            if (s.length() >= nl.length()) break;
            if (nl.endsWith(s.toString())) { if (s.length() >= 12) best = k + 1; }
            else if (!nl.contains(s.toString())) break;
        }
        return best;
    }

    /** Where a sentence starts on the page (in its letters, as letterAt counts them), or -1: all of it, or its first 30 letters. */
    int find(String sentence) {
        String n = normOf(sentence);
        if (n.isEmpty()) return -1;
        int f = norm.indexOf(n);
        if (f < 0 && n.length() > 30) f = norm.indexOf(n.substring(0, 30));
        return f;
    }

    /**
     * Where each sentence of the page is ({start, end} in the page's letters; null if it can't be
     * found). The first may have begun on the page before, so only its end is looked for.
     */
    List<int[]> spans(List<String> sentences) {
        List<int[]> out = new ArrayList<>();
        int at = 0;
        for (int k = 0; k < sentences.size(); k++) {
            String n = normOf(sentences.get(k));
            if (n.isEmpty()) { out.add(null); continue; }
            int f = norm.indexOf(n, at);
            if (f >= 0 && f - at < 400) { out.add(new int[]{f, f + n.length(), 0, n.length()}); at = f + n.length(); continue; }
            // only part of it is on this page (it started on the one before): find how it ends
            int tail = Math.min(n.length(), 30);
            int g = norm.indexOf(n.substring(n.length() - tail), at);
            if (g >= 0 && g - at < 400) { int e = g + tail; out.add(new int[]{at, e, Math.max(0, n.length() - (e - at)), n.length()}); at = e; continue; }
            // (the page's only sentence, starting at its top and running on to the next page: all of the page is its start. Before
            //  the next check, which would take a letter or two that the sentence ends with and the page starts with for its end)
            if (k == 0 && at == 0 && k == sentences.size() - 1 && norm.length() > 0 && n.length() > norm.length() && n.startsWith(norm.toString())) {
                out.add(new int[]{0, norm.length(), 0, n.length()}); at = norm.length(); continue;
            }
            // (the page's first sentence, with fewer than 30 of its letters on this page: "ment to me." after a break)
            if (k == 0 && at == 0) {
                int cut = -1;
                for (int c = 1; c < n.length(); c++) { String sfx = n.substring(c); if (sfx.length() <= norm.length() && norm.substring(0, sfx.length()).equals(sfx)) { cut = c; break; } }
                // (a letter or two only when the next sentence starts right after them: else any sentence ending with the page's
                //  first letter got a bogus span, as for one that ended on the page before)
                if (cut > 0 && (n.length() - cut >= 3 || k + 1 < sentences.size() && nextAt(sentences.get(k + 1), n.length() - cut))) {
                    int e = n.length() - cut; out.add(new int[]{0, e, cut, n.length()}); at = e; continue;
                }
            }
            // or it runs on to the next page: the page ends with its start
            int head = Math.min(n.length(), 30);
            int h = norm.indexOf(n.substring(0, head), at);
            if (h >= 0 && h - at < 400 && n.startsWith(norm.substring(h))) { out.add(new int[]{h, norm.length(), 0, n.length()}); at = norm.length(); continue; }
            // (fewer than 30 of its letters on this page: "You must there" at the foot of the page)
            if (k == sentences.size() - 1) {
                int hs = -1;
                for (int c = at; c < norm.length() && c - at < 400; c++) if (n.startsWith(norm.substring(c))) { hs = c; break; }
                if (hs >= 0) { out.add(new int[]{hs, norm.length(), 0, n.length()}); at = norm.length(); continue; }
            }
            out.add(null);
        }
        return out;
    }

    /** Does this sentence's text start at letter e of the page (or its first 30 letters, for one running on)? */
    private boolean nextAt(String sentence, int e) {
        String n = normOf(sentence);
        if (n.isEmpty() || e >= norm.length()) return false;
        int len = Math.min(n.length(), Math.min(30, norm.length() - e));
        return norm.substring(e, e + len).equals(n.substring(0, len));
    }

    /**
     * How far through the part of a sentence on this page the voice is (0..1), from how far through
     * the whole sentence it is; -1 while it's still on the part on the page before.
     */
    static float localFrac(int[] span, float frac) {
        if (span == null || span.length < 4 || span[3] <= 0 || span[1] <= span[0]) return frac;
        float at = frac * span[3] - span[2];
        if (at < 0) return -1f;
        return Math.min(1f, at / (span[1] - span[0]));
    }

    /** The letter (0..n-1) being said when a fraction of n letters is said (n/len at a word's end is still that word). */
    static int saidLetter(float frac, int n) {
        return Math.max(0, Math.min(n - 1, (int) Math.ceil(frac * n - 0.01f) - 1));
    }

    /**
     * Whether two sets of marks (the sentence's boxes, the word's box or null) are the same, so the highlight isn't redrawn: it was
     * drawn again every 80 ms whatever changed (the whole screen, on a dimmed phone; and while paused, nothing changes at all).
     */
    static boolean sameMarks(List<float[]> a, float[] aw, List<float[]> b, float[] bw) {
        if (!java.util.Arrays.equals(aw, bw)) return false;
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** The boxes covering letters [s, e) of the page, one per line. */
    List<float[]> boxesFor(int s, int e) {
        List<float[]> out = new ArrayList<>();
        if (s < 0 || e <= s || e > nLine.length) return out;
        int l = nLine[s], a = nPos[s];
        for (int i = s; i < e; i++) {
            boolean lineEnds = i == e - 1 || nLine[i + 1] != l;
            if (lineEnds) {
                out.addAll(rows(l, a, nPos[i] + 1));
                if (i + 1 < e) { l = nLine[i + 1]; a = nPos[i + 1]; }
            }
        }
        return out;
    }

    /**
     * The word at a point through letters [s, e) (frac 0 = the first word, 1 = the last): the one whose letter is being said
     * when that fraction of them is said, so at a pause (frac right at a word's end) the word just said, not the next one.
     */
    float[] wordAt(int s, int e, float frac) {
        if (s < 0 || e <= s || e > nLine.length) return null;
        int i = s + saidLetter(frac, e - s);
        int l = nLine[i];
        String t = lines.get(l);
        int a = nPos[i], b = nPos[i] + 1;
        while (a > 0 && !Character.isWhitespace(t.charAt(a - 1))) a--;
        while (b < t.length() && !Character.isWhitespace(t.charAt(b))) b++;
        return rows(l, a, b).get(0);
    }

    /** The page letter closest to a point on screen (a tap): the start of the line tapped, or the word. */
    int letterAt(float x, float y) {
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int l = 0; l < boxes.size(); l++) {
            float[] r = boxes.get(l);
            float d = y < r[1] ? r[1] - y : y > r[3] ? y - r[3] : 0;
            if (d < bestD) { bestD = d; best = l; }
        }
        if (best < 0) return -1;
        String t = lines.get(best);
        float[] r = boxes.get(best);
        int c = Math.max(0, Math.min(t.length() - 1, Math.round((x - r[0]) / Math.max(1, r[2] - r[0]) * t.length())));
        while (c > 0 && !Character.isWhitespace(t.charAt(c - 1))) c--;          // the word's start
        for (int i = 0; i < nLine.length; i++) if (nLine[i] == best && nPos[i] >= c) return i;
        for (int i = 0; i < nLine.length; i++) if (nLine[i] > best) return i;
        return nLine.length - 1;
    }

    /**
     * Characters [a, b) of line l as one box per row on screen: a piece of text Kindle hands over
     * as one line may wrap onto several rows (only known when it shares character boxes).
     */
    private List<float[]> rows(int l, int a, int b) {
        List<float[]> out = new ArrayList<>();
        float[][] cs = chars == null || l >= chars.size() ? null : chars.get(l);
        if (cs == null || b > cs.length) { out.add(piece(l, a, b)); return out; }
        float[] cur = null;
        for (int c = a; c < b; c++) {
            float[] r = cs[c];
            if (r == null || r[2] <= r[0]) continue;                       // (spaces at a wrap have no box)
            if (cur != null && Math.abs(r[1] - cur[1]) < (cur[3] - cur[1]) / 2) { cur[2] = Math.max(cur[2], r[2]); cur[3] = Math.max(cur[3], r[3]); continue; }
            cur = new float[]{r[0], r[1], r[2], r[3]};
            out.add(cur);
        }
        if (out.isEmpty()) out.add(piece(l, a, b));
        return out;
    }

    /** The box of characters [a, b) of line l: exact if Kindle shared the character boxes, else estimated from the line. */
    private float[] piece(int l, int a, int b) {
        float[] r = boxes.get(l);
        float[][] cs = chars == null || l >= chars.size() ? null : chars.get(l);
        if (cs != null && b - 1 < cs.length && cs[a] != null && cs[b - 1] != null)
            return new float[]{cs[a][0], Math.min(cs[a][1], cs[b - 1][1]), cs[b - 1][2], Math.max(cs[a][3], cs[b - 1][3])};
        int n = Math.max(1, lines.get(l).length());
        float w = r[2] - r[0];
        return new float[]{r[0] + w * a / n, r[1], r[0] + w * b / n, r[3]};
    }
}
