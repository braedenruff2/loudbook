package com.loudbook.app;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The PC's books that come as text (tools\volumes.py: "NNNN - name.text.json", one book per volume), read by the reader
 * like any web chapter: each chapter is a page at https://book.loudbook.invalid/<book>/<file>, made here from its text
 * (the browsers ask for it and get it from shouldInterceptRequest, so it needs no connection), with "next" going on to the
 * book's next chapter, and from its last chapter to the next volume's first. The voice is the reader's (Kokoro, or the PC
 * when paired); where the PC recorded a chapter and that recording is on the phone, its sentences are played (BookAudio).
 */
final class BookPages {
    static final String TAG = "Loudbook";
    static final String HOST = "book.loudbook.invalid";
    static final String BASE = "https://" + HOST + "/";
    static final String SITE = "book";

    static boolean isBook(String url) { return url != null && url.startsWith(BASE); }

    static String url(String book, String file) { return BASE + enc(book) + "/" + enc(file); }

    /** Everything but A-Z a-z 0-9 - . _ ~ as %XX (the browser keeps that as it is, so addresses compare equal). */
    private static String enc(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return b.toString();
    }

    /** [book, file] of a book page's address, or null. */
    static String[] parse(String url) {
        if (!isBook(url)) return null;
        String rest = url.substring(BASE.length());
        int cut = rest.indexOf('#'); if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('?'); if (cut >= 0) rest = rest.substring(0, cut);
        String[] p = rest.split("/");
        if (p.length != 2) return null;
        try {
            String b = java.net.URLDecoder.decode(p[0].replace("+", "%2B"), "UTF-8"), f = java.net.URLDecoder.decode(p[1].replace("+", "%2B"), "UTF-8");
            return Books.safe(b) && Books.safe(f) && f.endsWith(".json") ? new String[]{b, f} : null;
        } catch (Exception e) { return null; }
    }

    /** For the browsers: a book page, or null for every other address. */
    static WebResourceResponse intercept(Context ctx, WebResourceRequest r) {
        String u = r.getUrl().toString();
        if (!isBook(u)) return null;
        String html = null;
        try { String[] bf = parse(u); if (bf != null) html = html(ctx, bf[0], bf[1]); }
        catch (Exception e) { Log.w(TAG, "book page " + u, e); }
        if (html == null) {
            // (no paragraphs: the reader says there's no chapter here, rather than reading this note)
            html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"loudbook-offline\" data-site=\"book\"><title>Not on this phone</title></head><body style=\"background:#14120F;color:#EDE6DA;font:17px/1.6 sans-serif;margin:16px\">"
                + "<p>This chapter isn't on this phone. Open Audiobooks and tap “Get chapters from my PC”.</p></body></html>";
        }
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    /** The chapter's page: its title, then every paragraph (each one read, nothing left out). */
    static String html(Context ctx, String book, String file) throws Exception {
        String raw = Books.chapterText(ctx, book, file);
        if (raw.isEmpty()) return null;
        JSONObject o = new JSONObject(raw);
        JSONArray blocks = o.optJSONArray("blocks");
        if (blocks == null) return null;
        List<Books.Chapter> chs = Books.chapters(ctx, book);
        int at = -1;
        for (int i = 0; i < chs.size(); i++) if (chs.get(i).json.equals(file)) at = i;
        String prev = at > 0 ? url(book, chs.get(at - 1).json) : "";
        String next = at >= 0 && at + 1 < chs.size() ? url(book, chs.get(at + 1).json) : nextBook(ctx, book, o.optString("book", ""));
        if (prev.isEmpty()) prev = prevBook(ctx, book, o.optString("book", ""));
        String title = o.optString("title", file);
        String vol = o.optString("volume", book);
        StringBuilder h = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        // (bridge.js reads the [data-lb-block] paragraphs of a page with this, as they are: see its extract())
        h.append("<meta name=\"loudbook-offline\" data-site=\"").append(SITE).append("\" data-fiction=\"").append(TextUtils.htmlEncode(book))
            .append("\" data-next=\"").append(TextUtils.htmlEncode(next)).append("\" data-prev=\"").append(TextUtils.htmlEncode(prev)).append("\">");
        h.append("<title>").append(TextUtils.htmlEncode(title)).append("</title><style>")
            .append("html,body{background:#14120F;color:#EDE6DA}body{font:19px/1.65 Georgia,'Noto Serif',serif;margin:0 auto;padding:12px 18px 40px;max-width:720px;overflow-wrap:break-word}")
            .append("h1{font-size:1.35em;line-height:1.3;margin:.3em 0 .8em}.lb-note{font:13px/1.4 system-ui,sans-serif;color:#A79E90;letter-spacing:.06em;text-transform:uppercase}")
            .append("p{margin:0 0 .8em}</style></head><body>");
        h.append("<div class=\"lb-note\">").append(TextUtils.htmlEncode(o.optString("book", "") + " · " + vol))
            .append(at >= 0 ? " · chapter " + (at + 1) + " of " + chs.size() : "").append("</div>");
        h.append("<h1>").append(TextUtils.htmlEncode(title)).append("</h1>");
        for (int i = 0; i < blocks.length(); i++) h.append("<p data-lb-block>").append(TextUtils.htmlEncode(blocks.optString(i, ""))).append("</p>");
        h.append("</body></html>");
        return h.toString();
    }

    /** The first chapter of the next volume of the same book (the books are named in reading order), or "". */
    private static String nextBook(Context ctx, String book, String series) { return neighbour(ctx, book, series, 1); }
    private static String prevBook(Context ctx, String book, String series) { return neighbour(ctx, book, series, -1); }
    private static String neighbour(Context ctx, String book, String series, int d) {
        if (series.isEmpty()) return "";
        List<String> all = Books.list(ctx);
        int i = all.indexOf(book);
        if (i < 0 || i + d < 0 || i + d >= all.size()) return "";
        String other = all.get(i + d);
        List<Books.Chapter> chs = Books.chapters(ctx, other);
        if (chs.isEmpty()) return "";
        Books.Chapter c = d > 0 ? chs.get(0) : chs.get(chs.size() - 1);
        if (!c.json.endsWith(".text.json")) return "";
        try { if (!series.equals(new JSONObject(Books.chapterText(ctx, other, c.json)).optString("book", ""))) return ""; }
        catch (Exception e) { return ""; }
        return url(other, c.json);
    }

    private BookPages() { }
}
