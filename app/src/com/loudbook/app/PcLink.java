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

    static final class Audio { final short[] pcm; final int rate; final int workMs; Audio(short[] p, int r, int w) { pcm = p; rate = r; workMs = w; } }

    // ---------------------------------------------------------------- requests
    /** Is the PC there and does it still know this phone? Returns the engine name. */
    String health(int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/health", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        String body = readText(c);
        return str(body, "engine");
    }

    Audio speak(String text, int sid, float speed, int timeoutMs) throws IOException {
        HttpsURLConnection c = open("/v1/speak", fp, timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        byte[] req = ("{\"text\":" + quote(text) + ",\"sid\":" + sid + ",\"speed\":" + speed + "}").getBytes(StandardCharsets.UTF_8);
        post(c, req);
        int code = c.getResponseCode();
        if (code != 200) { String e = errorText(c); c.disconnect(); throw new IOException("PC said " + code + (e.isEmpty() ? "" : ": " + e)); }
        int rate = Integer.parseInt(c.getHeaderField("X-Sample-Rate"));
        String w = c.getHeaderField("X-Work-Ms");
        byte[] b;
        try (InputStream in = c.getInputStream()) { b = readAll(in); } finally { c.disconnect(); }
        short[] pcm = new short[b.length / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return new Audio(pcm, rate, w == null ? 0 : Integer.parseInt(w));
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
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
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
