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
        boolean open = false;
        for (int j = k; j < i; j++) open = run(ch.chunks.get(j).say, open);
        return open;
    }

    /** Whether a quote is still open at the end of this text. */
    static boolean run(String t, boolean open) {
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            if (ch == '“') open = true;
            else if (ch == '”') open = false;
            else if (ch == '"') open = !open;
        }
        return open;
    }

    /** The text in pieces: {text, "q"} for quoted speech, {text, "n"} for narration. */
    static List<String[]> split(String t, boolean open) {
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            boolean opens = ch == '“' || (ch == '"' && !open);
            boolean closes = ch == '”' || (ch == '"' && open);
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
