package com.loudbook.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the words of a block of text are, read off a picture of the screen: Kindle doesn't say
 * where its words are, but on screen each row of text is a band of "ink" and each word a run of
 * ink with a gap after it. The text's words are then matched to the ink's words in order (allowing
 * for a word split over two rows, or two run together), which gives each character a box. Only
 * shapes are looked at (no reading of letters), and nothing is kept. Plain Java.
 */
final class InkLayout {
    /** {x0, y0, x1, y1} of a word on screen. */
    static final class Word { final int x0, y0, x1, y1; final boolean rowEnd; Word(int a, int b, int c, int d, boolean e) { x0 = a; y0 = b; x1 = c; y1 = d; rowEnd = e; } }

    /** The words found in a region of the picture (row by row, left to right). px: ARGB, w × h. */
    static List<Word> words(int[] px, int w, int h, int offX, int offY) {
        List<Word> out = new ArrayList<>();
        if (w < 8 || h < 8) return out;
        int bg = background(px);
        boolean[] ink = new boolean[px.length];
        for (int i = 0; i < px.length; i++) ink[i] = Math.abs(lum(px[i]) - bg) > 70;
        // rows: bands of y where there's ink
        int[] rowInk = new int[h];
        for (int y = 0; y < h; y++) { int n = 0; for (int x = 0; x < w; x++) if (ink[y * w + x]) n++; rowInk[y] = n; }
        int minInk = Math.max(2, w / 400);
        List<int[]> bands = new ArrayList<>();
        for (int y = 0; y < h; ) {
            if (rowInk[y] < minInk) { y++; continue; }
            int s = y;
            while (y < h && rowInk[y] >= minInk) y++;
            bands.add(new int[]{s, y});
        }
        if (bands.isEmpty()) return out;
        // join bits of one row (the dot over an i, a comma below the line)
        List<Integer> hs = new ArrayList<>();
        for (int[] b : bands) hs.add(b[1] - b[0]);
        hs.sort(null);
        int typical = hs.get(hs.size() / 2);
        List<int[]> rows = new ArrayList<>();
        for (int[] b : bands) {
            if (!rows.isEmpty()) {
                int[] last = rows.get(rows.size() - 1);
                if (b[0] - last[1] < typical * 0.35 && (b[1] - b[0] < typical * 0.5 || last[1] - last[0] < typical * 0.5)) { last[1] = b[1]; continue; }
            }
            rows.add(new int[]{b[0], b[1]});
        }
        for (int[] r : rows) {
            int rh = r[1] - r[0];
            if (rh < Math.max(4, typical * 0.4)) continue;            // a speck
            // words: runs of ink columns, split where the gap is clearly wider than between letters
            int[] col = new int[w];
            for (int x = 0; x < w; x++) { int n = 0; for (int y = r[0]; y < r[1]; y++) if (ink[y * w + x]) n++; col[x] = n; }
            int gapMin = Math.max(3, Math.round(rh * 0.28f));
            List<int[]> runs = new ArrayList<>();
            int x = 0;
            while (x < w) {
                if (col[x] == 0) { x++; continue; }
                int s = x, e = x, gap = 0;
                while (x < w) {
                    if (col[x] > 0) { e = x; gap = 0; } else if (++gap >= gapMin) break;
                    x++;
                }
                runs.add(new int[]{s, e + 1});
            }
            for (int k = 0; k < runs.size(); k++) {
                int[] q = runs.get(k);
                out.add(new Word(offX + q[0], offY + r[0], offX + q[1], offY + r[1], k == runs.size() - 1));
            }
        }
        return out;
    }

