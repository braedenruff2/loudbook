package com.loudbook.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to the Loudbook voice server on your PC (pc-server/loudbook_server.py). Plain Java, no
 * Android classes, so it can be tested on its own.
 *
 * Security: HTTPS where the PC's certificate must match the fingerprint learned at pairing
 * (pinning, so no other machine can pose as the PC), and a per-phone secret key on every request.
 * Pairing uses the one-time code shown on the PC: the PC has to prove it knows the code before
 * the phone answers, and the code is stretched with PBKDF2.
 */
final class PcLink {
    static final int PORT = 8770;
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    final String host; final int port; final String fp; final String token;

    PcLink(String host, int port, String fp, String token) { this.host = host; this.port = port; this.fp = fp; this.token = token; }

    static final class Audio {
        final short[] pcm; final int rate; final int workMs; final float madeAt; final String engine;
        Audio(short[] p, int r, int w, float m, String e) { pcm = p; rate = r; workMs = w; madeAt = m; engine = e; }
    }
    /** What the PC has: its Kokoro, and the natural voice if it has a graphics card for it. */
    static final class Health {
        final String engine, natural; final boolean naturalOk; final float naturalSpeed;
        /** The full Chatterbox model ("" if the PC can't run it, "not loaded yet" until something asks for it), and the
         *  sentences it's making ahead: in the phone's last list, still to make, made so far. */
        String best = ""; float bestSpeed; int aheadTotal, aheadWaiting, aheadMade; boolean aheadLoading;
        /** The PC's Tailscale address ("" without Tailscale): where to reach it away from home. */
        String away = "";
        Health(String e, String n, boolean ok, float sp) { engine = e; natural = n; naturalOk = ok; naturalSpeed = sp; }
    }

    // ---------------------------------------------------------------- requests
    /** Is the PC there and does it still know this phone? Returns the engine name. */
    Health health(int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/health", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        String body = readText(c);
        String n = str(body, "natural");
        Health h = new Health(str(body, "engine"), n == null ? "" : n, body.matches("(?s).*\"naturalOk\"\\s*:\\s*true.*"),
            Float.parseFloat(num(body, "naturalSpeed")));
        String b = str(body, "best");
        h.best = b == null ? "" : b;
        try {
            h.bestSpeed = Float.parseFloat(num(body, "bestSpeed"));
            h.aheadTotal = (int) Float.parseFloat(num(body, "aheadTotal"));
            h.aheadWaiting = (int) Float.parseFloat(num(body, "aheadWaiting"));
            h.aheadMade = (int) Float.parseFloat(num(body, "aheadMade"));
        } catch (NumberFormatException ignored) { }
        h.aheadLoading = body.matches("(?s).*\"aheadLoading\"\\s*:\\s*true.*");
        String aw = str(body, "away");
        h.away = aw == null ? "" : aw.trim();
        return h;
    }

