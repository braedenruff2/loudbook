"""Loudbook PC voice server.

Runs the reading voice on your PC so the phone doesn't have to (saves its battery). Only phones
you pair can use it:

  * Everything goes over HTTPS. The server makes its own certificate; a paired phone remembers
    its exact fingerprint, so nothing can stand in for your PC.
  * Pairing needs a one-time code shown on the PC (60 bits, valid 10 minutes, 5 tries). The PC
    proves it knows the code before the phone reveals anything, and the code is stretched with
    PBKDF2, so watching the exchange doesn't help anyone guess it.
  * Each phone then gets its own 256-bit key. The server keeps only a hash of it.
  * It only answers devices on your home network (private addresses, plus Tailscale's range),
    limits request sizes and rates, and never logs what's being read.

  Best quality (the phone's setting "PC voice: best quality"): the phone sends the chapter it's reading and the next
  ones; the PC records every sentence ahead with the full Chatterbox model (each up to 3 times, keeping a take of a
  believable length), keeps them in its own folder (LOUDBOOK_AHEAD_GB, 4 GB by default, oldest dropped first) and
  answers from there. Sentences not made yet are read live, as before.

  Audiobooks: a job file (*.job.json) in the "Loudbook audiobooks" folder on the Desktop (LOUDBOOK_BOOKS) lists a book's
  chapters (each a JSON file of paragraphs of sentences); the PC records them with the best voice whenever nothing else
  needs the graphics card, one MP3 per chapter (plus each sentence's place in it, <chapter>.json), and writes how far it
  is to status.txt there. Stopping is safe: it carries on from the chapter it was on.

  python loudbook_server.py serve      run the server (the setup script starts it at log-on)
  python loudbook_server.py pair       show a pairing code and wait for the phone
  python loudbook_server.py forget     unpair every phone
"""
import base64, collections, datetime, glob, hashlib, hmac, http.server, ipaddress, json, os, secrets, socket, ssl, sys
import threading, time

os.environ.setdefault("TQDM_DISABLE", "1")       # no progress bars filling the log for every sentence

VERSION = 1
PORT = int(os.environ.get("LOUDBOOK_PORT", "8770"))
DISCOVERY_PORT = PORT + 1
MAX_BODY = 16 * 1024
MAX_TEXT = 2000
MAX_VOICE = 6 * 1024 * 1024          # a recorded voice: about a minute of speech, as base64
MAX_AHEAD = 2 * 1024 * 1024          # a list of sentences to make ahead: a few chapters
MAX_AHEAD_ITEMS = 8000
PAIR_MINUTES = 10
PAIR_TRIES = 5
STATE = os.environ.get("LOUDBOOK_STATE") or os.path.join(
    os.environ.get("LOCALAPPDATA") or os.path.expanduser("~/.local/share"), "Loudbook", "server")
MODELS = os.environ.get("LOUDBOOK_MODELS") or os.path.join(os.path.dirname(STATE), "models")
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"   # no 0/O, 1/I/L


def path(name):
    os.makedirs(STATE, exist_ok=True)
    return os.path.join(STATE, name)


def b64(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")
def unb64(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def read_json(name, default):
    try:
        with open(path(name), encoding="utf-8") as f: return json.load(f)
    except (OSError, ValueError): return default


voices_lock = threading.Lock()                         # (two voice changes at once could lose one)

def write_json(name, data):
    tmp = path(name + ".tmp." + str(threading.get_ident()))
    with open(tmp, "w", encoding="utf-8") as f: json.dump(data, f, indent=1)
    os.replace(tmp, path(name))


# ------------------------------------------------------------------ certificate
def ensure_cert():
    cert, key = path("cert.pem"), path("key.pem")
    if not (os.path.exists(cert) and os.path.exists(key)):
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
        k = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Loudbook PC")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
             .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
             .not_valid_after(now + datetime.timedelta(days=3650)).sign(k, hashes.SHA256()))
        with open(key, "wb") as f:
            f.write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with open(cert, "wb") as f: f.write(c.public_bytes(serialization.Encoding.PEM))
    return cert, key


def fingerprint():
    with open(path("cert.pem"), "rb") as f: der = ssl.PEM_cert_to_DER_cert(f.read().decode())
    return hashlib.sha256(der).digest()


# ------------------------------------------------------------------ pairing
def pair_key(code):
    code = "".join(ch for ch in code.upper() if ch in ALPHABET)
    return hashlib.pbkdf2_hmac("sha256", code.encode(), b"loudbook-pair-v1", 200_000, 32)


def mac(key, who, nc, ns, fp):
    return hmac.new(key, who + b"|" + nc + ns + fp, hashlib.sha256).digest()


class Pairing:
    """Pairing mode is switched on by `pair` (a file only this Windows user can write)."""
    lock = threading.Lock()
    sessions = {}

    @classmethod
    def active(cls):
        p = read_json("pairing.json", None)
        if not p or time.time() > p.get("expires", 0) or p.get("tries", 0) >= PAIR_TRIES: return None
        return p

    @classmethod
    def start(cls, nc, fp):
        with cls.lock:
            p = cls.active()
            if not p: return None
            cls.starts = getattr(cls, "starts", {})
            n = cls.starts.get(p["key"], 0) + 1
            cls.starts = {p["key"]: n}
            if n > 30: return None                              # no harvesting answers to guess from
            ns = secrets.token_bytes(16)
            cls.sessions = {k: v for k, v in cls.sessions.items() if v > time.time()}
            cls.sessions[(nc, ns)] = time.time() + 60
            return {"ns": b64(ns), "mac": b64(mac(unb64(p["key"]), b"server", nc, ns, fp)), "fp": fp.hex()}

    @classmethod
    def finish(cls, nc, ns, client_mac, name, fp):
        with cls.lock:
            p = cls.active()
            if not p or cls.sessions.pop((nc, ns), 0) < time.time(): return None
            if not hmac.compare_digest(mac(unb64(p["key"]), b"client", nc, ns, fp), client_mac):
                p["tries"] = p.get("tries", 0) + 1
                write_json("pairing.json", p)
                return None
            token = secrets.token_bytes(32)
            devices = read_json("devices.json", [])
            devices.append({"name": str(name)[:60], "hash": hashlib.sha256(token).hexdigest(),
                            "added": datetime.datetime.now().isoformat(timespec="seconds")})
            write_json("devices.json", devices)
            p["expires"] = 0                                   # one phone per code
            write_json("pairing.json", p)
            return b64(token)


def token_ok(token):
    try: h = hashlib.sha256(unb64(token)).hexdigest()
    except Exception: return False
    ok = False
    for d in read_json("devices.json", []):
        ok |= hmac.compare_digest(d.get("hash", ""), h)
    return ok


# ------------------------------------------------------------------ the voice
def preload_onnxruntime():
    """Windows 11 keeps an older onnxruntime.dll in System32 (for Windows ML) that gets picked up
    instead of the one sherpa-onnx ships with. Load sherpa-onnx's own copy first, by full path."""
    if os.name != "nt": return
    import ctypes, glob, importlib.util
    spec = importlib.util.find_spec("sherpa_onnx")
    if not spec or not spec.origin: return
    pkg = os.path.dirname(spec.origin)
    site = os.path.dirname(pkg)
    found = sorted(set(glob.glob(os.path.join(site, "**", "onnxruntime*.dll"), recursive=True)
                       + glob.glob(os.path.join(sys.prefix, "**", "onnxruntime*.dll"), recursive=True)
                       + glob.glob(os.path.join(sys.base_prefix, "**", "onnxruntime*.dll"), recursive=True)))
    if not found:
        print("Loudbook PC voice: sherpa_onnx files:", sorted(os.listdir(pkg))[:60], flush=True)
    print("Loudbook PC voice: onnxruntime candidates:", found or "none", flush=True)
    for dll in [f for f in found if os.path.basename(f).lower() == "onnxruntime.dll"]:
        try:
            os.add_dll_directory(os.path.dirname(dll))
            ctypes.WinDLL(dll)
            print("Loudbook PC voice: using", dll, flush=True)
            return
        except OSError as e:
            print("Loudbook PC voice: couldn't load", dll, e, flush=True)


class Engine:
    """Kokoro v1.0 through sherpa-onnx, on all the PC's cores (or the GPU if this sherpa-onnx has CUDA)."""
    def __init__(self):
        preload_onnxruntime()
        import sherpa_onnx
        d = os.path.join(MODELS, "kokoro-multi-lang-v1_0")
        lex = ",".join(os.path.join(d, n) for n in ("lexicon-us-en.txt", "lexicon-zh.txt") if os.path.exists(os.path.join(d, n)))
        provider = os.environ.get("LOUDBOOK_PROVIDER", "cpu")
        self.tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                    model=os.path.join(d, "model.onnx"), voices=os.path.join(d, "voices.bin"),
                    tokens=os.path.join(d, "tokens.txt"), lexicon=lex, data_dir=os.path.join(d, "espeak-ng-data")),
                num_threads=max(1, min(8, (os.cpu_count() or 2) - 1)), provider=provider),
            max_num_sentences=1))
        self.name = "Kokoro v1.0 (" + provider + ")"
        self.rate = self.tts.sample_rate
        self.lock = threading.Lock()
        self.speak("Ready.", 3, 1.0)

    def speak(self, text, sid, speed):
        import numpy as np
        with self.lock:
            a = self.tts.generate(text, sid=sid, speed=speed)
        x = np.clip(np.asarray(a.samples, dtype=np.float32), -1, 1)
        return (x * 32767).astype("<i2").tobytes(), a.sample_rate

    def samples(self, text, sid):
        """Float samples at normal speed (the natural voice's reference clips are made from these)."""
        import numpy as np
        with self.lock:
            a = self.tts.generate(text, sid=sid, speed=1.0)
        return np.asarray(a.samples, dtype=np.float32), a.sample_rate


