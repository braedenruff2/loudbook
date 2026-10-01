package com.loudbook.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of Kindle pages into sentences to read. A sentence that runs over the page break
 * is held back until the next page arrives, so it's read whole. Plain Java.
 */
final class KindleText {
    private String carry = "";
    private int block = 0;
    private final String[][] fixes;

    KindleText(String fixesText) {
        List<String[]> f = new ArrayList<>();
        if (fixesText != null) for (String line : fixesText.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1) f.add(new String[]{line.substring(0, eq).trim(), line.substring(eq + 1).trim()});
        }
        fixes = f.toArray(new String[0][]);
    }

    private static final Pattern SENT = Pattern.compile("(?<=[.!?…][\"'”’)\\]]{0,2})\\s+(?=[\"'“‘(\\[]?[A-Z0-9])");
    private static final Pattern END = Pattern.compile("[.!?…][\"'”’)\\]]{0,2}\\s*$");

    /** Sentences from a page's paragraphs. last: no more pages, so nothing is held back. */
    List<Chapter.Chunk> chunks(List<String> paragraphs, boolean last) {
        List<String> paras = new ArrayList<>();
        for (String p : paragraphs) { String t = p.replaceAll("\\s+", " ").trim(); if (!t.isEmpty()) paras.add(t); }
        if (!carry.isEmpty()) {
            if (paras.isEmpty()) paras.add(carry);
            else paras.set(0, carry + (startsLower(paras.get(0)) || !END.matcher(carry).find() ? " " : " ") + paras.get(0));
            carry = "";
        }
        if (!last && !paras.isEmpty()) {
            // the page ends mid-sentence: keep the unfinished part for the next page
            String lp = paras.get(paras.size() - 1);
            if (!END.matcher(lp).find()) {
                String[] parts = SENT.split(lp);
                carry = parts[parts.length - 1];
                String rest = lp.substring(0, lp.length() - carry.length()).trim();
                if (rest.isEmpty()) paras.remove(paras.size() - 1); else paras.set(paras.size() - 1, rest);
            }
        }
        List<Chapter.Chunk> out = new ArrayList<>();
        for (String p : paras) {
            for (String s : sentences(p)) out.add(new Chapter.Chunk(block, s, say(s)));
            block++;
        }
        return out;
    }

    private static boolean startsLower(String s) { return !s.isEmpty() && Character.isLowerCase(s.charAt(0)); }

    /** A paragraph's sentences: short ones joined, long ones split at a comma or similar. */
    static List<String> sentences(String p) {
        List<String> raw = new ArrayList<>();
        for (String s : SENT.split(p)) if (!s.trim().isEmpty()) raw.add(s.trim());
        List<String> merged = new ArrayList<>();
        StringBuilder pend = new StringBuilder();
        for (String s : raw) {
            if (pend.length() > 0) pend.append(' ');
            pend.append(s);
            if (pend.length() >= 20) { merged.add(pend.toString()); pend.setLength(0); }
        }
        if (pend.length() > 0) { if (merged.isEmpty()) merged.add(pend.toString()); else merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + pend); }
        List<String> out = new ArrayList<>();
        for (String s : merged) {
            while (s.length() > 300) {
                int cut = -1;
                Matcher m = Pattern.compile("[,;:—–]\\s").matcher(s);
                while (m.find()) { if (m.end() > 300) break; if (m.end() > 80) cut = m.end(); }
                if (cut < 0) { int sp = s.lastIndexOf(' ', 280); cut = sp > 80 ? sp + 1 : 280; }
                out.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String say(String s) {
        String t = s;
        for (String[] f : fixes) t = t.replaceAll("(?i)\\b" + Pattern.quote(f[0]) + "\\b", Matcher.quoteReplacement(f[1]));
        return t;
    }
}
