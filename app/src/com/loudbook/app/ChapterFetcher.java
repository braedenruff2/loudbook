package com.loudbook.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens chapters in a browser of its own, with no screen, inside the reading service: the next
 * chapter is loaded while this one plays, so the page turn needs nothing from the Loudbook screen
 * (which may be closed, or the phone locked). Uses the same page script and cookies as the screen.
 * Main thread only.
 */
final class ChapterFetcher {
    interface Done { void on(Chapter ch, String error); }

    private static final String TAG = "Loudbook";
    private static final long TIMEOUT_MS = 45_000;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private String js;
    private String loading;                          // the address being opened, or null
    private final List<Done> waiting = new ArrayList<>();
    private int attempt;                             // bumped per load: late answers from an old page are ignored
    private String doneUrl;
    private Chapter doneCh;

    ChapterFetcher(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** The chapter at this address, loaded already or as soon as it's ready. */
    void get(String url, Done d) {
        if (url.equals(doneUrl) && doneCh != null) { d.on(doneCh, null); return; }
        if (url.equals(loading)) { waiting.add(d); return; }
        fail("replaced");
        loading = url;
        waiting.add(d);
        start(url);
    }

    boolean busyWith(String url) { return url != null && url.equals(loading); }
    Chapter ready(String url) { return url != null && url.equals(doneUrl) ? doneCh : null; }

    void destroy() {
        fail("stopped");
        if (web != null) { try { web.destroy(); } catch (Exception ignored) { } web = null; }
    }

    private void start(String url) {
        final int my = ++attempt;
        try {
            if (web == null) make();
        } catch (Throwable t) {
            Log.w(TAG, "fetcher", t);
            loading = null;
            finish(null, "no browser: " + t.getMessage());
            return;
        }
        Log.i("LoudbookTest", "fetching " + url);
        web.loadUrl(url);
        main.postDelayed(() -> { if (my == attempt && loading != null) finish(null, "the page took too long"); }, TIMEOUT_MS);
        // pages that fill in their text late, or a site check that reloads the page: look again now and then
        Runnable[] again = new Runnable[1];
        again[0] = () -> {
            if (my != attempt || loading == null || web == null) return;
            run();
            main.postDelayed(again[0], 4000);
        };
        main.postDelayed(again[0], 6000);
    }

    private void make() {
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBlockNetworkImage(true);                 // the text is all that's wanted
        web.addJavascriptInterface(new Bridge(), "LoudbookNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { if (url != null && url.startsWith("http")) run(); }
        });
        // a phone-sized page (it's never shown), so the page lays out as it would on screen
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        web.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, dm.widthPixels, dm.heightPixels);
        js = readAsset("web/loudbook.js");
    }

    private void run() {
        if (web == null || loading == null) return;
        android.content.SharedPreferences p = Voice.prefs(ctx);
        web.evaluateJavascript(js, null);
        web.evaluateJavascript("window.LB && LB.run(" + JSONObject.quote(p.getString("fixes", "")) + "," + p.getBoolean("tidy", true) + ")", null);
    }

    private void finish(Chapter ch, String error) {
        List<Done> ds = new ArrayList<>(waiting);
        waiting.clear();
        String url = loading;
        loading = null;
        attempt++;
        if (ch != null) { doneUrl = url; doneCh = ch; }
        if (web != null) web.loadUrl("about:blank");     // stop the page's scripts and ads
        Log.i("LoudbookTest", "fetched " + url + ": " + (ch != null ? ch.size() + " sentences, next " + ch.nextUrl : error));
        for (Done d : ds) { try { d.on(ch, error); } catch (Throwable t) { Log.w(TAG, "fetcher callback", t); } }
    }

    private void fail(String why) { if (loading != null) finish(null, why); }

    final class Bridge {
        @JavascriptInterface public void onChapter(String json) {
            main.post(() -> {
                if (loading == null) return;
                try {
                    Chapter c = new Chapter(new JSONObject(json));
                    if (c.size() > 1) finish(c, null);
                } catch (Exception e) { Log.w(TAG, "fetched chapter", e); }
            });
        }
        // not a chapter (yet): a site check, or text that arrives late. Keep looking until the time is up.
        @JavascriptInterface public void onNoChapter(String json) { }
        @JavascriptInterface public String home() { return "{}"; }
        @JavascriptInterface public void resume(String url) { }
    }

    private String readAsset(String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