# ------------------------------------------------------------------ recorded voices
# Someone (with their say-so) reads a passage into the phone; the recording stays on this PC as the
# example the natural voice copies. Nothing is sent anywhere else.
def voices():
    return [v for v in read_json("voices.json", []) if isinstance(v, dict) and os.path.exists(voice_file(v.get("id", "")))]

def voice_file(vid):
    if not isinstance(vid, str) or len(vid) != 12 or any(c not in "0123456789abcdef" for c in vid): return ""
    return path(os.path.join("refs", f"custom-{vid}.wav"))

def add_voice(name, wav_bytes):
    """Checks the recording is a plain mono WAV of a sensible length and keeps it. Returns its id."""
    import io, wave
    with wave.open(io.BytesIO(wav_bytes)) as w:
        ch, width, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
    if ch != 1 or width != 2 or not 16000 <= rate <= 48000: raise ValueError("expected 16-bit mono audio")
    secs = n / rate
    if not 6 <= secs <= 90: raise ValueError(f"the recording should be 6 to 90 seconds (it was {secs:.0f})")
    vid = secrets.token_hex(6)
    f = voice_file(vid)
    os.makedirs(os.path.dirname(f), exist_ok=True)
    with open(f, "wb") as out: out.write(wav_bytes)
    # (no braces: the phone finds the list's entries by them, and a voice named "Mum {old}" went missing there)
    name = " ".join(str(name).replace("{", "(").replace("}", ")").split())[:40] or "Recorded voice"
    with voices_lock: write_json("voices.json", voices() + [{"id": vid, "name": name, "seconds": round(secs, 1)}])
    print(f"Loudbook PC voice: added the voice \"{name}\" ({secs:.0f} s)", flush=True)
    return vid

def delete_voice(vid):
    f = voice_file(vid)
    if f and os.path.exists(f): os.remove(f)
    with voices_lock: write_json("voices.json", [v for v in voices() if v.get("id") != vid])


# A few sentences of plain narration (written for this) for the natural voice to copy each
# Kokoro voice from, so a voice sounds like itself whichever engine reads.
REFERENCE_TEXT = ("The rain had stopped by the time she reached the bridge. Lamps were coming on along the river, "
                  "one after another, and somewhere a dog was barking at nothing in particular. She pulled her coat "
                  "tighter and kept walking, counting the steps the way her father used to.")


class NaturalEngine:
    """Chatterbox-Turbo (Resemble AI, MIT licence) on the graphics card: more natural, expressive
    reading. It copies each Kokoro voice from a short reference clip, so voice choices carry over.
    It doesn't do speed itself; the phone speeds playback up instead."""
    best = False

    def __init__(self, kokoro):
        import torch
        dev = os.environ.get("LOUDBOOK_NATURAL_DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")
        self.device = dev
        self.gpu = torch.cuda.get_device_name(0) if dev == "cuda" else "no graphics card (CPU)"
        self.model = self.load(dev)
        self.builtin = getattr(self.model, "conds", None)      # (Chatterbox's own voice, from its conds.pt: "builtin")
        self.rate = self.model.sr
        self.kokoro = kokoro
        self.voices = {}                      # sid -> conditionals
        self.lock = threading.Lock()
        self.recent = collections.deque(maxlen=12)     # (seconds of speech, seconds of work)
        self.gain = None
        self.name = self.label()
        self.too_slow = False
        # warm up, then see whether this card keeps ahead of reading
        self.speak("Ready to read.", 3)
        if self.best: return                  # (it reads ahead: keeping up with reading isn't the point)
        self.recent.clear()
        for t in ("He set the lantern down and listened to the wind moving through the empty house.",
                  "Nobody answered, so she knocked again, louder this time."):
            self.speak(t, 3)
        if self.speed() < float(os.environ.get("LOUDBOOK_NATURAL_MIN_SPEED", "1.1")):
            self.too_slow = True
            print(f"Loudbook PC voice: the natural voice makes speech at {self.speed():.2f}x real time here, "
                  "too slow to keep up, so Kokoro reads instead.", flush=True)

    def load(self, dev):
        from chatterbox.tts_turbo import ChatterboxTurboTTS
        return ChatterboxTurboTTS.from_pretrained(device=dev)

    def label(self): return f"Chatterbox-Turbo ({self.gpu})"

    def generate(self, text): return self.model.generate(text)

    def close(self):
        """Lets go of the graphics card's memory (the full model and Turbo don't both fit on a 6 GB card)."""
        import gc
        self.model = None; self.voices.clear()
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available(): torch.cuda.empty_cache()
        except Exception: pass

    def _voice(self, sid):
        st = style_of(sid)
        self.cur_style = st
        if st is not None:
            # a voice with its own settings ("s17@0.85/e0.35/c0.4": Onyx, copied from a slower reading of the passage, calmer)
            base, ref_speed, pitch = st[0], st[1], st[4]
            if (ref_speed == 1.0 and pitch == 0) or not isinstance(base, int): return self._voice_base(base)
            key = sid.split("/")[0]                       # (the same voice in every mood: copied once)
            if key not in self.voices:
                ref = path(os.path.join("refs", f"{base}@{ref_speed:g}" + (f"^{pitch:+g}" if pitch else "") + ".wav"))
                self._make_ref(base, ref_speed, ref, pitch)
                self.model.prepare_conditionals(ref)
                self.voices[key] = self.model.conds
            self.model.conds = self.voices[key]
            return
        return self._voice_base(sid)

    def _make_ref(self, sid, speed, ref, pitch=0):
        """The passage read by Kokoro voice sid at this speed, its pitch moved by `pitch` semitones (a deeper or lighter
        voice for the full model to copy: each character a voice of their own)."""
        import numpy as np, wave
        if os.path.exists(ref): return
        os.makedirs(os.path.dirname(ref), exist_ok=True)
        with self.kokoro.lock:
            a = self.kokoro.tts.generate(REFERENCE_TEXT, sid=sid, speed=speed)
        x = np.asarray(a.samples, dtype=np.float32)
        if pitch:
            import librosa
            x = librosa.effects.pitch_shift(x, sr=a.sample_rate, n_steps=float(pitch)).astype(np.float32)
        with wave.open(ref + ".part", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate)
            w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        os.replace(ref + ".part", ref)

    def _voice_base(self, sid):
        if sid == "builtin" and self.builtin is not None:
            self.model.conds = self.builtin
            return
        if isinstance(sid, str):                     # a recorded voice
            if sid not in self.voices:
                self.model.prepare_conditionals(voice_file(sid))
                self.voices[sid] = self.model.conds
            self.model.conds = self.voices[sid]
            return
        if sid not in self.voices:
            import numpy as np, wave
            ref = path(os.path.join("refs", f"{sid}.wav"))
            if not os.path.exists(ref):
                os.makedirs(os.path.dirname(ref), exist_ok=True)
                x, sr = self.kokoro.samples(REFERENCE_TEXT, sid)
                # (written beside it, then put in place: a half-written clip (a restart mid-write) was reused for good)
                with wave.open(ref + ".part", "wb") as w:
                    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
                    w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
                os.replace(ref + ".part", ref)
            self.model.prepare_conditionals(ref)
            self.voices[sid] = self.model.conds
        self.model.conds = self.voices[sid]

    def speed(self):
        """Seconds of speech made per second of work, lately (0 = not known yet)."""
        a = sum(r[0] for r in self.recent); w = sum(r[1] for r in self.recent)
        return a / w if w > 1 else 0

    def usable(self):
        if self.too_slow: return False
        if len(self.recent) == self.recent.maxlen and self.speed() < 0.9 * float(os.environ.get("LOUDBOOK_NATURAL_MIN_SPEED", "1.1")) / 1.1:
            self.too_slow = True                       # fell behind for a dozen sentences running
            print(f"Loudbook PC voice: natural voice fell behind ({self.speed():.2f}x), Kokoro takes over.", flush=True)
        return not self.too_slow

    def speak(self, text, sid):
        import numpy as np, torch
        with self.lock, torch.inference_mode():
            t0 = time.time()                           # (after the wait for the lock: waiting behind another request isn't
            self._voice(sid)                           # slowness, and it made Kokoro take over for good)
            wav = self.generate(text)
        x = wav.squeeze(0).detach().cpu().numpy().astype(np.float32)
        # steady loudness, close to Kokoro's, without pumping between sentences
        rms = float(np.sqrt(np.mean(x * x))) if len(x) else 0
        if rms > 1e-4:
            g = 0.08 / rms
            self.gain = g if self.gain is None else 0.8 * self.gain + 0.2 * g
            x = x * self.gain
        x = np.clip(x, -0.98, 0.98)
        self.recent.append((len(x) / self.rate, time.time() - t0))
        return (x * 32767).astype("<i2").tobytes(), self.rate


