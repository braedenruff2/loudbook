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
sleep 10
# no signal from here on: the chapters loaded ahead must carry it to the end
adb reverse --remove tcp:8000 || true
adb shell log -t LoudbookTest "=== no signal from here ==="
sleep 150
adb logcat -d > logcat.txt
# cold start: Loudbook closed, opened again, play pressed with nothing open -> carries on with the last story
adb reverse tcp:8000 tcp:8000
adb shell am force-stop com.loudbook.app
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell log -t LoudbookTest "=== reopened, play with nothing open ==="
adb shell am start -n com.loudbook.app/.MainActivity
sleep 25
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W * 46 / 100)) $((H * 89 / 100))          # the big play button
sleep 30
adb logcat -d > logcat.txt
