#!/usr/bin/env bash
# Emulator test of the Kindle reader against the stand-in Kindle. Run inside the emulator job.
set -x
adb install -r build/Loudbook.apk
adb install -r test/fakekindle/out/FakeKindle.apk
adb shell settings put secure enabled_accessibility_services com.loudbook.app/com.loudbook.app.KindleService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -n com.loudbook.app/.MainActivity
sleep 100                                   # the phone voice downloads and loads the first time
adb shell am start -n com.amazon.kindle/.Reader
sleep 6
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DEN=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
DP=$(( DEN * 100 / 160 ))
X=$(( W - (52 + 12) * DP / 100 + 26 * DP / 100 )); Y=$(( H * 2 / 3 + 26 * DP / 100 ))
adb shell screencap -p /sdcard/before.png
adb shell input tap $X $Y
sleep 75
adb shell screencap -p /sdcard/after.png
adb pull /sdcard/before.png . ; adb pull /sdcard/after.png .
# second book: a Kindle that shares its text only with an explore-by-touch screen reader
adb shell log -t LoudbookTest "=== explore-only Kindle ==="
adb shell am start -S -n com.amazon.kindle/.Reader --ez explore true
sleep 6
adb shell input tap $X $Y
sleep 80
adb shell screencap -p /sdcard/explore.png
adb pull /sdcard/explore.png .
# Kindle closed after reading: the round button must go away
adb shell am force-stop com.amazon.kindle
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell screencap -p /sdcard/closed.png
adb pull /sdcard/closed.png .
adb shell dumpsys window windows | grep -c "Window{.*com.loudbook.app" | sed 's/^/Loudbook windows on screen after Kindle closed: /' > closed.txt
adb shell settings get secure touch_exploration_enabled | sed 's/^/touch exploration at the end: /' > explore-state.txt
adb logcat -d > logcat.txt
