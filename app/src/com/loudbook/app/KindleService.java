package com.loudbook.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the Kindle app aloud, the way a screen reader does: it takes the page text the Kindle app
 * shares with screen readers (TalkBack reads Kindle books the same way), reads it with Loudbook's
 * voices, and turns the page when it gets near the end. A small round button floats over Kindle to
 * start and stop. It only looks at the Kindle app (see res/xml/kindle_service.xml), and reads only
 * books whose publishers let screen readers have the text; nothing is unlocked or saved.
 */
public class KindleService extends AccessibilityService {
    static final String TAG = "Loudbook";
    static final String KINDLE = "com.amazon.kindle";
    static volatile KindleService me;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::watch;
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private ReaderService svc;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { svc = ((ReaderService.LocalBinder) b).service(); }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override protected void onServiceConnected() {
        me = this;
        Log.i("LoudbookTest", "kindle service connected");
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bound = bindService(new Intent(this, ReaderService.class), conn, Context.BIND_AUTO_CREATE);
        main.post(tick);
    }

    @Override public void onDestroy() {
        me = null;
        hideHighlight(); hidePicker();
        if (exploreOn) setExplore(false);
        hideBubble();
        if (bound) { try { unbindService(conn); } catch (Exception ignored) { } bound = false; }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { main.removeCallbacks(tick); main.post(tick); }
    }
    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- the floating button
    /** Shows the button while Kindle is on screen (or while reading it), every second. */
    private void watch() {
        if (me != this) return;
        boolean kindle = kindleRoot() != null;
        kindleOnScreen = kindle;
        boolean reading = svc != null && svc.readingKindle();
        if (reading && kindle) main.post(paint); else hideHighlight();
        boolean speaking = reading && svc.isPlaying() && !svc.isPaused();
        // only while Loudbook is open (in recent apps) or reading: swipe Loudbook away and the
        // button stays out of Kindle
        if ((kindle && loudbookOpen()) || speaking) showBubble(); else hideBubble();
        // explore-by-touch only while actually reading (so the phone works normally the rest of the time)
        // (reading takes a moment to start: leave things as they are meanwhile)
        boolean settling = busy || System.currentTimeMillis() - startedAt < 6000;
        if (!reading && !settling) needExplore = false;
        boolean explore = needExplore && reading && svc.isPlaying() && !svc.isPaused();
        if (explore != exploreOn && !settling) setExplore(explore);
        if (bubble != null) {
            boolean on = reading && svc.isPlaying() && !svc.isPaused();
            bubble.setText(on ? "❚❚" : "▶");
            // while reading: keep the screen on (Kindle only turns pages on a live screen), dimmed
            boolean dim = on && Voice.prefs(this).getBoolean("kindleDim", true);
            float want = dim ? 0.02f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | (on ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0);
            if (bubbleLp.screenBrightness != want || bubbleLp.flags != flags) {
                bubbleLp.screenBrightness = want; bubbleLp.flags = flags;
                try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
            }
        }
        // keep checking only while the button is up (Kindle's own events say when it comes back)
        main.removeCallbacks(tick);
        if (bubble != null) main.postDelayed(tick, 1000);
    }

    /** Loudbook's screen is open, or in recent apps (not swiped away, and opened since the phone started). */
    private boolean loudbookOpen() {
        try {
            for (android.app.ActivityManager.AppTask t : ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getAppTasks()) {
                try { if (t.getTaskInfo() != null) return true; } catch (IllegalArgumentException gone) { }
            }
        } catch (Exception e) { return true; }
        return false;
    }

