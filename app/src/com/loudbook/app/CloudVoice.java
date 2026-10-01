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
    static final String HOST = "https://generativelanguage.googleapis.com/v1beta/";

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

    /** Speech for some text. model: e.g. gemini-3.8-flash-tts. Throws with Google's message on failure. */
    static Audio speak(String key, String model, String voice, String style, String text, int timeoutMs) throws IOException {
        String body = "{\"model\":" + PcLink.quote(model) + ",\"input\":[{\"type\":\"user_input\",\"content\":[{\"type\":\"text\",\"text\":"
            + PcLink.quote(text) + (style == null || style.trim().isEmpty() ? "" : ",\"annotations\":[{\"type\":\"speech_metadata\",\"style\":" + PcLink.quote(style.trim()) + "}]")
            + "}]}],\"response_format\":{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"sample_rate\":24000},"
            + "\"generation_config\":{\"speech_config\":[{\"voice\":" + PcLink.quote(voice) + "}]}}";
        Reply r = post(HOST + "interactions", key, body, timeoutMs);
        if (r.code == 404 || r.code == 400 && r.body.contains("interactions")) {
            // the older endpoint, in case this key or model only has that
            String old = "{\"contents\":[{\"parts\":[{\"text\":" + PcLink.quote((style == null || style.trim().isEmpty() ? "" : style.trim() + "\n\n") + text) + "}]}],"
                + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":" + PcLink.quote(voice) + "}}}}}";
            r = post(HOST + "models/" + model + ":generateContent", key, old, timeoutMs);
        }
        if (r.code != 200) throw new IOException(errorOf(r));
        return decode(r.body);
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
        Matcher m = Pattern.compile("\"data\"\\s*:\\s*\"([A-Za-z0-9+/=_-]{64,})\"").matcher(json);
        String b64 = null;
        while (m.find()) b64 = m.group(1);
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
