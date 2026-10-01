package com.amazon.kindle;

import android.app.Activity;
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
        {"The last page has only one paragraph, and it ends the chapter cleanly."},
    };
    int page = 0;
    LinearLayout body;
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
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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
        foot.setText("Location " + (120 + page * 7) + " of 4000 • " + (3 + page) + "%");
        Log.i("FakeKindle", "showing page " + page);
    }

    void addLine(String s, boolean indent) {
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
