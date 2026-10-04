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
            if (f >= 0 && f - at < 400) { out.add(new int[]{f, f + n.length()}); at = f + n.length(); continue; }
            // only part of it is on this page (it started on the one before): find how it ends
            int tail = Math.min(n.length(), 30);
            int g = norm.indexOf(n.substring(n.length() - tail), at);
            if (g >= 0 && g - at < 400) { out.add(new int[]{at, g + tail}); at = g + tail; continue; }
            out.add(null);
        }
        return out;
    }

    /** The boxes covering letters [s, e) of the page, one per line. */
    List<float[]> boxesFor(int s, int e) {
        List<float[]> out = new ArrayList<>();
        if (s < 0 || e <= s || e > nLine.length) return out;
        int l = nLine[s], a = nPos[s];
        for (int i = s; i < e; i++) {
            boolean lineEnds = i == e - 1 || nLine[i + 1] != l;
            if (lineEnds) {
                out.add(piece(l, a, nPos[i] + 1));
                if (i + 1 < e) { l = nLine[i + 1]; a = nPos[i + 1]; }
            }
        }
        return out;
    }

    /** The word at a point through letters [s, e) (frac 0 = the first word, 1 = the last). */
    float[] wordAt(int s, int e, float frac) {
        if (s < 0 || e <= s || e > nLine.length) return null;
        int i = Math.max(s, Math.min(e - 1, s + Math.round(frac * (e - s - 1))));
        int l = nLine[i];
        String t = lines.get(l);
        int a = nPos[i], b = nPos[i] + 1;
        while (a > 0 && !Character.isWhitespace(t.charAt(a - 1))) a--;
        while (b < t.length() && !Character.isWhitespace(t.charAt(b))) b++;
        return piece(l, a, b);
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