def load_natural(kokoro):
    """The natural voice, if its packages are installed (setup does that when there's an NVIDIA card)."""
    if os.environ.get("LOUDBOOK_NATURAL", "1") == "0": return None
    try:
        import chatterbox  # noqa: F401
    except ImportError:
        return None
    try:
        print("Loudbook PC voice: loading the natural voice (Chatterbox-Turbo)...", flush=True)
        return NaturalEngine(kokoro)
    except Exception as e:
        import traceback
        print("Loudbook PC voice: natural voice unavailable:", repr(e)[:300], flush=True)
        print("".join(traceback.format_exc().splitlines(True)[-8:]), flush=True)
        return None


class BestEngine(NaturalEngine):
    """The full Chatterbox model (Resemble AI, MIT licence): the most natural reading this PC can do, slower than Turbo.
    Used to record chapters ahead of reading, where time doesn't matter. Each sentence is made up to 3 times and a take
    of a believable length kept (now and then a take babbles on or stops short)."""
    best = True
    EXAGGERATION = float(os.environ.get("LOUDBOOK_BEST_EXAGGERATION", "0.5"))
    CFG = float(os.environ.get("LOUDBOOK_BEST_CFG", "0.5"))
    TAKES = max(1, int(os.environ.get("LOUDBOOK_BEST_TAKES", "3")))

    def load(self, dev):
        from chatterbox.tts import ChatterboxTTS
        return ChatterboxTTS.from_pretrained(device=dev)

    def label(self): return f"Chatterbox, full model ({self.gpu})"

    def generate(self, text):
        st = getattr(self, "cur_style", None)
        ex = st[2] if st and st[2] is not None else self.EXAGGERATION
        cfg = st[3] if st and st[3] is not None else self.CFG
        return self.model.generate(text, exaggeration=ex, cfg_weight=cfg)

    def expected(self, ktext, sid):
        """How long Kokoro takes to say it, in seconds (speech only): what a good take should be near."""
        import numpy as np
        st = style_of(sid)
        if st is not None: sid = st[0]
        try:
            x, sr = self.kokoro.samples(ktext, sid if isinstance(sid, int) else 3)
        except Exception: return 0
        x = np.abs(np.asarray(x))
        loud = np.nonzero(x > 0.01)[0]
        return (loud[-1] - loud[0]) / sr if len(loud) > 1 else 0

    @staticmethod
    def spoken(pcm, rate):
        """Seconds from the first sound to the last in 16-bit audio, and the longest silence inside (seconds)."""
        import numpy as np
        x = np.abs(np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768)
        loud = np.nonzero(x > 0.01)[0]
        if len(loud) < 2: return 0, 0
        gaps = np.diff(loud)
        return (loud[-1] - loud[0]) / rate, float(gaps.max()) / rate

    @staticmethod
    def pieces(text):
        """A long sentence in parts of at most ~250 letters, at commas and the like (a take stops after ~40 s of speech)."""
        import re
        if len(text) <= 260: return [text]
        out, cur = [], ""
        for part in re.split(r"(?<=[,;:\u2014)])\s+", text):
            if cur and len(cur) + len(part) > 250: out.append(cur); cur = part
            else: cur = (cur + " " + part).strip()
        if cur: out.append(cur)
        final = []
        for p in out:                              # (no comma for a long way: by words)
            while len(p) > 300:
                cut = p.rfind(" ", 0, 250)
                if cut < 50: cut = 250
                final.append(p[:cut].strip()); p = p[cut:].strip()
            if p: final.append(p)
        return final

    def best_take(self, text, sid, ktext=None, takes=None):
        """The sentence made up to `takes` times; the first take of a believable length (against Kokoro's), else the
        nearest. Returns (pcm, rate, how sure: 'ok' or 'odd')."""
        import numpy as np
        parts = self.pieces(text)
        if len(parts) > 1:
            gap = np.zeros(int(0.12 * self.rate), dtype="<i2").tobytes()
            out, sure = [], "ok"
            for p in parts:
                pcm, rate, s = self.best_take(p, sid, None, takes)
                out.append(pcm); sure = sure if s == "ok" else s
            return gap.join(out), self.rate, sure
        want = self.expected(ktext or text, sid)
        best, best_off = None, 9e9
        for t in range(takes or self.TAKES):
            pcm, rate = self.speak(text, sid)
            got, hole = self.spoken(pcm, rate)
            if want <= 0.3: return pcm, rate, "ok"      # (too short to judge)
            r = got / want
            # (Chatterbox reads a little slower than Kokoro; a long silence inside is a take gone wrong, unless the text pauses)
            ok = 0.7 <= r <= 1.8 and (hole < 1.6 or "..." in text or "\u2026" in text)
            if ok: return pcm, rate, "ok"
            off = abs(np.log(max(r, 1e-3) / 1.15)) + (1 if hole >= 1.6 else 0)
            if off < best_off: best, best_off = (pcm, rate), off
            print(f"Loudbook PC voice: take {t + 1} of a sentence came out {r:.2f}x Kokoro's length"
                  + (f" with a {hole:.1f} s gap" if hole >= 1.6 else "") + ", making it again", flush=True)
        return best[0], best[1], "odd"


def load_best(kokoro):
    """The full Chatterbox model, if its packages are installed (the same ones as the natural voice)."""
    try:
        import chatterbox  # noqa: F401
    except ImportError:
        return None
    try:
        print("Loudbook PC voice: loading the best-quality voice (Chatterbox, full model)...", flush=True)
        return BestEngine(kokoro)
    except Exception as e:
        import traceback
        print("Loudbook PC voice: best-quality voice unavailable:", repr(e)[:300], flush=True)
        print("".join(traceback.format_exc().splitlines(True)[-8:]), flush=True)
        return None


