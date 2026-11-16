#!/usr/bin/env bash
# Emulator test of going on to the next chapter with Loudbook in the background and the screen
# off: three short chapters served from the runner (test/chapters), reached as http://localhost.
set -x
adb reverse tcp:8000 tcp:8000
adb install -r build/Loudbook.apk
adb shell pm grant com.loudbook.app android.permission.POST_NOTIFICATIONS
adb logcat -G 16M || true
adb logcat -c
# the app's own lines (and crashes, activity starts), written down as they come: the 16 MB ring buffer still wrapped in some
# runs, so every "adb logcat -d" came back without a single Loudbook line, even the test's own markers (ab18225, 2026-11-08:
# every check NO). The checks read this file instead; it is copied to logcat.txt for the results and the crash check.
adb logcat -v threadtime LoudbookTest:V Loudbook:V AndroidRuntime:V ActivityTaskManager:I DEBUG:V libc:F MediaPlayer:E '*:S' > applog.txt 2>/dev/null &
LOGPID=$!
snap() { sleep 2; cp applog.txt logcat.txt; }
adb shell am start -n com.loudbook.app/.MainActivity
sleep 100                                   # the phone voice downloads and loads the first time
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "http://localhost:8000/ch1.html" --ez play true --ez dual true
sleep 8
# the audio output fails once mid-sentence (as when the phone's audio server restarts): reading must go on (2026-11-03: it
# used to stop without a word, still showing "playing")
adb shell am start -n com.loudbook.app/.MainActivity --ez audiofail true
sleep 4
# paused, then a sentence on from the headphones / notification: stays paused there (it started reading, 2026-11-16);
# play goes on from that sentence
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
adb shell log -t LoudbookTest "=== paused, a sentence on ==="
adb shell input keyevent KEYCODE_MEDIA_NEXT
sleep 4
adb shell log -t LoudbookTest "=== play after the step ==="
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell input keyevent KEYCODE_SLEEP
adb shell log -t LoudbookTest "=== app in the background, screen off ==="
sleep 10
# no signal from here on: the chapters loaded ahead must carry it to the end
adb reverse --remove tcp:8000 || true
adb shell log -t LoudbookTest "=== no signal from here ==="
sleep 150
snap
# (chapter one, read with the app open, has fancy letters and an emoji: every sentence must be marked on the page)
awk '/=== app in the background/{exit} 1' logcat.txt > foreground.txt
echo "app open (fancy text, emoji): sentences said: $(grep -a -c 'LoudbookTest: speaking' foreground.txt), not found on the page: $(grep -a -c "words weren't found" foreground.txt), found in another paragraph: $(grep -a -c 'found in paragraph' foreground.txt)" > fancy.txt
# (with no signal the pages of the chapters loaded ahead can't load: each is shown from its copy, and
# highlighted there. They used to be the browser's error page, "its words weren't found on the page")
# (dialogue in a second voice is on: chapter one quotes in double quotes, chapter two in single ones, with an apostrophe)
{ grep -a -qE "two voices: .*(Tom tried it|It never opens)" logcat.txt && echo "second voice, double quotes (chapter one): yes" || echo "second voice, double quotes (chapter one): NO"
  grep -a -q "two voices: 2 parts.*Is anyone here" logcat.txt && echo "second voice, single quotes (chapter two): yes" || echo "second voice, single quotes (chapter two): NO"
  grep -a -qE "two voices: [23] parts.*Hello, garden" logcat.txt && echo "second voice, dashes (chapter two): yes" || echo "second voice, dashes (chapter two): NO"
  grep -a -q "two voices: 2 parts.*Nobody answers" logcat.txt && echo "second voice, guillemets (chapter two): yes" || echo "second voice, guillemets (chapter two): NO"; } > dual.txt
awk '/test: the next audio write fails/{f=1} f' foreground.txt > audiofail.txt
{ grep -a -q "audio write failed" audiofail.txt && echo "audio output failed once: noticed" || echo "audio output failed once: NOT noticed (test hook not reached?)"
  grep -a -q "audio write failed" audiofail.txt && { sed -n '/audio write failed/,$p' logcat.txt | grep -a -q "LoudbookTest: speaking" && echo "reading went on after it: yes" || echo "reading went on after it: NO"; }; } > audio.txt
