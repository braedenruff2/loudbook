package com.loudbook.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small HTTP helpers: resumable file downloads and fetching short text. */
final class Net {
    interface Progress { void on(long done, long total); }

    static String text(String url) throws Exception {
        HttpURLConnection c = open(url, 0);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /**
     * Downloads url to dest, carrying on from a partial dest.part left by an earlier try.
     * expected: the size it must end up (0 = don't check).
     */
    static void download(String url, File dest, long expected, Progress p) throws Exception {
        if (dest.exists() && (expected <= 0 || dest.length() == expected)) return;
        dest.getParentFile().mkdirs();
        File part = new File(dest.getPath() + ".part");
        long have = part.exists() ? part.length() : 0;
        if (expected > 0 && have > expected) { part.delete(); have = 0; }
        HttpURLConnection c = open(url, have);
        long fullSize = 0;
        int code = c.getResponseCode();
        if (code == 416 && expected > 0 && have == expected) { c.disconnect(); }
        else {
            if (code != 200 && code != 206) { c.disconnect(); throw new java.io.IOException("download failed (" + code + ")"); }
            boolean append = code == 206;
            if (!append) have = 0;
            long len = c.getContentLengthLong();
            long total = len > 0 ? have + len : expected;
            fullSize = len > 0 ? have + len : 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16]; int n; long last = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); have += n;
                    long now = System.currentTimeMillis();
                    if (p != null && now - last > 400) { last = now; p.on(have, total); }
                }
            } finally { c.disconnect(); }
        }
        if (expected > 0 && part.length() != expected) throw new java.io.IOException("download incomplete, will retry");
        if (expected <= 0 && fullSize > 0 && part.length() != fullSize) throw new java.io.IOException("download incomplete, will retry");
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) throw new java.io.IOException("couldn't save " + dest.getName());
    }

    private static HttpURLConnection open(String url, long from) throws Exception {
        // Follow redirects by hand: GitHub release files redirect to another host.
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Loudbook/" + BuildInfo.VERSION);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }
}
