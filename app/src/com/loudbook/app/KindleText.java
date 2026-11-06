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
    // the block (paragraph number) of the paragraph the carried sentence belongs to: its rest on the next page is the same
    // paragraph (for the dialogue voice, a quote opened before the break is still open after it; no paragraph pause there)
    private int carryBlock = -1;
    private final String[][] fixes;

    KindleText(String fixesText) {
        List<String[]> f = new ArrayList<>();
        if (fixesText != null) for (String line : fixesText.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1) f.add(new String[]{line.substring(0, eq).trim(), line.substring(eq + 1).trim()});
        }
        // longest first ("Mr Tavi" before "Tavi"), as the web reader's makeSayer does
        f.sort((x, y) -> y[0].length() - x[0].length());
        fixes = f.toArray(new String[0][]);
        fixRes = new Pattern[fixes.length];
        for (int i = 0; i < fixes.length; i++) fixRes[i] = fixPattern(fixes[i][0]);
    }
    private final Pattern[] fixRes;
    /** A fix's pattern: whole words only where the fix starts/ends with a letter or digit ("Mr.", "C++", "#1" and "$" matched
     *  nowhere with \b around them, 2026-11-06); any case. */
    static Pattern fixPattern(String from) {
        String w = "[\\p{L}\\p{N}_]";
        boolean pre = !from.isEmpty() && (Character.isLetterOrDigit(from.codePointAt(0)) || from.charAt(0) == '_');
        int last = from.isEmpty() ? 0 : from.codePointBefore(from.length());
        boolean post = !from.isEmpty() && (Character.isLetterOrDigit(last) || last == '_');
        return Pattern.compile((pre ? "(?<!" + w + ")" : "") + Pattern.quote(from) + (post ? "(?!" + w + ")" : ""),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Pattern SENT = Pattern.compile("(?<=[.!?…][\"'”’)\\]]{0,2})\\s+(?=[\"'“‘(\\[]?[A-Z0-9])");
    // ("U.S. Army", "A.I. Then": a dotted short form doesn't end one either.
    //  "I." is never an initial: "Neither did I. Then..." ends a sentence; "No.", "Vol.", "Ch.", "Lv." are short forms only before a number)
    private static final Pattern ABBREV = Pattern.compile("(\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|Mt|vs|etc|Lt|Col|Gen|Sgt|Capt|Rev|Hon|approx)|(^|[\\s\"'\u201c\u2018(])[A-HJ-Z]|\\.[A-Z])\\.$");
    private static final Pattern NUMBERED = Pattern.compile("\\b(No|Vol|Ch|lvl|Lv|Lvl)\\.$");
    /** Whether a piece split off at a full stop belongs with the next one ("Mr." | "Hale arrived", "No." | "5 was..."). */
    private static boolean joins(String prev, String next) {
        // (only the end can match: a long run of joined "A. B. C." was searched whole at every join, seconds for 18k letters)
        if (prev.length() > 16) prev = prev.substring(prev.length() - 16);
        if (ABBREV.matcher(prev).find()) return true;
        if (!NUMBERED.matcher(prev).find()) return false;
        String n = next.trim();
        return !n.isEmpty() && (Character.isDigit(n.charAt(0)) || n.charAt(0) == '#');
    }
    private static final Pattern END = Pattern.compile("[.!?…][\"'”’)\\]]{0,2}\\s*$");

    /**
     * The sentences of the page reading starts on (this KindleText must be new). If no sentence ends on it (a heading alone,
     * one long unfinished sentence), it's read whole, as a last page, and nothing carries over: the held-back text used to be
     * read again in front of the next page ("CHAPTER FIVE It was a dark night.").
     */
    List<Chapter.Chunk> startChunks(List<String> paragraphs) {
        List<Chapter.Chunk> raw = chunks(paragraphs, false);
        if (!raw.isEmpty()) return raw;
        carry = ""; skip = null; carryBlock = -1; block = 0; bridgeHead = 0;          // (as new, with the same fixes)
        raw = chunks(paragraphs, true);
        carry = ""; skip = null; carryBlock = -1;
        return raw;
    }

    /** Sentences from a page's paragraphs. last: no more pages, so nothing is held back. */
    List<Chapter.Chunk> chunks(List<String> paragraphs, boolean last) {
        List<String> paras = new ArrayList<>();
        for (String p : paragraphs) { String t = p.replaceAll("\\s+", " ").trim(); if (!t.isEmpty()) paras.add(t); }
        // paras.get(0) goes on with the paragraph the last page ended in (carryBlock), if it's still that one
        int cont = carry.isEmpty() && skip == null ? -1 : carryBlock;
        if (skip != null) {
            // the start of this page was already read with the sentence that ran over the page
            // break (see bridge): carry on after it
            String s0 = paras.isEmpty() ? "" : paras.get(0);
            int after = s0.startsWith(skip) ? skip.length() : afterLetters(s0, skip);
            if (after >= 0) {
                String rest0 = s0.substring(after).trim();
                if (rest0.isEmpty()) { paras.remove(0); cont = -1; } else paras.set(0, rest0);
                carry = "";
            } else if (skipOver(paras, skip)) {
                // (the sentence read over the break ran on past the next page's first paragraph: "I thought we" | "could—”"
                // then "“Don’t.” She left." - Kindle's nextStart has no paragraph ends. It was read again with the page.)
                cont = -1;
                carry = "";
            }
            // (no next page after all, but the sentence over the break was read whole already: not again)
            if (paras.isEmpty()) carry = "";
            skip = null;
        }
        carryBlock = -1;
        if (!carry.isEmpty()) {
            if (paras.isEmpty()) paras.add(carry);
            else paras.set(0, joinBreak(carry, paras.get(0)));
            carry = "";
        }
        boolean lastKept = false;
        int lastAlone = -1;
        if (!last && !paras.isEmpty()) {
            // the page ends mid-sentence: keep the unfinished part for the next page
            String lp = paras.get(paras.size() - 1);
            if (!END.matcher(lp).find()) {
                carry = lastSentence(lp);
                String rest = lp.substring(0, lp.length() - carry.length()).trim();
                if (rest.isEmpty()) {
                    paras.remove(paras.size() - 1);
                    // (the whole paragraph is carried; if it was the one the page began with, it's still that paragraph)
                    if (paras.isEmpty()) lastAlone = cont;
                } else { paras.set(paras.size() - 1, rest); lastKept = true; }
            }
        }
        List<Chapter.Chunk> out = new ArrayList<>();
        Ramp ramp = new Ramp();
        for (int pi = 0; pi < paras.size(); pi++) {
            // the page's first sentence is what's waited for after a page turn: make its first
            // piece short (at a comma, or before "and"/"but"...), so the voice can start sooner; and the next few
            // (in this paragraph or the next ones) cut too while one is much longer than the piece before it
            List<String> ss = ramp.pieces(sentences(paras.get(pi)));
            int b = pi == 0 && cont >= 0 ? cont : block++;
            for (String s : ss) out.add(new Chapter.Chunk(b, s, say(s)));
            if (pi == paras.size() - 1 && lastKept) carryBlock = b;
        }
        if (!carry.isEmpty() && !lastKept) carryBlock = lastAlone;
        return out;
    }

    /** The last sentence of a paragraph ("Mr." and initials don't end one). */
    static String lastSentence(String p) {
        String[] parts = SENT.split(p);
        String last = parts[parts.length - 1];
        for (int i = parts.length - 2; i >= 0 && joins(parts[i], parts[i + 1]); i--) last = parts[i] + " " + last;
        return p.substring(Math.max(0, p.length() - last.length()));
    }
    /** The first sentence of a paragraph. */
    static String firstSentence(String p) {
        String[] parts = SENT.split(p);
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length && joins(parts[i - 1], parts[i]); i++) b.append(' ').append(parts[i]);
        int n = Math.min(p.length(), b.length());
        return p.substring(0, n);
    }

    private String skip;
    /** Drops the letters of head from the start of paras when they run over more than one paragraph; false (paras as they
     *  were) unless all of head's letters are found there, in order. */
    static boolean skipOver(List<String> paras, String head) {
        String h = KindleLayout.normOf(head);
        if (h.isEmpty()) return false;
        int k = 0;
        while (k < paras.size()) {
            String pl = KindleLayout.normOf(paras.get(k));
            if (pl.isEmpty()) { k++; continue; }
            if (h.startsWith(pl)) { h = h.substring(pl.length()); k++; if (h.isEmpty()) break; continue; }
            if (k == 0) return false;                              // (one paragraph: afterLetters' case, not this)
            int after = afterLetters(paras.get(k), h);
            if (after < 0) return false;
            String rest = paras.get(k).substring(after).trim();
            if (rest.isEmpty()) k++; else paras.set(k, rest);
            h = "";
            break;
        }
        if (!h.isEmpty()) return false;
        for (int i = 0; i < k; i++) paras.remove(0);
        return true;
    }
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
        // (its own paragraph if the carried part was one alone: the next page's rest of it goes on in this one)
        if (carryBlock < 0) carryBlock = block++;
        return new Chapter.Chunk(carryBlock, whole, say(whole));
    }

    private static final Pattern PAUSE = Pattern.compile("[,;:\u2014\u2013]\\s");
    private static final Pattern JOIN = Pattern.compile("\\s(?=(and|but|so|then|when|while|because|until|though|as|or|which|where)\\s)");
    /** A long sentence as a short first piece and the rest (in a few pieces if long), cut where a reader would breathe; else as it is. */
    static List<String> quickStart(String s) {
        List<String> out = new ArrayList<>();
        int cut = -1;
        if (s.length() >= 70) {
            Matcher m = PAUSE.matcher(s);
            // (not where the rest would be too short to say on its own: then before "and"/"but"... if there's a place)
            while (m.find()) { if (m.end() > 90 || s.length() - m.end() < 20) break; if (m.end() >= 18) { cut = m.end(); break; } }
            if (cut < 0) {
                m = JOIN.matcher(s);
                while (m.find()) { if (m.start() > 90 || s.length() - m.start() < 20) break; if (m.start() >= 25) { cut = m.start(); break; } }
            }
        }
        if (cut < 0) { out.add(s); return out; }
        out.add(s.substring(0, cut).trim());
        rampRest(out, s.substring(cut).trim(), 4);
        return out;
    }
    /**
     * The page's first sentences, as pieces that are each ready in time: the first one cut short (quickStart), and the next
     * ones (in its paragraph or the next) cut at commas while one is much longer than the piece before it, near the page's
     * start only. (After a page turn a long sentence that came after a short first one was made while nothing was said: about
     * 3 s of silence in the Kindle lab, "Upon the whole, I am much pleased with him.” then a new paragraph's 14 s sentence.)
     */
    static final class Ramp {
        private String last;                          // (the last piece so far; null before the first sentence)
        private int said;                             // (letters in the sentences so far)
        List<String> pieces(List<String> ss) {
            List<String> out = new ArrayList<>();
            for (String s : ss) {
                List<String> t;
                if (last == null) t = quickStart(s);
                else if (said < 200 && s.length() > Math.max(70, 3 * last.length() + 10)) {
                    t = new ArrayList<>();
                    t.add(last);
                    rampRest(t, s, 4);
                    t.remove(0);
                } else t = java.util.Collections.singletonList(s);
                out.addAll(t);
                last = t.get(t.size() - 1);
                said += s.length();
            }
            return out;
        }
    }
    /** Adds rest to out, cut at commas first if it's long next to out's last piece (each piece at most about 3 times the one before). */
    private static void rampRest(List<String> out, String rest, int pieces) {
        // a long rest takes longer to make than the short piece takes to say: cut it again at a
        // comma (only), each piece at most about three times the one before, so each is ready in time
        int start = out.size();
        while (out.size() < start + pieces - 1) {
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
        // (a short screen, a chapter heading or the last lines of one: only when the new one starts with all of it)
        if (pn.length() < 10) return next;
        boolean shortPage = pn.length() < 25;
        String key = shortPage ? pn : pn.substring(pn.length() - 20);
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
            if ((n == e || n >= 60) && n > bestLen && (!shortPage || f == 0)) { best = e; bestLen = n; }
        }
        if (best < 0) return next;
        int[] last = at.get(best - 1);
        List<String> out = new ArrayList<>();
        String rest = next.get(last[0]).substring(last[1] + 1);
        // (the marks right after the last word read, a full stop or a closing quote, go with it)
        rest = rest.replaceFirst("^[.,;:!?\u2026\"'\u201d\u2019)\\]]*", "").trim();
        // (and marks with no word after them: "said\u2014\u201d", "England._", "--": nothing to say)
        if (!rest.isEmpty() && rest.codePoints().anyMatch(Character::isLetterOrDigit)) out.add(rest);
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
            if (!raw.isEmpty() && joins(raw.get(raw.size() - 1), s)) raw.set(raw.size() - 1, raw.get(raw.size() - 1) + " " + s.trim());
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
                if (Character.isLowSurrogate(s.charAt(cut))) cut--;          // (not between an emoji's / fancy letter's two halves)
                out.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    String sayOf(String s) { return say(s); }

    /**
     * The first piece the next page will start with, if this page ended with its sentence (nothing carried over) and Kindle
     * shared how the next page starts (nextStart): so its voice can be made before the page turns. null if it can't be known.
     * (What chunks() gives for that page starts with the same piece, see UnitTest.)
     */
    String nextFirstPiece(String nextStart) {
        if (nextStart == null || !carry.isEmpty() || skip != null) return null;
        String t = nextStart.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        List<String> ss = sentences(t);
        if (ss.isEmpty()) return null;
        return quickStart(ss.get(0)).get(0);
    }
    private String say(String s) {
        String t = s;
        for (int i = 0; i < fixes.length; i++) t = fixRes[i].matcher(t).replaceAll(Matcher.quoteReplacement(fixes[i][1]));
        return t;
    }
}
