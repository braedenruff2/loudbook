package com.loudbook.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One chapter read by Gemini: the chapter goes up in a single request (the free tier allows only a
 * few a day), the recording is cut into its sentences so highlighting works like with every other
 * voice, and it's kept on disk: going back, replaying, or opening the chapter again later costs
 * nothing. If Google stops a long recording early, the rest is asked for in a second request.
 *
 * Sentences are filed by their text, not just their number, so a page that comes back slightly
 * different (an extra line somewhere) still finds its recording.
 */
final class GeminiBook {
    static final class Seg {
        final int idx; final int hash; final int part; volatile long start, end; boolean joined;
        Seg(int idx, int hash, int part, long s, long e, boolean j) { this.idx = idx; this.hash = hash; this.part = part; start = s; end = e; joined = j; }
    }

    final File dir;
    private final List<Seg> all = new ArrayList<>();                   // in chapter order
    private final List<CloudVoice.Pcm> parts = new ArrayList<>();
    private final Map<Integer, Seg> bound = new HashMap<>();           // chapter sentence -> recording
    private final java.util.Set<Integer> issued = new java.util.HashSet<>();   // handed out to play (timing frozen)
    private Chapter boundTo;

    private GeminiBook(File dir) { this.dir = dir; }

    /** The chapter's key: the page and the voice settings (a different voice is a new recording). */
    static String key(String url, String voice, String model, String style) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            d.update((url + "|" + voice + "|" + model + "|" + style).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            return sb.substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final java.util.LinkedHashMap<String, GeminiBook> live = new java.util.LinkedHashMap<>();

    /** The same object for the same chapter, so nothing recorded is ever missed. */
    static synchronized GeminiBook open(File root, String key) {
        GeminiBook b = live.get(key);
        if (b != null && (b.all.isEmpty() || b.dir.isDirectory())) return b;
        b = new GeminiBook(new File(root, key));
        b.load();
        live.put(key, b);
        while (live.size() > 4) live.remove(live.keySet().iterator().next());
        return b;
    }

    static int hash(Chapter.Chunk c) { return c.say.trim().hashCode(); }

    /** Matches the recording to this version of the chapter's text. */
    synchronized void bind(Chapter ch) {
        if (boundTo == ch) return;
        boundTo = ch;
        bound.clear();
        int s = 0;
        for (int i = 0; i < ch.size() && s < all.size(); i++) {
            int h = hash(ch.chunks.get(i));
            for (int look = s; look < Math.min(all.size(), s + 6); look++)
                if (all.get(look).hash == h) { bound.put(i, all.get(look)); s = look + 1; break; }
        }
    }

    synchronized boolean has(int i) { return bound.containsKey(i); }
    synchronized boolean joined(int i) { Seg s = bound.get(i); return s != null && s.joined && bound.containsKey(i + 1); }
    synchronized int recorded() { return all.size(); }

    /** The first sentence from i on that's already recorded (or n). */
    synchronized int nextHave(int i, int n) { for (int k = i; k < n; k++) if (bound.containsKey(k)) return k; return n; }
    /** The first sentence that's recorded at all (or -1). */
    synchronized int firstHave(int n) { for (int k = 0; k < n; k++) if (bound.containsKey(k)) return k; return -1; }

    synchronized Voice.Clip clip(int i) throws IOException {
        Seg s = bound.get(i);
        issued.add(s.idx);
        CloudVoice.Pcm p = parts.get(s.part);
        return new Voice.Clip(p.read(s.start, (int) (s.end - s.start)), p.rate, 1f, 0);
    }

    /** A recording just made, for sentence timing. */
    static final class Made { int part; int from; int count; }

    /**
     * Asks Google for sentences [from, to) in one request, and files what comes back. paceOut[0]:
     * the reader's pace (0 if the recording was cut short).
     */
    Made fetch(Chapter ch, int from, int to, String key, String model, String voice, String style, double pace, double[] paceOut) throws IOException {
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
        File f = new File(dir, "part" + partNo + "-" + System.currentTimeMillis() + ".pcm");
        CloudVoice.Pcm pcm = CloudVoice.speakToFile(key, model, voice, style, text.toString(), f, 600_000);
        Align.Result r = Align.align(pcm, chars, pace);
        if (r.count == 0) throw new IOException("Gemini's recording didn't match the chapter text");
        if (paceOut != null) paceOut[0] = r.cutShort ? 0 : r.secPerChar;
        Made m = new Made();
        synchronized (this) {
            m.part = parts.size(); m.from = from; m.count = r.count;
            parts.add(pcm);
            for (int k = 0; k < r.count; k++)
                all.add(new Seg(from + k, hash(ch.chunks.get(from + k)), m.part, r.start[k], r.end[k], k < r.count - 1));
            all.sort((a, b) -> Integer.compare(a.idx, b.idx));
            boundTo = null; bind(ch);
            save();
        }
        return m;
    }

    /**
     * Exact timing: the recogniser hears the words, and each sentence's cut moves to where its
     * first word starts (into the pause just before it). Sentences already handed out to play keep
     * theirs, so nothing is heard twice or skipped.
     */
    void refine(Chapter ch, Made m, AsrAligner asr) throws IOException {
        CloudVoice.Pcm pcm;
        List<Seg> segs = new ArrayList<>();
        synchronized (this) {
            pcm = parts.get(m.part);
            for (Seg s : all) if (s.part == m.part) segs.add(s);
        }
        if (segs.isEmpty()) return;
        long from = segs.get(0).start, to = segs.get(segs.size() - 1).end;
        List<AsrAligner.Word> heard = asr.words(pcm, from, to - from);
        List<String> texts = new ArrayList<>();
        for (Seg s : segs) texts.add(ch.chunks.get(Math.min(s.idx, ch.size() - 1)).say);
        double[] st = AsrAligner.sentenceStarts(heard, texts);
        double[][] pauses = Align.pauses(pcm);
        int rate = pcm.rate;
        int moved = 0;
        synchronized (this) {
            for (int k = 1; k < segs.size(); k++) {
                if (Double.isNaN(st[k])) continue;
                Seg prev = segs.get(k - 1), cur = segs.get(k);
                if (issued.contains(prev.idx) || issued.contains(cur.idx)) continue;
                // (the recogniser reports a word about a quarter second after it begins)
                double word = from / (double) rate + st[k] - 0.22;
                // cut in the middle of the pause just before the first word, if there is one
                double cut = word - 0.05, best = Double.MAX_VALUE;
                for (double[] p : pauses) {
                    double end = p[0] + p[1] / 2;                       // where the pause ends
                    if (end > word + 0.25 || end < word - 0.6) continue;
                    double d = Math.abs(end - word);
                    if (d < best) { best = d; cut = p[0]; }
                }
                long at = Math.round(cut * rate);
                if (at <= prev.start + rate / 5 || at >= cur.end - rate / 5) continue;
                if (Math.abs(at - cur.start) > rate / 20) moved++;
                prev.end = at; cur.start = at;
            }
            save();
        }
        lastRefined = moved;
    }
    volatile int lastRefined;

    // ---------------------------------------------------------------- on disk
    private void save() {
        StringBuilder sb = new StringBuilder("{\"parts\":[");
        for (int p = 0; p < parts.size(); p++) {
            CloudVoice.Pcm x = parts.get(p);
            if (p > 0) sb.append(',');
            sb.append('[').append(PcLink.quote(x.file.getName())).append(',').append(x.offset).append(',').append(x.bytes).append(',').append(x.rate).append(']');
        }
        sb.append("],\"segs\":[");
        for (int k = 0; k < all.size(); k++) {
            Seg s = all.get(k);
            if (k > 0) sb.append(',');
            sb.append('[').append(s.idx).append(',').append(s.hash).append(',').append(s.part).append(',').append(s.start).append(',').append(s.end).append(',').append(s.joined ? 1 : 0).append(']');
        }
        sb.append("]}");
        try {
            File tmp = new File(dir, "index.json.tmp");
            java.nio.file.Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            File idx = new File(dir, "index.json");
            if (idx.exists()) idx.delete();
            if (!tmp.renameTo(idx)) throw new IOException("rename");
        } catch (IOException ignored) { }
        dir.setLastModified(System.currentTimeMillis());
    }

    private void load() {
        File idx = new File(dir, "index.json");
        if (!idx.isFile()) return;
        try {
            String j = new String(java.nio.file.Files.readAllBytes(idx.toPath()), StandardCharsets.UTF_8);
            int cut = j.indexOf("\"segs\"");
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("\\[\"([^\"]+)\",(\\d+),(\\d+),(\\d+)\\]").matcher(j.substring(0, cut));
            while (pm.find()) {
                File f = new File(dir, pm.group(1));
                if (!f.isFile()) { parts.clear(); return; }
                parts.add(new CloudVoice.Pcm(f, Long.parseLong(pm.group(2)), Long.parseLong(pm.group(3)), Integer.parseInt(pm.group(4))));
            }
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\\[(\\d+),(-?\\d+),(\\d+),(\\d+),(\\d+),(\\d)\\]").matcher(j.substring(cut));
            while (sm.find()) {
                int part = Integer.parseInt(sm.group(3));
                if (part >= parts.size()) continue;
                all.add(new Seg(Integer.parseInt(sm.group(1)), Integer.parseInt(sm.group(2)), part,
                    Long.parseLong(sm.group(4)), Long.parseLong(sm.group(5)), sm.group(6).equals("1")));
            }
            dir.setLastModified(System.currentTimeMillis());
        } catch (Exception e) { all.clear(); parts.clear(); }
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
