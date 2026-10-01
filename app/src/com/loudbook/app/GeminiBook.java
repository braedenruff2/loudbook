package com.loudbook.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * One chapter read by Gemini: the whole chapter goes up in a single request (the free tier only
 * allows a handful a day), the recording is cut into its sentences (Align) so highlighting works
 * like with every other voice, and it's all kept on disk, so hearing it again costs nothing. If
 * Google stops the recording early, the rest is asked for in a second request.
 */
final class GeminiBook {
    static final class Seg { final int part; final long start, end; Seg(int p, long s, long e) { part = p; start = s; end = e; } }

    final File dir;
    final Map<Integer, Seg> segs = new HashMap<>();
    final java.util.List<CloudVoice.Pcm> parts = new java.util.ArrayList<>();
    final java.util.Set<Integer> joined = java.util.Collections.synchronizedSet(new java.util.HashSet<>());   // the pause after it is in the recording

    private GeminiBook(File dir) { this.dir = dir; }

    /** The chapter's key: its text and the voice settings (a change means a new recording). */
    static String key(Chapter ch, String voice, String model, String style) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            d.update((ch.url + "|" + voice + "|" + model + "|" + style).getBytes(StandardCharsets.UTF_8));
            for (Chapter.Chunk c : ch.chunks) d.update((c.block + ":" + c.say + "\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            return sb.substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final java.util.LinkedHashMap<String, GeminiBook> live = new java.util.LinkedHashMap<>();

    /** The same object for the same chapter, so a recording made ahead of time is seen at once. */
    static synchronized GeminiBook open(File root, String key) {
        GeminiBook b = live.get(key);
        if (b != null && (b.size() == 0 || b.dir.isDirectory())) return b;     // (unless its files were cleared away)
        b = new GeminiBook(new File(root, key));
        b.load();
        live.put(key, b);
        while (live.size() > 4) live.remove(live.keySet().iterator().next());
        return b;
    }

    synchronized boolean has(int i) { return segs.containsKey(i); }
    synchronized int size() { return segs.size(); }

    /** The first sentence from i on that's already recorded (or n). */
    synchronized int nextHave(int i, int n) { for (int k = i; k < n; k++) if (segs.containsKey(k)) return k; return n; }

    synchronized Voice.Clip clip(int i) throws IOException {
        Seg s = segs.get(i);
        CloudVoice.Pcm p = parts.get(s.part);
        return new Voice.Clip(p.read(s.start, (int) (s.end - s.start)), p.rate, 1f, 0);
    }

    /**
     * Asks Google for sentences [from, to) in one request, and files what comes back. Returns how
     * many were recorded (fewer than asked if Google stopped early). paceOut[0]: the reader's pace.
     */
    int fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut) throws IOException {
        StringBuilder text = new StringBuilder();
        int[] chars = new int[to - from];
        for (int k = from; k < to; k++) {
            Chapter.Chunk c = ch.chunks.get(k);
            if (k > from) text.append(c.block != ch.chunks.get(k - 1).block ? "\n\n" : " ");
            text.append(c.say);
            chars[k - from] = c.say.length();
        }
        dir.mkdirs();
        int partNo;
        synchronized (this) { partNo = parts.size(); }
        File f = new File(dir, "part" + partNo + "-" + from + ".pcm");
        CloudVoice.Pcm pcm = CloudVoice.speakToFile(key, model, voice, style, text.toString(), f, 600_000);
        Align.Result r = Align.align(pcm, chars, pace);
        if (r.count == 0) throw new IOException("Gemini's recording didn't match the chapter text");
        if (paceOut != null) paceOut[0] = r.cutShort ? 0 : r.secPerChar;
        synchronized (this) {
            parts.add(pcm);
            for (int k = 0; k < r.count; k++) {
                segs.put(from + k, new Seg(partNo, r.start[k], r.end[k]));
                if (k < r.count - 1) joined.add(from + k);
            }
            save();
        }
        return r.count;
    }

    // ---------------------------------------------------------------- on disk
    private void save() {
        StringBuilder sb = new StringBuilder("{\"parts\":[");
        for (int p = 0; p < parts.size(); p++) {
            CloudVoice.Pcm x = parts.get(p);
            if (p > 0) sb.append(',');
            sb.append('[').append(PcLink.quote(x.file.getName())).append(',').append(x.offset).append(',').append(x.bytes).append(',').append(x.rate).append(']');
        }
        sb.append("],\"segs\":[");
        boolean first = true;
        for (Map.Entry<Integer, Seg> e : segs.entrySet()) {
            if (!first) sb.append(','); first = false;
            Seg s = e.getValue();
            sb.append('[').append(e.getKey()).append(',').append(s.part).append(',').append(s.start).append(',').append(s.end).append(',').append(joined.contains(e.getKey()) ? 1 : 0).append(']');
        }
        sb.append("]}");
        try { java.nio.file.Files.write(new File(dir, "index.json").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { }
        dir.setLastModified(System.currentTimeMillis());
    }

    private void load() {
        File idx = new File(dir, "index.json");
        if (!idx.isFile()) return;
        try {
            String j = new String(java.nio.file.Files.readAllBytes(idx.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\\[\"([^\"]+)\",(\\d+),(\\d+),(\\d+)\\]").matcher(j.substring(0, j.indexOf("\"segs\"")));
            while (pm.find()) {
                File f = new File(dir, pm.group(1));
                if (!f.isFile()) { parts.clear(); return; }
                parts.add(new CloudVoice.Pcm(f, Long.parseLong(pm.group(2)), Long.parseLong(pm.group(3)), Integer.parseInt(pm.group(4))));
            }
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\\[(\\d+),(\\d+),(\\d+),(\\d+),(\\d)\\]").matcher(j.substring(j.indexOf("\"segs\"")));
            while (sm.find()) {
                int part = Integer.parseInt(sm.group(2));
                if (part >= parts.size()) continue;
                int i = Integer.parseInt(sm.group(1));
                segs.put(i, new Seg(part, Long.parseLong(sm.group(3)), Long.parseLong(sm.group(4))));
                if (sm.group(5).equals("1")) joined.add(i);
            }
            dir.setLastModified(System.currentTimeMillis());
        } catch (Exception e) { segs.clear(); parts.clear(); joined.clear(); }
    }

    /** Keeps the recordings on the phone under a size limit, dropping the least recently heard. */
    static void trim(File root, long maxBytes) {
        File[] ds = root.listFiles();
        if (ds == null) return;
        java.util.Arrays.sort(ds, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File d : ds) {
            long n = 0;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) n += f.length();
            total += n;
            if (total > maxBytes && fs != null) { for (File f : fs) f.delete(); d.delete(); }
        }
    }
}