awk '/=== paused, a sentence on ===/{f=1} /=== play after the step ===/{exit} f' foreground.txt > step.txt
{ grep -a -q "step to [0-9]* while paused: stays paused" step.txt && ! grep -a -q "LoudbookTest: speaking" step.txt \
    && echo "paused, a sentence on (media key): stays paused: yes" || echo "paused, a sentence on (media key): stays paused: NO ($(grep -a -c 'LoudbookTest: speaking' step.txt) sentences heard)"
  awk '/=== play after the step ===/{f=1} f' foreground.txt | grep -a -q "LoudbookTest: speaking" \
    && echo "play after it reads on: yes" || echo "play after it reads on: NO"; } >> audio.txt
awk '/=== no signal from here ===/{f=1} f' logcat.txt > nosignal.txt
echo "no signal: chapters shown from their copy: $(grep -a -c 'shown from the copy read ahead' nosignal.txt), sentences not found on the page: $(grep -a -c "words weren't found" nosignal.txt)" > offline.txt
# cold start: Loudbook closed, opened again, play pressed with nothing open -> carries on with the last story
adb reverse tcp:8000 tcp:8000
adb shell am force-stop com.loudbook.app
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell log -t LoudbookTest "=== reopened, play with nothing open ==="
adb shell am start -n com.loudbook.app/.MainActivity
sleep 25
SIZE=$(adb shell wm size | grep -a -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
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
adb shell dumpsys jobscheduler | grep -a -i "loudbook" | head -5 > jobs.txt
adb shell cmd jobscheduler run -f com.loudbook.app 4711
sleep 25
adb shell dumpsys notification --noredact | grep -a -o "New chapter[^,]*" | head -3 | sed 's/^/notification: /' > newchapter.txt
# reopened after the new chapter came out: play with nothing open goes on to the new chapter
adb shell am force-stop com.loudbook.app
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell log -t LoudbookTest "=== reopened after the new chapter, play with nothing open ==="
adb shell am start -n com.loudbook.app/.MainActivity
sleep 25
adb shell input tap $((W * 46 / 100)) $((H * 89 / 100))          # the big play button
sleep 25
snap
grep -a -o "resumed with the next chapter: [^ ]*" logcat.txt | head -1 | sed 's/^/new chapter on play: /' >> newchapter.txt
# reopened from Recents after a share: Android hands back the share that first opened it; it mustn't be read out again
adb shell am force-stop com.loudbook.app
adb shell log -t LoudbookTest "=== reopened from Recents after a share ==="
adb shell am start -n com.loudbook.app/.MainActivity -f 0x10100000 -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Pelicans circled the harbour wall all afternoon.'"
sleep 15
snap
sed -n '/=== reopened from Recents after a share/,$p' logcat.txt > recents-log.txt
grep -a -q "opened again with its first intent" recents-log.txt && ! grep -a -q "Pelicans" recents-log.txt && echo "reopened from Recents: the old share not read again: yes" > recents.txt || {
  echo "reopened from Recents: the old share not read again: NO ($(wc -l < recents-log.txt) log lines after the marker; the last of them:)" > recents.txt
  grep -a -E "LoudbookTest|Loudbook|ActivityTaskManager|AndroidRuntime" recents-log.txt | tail -12 | sed 's/^/    /' >> recents.txt
  tail -3 logcat.txt | sed 's/^/    (logcat ends) /' >> recents.txt; }
# the start page gets "Continue listening" from LoudbookNative.home() (only the start page itself may ask, 2026-11-04)
grep -a -q "start page: last story there" logcat.txt && ! grep -a -q "isn't the start page" logcat.txt && echo "start page shows the last story: yes" >> recents.txt || echo "start page shows the last story: NO" >> recents.txt
# the PC's audiobook played in Loudbook (BookPlayer + book.html, 2026-11-06): two short chapters pushed into Loudbook's files
# (as BookCopy leaves them, the recordings in its own Audiobooks folder), played from chapter 1, on to chapter 2 by itself
# (its own small log: cleared first, only Loudbook's lines. The whole 16 MB dump came back cut short late in the run,
#  without this section's lines, 2026-11-06; what's before is kept in logcat.txt for the crash check)
adb logcat -c
adb shell log -t LoudbookTest "=== audiobook ==="
BK=/tmp/lbbook; rm -rf $BK; mkdir -p $BK
for n in 1 2; do
  if command -v ffmpeg >/dev/null; then ffmpeg -loglevel error -f lavfi -i "sine=frequency=$((300 * n)):duration=4" -ac 1 -ar 24000 -b:a 48k "$BK/000$n - Part $n.mp3"
  else python3 -c "import wave,math,sys;w=wave.open(sys.argv[1],'wb');w.setnchannels(1);w.setsampwidth(2);w.setframerate(24000);w.writeframes(b''.join(int(8000*math.sin(i*0.08)).to_bytes(2,'little',signed=True) for i in range(96000)))" "$BK/000$n - Part $n.mp3"; fi
  echo "{\"title\": \"Part $n\", \"rate\": 24000, \"seconds\": 4.0, \"sentences\": [{\"p\": 0, \"s\": 0, \"start\": 0.0, \"end\": 1.5, \"text\": \"Part $n\"}, {\"p\": 1, \"s\": 0, \"start\": 2.0, \"end\": 3.9, \"text\": \"A test sentence in part $n.\"}]}" > "$BK/000$n - Part $n.json"
done
D=/sdcard/Android/data/com.loudbook.app/files
adb shell mkdir -p "'$D/books/Test Book'" "'$D/Audiobooks/Test Book'"
for n in 1 2; do adb push "$BK/000$n - Part $n.json" "$D/books/Test Book/"; adb push "$BK/000$n - Part $n.mp3" "$D/Audiobooks/Test Book/"; done
adb shell ls -l "'$D/books/Test Book'" "'$D/Audiobooks/Test Book'" > book-files.txt 2>&1
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb shell am start -n com.loudbook.app/.MainActivity --es bookplay "'Test Book'" > book-start.txt 2>&1
sleep 14
adb shell dumpsys media_session | grep -a -E "Loudbook audiobook|state=PlaybackState" | head -4 > book-session.txt
sleep 6
snap
sed -n '/=== audiobook ===/,$p' logcat.txt > book-log.txt
{
  grep -a -o 'book: test book .*' book-log.txt | head -1
  grep -a -q "book: playing chapter 1 at" book-log.txt && echo "audiobook: chapter 1 plays: yes" || echo "audiobook: chapter 1 plays: NO"
  grep -a -q "book: chapter 1 ended" book-log.txt && grep -a -q "book: playing chapter 2 at" book-log.txt && echo "audiobook: on to chapter 2 by itself: yes" || echo "audiobook: on to chapter 2 by itself: NO"
  grep -a -q "Loudbook audiobook" book-session.txt && echo "audiobook: media session there (headphones, car): yes" || echo "audiobook: media session there: NO"
  grep -a -E "book: (player error|opening|couldn't)" book-log.txt | head -3
  grep -a -q "book: playing chapter 2 at" book-log.txt || {
    echo "  (start: $(tr '\n' ' ' < book-start.txt | cut -c1-200))"; sed 's/^/  (files) /' book-files.txt
    grep -a -E "book|Book|AndroidRuntime" book-log.txt | tail -15 | sed 's/^/  /'; }
} > book.txt
# the audiobook sleep timer set to the chapter's end (2026-11-16: the audiobook player had no sleep timer): chapter 1 ends and
# the player stops there, chapter 2 ready but not playing
adb shell log -t LoudbookTest "=== audiobook sleep timer ==="
adb shell am start -n com.loudbook.app/.MainActivity --es bookplay "'Test Book'" --ez booksleepend true > sleep-start.txt 2>&1
sleep 14
snap
sed -n '/=== audiobook sleep timer ===/,$p' logcat.txt > sleep-log.txt
{ grep -a -q "book: sleep timer: paused at the end of chapter 1" sleep-log.txt && ! grep -a -q "book: playing chapter 2" sleep-log.txt \
    && echo "audiobook: sleep timer at the chapter's end pauses there: yes" \
    || { echo "audiobook: sleep timer at the chapter's end pauses there: NO"; grep -a -E "book" sleep-log.txt | tail -6 | sed 's/^/  /'; }; } >> book.txt
# "Make audio now" (AheadMaker, 2026-11-15): a text book of two short chapters, as the PC sends them (.text.json), made
# ahead on the phone with its voice; then the Audiobooks list must say "made on phone" for both
adb shell log -t LoudbookTest "=== make audio now ==="
AB=/tmp/lbahead; rm -rf $AB; mkdir -p $AB
echo '{"title": "Ahead One", "seconds": 4, "blocks": ["The lighthouse keeper climbed the stairs.", "Rain fell on the sea."]}' > "$AB/0001 - Ahead One.text.json"
echo '{"title": "Ahead Two", "seconds": 4, "blocks": ["The boats came home at dusk."]}' > "$AB/0002 - Ahead Two.text.json"
adb shell mkdir -p "'$D/books/Ahead Book'"
for f in "$AB"/*.json; do adb push "$f" "$D/books/Ahead Book/"; done
adb shell am start -n com.loudbook.app/.MainActivity --es bookahead "'Ahead Book'" > ahead-start.txt 2>&1
for t in $(seq 1 60); do sleep 5; grep -a -q "ahead test: list" applog.txt && break; done
adb shell ls -l "$D/made" > ahead-files.txt 2>&1
snap
sed -n '/=== make audio now ===/,$p' logcat.txt > ahead-log.txt
{
  grep -a -o 'ahead test: "Ahead Book" has .*' ahead-log.txt | head -1
  made=$(grep -a -c -E "ahead: Ahead (One|Two) made" ahead-log.txt)
  [ "$made" = 2 ] && echo "make audio now: both chapters made: yes" || echo "make audio now: both chapters made: NO ($made)"
  grep -a -o 'ahead test: job .*' ahead-log.txt | head -1
  phone=$(grep -a -o 'ahead test: list .*' ahead-log.txt | grep -a -o '"rec":"phone"' | wc -l)
  [ "$phone" = 2 ] && echo "make audio now: both listed as made on phone: yes" || echo "make audio now: both listed as made on phone: NO ($phone)"
  [ "$(grep -a -c '\.m4a' ahead-files.txt)" -ge 2 ] && echo "make audio now: audio files kept: yes" || { echo "make audio now: audio files kept: NO"; sed 's/^/  (files) /' ahead-files.txt; }
  [ "$made" = 2 ] || grep -a -E "ahead|AndroidRuntime" ahead-log.txt | tail -8 | sed 's/^/  /'
} > ahead.txt
# the first made chapter read in the reader: its sentences come from what was made (BookAudio), and the AAC decoder's
# extra samples at the start are measured ("times moved": RecMatch.shift)
adb shell log -t LoudbookTest "=== made chapter played ==="
adb shell am start -n com.loudbook.app/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://book.loudbook.invalid/Ahead%20Book/0001%20-%20Ahead%20One.text.json" --ez play true > made-start.txt 2>&1
sleep 25
snap
sed -n '/=== made chapter played ===/,$p' logcat.txt > made-log.txt
{
  grep -a -o 'book: [0-9]* of [0-9]* sentences are in the .*' made-log.txt | head -1
  grep -a -q -E 'book: ([1-9][0-9]*) of \1 sentences are in' made-log.txt && echo "make audio now: the made chapter plays from what was made: yes" || echo "make audio now: the made chapter plays from what was made: NO"
  grep -a -o 'book: made-ahead chapter decoded: .*' made-log.txt | head -1
} >> ahead.txt
kill $LOGPID 2>/dev/null || true
