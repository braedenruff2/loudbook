#!/bin/bash
# Best quality on the PC, with stand-in voices (no models): the phone's list of sentences is made ahead on the PC and
# then answered from its folder (pc-server/test/AheadTest.java). Needs python3 with cryptography and numpy, and Java.
set -u
cd "$(dirname "$0")/../.."
T=$(mktemp -d)
export LOUDBOOK_STATE=$T/state LOUDBOOK_PORT=8799 LOUDBOOK_FAKE_BEST=1 LOUDBOOK_FAKE_BEST_SECS=0.3 LOUDBOOK_BOOKS=$T/books
# (an audiobook job: two short chapters, made while nothing else needs the voice)
mkdir -p "$T/books/text"
echo '[["First sentence of the book.", "Another one."], ["A new paragraph."]]' > "$T/books/text/1.json"
echo '[["Chapter two begins here."]]' > "$T/books/text/2.json"
echo '{"title": "Test Book", "voice": "phone", "out": "Test Book", "chapters": [{"name": "One", "file": "text/1.json"}, {"name": "Two", "file": "text/2.json", "say_title": false}]}' > "$T/books/test.job.json"
# (a Kindle book beside it, where the Whizzer Library download keeps them: listed only when asked for)
mkdir -p "$T/Whizzer Library Downloader/Whizzer Library/Kindle/Test Book Rise of Tests"
head -c 300000 /dev/urandom > "$T/Whizzer Library Downloader/Whizzer Library/Kindle/Test Book Rise of Tests/Test Book 01 - Volume 1.epub"
echo x > "$T/Whizzer Library Downloader/Whizzer Library/Kindle/Test Book Rise of Tests/notes.txt"
PY=${PYTHON:-python3}
javac -nowarn -d "$T/out" app/src/com/loudbook/app/PcLink.java pc-server/test/AheadTest.java || exit 1
$PY -u pc-server/loudbook_server.py serve --fake > "$T/server.log" 2>&1 &
SP=$!
for i in $(seq 1 30); do sleep 0.5; grep -q "ready on port" "$T/server.log" && break; done
$PY -u pc-server/loudbook_server.py pair > "$T/pair.log" 2>&1 &
PP=$!
for i in $(seq 1 20); do sleep 0.5; grep -q "Code:" "$T/pair.log" && break; done
CODE=$(sed -n 's/.*Code:[[:space:]]*\([A-Z0-9-]*\).*/\1/p' "$T/pair.log")
java -cp "$T/out" com.loudbook.app.AheadTest 8799 "$CODE"; rc=$?
kill $SP $PP 2>/dev/null
[ $rc -eq 0 ] || { echo "--- server log"; cat "$T/server.log"; }
exit $rc
