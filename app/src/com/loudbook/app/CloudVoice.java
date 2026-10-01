package com.loudbook.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google's Gemini text-to-speech, one of the best-rated voices there is (online; needs the
 * reader's own API key from Google AI Studio). A paragraph goes up at a time, which reads more
 * naturally and makes fewer requests, and comes back split into its sentences for highlighting.
 * Plain Java, no Android classes, so it can be tested on its own.
 */
final class CloudVoice {
    static final String HOST = System.getProperty("loudbook.gemini", "https://generativelanguage.googleapis.com/v1beta/");   // (tests point it elsewhere)

    /** The 30 Gemini voices, with Google's one-word descriptions. Good narrators first. */
    static final String[][] VOICES = {
        {"Sulafat", "warm"}, {"Charon", "informative"}, {"Achernar", "soft"}, {"Gacrux", "mature"},
        {"Vindemiatrix", "gentle"}, {"Schedar", "even"}, {"Iapetus", "clear"}, {"Erinome", "clear"},
        {"Algieba", "smooth"}, {"Despina", "smooth"}, {"Kore", "firm"}, {"Orus", "firm"}, {"Alnilam", "firm"},
        {"Achird", "friendly"}, {"Callirrhoe", "easy-going"}, {"Umbriel", "easy-going"}, {"Aoede", "breezy"},
        {"Enceladus", "breathy"}, {"Algenib", "gravelly"}, {"Rasalgethi", "informative"}, {"Sadaltager", "knowledgeable"},
        {"Zephyr", "bright"}, {"Autonoe", "bright"}, {"Puck", "upbeat"}, {"Laomedeia", "upbeat"}, {"Fenrir", "excitable"},
        {"Leda", "youthful"}, {"Pulcherrima", "forward"}, {"Zubenelgenubi", "casual"}, {"Sadachbia", "lively"},
    };
    static final String DEFAULT_STYLE = "Read this like a skilled audiobook narrator: natural and engaging, following the mood of the story and the characters' emotions.";

    static final class Audio { final float[] samples; final int rate; Audio(float[] s, int r) { samples = s; rate = r; } }

    /** Which way of asking worked last time (Google has two APIs and has renamed models before). */
    static volatile int lastWay = 0;

