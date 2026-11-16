"""The audiobook studio (pc-server/studio.html + /api/*), with stand-in voices: books listed, a chapter's sentences, one
sentence recorded again (the MP3 and the timings after it change to match, new words go into the chapter's text), the
book's voice changed, a whole chapter recorded again (the old one kept in _replaced), and other sites refused.
Run from the repo: python3 pc-server/test/studio_test.py (needs numpy, soundfile, cryptography)."""
import json, os, subprocess, sys, tempfile, time, urllib.request

T = tempfile.mkdtemp()
PORT, STUDIO = 8797, 8799
env = dict(os.environ, LOUDBOOK_STATE=T + "/state", LOUDBOOK_PORT=str(PORT), LOUDBOOK_STUDIO_PORT=str(STUDIO),
           LOUDBOOK_FAKE_BEST="1", LOUDBOOK_FAKE_BEST_SECS="0.05", LOUDBOOK_BOOKS=T + "/books", LOUDBOOK_FAIL_WAIT="1")
os.makedirs(T + "/books/text")
with open(T + "/books/text/1.json", "w") as f:
    json.dump([["First sentence of the book.", "Another one here."], [["Say it, Tavi!", "Tavi"]], ["The end of it."]], f)
with open(T + "/books/text/2.json", "w") as f: json.dump([["Chapter two begins here."]], f)
with open(T + "/books/test.job.json", "w") as f:
    json.dump({"title": "Test Book", "voice": 3, "out": "Test Book", "chapters": [{"name": "One", "file": "text/1.json"}, {"name": "Two", "file": "text/2.json"}]}, f)
with open(T + "/books/0 voice sample 1.job.json", "w") as f:
    json.dump({"title": "Voice samples", "voice": "s17/e0.75", "out": "Voice samples", "chapters": [{"name": "1 - Onyx, expressive", "file": "text/2.json", "say_title": False}]}, f)
# two broken jobs named before the real one: a chapter that isn't an object (ended the recording thread for good) and a
# chapter whose text file is missing (retried every minute forever, holding up every later book)
with open(T + "/books/0 broken.job.json", "w") as f: json.dump({"title": "Broken", "chapters": ["text/1.json", None]}, f)
with open(T + "/books/0 missing.job.json", "w") as f: json.dump({"title": "Missing", "chapters": [{"name": "Gone", "file": "text/nope.json"}]}, f)
here = os.path.dirname(os.path.abspath(__file__))
srv = subprocess.Popen([sys.executable, "-u", os.path.join(here, "..", "loudbook_server.py"), "serve", "--fake"], env=env,
                       stdout=open(T + "/server.log", "w"), stderr=subprocess.STDOUT)
