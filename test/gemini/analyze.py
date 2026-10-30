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
    first = [l.split("LoudbookTest: ")[1] for l in L if "start: first sound" in l]
    out.append("- " + (first[0] if first else "NO SOUND"))
    for l in L:
        if any(k in l for k in ("gemini timing:", "not ready at sentence", "ready again from", "starting from sentence", "isn't in the recording", "playing as it arrives", "played as it arrived", "waiting for Google's audio")):
            out.append(f"- {ts(l) - t0:6.1f} s  " + l.split("LoudbookTest: ")[1])
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
