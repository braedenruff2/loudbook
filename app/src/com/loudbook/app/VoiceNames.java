package com.loudbook.app;

/**
 * Voice names in words, for "Which voice?" (the reader's button and the audiobook page): the phone's Kokoro voices, and the
 * voices the PC records with (pc-server's "voice" in a chapter's .json: a speaker number, "s17/e0.75" style settings,
 * "builtin", or a voice recorded on the PC). Pure Java (tested in UnitTest).
 */
final class VoiceNames {
    /** Kokoro v1.0 English voices, best first, with sherpa-onnx's speaker numbers. */
    static final String[][] VOICES = {
        {"3", "Heart — US female · A"}, {"2", "Bella — US female · A-"}, {"6", "Nicole — US female, soft · B-"},
        {"21", "Emma — UK female · B-"}, {"16", "Michael — US male · C+"}, {"14", "Fenrir — US male · C+"},
        {"18", "Puck — US male · C+"}, {"1", "Aoede — US female · C+"}, {"5", "Kore — US female · C+"},
        {"9", "Sarah — US female · C+"}, {"26", "George — UK male · C"}, {"25", "Fable — UK male · C"},
        {"22", "Isabella — UK female · C"}, {"7", "Nova — US female · C"}, {"0", "Alloy — US female · C"},
        {"10", "Sky — US female · C-"}, {"27", "Lewis — UK male · D+"}, {"13", "Eric — US male · D"},
        {"15", "Liam — US male · D"}, {"17", "Onyx — US male, deep · D"}, {"12", "Echo — US male · D"},
        {"24", "Daniel — UK male · D"}, {"20", "Alice — UK female · D"}, {"23", "Lily — UK female · D"},
        {"11", "Adam — US male · F+"},
    };

    /** A Kokoro voice's name ("Heart"), or "voice N". */
    static String kokoro(int sid) {
        for (String[] v : VOICES) if (Integer.parseInt(v[0]) == sid) return v[1].split(" \u2014 ")[0];
        return "voice " + sid;
    }

    /**
     * A recording's voice ("voice" in the PC's chapter .json) in words: 17 -> "Onyx"; "s17@0.85^-2/e0.75/c0.3" -> "Onyx
     * (speed 0.85, pitch -2, expressiveness 0.75, pace 0.3)"; "builtin" -> "Chatterbox's own voice"; a recorded voice's id ->
     * "a voice recorded on your PC"; "phone" -> "the phone's voice".
     */
    static String pc(String v) {
        if (v == null) return "unknown";
        v = v.trim();
        if (v.isEmpty() || v.equals("null")) return "unknown";
        if (v.equals("phone")) return "the phone's voice";
        if (v.matches("-?\\d+(\\.0)?")) return kokoro((int) Double.parseDouble(v));
        String[] parts = v.split("/");
        String head = parts[0], base;
        java.util.List<String> how = new java.util.ArrayList<>();
        String pitch = null, speed = null;
        int hat = head.indexOf('^');
        if (hat >= 0) { pitch = head.substring(hat + 1); head = head.substring(0, hat); }
        int at = head.indexOf('@');
        if (at >= 0) { speed = head.substring(at + 1); head = head.substring(0, at); }
        if (head.matches("s\\d{1,4}")) base = kokoro(Integer.parseInt(head.substring(1)));
        else if (head.equals("builtin")) base = "Chatterbox's own voice";
        else if (head.matches("[0-9a-f]{12}")) base = "a voice recorded on your PC";
        else if (head.matches("\\d{1,4}")) base = kokoro(Integer.parseInt(head));
        else base = head;
        if (speed != null && !speed.isEmpty()) how.add("speed " + speed);
        if (pitch != null && !pitch.isEmpty()) how.add("pitch " + (pitch.startsWith("-") || pitch.startsWith("+") ? pitch : "+" + pitch));
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() < 2) continue;
            if (p.charAt(0) == 'e') how.add("expressiveness " + p.substring(1));
            else if (p.charAt(0) == 'c') how.add("pace " + p.substring(1));
            else how.add(p);
        }
        return how.isEmpty() ? base : base + " (" + String.join(", ", how) + ")";
    }

    /** The voices a recording's sentences were made in, most used first: "Onyx (expressiveness 0.75)" or
     *  "Onyx (expressiveness 0.75): 120 sentences; Heart: 12 sentences". */
    static String summary(java.util.List<String> voices) {
        java.util.LinkedHashMap<String, Integer> n = new java.util.LinkedHashMap<>();
        for (String v : voices) n.merge(pc(v), 1, Integer::sum);
        if (n.isEmpty()) return "unknown";
        if (n.size() == 1) return n.keySet().iterator().next();
        java.util.List<java.util.Map.Entry<String, Integer>> es = new java.util.ArrayList<>(n.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : es)
            sb.append(sb.length() > 0 ? "; " : "").append(e.getKey()).append(": ").append(e.getValue()).append(e.getValue() == 1 ? " sentence" : " sentences");
        return sb.toString();
    }

    private VoiceNames() { }
}
