#!/usr/bin/env bash
# Emulator test of Gemini on a web chapter, against a stand-in Google on the runner
# (fake_gemini.py, port 8001) and two chapters served from test/gemini (port 8000):
#   1. streamed (the usual way): how soon reading starts, the phone's voice until Gemini's audio
#      arrives, where every sentence is cut, recording ahead into the next chapter;
#   2. not streamed (Google sends a recording only once it's all made): the same, from a cold start.
set -x
adb reverse tcp:8000 tcp:8000
adb reverse tcp:8001 tcp:8001
adb install -r build/Loudbook.apk
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -n com.loudbook.app/.MainActivity
sleep 100                                   # the phone voice downloads and loads the first time
G="--es gemhost http://localhost:8001/v1beta/"
adb shell log -t LoudbookTest "'=== streamed: play chapter one ==='"
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/g1.html" --ez play true $G
sleep 170
adb logcat -d > logcat1.txt
adb shell log -t LoudbookTest "'=== all at once: play chapter one again, a new address, so nothing is recorded yet ==='"   # (quoted twice: adb shell runs it through sh)
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/g1.html?again=1" --ez play true --ez gemwhole true $G
sleep 150
adb shell am force-stop com.loudbook.app
adb logcat -d > logcat.txt
