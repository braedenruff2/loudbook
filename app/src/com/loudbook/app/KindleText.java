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
    private static final Pattern ABBREV = Pattern.compile("(\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|Mt|vs|etc|No|Vol|Ch|Lt|Col|Gen|Sgt|Capt|Rev|Hon|approx|lvl|Lv|Lvl)|(^|\\s)[A-Z])\\.$");
    private static final Pattern END = Pattern.compile("[.!?…][\"'”’)\\]]{0,2}\\s*$");

    /** Sentences from a page's paragraphs. last: no more pages, so nothing is held back. */
    List<Chapter.Chunk> chunks(List<String> paragraphs, boolean last) {
        List<String> paras = new ArrayList<>();
        for (String p : paragraphs) { String t = p.replaceAll("\\s+", " ").trim(); if (!t.isEmpty()) paras.add(t); }
        if (skip != null) {
            // the start of this page was already read with the sentence that ran over the page
            // break (see bridge): carry on after it
            String s0 = paras.isEmpty() ? "" : paras.get(0);
            int after = s0.startsWith(skip) ? skip.length() : afterLetters(s0, skip);
            if (after >= 0) {
                String rest0 = s0.substring(after).trim();
                if (rest0.isEmpty()) paras.remove(0); else paras.set(0, rest0);
                carry = "";
            }
            // (no next page after all, but the sentence over the break was read whole already: not again)
            if (paras.isEmpty()) carry = "";
            skip = null;
        }
        if (!carry.isEmpty()) {
            if (paras.isEmpty()) paras.add(carry);
            else paras.set(0, joinBreak(carry, paras.get(0)));
            carry = "";
        }
        if (!last && !paras.isEmpty()) {
            // the page ends mid-sentence: keep the unfinished part for the next page
            String lp = paras.get(paras.size() - 1);
            if (!END.matcher(lp).find()) {
                carry = lastSentence(lp);
                String rest = lp.substring(0, lp.length() - carry.length()).trim();
                if (rest.isEmpty()) paras.remove(paras.size() - 1); else paras.set(paras.size() - 1, rest);
            }
        }
        List<Chapter.Chunk> out = new ArrayList<>();
        for (String p : paras) {
            List<String> ss = sentences(p);
            // the page's first sentence is what's waited for after a page turn: make its first
            // piece short (at a comma, or before "and"/"but"...), so the voice can start sooner
            if (out.isEmpty() && !ss.isEmpty()) { List<String> q = quickStart(ss.get(0)); ss.remove(0); ss.addAll(0, q); }
            for (String s : ss) out.add(new Chapter.Chunk(block, s, say(s)));
            block++;
        }
        return out;
    }

    /** The last sentence of a paragraph ("Mr." and initials don't end one). */
    static String lastSentence(String p) {
        String[] parts = SENT.split(p);
        String last = parts[parts.length - 1];
        for (int i = parts.length - 2; i >= 0 && ABBREV.matcher(parts[i]).find(); i--) last = parts[i] + " " + last;
        return p.substring(Math.max(0, p.length() - last.length()));
    }
    /** The first sentence of a paragraph. */
    static String firstSentence(String p) {
        String[] parts = SENT.split(p);
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length && ABBREV.matcher(parts[i - 1]).find(); i++) b.append(' ').append(parts[i]);
        int n = Math.min(p.length(), b.length());
        return p.substring(0, n);
    }

    private String skip;
    /**
     * Where s ends up after the letters of head, if s starts with them (marks, spaces and quotes may
     * differ: "said \u201cno.\u201d" and "said \"no.\""); the marks right after them go too. -1 if not.
     */
    static int afterLetters(String s, String head) {
        String h = KindleLayout.normOf(head);
        if (h.isEmpty()) return -1;
        int k = 0, i = 0;
        for (; i < s.length() && k < h.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c)) continue;
            if (Character.toLowerCase(c) != h.charAt(k)) return -1;
            k++;
        }
        if (k < h.length()) return -1;
        while (i < s.length() && ".,;:!?\u2026\"'\u201d\u2019)]".indexOf(s.charAt(i)) >= 0) i++;
        return i;
    }
    /** Length of the bridge's part on this page, for when to turn it. */
    int bridgeHead;
    /**
     * The sentence that runs over the page break, whole, when the start of the next page is known
     * (Kindle shares it, unseen, with screen readers): it's read before the page is turned, and the
     * page turns when the voice gets to the words on the next page. null if there's none (then the
     * unfinished part waits for the next page, as before).
     */
    Chapter.Chunk bridge(String nextStart) {
        if (carry.isEmpty() || nextStart == null) return null;
        String n = nextStart.replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) return null;
        String first = firstSentence(n);
        String whole = joinBreak(carry, first);
        skip = first;
        bridgeHead = carry.length();
        return new Chapter.Chunk(block, whole, say(whole));
    }

    private static final Pattern PAUSE = Pattern.compile("[,;:\u2014\u2013]\\s");
    private static final Pattern JOIN = Pattern.compile("\\s(?=(and|but|so|then|when|while|because|until|though|as|or|which|where)\\s)");
    /** A long sentence as a short first piece and the rest (in a few pieces if long), cut where a reader would breathe; else as it is. */
    static List<String> quickStart(String s) {
        List<String> out = new ArrayList<>();
        int cut = -1;
        if (s.length() >= 70) {
            Matcher m = PAUSE.matcher(s);
            while (m.find()) { if (m.end() > 90) break; if (m.end() >= 18) { cut = m.end(); break; } }
            if (cut < 0) {
                m = JOIN.matcher(s);
                while (m.find()) { if (m.start() > 90) break; if (m.start() >= 25) { cut = m.start(); break; } }
            }
            if (cut > 0 && s.length() - cut < 20) cut = -1;           // the rest too short to say on its own
        }
        if (cut < 0) { out.add(s); return out; }
        out.add(s.substring(0, cut).trim());
        String rest = s.substring(cut).trim();
        // a long rest takes longer to make than the short piece takes to say: cut it again at a
        // comma (only), each piece at most about three times the one before, so each is ready in time
        while (out.size() < 4) {
            int last = out.get(out.size() - 1).length(), most = Math.max(70, 3 * last + 10);
            if (rest.length() <= Math.max(60, 2 * last)) break;
            int c = -1;
            Matcher m = PAUSE.matcher(rest);
            while (m.find()) { if (m.end() > most || rest.length() - m.end() < 20) break; if (m.end() >= 20) c = m.end(); }
            if (c < 0) break;
            out.add(rest.substring(0, c).trim());
            rest = rest.substring(c).trim();
        }
        out.add(rest);
        return out;
    }

    /**
     * Whether a word (lower case) is in the voice's English dictionary; set by the app. null: not
     * known, so a page break is never taken to split a word.
     */
    static volatile java.util.function.Predicate<String> dictionary;
    private static final Pattern BREAK_TAIL = Pattern.compile("(\\p{L}{2,})$");
    private static final Pattern BREAK_HEAD = Pattern.compile("(\\p{Ll}{2,})(?![\\p{L}])");
    /** Short everyday words: "at|one", "of|ten", "for|a" are two words, not one split by the break. */
    private static final java.util.Set<String> SHORT = new java.util.HashSet<>(java.util.Arrays.asList((
        "a an the of at in on to for and or nor is it its me my be as by do so no up we he she his her him you your one two ten "
        + "was are has had not but can our us if into than then them they this that with from all any some out off own who how "
        + "why what when see saw get got let may way day man men new old").split(" ")));
    /**
     * The end of one page and the start of the next, joined. Kindle shares a word hyphenated over the
     * page break as two pieces ("therefore": "...You must there" | "fore allow me"): when the two
     * make one word in the dictionary (and neither is a short everyday word), they're joined
     * without a space, so the voice says one word. A hyphen kept at the end ("well-" | "known") joins too.
     */
    static String joinBreak(String carry, String next) {
        if (carry.isEmpty()) return next;
        if (next.isEmpty()) return carry;
        if (carry.length() > 1 && carry.endsWith("-") && Character.isLetter(carry.charAt(carry.length() - 2)) && startsLower(next)) return carry + next;
        java.util.function.Predicate<String> dict = dictionary;
        Matcher a = BREAK_TAIL.matcher(carry), b = BREAK_HEAD.matcher(next);
        if (dict != null && a.find() && b.lookingAt()) {
            String x = a.group(1).toLowerCase(java.util.Locale.ROOT), y = b.group(1);
            if (!SHORT.contains(x) && !SHORT.contains(y)) {
                try { if (dict.test(x + y)) return carry + next; } catch (RuntimeException ignored) { }
            }
        }
        return carry + " " + next;
    }

    /**
     * The new page's paragraphs from where the last page's text ends, if the new page begins with
     * some of the last one again (Kindle's continuous scrolling moves the text up by less than a
     * screen, and a paragraph cut by the screen's edge is shared by both): those words were read
     * already. Unchanged if the pages don't overlap (Kindle's page turns). Matched on letters only.
     */
    static List<String> afterOverlap(List<String> prev, List<String> next) {
        if (prev == null || prev.isEmpty() || next.isEmpty()) return next;
        String pn = KindleLayout.normOf(String.join(" ", prev));
        if (pn.length() < 25) return next;
        String key = pn.substring(pn.length() - 20);
        // where each letter of the new page is: {paragraph, character}
        StringBuilder nn = new StringBuilder();
        List<int[]> at = new ArrayList<>();
        for (int i = 0; i < next.size(); i++) {
            String t = next.get(i);
            for (int c = 0; c < t.length(); c++) if (Character.isLetterOrDigit(t.charAt(c))) { nn.append(Character.toLowerCase(t.charAt(c))); at.add(new int[]{i, c}); }
        }
        // the last page's end found in the new one, and how far back the two go on matching: the
        // overlap must reach back to the new page's start (the text above it is the same), or be long
        int best = -1, bestLen = 0;
        for (int f = nn.indexOf(key); f >= 0; f = nn.indexOf(key, f + 1)) {
            int e = f + key.length(), n = 0;
            while (n < e && n < pn.length() && nn.charAt(e - 1 - n) == pn.charAt(pn.length() - 1 - n)) n++;
            if ((n == e || n >= 60) && n > bestLen) { best = e; bestLen = n; }
        }
        if (best < 0) return next;
        int[] last = at.get(best - 1);
        List<String> out = new ArrayList<>();
        String rest = next.get(last[0]).substring(last[1] + 1);
        // (the marks right after the last word read, a full stop or a closing quote, go with it)
        rest = rest.replaceFirst("^[.,;:!?\u2026\"'\u201d\u2019)\\]]*", "").trim();
        if (!rest.isEmpty()) out.add(rest);
        for (int i = last[0] + 1; i < next.size(); i++) out.add(next.get(i));
        return out;
    }

    private static boolean startsLower(String s) { return !s.isEmpty() && Character.isLowerCase(s.charAt(0)); }

    /** A paragraph's sentences: short ones joined, long ones split at a comma or similar. */
    static List<String> sentences(String p) {
        List<String> raw = new ArrayList<>();
        for (String s : SENT.split(p)) {
            if (s.trim().isEmpty()) continue;
            // "Mr." or an initial ("J. Smith") isn't the end of a sentence: join it back on
            if (!raw.isEmpty() && ABBREV.matcher(raw.get(raw.size() - 1)).find()) raw.set(raw.size() - 1, raw.get(raw.size() - 1) + " " + s.trim());
            else raw.add(s.trim());
        }
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

    String sayOf(String s) { return say(s); }
    private String say(String s) {
        String t = s;
        for (String[] f : fixes) t = t.replaceAll("(?i)\\b" + Pattern.quote(f[0]) + "\\b", Matcher.quoteReplacement(f[1]));
        return t;
    }
}