    /**
     * Each character's box: the text's words matched to the words on screen. null if they don't
     * match well enough to trust.
     */
    static float[][] charBoxes(String text, List<Word> boxes) {
        List<int[]> toks = new ArrayList<>();                         // {start, end} of each word of the text
        for (int i = 0; i < text.length(); ) {
            if (Character.isWhitespace(text.charAt(i))) { i++; continue; }
            int s = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            toks.add(new int[]{s, i});
        }
        int n = toks.size(), m = boxes.size();
        if (n == 0 || m == 0) return null;
        double chars = 0, width = 0;
        for (int[] t : toks) chars += t[1] - t[0];
        for (Word b : boxes) width += b.x1 - b.x0;
        double k = width / chars;                                     // pixels per character, roughly
        final double SKIP = 1.6;
        double[][] d = new double[n + 1][m + 1];
        int[][] how = new int[n + 1][m + 1];
        for (double[] row : d) java.util.Arrays.fill(row, Double.MAX_VALUE / 4);
        d[0][0] = 0;
        for (int i = 0; i <= n; i++) for (int j = 0; j <= m; j++) {
            double here = d[i][j];
            if (here >= Double.MAX_VALUE / 8) continue;
            if (i < n && j < m) relax(d, how, i + 1, j + 1, here + fit(boxes.get(j).x1 - boxes.get(j).x0, k * len(toks.get(i))), 1);
            if (i < n && j + 1 < m && boxes.get(j).rowEnd)               // a word broken over two rows (hyphenated)
                relax(d, how, i + 1, j + 2, here + 0.4 + fit(boxes.get(j).x1 - boxes.get(j).x0 + boxes.get(j + 1).x1 - boxes.get(j + 1).x0, k * len(toks.get(i))), 2);
            if (i + 1 < n && j < m)                                        // two words with no visible gap ("Earth—my")
                relax(d, how, i + 2, j + 1, here + 0.6 + fit(boxes.get(j).x1 - boxes.get(j).x0, k * (len(toks.get(i)) + len(toks.get(i + 1)))), 3);
            if (j < m) relax(d, how, i, j + 1, here + SKIP, 4);         // ink that isn't a word (a mark, a picture)
            if (i < n) relax(d, how, i + 1, j, here + SKIP, 5);         // a word not found
        }
        if (d[n][m] / Math.max(1, n) > 0.55) return null;            // didn't line up: don't trust it
        float[][] out = new float[text.length()][];
        int i = n, j = m;
        while (i > 0 || j > 0) {
            int h = how[i][j];
            if (h == 1) { place(out, toks.get(i - 1), boxes.get(j - 1), null); i--; j--; }
            else if (h == 2) { place(out, toks.get(i - 1), boxes.get(j - 2), boxes.get(j - 1)); i--; j -= 2; }
            else if (h == 3) {
                int[] a = toks.get(i - 2), b = toks.get(i - 1);
                Word w = boxes.get(j - 1);
                int split = w.x0 + (int) ((w.x1 - w.x0) * (len(a) / (double) (len(a) + len(b))));
                place(out, a, new Word(w.x0, w.y0, split, w.y1, false), null);
                place(out, b, new Word(split, w.y0, w.x1, w.y1, false), null);
                i -= 2; j--;
            }
            else if (h == 4) j--;
            else if (h == 5) i--;
            else break;
        }
        return out;
    }

    private static int len(int[] t) { return t[1] - t[0]; }
    private static double fit(double have, double want) { return Math.abs(Math.log(Math.max(1, have) / Math.max(1, want))); }
    private static void relax(double[][] d, int[][] how, int i, int j, double v, int h) { if (v < d[i][j]) { d[i][j] = v; how[i][j] = h; } }

    /** The characters of a word, spread across its box (or two boxes, for a word broken over rows). */
    private static void place(float[][] out, int[] tok, Word a, Word b) {
        int n = tok[1] - tok[0];
        double wa = a.x1 - a.x0, wb = b == null ? 0 : b.x1 - b.x0, total = wa + wb;
        for (int c = 0; c < n; c++) {
            double s = total * c / n, e = total * (c + 1) / n;
            Word w = s < wa || b == null ? a : b;
            double off = w == a ? 0 : wa;
            out[tok[0] + c] = new float[]{(float) (w.x0 + Math.max(0, s - off)), w.y0, (float) (w.x0 + Math.min(w.x1 - w.x0, e - off)), w.y1};
        }
    }

    static int lum(int argb) { return (((argb >> 16) & 255) * 3 + ((argb >> 8) & 255) * 6 + (argb & 255)) / 10; }

    /** The page's colour: the most common brightness. */
    static int background(int[] px) {
        int[] hist = new int[256];
        for (int i = 0; i < px.length; i += 3) hist[lum(px[i])]++;
        int best = 0;
        for (int v = 1; v < 256; v++) if (hist[v] > hist[best]) best = v;
        return best;
    }
}
