#!/usr/bin/env python3
"""Reads the emulator's log and the stand-in Google's notes (test/gemini/run.sh) and writes what
happened: how soon reading started, what read what, any stalls, and how far each sentence's cut is
from where it really starts in the recording."""
import re, sys
from datetime import datetime

log = open(sys.argv[1], errors="replace").read().splitlines()
truth = [l.rstrip("\n").split("\t") for l in open(sys.argv[2], errors="replace")] if len(sys.argv) > 2 else []
reqlog = open(sys.argv[3], errors="replace").read() if len(sys.argv) > 3 else ""
def ts(line):
    m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp() if m else None
norm = lambda s: re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()

parts, cur = [], None
for l in log:
    if "LoudbookTest" not in l: continue
    m = re.search(r"=== (.*) ===", l)
    if m: cur = {"name": m.group(1), "lines": []}; parts.append(cur); continue
    if cur is not None: cur["lines"].append(l)

used = set()
bad = 0
out = []
for p in parts:
    out.append(f"### {p['name']}")
    L = p["lines"]
    t0 = ts(L[0]) if L else 0
    # (from the last "play" in the first few seconds: the test's own switch to the stand-in Google
    # replays the chapter that was on for a moment first, and its sound came after 19 ms)
    plays = [i for i, l in enumerate(L) if "trail: play:" in l and ts(l) is not None and ts(l) - t0 < 3]
    first = [l.split("LoudbookTest: ")[1] for l in L[(plays[-1] if plays else 0):] if "start: first sound" in l]
    out.append("- " + (first[0] if first else "NO SOUND"))
    # (a start that waits far longer than Google takes for its first audio: sentences left waiting on a request that
    # doesn't hold them, 2026-10-30. The stand-in takes ~8 s streamed, ~30 s all at once)
    waits = [int(m.group(1)) for x in first for m in [re.search(r"after (\d+) ms", x)] if m]
    if waits and max(waits) > 45000: out.append(f"- PROBLEM: {max(waits) / 1000:.0f} s of silence at the start"); bad += 1
    for l in L:
        if any(k in l for k in ("gemini timing:", "not ready at sentence", "ready again from", "starting from sentence", "isn't in the recording", "playing as it arrives", "played as it arrived", "waiting for Google's audio")):
            out.append(f"- {ts(l) - t0:6.1f} s  " + l.split("LoudbookTest: ")[1])
    # (the stand-in Google never leaves a sentence out: a sentence "not in the recording" means a
    # request started past one that wasn't recorded yet, 2026-10-31)
    # (the stand-in Google never fails: a request that "failed" is the app throwing its recording away,
    # "16-16" with nothing made out by the recogniser, 2026-10-31)
    fails = [l.split("LoudbookTest: ")[1] for l in L if re.search(r"gemini: the request for sentences .* failed", l)]
    for x in fails: out.append("- PROBLEM: " + x); bad += 1
    gaps = [l.split("LoudbookTest: ")[1] for l in L if "isn't in the recording" in l]
    if gaps: out.append(f"- PROBLEM: {len(gaps)} sentence(s) skipped by the requests and read by the phone's voice"); bad += 1
    # (a request for just the last sentence or two, straight after one that stopped short of them: a request of
    # its own, and its wait, for a few seconds of audio. "16-16" after "7-15", gemini-test 2026-10-31)
    prev, stopped = None, False
    for l in L:
        if "stopped early" in l or "was left out" in l: stopped = True
        m = re.search(r"asked Google for sentences (\d+)-(\d+)", l)
        if not m: continue
        a, z = int(m.group(1)), int(m.group(2))
        if prev is not None and a == prev + 1 and z - a <= 1 and not stopped:
            out.append(f"- PROBLEM: a request just for sentence(s) {a}-{z}, left over by the one before"); bad += 1
        prev, stopped = z, False
    # (the first request waited for the recogniser to load: asked 7.6 s after play, gemini-test 2026-10-31.
    # It loads while Google makes the start of the recording now)
    asks = [int(m.group(1)) for l in L for m in [re.search(r"gemini: asking Google for sentences .*, (\d+) ms after play", l)] if m]
    if asks and asks[0] > 4000: out.append(f"- PROBLEM: the first request went {asks[0] / 1000:.1f} s after play"); bad += 1
    # (a highlight put in after the voice had got to its sentence: the title stayed highlighted 5 s into the
    # first sentence, as a sentence was highlighted only once the one after it was heard, gemini-test 2026-10-31)
    late = [(float(m.group(1)), l) for l in L for m in [re.search(r"trail: hearing .*found ([\d.]+) s after the voice got there", l)] if m]
    # (the first time on a phone the listener is fetched while Google makes the recording, so the first
    # highlights can come late by up to that wait: a note then)
    waits = [float(m.group(1)) for l in L for m in [re.search(r"the listener ready after ([\d.]+) s", l)] if m]
    big = [x for x in late if x[0] > 3 + (max(waits) if waits else 0)]
    if big: out.append(f"- PROBLEM: {len(big)} highlight(s) more than 3 s late, the worst {max(x[0] for x in big):.1f} s: " + big[0][1].split("trail: ")[1][:90]); bad += 1
    elif late: out.append(f"- note: {len(late)} highlight(s) a little late, the worst {max(x[0] for x in late):.1f} s")
    # (the recogniser should hear the start of each recording, so its first sentence is timed by its
    # words. It still misses one now and then, so this is a note, not a problem)
    unheard = [l for l in L if re.search(r"hearing \d+ .*part \d+, 0\.0-[\d.]+ s, by length", l)]
    if unheard: out.append(f"- note: {len(unheard)} recording(s) whose first words the recogniser didn't hear")
    # what read each sentence, and stalls (a sentence heard later than the one before it could have ended)
    # (per chapter: sentence numbers start again after a page turn)
    chap, clips, heard = 0, {}, []
    for l in L:
        if "LoudbookTest: turned to " in l: chap += 1
        m = re.search(r"clip (\d+): ([\d.]+) s(?:, (.*))?$", l)
        if m: clips[(chap, int(m.group(1)))] = (float(m.group(2)), m.group(3) or "")
        m = re.search(r"LoudbookTest: speaking (\d+):", l)
        if m: heard.append((ts(l), (chap, int(m.group(1)))))
    stalls = []
    for (ta, a), (tb, b) in zip(heard, heard[1:]):
        if b == (a[0], a[1] + 1) and a in clips and tb - ta > clips[a][0] + 1.1: stalls.append((tb - ta - clips[a][0], f"{a[1]}->{b[1]}: {tb - ta - clips[a][0]:.1f} s"))
    src = " | ".join("".join("G" if "gemini" in v[1] else "p" if "phone" in v[1] else "?" for k, v in sorted(clips.items()) if k[0] == c) for c in range(chap + 1))
    out.append(f"- sentences read, per chapter (G Gemini, p phone): {src}")
    # (a sentence logged as neither: Gemini was taken for off, which it never is in this test)
    if "?" in src: out.append("- PROBLEM: sentences read as if Gemini were off (?)"); bad += 1
    out.append(f"- stalls: {', '.join(x[1] for x in stalls) if stalls else 'none'}")
    # (a few seconds at the very start is the emulator's slow phone voice; a long one is a real problem)
    if any(x[0] > 8 for x in stalls): bad += 1
    # cuts against the truth: each block of "gemini cut" lines is one request
    blocks, blk = [], []
    for l in L:
        m = re.search(r"gemini cut (\d+): ([\d.]+) ([\d.]+) \| (.*)$", l)
        if m: blk.append((int(m.group(1)), float(m.group(2)), m.group(4)))
        elif blk: blocks.append(blk); blk = []
    if blk: blocks.append(blk)
    errs = []
    for blk in blocks:
        reqs = {}
        for n, t, s in truth: reqs.setdefault(n, []).append((float(t), norm(s)))
        pick = None
        for n, rows in sorted(reqs.items(), key=lambda x: int(x[0])):
            if n in used: continue
            if any(norm(blk[0][2]).startswith(r[1]) for r in rows[:2]): pick = n; break
        if pick is None: out.append(f"- (no stand-in request found for cuts from sentence {blk[0][0]})"); continue
        used.add(pick)
        rows = reqs[pick]
        for idx, t, say in blk:
            cand = [r[0] for r in rows if norm(say).startswith(r[1])]
            if cand: errs.append((min((c - t for c in cand), key=abs) * -1, idx, say[:60]))
    if errs:
        big = [e for e in errs if abs(e[0]) > 0.6]
        mean = sum(abs(e[0]) for e in errs) / len(errs)
        out.append(f"- sentence cuts: {len(errs)} checked, mean {mean:.2f} s from the true start, {len(big)} off by more than 0.6 s")
        for e in sorted(errs, key=lambda e: -abs(e[0]))[:5]: out.append(f"    {e[0]:+.2f} s  #{e[1]} {e[2]}")
        if big: bad += 1
    else: out.append("- sentence cuts: none logged"); bad += 1
    if not first: bad += 1
out.append("")
out.append("Stand-in Google's requests:")
out += ["    " + l for l in reqlog.splitlines()]
out.append("")
out.append("RESULT: " + ("all good" if bad == 0 else f"{bad} problem(s)"))
print("\n".join(out))