def style_of(who):
    """A voice with its own settings: "s<speaker>[@<speed the passage it copies is read at>][^<semitones up or down>]
    [/e<exaggeration>][/c<cfg>]" ("s17^-2/e0.75": Onyx two semitones deeper, expressive), or "builtin/e0.4", or
    "<recorded id>/c0.3". Returns (base voice, speed, exaggeration or None, cfg or None, semitones), or None."""
    if not isinstance(who, str) or "/" not in who and not who.startswith("s"): return None
    parts = who.split("/")
    head = parts[0]
    try:
        pitch = 0.0
        if "^" in head:
            head, _, pt = head.partition("^")
            pitch = max(-6.0, min(6.0, float(pt)))
        if head.startswith("s") and head[1:2].isdigit():
            sid, _, sp = head[1:].partition("@")
            base, speed = max(0, min(52, int(sid))), max(0.6, min(1.4, float(sp))) if sp else 1.0
        elif head == "builtin" or (voice_file(head) and "/" in who):
            base, speed = head, 1.0
        else: return None
        ex = cfg = None
        for p in parts[1:]:
            if p[:1] == "e": ex = max(0.0, min(1.5, float(p[1:])))
            elif p[:1] == "c": cfg = max(0.0, min(1.0, float(p[1:])))
        return base, speed, ex, cfg, pitch
    except ValueError:
        return None


# ------------------------------------------------------------------ sentences made ahead (best quality)
def ahead_key(text, who):
    return hashlib.sha256(("best|" + str(who) + "|" + " ".join(str(text).split())).encode()).hexdigest()[:40]


class Ahead:
    """The sentences the phone will read next, made one by one with the full model while the graphics card is free,
    and kept on disk (raw 16-bit audio after a 4-byte sample rate) until the folder is full, oldest dropped first."""
    cond = threading.Condition()
    queue = collections.deque()          # (key, text, kokoro text, who)
    queued = set()
    heard = set()                        # read live already (passed): not worth making now
    total = 0                            # sentences in the phone's last list
    made = 0; odd = 0; failed = 0
    stores = 0
    live = 0                             # live requests running: they come first
    making = 0                           # 1 while a sentence is being made (it counts as waiting)
    last_used = time.time()
    loading = False
    MAX_BYTES = int(float(os.environ.get("LOUDBOOK_AHEAD_GB", "4")) * 1e9)

    @staticmethod
    def dir(): return path("ahead")

    @classmethod
    def file(cls, key): return os.path.join(cls.dir(), key + ".pcm")

    @classmethod
    def get(cls, key):
        f = cls.file(key)
        try:
            with open(f, "rb") as h: data = h.read()
        except OSError: return None
        if len(data) < 6: return None
        try: os.utime(f)                              # (recently used: dropped last)
        except OSError: pass
        return data[4:], int.from_bytes(data[:4], "little")

    @classmethod
    def has(cls, key): return os.path.exists(cls.file(key))

    @classmethod
    def put(cls, key, pcm, rate):
        os.makedirs(cls.dir(), exist_ok=True)
        f = cls.file(key)
        with open(f + ".part", "wb") as h: h.write(int(rate).to_bytes(4, "little")); h.write(pcm)
        os.replace(f + ".part", f)
        cls.stores += 1
        if cls.stores % 25 == 1: cls.trim()

    @classmethod
    def trim(cls):
        try:
            fs = [(e.stat().st_mtime, e.stat().st_size, e.path) for e in os.scandir(cls.dir()) if e.name.endswith(".pcm")]
        except OSError: return
        size = sum(f[1] for f in fs)
        for m, n, p in sorted(fs):
            if size <= cls.MAX_BYTES: break
            try: os.remove(p); size -= n
            except OSError: pass

    @classmethod
    def set(cls, items):
        """The phone's list (in reading order) replaces what was waiting. Returns (sentences, already made)."""
        with cls.cond:
            cls.queue.clear(); cls.queued.clear()
            done = 0
            for key, text, ktext, who in items:
                if key in cls.queued: continue
                if cls.has(key): done += 1; continue
                cls.queue.append((key, text, ktext, who)); cls.queued.add(key)
            cls.total = len(items)
            cls.heard.clear()
            cls.last_used = time.time()
            cls.cond.notify_all()
        return cls.total, done

    @classmethod
    def status(cls):
        with cls.cond:
            return {"total": cls.total, "waiting": len(cls.queue) + cls.making, "made": cls.made, "odd": cls.odd, "failed": cls.failed,
                    "loading": cls.loading}

    @classmethod
    def next(cls):
        """The next sentence to make, or None after half a minute with nothing to do."""
        with cls.cond:
            if not cls.queue: cls.cond.wait(1 if Books.pending else 30)
            return cls._pop()

    @classmethod
    def _pop(cls):
        while cls.queue:
            it = cls.queue.popleft(); cls.queued.discard(it[0])
            if it[0] not in cls.heard and not cls.has(it[0]): return it
        return None


def best_engine():
    """The full model, loaded the first time it's needed. On a card without room for it next to Turbo, Turbo goes (the
    full model reads live too when it keeps up; otherwise Kokoro does)."""
    if Handler.best is not None or Handler.engine is None or isinstance(Handler.engine, FakeEngine): return Handler.best
    with Handler.gpu_lock:
        if Handler.best is not None: return Handler.best
        if time.time() < Handler.best_retry: return None
        try:
            import torch
            if torch.cuda.is_available() and Handler.natural is not None:
                free, _ = torch.cuda.mem_get_info()
                if free < float(os.environ.get("LOUDBOOK_BEST_VRAM_GB", "4.5")) * 1e9:
                    print(f"Loudbook PC voice: {free / 1e9:.1f} GB free on the graphics card: Turbo makes room for the full model", flush=True)
                    n = Handler.natural; Handler.natural = None
                    with n.lock: n.close()
        except ImportError:
            return None
        Ahead.loading = True
        try: Handler.best = load_best(Handler.engine)
        finally: Ahead.loading = False
        if Handler.best is None: Handler.best_retry = time.time() + 600
        return Handler.best


# ------------------------------------------------------------------ audiobooks (whole books, recorded on the PC)
BOOKS = os.environ.get("LOUDBOOK_BOOKS") or os.path.join(os.path.expanduser("~"), "Desktop", "Loudbook audiobooks")


