package com.amazon.kindle;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.List;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

public class Reader extends Activity {
    static final String[][] PAGES = {
        {"Chapter Three", "The road out of the valley was older than the town, and in places the stones had sunk so deep that grass grew over them.",
         "Mara walked it every morning. She liked the quiet, and she liked that nobody else", },
        {"bothered to come this way anymore, not since the new highway had opened on the far side of the hill.",
         "\"You'll wear out your boots,\" her aunt said, every single time.", "\"Then I'll buy new ones.\""},
        {"The last page has only one paragraph, and it ends the chapter cleanly when Mr. Hale reaches Lv. 5 in Chapter IV."},
    };
    int page = 0;
    LinearLayout body;
    // explore mode: the page is drawn (like Kindle's renderer) and its text is shared only while
    // a screen reader that explores by touch is on
    boolean explore;
    Drawn drawn;

    class Drawn extends android.widget.FrameLayout {
        // like Kindle's renderer: the page is one block of wrapped, book-like text, and screen
        // readers get it as one piece (the text view inside is hidden from them)
        final List<String> lines = new ArrayList<>();     // (unused; kept for show())
        final List<Integer> indents = new ArrayList<>();
        String[] paras = new String[0];
        final TextView page;
        Drawn() {
            super(Reader.this);
            page = new TextView(Reader.this);
            page.setTextSize(22);
            page.setTypeface(android.graphics.Typeface.SERIF);
            page.setLineSpacing(0, 1.5f);
            page.setTextColor(0xFF222222);
            page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            // (and gives screen readers nothing itself: the text only comes as the one big piece)
            page.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setText(null); info.setContentDescription(null);
                }
            });
            addView(page, new android.widget.FrameLayout.LayoutParams(-1, -2));
        }
        String text() { StringBuilder b = new StringBuilder(); for (String p : paras) { if (b.length() > 0) b.append('\n'); b.append(p); } return b.toString(); }
        void setParas(String[] p) {
            paras = p;
            String t = text();
            android.text.SpannableString sp = new android.text.SpannableString(t);
            int at = 0;
            for (String q : p) {
                sp.setSpan(new android.text.style.LeadingMarginSpan.Standard(70, 0), at, Math.min(t.length(), at + q.length() + 1), android.text.Spanned.SPAN_PARAGRAPH);
                at += q.length() + 1;
            }
            page.setText(sp);
        }
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (!am.isTouchExplorationEnabled()) return null;
            return new AccessibilityNodeProvider() {
                @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                    if (id == View.NO_ID) {
                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(Drawn.this);
                        onInitializeAccessibilityNodeInfo(info);
                        info.addChild(Drawn.this, 0);
                        info.addChild(Drawn.this, 999);
                        return info;
                    }
                    if (id == 999) {
                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(Drawn.this, id);
                        info.setParent(Drawn.this);
                        info.setClassName("android.view.View");
                        info.setContentDescription("Continuous reading, double tap to start reading");
                        info.setClickable(true);
                        int[] at = new int[2];
                        getLocationOnScreen(at);
                        info.setBoundsInScreen(new Rect(at[0] + 100, at[1] + getHeight() - 200, at[0] + 600, at[1] + getHeight() - 100));
                        info.setVisibleToUser(true);
                        info.setPackageName(getPackageName());
                        return info;
                    }
                    if (id != 0) return null;
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(Drawn.this, id);
                    info.setParent(Drawn.this);
                    info.setClassName("android.view.View");
                    info.setText(text());
                    int[] at = new int[2];
                    getLocationOnScreen(at);
                    Rect r = new Rect(0, 0, page.getWidth(), page.getHeight());
                    r.offset(at[0], at[1]);
                    info.setBoundsInScreen(r);
                    info.setVisibleToUser(true);
                    info.setEnabled(true);
                    info.setPackageName(getPackageName());
                    return info;
                }
            };
        }
    }
    TextView foot;

    class Pages extends LinearLayout {
        Pages() { super(Reader.this); setOrientation(VERTICAL); }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setScrollable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { Log.i("FakeKindle", "scroll forward"); return turn(); }
            return super.performAccessibilityAction(action, args);
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Pages root = new Pages();
        root.setPadding(40, 40, 40, 40);
        TextView head = new TextView(this); head.setText("The Valley Road"); head.setGravity(Gravity.CENTER);
        root.addView(head);
        explore = getIntent().getBooleanExtra("explore", false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        if (explore) {
            drawn = new Drawn();
            root.addView(drawn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            am.addTouchExplorationStateChangeListener(new AccessibilityManager.TouchExplorationStateChangeListener() {
                @Override public void onTouchExplorationStateChanged(boolean on) {
                    Log.i("FakeKindle", "touch exploration " + on);
                    drawn.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                }
            });
        } else root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        foot = new TextView(this); foot.setGravity(Gravity.CENTER);
        root.addView(foot);
        setContentView(root);
        show();
    }

    boolean turn() {
        if (page + 1 >= PAGES.length) return false;
        page++;
        show();
        return true;
    }

    void show() {
        body.removeAllViews();
        if (drawn != null) drawn.setParas(PAGES[page]);
        for (String para : PAGES[page]) {
            // lay each paragraph out as lines of about 40 characters, like a book page
            StringBuilder line = new StringBuilder();
            boolean first = true;
            for (String w : para.split(" ")) {
                if (line.length() + w.length() > 40) { addLine(line.toString(), first); first = false; line.setLength(0); }
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
            if (line.length() > 0) addLine(line.toString(), first);
            TextView gap = new TextView(this); gap.setText(""); gap.setHeight(30); body.addView(gap);

        }
        if (drawn != null) { drawn.invalidate(); drawn.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }
        foot.setText("Location " + (120 + page * 7) + " of 4000 • " + (3 + page) + "%");
        Log.i("FakeKindle", "showing page " + page);
    }

    void addLine(String s, boolean indent) {
        if (drawn != null) return;
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(18);
        t.setPadding(indent ? 40 : 0, 0, 0, 0);
        body.addView(t);
    }

    float downX;
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) downX = e.getX();
        if (e.getAction() == MotionEvent.ACTION_UP && downX - e.getX() > 200) { Log.i("FakeKindle", "swipe"); turn(); }
        return super.dispatchTouchEvent(e);
    }
}
