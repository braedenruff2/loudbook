#!/usr/bin/env bash
# Screenshots of Loudbook on an emulator (for reviewing how it looks): the start screen, a chapter
# being read, and every part of Settings.
set -x
adb reverse tcp:8000 tcp:8000
adb install -r build/Loudbook.apk
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb shell am start -n com.loudbook.app/.MainActivity
sleep 90
mkdir -p shots
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png shots/01-start.png
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/ch1.html" --ez play true
sleep 12
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png shots/02-reading.png
adb shell input keyevent KEYCODE_MEDIA_PAUSE
adb shell am start -n com.loudbook.app/.MainActivity --ez settings true
sleep 5
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
for i in $(seq -w 1 12); do
  adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png shots/03-settings-$i.png
  adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 400
  sleep 1
done
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell cmd uimode night yes
sleep 3
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png shots/04-dark.png