class Books:
    """Job files in BOOKS: {"title", "voice": a speaker number, a recorded voice's id or "phone" (the voice the phone last
    read with), "out": a folder name, "chapters": [{"name", "file": a JSON file of [[sentence, ...], ...] paragraphs}]}."""
    idle_until = 0                # (nothing to do, or it failed: look again after this)
    pending = True                # an audiobook chapter may be waiting to be made (looked at straight away)
    started = None
    secs_made = 0.0               # seconds of speech made since the server started, and the work it took
    secs_work = 0.0

    heard = collections.Counter()

    @classmethod
    def remember_voice(cls, who):
        """The voice the phone reads with most (its narrator: quotes in the second voice are fewer), kept for "phone"."""
        cls.heard[who] += 1
        if sum(cls.heard.values()) % 20 == 1:
            top = cls.heard.most_common(1)[0][0]
            if read_json("last-voice.json", {}).get("who") != top: write_json("last-voice.json", {"who": top})

    @classmethod
    def jobs(cls):
        try: names = sorted(n for n in os.listdir(BOOKS) if n.endswith(".job.json"))
        except OSError: return []
        out = []
        for n in names:
            try:
                with open(os.path.join(BOOKS, n), encoding="utf-8") as f: j = json.load(f)
                if isinstance(j, dict) and isinstance(j.get("chapters"), list): out.append((n, j))
            except Exception as e:
                print(f"Loudbook PC voice: audiobook job {n} can't be read: {e!r}"[:200], flush=True)
        return out

    @staticmethod
    def safe(name, n=80):
        name = "".join(ch if ch.isalnum() or ch in " -_.,()'&" else " " for ch in str(name))
        return " ".join(name.split())[:n].strip(" .") or "untitled"

    @classmethod
    def chapter_path(cls, job, k):
        ch = job["chapters"][k]
        return os.path.join(BOOKS, cls.safe(job.get("out") or job.get("title") or "book"), f"{k + 1:04d} - {cls.safe(ch.get('name', ''), 70)}")

    @classmethod
    def voice_map(cls, job):
        """Name -> voice: the job's "voices", then its "voices_file" (a JSON file in BOOKS, {name: {"voice": ...}} or {name:
        voice}), read at the start of each chapter so a change there applies from the next chapter on."""
        m = dict(job.get("voices") or {})
        vf = job.get("voices_file")
        if vf:
            try:
                with open(vf if os.path.isabs(vf) else os.path.join(BOOKS, vf), encoding="utf-8") as h: d = json.load(h)
                for name, e in d.items():
                    if name.startswith("_"): continue
                    v = e.get("voice") if isinstance(e, dict) else e
                    if v is not None: m[name] = v
            except Exception as e:
                print(f"Loudbook PC voice: voices file {vf} can't be read: {e!r}"[:200], flush=True)
        return m

    MOODS = {"calm": "/e0.45", "soft": "/e0.4/c0.45", "whisper": "/e0.3/c0.4", "sad": "/e0.5/c0.3", "tired": "/e0.35/c0.3",
             "happy": "/e0.95", "excited": "/e1.05/c0.55", "playful": "/e0.95/c0.5", "angry": "/e1.1/c0.4", "shout": "/e1.3/c0.5",
             "scared": "/e0.95/c0.6", "nervous": "/e0.85/c0.55", "cold": "/e0.4/c0.5", "menacing": "/e1.0/c0.3",
             "proud": "/e0.9/c0.4", "pleading": "/e1.0/c0.45", "surprised": "/e1.05/c0.55", "neutral": ""}

    @classmethod
    def moods(cls, job):
        """Mood -> settings added to the speaker's voice ("/e<exaggeration>/c<cfg>": more or less emotional, slower with a
        lower cfg). The voices file's "_moods" changes or adds to these."""
        m = dict(cls.MOODS)
        vf = job.get("voices_file")
        if vf:
            try:
                with open(vf if os.path.isabs(vf) else os.path.join(BOOKS, vf), encoding="utf-8") as h: d = json.load(h)
                if isinstance(d.get("_moods"), dict): m.update({k: str(v) for k, v in d["_moods"].items() if not k.startswith("_")})
            except Exception: pass
        return m

    @classmethod
    def earlier(cls, name):
        """Does a job named before this one have a chapter to make?"""
        for n, j in cls.jobs():
            if n >= name: return False
            if any(not os.path.exists(cls.chapter_path(j, k) + ".mp3") for k in range(len(j["chapters"]))): return True
        return False

    @classmethod
    def next_chapter(cls):
        """(job file, job, chapter index) of the first chapter not made yet, in job-name order; None if all are made."""
        for n, j in cls.jobs():
            for k in range(len(j["chapters"])):
                if not os.path.exists(cls.chapter_path(j, k) + ".mp3"): return n, j, k
        return None

    @classmethod
    def who(cls, job):
        v = job.get("voice", "phone")
        if v == "phone" or v is None: v = read_json("last-voice.json", {}).get("who", 3)
        if v == "builtin" or style_of(v) is not None: return v
        if isinstance(v, str) and not (voice_file(v) and os.path.exists(voice_file(v))):
            try: v = int(v)
            except ValueError: v = 3
        return max(0, min(52, v)) if isinstance(v, int) else v

    @classmethod
    def status(cls, line):
        try:
            os.makedirs(BOOKS, exist_ok=True)
            with open(os.path.join(BOOKS, "status.txt"), "w", encoding="utf-8") as f: f.write(line + "\n")
        except OSError: pass

    @classmethod
    def progress(cls):
        """Done/total chapters and seconds of speech per second of work, over all jobs."""
        done = total = 0
        for _, j in cls.jobs():
            for k in range(len(j["chapters"])):
                total += 1
                if os.path.exists(cls.chapter_path(j, k) + ".mp3"): done += 1
        return done, total

    @classmethod
    def kindle_dir(cls, d):
        """The folder of Kindle books (EPUB) for the book folder d: its job's "kindle" (a folder), or else the Whizzer
        Library download's Kindle/<the book's title...> folder found beside BOOKS (Desktop\\*\\Whizzer Library\\Kindle)."""
        for _, j in cls.jobs():
            if cls.safe(j.get("out") or j.get("title") or "book") != d: continue
            k = j.get("kindle")
            if k:
                k = k if os.path.isabs(k) else os.path.join(BOOKS, k)
                return k if os.path.isdir(k) else None
            key = cls.safe(j.get("title") or d).lower()
            base = os.path.dirname(os.path.abspath(BOOKS))
            for pat in ("*/Whizzer Library/Kindle", "*/*/Whizzer Library/Kindle"):
                for root in sorted(glob.glob(os.path.join(glob.escape(base), pat))):
                    try: names = sorted(os.listdir(root))
                    except OSError: continue
                    for n in names:
                        if cls.safe(n).lower().startswith(key) and os.path.isdir(os.path.join(root, n)): return os.path.join(root, n)
        return None

    @classmethod
    def kindle_files(cls, d):
        """[(file name, full path)] of the EPUBs for book folder d."""
        k = cls.kindle_dir(d)
        if not k: return []
        try: return [(n, os.path.join(k, n)) for n in sorted(os.listdir(k)) if n.lower().endswith(".epub") and os.path.isfile(os.path.join(k, n))]
        except OSError: return []

    @classmethod
    def finished(cls, kindle=False):
        """[{name, files: [{p: path inside BOOKS, bytes}]}] of every finished chapter (MP3 and its .json), by book; with
        kindle, the book's Kindle books (EPUB) too (asked for apart: older phones copied every file listed)."""
        out = []
        try: dirs = sorted(d for d in os.listdir(BOOKS) if os.path.isdir(os.path.join(BOOKS, d)))
        except OSError: return out
        for d in dirs:
            files = []
            for f in sorted(os.listdir(os.path.join(BOOKS, d))):
                full = os.path.join(BOOKS, d, f)
                if (f.endswith(".mp3") and not f.endswith(".part.mp3")) or (f.endswith(".json") and os.path.exists(full[:-5] + ".mp3")):
                    files.append({"p": d + "/" + f, "bytes": os.path.getsize(full)})
            if any(x["p"].endswith(".mp3") for x in files):
                if kindle:
                    for n, full in cls.kindle_files(d): files.append({"p": d + "/" + n, "bytes": os.path.getsize(full)})
                out.append({"name": d, "files": files})
        return out

    @classmethod
    def file_for(cls, p):
        """The finished file at p (book folder/file name) inside BOOKS, or None (nothing outside it, nothing half-made)."""
        parts = str(p).replace("\\", "/").split("/")
        if len(parts) != 2 or any(not x or x in (".", "..") for x in parts): return None
        if parts[1].lower().endswith(".epub"):
            # (a Kindle book: only one listed for that book, from its Kindle folder)
            return dict(cls.kindle_files(parts[0])).get(parts[1])
        if not (parts[1].endswith(".mp3") or parts[1].endswith(".json")) or ".part" in parts[1]: return None
        f = os.path.join(BOOKS, parts[0], parts[1])
        root = os.path.realpath(BOOKS)
        if not os.path.realpath(f).startswith(root + os.sep) or not os.path.isfile(f): return None
        return f

    @classmethod
    def make_chapter(cls, b, n, job, k):
        """Records chapter k with the best voice into <chapter>.mp3 (+ .json of where each sentence is). False if it
        stopped part-way to let the phone's sentences go first (it's picked up again, sentences made so far kept)."""
        import numpy as np
        ch = job["chapters"][k]
        f = ch["file"] if os.path.isabs(ch["file"]) else os.path.join(BOOKS, ch["file"])
        with open(f, encoding="utf-8") as h: paras = json.load(h)
        vmap = cls.voice_map(job)
        moods = cls.moods(job)
        who = cls.who({"voice": vmap["Narrator"]}) if "Narrator" in vmap else cls.who(job)
        dest = cls.chapter_path(job, k)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        parts, marks, at = [], [], 0
        rate = b.rate
        title = str(ch.get("name") or "").strip()
        lines = ([[title]] if title and ch.get("say_title", True) else []) + [p for p in paras if p]
        total = sum(len(p) for p in lines)
        done = 0
        for pi, para in enumerate(lines):
            for si, item in enumerate(para):
                # (an item is a sentence, or [sentence, voice]: a voice, or a name in the job's "voices", like "female")
                who_i = who
                if isinstance(item, list):
                    text = str(item[0]) if item else ""
                    if len(item) > 1 and item[1] is not None:
                        v = item[1]
                        who_i = cls.who({"voice": vmap.get(v, v) if isinstance(v, str) else v})
                    # (how the line is said: [sentence, speaker, mood], the mood's settings added to the speaker's voice)
                    if len(item) > 2 and item[2] and moods.get(item[2]):
                        who_i = (f"s{who_i}" if isinstance(who_i, int) else str(who_i)) + moods[item[2]]
                else: text = str(item)
                text = text.strip()
                if not text: continue
                if Ahead.queue or Ahead.live: return False          # (the phone's own reading comes first)
                if done % 5 == 0 and cls.earlier(n): return False     # (a job named before this one, like voice samples, goes next)
                key = ahead_key(text, who_i)
                got = Ahead.get(key)
                if got is None:
                    t0 = time.time()
                    pcm, r, sure = b.best_take(text, who_i) if sum(c.isalpha() for c in text) >= 4 else (*b.speak(text + ".", who_i), "ok")
                    Ahead.put(key, pcm, r)
                    got = (pcm, r)
                    cls.secs_work += time.time() - t0
                    cls.secs_made += len(pcm) / 2 / r
                pcm, r = got
                x = np.frombuffer(pcm, dtype="<i2")
                if r != rate: x = np.interp(np.arange(0, len(x), r / rate), np.arange(len(x)), x).astype("<i2")
                # (the take's own silence at the ends cut down; a breath between sentences, longer between paragraphs)
                loud = np.nonzero(np.abs(x) > 300)[0]
                if len(loud): x = x[max(0, loud[0] - int(0.05 * rate)): loud[-1] + int(0.1 * rate)]
                marks.append({"p": pi, "s": si, "voice": who_i, "start": round(at / rate, 3), "end": round((at + len(x)) / rate, 3), "text": text})
                parts.append(x); at += len(x)
                gap = int((0.75 if si == len(para) - 1 else 0.3) * rate)
                parts.append(np.zeros(gap, dtype="<i2")); at += gap
                done += 1
                if done % 10 == 0:
                    sp = cls.secs_made / cls.secs_work if cls.secs_work > 1 else 0
                    d, t = cls.progress()
                    cls.status(f"{job.get('title', n)}: chapter {k + 1} of {len(job['chapters'])} ({ch.get('name', '')}), sentence {done} of {total}. "
                               f"{d} of {t} chapters done. Recording at {sp:.2f}x reading speed.")
        import soundfile as sf
        audio = np.concatenate(parts) if parts else np.zeros(rate, dtype="<i2")
        with sf.SoundFile(dest + ".part.mp3", "w", rate, 1, format="MP3") as out: out.write(audio.astype(np.int16))
        with open(dest + ".json.part", "w", encoding="utf-8") as h:
            json.dump({"title": title, "rate": rate, "seconds": round(at / rate, 2), "sentences": marks}, h, ensure_ascii=False)
        os.replace(dest + ".json.part", dest + ".json")
        os.replace(dest + ".part.mp3", dest + ".mp3")
        d, t = cls.progress()
        sp = cls.secs_made / cls.secs_work if cls.secs_work > 1 else 0
        print(f"Loudbook PC voice: audiobook chapter {k + 1} of {job.get('title', n)} done ({at / rate / 60:.0f} min); {d} of {t}", flush=True)
        cls.status(f"{d} of {t} chapters done. Last: {job.get('title', n)} chapter {k + 1} ({ch.get('name', '')}). "
                   f"Recording at {sp:.2f}x reading speed." + (" All done." if d == t else ""))
        return True

    @classmethod
    def work(cls):
        """One chapter of an audiobook, if there's one to make. True if it did something."""
        if time.time() < cls.idle_until: return False
        nxt = cls.next_chapter()
        cls.pending = nxt is not None
        if nxt is None: cls.idle_until = time.time() + 20; return False
        b = best_engine()
        if b is None: cls.idle_until = time.time() + 300; cls.status("Waiting: the best-quality voice couldn't be loaded on this PC."); return False
        Ahead.last_used = time.time()
        try: cls.make_chapter(b, *nxt)
        except Exception as e:
            import traceback
            print("Loudbook PC voice: audiobook chapter failed:", repr(e)[:300], flush=True)
            print("".join(traceback.format_exc().splitlines(True)[-6:]), flush=True)
            cls.status(f"Stopped at {nxt[1].get('title', nxt[0])} chapter {nxt[2] + 1}: {e!r}"[:300])
            cls.idle_until = time.time() + 60
        Ahead.last_used = time.time()
        return True


