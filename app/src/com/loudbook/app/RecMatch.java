package com.loudbook.app;

import java.util.List;

/**
 * Which recorded sentence each of the reader's sentences is (BookAudio): the longest run of equal sentences in order
 * (a longest common subsequence), so a repeated short line ("Yes.") can't pull the match far ahead and leave the
 * sentences in between to the voice, and a stretch the reader doesn't have (a title block, an author's note) doesn't
 * stop every later sentence from matching. Empty sentences never match. Pure Java (tested in UnitTest).
 */
final class RecMatch {
    /** For each of mine, the index of its sentence in rec, or -1. */
    static int[] align(List<String> mine, List<String> rec) {
        int n = mine.size(), m = rec.size();
        int[] out = new int[n];
        java.util.Arrays.fill(out, -1);
        if (n == 0 || m == 0) return out;
        if ((long) n * m > 6_000_000L) return greedy(mine, rec, out);      // (a huge chapter: the old way, a window ahead)
        // L[i][j] = longest run in order of mine[i..] and rec[j..]
        int[][] L = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            String a = mine.get(i);
            boolean any = !a.isEmpty();
            int[] row = L[i], below = L[i + 1];
            for (int j = m - 1; j >= 0; j--) {
                int best = Math.max(below[j], row[j + 1]);
                if (any && a.equals(rec.get(j))) best = Math.max(best, below[j + 1] + 1);
                row[j] = best;
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            String a = mine.get(i);
            if (!a.isEmpty() && a.equals(rec.get(j)) && L[i][j] == L[i + 1][j + 1] + 1) { out[i] = j; i++; j++; }
            else if (L[i + 1][j] >= L[i][j + 1]) i++;
            else j++;
        }
        return out;
    }

    private static int[] greedy(List<String> mine, List<String> rec, int[] out) {
        int s = 0;
        for (int k = 0; k < mine.size() && s < rec.size(); k++) {
            String w = mine.get(k);
            if (w.isEmpty()) continue;
            for (int look = s; look < Math.min(rec.size(), s + 40); look++)
                if (rec.get(look).equals(w)) { out[k] = look; s = look + 1; break; }
        }
        return out;
    }

    private RecMatch() { }
}