fails = []
def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what); (None if ok else fails.append(what))
def call(path, body=None, headers=None, host=None):
    h = {"Content-Type": "application/json", "X-Loudbook": "studio"} if body is not None else {}
    h.update(headers or {})
    if host: h["Host"] = host
    r = urllib.request.Request(f"http://127.0.0.1:{STUDIO}{path}", data=None if body is None else json.dumps(body).encode(), headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as x: return x.status, x.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
def js(path, body=None): c, b = call(path, body); return json.loads(b)
def wait(cond, secs=60):
    end = time.time() + secs
    while time.time() < end:
        try:
            if cond(): return True
        except Exception: pass
        time.sleep(0.5)
    return False
try:
    check("studio up", wait(lambda: call("/")[0] == 200, 30))
    check("both chapters and the sample recorded (broken jobs before them passed over)",
          wait(lambda: all(b["done"] == b["total"] for b in js("/api/books")["books"] if b["title"] in ("Test Book", "Voice samples")), 90))
    log = open(T + "/server.log").read()
    check("the missing chapter tried 3 times, then passed over", log.count("audiobook chapter failed") == 3)
    check("the bad job left out, said once", log.count("broken.job.json: chapter") == 1)
    lib = js("/api/books")
    check("books listed, sample marked", sorted((b["title"], b["sample"]) for b in lib["books"] if b["title"] != "Missing") == [("Test Book", False), ("Voice samples", False if False else True)])
    check("phone voice names read from VoiceNames.java", any(v["id"] == 3 and v["name"].startswith("Heart") for v in lib["kokoro"]))
    check("book voice named", next(b for b in lib["books"] if b["title"] == "Test Book")["voiceName"].startswith("Heart"))
    check("sample listed with its voice", lib["samples"] and lib["samples"][0]["voiceName"].startswith("Onyx") and lib["samples"][0]["recorded"])
    c = js("/api/chapter?job=test.job.json&k=0")
    before = [(s["start"], s["end"]) for s in c["sentences"]]
    check("chapter one: title + 4 sentences", len(c["sentences"]) == 5 and c["recorded"])
    import glob as _g
    made = json.load(open(_g.glob(T + "/books/Test Book/0001 - *.json")[0], encoding="utf-8"))
    check("the chapter's .json says its engine and each sentence's voice (the phone's Voice button)",
          made.get("engine") in ("Kokoro", "Chatterbox (best quality)") and all("voice" in x for x in made["sentences"]))
    code, mp3 = call("/audio?job=test.job.json&k=0", headers={"Range": "bytes=0-99"})
    check("audio answers a range", code == 206 and len(mp3) == 100)
    # one sentence, new words: a longer take -> later sentences move by the same amount
    t = js("/api/redo", {"job": "test.job.json", "k": 0, "i": 3, "text": "Say it, Tah-vee, say it again and again!"})
    check("re-record queued", "task" in t)
    check("re-record done", wait(lambda: js("/api/tasks")["tasks"][t["task"]]["state"] == "done", 30))
    c2 = js("/api/chapter?job=test.job.json&k=0")
    s3 = c2["sentences"][3]
    check("new words kept, marked recorded again", s3["text"].startswith("Say it, Tah-vee") and s3["redone"] > 0)
    grow = (s3["end"] - s3["start"]) - (before[3][1] - before[3][0])
    check("re-recorded take is longer", grow > 0.3)
    check("sentence before unchanged", (c2["sentences"][2]["start"], c2["sentences"][2]["end"]) == before[2])
    check("sentence after moved by the same", abs(c2["sentences"][4]["start"] - (before[4][0] + grow)) < 0.01)
    import soundfile as sf
    info = sf.info(T + "/books/Test Book/0001 - One.mp3")
    check("MP3 length matches the timings", abs(info.duration - c2["seconds"]) < 0.06)
    with open(T + "/books/text/1.json") as f: txt = json.load(f)
    check("chapter text has the new words (speaker kept)", txt[1][0] == ["Say it, Tah-vee, say it again and again!", "Tavi"])
    # voice
    check("bad voice refused", call("/api/voice", {"job": "test.job.json", "voice": "nonsense"})[0] == 400)
    check("voice set", call("/api/voice", {"job": "test.job.json", "voice": "s17/e0.75"})[0] == 200)
    with open(T + "/books/test.job.json") as f: check("job file has the voice", json.load(f)["voice"] == "s17/e0.75")
    # whole chapter again
    t = js("/api/redo-chapter", {"job": "test.job.json", "k": 1})
    check("chapter re-record done", wait(lambda: js("/api/tasks")["tasks"][t["task"]]["state"] == "done", 30))
    rep = os.path.join(T, "books", "Test Book", "_replaced")
    check("old chapter kept in _replaced", os.path.isdir(rep) and any(f.endswith(".mp3") for d in os.listdir(rep) for f in os.listdir(os.path.join(rep, d))))
    check("chapter recorded again with the new voice", wait(lambda: js("/api/chapter?job=test.job.json&k=1")["recorded"]
                                                         and "Onyx" in js("/api/chapter?job=test.job.json&k=1")["sentences"][0]["voice"], 60))
    # refused
    check("POST without the studio header refused", call("/api/voice", {"job": "test.job.json", "voice": 3}, headers={"X-Loudbook": "x"})[0] == 403)
    check("other host name refused", call("/api/books", host="evil.example:%d" % STUDIO)[0] == 403)
    check("job outside the folder refused", call("/api/chapter?job=../x.job.json&k=0")[0] == 404)
    t = js("/api/redo", {"job": "test.job.json", "k": 0, "i": 99})
    check("re-record of a missing sentence fails cleanly", wait(lambda: js("/api/tasks")["tasks"][t["task"]]["state"] == "failed", 30)
          and "no such sentence" in js("/api/tasks")["tasks"][t["task"]]["msg"])
    # a file held open on Windows (the page streaming the MP3): os.replace fails for a while; swap_in waits for it
    sys.path.insert(0, os.path.join(here, ".."))
    os.environ.setdefault("LOUDBOOK_STATE", T + "/state2")
    import loudbook_server as ls
    real, tries = os.replace, []
    def held(a, b):
        tries.append(1)
        if len(tries) <= 3: raise PermissionError(13, "in use")
        real(a, b)
    with open(T + "/x.part", "w") as f: f.write("new")
    ls.os.replace = held
    try:
        ls.swap_in(T + "/x.part", T + "/x", wait=5)
        check("swap_in waits for a held file", open(T + "/x").read() == "new" and len(tries) == 4)
        with open(T + "/y.part", "w") as f: f.write("new")
        def always(a, b): raise PermissionError(13, "in use")
        ls.os.replace = always
        try: ls.swap_in(T + "/y.part", T + "/y", wait=0.6); check("swap_in gives up", False)
        except PermissionError: check("swap_in gives up and removes its part file", not os.path.exists(T + "/y.part"))
    finally:
        ls.os.replace = real
    # a chapter whose name has ".part" in it ("Vol 2.part 1") is finished like any other: listed and handed out
    # (file_for refused any name containing ".part", so the phone listed it and then failed to copy it)
    ls.BOOKS = T + "/books2"
    os.makedirs(T + "/books2/B")
    for n in ("0001 - Vol 2.part 1.mp3", "0001 - Vol 2.part 1.json", "0002 - Two.part.mp3", "0003 - x.json.part"):
        with open(T + "/books2/B/" + n, "w") as f: f.write("x")
    listed = [x["p"] for b in ls.Books.finished() for x in b["files"]]
    check("'.part' in a chapter's name: listed", listed == ["B/0001 - Vol 2.part 1.json", "B/0001 - Vol 2.part 1.mp3"])
    check("'.part' in a chapter's name: every listed file handed out", all(ls.Books.file_for(x) for x in listed))
    check("half-made files still refused", ls.Books.file_for("B/0002 - Two.part.mp3") is None and ls.Books.file_for("B/0003 - x.json.part") is None)
    # (Windows opens "X.PART.mp3" as the half-made "x.part.mp3": refused whatever its case)
    with open(T + "/books2/B/0004 - Three.PART.mp3", "w") as f: f.write("x")
    check("half-made files refused in capitals too", ls.Books.file_for("B/0004 - Three.PART.mp3") is None)
finally:
    srv.kill()
if fails:
    print(open(T + "/server.log").read()[-3000:])
    print(len(fails), "FAILED"); sys.exit(1)
print("STUDIO TEST OK")