def ahead_worker():
    while True:
        it = Ahead.next()
        if it is None and Books.work(): continue
        if it is None:
            # (nothing to make for half an hour: the full model gives the card back to Turbo for live reading)
            b = Handler.best
            if b is not None and time.time() - Ahead.last_used > 1800 and not Handler.natural_off:
                with Handler.gpu_lock:
                    if time.time() - Ahead.last_used > 1800 and Handler.best is b:
                        Handler.best = None
                        with b.lock: b.close()
                        print("Loudbook PC voice: nothing to make ahead: the natural voice (Turbo) is back", flush=True)
                        Handler.natural = load_natural(Handler.engine)
            continue
        key, text, ktext, who = it
        b = best_engine()
        if b is None:                                   # (couldn't load it: the sentence waits, and loading is tried later)
            with Ahead.cond:
                if key not in Ahead.queued: Ahead.queue.appendleft(it); Ahead.queued.add(key)
            time.sleep(30); continue
        while Ahead.live > 0: time.sleep(0.2)          # (a sentence the phone is waiting for comes first)
        Ahead.making = 1
        try:
            pcm, rate, sure = b.best_take(text, who, ktext)
            Ahead.put(key, pcm, rate)
            Ahead.made += 1
            if sure != "ok": Ahead.odd += 1
            Ahead.last_used = time.time()
        except Exception as e:
            Ahead.failed += 1
            print("Loudbook PC voice: making a sentence ahead failed:", repr(e)[:200], flush=True)
            time.sleep(2)
        finally:
            Ahead.making = 0


class FakeBest:
    """For tests: the best-quality voice as a different quiet tone, a little slow."""
    name, rate, best, too_slow = "test best", 24000, True, False
    def __init__(self): self.lock = threading.Lock(); self.voices = {}; self.calls = 0
    def speed(self): return 2.0
    def usable(self): return True
    def speak(self, text, sid):
        import array, math
        with self.lock:
            self.calls += 1
            time.sleep(float(os.environ.get("LOUDBOOK_FAKE_BEST_SECS", "0.05")))
            n = int(self.rate * min(10, 0.05 * len(text)))
            return array.array("h", (int(1200 * math.sin(i / 9)) for i in range(n))).tobytes(), self.rate
    def best_take(self, text, sid, ktext=None, takes=None):
        pcm, rate = self.speak(text, sid)
        return pcm, rate, "ok"
    def close(self): pass


class FakeEngine:
    """For tests: a quiet tone, as long as the text."""
    name, rate = "test tone", 24000
    def samples(self, text, sid): return [], self.rate

    def speak(self, text, sid, speed):
        import array, math
        n = int(self.rate * min(10, 0.05 * len(text)) / speed)
        return array.array("h", (int(800 * math.sin(i / 20)) for i in range(n))).tobytes(), self.rate


# ------------------------------------------------------------------ HTTP
def allowed_ip(ip):
    try: a = ipaddress.ip_address(ip.split("%")[0])
    except ValueError: return False
    if getattr(a, "ipv4_mapped", None): a = a.ipv4_mapped
    return a.is_private or a.is_loopback or a.is_link_local or (a.version == 4 and a in ipaddress.ip_network("100.64.0.0/10"))


