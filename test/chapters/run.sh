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
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/ch1.html" --ez play true --ez dual true
sleep 8
# the audio output fails once mid-sentence (as when the phone's audio server restarts): reading must go on (2026-11-03: it
# used to stop without a word, still showing "playing")
adb shell am start -n com.loudbook.app/.MainActivity --ez audiofail true
sleep 4
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
# (chapter one, read with the app open, has fancy letters and an emoji: every sentence must be marked on the page)
awk '/=== app in the background/{exit} 1' logcat.txt > foreground.txt
echo "app open (fancy text, emoji): sentences said: $(grep -c 'LoudbookTest: speaking' foreground.txt), not found on the page: $(grep -c "words weren't found" foreground.txt), found in another paragraph: $(grep -c 'found in paragraph' foreground.txt)" > fancy.txt
# (with no signal the pages of the chapters loaded ahead can't load: each is shown from its copy, and
# highlighted there. They used to be the browser's error page, "its words weren't found on the page")
# (dialogue in a second voice is on: chapter one quotes in double quotes, chapter two in single ones, with an apostrophe)
{ grep -qE "two voices: .*(Tom tried it|It never opens)" logcat.txt && echo "second voice, double quotes (chapter one): yes" || echo "second voice, double quotes (chapter one): NO"
  grep -q "two voices: 2 parts.*Is anyone here" logcat.txt && echo "second voice, single quotes (chapter two): yes" || echo "second voice, single quotes (chapter two): NO"; } > dual.txt
awk '/test: the next audio write fails/{f=1} f' foreground.txt > audiofail.txt
{ grep -q "audio write failed" audiofail.txt && echo "audio output failed once: noticed" || echo "audio output failed once: NOT noticed (test hook not reached?)"
  grep -q "audio write failed" audiofail.txt && { sed -n '/audio write failed/,$p' logcat.txt | grep -q "LoudbookTest: speaking" && echo "reading went on after it: yes" || echo "reading went on after it: NO"; }; } > audio.txt
awk '/=== no signal from here ===/{f=1} f' logcat.txt > nosignal.txt
echo "no signal: chapters shown from their copy: $(grep -c 'shown from the copy read ahead' nosignal.txt), sentences not found on the page: $(grep -c "words weren't found" nosignal.txt)" > offline.txt
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
# text shared from another app (no link): read out
adb shell log -t LoudbookTest "=== shared text ==="
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Mr. Hale walked to the market on a cold morning. He bought 3 apples and a loaf of bread before heading home.'"
sleep 20
# a new chapter comes out for the story read to the end: the background check notices it
adb shell log -t LoudbookTest "=== new chapter published ==="
sed -i 's#<nav>#<nav><a href="ch4.html">Next chapter</a> #' test/chapters/ch3.html
sed 's/Chapter Three: The House/Chapter Four: The Kettle/; s#ch2.html">Previous#ch3.html">Previous#' test/chapters/ch3.html > test/chapters/ch4.html
adb shell dumpsys jobscheduler | grep -i "loudbook" | head -5 > jobs.txt
adb shell cmd jobscheduler run -f com.loudbook.app 4711
sleep 25
adb shell dumpsys notification --noredact | grep -o "New chapter[^,]*" | head -3 | sed 's/^/notification: /' > newchapter.txt
# reopened after the new chapter came out: play with nothing open goes on to the new chapter
adb shell am force-stop com.loudbook.app
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell log -t LoudbookTest "=== reopened after the new chapter, play with nothing open ==="
adb shell am start -n com.loudbook.app/.MainActivity
sleep 25
adb shell input tap $((W * 46 / 100)) $((H * 89 / 100))          # the big play button
sleep 25
adb logcat -d > logcat.txt
grep -o "resumed with the next chapter: [^ ]*" logcat.txt | head -1 | sed 's/^/new chapter on play: /' >> newchapter.txt
# reopened from Recents after a share: Android hands back the share that first opened it; it mustn't be read out again
adb shell am force-stop com.loudbook.app
adb shell log -t LoudbookTest "=== reopened from Recents after a share ==="
adb shell am start -n com.loudbook.app/.MainActivity -f 0x10100000 -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Pelicans circled the harbour wall all afternoon.'"
sleep 15
adb logcat -d > logcat.txt
sed -n '/=== reopened from Recents after a share/,$p' logcat.txt > recents-log.txt
grep -q "opened again with its first intent" recents-log.txt && ! grep -q "Pelicans" recents-log.txt && echo "reopened from Recents: the old share not read again: yes" > recents.txt || {
  echo "reopened from Recents: the old share not read again: NO ($(wc -l < recents-log.txt) log lines after the marker; the last of them:)" > recents.txt
  grep -E "LoudbookTest|Loudbook|ActivityTaskManager|AndroidRuntime" recents-log.txt | tail -12 | sed 's/^/    /' >> recents.txt
  tail -3 logcat.txt | sed 's/^/    (logcat ends) /' >> recents.txt; }
# the start page gets "Continue listening" from LoudbookNative.home() (only the start page itself may ask, 2026-11-04)
grep -q "start page: last story there" logcat.txt && ! grep -q "isn't the start page" logcat.txt && echo "start page shows the last story: yes" >> recents.txt || echo "start page shows the last story: NO" >> recents.txt
