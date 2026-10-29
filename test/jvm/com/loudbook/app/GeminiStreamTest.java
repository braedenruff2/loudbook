package com.loudbook.app;

import com.k2fsa.sherpa.onnx.*;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gemini end to end against a stand-in Google: a "chapter" spoken by Kokoro is sent back the way
 * Google's API sends speech, a little faster than it plays (as Gemini makes it). Checks that:
 * the reading style goes only in Google's style field (never in the text, where it was read aloud);
 * streamed audio is decoded piece by piece; the first sentence is ready within seconds, long before
 * the recording ends; every sentence is cut where it really starts, also when reading starts in the
 * middle of a chapter; and the older API (no style field, streamed as server-sent events) and the
 * all-at-once WAV reply still work.
 */
public class GeminiStreamTest {
    static int bad = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) bad++; }

    static volatile String mode;                      // how the stand-in answers
    static final List<String> bodies = new ArrayList<>();
    static byte[] pcm;                                // the audio it sends (16-bit, 24 kHz)
    static int stopAt = -1;                           // bytes: Google stops the recording there (mid-sentence)

    public static void main(String[] a) throws Exception {
        java.io.File files = new java.io.File(a[0]);
        String k = a[1];
        OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
            .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(k + "/model.onnx").setVoices(k + "/voices.bin")
                .setTokens(k + "/tokens.txt").setLexicon(k + "/lexicon-us-en.txt").setDataDir(k + "/espeak-ng-data").build())
            .setNumThreads(2).setDebug(false).build()).build());
        String[] text = AsrTest.TEXT;
        int rate = 24000;
        List<Chapter.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < text.length; i++) chunks.add(new Chapter.Chunk(i / 4, text[i], text[i]));
        Chapter ch = new Chapter("https://example.com/ch1", "test", "Chapter", "Story", chunks);

        AsrAligner asr = new AsrAligner();
        asr.load(new java.io.File(files, "asr"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().toString();
            synchronized (bodies) { bodies.add(path + " " + body); }
            boolean interactions = path.contains("interactions"), streamed = body.contains("\"stream\":true") || path.contains("streamGenerateContent");
            try {
                if (mode.equals("old api") && interactions) { send(ex, 400, "{\"error\":{\"message\":\"Unknown name \\\"input\\\"\"}}"); return; }
                if (!streamed) {                                             // all at once: a WAV file in JSON
                    byte[] wav = wav(pcm, rate);
                    String json = interactions
                        ? "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\"" + Base64.getEncoder().encodeToString(wav) + "\"}]}]}"
                        : "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"" + Base64.getEncoder().encodeToString(wav) + "\"}}]}}]}";
                    Thread.sleep(pcm.length / 2 * 1000L / rate / 3);         // (made at three times the speed it plays)
                    send(ex, 200, json);
                    return;
                }
                // streamed: a piece every so often, each half a second of speech, made at 3x real time
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream o = ex.getResponseBody();
                Thread.sleep(1500);                                          // (Gemini takes a moment to begin)
                if (interactions) o.write(("data: {\"event_type\":\"interaction.start\",\"interaction\":{\"id\":\"x\"}}\n\n").getBytes(StandardCharsets.UTF_8));
                int piece = rate;                                            // bytes: half a second
                int len = stopAt > 0 ? stopAt : pcm.length;
                for (int at = 0; at < len; at += piece) {
                    byte[] part = java.util.Arrays.copyOfRange(pcm, at, Math.min(len, at + piece));
                    String b64 = Base64.getEncoder().encodeToString(part);
                    String ev = interactions
                        ? "data: {\"event_type\":\"step.delta\",\"index\":0,\"delta\":{\"type\":\"audio\",\"mime_type\":\"audio/l16\",\"data\":\"" + b64 + "\"}}\n\n"
                        : "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/L16;codec=pcm;rate=24000\",\"data\":\"" + b64 + "\"}}]}}],\"modelVersion\":\"x\"}\r\n\r\n";
                    o.write(ev.getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    Thread.sleep(500 / 3);
                }
                if (interactions) o.write("data: {\"event_type\":\"interaction.complete\"}\n\n".getBytes(StandardCharsets.UTF_8));
                o.close();
            } catch (Exception e) { e.printStackTrace(); }
        });
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/";
        System.setProperty("loudbook.gemini", host);
        // (CloudVoice reads the address once)
        check("the stand-in Google is used", CloudVoice.HOST.equals(host));

        String style = CloudVoice.DEFAULT_STYLE;
        String[][] runs = {{"streamed", "0"}, {"streamed, started mid-chapter", "7"}, {"old api", "0"}, {"all at once", "0"}, {"streamed, Google stops early", "0"}};
        for (String[] run : runs) {
            mode = run[0];
            int from = Integer.parseInt(run[1]);
            CloudVoice.lastWay = -1;
            synchronized (bodies) { bodies.clear(); }
            // what Kokoro says for sentences from..end, with the true start of each
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.util.Random rnd = new java.util.Random(11);
            long n = 0;
            double[] truth = new double[text.length - from];
            for (int i = from; i < text.length; i++) {
                float[] x = Voice.trim(tts.generate(text[i], 3, 1.0f).getSamples(), rate);
                truth[i - from] = n / (double) rate;
                for (float f : x) { short s = (short) (f * 32767); out.write(s & 0xff); out.write((s >> 8) & 0xff); n++; }
                int gap = (int) (rate * (0.25 + rnd.nextDouble() * 0.45));
                for (int g = 0; g < gap; g++) { out.write(0); out.write(0); n++; }
            }
            pcm = out.toByteArray();
            // (Google stops early: halfway through sentence 12)
            int stopIn = 12;
            stopAt = run[0].contains("stops early") ? (int) ((truth[stopIn] + truth[stopIn + 1]) / 2 * rate) * 2 : -1;
            double seconds = (stopAt > 0 ? stopAt : pcm.length) / 2.0 / rate;

            java.io.File dir = new java.io.File(files, "book-" + run[0].replace(' ', '-').replace(",", ""));
            deleteAll(dir);
            GeminiBook b = GeminiBook.open(dir.getParentFile(), dir.getName());
            b.bind(ch);
            long t0 = System.currentTimeMillis();
            final long[] firstReady = {-1};
            Thread watch = new Thread(() -> {
                while (firstReady[0] < 0 && System.currentTimeMillis() - t0 < 300_000) {
                    if (b.has(from)) firstReady[0] = System.currentTimeMillis() - t0;
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
            });
            watch.start();
            boolean live = !run[0].equals("all at once");
            // (all at once: not streamed, but still listened to, as fast as it can be, before it's played)
            GeminiBook.Made m = b.fetch(ch, from, text.length, "test-key", "gemini-3.8-flash-tts", "Sulafat", style, 0, new double[1], asr, new ArrayList<>(), live);
            long took = System.currentTimeMillis() - t0;
            watch.join();
            System.out.printf("== %s: %.0f s of speech; first sentence ready after %.1f s, all after %.1f s; %s%n",
                run[0], seconds, firstReady[0] / 1000.0, took / 1000.0, CloudVoice.how);

            String req;
            synchronized (bodies) { req = String.join("\n", bodies); }
            String lastReq;
            synchronized (bodies) { lastReq = bodies.get(bodies.size() - 1); }
            if (lastReq.contains("interactions")) {
                check(run[0] + ": the style goes in Google's style field", lastReq.contains("\"speech_metadata\",\"style\":"));
                check(run[0] + ": the text sent is the chapter's text alone", lastReq.contains("\"text\":\"" + text[from]));
            } else check(run[0] + ": the older API gets the text with no style in front", !lastReq.contains("narrator") && lastReq.contains("\"text\":\"" + text[from]));
            if (stopAt > 0) {
                // the sentences before the one it stopped in are kept; that one is asked for again (not played cut off)
                check(run[0] + ": the sentences it got through are kept, the one it stopped in isn't (" + m.count + ")", m.count == stopIn && b.has(stopIn - 1) && !b.has(stopIn));
                stopAt = -1;
                continue;
            }
            // (the end of a recording isn't taken for Google stopping early: the last sentences were
            // dropped, and asked for again in another request)
            check(run[0] + ": every sentence recorded, to the end", m.count == text.length - from && b.has(text.length - 1));
            check(run[0] + ": timed by the words heard", m.timed);
            if (live) {
                check(run[0] + ": streamed", req.contains("\"stream\":true") || req.contains("streamGenerateContent"));
                check(run[0] + ": the first sentence is ready long before the recording is done", firstReady[0] >= 0 && firstReady[0] < took / 2 && firstReady[0] < 9000);
            } else check(run[0] + ": not streamed", !req.contains("\"stream\":true"));
            // each sentence's clip starts where the sentence really does
            double worst = 0; int worstAt = -1;
            for (int i = from; i < text.length; i++) {
                double st = b.startOf(i) / (double) rate, tr = truth[i - from];
                if (Math.abs(st - tr) > worst) { worst = Math.abs(st - tr); worstAt = i; }
            }
            System.out.printf("   worst start error %.2f s (sentence %d)%n", worst, worstAt);
            check(run[0] + ": no sentence is off (every start within half a second)", worst < 0.5);
            Voice.Clip c = b.clip(text.length - 1);
            check(run[0] + ": the last clip has the end of the recording", c.samples.length > rate / 2);
        }
        // packed: the end of one chapter and the start of the next in one request
        {
            mode = "streamed";
            CloudVoice.lastWay = -1;
            int split = 12;
            List<Chapter.Chunk> a1 = new ArrayList<>(), a2 = new ArrayList<>();
            for (int i = 0; i < text.length; i++) (i < split ? a1 : a2).add(new Chapter.Chunk(i / 4, text[i], text[i]));
            Chapter c1 = new Chapter("https://example.com/p1", "test", "One", "Story", a1);
            Chapter c2 = new Chapter("https://example.com/p2", "test", "Two", "Story", a2);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.util.Random rnd = new java.util.Random(5);
            long n = 0;
            double[] truth = new double[text.length];
            for (int i = 0; i < text.length; i++) {
                float[] x = Voice.trim(tts.generate(text[i], 3, 1.0f).getSamples(), rate);
                truth[i] = n / (double) rate;
                for (float f : x) { short sh = (short) (f * 32767); out.write(sh & 0xff); out.write((sh >> 8) & 0xff); n++; }
                int gap = (int) (rate * (i == split - 1 ? 1.2 : 0.25 + rnd.nextDouble() * 0.45));
                for (int g = 0; g < gap; g++) { out.write(0); out.write(0); n++; }
            }
            pcm = out.toByteArray();
            java.io.File d1 = new java.io.File(files, "book-p1"), d2 = new java.io.File(files, "book-p2");
            deleteAll(d1); deleteAll(d2);
            GeminiBook b1 = GeminiBook.open(files, "book-p1"), b2 = GeminiBook.open(files, "book-p2");
            b1.bind(c1); b2.bind(c2);
            List<GeminiBook.Span> more = new ArrayList<>();
            more.add(new GeminiBook.Span(c2, b2, 0, c2.size()));
            GeminiBook.Made m = b1.fetch(c1, 0, c1.size(), "test-key", "gemini-3.8-flash-tts", "Sulafat", style, 0, new double[1], asr, more);
            String req;
            synchronized (bodies) { req = bodies.get(bodies.size() - 1); }
            System.out.printf("== packed: %d + %d sentences in one request%n", m.count, m.extra);
            check("packed: one request holds the end of this chapter and the next one's start", req.contains(text[split - 1]) && req.contains(text[split]));
            check("packed: all of this chapter filed here", m.count == split && b1.has(split - 1));
            check("packed: the next chapter's sentences are filed in its own book", m.extra == text.length - split && b2.has(0) && b2.has(c2.size() - 1));
            double worst = 0;
            for (int i = 0; i < split; i++) worst = Math.max(worst, Math.abs(b1.startOf(i) / (double) rate - truth[i]));
            double base = b2.startOf(0) / (double) rate;
            for (int i = split; i < text.length; i++) worst = Math.max(worst, Math.abs((b2.startOf(i - split) / (double) rate - base) - (truth[i] - truth[split])));
            System.out.printf("   worst start error %.2f s%n", worst);
            check("packed: every sentence in both chapters starts where it should", worst < 0.5);
            Voice.Clip last = b1.clip(split - 1), firstNext = b2.clip(0);
            check("packed: this chapter's last clip stops before the next chapter begins", last.samples.length / (double) rate < (truth[split] - truth[split - 1]) + 0.2);
            check("packed: the next chapter's first clip is its first sentence", Math.abs(firstNext.samples.length / (double) rate - (truth[split + 1] - truth[split])) < 0.6);
            // and it's kept: opened again later, the next chapter's book still has its recording
            GeminiBook again = GeminiBook.open(files, "book-p2");
            check("packed: the next chapter's recording is kept", again.recorded() == c2.size());
        }
        server.stop(0);
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static byte[] wav(byte[] pcm, int rate) {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(pcm.length).put(pcm);
        return h.array();
    }

    static void deleteAll(java.io.File d) {
        java.io.File[] fs = d.listFiles();
        if (fs != null) for (java.io.File f : fs) f.delete();
        d.delete();
    }
}
