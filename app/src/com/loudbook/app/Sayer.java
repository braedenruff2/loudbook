package com.loudbook.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the voice is given for text that doesn't come from a web page (the Kindle reader): the same
 * clean-ups the page script makes (numbers as words, Roman numerals, "Mr.", stat blocks, scene
 * breaks...), run by the same script in a small browser with no screen. Call from a background thread.
 */
final class Sayer {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile boolean ready;

    Sayer(Context c) { ctx = c.getApplicationContext(); }

    /** The spoken form of each text (the texts as they are if anything goes wrong). Blocks; not on the main thread. */
    List<String> say(List<String> texts, String fixes, boolean tidy) {
        if (texts.isEmpty()) return texts;
        final String[] out = new String[1];
        CountDownLatch done = new CountDownLatch(1);
        String call = "(function(){try{var a=" + new JSONArray(texts) + ";return JSON.stringify(a.map(function(t){return LB.sayText(t,"
            + JSONObject.quote(fixes == null ? "" : fixes) + "," + tidy + ");}));}catch(e){return null;}})()";
        main.post(() -> {
            try {
                if (web == null) {
                    web = new WebView(ctx);
                    web.getSettings().setJavaScriptEnabled(true);           // (its empty page is enough)
                }
                if (!ready) { web.evaluateJavascript(readAsset("web/loudbook.js"), null); ready = true; }
                web.evaluateJavascript(call, r -> { out[0] = r; done.countDown(); });
            } catch (Throwable t) { Log.w("Loudbook", "sayer", t); done.countDown(); }
        });
        try { done.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try {
            if (out[0] == null || out[0].equals("null")) return texts;
            // evaluateJavascript hands back a JSON string holding our JSON array
            JSONArray a = new JSONArray(new JSONArray("[" + out[0] + "]").getString(0));
            if (a.length() != texts.size()) return texts;
            List<String> r = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
            return r;
        } catch (Exception e) { Log.w("Loudbook", "sayer result", e); return texts; }
    }

    void destroy() { main.post(() -> { if (web != null) { web.destroy(); web = null; ready = false; } }); }

    private String readAsset(String path) throws Exception {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }
}
