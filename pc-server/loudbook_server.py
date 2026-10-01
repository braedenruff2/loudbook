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

  python loudbook_server.py serve      run the server (the setup script starts it at log-on)
  python loudbook_server.py pair       show a pairing code and wait for the phone
  python loudbook_server.py forget     unpair every phone
"""
import base64, datetime, hashlib, hmac, http.server, ipaddress, json, os, secrets, socket, ssl, sys
import threading, time

VERSION = 1
PORT = int(os.environ.get("LOUDBOOK_PORT", "8770"))
DISCOVERY_PORT = PORT + 1
MAX_BODY = 16 * 1024
MAX_TEXT = 2000
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


def write_json(name, data):
    tmp = path(name + ".tmp")
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


class FakeEngine:
    """For tests: a quiet tone, as long as the text."""
    name, rate = "test tone", 24000
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
    fp = b""

    def log_message(self, fmt, *args): pass          # never log what's being read

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
        if n < 0 or n > MAX_BODY: raise ValueError("too big")
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
        Limits.fail(self.client_address[0])
        self.reply(401, {"error": "not paired"})
        return False

    def do_GET(self):
        if not self.gate(): return
        if self.path == "/v1/hello": return self.reply(200, {"app": "loudbook", "v": VERSION})
        if self.path == "/v1/health":
            if not self.authed(): return
            return self.reply(200, {"engine": Handler.engine.name, "rate": Handler.engine.rate, "host": socket.gethostname()})
        self.reply(404, {"error": "no such thing"})

    def do_POST(self):
        if not self.gate(): return
        if not Limits.busy.acquire(timeout=5): return self.reply(503, {"error": "busy"})
        try:
            try: req = self.body()
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
            if self.path == "/v1/speak":
                if not self.authed(): return
                text = str(req.get("text", ""))[:MAX_TEXT]
                try: sid = max(0, min(52, int(req.get("sid", 3)))); speed = max(0.5, min(2.5, float(req.get("speed", 1.0))))
                except (TypeError, ValueError): return self.reply(400, {"error": "bad request"})
                if not text.strip(): return self.reply(400, {"error": "no text"})
                t0 = time.time()
                pcm, rate = Handler.engine.speak(text, sid, speed)
                return self.reply(200, pcm, "audio/L16", {"X-Sample-Rate": str(rate), "X-Work-Ms": str(int((time.time() - t0) * 1000))})
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

    def handle_error(self, request, client_address): pass     # bad handshakes, dropped connections


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
    print("Loudbook PC voice: loading the voice…", flush=True)
    Handler.engine = FakeEngine() if fake else Engine()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    Server.ssl_ctx = ctx
    httpd = Server(("0.0.0.0", PORT), Handler)
    stop = threading.Event()
    threading.Thread(target=discovery, args=(stop,), daemon=True).start()
    print(f"Loudbook PC voice: ready on port {PORT} ({Handler.engine.name}), fingerprint {Handler.fp.hex()[:16]}…", flush=True)
    try: httpd.serve_forever()
    finally: stop.set()


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
    print(f"  The code works once, for {PAIR_MINUTES} minutes. Waiting for the phone…")
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