    /** Speech audio Google sent, saved to a file: 16-bit mono PCM starting at offset. */
    static final class Pcm {
        final java.io.File file; final long offset; final long bytes; final int rate;
        Pcm(java.io.File f, long o, long b, int r) { file = f; offset = o; bytes = b; rate = r; }
        long samples() { return bytes / 2; }
        /** Samples [from, from+n) as floats. */
        float[] read(long from, int n) throws IOException {
            n = (int) Math.max(0, Math.min(n, samples() - from));
            byte[] b = new byte[n * 2];
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file, "r")) { f.seek(offset + from * 2); f.readFully(b); }
            float[] out = new float[n];
            for (int i = 0; i < n; i++) out[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
            return out;
        }
    }

    /** A short bit of speech (the "hear it" sample). */
    static Audio speak(String key, String model, String voice, String style, String text, int timeoutMs) throws IOException {
        java.io.File tmp = java.io.File.createTempFile("gemini", ".pcm");
        try {
            Pcm p = speakToFile(key, model, voice, style, text, tmp, timeoutMs);
            return new Audio(p.read(0, (int) p.samples()), p.rate);
        } finally { tmp.delete(); }
    }

    /**
     * Speech for some text, streamed straight to a file (a chapter's audio is tens of MB, too big
     * to hold as text). One request, however long the text. Throws with Google's message.
     */
    static Pcm speakToFile(String key, String model, String voice, String style, String text, java.io.File out, int timeoutMs) throws IOException {
        String st = style == null ? "" : style.trim();
        String withStyle = (st.isEmpty() ? "" : st + "\n\n") + text;
        String[] urls = new String[4], bodies = new String[4];
        String content = "{\"type\":\"text\",\"text\":" + PcLink.quote(text)
            + (st.isEmpty() ? "" : ",\"annotations\":[{\"type\":\"speech_metadata\",\"style\":" + PcLink.quote(st) + "}]") + "}";
        String speech = "\"generation_config\":{\"speech_config\":[{\"voice\":" + PcLink.quote(voice) + "}]}";
        urls[0] = HOST + "interactions";                          // the Interactions API, as Google's example
        bodies[0] = "{\"model\":" + PcLink.quote(model) + ",\"input\":[{\"type\":\"user_input\",\"content\":[" + content + "]}],"
            + "\"response_format\":{\"type\":\"audio\"}," + speech + "}";
        urls[1] = urls[0];                                         // the same, asking for raw 24 kHz PCM
        bodies[1] = "{\"model\":" + PcLink.quote(model) + ",\"input\":[{\"type\":\"user_input\",\"content\":[" + content + "]}],"
            + "\"response_format\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"sample_rate\":24000}," + speech + "}";
        String legacy = "{\"contents\":[{\"parts\":[{\"text\":" + PcLink.quote(withStyle) + "}]}],"
            + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":" + PcLink.quote(voice) + "}}}}}";
        urls[2] = HOST + "models/" + model + ":generateContent"; bodies[2] = legacy;          // the older API
        urls[3] = HOST + "models/gemini-2.5-flash-preview-tts:generateContent"; bodies[3] = legacy;

        String firstProblem = null;
        // Only a wrong request (not a refusal of the key or the daily limit) moves on to the next
        // way of asking, so a working setup spends exactly one of the day's requests per call.
        for (int t = 0; t < 4; t++) {
            int w = (lastWay + t) % 4;
            Streamed r = postToFile(urls[w], key, bodies[w], out, timeoutMs);
            if (r.code == 429) throw new Limited(errorOf(new Reply(r.code, r.json)), retryMs(r.json));
            if (r.code == 401 || r.code == 403) throw new IOException(errorOf(new Reply(r.code, r.json)));
            if (r.code == 200 && r.audioBytes > 100) { lastWay = w; return wavOrPcm(out, r.audioBytes); }
            if (r.code == 200 && firstProblem == null) firstProblem = "no audio in Google's reply: " + outline(r.json);
            else if (firstProblem == null) firstProblem = errorOf(new Reply(r.code, r.json));
            if (r.code == 200) break;                     // it was understood (and counted): don't spend more
        }
        throw new IOException(firstProblem);
    }

    /** Google's "too many requests": how long until there are more. */
    static final class Limited extends IOException {
        final long retryMs;
        Limited(String m, long retryMs) { super(m); this.retryMs = retryMs; }
    }

    /**
     * When requests are available again, from Google's 429 reply: its retryDelay if it gives one;
     * for a daily limit, midnight Pacific time (when Google's daily quotas reset).
     */
    static long retryMs(String json) {
        long delay = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").matcher(json);
        if (m.find()) delay = (long) (Double.parseDouble(m.group(1)) * 1000) + 2000;
        boolean daily = json.matches("(?s).*PerDay.*") || json.toLowerCase(java.util.Locale.US).contains("per day");
        if (daily) {
            java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 1); c.set(java.util.Calendar.SECOND, 0);
            long untilMidnight = c.getTimeInMillis() - System.currentTimeMillis();
            delay = Math.max(delay, untilMidnight);
        }
        return delay > 0 ? delay : 60_000;
    }

    /** A reply's shape with long values cut short, for error messages (no audio, no key in it). */
    static String outline(String json) {
        String o = json.replaceAll("\"([^\"\\\\]|\\\\.){60,}\"", "\"…\"").replaceAll("\\s+", " ");
        return o.length() > 400 ? o.substring(0, 400) + "…" : o;
    }

    static final class Streamed { int code; String json = ""; long audioBytes; }

    /**
     * Posts, then reads the JSON reply as it arrives: the long base64 string (the audio) is
     * decoded straight into the file; the rest of the JSON is kept (small) for errors.
     */
    static Streamed postToFile(String url, String key, String body, java.io.File out, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        Streamed r = new Streamed();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key.trim());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return r;
            try (InputStream bin = new java.io.BufferedInputStream(in, 1 << 16)) { scan(bin, out, r); }
            return r;
        } finally { c.disconnect(); }
    }

    /** Streams JSON: strings longer than 2 KB of base64 go to the file (the longest one wins). */
    static void scan(InputStream in, java.io.File out, Streamed r) throws IOException {
        StringBuilder small = new StringBuilder();
        StringBuilder str = new StringBuilder();
        java.io.File tmp = new java.io.File(out.getPath() + ".b64");
        boolean inStr = false, esc = false, blob = false;
        long blobBytes = 0;
        int[] quad = new int[4]; int q = 0;
        OutputStream bo = null;
        int ch;
        try {
            while ((ch = in.read()) >= 0) {
                char cc = (char) ch;
                if (!inStr) {
                    if (cc == '"') { inStr = true; str.setLength(0); blob = false; blobBytes = 0; q = 0; }
                    else if (small.length() < 8000) small.append(cc);
                    continue;
                }
                if (esc) {
                    esc = false;
                    if (cc == 'u') { char[] h = new char[4]; for (int i = 0; i < 4; i++) h[i] = (char) in.read(); cc = (char) Integer.parseInt(new String(h), 16); }
                    else if (cc == 'n') cc = '\n'; else if (cc == 't') cc = '\t';
                } else if (cc == '\\') { esc = true; continue; }
                else if (cc == '"') {
                    inStr = false;
                    if (blob) {
                        // finish the last group (padding is fine)
                        if (q > 1) { bo.write((quad[0] << 2) | (quad[1] >> 4)); blobBytes++; }
                        if (q > 2) { bo.write(((quad[1] & 15) << 4) | (quad[2] >> 2)); blobBytes++; }
                        bo.close(); bo = null;
                        if (blobBytes > r.audioBytes) {
                            r.audioBytes = blobBytes;
                            if (out.exists()) out.delete();
                            if (!tmp.renameTo(out)) throw new IOException("couldn't save the audio");
                        } else tmp.delete();
                        if (small.length() < 8000) small.append("\"…\"");
                    } else if (small.length() < 8000) small.append('"').append(str).append('"');
                    continue;
                }
                if (!blob) {
                    str.append(cc);
                    if (str.length() > 2048) {
                        boolean b64 = true;
                        for (int i = 0; i < str.length() && b64; i++) b64 = val(str.charAt(i)) >= 0 || str.charAt(i) == '=';
                        if (!b64) { if (str.length() > 20000) str.setLength(20000); continue; }
                        blob = true;
                        bo = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16);
                        String pre = str.toString(); str.setLength(0);
                        for (int i = 0; i < pre.length(); i++) {
                            int v = val(pre.charAt(i)); if (v < 0) continue;
                            quad[q++] = v;
                            if (q == 4) { blobBytes += emit(bo, quad); q = 0; }
                        }
                    }
                    continue;
                }
                int v = val(cc);
                if (v < 0) continue;                                 // '=' padding, line breaks
                quad[q++] = v;
                if (q == 4) { blobBytes += emit(bo, quad); q = 0; }
            }
        } finally { if (bo != null) { bo.close(); tmp.delete(); } }
        r.json = small.toString();
    }
    private static int emit(OutputStream o, int[] q) throws IOException {
        o.write((q[0] << 2) | (q[1] >> 4));
        o.write(((q[1] & 15) << 4) | (q[2] >> 2));
        o.write(((q[2] & 3) << 6) | q[3]);
        return 3;
    }
    private static int val(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+' || c == '-') return 62;
        if (c == '/' || c == '_') return 63;
        return -1;
    }

    /** Where the samples start in what Google sent: after a WAV header, or at 0 for raw PCM. */
    static Pcm wavOrPcm(java.io.File f, long len) throws IOException {
        byte[] h = new byte[(int) Math.min(4096, len)];
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) { r.readFully(h); }
        if (h.length > 44 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            int rate = 24000, p = 12;
            while (p + 8 <= h.length) {
                String id = new String(h, p, 4, StandardCharsets.US_ASCII);
                int size = le32(h, p + 4);
                if (id.equals("fmt ")) rate = le32(h, p + 12);
                if (id.equals("data")) { long data = p + 8; long n = size <= 0 || size > len - data ? len - data : size; return new Pcm(f, data, n & ~1L, rate); }
                p += 8 + size + (size & 1);
            }
            throw new IOException("Google's audio had no data");
        }
        return new Pcm(f, 0, len & ~1L, 24000);
    }

    static final class Reply { final int code; final String body; Reply(int c, String b) { code = c; body = b; } }

    private static Reply post(String url, String key, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key.trim());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Reply(code, in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }

    static String errorOf(Reply r) {
        String m = PcLink.str(r.body, "message");
        if (r.code == 429) return "Google's limit for this key was reached; the phone's voice reads for now" + (m == null ? "" : " (" + m + ")");
        if (r.code == 401 || r.code == 403) return "Google didn't accept the key" + (m == null ? "" : " (" + m + ")");
        return "Google said " + r.code + (m == null ? "" : ": " + m);
    }

    /** The audio in Google's reply: the last base64 "data" field; a WAV file or raw 16-bit PCM at 24 kHz. */
    static Audio decode(String json) throws IOException {
        String j = json.replace("\\/", "/").replace("\\u003d", "=").replace("\\u002b", "+");
        // the audio is the longest base64 string in the reply, whatever its field is called
        Matcher m = Pattern.compile("\"([A-Za-z0-9+/=_-]{200,})\"").matcher(j);
        String b64 = null;
        while (m.find()) if (b64 == null || m.group(1).length() > b64.length()) b64 = m.group(1);
        if (b64 == null) throw new IOException("Google sent no audio");
        byte[] a = Base64.getDecoder().decode(b64.replace('-', '+').replace('_', '/'));
        int rate = 24000, off = 0, len = a.length;
        if (a.length > 44 && a[0] == 'R' && a[1] == 'I' && a[2] == 'F' && a[3] == 'F') {
            int p = 12;
            off = -1;
            while (p + 8 <= a.length) {
                String id = new String(a, p, 4, StandardCharsets.US_ASCII);
                int size = le32(a, p + 4);
                if (id.equals("fmt ")) rate = le32(a, p + 12);
                if (id.equals("data")) { off = p + 8; len = Math.min(size < 0 ? Integer.MAX_VALUE : size, a.length - off); break; }
                p += 8 + size + (size & 1);
            }
            if (off < 0) throw new IOException("Google's audio had no data");
        }
        float[] f = new float[len / 2];
        for (int i = 0; i < f.length; i++) f[i] = (short) ((a[off + 2 * i] & 0xff) | (a[off + 2 * i + 1] << 8)) / 32768f;
        return new Audio(f, rate);
    }

    private static int le32(byte[] a, int p) { return (a[p] & 0xff) | (a[p + 1] & 0xff) << 8 | (a[p + 2] & 0xff) << 16 | (a[p + 3] & 0xff) << 24; }

    /**
     * Cuts a paragraph's audio into its sentences: each cut goes near where the text says it
     * should, at the quietest moment around there (the pause between sentences).
     */
    static List<float[]> split(float[] audio, int rate, int[] chars) {
        List<float[]> out = new ArrayList<>();
        int n = chars.length;
        if (n == 1) { out.add(audio); return out; }
        long total = 0;
        for (int c : chars) total += Math.max(1, c);
        int win = rate / 50;                                     // 20 ms
        int prev = 0; long cum = 0;
        for (int k = 0; k < n - 1; k++) {
            cum += Math.max(1, chars[k]);
            int target = (int) ((double) cum / total * audio.length);
            int reach = (int) (0.35 * audio.length * Math.max(1, Math.min(chars[k], chars[k + 1])) / total);
            int from = Math.max(prev + win, target - reach), to = Math.min(audio.length - win, target + reach);
            int best = Math.max(prev + 1, Math.min(target, audio.length - 1));
            double bestE = Double.MAX_VALUE;
            for (int s = from; s + win <= to; s += win / 2) {
                double e = 0;
                for (int i = s; i < s + win; i++) e += audio[i] * audio[i];
                e *= 1 + 0.3 * Math.abs(s + win / 2 - target) / Math.max(1.0, reach);    // prefer cuts near the target
                if (e < bestE) { bestE = e; best = s + win / 2; }
            }
            float[] seg = new float[best - prev];
            System.arraycopy(audio, prev, seg, 0, seg.length);
            out.add(seg);
            prev = best;
        }
        float[] last = new float[audio.length - prev];
        System.arraycopy(audio, prev, last, 0, last.length);
        out.add(last);
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
