"""Voice commands: prepares the models the app downloads (keyword spotter + speaker check) and
tests them with synthetic speakers made by Kokoro. Writes out/ (release files) and results.md.

  python3 prepare.py KWS_DIR KOKORO_DIR SPEAKER_MODEL...
"""
import os, sys, glob, shutil, json, time
import numpy as np, sherpa_onnx, sentencepiece as spm

KWS, KOKORO, SPEAKERS = sys.argv[1], sys.argv[2], sys.argv[3:]
WORDS = ["play", "pause", "back", "forward", "beginning", "end"]
# every command starts with the app's name ("Loudbook, pause"), so a story, the TV or a
# conversation saying "pause" or "back" on its own never counts
PREFIX = "LOUD BOOK"
os.makedirs("out", exist_ok=True)
R = ["## Voice commands test", ""]

def pick(pattern):
    c = sorted(f for f in glob.glob(os.path.join(KWS, pattern)) if "int8" not in f)
    return c[0]

enc, dec, joi = pick("encoder-*.onnx"), pick("decoder-*.onnx"), pick("joiner-*.onnx")
for src, dst in [(enc, "kws-encoder.onnx"), (dec, "kws-decoder.onnx"), (joi, "kws-joiner.onnx"), (f"{KWS}/tokens.txt", "kws-tokens.txt")]:
    shutil.copy(src, f"out/{dst}")

# keywords, written as the model's BPE pieces. Short words get a bigger boost and a lower bar.
sp = spm.SentencePieceProcessor(); sp.load(f"{KWS}/bpe.model")
def kw_lines(boost, thr):
    out = []
    for w in WORDS:
        pieces = sp.encode(PREFIX + " " + w.upper(), out_type=str)
        b = boost
        out.append(f"{' '.join(pieces)} :{b:.1f} #{thr:.2f} @{w}")
    return out

def spotter(kwfile, thr=0.25, score=1.0):
    return sherpa_onnx.KeywordSpotter(tokens=f"{KWS}/tokens.txt", encoder=enc, decoder=dec, joiner=joi,
        num_threads=1, keywords_file=kwfile, keywords_score=score, keywords_threshold=thr, max_active_paths=4, provider="cpu")

def spot(k, audio16):
    s = k.create_stream(); found = []
    pad = np.zeros(int(0.6 * 16000), np.float32)
    s.accept_waveform(16000, np.concatenate([pad, audio16, pad])); s.input_finished()
    while k.is_ready(s):
        k.decode_stream(s); r = k.get_result(s)
        if r: found.append(r); k.reset_stream(s)
    return found

# synthetic speakers from Kokoro
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f"{KOKORO}/model.onnx", voices=f"{KOKORO}/voices.bin",
        tokens=f"{KOKORO}/tokens.txt", lexicon=f"{KOKORO}/lexicon-us-en.txt", data_dir=f"{KOKORO}/espeak-ng-data"),
    num_threads=4), max_num_sentences=1))
def say(text, sid, speed=1.0):
    a = tts.generate(text, sid=sid, speed=speed); x = np.array(a.samples, np.float32)
    n = int(len(x) * 16000 / a.sample_rate)
    y = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return y + np.random.RandomState(len(text)).randn(len(y)).astype(np.float32) * 0.003
VOICES = {"Michael (am)": 16, "Heart (af)": 3, "George (bm)": 26, "Bella (af)": 2, "Fenrir (am)": 14, "Emma (bf)": 21}

R += ["### Keyword spotting", "", "| setting | words found (of %d) | wrong word | false hits (story + bare words without Loudbook) |" % (len(VOICES) * len(WORDS) * 2), "|---|---|---|---|"]
takes = {(v, w, sp_): say("Loud book, " + w + ".", sid, sp_) for v, sid in VOICES.items() for w in WORDS for sp_ in (0.9, 1.15)}
bare = [say(w.capitalize() + ".", sid, 1.0) for sid in (3, 16) for w in WORDS]
story = ("He went back to the beginning of the road and waited. At the end of the day, they would play the old songs, "
         "and she would pause before the last verse. Forward, he thought. Always forward. The children played by the river. "
         "She came back with two cups and set them at the end of the table.")
