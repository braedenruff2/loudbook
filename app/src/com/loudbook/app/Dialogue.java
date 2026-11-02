package com.loudbook.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds what characters say (the parts in quotation marks), so it can be read in a second voice.
 * A quote can run over several sentences of a paragraph, so whether a sentence starts inside one
 * is worked out from the sentences before it. Plain Java.
 */
final class Dialogue {
    /** Does sentence i start inside a quotation (one opened earlier in the same paragraph)? */
    static boolean openAt(Chapter ch, int i) {
        if (i <= 0 || i >= ch.size()) return false;
        int block = ch.chunks.get(i).block;
        if (block < 0) return false;
        int k = i;
        while (k > 0 && ch.chunks.get(k - 1).block == block) k--;
        boolean open = false, single = singles(ch, i);
        for (int j = k; j < i; j++) open = run(ch.chunks.get(j).say, open, single);
        return open;
    }

    /**
     * Does sentence i's paragraph quote speech in single quotes (‘Yes,’ she said: British style)? Only when it has no
     * double quotes at all and a single quote opens somewhere (an apostrophe alone doesn't count).
     */
    static boolean singles(Chapter ch, int i) {
        if (i < 0 || i >= ch.size()) return false;
        int block = ch.chunks.get(i).block;
        if (block < 0) return false;
        int k = i, e = i;
        while (k > 0 && ch.chunks.get(k - 1).block == block) k--;
        while (e + 1 < ch.size() && ch.chunks.get(e + 1).block == block) e++;
        StringBuilder b = new StringBuilder();
        for (int j = k; j <= e; j++) b.append(ch.chunks.get(j).say).append(' ');
        return singles(b.toString());
    }
    static boolean singles(String para) {
        if (para.indexOf('“') >= 0 || para.indexOf('”') >= 0 || para.indexOf('"') >= 0) return false;
        for (int c = 0; c < para.length(); c++) if (opensSingle(para, c)) return true;
        return false;
    }
    /**
     * Where single quotes open (1) and close (-1) speech in this text. A quote closes at the first clear close (,’ .’ !’ or
     * ’ before punctuation); if none comes before the next opening (or the end), at the first ’ before a space instead
     * (saying ‘Yes’ to all this), which otherwise could be a plural's apostrophe (the boys’ dog).
     */
    static byte[] singleMarks(String t, boolean open) {
        byte[] m = new byte[t.length()];
        int c = 0;
        while (c < t.length()) {
            if (!open) { if (opensSingle(t, c)) { m[c] = 1; open = true; } c++; continue; }
            int strict = -1, loose = -1, next = t.length();
            for (int d = c; d < t.length(); d++) {
                if (d > c && opensSingle(t, d)) { next = d; break; }
                if (closesSingle(t, d)) { strict = d; break; }
                char ch = t.charAt(d);
                if (loose < 0 && (ch == '’' || ch == '\'') && d + 1 < t.length() && Character.isWhitespace(t.charAt(d + 1))) loose = d;
            }
            int close = strict >= 0 ? strict : loose >= 0 && loose < next ? loose : -1;
            if (close < 0) { if (next >= t.length()) break; c = next; continue; }   // (still open here; or a new one opens)
            m[close] = -1; open = false; c = close + 1;
        }
        return m;
    }
    /** A single quote opening speech: at the start or after a space/bracket/dash, not an apostrophe ('em, '90s, 'tis). */
    private static boolean opensSingle(String t, int c) {
        char ch = t.charAt(c);
        if (ch != '‘' && ch != '\'') return false;
        if (c > 0 && "( \t\n[—–".indexOf(t.charAt(c - 1)) < 0) return false;
        if (c + 1 >= t.length() || !Character.isLetter(t.charAt(c + 1)) && "…—".indexOf(t.charAt(c + 1)) < 0) return false;
        String rest = t.substring(c + 1, Math.min(t.length(), c + 7)).toLowerCase(java.util.Locale.ROOT);
        return !rest.matches("^(em|tis|twas|twere|cause|cos|til|till|n|nuff|bout|round|ello|ere)\\b.*");
    }
    /** A single quote closing speech: after the quote's last punctuation (,’ .’ !’), or before punctuation; not "the boys’ dog". */
    private static boolean closesSingle(String t, int c) {
        char ch = t.charAt(c);
        if (ch != '’' && ch != '\'') return false;
        if (c > 0 && ",.!?…—–-;:".indexOf(t.charAt(c - 1)) >= 0) return true;
        return c + 1 >= t.length() || ",.;:!?—–…)".indexOf(t.charAt(c + 1)) >= 0
            || Character.isWhitespace(t.charAt(c + 1)) && c > 0 && !Character.isLetter(t.charAt(c - 1));
    }

    /** Whether a quote is still open at the end of this text. */
    static boolean run(String t, boolean open) { return run(t, open, false); }
    static boolean run(String t, boolean open, boolean single) {
        byte[] marks = null;
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            if (single) { if (marks == null) marks = singleMarks(t, open); if (marks[c] != 0) open = marks[c] > 0; continue; }
            if (ch == '“') open = true;
            else if (ch == '”') open = false;
            else if (ch == '"') open = !open;
        }
        return open;
    }

    /** The text in pieces: {text, "q"} for quoted speech, {text, "n"} for narration. */
    static List<String[]> split(String t, boolean open) { return split(t, open, false); }
    /** single: the paragraph quotes in single quotes (see singles). */
    static List<String[]> split(String t, boolean open, boolean single) {
        byte[] marks = null;
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            if (single && marks == null) marks = singleMarks(t, open);
            boolean opens = single ? marks[c] > 0 : ch == '“' || (ch == '"' && !open);
            boolean closes = single ? marks[c] < 0 : ch == '”' || (ch == '"' && open);
            if (opens && !open) { add(out, cur, false); open = true; continue; }
            if (closes && open) { add(out, cur, true); open = false; continue; }
            cur.append(ch);
        }
        add(out, cur, open);
        if (out.isEmpty()) out.add(new String[]{t, "n"});
        return out;
    }

    private static void add(List<String[]> out, StringBuilder cur, boolean quoted) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.codePoints().anyMatch(Character::isLetterOrDigit)) {
            // only punctuation (", " between the quote and "he said"): keep it with what came before
            if (!s.isEmpty() && !out.isEmpty()) out.get(out.size() - 1)[0] += s;
            return;
        }
        // two pieces in a row in the same voice: one piece
        if (!out.isEmpty() && out.get(out.size() - 1)[1].equals(quoted ? "q" : "n")) { out.get(out.size() - 1)[0] += " " + s; return; }
        out.add(new String[]{s, quoted ? "q" : "n"});
    }
}
