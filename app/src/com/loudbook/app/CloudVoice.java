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
    static volatile String HOST = System.getProperty("loudbook.gemini", "https://generativelanguage.googleapis.com/v1beta/");   // (tests point it elsewhere)

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

    /** About how many letters of instructions Gemini might say before the text, if it ever says them (0: none sent). */
    static int preambleChars(String style) {
        String st = style == null ? "" : style.trim();
        return st.isEmpty() ? 0 : st.length();
    }

    static final class Audio { final float[] samples; final int rate; Audio(float[] s, int r) { samples = s; rate = r; } }

    /** Which way of asking worked last time (Google has two APIs and has renamed models before). */
    static volatile int lastWay = -1;
    /** How the last recording was asked for, and what the first way that didn't work said (shown in Settings). */
    static volatile String how = "", howProblem = "";

    /** Called as audio arrives: bytes of samples saved so far. */
    interface Progress { void on(long bytes); }

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
            Pcm p = speakToFile(key, model, voice, style, text, tmp, timeoutMs, false, null);
            return new Audio(p.read(0, (int) p.samples()), p.rate);
        } finally { tmp.delete(); }
    }

    static Pcm speakToFile(String key, String model, String voice, String style, String text, java.io.File out, int timeoutMs) throws IOException {
        return speakToFile(key, model, voice, style, text, out, timeoutMs, false, null);
    }

    static final String[] WAY_NAMES = {"streamed, Google's new API", "Google's new API", "Google's new API (raw audio)",
        "streamed, Google's older API", "Google's older API", "Google's older API, older model"};

    /**
     * Speech for some text, saved straight to a file as it arrives (a chapter's audio is tens of
     * MB). One request, however long the text. stream: ask for the audio to be sent while it's
     * being made, so reading can start within seconds (progress hears about each piece). Throws
     * with Google's message.
     *
     * The reading style goes in Google's own field for it (speech_metadata), never in the text:
     * Gemini reads the text word for word, so a style put in front of it was read out loud too,
     * and every sentence after it was out of step. The older API has no such field, so there the
     * text goes alone.
     */
    static Pcm speakToFile(String key, String model, String voice, String style, String text, java.io.File out, int timeoutMs,
                           boolean stream, Progress progress) throws IOException {
        String st = style == null ? "" : style.trim();
        String content = "{\"type\":\"text\",\"text\":" + PcLink.quote(text)
            + (st.isEmpty() ? "" : ",\"annotations\":[{\"type\":\"speech_metadata\",\"style\":" + PcLink.quote(st) + "}]") + "}";
        String speech = "\"generation_config\":{\"speech_config\":[{\"voice\":" + PcLink.quote(voice) + "}]}";
        String input = "\"model\":" + PcLink.quote(model) + ",\"input\":[{\"type\":\"user_input\",\"content\":[" + content + "]}],";
        String legacy = "{\"contents\":[{\"parts\":[{\"text\":" + PcLink.quote(text) + "}]}],"
            + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":" + PcLink.quote(voice) + "}}}}}";
        String[] urls = new String[6], bodies = new String[6];
        urls[0] = HOST + "interactions";                          // the Interactions API, streamed (raw 24 kHz PCM as it's made)
        bodies[0] = "{" + input + "\"response_format\":{\"type\":\"audio\"},\"stream\":true," + speech + "}";
        urls[1] = urls[0];                                         // the same, all at once (a WAV file)
        bodies[1] = "{" + input + "\"response_format\":{\"type\":\"audio\"}," + speech + "}";
        urls[2] = urls[0];                                         // all at once, as raw 24 kHz PCM
        bodies[2] = "{" + input + "\"response_format\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"sample_rate\":24000}," + speech + "}";
        urls[3] = HOST + "models/" + model + ":streamGenerateContent?alt=sse"; bodies[3] = legacy;     // the older API, streamed
        urls[4] = HOST + "models/" + model + ":generateContent"; bodies[4] = legacy;
        urls[5] = HOST + "models/gemini-2.5-flash-preview-tts:generateContent"; bodies[5] = legacy;
        int[] order = stream ? new int[]{0, 3, 1, 2, 4, 5} : new int[]{1, 2, 4, 5};
        // the way that worked last time goes first (if it's one of these)
        int lw = lastWay;
        for (int i = 1; i < order.length; i++) if (order[i] == lw) { System.arraycopy(order, 0, order, 1, i); order[0] = lw; break; }

        String firstProblem = null;
        // Only a wrong request (not a refusal of the key or the daily limit) moves on to the next
        // way of asking, so a working setup spends exactly one of the day's requests per call.
        for (int w : order) {
            Streamed r = postToFile(urls[w], key, bodies[w], out, timeoutMs, progress);
            if (r.code == 429) throw new Limited(errorOf(new Reply(r.code, r.json)), retryMs(r.json));
            if (r.code == 401 || r.code == 403) throw new IOException(errorOf(new Reply(r.code, r.json)));
            if (r.code == 200 && r.audioBytes > 100) {
                lastWay = w;
                how = WAY_NAMES[w];
                howProblem = firstProblem == null ? "" : firstProblem;
                return new Pcm(out, 0, r.audioBytes & ~1L, r.rate);
            }
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

    static final class Streamed { int code; String json = ""; long audioBytes; int rate = 24000; }

    /**
     * Posts, then reads the reply as it arrives: every base64 "data" value (the audio: one long
     * one, or many short ones when streamed, each in its own event) is decoded straight onto the
     * end of the file; a WAV header in front of a piece is left out. The rest of the reply is kept
     * (small) for errors.
     */
    static Streamed postToFile(String url, String key, String body, java.io.File out, int timeoutMs, Progress progress) throws IOException {
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
            try (InputStream bin = new java.io.BufferedInputStream(in, 1 << 16)) { readAudio(bin, out, r, r.code == 200 ? progress : null); }
            return r;
        } finally { c.disconnect(); }
    }

    /** Where decoded audio goes: onto the end of the file, leaving out a WAV header in front of a piece. */
    private static final class Sink {
        final OutputStream o; final Streamed r;
        final byte[] head = new byte[64]; int headN; boolean headDone = true;
        long written;
        Sink(OutputStream o, Streamed r) { this.o = o; this.r = r; }
        void start() { headN = 0; headDone = false; }
        void put(int b) throws IOException {
            if (headDone) { o.write(b); written++; return; }
            head[headN++] = (byte) b;
            if (headN == head.length) end();
        }
        void end() throws IOException {
            if (headDone) return;
            headDone = true;
            int from = 0;
            if (headN >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                from = Math.min(headN, 44);
                int p = 12;
                while (p + 8 <= headN) {
                    String id = new String(head, p, 4, StandardCharsets.US_ASCII);
                    int size = le32(head, p + 4);
                    if (id.equals("fmt ") && p + 16 <= headN) r.rate = le32(head, p + 12);
                    if (id.equals("data")) { from = p + 8; break; }
                    if (size < 0 || size > 1 << 20) break;
                    p += 8 + size + (size & 1);
                }
            }
            o.write(head, from, headN - from);
            written += headN - from;
        }
    }

    /** Reads a JSON (or server-sent events) reply, decoding the audio as it goes. */
    static void readAudio(InputStream in, java.io.File out, Streamed r, Progress progress) throws IOException {
        StringBuilder small = new StringBuilder();
        StringBuilder str = new StringBuilder();
        String lastKey = null;
        boolean inStr = false, esc = false, audio = false, afterColon = false;
        int[] quad = new int[4]; int q = 0;
        long told = 0;
        if (out.exists()) out.delete();
        OutputStream bo = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out), 1 << 16);
        Sink sink = new Sink(bo, r);
        try {
            int ch;
            while ((ch = in.read()) >= 0) {
                char cc = (char) ch;
                if (!inStr) {
                    if (cc == '"') {
                        inStr = true; esc = false; str.setLength(0); q = 0;
                        // the value (after a colon) of a "data" key is a piece of audio
                        audio = afterColon && "data".equals(lastKey);
                        if (audio) sink.start();
                    } else {
                        if (cc == ':') afterColon = true;
                        else if (cc == ',' || cc == '{' || cc == '}' || cc == '[' || cc == ']' || cc == '\n') { afterColon = false; lastKey = null; }
                        if (small.length() < 8000) small.append(cc);
                    }
                    continue;
                }
                if (audio) {
                    if (cc == '"') {                                             // the end of a piece
                        if (q > 1) sink.put((quad[0] << 2) | (quad[1] >> 4));
                        if (q > 2) sink.put(((quad[1] & 15) << 4) | (quad[2] >> 2));
                        sink.end();
                        inStr = false; audio = false; afterColon = false; lastKey = null;
                        if (small.length() < 8000) small.append("\"\u2026\"");
                        bo.flush();
                        if (progress != null && sink.written > told) { told = sink.written; progress.on(told); }
                        continue;
                    }
                    int v = val(cc);
                    if (v < 0) continue;                                         // '=' padding, escapes, line breaks
                    quad[q++] = v;
                    if (q == 4) {
                        sink.put((quad[0] << 2) | (quad[1] >> 4));
                        sink.put(((quad[1] & 15) << 4) | (quad[2] >> 2));
                        sink.put(((quad[2] & 3) << 6) | quad[3]);
                        q = 0;
                        if (progress != null && sink.written - told >= 48_000) { bo.flush(); told = sink.written; progress.on(told); }
                    }
                    continue;
                }
                if (esc) {
                    esc = false;
                    if (cc == 'u') { char[] h = new char[4]; for (int i = 0; i < 4; i++) h[i] = (char) in.read(); cc = (char) Integer.parseInt(new String(h), 16); }
                    else if (cc == 'n') cc = '\n'; else if (cc == 't') cc = '\t';
                } else if (cc == '\\') { esc = true; continue; }
                else if (cc == '"') {
                    inStr = false;
                    String sv = str.toString();
                    if (afterColon) { afterColon = false; lastKey = null; } else lastKey = sv;     // a value, or a key
                    if (small.length() < 8000) small.append('"').append(sv.length() > 200 ? sv.substring(0, 200) : sv).append('"');
                    continue;
                }
                if (str.length() < 20000) str.append(cc);
            }
        } finally { bo.close(); }
        r.audioBytes = sink.written;
        r.json = small.toString();
        if (progress != null && sink.written > told) progress.on(sink.written);
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
