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
    private void showLastCrash() {
        java.io.File f = new java.io.File(getFilesDir(), "last-crash.txt");
        if (!f.exists()) return;
        String text;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            text = out.toString("UTF-8");
        } catch (Exception e) { text = String.valueOf(e); }
        f.delete();
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
    @Override protected void onResume() {
        super.onResume();
        refreshUpdate();
        Updater.check(getApplicationContext(), false, () -> main.post(this::refreshUpdate));
    }

    /** Leaving the app with nothing playing is the moment to swap in a waiting update. */
    @Override protected void onStop() {
        super.onStop();
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

    private void inject(String url) {
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

    // ---------------------------------------------------------------- settings
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
