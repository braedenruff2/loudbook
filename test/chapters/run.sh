#!/usr/bin/env bash
# Emulator test of going on to the next chapter with Loudbook in the background and the screen
# off: three short chapters served from the runner (test/chapters), reached as http://localhost.
set -x
adb reverse tcp:8000 tcp:8000
adb install -r build/Loudbook.apk
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -n com.loudbook.app/.MainActivity
sleep 100                                   # the phone voice downloads and loads the first time
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/ch1.html" --ez play true
sleep 12
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell input keyevent KEYCODE_SLEEP
adb shell log -t LoudbookTest "=== app in the background, screen off ==="
sleep 150
adb logcat -d > logcat.txt