story_audio = say(story, 3)
best = None
per_word = {}
for boost, thr in [(0.5, 0.25), (1.0, 0.25), (0.5, 0.15), (1.0, 0.15), (1.0, 0.10), (0.5, 0.08), (1.0, 0.05), (2.0, 0.10)]:
    f = f"/tmp/kw-{boost}-{thr}.txt"; open(f, "w").write("\n".join(kw_lines(boost, thr)) + "\n")
    k = spotter(f, thr)
    ok = wrong = 0; pw = {w: 0 for w in WORDS}
    for (v, w, _), a in takes.items():
        got = spot(k, a)
        if w in got: ok += 1; pw[w] += 1
        wrong += sum(1 for g in got if g != w)
    per_word[(boost, thr)] = pw
    fa = spot(k, story_audio) + [g for a in bare for g in spot(k, a)]
    R.append(f"| boost {boost}, threshold {thr} | {ok} | {wrong} | {len(fa)} ({', '.join(fa)}) |")
    score = ok - 3 * wrong - 3 * len(fa)
    if best is None or score > best[0]: best = (score, boost, thr)
_, boost, thr = best
lines = kw_lines(boost, thr)

# short words are harder: tune each word on its own, keeping the others as chosen
R += ["", "Per-word tuning (found / wrong word hits caused):", ""]
for wi, w in enumerate(WORDS):
    pieces = " ".join(sp.encode(PREFIX + " " + w.upper(), out_type=str))
    best_w = None
    for b, t in [(boost, thr), (0.0, 0.05), (0.3, 0.03), (0.5, 0.02), (0.0, 0.02), (0.8, 0.01)]:
        trial = list(lines); trial[wi] = f"{pieces} :{b:.1f} #{t:.2f} @{w}"
        f = f"/tmp/kw-{w}-{b}-{t}.txt"; open(f, "w").write("\n".join(trial) + "\n")
        k = spotter(f, thr)
        found = wrong = 0
        for (v, w2, _), a in takes.items():
            got = spot(k, a)
            if w2 == w and w in got: found += 1
            if w2 != w and w in got: wrong += 1
        for a in bare: wrong += spot(k, a).count(w)            # the bare word, without "Loudbook"
        R.append(f"- {w} boost {b} threshold {t}: {found} / {wrong}")
        sc = found - 4 * wrong
        if best_w is None or sc > best_w[0]: best_w = (sc, trial[wi])
    lines[wi] = best_w[1]
open("out/keywords.txt", "w").write("\n".join(lines) + "\n")
R += ["", f"Chosen: boost {boost}, threshold {thr}. Found per word (of {len(VOICES) * 2}): " + ", ".join(f"{w} {n}" for w, n in per_word[(boost, thr)].items()), "", "```", open("out/keywords.txt").read().strip(), "```", ""]

# speaker check: enrol one voice from its six words, then score other takes vs other voices
R += ["### Speaker check", "", "| model | dim | same speaker (min / mean) | other speakers (max / mean) | gap | ms per check |", "|---|---|---|---|---|---|"]
def norm(e): e = np.array(e, np.float32); return e / (np.linalg.norm(e) + 1e-9)
chosen = None
for path in SPEAKERS:
    try:
        ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=1))
    except Exception as e:
        R.append(f"| {os.path.basename(path)} | failed: {str(e)[:80]} | | | | |"); continue
    def emb(a):
        s = ex.create_stream(); s.accept_waveform(16000, a); s.input_finished(); return norm(ex.compute(s))
    same, other = [], []; t0 = time.time(); n = 0
    for v, sid in VOICES.items():
        prof = norm(np.mean([emb(takes[(v, w, 0.9)]) for w in WORDS], 0))
        for (v2, w, sp_), a in takes.items():
            if sp_ != 1.15: continue
            sc = float(np.dot(prof, emb(a))); n += 1
            (same if v2 == v else other).append(sc)
    ms = (time.time() - t0) * 1000 / max(1, n + len(VOICES) * len(WORDS))
    gap = min(same) - max(other)
    R.append(f"| {os.path.basename(path)} | {ex.dim} | {min(same):.2f} / {np.mean(same):.2f} | {max(other):.2f} / {np.mean(other):.2f} | {gap:.2f} | {ms:.0f} |")
    if chosen is None or (np.mean(same) - np.mean(other)) > chosen[0]: chosen = (np.mean(same) - np.mean(other), path)
if chosen:
    shutil.copy(chosen[1], "out/speaker.onnx")
    R += ["", f"Chosen speaker model: {os.path.basename(chosen[1])}"]
json.dump({"words": WORDS, "boost": boost, "threshold": thr, "speaker": os.path.basename(chosen[1]) if chosen else None},
          open("out/commands.json", "w"))
open("results.md", "w").write("\n".join(R) + "\n")
print("\n".join(R))
