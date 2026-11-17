"""An audiobook job edited while one of its chapters is being recorded (2026-11-17): the chapter is written where it is in
the job now (a chapter before it removed: one place up; it was written at its old place, left there beside its new one, and
recorded again), and not at all if it was taken out of the job or the job was removed (cancelled).
Run from the repo: python3 pc-server/test/job_edit_test.py (needs numpy; soundfile is stood in for)."""
import json, os, sys, tempfile, types

T = tempfile.mkdtemp()
os.environ["LOUDBOOK_STATE"] = T + "/state"; os.environ["LOUDBOOK_BOOKS"] = T + "/books"
sf = types.ModuleType("soundfile")
class SF:
    def __init__(s, p, *a, **k): s.p = p
    def __enter__(s): return s
    def __exit__(s, *a): pass
    def write(s, x): open(s.p, "wb").write(x.tobytes())
sf.SoundFile = SF; sys.modules["soundfile"] = sf
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import loudbook_server as ls

B = ls.BOOKS; os.makedirs(B + "/text")
for i in range(4): json.dump([[f"Text of chapter number {i}."]], open(f"{B}/text/{i}.json", "w"))
def job(chs, name="b.job.json"): json.dump({"title": "Bk", "voice": 3, "chapters": chs}, open(os.path.join(B, name), "w"))
A, Bc, C = ({"name": x, "file": f"text/{i + 1}.json"} for i, x in enumerate("ABC"))
fb = ls.FakeBest()
fails = []
def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok: fails.append(what)

def until(k):
    while True:
        n, j, kk = ls.Books.next_chapter()
        if kk == k: return n, j, kk
        ls.Books.make_chapter(fb, n, j, kk)

# 1. B removed while C (third) is recorded: C written as the second chapter, nothing at its old place, nothing to redo
job([A, Bc, C]); n, j, k = until(2)
job([A, C]); ls.Books.make_chapter(fb, n, j, k)
fs = sorted(f for f in os.listdir(B + "/Bk") if f.endswith(".mp3"))
check("chapter removed before the one recording: written at its new place " + str(fs), "0002 - C.mp3" in fs and "0003 - C.mp3" not in fs)
check("nothing left to record after it", ls.Books.next_chapter() is None)

# 2. unchanged job: written where it was going
job([A, Bc, C], "c.job.json"); json.dump({"title": "Bk2", "voice": 3, "chapters": [A, Bc, C]}, open(B + "/c.job.json", "w"))
os.remove(B + "/b.job.json")
n, j, k = until(2)
ls.Books.make_chapter(fb, n, j, k)
check("unchanged job: chapter written", os.path.exists(B + "/Bk2/0003 - C.mp3"))

# 3. the chapter taken out of the job, or the job removed, while recording: not written
job([A, Bc, C], "d.job.json"); json.dump({"title": "Bk3", "voice": 3, "chapters": [A, Bc, C]}, open(B + "/d.job.json", "w"))
n, j, k = until(2)
json.dump({"title": "Bk3", "voice": 3, "chapters": [A, Bc]}, open(B + "/d.job.json", "w"))
ls.Books.make_chapter(fb, n, j, k)
check("chapter taken out of the job: not written", not os.path.exists(B + "/Bk3/0003 - C.mp3"))
json.dump({"title": "Bk4", "voice": 3, "chapters": [A, Bc, C]}, open(B + "/e.job.json", "w"))
n, j, k = until(2)
os.remove(B + "/e.job.json")
ls.Books.make_chapter(fb, n, j, k)
check("job removed (cancelled): chapter not written", not os.path.exists(B + "/Bk4/0003 - C.mp3"))

print("FAILED: " + "; ".join(fails) if fails else "all good")
sys.exit(1 if fails else 0)
