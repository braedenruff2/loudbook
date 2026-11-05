package com.loudbook.app;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Net.download (updates, voices, voice-command models) against a small local web server: carrying on from a part
 * downloaded before, a server that ignores the range, a part that's already whole, redirects, a download cut short.
 * Plain Java (javac with Net.java and test/unit's BuildInfo stub; no Android).
 */
public class NetTest {
    static int fails = 0;
    static void check(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) { fails++; System.out.println("     want " + want + "\n     got  " + got); }
    }

    static byte[] data = new byte[300_000];
    static volatile boolean honourRange = true, cutShort = false, noLength = false;
    static final List<String> asked = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        new java.util.Random(7).nextBytes(data);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/moved", x -> { x.getResponseHeaders().add("Location", "/file"); x.sendResponseHeaders(302, -1); x.close(); });
        srv.createContext("/file", x -> {
            String range = x.getRequestHeaders().getFirst("Range");
            synchronized (asked) { asked.add(range == null ? "-" : range); }
            int from = 0;
            if (range != null && honourRange) from = Integer.parseInt(range.replaceAll("\\D*(\\d+)-.*", "$1"));
            if (from >= data.length) {
                x.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                x.sendResponseHeaders(416, -1); x.close(); return;
            }
            int n = data.length - from;
            if (from > 0) x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
            x.sendResponseHeaders(from > 0 ? 206 : 200, noLength || cutShort ? 0 : n);   // (cut short: sent in chunks, ended early)
            try (OutputStream o = x.getResponseBody()) {
                o.write(data, from, cutShort ? n / 2 : n);
            } catch (java.io.IOException ignored) { }
            x.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        File dir = Files.createTempDirectory("nettest").toFile();
        try {
            File f = new File(dir, "a.bin"), part = new File(dir, "a.bin.part");

            Net.download(base + "/file", f, data.length, null);
            check("a fresh download is whole", same(f), true);

            f.delete(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, 100_000)); asked.clear();
            Net.download(base + "/file", f, data.length, null);
            check("carries on from the part downloaded before", same(f) && !part.exists(), true);
            check("asked only for the rest", asked.toString(), "[bytes=100000-]");

            f.delete(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, 100_000)); honourRange = false;
            Net.download(base + "/file", f, data.length, null);
            check("a server that sends it all again: starts over, still whole", same(f), true);
            honourRange = true;

            f.delete(); Files.write(part.toPath(), data); asked.clear();
            Net.download(base + "/file", f, data.length, null);
            check("a part that's already whole (416) is kept", same(f) && !part.exists(), true);

            f.delete(); Files.write(part.toPath(), data);
            String err = null;
            try { Net.download(base + "/file", f, 0, null); } catch (Exception e) { err = e.getMessage(); }
            check("a whole part, size not known ahead (416): kept, no error", err == null && same(f), true);

            f.delete(); asked.clear();
            Net.download(base + "/moved", f, data.length, null);
            check("follows a redirect", same(f), true);

            asked.clear();
            Net.download(base + "/file", f, data.length, null);
            check("already there: not downloaded again", asked.size(), 0);

            f.delete(); part.delete(); cutShort = true; err = null;
            try { Net.download(base + "/file", f, data.length, null); } catch (Exception e) { err = e.getMessage(); }
            check("cut short: an error, nothing saved as whole", err != null && !f.exists() && part.exists(), true);
            cutShort = false;
            Net.download(base + "/file", f, data.length, null);
            check("cut short, then the next try carries on to the end", same(f), true);

            f.delete(); Files.write(part.toPath(), new byte[data.length + 10]);
            Net.download(base + "/file", f, data.length, null);
            check("a part bigger than the file is dropped", same(f), true);

            f.delete(); Files.write(part.toPath(), new byte[data.length + 10]); err = null;
            try { Net.download(base + "/file", f, 0, null); } catch (Exception e) { err = e.getMessage(); }
            check("a part bigger than the file, size not known ahead: an error, and the part dropped", err != null && !part.exists(), true);
            Net.download(base + "/file", f, 0, null);
            check("... so the next try is whole", same(f), true);

            f.delete(); noLength = true;
            Net.download(base + "/file", f, 0, null);
            check("no length from the server, size not known ahead: whole", same(f), true);
            noLength = false;
        } finally {
            srv.stop(0);
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    static boolean same(File f) throws Exception { return f.exists() && java.util.Arrays.equals(Files.readAllBytes(f.toPath()), data); }
}
