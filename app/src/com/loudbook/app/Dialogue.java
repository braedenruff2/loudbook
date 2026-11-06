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
        if (!single && dashes(ch, i)) {
            for (int j = k; j < i; j++) open = runDashes(ch.chunks.get(j).say, open);
            return open;
        }
        if (single) {
            // (single quotes: where they close depends on what comes later in the paragraph, so they're worked out over all
            // of it: ‘We fed the boys’ dog. It was hungry,’ she said. The boys’ didn't close the speech)
            StringBuilder b = new StringBuilder();
            for (int j = k; j < i; j++) b.append(ch.chunks.get(j).say).append(' ');
            int at = b.length();
            b.append(after(ch, i - 1));
            byte[] m = singleMarks(b.toString(), false);
            for (int c = 0; c < at; c++) if (m[c] != 0) open = m[c] > 0;
            return open;
        }
        for (int j = k; j < i; j++) open = run(ch.chunks.get(j).say, open, single);
        return open;
    }
    /** The rest of sentence i's paragraph after it (its later sentences), "" if none: for where single quotes close. */
    static String after(Chapter ch, int i) {
        if (i < 0 || i >= ch.size()) return "";
        int block = ch.chunks.get(i).block;
        if (block < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int j = i + 1; j < ch.size() && ch.chunks.get(j).block == block; j++) b.append(ch.chunks.get(j).say).append(' ');
        return b.toString();
    }

    /**
     * Does sentence i's paragraph quote speech in single quotes (‘Yes,’ she said: British style)? When a single quote opens
     * somewhere (an apostrophe alone doesn't count) and any double quotes are inside the single-quoted speech.
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
        int first = -1;
        for (int c = 0; c < para.length() && first < 0; c++) if (opensSingle(para, c)) first = c;
        if (first < 0) return false;
        boolean doubles = para.indexOf('“') >= 0 || para.indexOf('”') >= 0 || para.indexOf('"') >= 0
            || para.indexOf('«') >= 0 || para.indexOf('»') >= 0;
        if (!doubles) return true;
        // a quote inside a quote, British style: ‘He told me “never”,’ she said. The double quotes are part of what she says
        // (they were taken for the speech, and the rest for narration). Only when every double quote is inside single-quoted
        // speech; American style (“He said ‘no’,” she said) and a paragraph with both kinds of speech stay double-quoted
        byte[] m = singleMarks(para, false);
        boolean open = false, inside = false;      // (inside: a double quote in the speech open now; it must close too)
        for (int c = 0; c < para.length(); c++) {
            if (m[c] != 0) { open = m[c] > 0; if (!open) inside = false; continue; }
            char ch = para.charAt(c);
            if (ch == '“' || ch == '”' || ch == '"') { if (!open) return false; inside = true; }
        }
        return !inside;                            // (not "on 'Change, is the "beautiful quadroon."": an elision, never closed)
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
        // the quote's first word an elision with its own apostrophe: ‘‘Twas brillig,’ she said. / ‘’Tis true,’ he said.
        if (c + 2 < t.length() && "‘’'".indexOf(t.charAt(c + 1)) >= 0 && elision(t, c + 2)) return true;
        if (c + 1 >= t.length() || !Character.isLetter(t.charAt(c + 1)) && "…—".indexOf(t.charAt(c + 1)) < 0) return false;
        if (!elision(t, c + 1)) return true;
        // (a quote before "Round", "Cause", "Til"...: speech if it closes later on (‘Round up the cattle,’ he said.); a
        // word processor turns 'em into ‘em too, so with no close it's that; straight ones the same)
        // (the close must come before the next left quote opening a word: in "We'll get ‘em now. ‘Go,’ she said." the ,’ is Go's)
        return closeBeforeOpener(t, c + 2);
    }
    private static String stopText;
    private static int[] stopAt;                   // (per position: where the next close (>= 0) or word-opening ‘ (-1) is)
    /** Is there a clear single close at or after k, before any ‘ that starts a word? (Worked out once per text: a long
     *  paragraph full of ‘em was checked to its end for every one, per sentence.) */
    private static synchronized boolean closeBeforeOpener(String t, int k) {
        if (t != stopText) {
            int[] a = new int[t.length() + 1];
            a[t.length()] = -1;
            for (int j = t.length() - 1; j >= 0; j--) {
                if (closesSingle(t, j)) a[j] = j;
                else if ((t.charAt(j) == '‘' || t.charAt(j) == '\'') && (j == 0 || "( \t\n[—–".indexOf(t.charAt(j - 1)) >= 0)
                    && j + 1 < t.length() && Character.isLetter(t.charAt(j + 1)) && !elision(t, j + 1)) a[j] = -1;
                else a[j] = a[j + 1];
            }
            stopText = t; stopAt = a;
        }
        return k < t.length() && stopAt[k] >= 0;
    }
    /** Does a word cut short at the front start at c ('em, 'tis, 'twas, 'cause: its apostrophe just before c)? */
    private static boolean elision(String t, int c) {
        String rest = t.substring(c, Math.min(t.length(), c + 6)).toLowerCase(java.util.Locale.ROOT);
        return ELISION.matcher(rest).lookingAt();
    }
    private static final java.util.regex.Pattern ELISION = java.util.regex.Pattern.compile("(em|tis|twas|twere|cause|cos|til|till|n|nuff|bout|round|ello|ere)\\b");
    /** A single quote closing speech: after the quote's last punctuation (,’ .’ !’), or before punctuation; not "the boys’ dog". */
    private static boolean closesSingle(String t, int c) {
        char ch = t.charAt(c);
        if (ch != '’' && ch != '\'') return false;
        if (c > 0 && ",.!?…—–-;:".indexOf(t.charAt(c - 1)) >= 0) return true;
        return c + 1 >= t.length() || ",.;:!?—–…)".indexOf(t.charAt(c + 1)) >= 0
            || Character.isWhitespace(t.charAt(c + 1)) && c > 0 && !Character.isLetter(t.charAt(c - 1));
    }

    /** A straight double quote right after a number, not opening speech: an inch mark (6' 2", a 12" pizza). (One closing speech
     *  can follow a number: "Room 5," she said.) */
    private static boolean inchMark(String t, int c) { return c > 0 && Character.isDigit(t.charAt(c - 1)); }
    /** A straight double quote after a number inside open speech that is an inch mark, not the close: "I'm 6' 2" tall," he
     *  said. Only when a word in lower case follows (not "he", "she", "said"...) and another " comes later in the text. */
    private static boolean inchInside(String t, int c) {
        if (!inchMark(t, c) || c + 2 >= t.length() || t.charAt(c + 1) != ' ' || !Character.isLowerCase(t.charAt(c + 2))) return false;
        if (t.indexOf('"', c + 1) < 0) return false;
        int e = c + 2;
        while (e < t.length() && Character.isLetter(t.charAt(e))) e++;
        return !TAG_WORDS.contains(t.substring(c + 2, e));
    }
    private static final java.util.Set<String> TAG_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "he", "she", "they", "i", "we", "you", "it", "said", "asked", "replied", "called", "shouted", "whispered", "says", "and", "was"));

    /** Whether a quote is still open at the end of this text. */
    static boolean run(String t, boolean open) { return run(t, open, false); }
    static boolean run(String t, boolean open, boolean single) {
        byte[] marks = null;
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            if (single) { if (marks == null) marks = singleMarks(t, open); if (marks[c] != 0) open = marks[c] > 0; continue; }
            if (ch == '“' || guilOpens(t, c)) open = true;
            else if (ch == '”' || guilCloses(t, c)) open = false;
            else if (ch == '"' && !(open ? inchInside(t, c) : inchMark(t, c))) open = !open;
        }
        return open;
    }

    /** The text in pieces: {text, "q"} for quoted speech, {text, "n"} for narration. */
    static List<String[]> split(String t, boolean open) { return split(t, open, false); }
    /** single: the paragraph quotes in single quotes (see singles). */
    static List<String[]> split(String t, boolean open, boolean single) { return split(t, open, single, ""); }
    /** after: the rest of the paragraph (where a single quote closes can depend on it). */
    static List<String[]> split(String t, boolean open, boolean single, String after) {
        byte[] marks = null;
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int c = 0; c < t.length(); c++) {
            char ch = t.charAt(c);
            if (single && marks == null) marks = after == null || after.isEmpty() ? singleMarks(t, open) : java.util.Arrays.copyOf(singleMarks(t + " " + after, open), t.length());
            boolean opens = single ? marks[c] > 0 : ch == '“' || guilOpens(t, c) || (ch == '"' && !open && !inchMark(t, c));
            boolean closes = single ? marks[c] < 0 : ch == '”' || guilCloses(t, c) || (ch == '"' && open && !inchInside(t, c));
            if (opens && !open) { add(out, cur, false); open = true; continue; }
            if (closes && open) { add(out, cur, true); open = false; continue; }
            cur.append(ch);
        }
        add(out, cur, open);
        if (out.isEmpty()) out.add(new String[]{t, "n"});
        return out;
    }

    /** dashes: the paragraph sets speech off with dashes (see dashes(String)). */
    static List<String[]> split(String t, boolean open, boolean single, String after, boolean dashes) {
        if (!dashes || single) return split(t, open, single, after);
        byte[] m = dashMarks(t, open);
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int c = 0; c < t.length(); c++) {
            if (m[c] > 0 && !open) { add(out, cur, false); open = true; continue; }
            if (m[c] < 0 && open) { add(out, cur, true); open = false; continue; }
            cur.append(t.charAt(c));
        }
        add(out, cur, open);
        if (out.isEmpty()) out.add(new String[]{t, "n"});
        return out;
    }

    /** Guillemets: « opens and » closes (French: «Oui», dit-il. / « Non », répondit-elle.), or the other way round (German:
     *  »Hallo«, sagte er.), told apart by which side of the mark the words are on. */
    private static boolean guilOpens(String t, int c) {
        char ch = t.charAt(c);
        if (ch == '«') return !guilCloses(t, c);
        if (ch != '»') return false;
        // German opening »: at a word's start (start, space, bracket or dash before it) and a word right after it
        return (c == 0 || "( \t\n[—–".indexOf(t.charAt(c - 1)) >= 0) && c + 1 < t.length()
            && (Character.isLetterOrDigit(t.charAt(c + 1)) || "…¿¡".indexOf(t.charAt(c + 1)) >= 0);
    }
    private static boolean guilCloses(String t, int c) {
        char ch = t.charAt(c);
        if (ch == '»') return !guilOpens(t, c);
        if (ch != '«') return false;
        // German closing «: right after the quote's last word or punctuation, then a space, punctuation or the end
        return c > 0 && !Character.isWhitespace(t.charAt(c - 1)) && "([—–".indexOf(t.charAt(c - 1)) < 0
            && (c + 1 >= t.length() || Character.isWhitespace(t.charAt(c + 1)) || ",.;:!?—–…)".indexOf(t.charAt(c + 1)) >= 0);
    }

    private static boolean isDash(char ch) { return ch == '—' || ch == '―' || ch == '–'; }
    /**
     * Does this paragraph set speech off with dashes, as in translated fiction (—No sé —dijo él—. Quizá mañana. / — Come
     * here —she said.)? When it starts with a dash and a word, and has no quotation marks.
     */
    static boolean dashes(String para) {
        int c = 0;
        while (c < para.length() && Character.isWhitespace(para.charAt(c))) c++;
        if (c >= para.length() || !isDash(para.charAt(c))) return false;
        int d = c + 1;
        if (d < para.length() && para.charAt(d) == ' ') d++;
        if (d >= para.length() || !(Character.isLetterOrDigit(para.charAt(d)) || "¿¡…".indexOf(para.charAt(d)) >= 0)) return false;
        for (int k = 0; k < para.length(); k++) if ("“”\"«»‘".indexOf(para.charAt(k)) >= 0 || opensSingle(para, k)) return false;
        return true;
    }
    static boolean dashes(Chapter ch, int i) {
        if (i < 0 || i >= ch.size()) return false;
        int block = ch.chunks.get(i).block;
        if (block < 0) return false;
        int k = i, e = i;
        while (k > 0 && ch.chunks.get(k - 1).block == block) k--;
        while (e + 1 < ch.size() && ch.chunks.get(e + 1).block == block) e++;
        StringBuilder b = new StringBuilder();
        for (int j = k; j <= e; j++) b.append(ch.chunks.get(j).say).append(' ');
        return dashes(b.toString());
    }
    /**
     * Where dashes open (1) and close (-1) speech in a dash-dialogue paragraph. Speech: from a dash at the start; it stops at
     * a dash with a space before it and a small letter after (" —dijo él"). Narration: speech again after a dash right after
     * a word and before punctuation or a space ("él—. Quizá", "said—, and"), or a spaced dash before a capital (". —Maybe").
     * A dash inside a word or speech cut off ("I was going—") changes nothing.
     */
    static byte[] dashMarks(String t, boolean open) {
        byte[] m = new byte[t.length()];
        int first = 0;
        while (first < t.length() && Character.isWhitespace(t.charAt(first))) first++;
        boolean tagged = false;                    // (a tag ended speech in this text: a dash after it goes back to speech)
        for (int c = 0; c < t.length(); c++) {
            if (!isDash(t.charAt(c))) continue;
            boolean spaceBefore = c == 0 || Character.isWhitespace(t.charAt(c - 1));
            int n = c + 1;
            boolean spaceAfter = n < t.length() && t.charAt(n) == ' ';
            if (spaceAfter) n++;
            char next = n < t.length() ? t.charAt(n) : 0;
            if (!open) {
                boolean starts = c == first && (Character.isLetterOrDigit(next) || "¿¡…".indexOf(next) >= 0)
                    || spaceBefore && (Character.isUpperCase(next) || "¿¡".indexOf(next) >= 0)
                    || !spaceBefore && Character.isLetter(t.charAt(c - 1)) && (next == 0 ? false : ",.;:".indexOf(next) >= 0 && !spaceAfter)
                    // (the speech goes on after the tag: "siente—replicó el ciego deteniéndose—y que parece")
                    || tagged && !spaceBefore && Character.isLetter(t.charAt(c - 1)) && Character.isLetter(next);
                if (starts) { m[c] = 1; open = true; }
            } else if (Character.isLowerCase(next) && (spaceBefore ? !spaceAfter || tagWord(t, n)
                       : !spaceAfter && (",.!?…;:".indexOf(t.charAt(c - 1)) >= 0 || tagWord(t, n) || tagEnds(t, n)))) {
                m[c] = -1; open = false; tagged = true;
            }
        }
        return m;
    }
    /** Does a word like "he" or "said" start at c (the spaced dash before it ends the speech: — Come here — she said.)? */
    private static boolean tagWord(String t, int c) {
        int e = c;
        while (e < t.length() && Character.isLetter(t.charAt(e))) e++;
        String w = t.substring(c, e);
        return TAG_WORDS.contains(w) || SAY_WORDS.contains(w) || w.length() >= 5 && w.endsWith("ó");   // (Spanish: preguntó, contestó)
    }
    /** Words that start a dialogue tag in translated fiction (the dash before them ends the speech). */
    private static final java.util.Set<String> SAY_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
        "dijo", "dice", "decía", "pregunta", "responde", "exclama", "murmura", "grita", "añade", "dit", "répondit", "demanda",
        "cria", "murmura", "ajouta", "fit", "disse", "rispose", "chiese", "perguntou", "respondeu", "asks", "replies", "cried",
        "murmured", "answered", "added", "exclaimed", "muttered", "shouted", "yelled", "laughed", "sighed", "continued"));
    /** Is there a dash after c that ends a tag (—murmuró—. / —said Ana—, and / —he said— Then), before any other speech dash?
     *  (Then "equivocarme—murmuró—." is speech, tag, speech; "I was—well—scared" stays speech.) */
    private static boolean tagEnds(String t, int c) {
        for (int d = c; d < t.length(); d++) {
            if (!isDash(t.charAt(d))) continue;
            int n = d + 1;
            if (n >= t.length()) return false;
            char x = t.charAt(n);
            if (",.;:".indexOf(x) >= 0) return true;
            return x == ' ' && n + 1 < t.length() && (Character.isUpperCase(t.charAt(n + 1)) || "¿¡".indexOf(t.charAt(n + 1)) >= 0);
        }
        return false;
    }
    /** Whether dash speech is still open at the end of this text. */
    static boolean runDashes(String t, boolean open) {
        byte[] m = dashMarks(t, open);
        for (byte b : m) if (b != 0) open = b > 0;
        return open;
    }

    private static void add(List<String[]> out, StringBuilder cur, boolean quoted) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.codePoints().anyMatch(Character::isLetterOrDigit)) {
            // only punctuation (", " between the quote and "he said"): keep it with what came before
            if (!s.isEmpty() && !out.isEmpty()) out.get(out.size() - 1)[0] += s;
            return;
        }
        // punctuation that ends the piece before ("Oui», dit-il." / ‘Hi’, he said. / él—. Quizá): it goes with that piece
        int lead = 0;
        while (lead < s.length() && ",.;:!?".indexOf(s.charAt(lead)) >= 0) lead++;
        if (lead > 0 && !out.isEmpty()) { out.get(out.size() - 1)[0] += s.substring(0, lead); s = s.substring(lead).trim(); }
        // two pieces in a row in the same voice: one piece
        if (!out.isEmpty() && out.get(out.size() - 1)[1].equals(quoted ? "q" : "n")) { out.get(out.size() - 1)[0] += " " + s; return; }
        out.add(new String[]{s, quoted ? "q" : "n"});
    }
}
