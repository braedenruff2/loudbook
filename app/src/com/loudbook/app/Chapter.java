package com.loudbook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A chapter as the page script (assets/web/loudbook.js) hands it over. */
final class Chapter {
    static final class Chunk {
        final int block; final String text; final String say;
        Chunk(int block, String text, String say) { this.block = block; this.text = text; this.say = say; }
    }

    final String url, site, title, fiction, nextUrl, prevUrl;
    // (Kindle "chapters" grow as pages are turned while reading, so this must be safe to add to)
    final List<Chunk> chunks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A chapter made in the app (the Kindle reader). */
    Chapter(String url, String site, String title, String fiction, List<Chunk> chunks) {
        this.url = url; this.site = site; this.title = title; this.fiction = fiction;
        this.nextUrl = null; this.prevUrl = null;
        this.chunks.addAll(chunks);
    }

    Chapter(JSONObject o) throws Exception {
        url = o.optString("url");
        site = o.optString("site");
        title = o.optString("title");
        fiction = o.optString("fiction");
        nextUrl = o.isNull("nextUrl") ? null : o.optString("nextUrl", null);
        prevUrl = o.isNull("prevUrl") ? null : o.optString("prevUrl", null);
        JSONArray a = o.getJSONArray("chunks");
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            chunks.add(new Chunk(c.optInt("block", -1), c.optString("text"), c.optString("say", c.optString("text"))));
        }
    }

    int size() { return chunks.size(); }

    /** Non-space characters of this paragraph that come before chunk i (to find it on the page). */
    int offsetInBlock(int i) {
        int n = 0;
        Chunk c = chunks.get(i);
        for (int k = i - 1; k >= 0 && chunks.get(k).block == c.block; k--) n += chunks.get(k).text.replaceAll("\\s+", "").length();
        return n;
    }

    /** Rough minutes left at a given speed (about 15 characters a second at 1x). */
    int minutesLeft(int pos, float speed) {
        long chars = 0;
        for (int i = Math.max(0, pos); i < chunks.size(); i++) chars += chunks.get(i).text.length();
        return (int) Math.max(1, Math.round(chars / (15.0 * speed) / 60.0));
    }
}
