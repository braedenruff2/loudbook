#!/usr/bin/env python3
"""A stand-in for Google's Gemini speech API, for the emulator test (test/gemini/run.sh).

Speaks whatever text it's sent with Kokoro (sherpa-onnx), the way Gemini answers: after a wait
(LATENCY s), the audio streamed as it's made at SPEED times listening speed (or, not streamed, all
at once when it's all made). Writes where every sentence starts in each recording to TRUTH (for
checking the app's sentence timing) and one line per request to LOG.

  python3 fake_gemini.py PORT KOKORO_DIR   (env: LATENCY, SPEED, TRUTH, LOG, CUT_AFTER)
"""
import base64, http.server, io, json, os, random, re, socketserver, sys, threading, time, wave
import numpy as np
import sherpa_onnx

PORT, KOKORO = int(sys.argv[1]), sys.argv[2]
LATENCY = float(os.environ.get("LATENCY", "4"))
SPEED = float(os.environ.get("SPEED", "2"))
TRUTH = os.environ.get("TRUTH", "/tmp/gem-truth.tsv")
LOG = os.environ.get("LOG", "/tmp/gem-requests.log")
RATE = 24000

tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f"{KOKORO}/model.onnx", voices=f"{KOKORO}/voices.bin",
        tokens=f"{KOKORO}/tokens.txt", lexicon=f"{KOKORO}/lexicon-us-en.txt", data_dir=f"{KOKORO}/espeak-ng-data"),
    num_threads=2)))
lock = threading.Lock()
nreq = [0]

def trim(x):
    loud = np.nonzero(np.abs(x) > 0.01)[0]
    return x[max(0, loud[0] - 240):loud[-1] + 240] if len(loud) else x

def sentences(text):
    out = []
    for para in re.split(r"\n\s*\n", text):
        for s in re.split(r"(?:(?<=[.!?…])|(?<=[.!?…][\"”’)]))\s+(?=\S)", para.strip()):
            if re.search(r"\w", s): out.append((s.strip(), False))
        if out: out[-1] = (out[-1][0], True)          # (a paragraph ends here)
    return out

def speak(text):
    """Yields (sentence, pcm16 bytes, paragraph end) one sentence at a time."""
    rnd = random.Random(len(text))
    for s, para_end in sentences(text):
        with lock:
            a = tts.generate(s, sid=3, speed=0.9 + rnd.random() * 0.2)
        x = trim(np.array(a.samples, dtype=np.float32))
        gap = int(RATE * ((0.6 + rnd.random() * 0.5) if para_end else (0.25 + rnd.random() * 0.35)))
        pcm = (np.clip(np.concatenate([x, np.zeros(gap, np.float32)]), -1, 1) * 32767).astype("<i2").tobytes()
        yield s, pcm, len(x)

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        t0 = time.time()
        with lock: nreq[0] += 1; n = nreq[0]
        try: text = body["input"][0]["content"][0]["text"]
        except Exception: text = body["contents"][0]["parts"][0]["text"]
        streamed = body.get("stream") is True or "streamGenerateContent" in self.path
        cut = float(os.environ.get("CUT_AFTER", "0"))
        time.sleep(LATENCY)
        at, first = 0, None
        truth = []
        if streamed:
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            self.wfile.write(b'data: {"event_type":"interaction.start","interaction":{"id":"x"}}\n\n')
        chunks = []
        for s, pcm, voiced in speak(text):
            truth.append((at / RATE, s))
            if cut and at / RATE > cut: break
            if streamed:
                for k in range(0, len(pcm), RATE):                       # half a second at a time
                    piece = pcm[k:k + RATE]
                    due = t0 + LATENCY + (at + k // 2) / RATE / SPEED     # made at SPEED x listening speed
                    if due > time.time(): time.sleep(due - time.time())
                    ev = {"event_type": "step.delta", "index": 0, "delta": {"type": "audio", "mime_type": "audio/l16", "data": base64.b64encode(piece).decode()}}
                    self.wfile.write(b"data: " + json.dumps(ev).encode() + b"\n\n"); self.wfile.flush()
                    if first is None: first = time.time() - t0
            else: chunks.append(pcm)
            at += len(pcm) // 2
        if streamed:
            self.wfile.write(b'data: {"event_type":"interaction.complete"}\n\n')
        else:
            due = t0 + LATENCY + at / RATE / SPEED
            if due > time.time(): time.sleep(due - time.time())
            buf = io.BytesIO()
            with wave.open(buf, "wb") as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(b"".join(chunks))
            out = json.dumps({"steps": [{"type": "model_output", "content": [{"type": "audio", "mime_type": "audio/wav", "data": base64.b64encode(buf.getvalue()).decode()}]}]}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(out))); self.end_headers()
            self.wfile.write(out)
            first = time.time() - t0
        with open(TRUTH, "a") as f:
            for t, s in truth: f.write(f"{n}\t{t:.3f}\t{s}\n")
        with open(LOG, "a") as f:
            f.write(f"request {n}: {'streamed' if streamed else 'all at once'}, {len(text)} letters, {len(truth)} sentences, "
                    f"{at / RATE:.0f} s of audio, first audio after {first:.1f} s, done after {time.time() - t0:.1f} s; starts: {truth[0][1][:50] if truth else ''}\n")
        self.close_connection = True

class S(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True
S(("0.0.0.0", PORT), H).serve_forever()
