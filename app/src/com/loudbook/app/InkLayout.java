package com.loudbook.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Where the words of a page are, read off a picture of the screen: Kindle doesn't say where its
 * words are, but on screen each row of text is a band of "ink" and each word a run of ink with a
 * gap after it. The text's words are then matched to the ink's words in order (allowing for a word
 * split over two rows, two run together, and ink that isn't the book's text: the clock, "5 mins
 * left", a picture), which gives each character a box. Only shapes are looked at (no reading of
 * letters), and nothing is kept. Plain Java.
 */
final class InkLayout {
    /** {x0, y0, x1, y1} of a word on screen. */
    static final class Word {
        final int x0, y0, x1, y1; final boolean rowEnd;
        Word(int a, int b, int c, int d, boolean e) { x0 = a; y0 = b; x1 = c; y1 = d; rowEnd = e; }
        int width() { return x1 - x0; }
        @Override public String toString() { return "[" + x0 + "," + y0 + " " + (x1 - x0) + "x" + (y1 - y0) + (rowEnd ? " end" : "") + "]"; }
    }

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
        int typical = median(bands, 0);
        List<int[]> rows = new ArrayList<>();
        for (int[] b : bands) {
            if (!rows.isEmpty()) {
                int[] last = rows.get(rows.size() - 1);
                if (b[0] - last[1] < typical * 0.35 && (b[1] - b[0] < typical * 0.5 || last[1] - last[0] < typical * 0.5)) { last[1] = b[1]; continue; }
            }
            rows.add(new int[]{b[0], b[1]});
        }
        typical = median(rows, Math.max(4, typical / 3));
        // each row's runs of ink (split at every empty column), and the gaps between them
        List<int[]> keptRows = new ArrayList<>();
        List<List<int[]>> rowRuns = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        for (int[] r : rows) {
            int rh = r[1] - r[0];
            if (rh < Math.max(4, typical * 0.4)) continue;              // a speck
            int[] col = new int[w];
            for (int x = 0; x < w; x++) { int n = 0; for (int y = r[0]; y < r[1]; y++) if (ink[y * w + x]) n++; col[x] = n; }
            List<int[]> runs = new ArrayList<>();
            for (int x = 0; x < w; ) {
                if (col[x] == 0) { x++; continue; }
                int s = x;
                while (x < w && col[x] > 0) x++;
                runs.add(new int[]{s, x});
            }
            for (int k = 1; k < runs.size(); k++) gaps.add(runs.get(k)[0] - runs.get(k - 1)[1]);
            keptRows.add(r);
            rowRuns.add(runs);
        }
        int gapMin = wordGap(gaps, typical);
        for (int ri = 0; ri < keptRows.size(); ri++) {
            int[] r = keptRows.get(ri);
            List<int[]> runs = rowRuns.get(ri);
            List<int[]> ws = new ArrayList<>();
            int rh = r[1] - r[0];
            for (int[] q : runs) {
                int gap = ws.isEmpty() ? Integer.MAX_VALUE : q[0] - ws.get(ws.size() - 1)[1];
                // a full stop or comma set a little apart still belongs to the word before it
                boolean mark = gap < gapMin * 2.5 && q[1] - q[0] < rh * 0.25 && inkHeight(ink, w, q, r) < rh * 0.35;
                if (gap < gapMin || mark) ws.get(ws.size() - 1)[1] = q[1];
                else ws.add(new int[]{q[0], q[1]});
            }
            for (int k = 0; k < ws.size(); k++)
                out.add(new Word(offX + ws.get(k)[0], offY + r[0], offX + ws.get(k)[1], offY + r[1], k == ws.size() - 1));
        }
        return out;
    }

    private static int inkHeight(boolean[] ink, int w, int[] q, int[] r) {
        int top = -1, bottom = -1;
        for (int y = r[0]; y < r[1]; y++) for (int x = q[0]; x < q[1]; x++) if (ink[y * w + x]) { if (top < 0) top = y; bottom = y; break; }
        return top < 0 ? 0 : bottom - top + 1;
    }

    /**
     * The narrowest gap that's a space between words: the gaps in a page of text come in two sizes
     * (between letters, between words), so they're split in two groups and the line drawn between.
     */
    static int wordGap(List<Integer> gaps, int rowHeight) {
        int lo = Math.max(3, Math.round(rowHeight * 0.1f)), hi = Math.max(lo + 1, Math.round(rowHeight * 0.45f));
        if (gaps.size() < 6) return Math.max(lo, Math.min(hi, Math.round(rowHeight * 0.22f)));
        int[] g = new int[gaps.size()];
        for (int i = 0; i < g.length; i++) g[i] = gaps.get(i);
        Arrays.sort(g);
        // (huge gaps, between columns or around a picture, aren't spaces: leave them out)
        int top = g.length;
        while (top > 1 && g[top - 1] > rowHeight * 2) top--;
        // the cut that best splits the gaps in two tight groups (Otsu)
        double best = -1; int cut = Math.round(rowHeight * 0.22f);
        double total = 0;
        for (int i = 0; i < top; i++) total += g[i];
        double left = 0;
        for (int i = 0; i + 1 < top; i++) {
            left += g[i];
            if (g[i + 1] == g[i]) continue;
            int nl = i + 1, nr = top - nl;
            double ml = left / nl, mr = (total - left) / nr;
            double between = (double) nl * nr * (ml - mr) * (ml - mr);
            if (between > best) { best = between; cut = g[i + 1]; }
        }
        return Math.max(lo, Math.min(hi, cut));
    }

    private static int median(List<int[]> spans, int floor) {
        List<Integer> hs = new ArrayList<>();
        for (int[] b : spans) if (b[1] - b[0] >= floor) hs.add(b[1] - b[0]);
        if (hs.isEmpty()) for (int[] b : spans) hs.add(b[1] - b[0]);
        Collections.sort(hs);
        return hs.get(hs.size() / 2);
    }

    /** One piece of text's character boxes (see {@link #pageBoxes}); null if it didn't line up. */
    static float[][] charBoxes(String text, List<Word> boxes) {
        List<float[][]> r = pageBoxes(Collections.singletonList(text), boxes);
        return r == null ? null : r.get(0);
    }

    /**
     * Each character's box, for every piece of text on the page (in reading order): the text's words
     * matched, in order, to the words on screen. null if they don't match well enough to trust; a
     * piece gets null if none of its words were found (it isn't on screen).
     */
    static List<float[][]> pageBoxes(List<String> texts, List<Word> boxes) {
        List<int[]> toks = new ArrayList<>();                          // {piece, start, end} of each word of the text
        for (int p = 0; p < texts.size(); p++) {
            String text = texts.get(p);
            for (int i = 0; i < text.length(); ) {
                if (Character.isWhitespace(text.charAt(i))) { i++; continue; }
                int s = i;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
                toks.add(new int[]{p, s, i});
            }
        }
        int n = toks.size(), m = boxes.size();
        if (n == 0 || m == 0 || (long) n * m > 3_000_000L) return null;
        double[] tw = new double[n];
        for (int i = 0; i < n; i++) { int[] t = toks.get(i); tw[i] = weight(texts.get(t[0]), t[1], t[2]); }
        // pixels per (average) letter, roughly: a typical word on screen against a typical word of
        // the text (the screen may show more than the text, or less)
        double[] bw = new double[m], tws = tw.clone();
        for (int j = 0; j < m; j++) bw[j] = boxes.get(j).width();
        Arrays.sort(bw); Arrays.sort(tws);
        double k = bw[m / 2] / tws[n / 2];
        Fit f = null;
        for (int pass = 0; pass < 3; pass++) {                         // then again with that measured on the words that matched
            f = align(tw, boxes, k);
            if (f.matchedWeight <= 0) return null;
            double nk = f.matchedWidth / f.matchedWeight;
            if (Math.abs(nk - k) < k * 0.02) break;
            k = nk;
        }
        if (f.matched < Math.max(2, n * 0.6) || f.fitSum / f.matched > 0.35) return null;   // didn't line up: don't trust it
        List<float[][]> out = new ArrayList<>();
        for (String t : texts) out.add(new float[t.length()][]);
        boolean[] any = new boolean[texts.size()];
        for (int[] mv : f.moves) {                                     // {kind, token, box}
            int i = mv[1], j = mv[2];
            if (mv[0] == 1) { place(out, texts, toks.get(i), boxes.get(j), null); any[toks.get(i)[0]] = true; }
            else if (mv[0] == 2) { place(out, texts, toks.get(i), boxes.get(j), boxes.get(j + 1)); any[toks.get(i)[0]] = true; }
            else if (mv[0] == 3) {
                int[] a = toks.get(i), b = toks.get(i + 1);
                Word w = boxes.get(j);
                int split = w.x0 + (int) Math.round(w.width() * (tw[i] / (tw[i] + tw[i + 1])));
                place(out, texts, a, new Word(w.x0, w.y0, split, w.y1, false), null);
                place(out, texts, b, new Word(split, w.y0, w.x1, w.y1, false), null);
                any[a[0]] = any[b[0]] = true;
            }
        }
        for (int p = 0; p < any.length; p++) if (!any[p]) out.set(p, null);
        return out;
    }

    private static final class Fit { List<int[]> moves = new ArrayList<>(); int matched; double fitSum, matchedWidth, matchedWeight; }

    // costs: ink that isn't a word costs little at the top and bottom of the page (clock, page
    // number) and more in the middle; text missing from the screen likewise (the start of a page's
    // first piece may be on the page before)
    private static final double EDGE_BOX = 0.05, EDGE_TOK = 0.3, MID_BOX = 0.8, MID_TOK = 1.6;

    private static Fit align(double[] tw, List<Word> boxes, double k) {
        int n = tw.length, m = boxes.size(), W = m + 1;
        double[] d = new double[(n + 1) * W];
        byte[] how = new byte[(n + 1) * W];
        for (int i = 0; i <= n; i++) for (int j = 0; j <= m; j++) d[i * W + j] = EDGE_TOK * i + EDGE_BOX * j;   // (how 0: start here)
        for (int i = 0; i <= n; i++) for (int j = 0; j <= m; j++) {
            double here = d[i * W + j];
            if (i < n && j < m) relax(d, how, (i + 1) * W + j + 1, here + fit(boxes.get(j).width(), k * tw[i]), 1);
            if (i < n && j + 1 < m && boxes.get(j).rowEnd)                  // a word broken over two rows (hyphenated)
                relax(d, how, (i + 1) * W + j + 2, here + 0.3 + fit(boxes.get(j).width() + boxes.get(j + 1).width(), k * tw[i]), 2);
            if (i + 1 < n && j < m)                                           // two words with no visible gap ("Earth— my")
                relax(d, how, (i + 2) * W + j + 1, here + 0.6 + fit(boxes.get(j).width(), k * (tw[i] + tw[i + 1])), 3);
            if (j < m) relax(d, how, i * W + j + 1, here + MID_BOX, 4);
            if (i < n) relax(d, how, (i + 1) * W + j, here + MID_TOK, 5);
        }
        int bi = 0, bj = 0; double best = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) for (int j = 0; j <= m; j++) {
            double v = d[i * W + j] + EDGE_TOK * (n - i) + EDGE_BOX * (m - j);
            if (v < best) { best = v; bi = i; bj = j; }
        }
        Fit f = new Fit();
        int i = bi, j = bj;
        while (i > 0 || j > 0) {
            int h = how[i * W + j];
            if (h == 1) { f.moves.add(new int[]{1, i - 1, j - 1}); count(f, boxes.get(j - 1).width(), tw[i - 1], k, 1); i--; j--; }
            else if (h == 2) { f.moves.add(new int[]{2, i - 1, j - 2}); count(f, boxes.get(j - 2).width() + boxes.get(j - 1).width(), tw[i - 1], k, 1); i--; j -= 2; }
            else if (h == 3) { f.moves.add(new int[]{3, i - 2, j - 1}); count(f, boxes.get(j - 1).width(), tw[i - 2] + tw[i - 1], k, 2); i -= 2; j--; }
            else if (h == 4) j--;
            else if (h == 5) i--;
            else break;                                                       // the start
        }
        return f;
    }

    private static void count(Fit f, double width, double weight, double k, int tokens) {
        f.matched += tokens; f.fitSum += fit(width, k * weight) * tokens; f.matchedWidth += width; f.matchedWeight += weight;
    }

    private static double fit(double have, double want) { return Math.abs(Math.log(Math.max(1, have) / Math.max(1, want))); }
    private static void relax(double[] d, byte[] how, int at, double v, int h) { if (v < d[at]) { d[at] = v; how[at] = (byte) h; } }

    /** About how wide a letter is, against an average one (an "i" is narrow, an "m" wide). */
    static double letter(char c) {
        if ("iljtfrI.,;:'!|’‘()".indexOf(c) >= 0) return 0.55;
        if ("mwMW—".indexOf(c) >= 0) return 1.5;
        if (Character.isUpperCase(c)) return 1.25;
        if (c == '"' || c == '“' || c == '”' || c == '-') return 0.7;
        return 1;
    }
    private static double weight(String t, int s, int e) { double x = 0; for (int c = s; c < e; c++) x += letter(t.charAt(c)); return Math.max(0.5, x); }

    /** The characters of a word, spread across its box (or two boxes, for a word broken over rows). */
    private static void place(List<float[][]> out, List<String> texts, int[] tok, Word a, Word b) {
        String t = texts.get(tok[0]);
        float[][] o = out.get(tok[0]);
        double total = weight(t, tok[1], tok[2]);
        double wa = a.width(), wb = b == null ? 0 : b.width(), px = wa + wb;
        double at = 0;
        for (int c = tok[1]; c < tok[2]; c++) {
            double s = px * at / total;
            at += letter(t.charAt(c));
            double e = px * at / total;
            Word w = s < wa || b == null ? a : b;
            double off = w == a ? 0 : wa;
            o[c] = new float[]{(float) (w.x0 + Math.max(0, s - off)), w.y0, (float) (w.x0 + Math.min(w.width(), e - off)), w.y1};
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
