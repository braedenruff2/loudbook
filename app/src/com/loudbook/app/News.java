package com.loudbook.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "What's new" on the start page (web/new.txt: newest first, a line per update, each line several changes). Shown one
 * change at a time, and only the changes not seen yet: "Got it" (or ✕) remembers each change it showed, so an update that
 * adds one line shows that line alone (it showed the whole file again, every update).
 */
final class News {
    /** Words ending in a full stop that don't end a change ("Mr. He", "e.g. Ext."). */
    private static final Set<String> ABBR = new HashSet<>(Arrays.asList(
        "mr", "mrs", "ms", "dr", "st", "lt", "sgt", "rt", "rte", "ext", "vs", "etc", "e.g", "i.e", "u.k", "u.s", "no", "vol", "ch", "&c"));

    /** The changes in new.txt, newest first: each line cut at its sentences. */
    static List<String> items(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            int start = 0, paren = 0;
            boolean quote = false;                                   // (inside "..." or “...”: an example, not the end)
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(') paren++;
                else if (c == ')') paren = Math.max(0, paren - 1);
                else if (c == '“') quote = true;
                else if (c == '”') quote = false;
                else if (c == '"') quote = !quote;
                if (c != '.' && c != '!' && c != '?' || paren > 0) continue;
                // (a quote closing right after the stop ends with it: "... read right." is fine)
                if (quote && !(i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '”'))) continue;
                int j = i + 1;
                while (j < line.length() && "\"'”’)".indexOf(line.charAt(j)) >= 0) j++;
                if (j >= line.length() || line.charAt(j) != ' ') continue;
                int k = j + 1;
                if (k >= line.length() || !(Character.isUpperCase(line.charAt(k)) || "\"“‘(".indexOf(line.charAt(k)) >= 0)) continue;
                if (c == '.' && j == i + 1) {
                    int w = i;
                    while (w > 0 && line.charAt(w - 1) != ' ' && "\"“(‘".indexOf(line.charAt(w - 1)) < 0) w--;
                    String word = line.substring(w, i).toLowerCase(java.util.Locale.ROOT);
                    if (ABBR.contains(word) || word.length() == 1) continue;       // (an initial: "J. K.")
                }
                add(out, line.substring(start, j));
                start = k;
            }
            add(out, line.substring(start));
        }
        return out;
    }
    private static void add(List<String> out, String s) { s = s.trim(); if (!s.isEmpty()) out.add(s); }

    /** A change's key for the seen set (its text, so a change kept when its line grows stays seen). */
    static String key(String item) { return Integer.toHexString(item.hashCode()); }

    /** The changes not seen yet, newest first, at most max of them. */
    static List<String> unseen(String text, Set<String> seen, int max) {
        List<String> out = new ArrayList<>();
        for (String it : items(text)) if (!seen.contains(key(it))) { out.add(it); if (out.size() >= max) break; }
        return out;
    }

    /** The seen set once everything in text was seen. */
    static Set<String> allSeen(String text, Set<String> seen) {
        Set<String> s = new HashSet<>(seen);
        for (String it : items(text)) s.add(key(it));
        return s;
    }

    private News() { }
}
