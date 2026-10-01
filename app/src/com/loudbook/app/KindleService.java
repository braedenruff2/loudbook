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
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bound = bindService(new Intent(this, ReaderService.class), conn, Context.BIND_AUTO_CREATE);
        main.post(tick);
    }

    @Override public void onDestroy() {
        me = null;
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
        boolean reading = svc != null && svc.readingKindle();
        if (kindle || reading) showBubble(); else hideBubble();
        // explore-by-touch only while actually reading (so the phone works normally the rest of the time)
        if (!reading && !busy) needExplore = false;
        boolean explore = needExplore && reading && svc.isPlaying() && !svc.isPaused();
        if (explore != exploreOn && !busy) setExplore(explore);
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
                case MotionEvent.ACTION_DOWN: down[0] = ev.getRawX(); down[1] = ev.getRawY(); down[2] = bubbleLp.x; down[3] = bubbleLp.y; moved[0] = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (Math.abs(dx) + Math.abs(dy) > 12 * dm.density) moved[0] = true;
                    if (moved[0]) { bubbleLp.x = (int) (down[2] + dx); bubbleLp.y = (int) (down[3] + dy); wm.updateViewLayout(bubble, bubbleLp); }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved[0]) Voice.prefs(this).edit().putInt("bubbleX", bubbleLp.x).putInt("bubbleY", bubbleLp.y).apply();
                    else tapped();
                    return true;
            }
            return false;
        });
        try { wm.addView(bubble, bubbleLp); } catch (Exception e) { Log.w(TAG, "kindle button", e); bubble = null; }
    }

    private void hideBubble() {
        if (bubble == null) return;
        try { wm.removeView(bubble); } catch (Exception ignored) { }
        bubble = null;
    }

    private void tapped() {
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
            final String why = got.paragraphs.isEmpty() ? dump() : null;
            main.post(() -> {
                busy = false;
                if (got.paragraphs.isEmpty()) {
                    // what Loudbook sees, ready to paste, so the reader can be fixed for this Kindle
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Loudbook Kindle", "Loudbook " + BuildInfo.VERSION + " Kindle view, Android " + android.os.Build.VERSION.SDK_INT + "\n" + why));
                    } catch (Exception ignored) { }
                    Log.i("LoudbookTest", "no book text found");
                    toast("No book text found in Kindle. What Loudbook sees there is copied: paste it to Claude to get it fixed. (Or the book's publisher doesn't allow screen readers.)");
                    return;
                }
                needExplore = ex;
                if (ex) toast("Kindle shares this book's text only with screen readers, so while Loudbook reads, the screen works like one. Tap the gold button to pause and get your screen back.");
                accept(got);
                svc.startKindle(got);
                watch();
            });
        }, "lb-kindle").start();
    }
    private volatile boolean busy;
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
    void accept(Page p) { lastEdges = p.edges; }

    static final class Bit { final String text; final Rect r; Bit(String t, Rect r) { text = t; this.r = r; } }
    private static final Pattern CHROME = Pattern.compile("(?i)^(location \\d+.*|page \\d+.*|\\d+\\s*%.*|\\d+ (min|mins|hr|hrs|hours?|minutes?) left.*|learning reading speed.*|.*\\bof \\d+\\s*$)");

    /** The text on the Kindle page, in reading order, without the bars around it. */
    Page readPage() {
        Page p = new Page();
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
        // what's on screen, unfiltered: tells whether the page really changed
        StringBuilder sig = new StringBuilder();
        for (Bit b : bits) sig.append(b.text.length() > 40 ? b.text.substring(0, 40) : b.text).append('|');
        p.signature = sig.toString();
        return p;
    }

    /** Text-bearing nodes with no text below them (the finest pieces), skipping buttons. */
    private static void collect(AccessibilityNodeInfo n, List<Bit> out, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return;
        int before = out.size();
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
        if (out.size() > before) return;                         // its children had the text
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t == null || t.toString().trim().isEmpty()) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("Button") || cls.endsWith("ImageView") || cls.endsWith("EditText") || cls.endsWith("SeekBar")) return;
        if (n.isClickable() && t.length() < 30 && !cls.contains("Text")) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return;
        out.add(new Bit(t.toString(), r));
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
        sb.append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), sb, depth + 1);
    }

    boolean kindleVisible() { return kindleRoot() != null; }

    static boolean enabled(Context c) {
        String on = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/" + KindleService.class.getName()) || on != null && on.contains(c.getPackageName() + "/.KindleService");
    }
}
