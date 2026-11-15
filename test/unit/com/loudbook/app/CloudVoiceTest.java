package com.loudbook.app;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * CloudVoice.speakToFile against a stand-in Google on localhost (2026-11-15 review):
 * escaped characters in the audio's base64 (+ = / written as escapes) are decoded, not read as audio letters;
 * a whole (not streamed) request doesn't put the whole-WAV way first for streamed ones;
 * Google busy (503) once a way has worked costs one request and an error, not a request per way and the old model for good.
 */
public class CloudVoiceTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }
    static final List<String> hits = new ArrayList<>();
    static volatile boolean busy;

    public static void main(String[] a) throws Exception {
        int code = 1;
        try { run(); code = fails == 0 ? 0 : 1; } catch (Throwable t) { t.printStackTrace(); }
        System.out.println(fails == 0 && code == 0 ? "CloudVoiceTest: all passed" : "CloudVoiceTest: FAILED");
        System.exit(code);
    }

    static void run() throws Exception {
        byte[] pcm = new byte[3000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * 7 + 3);
        String b64 = Base64.getEncoder().encodeToString(pcm);
        String esc = b64.replace("+", "\\u002b").replace("=", "\\u003d").replace("/", "\\/");
        HttpServer s = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String p = ex.getRequestURI().toString();
            String body = new String(ex.getRequestBody().readAllBytes());
            boolean st = body.contains("\"stream\":true") || p.contains("streamGenerate");
            synchronized (hits) { hits.add(p + (st ? " S" : " W")); }
            int c = 200; String out;
            if (busy) { c = 503; out = "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\"}}"; }
            else out = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/L16;codec=pcm;rate=24000\",\"data\":\"" + esc + "\"}}]}}]}";
            byte[] b = out.getBytes();
            ex.sendResponseHeaders(c, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        CloudVoice.HOST = "http://127.0.0.1:" + s.getAddress().getPort() + "/";
        CloudVoice.forgetWays();
        File f = File.createTempFile("cloudvoice", ".pcm");
        f.deleteOnExit();

        CloudVoice.speakToFile("k", "m", "v", "", "hi", f, 5000, false, null);
        byte[] got = java.nio.file.Files.readAllBytes(f.toPath());
        check("escaped base64 decoded exactly (" + got.length + " bytes for " + pcm.length + ")", Arrays.equals(got, pcm));

        hits.clear();
        CloudVoice.speakToFile("k", "m", "v", "", "hi", f, 5000, true, null);
        check("a streamed request after a whole one asks streamed first " + hits, !hits.isEmpty() && hits.get(0).endsWith(" S"));

        hits.clear(); busy = true;
        boolean threw = false;
        try { CloudVoice.speakToFile("k", "m", "v", "", "hi", f, 5000, true, null); } catch (IOException e) { threw = true; }
        check("Google busy after a way worked: one request, then an error " + hits, threw && hits.size() == 1);
        busy = false;
        s.stop(0);
    }
}
