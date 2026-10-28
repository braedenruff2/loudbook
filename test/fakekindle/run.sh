#!/usr/bin/env bash
# Emulator test of the Kindle reader against the stand-in Kindle. Run inside the emulator job.
set -x
adb install -r build/Loudbook.apk
adb install -r test/fakekindle/out/FakeKindle.apk
adb shell settings put secure enabled_accessibility_services com.loudbook.app/com.loudbook.app.KindleService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb logcat -c
adb logcat -G 16M || true
adb shell am start -n com.loudbook.app/.MainActivity
sleep 100                                   # the phone voice downloads and loads the first time
# (switch the reader on again now that everything's installed and running: the first time can be missed)
adb shell settings delete secure enabled_accessibility_services || true
sleep 1
adb shell settings put secure enabled_accessibility_services com.loudbook.app/com.loudbook.app.KindleService
sleep 3
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || true
adb shell am start -n com.amazon.kindle/.Reader
sleep 6
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DEN=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
DP=$(( DEN * 100 / 160 ))
X=$(( W - (52 + 12) * DP / 100 + 26 * DP / 100 )); Y=$(( H * 2 / 3 + 26 * DP / 100 ))
adb shell screencap -p /sdcard/before.png
adb shell input tap $X $Y
sleep 9
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || true
adb shell screencap -p /sdcard/reading.png             # the highlight on the page, mid-sentence
adb pull /sdcard/reading.png .
sleep 66
adb shell screencap -p /sdcard/after.png
adb pull /sdcard/before.png . ; adb pull /sdcard/after.png .
# second book: a Kindle that shares its text only with an explore-by-touch screen reader
adb shell log -t LoudbookTest "=== explore-only Kindle ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true
sleep 6
adb shell input tap $X $Y
sleep 12
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || true
adb shell screencap -p /sdcard/explore-reading.png      # the highlight worked out for a whole-page piece of text
adb pull /sdcard/explore-reading.png .
sleep 68
adb shell screencap -p /sdcard/explore.png
adb pull /sdcard/explore.png .
# choosing where to start: hold the button, then tap the second paragraph ("Mara walked...")
adb shell log -t LoudbookTest "=== choose where to start ==="
adb shell am start -S -n com.amazon.kindle/.Reader
sleep 6
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml . >/dev/null 2>&1
B=$(grep -o 'text="Mara walked[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ui.xml | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ')
set -- $B
TX=$(( ($1 + $3) / 2 )); TY=$(( ($2 + $4) / 2 ))
adb shell log -t LoudbookTest "tapping the line at $TX,$TY"
adb shell input swipe $X $Y $X $Y 1200                # hold the button
sleep 2
adb shell screencap -p /sdcard/pick.png; adb pull /sdcard/pick.png .
adb shell input tap $TX $TY
sleep 12
adb shell input tap $X $Y                              # pause
# Kindle closed after reading: the round button must go away
adb shell am force-stop com.amazon.kindle
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell screencap -p /sdcard/closed.png
adb pull /sdcard/closed.png .
adb shell dumpsys window windows | grep -c "Window{.*com.loudbook.app" | sed 's/^/Loudbook windows on screen after Kindle closed: /' > closed.txt
adb shell settings get secure touch_exploration_enabled | sed 's/^/touch exploration at the end: /' > explore-state.txt
# choosing where to start in a Kindle that shares its text only with explore by touch (the same tap as above)
adb shell log -t LoudbookTest "=== choose where to start, explore-only Kindle ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true
sleep 6
adb shell input swipe $X $Y $X $Y 1200                # hold the button
sleep 2
adb shell input tap $TX $TY
sleep 10
adb shell screencap -p /sdcard/explore-pick.png; adb pull /sdcard/explore-pick.png .
adb logcat -d | sed -n '/=== choose where to start, explore-only/,$p' | grep -o "kindle: start at letter.*\|no book text found" | tail -1 | sed 's/^/explore-only, hold and tap: /' > pick.txt
adb shell input tap $X $Y                              # pause (so the next section's tap starts reading, not pauses it)
sleep 2
adb shell am force-stop com.amazon.kindle
adb shell input keyevent KEYCODE_HOME
# leaving Kindle while it reads (screen-reader touch on): everything must shut off by itself,
# the screen-reader touch first (it would make every other app need double taps), and reading pauses
adb shell log -t LoudbookTest "=== leave Kindle while reading ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true
sleep 6
adb shell input tap $X $Y
sleep 14
adb shell settings get secure touch_exploration_enabled | sed 's/^/touch exploration while reading: /' > leave.txt
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell settings get secure touch_exploration_enabled | sed 's/^/touch exploration after leaving Kindle: /' >> leave.txt
adb shell dumpsys window windows | grep -c "Window{.*com.loudbook.app" | sed 's/^/Loudbook windows on screen after leaving Kindle: /' >> leave.txt
adb logcat -d | sed -n '/=== leave Kindle while reading/,$p' > leave-log.txt
grep -c "kindle speaking" leave-log.txt | sed 's/^/sentences read before leaving: /' >> leave.txt
grep -q "left Kindle, reading paused" leave-log.txt && echo "reading after leaving Kindle: paused" >> leave.txt || echo "reading after leaving Kindle: NOT paused" >> leave.txt
adb shell screencap -p /sdcard/left.png; adb pull /sdcard/left.png .
adb shell am force-stop com.amazon.kindle
# a Kindle that also lists the next page's text, unseen, as the real Kindle does with a screen reader on:
# the sentence over the page break is read whole, once, and the page turns when the voice gets to the next page's words
adb shell log -t LoudbookTest "=== next page shared ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true --ez next true
sleep 6
# (hold and tap "Mara walked...": a fresh start there, whatever the last section left paused)
adb shell input swipe $X $Y $X $Y 1200
sleep 2
adb shell input tap $TX $TY
sleep 50                                               # (the emulator's voice is slow: the long sentence over the break takes ~18 s to make)
adb shell screencap -p /sdcard/bridge.png; adb pull /sdcard/bridge.png .
adb logcat -d | sed -n '/=== next page shared/,$p' > bridge-log.txt
grep -q "sentence over the page break" bridge-log.txt && echo "next page shared, sentence over the break read whole: yes" > bridge.txt || echo "next page shared, sentence over the break read whole: NO" > bridge.txt
grep -q "the voice is at the next page's words" bridge-log.txt && echo "page turned at the next page's words: yes" >> bridge.txt || echo "page turned at the next page's words: NO" >> bridge.txt
grep -c "kindle speaking [0-9]*: She liked the quiet" bridge-log.txt | sed 's/^/times the sentence over the break was read (want 1): /' >> bridge.txt
grep -c "kindle speaking [0-9]*: bothered" bridge-log.txt | sed 's/^/times the next page start was read again (want 0): /' >> bridge.txt
adb shell am force-stop com.amazon.kindle
adb shell input keyevent KEYCODE_HOME
# leaving Kindle just as a page ends (the stand-in Kindle goes to the background instead of turning, once):
# reading must wait, paused, not take it as the end of the book, and turn the page once Kindle is back and the
# button is tapped, reading the sentence over the break once, whole
adb shell log -t LoudbookTest "=== away at the page turn ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true --ez away true
sleep 6
adb shell input tap $X $Y
for i in $(seq 1 45); do sleep 2; adb logcat -d | sed -n '/=== away at the page turn/,$p' | grep -q "not on screen at the page turn" && break; done
sleep 6
adb shell am start -n com.amazon.kindle/.Reader --ez explore true      # (back to the book, on the same page)
sleep 6
adb shell input tap $X $Y                                               # carry on
sleep 45
adb shell screencap -p /sdcard/away.png; adb pull /sdcard/away.png .
adb logcat -d | sed -n '/=== away at the page turn/,$p' > away-log.txt
grep -q "not on screen at the page turn" away-log.txt && echo "Kindle away at the page turn, noticed: yes" > away.txt || echo "Kindle away at the page turn, noticed: NO" > away.txt
grep -q "left Kindle, reading paused" away-log.txt && echo "reading paused while away: yes" >> away.txt || echo "reading paused while away: NO" >> away.txt
grep -q "back in Kindle, turning the page now" away-log.txt && grep -q "kindle page turned" away-log.txt && echo "page turned once back: yes" >> away.txt || echo "page turned once back: NO" >> away.txt
grep -c "kindle speaking [0-9]*: She liked the quiet" away-log.txt | sed 's/^/times the sentence over the break was started (want 1): /' >> away.txt
grep -q "kindle speaking [0-9]*: and she liked that nobody else bothered" away-log.txt && echo "the rest of it read with the next page's words: yes" >> away.txt || echo "the rest of it read with the next page's words: NO" >> away.txt
adb shell am force-stop com.amazon.kindle
adb shell input keyevent KEYCODE_HOME
adb logcat -d > logcat.txt
