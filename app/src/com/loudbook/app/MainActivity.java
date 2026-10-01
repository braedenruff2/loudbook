package com.loudbook.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The screen: a browser for the fiction sites with a player underneath. The page script
 * (assets/web/loudbook.js, shared with the Chrome extension) finds the chapter; the
 * ReaderService reads it aloud and asks this screen to open the next chapter when one ends.
 */
public class MainActivity extends Activity implements ReaderService.Listener {
    static final String TAG = "Loudbook";
    static final String HOME = "file:///android_asset/web/home.html";
    static final int C_BG = 0xFF14120F, C_PANEL = 0xFF1E1B17, C_LINE = 0xFF2E2A24, C_INK = 0xFFEDE6DA,
        C_DIM = 0xFFA79E90, C_AMBER = 0xFFE8AA46, C_RED = 0xFFE0735F;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private EditText address;
    private TextView storyText, chapterText, nowText, statusText, speedBtn;
    private android.widget.ImageView playBtn;
    private Button readThisBtn, updateBtn;
    private ReaderService svc;
    private String pageJs;
    private Chapter pageChapter;           // the chapter on screen (may differ from the one playing)
    private String awaitingTurn = null;    // we're opening this URL because the chapter ended
    private boolean resumeAfterTurn = true;
    private float lastTouchX, lastTouchY;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((ReaderService.LocalBinder) b).service();
            svc.setListener(MainActivity.this);
            svc.setAppVisible(visible);
            runSelfTest();
            speedBtn.setText(fmtSpeed(svc.speed()));
            if (pageChapter != null && svc.chapter() == null) offer(pageChapter);
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    // ---------------------------------------------------------------- setup
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashCatcher();
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_PANEL);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        pageJs = readAsset("web/loudbook.js");
        setContentView(buildUi());
        setupWeb();
        ReaderService.start(this);
        bindService(new Intent(this, ReaderService.class), conn, BIND_AUTO_CREATE);
        if (!handleIntent(getIntent())) {
            if (state != null) web.restoreState(state); else web.loadUrl(HOME);
        }
        showLastCrash();
        if (VoiceCommands.crashedLastTime(this))
            main.postDelayed(() -> onStatus("Voice commands closed Loudbook while starting, so they're off for now. An update will fix it.", true), 1500);
    }

    // If the app ever crashes, the next launch shows what happened, with a Copy button, so it can
    // be sent to whoever is fixing it.
    private void installCrashCatcher() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final java.io.File f = new java.io.File(getFilesDir(), "last-crash.txt");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(f)) {
                w.println("Loudbook " + BuildInfo.VERSION + " on Android " + Build.VERSION.RELEASE + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")");
                e.printStackTrace(w);
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
    /**
     * Android's own record of how Loudbook last ended. Catches what the Java crash catcher can't:
     * crashes in native code (the voice engines), being killed for memory, or quitting from native.
     */
    private String lastNativeExit() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
            long seen = prefs.getLong("exitSeen", 0);
            long since = seen > 0 ? seen : System.currentTimeMillis() - 3 * 24 * 3600_000L;   // first time: the last few days
            if (!l.isEmpty()) prefs.edit().putLong("exitSeen", l.get(0).getTimestamp()).apply();
            android.app.ApplicationExitInfo x = null;
            for (android.app.ApplicationExitInfo e : l) {                    // newest first: the latest bad ending
                if (e.getTimestamp() <= since) break;
                int r = e.getReason();
                if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_SIGNALED
                    || r == android.app.ApplicationExitInfo.REASON_ANR || r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                    || r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    || (r == android.app.ApplicationExitInfo.REASON_EXIT_SELF && e.getStatus() != 0)) { x = e; break; }
            }
            if (x == null) return null;
            int r = x.getReason();
            StringBuilder sb = new StringBuilder();
            String[] names = {"unknown", "exit self", "signaled", "low memory", "crash", "native crash", "ANR", "init failure", "permission change",
                "excessive resource use", "user requested", "user stopped", "dependency died", "other", "freezer", "package state", "package updated"};
            sb.append("Loudbook ").append(BuildInfo.VERSION).append(" on Android ").append(Build.VERSION.RELEASE).append(" (")
              .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(")\n")
              .append("Ended: ").append(r < names.length ? names[r] : String.valueOf(r)).append(", status ").append(x.getStatus())
              .append(", ").append(String.valueOf(x.getDescription())).append(", ")
              .append(android.text.format.DateFormat.format("MMM d HH:mm", x.getTimestamp())).append("\n");
            try (java.io.InputStream in = x.getTraceInputStream()) {
                if (in != null) {
                    // the native trace is binary; its readable parts (libraries, functions, the abort message) are what matter
                    byte[] all = readAllBytes(in, 400_000);
                    StringBuilder run = new StringBuilder(); int lines = 0;
                    for (byte b : all) {
                        char ch = (char) (b & 0xff);
                        if (ch >= 32 && ch < 127) run.append(ch);
                        else { if (run.length() >= 6 && lines < 120) { sb.append(run).append('\n'); lines++; } run.setLength(0); }
                    }
                }
            }
            return sb.toString();
        } catch (Throwable t) { return null; }
    }
    private static byte[] readAllBytes(java.io.InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0 && out.size() < max) out.write(b, 0, n);
        return out.toByteArray();
    }

    private void showLastCrash() {
        java.io.File f = new java.io.File(getFilesDir(), "last-crash.txt");
        String text;
        String nat = lastNativeExit();
        if (!f.exists()) {
            if (nat == null) return;
            text = nat;
        } else {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                text = out.toString("UTF-8");
            } catch (Exception e) { text = String.valueOf(e); }
            f.delete();
        }
        final String report = text;
        TextView t = text(12, C_INK, false);
        t.setText(report); t.setTextIsSelectable(true); t.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView sv = new ScrollView(this); sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Loudbook stopped last time")
            .setView(sv)
            .setPositiveButton("Copy", (d, w) -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Loudbook crash", report));
            })
            .setNegativeButton("Close", null)
            .show();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handleIntent(intent); }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    // ---------------------------------------------------------------- updates
    private boolean visible = false;
    @Override protected void onStart() { super.onStart(); visible = true; if (svc != null) svc.setAppVisible(true); }

    @Override protected void onResume() {
        super.onResume();
        refreshUpdate();
        Updater.check(getApplicationContext(), false, () -> main.post(this::refreshUpdate));
    }

    /** Leaving the app with nothing playing is the moment to swap in a waiting update. */
    @Override protected void onStop() {
        super.onStop();
        visible = false;
        if (svc != null) svc.setAppVisible(false);
        boolean reading = svc != null && svc.isPlaying() && !svc.isPaused();
        if (!reading && !isChangingConfigurations() && Updater.readyCode(this) > 0 && Updater.mayInstall(this)) Updater.install(getApplicationContext());
    }

    private void refreshUpdate() {
        if (updateBtn == null) return;
        int code = Updater.readyCode(this);
        if (code == 0) { updateBtn.setVisibility(View.GONE); return; }
        updateBtn.setVisibility(View.VISIBLE);
        if (!Updater.mayInstall(this)) {
            updateBtn.setText("Update " + Updater.readyName(this) + " is ready. Tap, then allow Loudbook to install its updates (one time).");
            updateBtn.setOnClickListener(v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { onStatus("Allow it in Settings › Apps › Loudbook › Install unknown apps.", true); }
            });
        } else {
            updateBtn.setText("Update " + Updater.readyName(this) + " is ready — tap to restart into it (it also installs by itself when you leave the app).");
            updateBtn.setOnClickListener(v -> {
                if (svc != null && svc.isPlaying() && !svc.isPaused()) svc.pause();
                if (!Updater.install(getApplicationContext())) onStatus("The update couldn't start: " + Updater.lastError(this), true);
            });
        }
    }

    @Override protected void onDestroy() {
        if (svc != null) svc.setListener(null);
        unbindService(conn);
        super.onDestroy();
    }

    // A link shared from Chrome ("Share > Loudbook") or opened with Loudbook.
    private boolean handleIntent(Intent i) {
        if (i == null) return false;
        String url = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) url = firstUrl(i.getStringExtra(Intent.EXTRA_TEXT));
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) url = i.getData().toString();
        if (url == null) return false;
        go(url);
        return true;
    }

    private static String firstUrl(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : null;
    }

    private void go(String typed) {
        String u = typed.trim();
        if (u.isEmpty()) return;
        if (!u.matches("(?i)^[a-z]+://.*")) u = u.contains(".") && !u.contains(" ") ? "https://" + u
            : "https://www.royalroad.com/fictions/search?title=" + Uri.encode(u);
        web.loadUrl(u);
        hideKeyboard();
    }

    // ---------------------------------------------------------------- the browser
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setBackgroundColor(C_BG);
        web.addJavascriptInterface(new Bridge(), "LoudbookNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme));
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                address.setText(url.startsWith("file:") ? "" : url);
                pageChapter = null;
                readThisBtn.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView v, String url) { inject(url); }
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                // sites that change chapter without a page load (history.pushState)
                if (!reload && url.startsWith("http") && (pageChapter == null || !url.equals(pageChapter.url))) main.postDelayed(() -> inject(web.getUrl()), 1200);
            }
        });
        // long-press a paragraph: read from there
        web.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_DOWN) { lastTouchX = e.getX(); lastTouchY = e.getY(); } return false; });
        web.setOnLongClickListener(v -> {
            if (pageChapter == null) return false;
            float d = getResources().getDisplayMetrics().density;
            web.evaluateJavascript("window.LB ? LB.blockAt(" + (lastTouchX / d) + "," + (lastTouchY / d) + ") : -1", r -> {
                int block;
                try { block = Integer.parseInt(r.trim()); } catch (Exception e) { return; }
                if (block < 0) return;
                for (int i = 0; i < pageChapter.size(); i++) if (pageChapter.chunks.get(i).block == block) {
                    if (svc != null) {
                        if (svc.chapter() == null || !svc.chapter().url.equals(pageChapter.url)) svc.setChapter(pageChapter, i, true);
                        else { svc.seek(i); if (!svc.isPlaying()) play(); }
                    }
                    break;
                }
            });
            return true;
        });
    }

    private void inject(String url) { inject(web, url); }
    private void inject(WebView web, String url) {
        if (url == null || !url.startsWith("http")) return;
        String fixes = prefs.getString("fixes", "");
        boolean tidy = prefs.getBoolean("tidy", true);
        web.evaluateJavascript(pageJs, null);
        web.evaluateJavascript("window.LB && LB.run(" + JSONObject.quote(fixes) + "," + tidy + ")", null);
    }

    /** Called from the page script. Runs on a WebView thread: hop to the main thread. */
    final class Bridge {
        @JavascriptInterface public void onChapter(String json) {
            main.post(() -> {
                try { gotChapter(new Chapter(new JSONObject(json))); }
                catch (Exception e) { Log.e(TAG, "chapter", e); onStatus("Couldn't read this page: " + e.getMessage(), true); }
            });
        }
        @JavascriptInterface public void onNoChapter(String json) {
            main.post(() -> {
                pageChapter = null;
                if (awaitingTurn != null) {
                    awaitingTurn = null;
                    onStatus("Couldn't find the text of the next chapter. Open it and press play.", true);
                    if (svc != null) svc.pause();
                }
                renderNow();
            });
        }
        /** For the start page: the last chapter, to carry on. */
        @JavascriptInterface public String home() {
            try {
                return new JSONObject().put("lastUrl", prefs.getString("lastUrl", ""))
                    .put("lastTitle", prefs.getString("lastTitle", "")).put("lastFiction", prefs.getString("lastFiction", ""))
                    .put("lastPct", prefs.getInt("lastPct", 0)).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void resume(String url) { main.post(() -> { resumeOnLoad = url; web.loadUrl(url); }); }
    }
    private String resumeOnLoad = null;

    private void gotChapter(Chapter ch) {
        pageChapter = ch;
        if (awaitingTurn != null) {
            // the page we opened because the last chapter ended
            awaitingTurn = null;
            boolean go = resumeAfterTurn && !(svc != null && svc.pendingPauseAfterTurn);
            if (svc != null) { svc.pendingPauseAfterTurn = false; svc.setChapter(ch, 0, go); }
            if (!go) onStatus("Stopped at the end of the chapter. The next one is ready.", false);
            return;
        }
        if (resumeOnLoad != null && ch.url.equals(resumeOnLoad)) {
            resumeOnLoad = null;
            if (svc != null) svc.setChapter(ch, svc.savedPos(ch.url), true);
            return;
        }
        offer(ch);
    }

    /** A chapter opened by the reader. Take it over unless something else is playing. */
    private void offer(Chapter ch) {
        if (svc == null) return;
        Chapter cur = svc.chapter();
        if (cur != null && cur.url.equals(ch.url)) { onSpeaking(cur, svc.pos()); return; }
        if (svc.isPlaying() && !svc.isPaused()) { readThisBtn.setVisibility(View.VISIBLE); return; }
        int at = svc.savedPos(ch.url);
        svc.setChapter(ch, at > 0 && at < ch.size() - 1 ? at : 0, false);
        if (at > 0) onStatus("Picking up where you left off.", false);
    }

    // ---------------------------------------------------------------- ReaderService.Listener
    @Override public void onState(boolean playing, boolean busy) {
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playBtn.setAlpha(busy ? 0.6f : 1f);
    }

    @Override public void onSpeaking(Chapter ch, int pos) {
        storyText.setText(ch.fiction);
        chapterText.setText(ch.title);
        if (pos >= 0 && pos < ch.size()) nowText.setText(ch.chunks.get(pos).text);
        String cur = web.getUrl();
        if (cur != null && ch.url.equals(cur) && pos < ch.size()) {
            Chapter.Chunk c = ch.chunks.get(pos);
            if (c.block >= 0) web.evaluateJavascript("window.LB && LB.highlight(" + c.block + "," + JSONObject.quote(c.text) + "," + ch.offsetInBlock(pos) + ")", null);
            else web.evaluateJavascript("window.LB && LB.clear()", null);
        }
        if (svc != null) statusText.setTag(ch.minutesLeft(pos, svc.speed()) + " min left");
        renderNow();
    }

    @Override public void onStatus(String text, boolean error) {
        statusText.setText(TextUtils.isEmpty(text) ? (statusText.getTag() != null ? statusText.getTag().toString() : "") : text);
        statusText.setTextColor(error ? C_RED : C_DIM);
    }

    // Gemini records a chapter in one request, which takes a while: the next chapter is opened
    // quietly in a hidden browser near the end of this one, so its recording is ready in time.
    private WebView pre;
    @Override public void onPrefetch(String url) {
        if (pre == null) {
            pre = new WebView(this);
            WebSettings ps = pre.getSettings();
            ps.setJavaScriptEnabled(true);
            ps.setDomStorageEnabled(true);
            pre.addJavascriptInterface(new PreBridge(), "LoudbookNative");
            pre.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String u) { if (u != null && u.startsWith("http")) inject(pre, u); }
            });
            pre.setAlpha(0f);
            ((ViewGroup) web.getParent()).addView(pre, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        pre.loadUrl(url);
    }
    final class PreBridge {
        @JavascriptInterface public void onChapter(String json) {
            main.post(() -> {
                try { Chapter c = new Chapter(new JSONObject(json)); if (svc != null) svc.cloudPrefetch(c); }
                catch (Exception e) { Log.w(TAG, "prefetch", e); }
                if (pre != null) pre.loadUrl("about:blank");
            });
        }
        @JavascriptInterface public void onNoChapter(String json) { }
        @JavascriptInterface public String home() { return "{}"; }
        @JavascriptInterface public void resume(String url) { }
    }

    @Override public void onNeedChapter(String url) {
        awaitingTurn = url;
        resumeAfterTurn = true;
        web.loadUrl(url);
    }

    @Override public void onFinished(Chapter ch) { web.evaluateJavascript("window.LB && LB.clear()", null); }

    private void renderNow() {
        if (svc == null || svc.chapter() == null) {
            storyText.setText("");
            chapterText.setText(pageChapter != null ? pageChapter.title : "Open a chapter to begin");
            nowText.setText(pageChapter != null ? "Press play to start reading." : "Royal Road, AO3, Wattpad, FanFiction.net and more. Or share a chapter link to Loudbook from Chrome.");
        }
    }

    // ---------------------------------------------------------------- controls
    private void play() {
        if (svc == null) return;
        askNotificationPermission();
        if (svc.isPlaying() && !svc.isPaused()) { svc.pause(); return; }
        if (svc.chapter() == null && pageChapter != null) { svc.setChapter(pageChapter, 0, true); return; }
        svc.play();
    }

    private void chapterStep(boolean next) {
        Chapter ch = svc != null ? svc.chapter() : null;
        if (ch == null) ch = pageChapter;
        if (ch == null) return;
        String url = next ? ch.nextUrl : ch.prevUrl;
        if (url == null) { onStatus(next ? "No next chapter yet." : "No previous chapter.", false); return; }
        boolean was = svc != null && svc.isPlaying() && !svc.isPaused();
        if (svc != null) svc.pause();
        awaitingTurn = url;
        resumeAfterTurn = was;
        web.loadUrl(url);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (web.canGoBack()) { web.goBack(); return true; }
            moveTaskToBack(true);           // keep reading in the background instead of closing
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- emulator checks (CI only)
    private void runSelfTest() {
        String t = getIntent() != null ? getIntent().getStringExtra("selftest") : null;
        if (t == null || svc == null) return;
        getIntent().removeExtra("selftest");
        if (t.contains("commands")) new Thread(() -> {
            SelfTest.commands(getApplicationContext(), svc.commands);
            if (t.contains("teach")) main.post(() -> { SelfTest.step("teach"); prefs.edit().putBoolean("voiceCmds", true).apply(); setUpCommands(true, () -> SelfTest.step("DONE teach")); });
        }, "lb-selftest").start();
    }

    // ---------------------------------------------------------------- PC voice pairing
    /** Finds the PC on the home network, then pairs with the one-time code it shows. */
    private void pairPc(Runnable refresh) {
        LinearLayout box = vbox(dp(20), dp(8));
        TextView how = label("On the PC, run \u201cPair a phone.bat\u201d (or \u201cSet up PC voice.bat\u201d the first time). Enter the code it shows.");
        box.addView(how);
        box.addView(label("PC address"));
        EditText host = new EditText(this);
        host.setSingleLine(true); host.setTextColor(C_INK); host.setHint("looking\u2026");
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(prefs.getString("pcHost", ""));
        box.addView(host);
        box.addView(label("Code"));
        EditText code = new EditText(this);
        code.setSingleLine(true); code.setTextColor(C_INK); code.setHint("XXXX-XXXX-XXXX"); code.setTypeface(Typeface.MONOSPACE);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(code);
        TextView msg = label("");
        box.addView(msg);
        final String[] foundName = {""};
        new Thread(() -> {
            java.util.List<PcLink.Found> f = PcLink.discover(1500);
            main.post(() -> {
                if (!f.isEmpty()) { if (host.getText().length() == 0 || !f.get(0).host.equals(host.getText().toString())) host.setText(f.get(0).host); foundName[0] = f.get(0).name; msg.setText("Found " + f.get(0).name + "."); }
                else if (host.getText().length() == 0) msg.setText("Couldn't find the PC by itself. Type the address \u201cPair a phone\u201d shows.");
            });
        }, "lb-find-pc").start();
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pair with my PC").setView(box)
            .setPositiveButton("Pair", null).setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String h = host.getText().toString().trim(), c = code.getText().toString().trim();
            if (h.isEmpty() || c.replaceAll("[^A-Za-z0-9]", "").length() < 12) { msg.setText("Enter the PC address and the 12-character code."); return; }
            int port = PcLink.PORT;
            if (h.matches(".*:\\d+$") && h.indexOf(':') == h.lastIndexOf(':')) { port = Integer.parseInt(h.substring(h.indexOf(':') + 1)); h = h.substring(0, h.indexOf(':')); }
            final String fh = h; final int fport = port;
            msg.setText("Pairing\u2026");
            v.setEnabled(false);
            new Thread(() -> {
                try {
                    PcLink l = PcLink.pair(fh, fport, c, Build.MANUFACTURER + " " + Build.MODEL);
                    String name;
                    try { name = foundName[0].isEmpty() ? fh : foundName[0]; } catch (Exception e) { name = fh; }
                    prefs.edit().putString("pcHost", l.host).putInt("pcPort", l.port).putString("pcFp", l.fp).putString("pcToken", l.token)
                        .putString("pcName", name).putBoolean("pcOn", true).apply();
                    main.post(() -> {
                        dlg.dismiss();
                        if (svc != null) svc.pcChanged();
                        onStatus("Paired with your PC. It does the reading when it's on and you're home.", false);
                        main.postDelayed(refresh, 3500);
                        refresh.run();
                    });
                } catch (Exception e) {
                    main.post(() -> { msg.setText("Couldn't pair: " + e.getMessage()); v.setEnabled(true); });
                }
            }, "lb-pair").start();
        });
    }

    // ---------------------------------------------------------------- voice commands setup
    private Runnable afterMicPermission;
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 42) {
            Runnable r = afterMicPermission; afterMicPermission = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { if (r != null) r.run(); }
            else { prefs.edit().putBoolean("voiceCmds", false).apply(); onStatus("Voice commands need the microphone.", true); }
        }
    }

    /** Microphone permission, then the models (about 40 MB, once), then your voice. */
    private void setUpCommands(boolean reteach, Runnable refresh) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            afterMicPermission = () -> setUpCommands(reteach, refresh);
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42);
            return;
        }
        if (!VoiceCommands.downloaded(this) || !prefs.getBoolean("cmdChecked", false)) {
            prefs.edit().putBoolean("cmdChecked", true).apply();
            onStatus("Getting voice commands ready (about 55 MB, once)…", false);
            new Thread(() -> {
                try {
                    VoiceCommands.fetch(getApplicationContext(), (d, t) -> main.post(() ->
                        onStatus("Getting voice commands ready: " + (100 * d / Math.max(1, t)) + "%", false)));
                    main.post(() -> { onStatus("", false); setUpCommands(reteach, refresh); });
                } catch (Exception e) {
                    main.post(() -> onStatus("Couldn't download voice commands: " + e.getMessage(), true));
                }
            }, "lb-cmd-download").start();
            return;
        }
        if (reteach || VoiceCommands.profile(this) == null) teachVoice(refresh);
        else { if (svc != null) svc.updateListening(); if (refresh != null) refresh.run(); }
    }

    /** Records each command word twice to learn what your voice sounds like. */
    private void teachVoice(Runnable refresh) {
        if (svc == null) return;
        if (svc.isPlaying() && !svc.isPaused()) svc.pause();
        svc.holdListening(true);
        LinearLayout box = vbox(dp(20), dp(12));
        TextView big = text(30, C_AMBER, true);
        big.setGravity(Gravity.CENTER);
        TextView small = text(14, C_DIM, false);
        small.setGravity(Gravity.CENTER);
        small.setText("Say each word when it appears, in your normal voice.");
        box.addView(big); box.addView(small);
        final boolean[] cancelled = {false};
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Set up my voice").setView(box)
            .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true).setCancelable(false).show();
        new Thread(() -> {
            String done;
            try {
                svc.commands.load(getApplicationContext());
                Thread.sleep(600);
                java.util.List<float[]> embs = new java.util.ArrayList<>();
                int spotted = 0, asked = 0;
                for (int round = 0; round < 2 && !cancelled[0]; round++) {
                    for (String w : VoiceCommands.WORDS) {
                        if (cancelled[0]) break;
                        float[] part = null;
                        for (int tries = 0; tries < 3 && part == null && !cancelled[0]; tries++) {
                            final String say = Character.toUpperCase(w.charAt(0)) + w.substring(1);
                            final int t = tries;
                            main.post(() -> { big.setText("\u201c" + say + "\u201d"); small.setText(t == 0 ? "Say it now" : "Didn't catch that. Once more."); });
                            Thread.sleep(250);
                            float[] rec = VoiceCommands.record(1.8);
                            part = VoiceCommands.speechPart(rec);
                            if (part != null) {
                                asked++;
                                if (w.equals(svc.commands.spot(rec))) spotted++;
                                embs.add(svc.commands.embed(part));
                            }
                        }
                        main.post(() -> big.setText(""));
                        Thread.sleep(350);
                    }
                }
                if (cancelled[0]) done = null;
                else if (embs.size() < 6) done = "Couldn't hear you clearly enough. Try again somewhere quieter, closer to the phone.";
                else {
                    float[] thr = new float[1];
                    float[] prof = VoiceCommands.buildProfile(embs, thr);
                    VoiceCommands.saveProfile(getApplicationContext(), prof, thr[0]);
                    done = "Done. Loudbook knows your voice. It recognised " + spotted + " of " + asked + " words"
                        + (spotted < asked * 2 / 3 ? ", so speak clearly and not too fast when giving commands." : ".");
                }
            } catch (Throwable t) {
                Log.e(TAG, "voice setup", t);
                done = "Voice setup failed: " + t.getMessage();
            }
            final String msg = done;
            Log.i("LoudbookTest", "voice setup finished: " + msg);
            main.post(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) { }
                if (svc != null) svc.holdListening(false);
                if (msg != null) onStatus(msg, msg.startsWith("Voice setup failed") || msg.startsWith("Couldn't"));
                if (refresh != null) refresh.run();
            });
        }, "lb-voice-setup").start();
    }

    // ---------------------------------------------------------------- settings
    private Runnable settingsSave;
    private void showSettings() {
        LinearLayout box = vbox(dp(20), dp(8));
        box.addView(label("Voice"));
        Spinner voices = new Spinner(this);
        String[] names = new String[Voice.VOICES.length];
        int sel = 0;
        for (int i = 0; i < names.length; i++) { names[i] = Voice.VOICES[i][1]; if (svc != null && Integer.parseInt(Voice.VOICES[i][0]) == svc.voiceId()) sel = i; }
        voices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        voices.setSelection(sel);
        box.addView(voices);
        Button hear = smallButton("Hear this voice");
        hear.setOnClickListener(v -> { if (svc != null) { svc.setVoice(Integer.parseInt(Voice.VOICES[voices.getSelectedItemPosition()][0])); svc.preview(); } });
        box.addView(hear);

        TextView speedLabel = label("Speed " + fmtSpeed(svc != null ? svc.speed() : 1f));
        box.addView(speedLabel);
        SeekBar speed = new SeekBar(this);
        speed.setMax(22);                                         // 0.7 .. 1.8 in 0.05 steps
        speed.setProgress(Math.round(((svc != null ? svc.speed() : 1f) - 0.7f) / 0.05f));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                float s = 0.7f + p * 0.05f; speedLabel.setText("Speed " + fmtSpeed(s));
                if (user && svc != null) { svc.setSpeed(s); speedBtn.setText(fmtSpeed(s)); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        box.addView(speed);

        box.addView(label("Pause between sentences"));
        Spinner gaps = new Spinner(this);
        String[] gl = {"Short", "Normal", "Long", "Very long"};
        float[] gv = {0.5f, 1f, 1.5f, 2f};
        gaps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gl));
        int gsel = 1;
        for (int i = 0; i < gv.length; i++) if (svc != null && Math.abs(svc.pauseScale - gv[i]) < 0.01f) gsel = i;
        gaps.setSelection(gsel);
        gaps.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int i, long id) { if (svc != null) svc.setPauseScale(gv[i]); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        box.addView(gaps);

        Switch auto = toggle("Roll into the next chapter", svc == null || svc.autoNext, (b, on) -> { if (svc != null) svc.setAutoNext(on); });
        box.addView(auto);

        box.addView(label("Sleep timer"));
        Spinner sleep = new Spinner(this);
        String[] sl = {"Off", "End of this chapter", "15 minutes", "30 minutes", "45 minutes", "1 hour"};
        int[] mins = {0, 0, 15, 30, 45, 60};
        sleep.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sl));
        sleep.setSelection(svc != null && svc.stopAtChapterEnd ? 1 : 0);
        box.addView(sleep);

        box.addView(label("Pronunciation fixes — one per line, e.g.  Tavi = Tah-vee"));
        EditText fixes = new EditText(this);
        fixes.setText(prefs.getString("fixes", ""));
        fixes.setMinLines(3);
        fixes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fixes.setTextColor(C_INK); fixes.setTextSize(14); fixes.setTypeface(Typeface.MONOSPACE);
        box.addView(fixes);
        Switch tidy = toggle("Tidy up web-fiction quirks (“Noooo”, “!!!!”, 120/150)", prefs.getBoolean("tidy", true), null);
        box.addView(tidy);

        TextView info = label("");
        if (svc != null) {
            double rt = svc.realtime();
            info.setText("Kokoro on this phone: " + svc.threads() + " processor threads" + (rt > 0 ? String.format(java.util.Locale.US, ", making speech %.1f× faster than it's spoken", rt) : "") + ".");
        }
        box.addView(info);

        box.addView(label("Kindle"));
        TextView kInfo = label("");
        box.addView(kInfo);
        Button kOn = smallButton("Turn on Kindle reading");
        box.addView(kOn);
        box.addView(toggle("Dim the screen while reading Kindle (it has to stay on to turn pages)",
            prefs.getBoolean("kindleDim", true), (b, on) -> prefs.edit().putBoolean("kindleDim", on).apply()));
        Button kDump = smallButton("Show what Loudbook sees in Kindle (to send if something's off)");
        box.addView(kDump);
        Runnable showK = () -> {
            boolean on = KindleService.enabled(this);
            kInfo.setText(on ? "On. Open a book in the Kindle app and tap the round Loudbook button that appears. Loudbook reads with your voice settings and turns the pages; the button pauses and resumes. "
                + "It works with books whose publishers let screen readers read them (most do). Gemini isn't used for Kindle, to save its requests."
                : "Loudbook can read the Kindle app aloud the way a screen reader does: it reads the page text Kindle shares with screen readers and turns the pages. "
                + "To turn it on: tap the button below, find Loudbook in the list, and switch it on. If Android says it's a restricted setting: go to Settings \u203a Apps \u203a Loudbook, tap \u22ee (top right), \u201cAllow restricted settings\u201d, then try again.");
            kOn.setVisibility(on ? View.GONE : View.VISIBLE);
            kDump.setVisibility(on ? View.VISIBLE : View.GONE);
        };
        showK.run();
        kOn.setOnClickListener(v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { onStatus("Open Settings \u203a Accessibility \u203a Loudbook.", true); }
        });
        kDump.setOnClickListener(v -> {
            KindleService k = KindleService.me;
            if (k == null) { kInfo.setText("Kindle reading isn't running. Turn it on first."); return; }
            onStatus("Open a book in Kindle; Loudbook will look in 5 seconds\u2026", false);
            new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                String d = k.dump();
                main.post(() -> {
                    TextView t = text(11, C_INK, false);
                    t.setText(d); t.setTextIsSelectable(true); t.setTypeface(Typeface.MONOSPACE); t.setPadding(dp(12), dp(8), dp(12), dp(8));
                    ScrollView sv = new ScrollView(this); sv.addView(t);
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("What Loudbook sees in Kindle").setView(sv)
                        .setPositiveButton("Copy", (dd, w) -> ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("Loudbook Kindle", d)))
                        .setNegativeButton("Close", null).show();
                });
            }, "lb-kindle-dump").start();
        });

        box.addView(label("Best voice: Google Gemini (online)"));
        TextView gInfo = label("");
        box.addView(gInfo);
        Switch gOn = toggle("Read with Gemini", prefs.getBoolean("cloudOn", false), null);
        box.addView(gOn);
        EditText gKey = new EditText(this);
        gKey.setSingleLine(true); gKey.setTextColor(C_INK); gKey.setHint("Paste your Gemini API key");
        gKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        gKey.setText(prefs.getString("cloudKey", ""));
        box.addView(gKey);
        Spinner gVoice = new Spinner(this);
        String[] gvn = new String[CloudVoice.VOICES.length];
        int gsel2 = 0;
        for (int i = 0; i < gvn.length; i++) { gvn[i] = CloudVoice.VOICES[i][0] + " \u2014 " + CloudVoice.VOICES[i][1]; if (CloudVoice.VOICES[i][0].equals(prefs.getString("cloudVoice", "Sulafat"))) gsel2 = i; }
        gVoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gvn));
        gVoice.setSelection(gsel2);
        box.addView(gVoice);
        Spinner gModel = new Spinner(this);
        String[] gml = {"Gemini Flash TTS (best)", "Gemini Flash-Lite TTS (cheaper, faster)"};
        gModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gml));
        gModel.setSelection(prefs.getString("cloudModel", "gemini-3.8-flash-tts").contains("lite") ? 1 : 0);
        box.addView(gModel);
        EditText gStyle = new EditText(this);
        gStyle.setTextColor(C_INK); gStyle.setTextSize(13); gStyle.setMinLines(2);
        gStyle.setText(prefs.getString("cloudStyle", CloudVoice.DEFAULT_STYLE));
        box.addView(gStyle);
        Button gHear = smallButton("Save and hear it (uses a request)");
        box.addView(gHear);
        box.addView(toggle("Record the next chapter ahead of time (uses a request; no wait at the page turn)",
            prefs.getBoolean("gemPrefetch", false), (b, on) -> prefs.edit().putBoolean("gemPrefetch", on).apply()));
        box.addView(toggle("Going back to a part Gemini didn't record: record it too (uses a request; otherwise the regular voice reads it)",
            prefs.getBoolean("gemBackfill", false), (b, on) -> prefs.edit().putBoolean("gemBackfill", on).apply()));
        box.addView(toggle("Exact sentence timing (the phone listens to each recording once to find where every sentence starts)",
            prefs.getBoolean("gemExact", true), (b, on) -> prefs.edit().putBoolean("gemExact", on).apply()));
        Runnable showG = () -> {
            String err = svc != null ? svc.cloudError : "";
            int used = svc != null ? svc.cloudRequestsToday() : 0;
            long retry = prefs.getLong("gemRetryAt", 0);
            String wait = retry > System.currentTimeMillis() ? " Google's limit was reached; more requests from " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(retry)) + "." : "";
            gInfo.setText("Requests used today: " + used + "." + wait + " Each chapter is read with one request (two if Google stops a long one early), and recordings are kept, so going back or hearing a chapter again is free.\n"
                + "Google's Gemini voice is one of the best-rated anywhere. It needs a free API key from aistudio.google.com (Get API key). "
                + "The free tier has daily limits, and Google may use what's sent to improve its products; past that, Google charges about $0.81 per hour of listening. "
                + "Whenever Gemini can't be reached or the limit is hit, your PC or the phone reads instead."
                + (err.isEmpty() ? "" : "\nLast problem: " + err));
        };
        showG.run();
        Runnable saveG = () -> {
            String m = gModel.getSelectedItemPosition() == 1 ? "gemini-3.8-flash-lite-tts" : "gemini-3.8-flash-tts";
            String st = gStyle.getText().toString().trim();
            boolean changed = !gKey.getText().toString().trim().equals(prefs.getString("cloudKey", "")) || gOn.isChecked() != prefs.getBoolean("cloudOn", false)
                || !CloudVoice.VOICES[gVoice.getSelectedItemPosition()][0].equals(prefs.getString("cloudVoice", "Sulafat"))
                || !m.equals(prefs.getString("cloudModel", "gemini-3.8-flash-tts")) || !st.equals(prefs.getString("cloudStyle", CloudVoice.DEFAULT_STYLE));
            prefs.edit().putString("cloudKey", gKey.getText().toString().trim()).putBoolean("cloudOn", gOn.isChecked())
                .putString("cloudVoice", CloudVoice.VOICES[gVoice.getSelectedItemPosition()][0]).putString("cloudModel", m)
                .putString("cloudStyle", st.isEmpty() ? CloudVoice.DEFAULT_STYLE : st).apply();
            if (changed && svc != null) svc.cloudChanged();
        };
        gOn.setOnCheckedChangeListener((b, on) -> saveG.run());
        gHear.setOnClickListener(v -> {
            saveG.run();
            if (gKey.getText().toString().trim().isEmpty()) { gInfo.setText("Paste your Gemini API key first."); return; }
            if (!gOn.isChecked()) gOn.setChecked(true);
            if (svc != null) { svc.preview(); main.postDelayed(showG, 6000); }
        });
        settingsSave = saveG;

        box.addView(label("Voice on your PC (saves battery)"));
        TextView pcInfo = label("");
        box.addView(pcInfo);
        Switch pcOn = toggle("Read with my PC when it's on", prefs.getBoolean("pcOn", false), null);
        box.addView(pcOn);
        Spinner pcStyle = new Spinner(this);
        String[] psl = {"PC voice: natural (graphics card)", "PC voice: Kokoro (same as the phone)"};
        pcStyle.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, psl));
        pcStyle.setSelection("kokoro".equals(prefs.getString("pcEngine", "natural")) ? 1 : 0);
        pcStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int i, long id) {
                String e = i == 1 ? "kokoro" : "natural";
                if (!e.equals(prefs.getString("pcEngine", "natural"))) { prefs.edit().putString("pcEngine", e).apply(); if (svc != null && svc.isPlaying()) svc.seek(svc.pos()); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        box.addView(pcStyle);
        Button pcPair = smallButton(prefs.getString("pcToken", "").isEmpty() ? "Pair with my PC" : "Pair again");
        Button pcForget = smallButton("Forget my PC");
        box.addView(pcPair); box.addView(pcForget);
        Runnable showPc = () -> {
            boolean paired = !prefs.getString("pcToken", "").isEmpty();
            pcOn.setVisibility(paired ? View.VISIBLE : View.GONE);
            pcStyle.setVisibility(paired && prefs.getBoolean("pcOn", false) ? View.VISIBLE : View.GONE);
            pcForget.setVisibility(paired ? View.VISIBLE : View.GONE);
            Voice v = svc != null ? svc.voice() : null;
            PcLink.Health hh = v == null ? null : v.pcHealth;
            String nat = hh == null ? "" : hh.natural.isEmpty() ? " It has no natural voice (needs an NVIDIA graphics card), so it reads with Kokoro."
                : hh.naturalOk ? " Natural voice: " + hh.natural + (hh.naturalSpeed > 0 ? String.format(java.util.Locale.US, ", %.1f\u00d7 faster than reading.", hh.naturalSpeed) : ".")
                : " Its natural voice is too slow on that graphics card, so it reads with Kokoro.";
            String where = v == null ? "" : v.pcOk ? "Reading on your PC now." + nat : v.pcError.length() > 0 ? "Can't reach it right now (" + v.pcError + "), so the phone reads." : "";
            pcInfo.setText(!paired ? "Run \u201cSet up PC voice.bat\u201d on your PC (in the Loudbook-android folder), then pair here with the code it shows. Works on your home Wi-Fi; elsewhere the phone reads as usual."
                : "Paired with " + prefs.getString("pcName", "your PC") + " (" + prefs.getString("pcHost", "") + "). " + (prefs.getBoolean("pcOn", false) ? where : "Off: the phone reads."));
        };
        showPc.run();
        pcOn.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("pcOn", on).apply(); if (svc != null) svc.pcChanged(); main.postDelayed(showPc, 3500); showPc.run(); });
        pcPair.setOnClickListener(v -> pairPc(showPc));
        pcForget.setOnClickListener(v -> {
            prefs.edit().remove("pcToken").remove("pcFp").remove("pcHost").remove("pcName").putBoolean("pcOn", false).apply();
            if (svc != null) svc.pcChanged();
            pcPair.setText("Pair with my PC");
            showPc.run();
        });

        box.addView(label("Voice commands"));
        Switch cmds = toggle("Listen for play, pause, back, forward, beginning, end", prefs.getBoolean("voiceCmds", false), null);
        box.addView(cmds);
        TextView cmdInfo = label("");
        box.addView(cmdInfo);
        Button teach = smallButton(VoiceCommands.profile(this) == null ? "Set up my voice" : "Set up my voice again");
        box.addView(teach);
        Spinner strict = new Spinner(this);
        String[] stl = {"Voice match: relaxed", "Voice match: normal", "Voice match: strict"};
        float[] stv = {-0.08f, 0f, 0.08f};
        strict.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, stl));
        float cur = prefs.getFloat("voiceStrict", 0f);
        strict.setSelection(cur < -0.01f ? 0 : cur > 0.01f ? 2 : 1);
        strict.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int i, long id) { prefs.edit().putFloat("voiceStrict", stv[i]).apply(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        box.addView(strict);
        Runnable showCmdInfo = () -> {
            boolean on = prefs.getBoolean("voiceCmds", false);
            teach.setVisibility(on ? View.VISIBLE : View.GONE);
            strict.setVisibility(on && VoiceCommands.profile(this) != null ? View.VISIBLE : View.GONE);
            String last = svc != null && svc.lastHeard.length() > 0 ? "\nLast heard: " + svc.lastHeard : "";
            cmdInfo.setText(!on ? "Say a word to control reading while Loudbook is open or reading. Only your voice counts, so the TV, other people and the story itself are ignored. Nothing you say leaves the phone."
                : VoiceCommands.profile(this) == null ? "One more step: set up your voice (say each word twice)."
                : (svc != null && svc.commands.listening() ? "Listening." : "Ready. Listens while Loudbook is open or reading.") + last);
        };
        showCmdInfo.run();
        cmds.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("voiceCmds", on).apply();
            showCmdInfo.run();
            if (on) setUpCommands(false, showCmdInfo); else if (svc != null) svc.updateListening();
        });
        teach.setOnClickListener(v -> setUpCommands(true, showCmdInfo));

        TextView ver = label("Version " + BuildInfo.VERSION + (Updater.enabled() ? "" : " (updates off in this build)"));
        box.addView(ver);
        if (Updater.enabled()) {
            Button check = smallButton("Check for updates");
            check.setOnClickListener(v -> {
                ver.setText("Version " + BuildInfo.VERSION + " — checking…");
                Updater.check(getApplicationContext(), true, () -> main.post(() -> {
                    int code = Updater.readyCode(this);
                    String err = Updater.lastError(this);
                    ver.setText("Version " + BuildInfo.VERSION + " — " + (code > 0 ? "update " + Updater.readyName(this) + " downloaded."
                        : err.length() > 0 ? "couldn't check (" + err + ")." : "up to date."));
                    refreshUpdate();
                }));
            });
            box.addView(check);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Loudbook")
            .setView(sv)
            .setPositiveButton("Done", (d, w) -> {
                if (settingsSave != null) settingsSave.run();
                if (svc != null) {
                    svc.setVoice(Integer.parseInt(Voice.VOICES[voices.getSelectedItemPosition()][0]));
                    int k = sleep.getSelectedItemPosition();
                    svc.setSleep(mins[k], k == 1);
                }
                String f = fixes.getText().toString();
                boolean changed = !f.equals(prefs.getString("fixes", "")) || tidy.isChecked() != prefs.getBoolean("tidy", true);
                prefs.edit().putString("fixes", f).putBoolean("tidy", tidy.isChecked()).apply();
                if (changed && pageChapter != null && (svc == null || !svc.isPlaying())) inject(web.getUrl());
                else if (changed) onStatus("Pronunciation changes apply from the next chapter.", false);
            })
            .show();
    }

    // ---------------------------------------------------------------- layout (built in code)
    private View buildUi() {
        LinearLayout root = vbox(0, 0);
        root.setBackgroundColor(C_BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = hbox(dp(8), dp(6));
        top.setBackgroundColor(C_PANEL);
        View home = imageButton(R.drawable.ic_home, C_DIM, 0);
        home.setOnClickListener(v -> web.loadUrl(HOME));
        top.addView(home);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Chapter link or search Royal Road");
        address.setHintTextColor(C_DIM); address.setTextColor(C_INK); address.setTextSize(14);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        address.setBackground(round(C_BG, dp(8), C_LINE));
        address.setPadding(dp(10), dp(6), dp(10), dp(6));
        address.setOnEditorActionListener((v, a, e) -> { go(address.getText().toString()); return true; });
        top.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View menu = imageButton(R.drawable.ic_settings, C_DIM, 0);
        menu.setOnClickListener(v -> showSettings());
        top.addView(menu);
        root.addView(top);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout player = vbox(dp(14), dp(10));
        player.setBackgroundColor(C_PANEL);
        storyText = text(11, C_AMBER, true);
        storyText.setAllCaps(true);
        chapterText = text(16, C_INK, true);
        chapterText.setSingleLine(true); chapterText.setEllipsize(TextUtils.TruncateAt.END);
        nowText = text(15, C_INK, false);
        nowText.setMaxLines(3); nowText.setEllipsize(TextUtils.TruncateAt.END);
        nowText.setTypeface(Typeface.SERIF);
        nowText.setPadding(0, dp(4), 0, dp(4));
        statusText = text(12, C_DIM, false);
        updateBtn = smallButton("");
        updateBtn.setVisibility(View.GONE);
        updateBtn.setTextSize(13);
        player.addView(updateBtn);
        player.addView(storyText); player.addView(chapterText); player.addView(nowText); player.addView(statusText);

        readThisBtn = smallButton("Read this page instead");
        readThisBtn.setVisibility(View.GONE);
        readThisBtn.setOnClickListener(v -> { if (svc != null && pageChapter != null) svc.setChapter(pageChapter, 0, true); v.setVisibility(View.GONE); });
        player.addView(readThisBtn);

        LinearLayout ctl = hbox(0, dp(4));
        ctl.setGravity(Gravity.CENTER);
        View prevCh = imageButton(R.drawable.ic_prev_ch, C_INK, 0), back = imageButton(R.drawable.ic_back, C_INK, 0),
            fwd = imageButton(R.drawable.ic_fwd, C_INK, 0), nextCh = imageButton(R.drawable.ic_next_ch, C_INK, 0);
        playBtn = imageButton(R.drawable.ic_play, 0xFF1B1712, C_AMBER);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(dp(64), dp(64));
        big.setMargins(dp(10), 0, dp(10), 0);
        prevCh.setOnClickListener(v -> chapterStep(false));
        nextCh.setOnClickListener(v -> chapterStep(true));
        back.setOnClickListener(v -> { if (svc != null) svc.seek(svc.pos() - 1); });
        fwd.setOnClickListener(v -> { if (svc != null) svc.seek(svc.pos() + 1); });
        playBtn.setOnClickListener(v -> play());
        speedBtn = iconButton("1.0×");
        speedBtn.setTextSize(13);
        speedBtn.setOnClickListener(v -> {
            if (svc == null) return;
            float[] steps = {0.8f, 1f, 1.15f, 1.3f, 1.5f, 1.75f};
            float s = svc.speed(), nx = steps[0];
            for (float st : steps) if (st > s + 0.01f) { nx = st; break; }
            svc.setSpeed(nx); speedBtn.setText(fmtSpeed(nx));
        });
        ctl.addView(prevCh); ctl.addView(back); ctl.addView(playBtn, big); ctl.addView(fwd); ctl.addView(nextCh); ctl.addView(speedBtn);
        player.addView(ctl);
        root.addView(player);
        renderNow();
        return root;
    }

    // small view helpers
    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    private LinearLayout vbox(int padH, int padV) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(padH, padV, padH, padV); return l; }
    private LinearLayout hbox(int padH, int padV) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); l.setPadding(padH, padV, padH, padV); return l; }
    private TextView text(float sp, int color, boolean bold) { TextView t = new TextView(this); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private TextView label(String s) { TextView t = text(13, C_DIM, false); t.setText(s); t.setPadding(0, dp(12), 0, dp(4)); return t; }
    private TextView iconButton(String s) {
        TextView t = text(20, C_INK, false); t.setText(s); t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44)); t.setMinHeight(dp(44)); t.setPadding(dp(6), dp(4), dp(6), dp(4));
        t.setClickable(true); t.setFocusable(true);
        return t;
    }
    private android.widget.ImageView imageButton(int res, int tint, int fill) {
        android.widget.ImageView v = new android.widget.ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        v.setMinimumWidth(dp(48)); v.setMinimumHeight(dp(48));
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setClickable(true); v.setFocusable(true);
        if (fill != 0) v.setBackground(round(fill, dp(32), 0));
        return v;
    }
    private Button smallButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private Switch toggle(String s, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch w = new Switch(this); w.setText(s); w.setTextColor(C_INK); w.setChecked(on); w.setPadding(0, dp(10), 0, dp(4));
        if (l != null) w.setOnCheckedChangeListener(l);
        return w;
    }
    private static GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(2, stroke);
        return g;
    }
    private static String fmtSpeed(float s) { String t = String.format(java.util.Locale.US, "%.2f", s).replaceAll("0$", ""); return t + "×"; }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0); web.requestFocus(); }
    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