class Limits:
    lock = threading.Lock()
    fails = {}            # ip -> [count, window start]
    busy = threading.BoundedSemaphore(6)

    @classmethod
    def blocked(cls, ip):
        with cls.lock:
            c = cls.fails.get(ip)
            return bool(c and c[0] >= 10 and time.time() - c[1] < 600)

    @classmethod
    def fail(cls, ip):
        with cls.lock:
            c = cls.fails.get(ip)
            if not c or time.time() - c[1] > 600: c = [0, time.time()]
            c[0] += 1
            cls.fails[ip] = c


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "Loudbook"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 20
    engine = None
    natural = None
    best = None                      # the full Chatterbox model, once something asks for it
    best_retry = 0
    natural_off = os.environ.get("LOUDBOOK_NATURAL", "1") == "0"
    gpu_lock = threading.Lock()
    fp = b""

    def log_message(self, fmt, *args): pass          # never log what's being read

    @staticmethod
    def can_best():
        """Is the full model installed here (it comes with the natural voice's packages)?"""
        if isinstance(Handler.engine, FakeEngine): return Handler.fake_best
        try:
            import importlib.util
            return importlib.util.find_spec("chatterbox") is not None
        except Exception: return False
    fake_best = False

    def reply(self, code, body=b"", ctype="application/json", headers=None):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        if self.headers.get("Transfer-Encoding"): raise ValueError("chunked bodies not accepted")
        n = int(self.headers.get("Content-Length") or 0)
        # only a paired phone may send a recording; everything else is small
        big = self.path == "/v1/voice/add" and self.headers.get("Authorization", "").startswith("Bearer ") \
            and token_ok(self.headers.get("Authorization", "")[7:].strip())
        if not big and self.path == "/v1/ahead" and self.headers.get("Authorization", "").startswith("Bearer ") \
            and token_ok(self.headers.get("Authorization", "")[7:].strip()):
            if n < 0 or n > MAX_AHEAD: raise ValueError("too big")
            return json.loads(self.rfile.read(n) or b"{}")
        if n < 0 or n > (MAX_VOICE if big else MAX_BODY): raise ValueError("too big")
        return json.loads(self.rfile.read(n) or b"{}")

    def gate(self):
        ip = self.client_address[0]
        if not allowed_ip(ip) or Limits.blocked(ip):
            self.close_connection = True
            self.reply(403, {"error": "not allowed"})
            return False
        return True

    def authed(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and token_ok(auth[7:].strip()): return True
        # (a phone that was unpaired keeps asking with its old key every 30 s: counted, that blocked it from pairing again
        # for 10 minutes. Keys are long random ones, nothing to guess; the pairing code's tries are limited on their own.)
        if not auth.startswith("Bearer "): Limits.fail(self.client_address[0])
        self.reply(401, {"error": "not paired"})
        return False

    def do_GET(self):
        if not self.gate(): return
        if self.path == "/v1/hello": return self.reply(200, {"app": "loudbook", "v": VERSION})
        if self.path == "/v1/health":
            if not self.authed(): return
            n = Handler.natural or Handler.best
            b = Handler.best
            a = Ahead.status()
            return self.reply(200, {"engine": Handler.engine.name, "rate": Handler.engine.rate, "host": socket.gethostname()[:40],
                                    "natural": n.name if n else "", "naturalSpeed": round(n.speed(), 2) if n else 0,
                                    "naturalOk": bool(n and not n.too_slow),
                                    "best": b.name if b else ("" if not Handler.can_best() else "not loaded yet"),
                                    "bestSpeed": round(b.speed(), 2) if b else 0,
                                    "aheadTotal": a["total"], "aheadWaiting": a["waiting"], "aheadMade": a["made"],
                                    "aheadOdd": a["odd"], "aheadLoading": a["loading"]})
        if self.path == "/v1/books" or self.path == "/v1/books?kindle=1":
            # the finished audiobook chapters (for the phone to copy and play with no connection), and the Kindle books
            if not self.authed(): return
            return self.reply(200, {"books": Books.finished(kindle=self.path.endswith("kindle=1"))})
        if self.path.startswith("/v1/books/get?"):
            if not self.authed(): return
            from urllib.parse import parse_qs
            f = Books.file_for(parse_qs(self.path.split("?", 1)[1]).get("p", [""])[0])
            if not f: return self.reply(404, {"error": "no such file"})
            n = os.path.getsize(f)
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg" if f.endswith(".mp3") else "application/epub+zip" if f.lower().endswith(".epub") else "application/json")
            self.send_header("Content-Length", str(n))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.connection.settimeout(60)
            with open(f, "rb") as h:
                while True:
                    b = h.read(256 * 1024)
                    if not b: break
                    self.wfile.write(b)
            return
        if self.path == "/v1/voices":
            if not self.authed(): return
            return self.reply(200, {"voices": [{"id": v["id"], "name": str(v.get("name", "")).replace("{", "(").replace("}", ")")} for v in voices()],
                                    "natural": Handler.natural is not None or Handler.best is not None})
        self.reply(404, {"error": "no such thing"})

    def do_POST(self):
        if not self.gate(): return
        if not Limits.busy.acquire(timeout=5): return self.reply(503, {"error": "busy"})
        try:
            try: req = self.body(); assert isinstance(req, dict)     # (a list or a string as the body: no answer at all before)
            except Exception: return self.reply(400, {"error": "bad request"})
            if self.path == "/v1/pair/start":
                try: nc = unb64(req["nc"]); assert len(nc) == 16
                except Exception: return self.reply(400, {"error": "bad request"})
                r = Pairing.start(nc, Handler.fp)
                return self.reply(200, r) if r else self.reply(403, {"error": "pairing is not on: run Pair a phone on the PC"})
            if self.path == "/v1/pair/finish":
                try: nc, ns, m = unb64(req["nc"]), unb64(req["ns"]), unb64(req["mac"])
                except Exception: return self.reply(400, {"error": "bad request"})
                tok = Pairing.finish(nc, ns, m, req.get("name", "phone"), Handler.fp)
                if not tok:
                    Limits.fail(self.client_address[0])
                    return self.reply(403, {"error": "wrong code"})
                return self.reply(200, {"token": tok})
            if self.path == "/v1/voice/add":
                if not self.authed(): return
                if Handler.natural is None and Handler.best is None:
                    return self.reply(409, {"error": "this PC has no natural voice (it needs an NVIDIA graphics card and Set up PC voice.bat)"})
                try: vid = add_voice(req.get("name", ""), base64.b64decode(str(req.get("wav", ""))))
                except Exception as e: return self.reply(400, {"error": str(e)[:200] or "not a recording"})
                return self.reply(200, {"id": vid})
            if self.path == "/v1/voice/delete":
                if not self.authed(): return
                delete_voice(str(req.get("id", "")))
                for e in (Handler.natural, Handler.best):
                    if e: e.voices.pop(str(req.get("id", "")), None)
                return self.reply(200, {})
            if self.path == "/v1/ahead":
                if not self.authed(): return
                if not Handler.can_best(): return self.reply(409, {"error": "this PC has no natural voice (it needs an NVIDIA graphics card and Set up PC voice.bat)"})
                vid = req.get("voice")
                if vid and (not voice_file(vid) or not os.path.exists(voice_file(vid))): return self.reply(404, {"error": "that recorded voice isn't on this PC"})
                items = []
                try:
                    for x in list(req.get("items") or [])[:MAX_AHEAD_ITEMS]:
                        text = str(x[0])[:MAX_TEXT]; ktext = str(x[1] or text)[:MAX_TEXT] if len(x) > 1 else text
                        sid = max(0, min(52, int(x[2]))) if len(x) > 2 else 3
                        if sum(ch.isalpha() for ch in text) < 4: continue      # (a lone word or symbols: read live)
                        who = vid if vid else sid
                        items.append((ahead_key(text, who), text, ktext, who))
                except (TypeError, ValueError, IndexError): return self.reply(400, {"error": "bad request"})
                total, done = Ahead.set(items)
                return self.reply(200, {"total": total, "ready": done})
            if self.path == "/v1/speak":
                if not self.authed(): return
                text = str(req.get("text", ""))[:MAX_TEXT]
                try: sid = max(0, min(52, int(req.get("sid", 3)))); speed = max(0.5, min(2.5, float(req.get("speed", 1.0))))
                except (TypeError, ValueError): return self.reply(400, {"error": "bad request"})
                if not text.strip(): return self.reply(400, {"error": "no text"})
                if req.get("engine") in ("best", "natural"): Books.remember_voice(req.get("voice") or sid)   # (for audiobooks "in the phone's voice")
                t0 = time.time()
                n = Handler.natural or Handler.best
                vid = req.get("voice")
                if req.get("engine") == "best":
                    # made ahead: straight from the folder. Otherwise read live (the full model when it keeps up and is
                    # free, else the natural voice or Kokoro), and the sentence is marked as passed for the maker
                    who = vid if vid else sid
                    key = ahead_key(text, who)
                    got = Ahead.get(key)
                    if got is not None:
                        Ahead.last_used = time.time()
                        return self.reply(200, got[0], "audio/L16", {"X-Sample-Rate": str(got[1]), "X-Work-Ms": str(int((time.time() - t0) * 1000)),
                                                                     "X-Made-At": "1.000", "X-Engine": "best"})
                    with Ahead.cond: Ahead.heard.add(key)
                    b = Handler.best
                    if b is not None and not b.lock.locked() and b.usable() and sum(ch.isalpha() for ch in text) >= 4:
                        Ahead.live += 1
                        try:
                            pcm, rate = b.speak(text, who)
                            Ahead.put(key, pcm, rate)
                            return self.reply(200, pcm, "audio/L16", {"X-Sample-Rate": str(rate), "X-Work-Ms": str(int((time.time() - t0) * 1000)),
                                                                      "X-Made-At": "1.000", "X-Engine": "best-live"})
                        except Exception as e:
                            print("Loudbook PC voice: best-quality voice failed:", repr(e)[:200], flush=True)
                        finally: Ahead.live -= 1
                    n = Handler.natural
                    if vid and n is None and b is not None: n = b       # (a recorded voice: the full model copies it, waiting its turn)
                    req = dict(req, engine="natural")
                if vid:
                    # a recorded voice: only the natural voice can copy one
                    if not voice_file(vid) or not os.path.exists(voice_file(vid)): return self.reply(404, {"error": "that recorded voice isn't on this PC"})
                    if n is None: return self.reply(409, {"error": "this PC has no natural voice"})
                    try:
                        pcm, rate = n.speak(text, vid)
                    except Exception as e:
                        print("Loudbook PC voice: recorded voice failed:", repr(e)[:200], flush=True)
                        return self.reply(500, {"error": "the recorded voice failed"})
                    return self.reply(200, pcm, "audio/L16", {"X-Sample-Rate": str(rate), "X-Work-Ms": str(int((time.time() - t0) * 1000)),
                                                              "X-Made-At": "1.000", "X-Engine": "recorded"})
                # the natural voice when asked for, unless it's proving too slow to keep up, or the
                # text is too short for it (a lone word can come out odd)
                use_natural = (n is not None and req.get("engine") == "natural" and sum(ch.isalpha() for ch in text) >= 4
                               and n.usable())
                if use_natural:
                    try:
                        pcm, rate = n.speak(text, sid); made_at = 1.0; used = "natural"
                    except Exception as e:
                        print("Loudbook PC voice: natural voice failed:", repr(e)[:200], flush=True)
                        use_natural = False
                if not use_natural:
                    # (the phone's respellings for Kokoro, e.g. heteronym verbs and dashes as pauses, if it sent them)
                    ktext = str(req.get("kokoro_text") or text)[:MAX_TEXT]
                    pcm, rate = Handler.engine.speak(ktext, sid, speed); made_at = speed; used = "kokoro"
                return self.reply(200, pcm, "audio/L16", {"X-Sample-Rate": str(rate), "X-Work-Ms": str(int((time.time() - t0) * 1000)),
                                                          "X-Made-At": f"{made_at:.3f}", "X-Engine": used})
            self.reply(404, {"error": "no such thing"})
        finally:
            Limits.busy.release()


class Server(http.server.ThreadingHTTPServer):
    """TLS is set up per connection on its own thread, so a slow or half-open client can't hold
    up anyone else; connections from outside the home network are dropped before that."""
    daemon_threads = True
    request_queue_size = 16
    allow_reuse_address = os.name != "nt"     # on Windows that would let another program take the port
    ssl_ctx = None
    slots = threading.BoundedSemaphore(24)

    def verify_request(self, request, client_address):
        return allowed_ip(client_address[0])

    def process_request(self, request, client_address):
        if not Server.slots.acquire(blocking=False):
            self.shutdown_request(request); return
        try: super().process_request(request, client_address)
        except Exception: Server.slots.release(); raise

    def process_request_thread(self, request, client_address):
        try: super().process_request_thread(request, client_address)
        finally: Server.slots.release()

    def finish_request(self, request, client_address):
        request.settimeout(10)
        tls = self.ssl_ctx.wrap_socket(request, server_side=True)
        try: self.RequestHandlerClass(tls, client_address, self)
        finally:
            try: tls.close()
            except OSError: pass

    def handle_error(self, request, client_address):     # bad handshakes, dropped connections
        if os.environ.get("LOUDBOOK_DEBUG"): import traceback; traceback.print_exc()


def discovery(stop):
    """Answers "where is Loudbook?" on the home network with the port and certificate fingerprint
    (no secrets), so a paired phone finds the PC even after its address changes."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if os.name == "nt": s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    s.bind(("0.0.0.0", DISCOVERY_PORT))
    s.settimeout(1)
    reply = json.dumps({"app": "loudbook", "port": PORT, "fp": Handler.fp.hex(), "name": socket.gethostname()[:40]}).encode()
    while not stop.is_set():
        try: data, addr = s.recvfrom(64)
        except socket.timeout: continue
        except OSError: continue
        if data.strip() == b"LOUDBOOK?" and allowed_ip(addr[0]):
            try: s.sendto(reply, addr)
            except OSError: pass


def serve(fake=False):
    cert, key = ensure_cert()
    Handler.fp = fingerprint()
    print("Loudbook PC voice: loading the voice...", flush=True)
    Handler.engine = FakeEngine() if fake else Engine()
    if not fake: Handler.natural = load_natural(Handler.engine)
    elif os.environ.get("LOUDBOOK_FAKE_BEST"): Handler.fake_best = True; Handler.best = FakeBest()
    threading.Thread(target=ahead_worker, daemon=True, name="ahead").start()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    Server.ssl_ctx = ctx
    httpd = Server(("0.0.0.0", PORT), Handler)
    stop = threading.Event()
    threading.Thread(target=discovery, args=(stop,), daemon=True).start()
    print(f"Loudbook PC voice: ready on port {PORT} ({Handler.engine.name}"
          + (f" + {Handler.natural.name}" if Handler.natural else "") + f"), fingerprint {Handler.fp.hex()[:16]}...", flush=True)
    threading.Thread(target=restart_when_updated, daemon=True).start()
    try: httpd.serve_forever()
    finally: stop.set()


def restart_when_updated():
    """Exits (code 3) when the server's code changes, so start-server.cmd brings it back up with
    the update. Updates arrive through the Loudbook-android folder."""
    here = os.path.dirname(os.path.abspath(__file__))
    files = [os.path.abspath(__file__), os.path.join(here, "pyproject.toml")]
    def stamp(): return [os.path.getmtime(f) if os.path.exists(f) else 0 for f in files]
    first = stamp()
    while True:
        time.sleep(60)
        if stamp() != first:
            print("Loudbook PC voice: updated, restarting...", flush=True)
            os._exit(3)


def local_addresses():
    out = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET): out.add(info[4][0])
    except OSError: pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("192.168.1.1", 9)); out.add(s.getsockname()[0]); s.close()
    except OSError: pass
    return sorted(a for a in out if allowed_ip(a) and not a.startswith("127."))


def pair():
    ensure_cert()
    code = "".join(secrets.choice(ALPHABET) for _ in range(12))
    shown = "-".join(code[i:i + 4] for i in range(0, 12, 4))
    before = len(read_json("devices.json", []))
    write_json("pairing.json", {"key": b64(pair_key(code)), "expires": time.time() + PAIR_MINUTES * 60, "tries": 0})
    try:
        s = socket.create_connection(("127.0.0.1", PORT), timeout=2); s.close(); running = True
    except OSError: running = False
    print()
    print("  Pair a phone with Loudbook on this PC")
    print("  =====================================")
    if not running:
        print("  (The Loudbook voice server isn't running. Run \"Set up PC voice.bat\" first.)")
    print()
    print("  On the phone: Loudbook > settings > Use my PC for the voice > Pair.")
    print("  PC address:   " + (", ".join(local_addresses()) or "see ipconfig"))
    print("  Code:         " + shown)
    print()
    print(f"  The code works once, for {PAIR_MINUTES} minutes. Waiting for the phone...")
    end = time.time() + PAIR_MINUTES * 60
    try:
        while time.time() < end:
            if len(read_json("devices.json", [])) > before:
                print("\n  Paired. You can close this window.")
                return 0
            if not Pairing.active():
                break
            time.sleep(1)
        print("\n  No phone paired (the code expired or was typed wrong too many times). Run this again for a new code.")
        return 1
    finally:
        p = read_json("pairing.json", None)
        if p: p["expires"] = 0; write_json("pairing.json", p)


def forget():
    write_json("devices.json", [])
    print("Every phone has been unpaired. Pair again with \"Pair a phone.bat\".")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if cmd == "serve": serve(fake="--fake" in sys.argv)
    elif cmd == "pair": sys.exit(pair())
    elif cmd == "forget": forget()
    else: print(__doc__)