    private void showBubble() {
        if (bubble != null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = Math.round(52 * dm.density);
        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(0xFF1B1712);
        bubble.setTextSize(18);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE6E8AA46);
        bubble.setBackground(g);
        bubble.setElevation(8 * dm.density);
        bubbleLp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Voice.prefs(this).getInt("bubbleX", dm.widthPixels - size - Math.round(12 * dm.density));
        bubbleLp.y = Voice.prefs(this).getInt("bubbleY", dm.heightPixels * 2 / 3);
        final float[] down = new float[4];
        longPressed[0] = false;
        final boolean[] moved = {false};
        // while the screen works like a screen reader's, a tap arrives as a short hover
        final long[] hoverAt = {0};
        bubble.setOnHoverListener((v, ev) -> {
            if (!exploreOn) return false;
            if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) hoverAt[0] = ev.getEventTime();
            else if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && ev.getEventTime() - hoverAt[0] < 700) tapped();
            return true;
        });
        bubble.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX(); down[1] = ev.getRawY(); down[2] = bubbleLp.x; down[3] = bubbleLp.y; moved[0] = false;
                    longPressed[0] = false;
                    main.postDelayed(longPress, 700);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (Math.abs(dx) + Math.abs(dy) > 12 * dm.density) { moved[0] = true; main.removeCallbacks(longPress); }
                    if (moved[0]) { bubbleLp.x = (int) (down[2] + dx); bubbleLp.y = (int) (down[3] + dy); wm.updateViewLayout(bubble, bubbleLp); }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (longPressed[0]) return true;
                    if (moved[0]) Voice.prefs(this).edit().putInt("bubbleX", bubbleLp.x).putInt("bubbleY", bubbleLp.y).apply();
                    else tapped();
                    return true;
            }
            return false;
        });
        try { wm.addView(bubble, bubbleLp); Log.i("LoudbookTest", "kindle button shown at " + bubbleLp.x + "," + bubbleLp.y); }
        catch (Exception e) { Log.w(TAG, "kindle button", e); bubble = null; }
    }

    private final boolean[] longPressed = {false};
    /** Holding the button: tap the page to choose where reading starts. */
    private final Runnable longPress = () -> { longPressed[0] = true; showPicker(); };

    // ---------------------------------------------------------------- the highlight on Kindle's page
    private volatile boolean kindleOnScreen;
    private HighlightView hl;
    private volatile int spansFor = -1;
    private int lastLit = -1;
    private List<int[]> spans;
    private final Runnable paint = this::paintHighlight;

    /** Marks the sentence being read on Kindle's page, and the word in it, as the reading goes. */
    private void paintHighlight() {
        main.removeCallbacks(paint);
        if (me != this) return;
        boolean reading = svc != null && svc.readingKindle() && svc.isPlaying();
        if (!reading || !kindleOnScreen) { hideHighlight(); return; }
        KindleLayout lay = shown;
        float[] now = svc.kindleNow();
        Chapter ch = svc.chapter();
        if (lay == null || now == null || ch == null) { main.postDelayed(paint, 200); return; }
        int idx = (int) now[0], from = (int) now[2], to = Math.min((int) now[3], ch.size());
        int key = from * 100_000 + to;
        if (spansFor != key || spans == null) {
            List<String> texts = new ArrayList<>();
            for (int i = from; i < to; i++) texts.add(ch.chunks.get(i).text);
            spans = lay.spans(texts);
            spansFor = key;
        }
        List<float[]> sentence = new ArrayList<>();
        float[] word = null;
        int k = idx - from;
        if (k >= 0 && k < spans.size() && spans.get(k) != null) {
            int[] sp = spans.get(k);
            sentence = lay.boxesFor(sp[0], sp[1]);
            if (!svc.isPaused()) word = lay.wordAt(sp[0], sp[1], now[1]);
        }
        if (idx != lastLit) {
            lastLit = idx;
            Log.i("LoudbookTest", "kindle highlight: sentence " + idx + " on " + sentence.size() + " lines"
                + (sentence.isEmpty() ? "" : " from y=" + Math.round(sentence.get(0)[1])) + (lay.exactLines > 0 ? " (exact)" : " (estimated)"));
        }
        showHighlight(sentence, word);
        main.postDelayed(paint, 80);
    }

    private void showHighlight(List<float[]> sentence, float[] word) {
        if (hl == null) {
            hl = new HighlightView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            try { wm.addView(hl, lp); } catch (Exception e) { Log.w(TAG, "highlight", e); hl = null; return; }
            // the button stays above the highlight
            if (bubble != null) { try { wm.removeView(bubble); wm.addView(bubble, bubbleLp); } catch (Exception ignored) { } }
        }
        hl.set(sentence, word);
    }

    private void hideHighlight() {
        main.removeCallbacks(paint);
        if (hl != null) { try { wm.removeView(hl); } catch (Exception ignored) { } hl = null; }
    }

    /** Draws the marks: the sentence lightly, the word being said more strongly. */
    static final class HighlightView extends View {
        private final android.graphics.Paint soft = new android.graphics.Paint(), strong = new android.graphics.Paint();
        private List<float[]> sentence = new ArrayList<>();
        private float[] word;
        private final int[] at = new int[2];
        private final float radius;
        HighlightView(Context c) {
            super(c);
            soft.setColor(0x38E8AA46); strong.setColor(0x80E8AA46);
            radius = 4 * c.getResources().getDisplayMetrics().density;
        }
        void set(List<float[]> s, float[] w) {
            sentence = s; word = w;
            invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            getLocationOnScreen(at);
            for (float[] r : sentence) c.drawRoundRect(r[0] - at[0] - 2, r[1] - at[1], r[2] - at[0] + 2, r[3] - at[1], radius, radius, soft);
            if (word != null) c.drawRoundRect(word[0] - at[0] - 3, word[1] - at[1], word[2] - at[0] + 3, word[3] - at[1], radius, radius, strong);
        }
    }

    // ---------------------------------------------------------------- choosing where to start
    private View picker;
    private void showPicker() {
        if (picker != null || wm == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setBackgroundColor(0x33000000);
        TextView hint = new TextView(this);
        hint.setText("Tap where Loudbook should start reading");
        hint.setTextColor(0xFF1B1712); hint.setTextSize(16); hint.setGravity(Gravity.CENTER);
        int pad = Math.round(10 * dm.density);
        hint.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xF2E8AA46); g.setCornerRadius(24 * dm.density);
        hint.setBackground(g);
        android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = Math.round(40 * dm.density);
        f.addView(hint, hp);
        TextView copyIt = new TextView(this);
        copyIt.setText("Something read wrong? Copy what Loudbook sees");
        copyIt.setTextColor(0xFFEDE6DA); copyIt.setTextSize(13); copyIt.setPadding(pad, pad, pad, pad);
        copyIt.setBackgroundColor(0xCC1E1B17);
        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = Math.round(40 * dm.density);
        f.addView(copyIt, cp);
        copyIt.setOnClickListener(v -> {
            hidePicker();
            new Thread(() -> {
                String d = report(readPage());
                main.post(() -> { copy(d); toast("Copied what Loudbook sees in Kindle. Paste it to Claude to get the reading fixed."); });
            }, "lb-kindle-copy").start();
        });
        f.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() != MotionEvent.ACTION_UP) return true;
            final float x = ev.getRawX(), y = ev.getRawY();
            hidePicker();
            startFrom(x, y);
            return true;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        try { wm.addView(f, lp); picker = f; Log.i("LoudbookTest", "kindle: choose where to start"); } catch (Exception e) { Log.w(TAG, "picker", e); }
        main.postDelayed(this::hidePicker, 20_000);                  // (tapped nothing: put it away)
    }
    private void hidePicker() { if (picker != null) { try { wm.removeView(picker); } catch (Exception ignored) { } picker = null; } }

    /** Starts reading from the sentence at a point on the page. */
    private void startFrom(float x, float y) {
        if (svc == null) return;
        new Thread(() -> {
            Page p = readPage();
            if (p.paragraphs.isEmpty()) { main.post(() -> toast("No book text found there.")); return; }
            KindleLayout lay = p.layout(false);
            int letter = lay.letterAt(x, y);
            Log.i("LoudbookTest", "kindle: start at letter " + letter + " (tap " + Math.round(x) + "," + Math.round(y) + ")");
            main.post(() -> {
                accept(p);
                svc.startKindle(p, letter);
                watch();
            });
        }, "lb-kindle-from").start();
    }

    private void copy(String d) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Loudbook Kindle", d));
        } catch (Exception ignored) { }
    }

    /** What Loudbook sees and what it made of it, also kept for Settings › "Show what Loudbook sees in Kindle". */
    String report(Page p) {
        String d = "Loudbook " + BuildInfo.VERSION + " Kindle view, Android " + android.os.Build.VERSION.SDK_INT
            + (exploreOn ? ", explore-by-touch on" : "") + "\n" + dump();
        try { java.nio.file.Files.write(new java.io.File(getFilesDir(), "kindle-last.txt").toPath(), d.getBytes("UTF-8")); } catch (Exception ignored) { }
        return d;
    }
    static String lastReport(Context c) {
        try { return new String(java.nio.file.Files.readAllBytes(new java.io.File(c.getFilesDir(), "kindle-last.txt").toPath()), "UTF-8"); }
        catch (Exception e) { return null; }
    }

    private void hideBubble() {
        if (bubble == null) return;
        Log.i("LoudbookTest", "kindle button hidden");
        try { wm.removeView(bubble); } catch (Exception ignored) { }
        bubble = null;
    }

    private void tapped() {
        Log.i("LoudbookTest", "kindle button tapped (service " + (svc != null) + ")");
        if (svc == null) {
            // the reading service isn't connected yet (Loudbook was just started): connect, then go
            if (!bound) bound = bindService(new Intent(this, ReaderService.class), conn, Context.BIND_AUTO_CREATE);
            toast("Loudbook is starting…");
            main.postDelayed(() -> { if (svc != null) tapped(); else toast("Loudbook couldn't start. Open the Loudbook app once, then try again."); }, 1500);
            return;
        }
        if (svc.readingKindle() && !svc.kindleDone()) {
            if (svc.isPlaying() && !svc.isPaused()) svc.pause();
            else { if (needExplore) setExplore(true); svc.play(); }
            watch();
            return;
        }
        if (busy) return;
        busy = true;
        new Thread(() -> {
            Page p = readPage();
            boolean explored = false;
            if (p.paragraphs.isEmpty() && kindleRoot() != null) {
                // Kindle may share a book's text only with a screen reader that explores by touch
                // (as TalkBack does): turn that on and look again
                Log.i("LoudbookTest", "no text; trying explore-by-touch");
                main.post(() -> setExplore(true));
                for (int i = 0; i < 16 && p.paragraphs.isEmpty(); i++) { pause(300); p = readPage(); }
                explored = !p.paragraphs.isEmpty();
                if (!explored) main.post(() -> setExplore(false));
            }
            final Page got = p;
            final boolean ex = explored;
            final String why = report(got);
            main.post(() -> {
                busy = false;
                if (got.paragraphs.isEmpty()) {
                    // what Loudbook sees, ready to paste, so the reader can be fixed for this Kindle
                    copy(why);
                    Log.i("LoudbookTest", "no book text found");
                    toast("No book text found in Kindle. What Loudbook sees there is copied: paste it to Claude to get it fixed. (Or the book's publisher doesn't allow screen readers.)");
                    return;
                }
                needExplore = ex;
                startedAt = System.currentTimeMillis();
                if (ex) toast("Kindle shares this book's text only with screen readers, so while Loudbook reads, the screen works like one. Tap the gold button to pause and get your screen back.");
                accept(got);
                svc.startKindle(got, 0);
                watch();
            });
        }, "lb-kindle").start();
    }
    private volatile boolean busy;
    private volatile long startedAt;
    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ---------------------------------------------------------------- explore by touch
    private volatile boolean exploreOn, needExplore;
    /** Asks Android for screen-reader touch (taps explore; Kindle then shares the page text). */
    private void setExplore(boolean on) {
        try {
            AccessibilityServiceInfo i = getServiceInfo();
            if (i == null) return;
            if (on) i.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            else i.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            setServiceInfo(i);
            exploreOn = on;
            Log.i("LoudbookTest", "explore by touch " + (on ? "on" : "off"));
        } catch (Exception e) { Log.w(TAG, "explore by touch", e); }
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    // ---------------------------------------------------------------- the page
    static final class Page {
        final List<String> paragraphs = new ArrayList<>();
        String title = "";
        String signature = "";
        List<Bit> edges = new ArrayList<>();
        List<Bit> lines = new ArrayList<>();          // the book's lines, in reading order
        KindleLayout layout(boolean exact) { return KindleService.layoutOf(lines, exact); }
    }

    private AccessibilityNodeInfo kindleRoot() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && KINDLE.contentEquals(r.getPackageName())
                    && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) return r;
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && KINDLE.contentEquals(r.getPackageName())) return r;
        } catch (Exception ignored) { }
        return null;
    }

    private volatile List<Bit> lastEdges = new ArrayList<>();
    /** This page is the one being read now: its header and footer are known from here on. */
    void accept(Page p) {
        lastEdges = p.edges;
        // where this page's lines are, for the highlight (exact character boxes take a moment: off the main thread)
        new Thread(() -> {
            KindleLayout k = p.layout(true);
            shown = k; shownPage = p; spansFor = -1;
            if (k.exactLines < p.lines.size()) main.post(() -> fromScreen(p));
        }, "lb-kindle-layout").start();
    }
    private volatile Page shownPage;

    /**
     * Kindle didn't say where its words are: find them on a picture of the screen instead (each
     * row of text is a band of ink, each word a run of ink). Only shapes are used, nothing is kept.
     */
    private void fromScreen(Page p) { fromScreen(p, 0); }
    private void fromScreen(Page p, int tries) {
        if (android.os.Build.VERSION.SDK_INT < 30 || p != shownPage) return;
        if (hl != null) hl.setVisibility(View.INVISIBLE);           // (not our own marks)
        main.postDelayed(() -> {
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        android.graphics.Bitmap shot = null;
                        try {
                            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            if (hw != null) shot = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        } catch (Throwable t) { Log.w(TAG, "kindle screen", t); }
                        finally { try { r.getHardwareBuffer().close(); } catch (Throwable ignored) { } }
                        if (hl != null) hl.setVisibility(View.VISIBLE);
                        final android.graphics.Bitmap pic = shot;
                        final Rect button = bubble == null ? null : new Rect(bubbleLp.x, bubbleLp.y, bubbleLp.x + bubbleLp.width, bubbleLp.y + bubbleLp.height);
                        if (pic != null) new Thread(() -> placeFromPicture(p, pic, button, tries), "lb-kindle-ink").start();
                    }
                    @Override public void onFailure(int code) {
                        if (hl != null) hl.setVisibility(View.VISIBLE);
                        Log.i("LoudbookTest", "kindle: no picture of the screen (" + code + "), word places estimated");
                        if (tries < 2) main.postDelayed(() -> fromScreen(p, tries + 1), 1200);   // (pictures are allowed once a second)
                    }
                });
            } catch (Throwable t) { if (hl != null) hl.setVisibility(View.VISIBLE); Log.w(TAG, "kindle screen", t); }
        }, 120);
    }

    private void placeFromPicture(Page p, android.graphics.Bitmap pic, Rect button, int tries) {
        boolean again = false;
        try {
            // the whole screen at once: Kindle's idea of where a piece of text is can be a little
            // off, and the clock, "mins left" and such are simply passed over
            int w = pic.getWidth(), h = pic.getHeight();
            int[] px = new int[w * h];
            pic.getPixels(px, 0, w, 0, 0, w, h);
            int bgv = InkLayout.background(px), fill = 0xFF000000 | (bgv << 16) | (bgv << 8) | bgv;
            if (button != null) {                                        // (not the gold button)
                Rect cut = new Rect(button);
                cut.inset(-button.width() / 6, -button.height() / 6);
                if (cut.intersect(0, 0, w, h)) for (int y = cut.top; y < cut.bottom; y++) java.util.Arrays.fill(px, y * w + cut.left, y * w + cut.right, fill);
            }
            List<String> texts = new ArrayList<>();
            List<float[]> boxes = new ArrayList<>();
            for (Bit b : p.lines) { texts.add(b.text); boxes.add(new float[]{b.r.left, b.r.top, b.r.right, b.r.bottom}); }
            List<InkLayout.Word> ink = InkLayout.words(px, w, h, 0, 0);
            List<float[][]> placed = InkLayout.pageBoxes(texts, ink);
            KindleLayout before = shown;
            List<float[][]> chars = new ArrayList<>();
            int found = 0;
            for (int i = 0; i < p.lines.size(); i++) {
                float[][] got = placed == null ? null : placed.get(i);
                if (got != null) found++;
                chars.add(got != null ? got : before != null && i < before.chars.size() ? before.chars.get(i) : null);
            }
            if (found == 0) {
                Log.i("LoudbookTest", "kindle: couldn't place the words from the screen (" + ink.size() + " ink words), estimated instead");
                again = tries < 2;                                       // (maybe the page was still turning)
                return;
            }
            KindleLayout k = new KindleLayout(texts, boxes, chars);
            k.exactLines = found;
            if (p == shownPage) { shown = k; spansFor = -1; }
            Log.i("LoudbookTest", "kindle: words placed from the screen on " + found + " of " + p.lines.size() + " pieces (" + ink.size() + " ink words)");
        } catch (Throwable t) { Log.w(TAG, "kindle ink", t); }
        finally {
            pic.recycle();
            if (again) main.postDelayed(() -> fromScreen(p, tries + 1), 1200);
        }
    }

    /** The page now being read: its lines and where they are. */
    private volatile KindleLayout shown;

    static KindleLayout layoutOf(List<Bit> lines, boolean exact) {
        List<String> texts = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        List<float[][]> chars = new ArrayList<>();
        int exactOnes = 0;
        for (Bit b : lines) {
            texts.add(b.text);
            boxes.add(new float[]{b.r.left, b.r.top, b.r.right, b.r.bottom});
            float[][] cb = exact ? charBoxes(b) : null;
            if (cb != null) exactOnes++;
            else { try { cb = estimateChars(b.text, b.r); } catch (Throwable t) { Log.w(TAG, "kindle layout", t); } }
            chars.add(cb);
        }
        KindleLayout k = new KindleLayout(texts, boxes, chars);
        k.exactLines = exactOnes;
        return k;
    }

    /**
     * Where each character probably is, when Kindle doesn't say: Kindle often hands over a whole
     * paragraph (or page) as one piece, many rows tall. The text is laid out again here, in a
     * book-like font at the size that makes it wrap into as many rows as fit the piece's height,
     * which puts each word on the right row and close to its place in it.
     */
    /** Paragraphs after a line break start indented, as books set them. */
    private static CharSequence indented(String text, float size) {
        boolean firstIndented = !text.isEmpty() && (Character.isUpperCase(text.charAt(0)) || "\"\u201C\u2018'".indexOf(text.charAt(0)) >= 0);
        if (text.indexOf('\n') < 0 && !firstIndented) return text;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int indent = Math.round(size * 1.6f);
        if (firstIndented) {
            int e0 = text.indexOf('\n');
            sp.setSpan(new android.text.style.LeadingMarginSpan.Standard(indent, 0), 0, e0 < 0 ? text.length() : e0 + 1, android.text.Spanned.SPAN_PARAGRAPH);
        }
        for (int i = text.indexOf('\n'); i >= 0 && i + 1 < text.length(); i = text.indexOf('\n', i + 1)) {
            int end = text.indexOf('\n', i + 1);
            sp.setSpan(new android.text.style.LeadingMarginSpan.Standard(indent, 0), i + 1, end < 0 ? text.length() : end + 1, android.text.Spanned.SPAN_PARAGRAPH);
        }
        return sp;
    }

    static float[][] estimateChars(String text, Rect r) {
        int n = text.length(), w = r.width(), h = r.height();
        if (n == 0 || w <= 0 || h <= 0) return null;
        android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(android.graphics.Typeface.SERIF);
        android.text.StaticLayout best = null;
        int bestRows = 1;
        float bestF = 1.5f;
        float[] spacing = {1.5f, 1.4f, 1.6f, 1.3f, 1.7f, 1.25f, 1.8f};
        int maxRows = Math.max(1, Math.min(80, h / 18));
        search:
        for (float f : spacing) {
            for (int rows = 1; rows <= maxRows; rows++) {
                paint.setTextSize(h / (rows * f));
                android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(indented(text, paint.getTextSize()), 0, n, paint, w).setIncludePad(false).build();
                if (l.getLineCount() == rows) { best = l; bestRows = rows; bestF = f; break search; }
                if (l.getLineCount() < rows) break;                  // smaller text only makes fewer rows
            }
        }
        if (best == null) {                                           // nothing fits exactly: one row per... the nearest
            paint.setTextSize(h / (Math.max(1, Math.round(h / 60f)) * 1.5f));
            best = android.text.StaticLayout.Builder.obtain(indented(text, paint.getTextSize()), 0, n, paint, w).setIncludePad(false).build();
            bestRows = Math.max(1, best.getLineCount());
        }
        // rows are spaced evenly, the space between them below each row's letters (none after the
        // last): pitch p with h = (rows - 1) * p + p / f
        float pitch = h / (bestRows - 1 + 1f / bestF), glyphs = pitch / bestF;
        float[][] out = new float[n][];
        for (int c = 0; c < n; c++) {
            if (Character.isWhitespace(text.charAt(c))) continue;
            int line = best.getLineForOffset(c);
            float x0 = best.getPrimaryHorizontal(c);
            float x1 = c + 1 < n && best.getLineForOffset(c + 1) == line ? best.getPrimaryHorizontal(c + 1) : best.getLineRight(line);
            if (x1 < x0) { float t = x0; x0 = x1; x1 = t; }
            float top = r.top + line * pitch;
            out[c] = new float[]{r.left + x0, top, r.left + Math.max(x1, x0 + 1), top + glyphs};
        }
        return out;
    }

    /** Each character's box on screen, if Kindle shares them (as text views do); null if not. */
    private static float[][] charBoxes(Bit b) {
        if (!b.ownText || b.node == null || b.text.length() > 2000) return null;
        try {
            String key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
            if (!b.node.getAvailableExtraData().contains(key)) return null;
            android.os.Bundle args = new android.os.Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, b.text.length());
            if (!b.node.refreshWithExtraData(key, args)) return null;
            android.os.Parcelable[] a = b.node.getExtras().getParcelableArray(key);
            if (a == null || a.length != b.text.length()) return null;
            float[][] out = new float[a.length][];
            for (int i = 0; i < a.length; i++) if (a[i] instanceof android.graphics.RectF) { android.graphics.RectF f = (android.graphics.RectF) a[i]; out[i] = new float[]{f.left, f.top, f.right, f.bottom}; }
            return out;
        } catch (Throwable t) { return null; }
    }

    static final class Bit {
        final String text; final Rect r; AccessibilityNodeInfo node; boolean ownText;
        Bit(String t, Rect r) { text = t; this.r = r; }
    }
    /** Instructions for screen-reader users ("double tap to…") and the like: never part of a book. */
    static final Pattern HINT = Pattern.compile("(?i).*\\b(double[- ]?tap|tap to|swipe (up|down|left|right)|to activate|to dismiss|continuous reading)\\b.*");
    static final Pattern HINT_PART = Pattern.compile("(?i)(?<=^|[.!?;])\\s*[^.!?;]*\\b(double[- ]?tap|tap to|swipe (up|down|left|right)|to activate|to dismiss|continuous reading)\\b[^.!?;]*[.!?;]?");
    private static final Pattern CHROME = Pattern.compile("(?i)^(location \\d+.*|page \\d+.*|\\d+\\s*%.*|\\d+ (min|mins|hr|hrs|hours?|minutes?) left.*|learning reading speed.*|.*\\bof \\d+\\s*$)");

    /** The text on the Kindle page, in reading order, without the bars around it. */
    Page readPage() {
        Page p = new Page();
        // the page just changed: don't trust remembered copies of its text (an app that draws its
        // own page may not say exactly which bits changed)
        if (android.os.Build.VERSION.SDK_INT >= 34) { try { clearCache(); } catch (Throwable ignored) { } }
        AccessibilityNodeInfo root = kindleRoot();
        if (root == null) return p;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<Bit> bits = new ArrayList<>();
        collect(root, bits, 0);
        int h = Math.max(1, screen.height());
        List<Bit> body = new ArrayList<>();
        List<Bit> edges = new ArrayList<>();
        for (Bit b : bits) {
            String t = b.text.trim();
            // a short line that was in the same place on the last page too is a running header or
            // footer (the book's title, say), not part of the text
            boolean nearEdge = b.r.top < screen.top + h * 0.2 || b.r.bottom > screen.bottom - h * 0.15;
            if (nearEdge && t.length() < 80) {
                edges.add(b);
                boolean repeated = false;
                for (Bit o : lastEdges) repeated |= o.text.equals(b.text) && Math.abs(o.r.top - b.r.top) < 60;
                if (repeated) continue;
            }
            boolean edge = b.r.top < screen.top + h * 0.08 || b.r.bottom > screen.bottom - h * 0.07;
            if (edge && t.length() < 80) { if (b.r.top < screen.top + h * 0.08 && p.title.isEmpty() && t.length() > 2 && !CHROME.matcher(t).matches()) p.title = t; continue; }
            if (CHROME.matcher(t).matches() && t.length() < 60) continue;
            body.add(b);
        }
        body.sort((a, b) -> a.r.top != b.r.top && Math.abs(a.r.top - b.r.top) > 4 ? Integer.compare(a.r.top, b.r.top) : Integer.compare(a.r.left, b.r.left));
        // lines into paragraphs: a bigger gap than usual, or an indented line, starts a new one
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < body.size(); i++) gaps.add(body.get(i).r.top - body.get(i - 1).r.bottom);
        List<Integer> sorted = new ArrayList<>(gaps);
        java.util.Collections.sort(sorted);
        int usual = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        int minLeft = Integer.MAX_VALUE;
        for (Bit b : body) minLeft = Math.min(minLeft, b.r.left);
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            Bit b = body.get(i);
            boolean longBit = b.text.length() > 200;           // a whole paragraph in one piece
            boolean newPara = i > 0 && (longBit || gaps.get(i - 1) > usual * 1.6 + 6 || b.r.left > minLeft + 20 * getResources().getDisplayMetrics().density);
            if (newPara && para.length() > 0) { p.paragraphs.add(para.toString()); para.setLength(0); }
            if (para.length() > 0) para.append(para.charAt(para.length() - 1) == '-' ? "" : " ");
            para.append(b.text.trim());
            if (longBit) { p.paragraphs.add(para.toString()); para.setLength(0); }
        }
        if (para.length() > 0) p.paragraphs.add(para.toString());
        p.edges = edges;
        p.lines = body;
        // what's on screen, unfiltered: tells whether the page really changed
        StringBuilder sig = new StringBuilder();
        for (Bit b : bits) sig.append(b.text.length() > 40 ? b.text.substring(0, 40) : b.text).append('|');
        p.signature = sig.toString();
        return p;
    }

    /** Text-bearing nodes with no text below them (the finest pieces), skipping buttons. */
    private static void collect(AccessibilityNodeInfo n, List<Bit> out, int depth) {
        if (n == null || depth > 40) return;
        if (android.os.Build.VERSION.SDK_INT < 34 && depth > 0) n.refresh();
        if (!n.isVisibleToUser()) return;
        int before = out.size();
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
        if (out.size() > before) return;                         // its children had the text
        CharSequence t = n.getText();
        boolean control = n.isClickable() || n.isLongClickable() || n.isCheckable();
        // a description is a label for something (a control, a picture), not book text, unless it's long
        if ((t == null || t.length() == 0) && n.getContentDescription() != null && (!control || n.getContentDescription().length() > 160)) t = n.getContentDescription();
        if (t == null || t.toString().trim().isEmpty()) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("Button") || cls.endsWith("ImageView") || cls.endsWith("EditText") || cls.endsWith("SeekBar") || cls.endsWith("Switch") || cls.endsWith("CheckBox")) return;
        if (control && t.length() < 120 && !cls.contains("Text")) return;
        if (t.length() < 200 && HINT.matcher(t).matches()) return;
        // a page's text with an instruction tacked on ("... Continuous reading, double tap to start"): drop the instruction
        if (t.length() >= 200) t = HINT_PART.matcher(t).replaceAll("").trim();
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;
        Bit b = new Bit(t.toString(), r);
        b.node = n;
        b.ownText = n.getText() != null && n.getText().toString().equals(b.text);
        out.add(b);
    }

    /**
     * Turns to the next page and returns it (an empty page if it didn't turn: the end of the book,
     * or Kindle wasn't on screen). Tries what TalkBack does first (scroll forward), then a swipe,
     * then a tap on the right edge.
     */
    Page nextPage(String beforeSig) {
        AccessibilityNodeInfo root = kindleRoot();
        if (root == null) return new Page();
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean sent;
            if (attempt == 0) sent = scrollForward(root);
            else sent = gesture(attempt == 1);
            if (!sent) continue;
            long end = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(150); } catch (InterruptedException e) { return new Page(); }
                Page p = readPage();
                if (!p.paragraphs.isEmpty() && !p.signature.equals(beforeSig)) {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { }   // let it finish drawing
                    Page q = readPage();
                    Page got = q.paragraphs.isEmpty() ? p : q;
                    accept(got);
                    return got;
                }
            }
            root = kindleRoot();
            if (root == null) break;
        }
        return new Page();
    }

    private static boolean scrollForward(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (scrollForward(n.getChild(i))) return true;
        return false;
    }

    private boolean gesture(boolean swipe) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        Path path = new Path();
        if (swipe) { path.moveTo(w * 0.85f, h * 0.5f); path.lineTo(w * 0.15f, h * 0.5f); }
        else path.moveTo(w * 0.93f, h * 0.5f);
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, swipe ? 220 : 60)).build();
        final Object lock = new Object();
        final boolean[] done = {false, false};
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { synchronized (lock) { done[0] = true; done[1] = true; lock.notifyAll(); } }
            @Override public void onCancelled(GestureDescription d) { synchronized (lock) { done[0] = true; lock.notifyAll(); } }
        }, main);
        if (!ok) return false;
        synchronized (lock) { try { if (!done[0]) lock.wait(1500); } catch (InterruptedException ignored) { } }
        return done[1];
    }

    /** What the Kindle screen looks like to a screen reader (for fixing the reader if Kindle changes). */
    String dump() {
        StringBuilder sb = new StringBuilder();
        AccessibilityNodeInfo root = kindleRoot();
        if (root == null) return "Kindle isn't on screen.";
        dumpNode(root, sb, 0);
        Page p = readPage();
        sb.append("\n--- read as ").append(p.paragraphs.size()).append(" paragraphs; title: ").append(p.title).append('\n');
        for (String s : p.paragraphs) sb.append("¶ ").append(s.length() > 120 ? s.substring(0, 120) + "…" : s).append('\n');
        return sb.toString();
    }
    private static void dumpNode(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 30 || sb.length() > 60000) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        CharSequence t = n.getText(), d = n.getContentDescription();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(n.getClassName()).append(n.getViewIdResourceName() != null ? " #" + n.getViewIdResourceName().replace(KINDLE + ":id/", "") : "")
          .append(' ').append(r.toShortString())
          .append(n.isScrollable() ? " scroll" : "").append(n.isClickable() ? " click" : "").append(n.isVisibleToUser() ? "" : " hidden");
        if (t != null && t.length() > 0) sb.append(" text[").append(t.length()).append("]=\"").append(t.length() > 50 ? t.subSequence(0, 50) + "…" : t).append('"');
        if (d != null && d.length() > 0) sb.append(" desc[").append(d.length()).append("]=\"").append(d.length() > 50 ? d.subSequence(0, 50) + "…" : d).append('"');
        // what the reader could use for exact placement (line by line, character boxes)
        if (t != null && t.length() > 40) {
            sb.append(" gran=").append(n.getMovementGranularities());
            if (n.getAvailableExtraData() != null && !n.getAvailableExtraData().isEmpty()) sb.append(" extra=").append(n.getAvailableExtraData());
            int nl = 0; for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') nl++;
            sb.append(" newlines=").append(nl);
        }
        sb.append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), sb, depth + 1);
    }

    boolean kindleVisible() { return kindleRoot() != null; }

    static boolean enabled(Context c) {
        String on = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/" + KindleService.class.getName()) || on != null && on.contains(c.getPackageName() + "/.KindleService");
    }
}
