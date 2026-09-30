"""Kokoro speed on an ARM64 CPU (GitHub's arm runner, Neoverse cores, close to a phone's big cores):
fp32 vs int8 models made from the same v1.0 model, at 1/2/4 threads, plus how close int8 sounds to
fp32 (log-mel similarity). Results go to the job summary."""
import os, sys, time, json, shutil
import numpy as np, sherpa_onnx

K = sys.argv[1]
OUT = os.environ.get("GITHUB_STEP_SUMMARY", "/dev/stdout")
TEXTS = [
    "The door creaked open, and a thin line of lamplight spilled across the floor.",
    "She hadn't slept in two days, but the numbers on the page refused to add up.",
    "“You're late,” he said, without looking up from the map.",
    "Outside, the rain kept falling, patient and cold, as if it had all the time in the world.",
]

def make_int8(variant):
    from onnxruntime.quantization import quantize_dynamic, QuantType
    import onnx
    src = f"{K}/model.onnx"; dst = f"/tmp/model.{variant}.onnx"
    kw = dict(weight_type=QuantType.QUInt8 if variant.endswith("u8") else QuantType.QInt8)
    if variant.startswith("mm"): kw["op_types_to_quantize"] = ["MatMul", "Gemm", "LSTM"]
    quantize_dynamic(src, dst, **kw)
    m = onnx.load(dst); o = onnx.load(src)
    del m.metadata_props[:]; m.metadata_props.extend(o.metadata_props); onnx.save(m, dst)
    return dst

def tts(model, threads):
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=model, voices=f"{K}/voices.bin", tokens=f"{K}/tokens.txt",
            lexicon=f"{K}/lexicon-us-en.txt,{K}/lexicon-zh.txt", data_dir=f"{K}/espeak-ng-data"),
        num_threads=threads, provider="cpu"), max_num_sentences=1)
    return sherpa_onnx.OfflineTts(cfg)

def run(t):
    t.generate("Ready.", sid=3, speed=1.0)
    audio = work = 0.0; outs = []
    for s in TEXTS:
        t0 = time.time(); a = t.generate(s, sid=3, speed=1.0); work += time.time() - t0
        x = np.array(a.samples, dtype=np.float32); audio += len(x) / a.sample_rate; outs.append(x)
    return audio / work, outs

def logmel(x, n=512, hop=256):
    if len(x) < n: x = np.pad(x, (0, n - len(x)))
    frames = np.lib.stride_tricks.sliding_window_view(x, n)[::hop] * np.hanning(n)
    spec = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    bins = np.array_split(np.arange(spec.shape[1]), 64)
    return np.log(np.stack([spec[:, b].sum(1) for b in bins], 1) + 1e-6)

def similar(a, b):
    A, B = logmel(a), logmel(b); n = min(len(A), len(B))
    return float(np.corrcoef(A[:n].ravel(), B[:n].ravel())[0, 1]), len(b) / len(a)

lines = ["## Kokoro v1.0 on ARM64 (%d cores)" % os.cpu_count(), "",
         "| model | size MB | threads | speed (× real time) | mel similarity to fp32 | length ratio |", "|---|---|---|---|---|---|"]
ref = None
models = [("fp32", f"{K}/model.onnx")]
for v in ["all-u8", "all-s8", "mm-s8"]:
    try: models.append((v, make_int8(v)))
    except Exception as e: lines.append(f"| {v} | quantize failed: {str(e)[:120]} | | | | |")
results = {}
for name, path in models:
    for th in (1, 2, 4):
        try:
            rt, outs = run(tts(path, th))
        except Exception as e:
            lines.append(f"| {name} | | {th} | failed: {str(e)[:120]} | | |"); break
        if name == "fp32" and th == 1: ref = outs
        sims = [similar(r, o) for r, o in zip(ref, outs)] if ref else [(1, 1)]
        lines.append("| %s | %.0f | %d | %.2f | %.3f | %.2f |" % (name, os.path.getsize(path) / 1e6, th, rt,
            np.mean([s[0] for s in sims]), np.mean([s[1] for s in sims])))
        results[f"{name}/{th}"] = rt
    if name != "fp32" and os.path.exists(path):
        os.makedirs("out", exist_ok=True); shutil.copy(path, f"out/model.{name}.onnx")
open(OUT, "a").write("\n".join(lines) + "\n")
open("results.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