    /**
     * The sentences the phone will read next ({text, text respelled for Kokoro or null, speaker number} each, in reading
     * order), for the PC to make ahead with its best voice; this list replaces the last one. voice: a recorded voice's id, or
     * null. Returns {sentences, already made}.
     */
    int[] ahead(List<String[]> items, String voice, int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/ahead", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        StringBuilder b = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            String[] x = items.get(i);
            if (i > 0) b.append(',');
            b.append('[').append(quote(x[0])).append(',').append(x[1] == null || x[1].equals(x[0]) ? "null" : quote(x[1]))
             .append(',').append(x[2]).append(']');
        }
        b.append(']');
        if (voice != null && !voice.isEmpty()) b.append(",\"voice\":").append(quote(voice));
        b.append('}');
        post(c, b.toString().getBytes(StandardCharsets.UTF_8));
        String r = readText(c);
        return new int[]{(int) Float.parseFloat(num(r, "total")), (int) Float.parseFloat(num(r, "ready"))};
    }

    /** engine: "natural" for the graphics-card voice when the PC has it, otherwise Kokoro. */
    Audio speak(String text, int sid, float speed, String engine, int timeoutMs) throws IOException { return speak(text, sid, speed, engine, null, timeoutMs); }

    /** voice: a voice recorded on the PC (its id), or null for the usual ones. */
    Audio speak(String text, int sid, float speed, String engine, String voice, int timeoutMs) throws IOException { return speak(text, null, sid, speed, engine, voice, timeoutMs); }

    /** kokoroText: the text respelled for Kokoro (Chapter.forKokoro), for when the PC reads with Kokoro; null if the same. */
    Audio speak(String text, String kokoroText, int sid, float speed, String engine, String voice, int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/speak", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        byte[] req = ("{\"text\":" + quote(text) + ",\"sid\":" + sid + ",\"speed\":" + speed + ",\"engine\":" + quote(engine)
            + (voice != null && !voice.isEmpty() ? ",\"voice\":" + quote(voice) : "")
            + (kokoroText != null && !kokoroText.equals(text) ? ",\"kokoro_text\":" + quote(kokoroText) : "") + "}").getBytes(StandardCharsets.UTF_8);
        int code;
        // (a timeout or reset while sending or waiting: the connection is let go, not left for the garbage collector)
        try { post(c, req); code = c.getResponseCode(); } catch (IOException | RuntimeException e) { c.disconnect(); throw e; }
        if (code != 200) { String e = errorText(c); c.disconnect(); throw new IOException("PC said " + code + (e.isEmpty() ? "" : ": " + e)); }
        int rate;
        try { rate = Integer.parseInt(c.getHeaderField("X-Sample-Rate")); }
        catch (RuntimeException e) { c.disconnect(); throw new IOException("the PC's answer had no sample rate"); }
        String w = c.getHeaderField("X-Work-Ms");
        String made = c.getHeaderField("X-Made-At");
        String used = c.getHeaderField("X-Engine");
        byte[] b;
        try (InputStream in = c.getInputStream()) { b = readAll(in); } finally { c.disconnect(); }
        short[] pcm = new short[b.length / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return new Audio(pcm, rate, w == null ? 0 : Integer.parseInt(w), made == null ? speed : Float.parseFloat(made), used == null ? "kokoro" : used);
    }

    // ---------------------------------------------------------------- audiobooks recorded on the PC
    static final class BookFile { final String path; final long bytes; BookFile(String p, long b) { path = p; bytes = b; } }

    /** Every finished audiobook file on the PC ("book folder/0001 - name.mp3" and its .json). */
    List<BookFile> books(int timeoutMs) throws IOException { return books(timeoutMs, false); }

    /** With kindle, also each book's Kindle books ("book folder/name.epub", from the Whizzer Library download on the PC). */
    List<BookFile> books(int timeoutMs, boolean kindle) throws IOException {
        HttpsURLConnection c = open(kindle ? "/v1/books?kindle=1" : "/v1/books", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        String body = readText(c);
        List<BookFile> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\\s*\"p\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"bytes\"\\s*:\\s*(\\d+)\\s*\\}").matcher(body);
        while (m.find()) out.add(new BookFile(unescape(m.group(1)), Long.parseLong(m.group(2))));
        return out;
    }

    /** Copies one of those files from the PC into out. Returns the bytes copied. */
    long download(String path, OutputStream out, int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/books/get?p=" + java.net.URLEncoder.encode(path, "UTF-8"), fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = c.getResponseCode();
            if (code != 200) { String e = errorText(c); throw new IOException(e.isEmpty() ? "PC said " + code : e); }
            long n = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[65536]; int k;
                while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); n += k; }
            }
            return n;
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- recorded voices
    static final class Recorded { final String id, name; Recorded(String i, String n) { id = i; name = n; } }

    /** The voices recorded on the PC. */
    List<Recorded> voices(int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/voices", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        String body = readText(c);
        List<Recorded> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(body.substring(Math.max(0, body.indexOf('['))));
        while (m.find()) {
            String id = str(m.group(), "id"), name = str(m.group(), "name");
            if (id != null) out.add(new Recorded(id, name == null ? "Recorded voice" : name));
        }
        return out;
    }

    /** Keeps a recording (a WAV file) on the PC as a voice to read with. Returns its id. */
    String addVoice(String name, byte[] wav) throws IOException {
        HttpsURLConnection c = open("/v1/voice/add", fp, 60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        post(c, ("{\"name\":" + quote(name) + ",\"wav\":\"" + Base64.getEncoder().encodeToString(wav) + "\"}").getBytes(StandardCharsets.UTF_8));
        String id = str(readText(c), "id");
        if (id == null) throw new IOException("the PC didn't keep it");
        return id;
    }

    void deleteVoice(String id) throws IOException {
        HttpsURLConnection c = open("/v1/voice/delete", fp, 10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        post(c, ("{\"id\":" + quote(id) + "}").getBytes(StandardCharsets.UTF_8));
        readText(c);
    }

    // ---------------------------------------------------------------- pairing
    /** Pairs with the PC at host using the code it shows. Returns the link to keep. */
    static PcLink pair(String host, int port, String code, String phoneName) throws IOException {
        byte[] key = pairKey(code);
        SecureRandom rnd = new SecureRandom();
        byte[] nc = new byte[16];
        rnd.nextBytes(nc);
        // step 1: we don't know the PC's certificate yet; note it, and make the PC prove the code
        String[] seen = new String[1];
        HttpsURLConnection c = open(host, port, "/v1/pair/start", null, seen, 8000);
        post(c, ("{\"nc\":\"" + b64(nc) + "\"}").getBytes(StandardCharsets.UTF_8));
        String r = readText(c);
        String fpHex = str(r, "fp");
        if (fpHex == null || str(r, "ns") == null || str(r, "mac") == null) throw new IOException("the PC's answer wasn't a pairing one. Is it Loudbook's PC server?");
        byte[] ns = unb64(str(r, "ns"));
        byte[] fp = hex(fpHex);
        if (seen[0] == null || !seen[0].equals(fpHex)) throw new IOException("the PC's certificate didn't match. Pairing stopped.");
        if (!MessageDigest.isEqual(mac(key, "server", nc, ns, fp), unb64(str(r, "mac"))))
            throw new IOException("wrong code (or that's not your PC)");
        // step 2: now the certificate is trusted; prove the code ourselves and get our key
        HttpsURLConnection c2 = open(host, port, "/v1/pair/finish", fpHex, null, 8000);
        post(c2, ("{\"nc\":\"" + b64(nc) + "\",\"ns\":\"" + b64(ns) + "\",\"mac\":\"" + b64(mac(key, "client", nc, ns, fp))
            + "\",\"name\":" + quote(phoneName) + "}").getBytes(StandardCharsets.UTF_8));
        String t = str(readText(c2), "token");
        if (t == null || t.isEmpty()) throw new IOException("the PC didn't send a key");
        return new PcLink(host, port, fpHex, t);
    }

    static byte[] pairKey(String code) {
        StringBuilder sb = new StringBuilder();
        for (char ch : code.toUpperCase(java.util.Locale.US).toCharArray()) if (ALPHABET.indexOf(ch) >= 0) sb.append(ch);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(sb.toString().toCharArray(), "loudbook-pair-v1".getBytes(StandardCharsets.UTF_8), 200_000, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] mac(byte[] key, String who, byte[] nc, byte[] ns, byte[] fp) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update((who + "|").getBytes(StandardCharsets.UTF_8));
            m.update(nc); m.update(ns); m.update(fp);
            return m.doFinal();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- finding the PC
    static final class Found { final String host; final int port; final String fp; final String name; Found(String h, int p, String f, String n) { host = h; port = p; fp = f; name = n; } }

    /** Asks the home network where Loudbook's PC voice is (a UDP broadcast). */
    static List<Found> discover(int waitMs) {
        List<Found> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] q = "LOUDBOOK?".getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), PORT + 1));
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (java.net.SocketTimeoutException e) { continue; }
                String j = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!"loudbook".equals(str(j, "app"))) continue;
                String h = p.getAddress().getHostAddress();
                boolean dup = false;
                for (Found f : out) dup |= f.host.equals(h);
                if (!dup) out.add(new Found(h, Integer.parseInt(num(j, "port")), str(j, "fp"), str(j, "name")));
            }
        } catch (IOException ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- HTTPS with a pinned certificate
    private HttpsURLConnection open(String p, String pin, int timeoutMs) throws IOException { return open(host, port, p, pin, null, timeoutMs); }

    /** pin: the certificate fingerprint to insist on; null only during pairing, when seen[0] gets it. */
    static HttpsURLConnection open(String host, int port, String p, String pin, String[] seen, int timeoutMs) throws IOException {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + h + ":" + port + p).openConnection();
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new Pinned(pin, seen)}, new SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
        } catch (Exception e) { throw new IOException(e); }
        c.setHostnameVerifier((name, session) -> true);       // identity comes from the pinned fingerprint
        c.setConnectTimeout(Math.min(timeoutMs, 4000));
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        // (each request has its own pinned socket factory, so a kept-alive connection is never used again: it only held one
        // of the PC's 24 connection slots until it timed out, and five requests a second ran the PC out of them)
        c.setRequestProperty("Connection", "close");
        return c;
    }

    static final class Pinned implements X509TrustManager {
        final String pin; final String[] seen;
        Pinned(String pin, String[] seen) { this.pin = pin; this.seen = seen; }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
            String f;
            try { f = hexOf(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())); }
            catch (Exception e) { throw new CertificateException(e); }
            if (seen != null) seen[0] = f;
            if (pin != null && !MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII), f.getBytes(StandardCharsets.US_ASCII)))
                throw new CertificateException("this isn't the PC Loudbook was paired with");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // ---------------------------------------------------------------- little helpers
    private static void post(HttpURLConnection c, byte[] body) throws IOException {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
    }
    private static String readText(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code != 200) { String e = errorText(c); throw new IOException(e.isEmpty() ? "PC said " + code : e); }
            try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        } finally { c.disconnect(); }
    }
    private static String errorText(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) { if (in == null) return ""; String e = str(new String(readAll(in), StandardCharsets.UTF_8), "error"); return e == null ? "" : e; }
        catch (IOException e) { return ""; }
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }
    /** A JSON string's escapes: the PC's Python writes every letter outside plain ASCII (a voice called Zoë, a curly apostrophe) as a backslash-u code. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { sb.append(ch); continue; }
            char e = s.charAt(++i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break; }
                        catch (NumberFormatException ignored) { }
                    }
                    sb.append(e); break;
                default: sb.append(e);                            // \" \\ \/
            }
        }
        return sb.toString();
    }
    static String num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        return m.find() ? m.group(1) : "0";
    }
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
    static String hexOf(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
    static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
}
