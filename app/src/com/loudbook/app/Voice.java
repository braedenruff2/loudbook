package com.loudbook.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Kokoro, running on the phone through sherpa-onnx. The dictionaries and espeak-ng's data ship
 * inside the app (assets/kokoro) and are copied out as real files once per version; the model
 * and voices are downloaded once (see fetchPack).
 */
final class Voice {
    static final String TAG = "Loudbook";
    static final String DIR = "kokoro";

    /** Kokoro v1.0 English voices, best first, with sherpa-onnx's speaker numbers. */
    static final String[][] VOICES = {
        {"3", "Heart — US female · A"}, {"2", "Bella — US female · A-"}, {"6", "Nicole — US female, soft · B-"},
        {"21", "Emma — UK female · B-"}, {"16", "Michael — US male · C+"}, {"14", "Fenrir — US male · C+"},
        {"18", "Puck — US male · C+"}, {"1", "Aoede — US female · C+"}, {"5", "Kore — US female · C+"},
        {"9", "Sarah — US female · C+"}, {"26", "George — UK male · C"}, {"25", "Fable — UK male · C"},
        {"22", "Isabella — UK female · C"}, {"7", "Nova — US female · C"}, {"0", "Alloy — US female · C"},
        {"10", "Sky — US female · C-"}, {"27", "Lewis — UK male · D+"}, {"13", "Eric — US male · D"},
        {"15", "Liam — US male · D"}, {"17", "Onyx — US male, deep · D"}, {"12", "Echo — US male · D"},
        {"24", "Daniel — UK male · D"}, {"20", "Alice — UK female · D"}, {"23", "Lily — UK female · D"},
        {"11", "Adam — US male · F+"},
    };

    private OfflineTts tts;
    int sampleRate = 24000;
    int threads = 1;

    static final class Clip {
        final float[] samples; final int rate; final float madeAt; final long ms;
        Clip(float[] s, int r, float speed, long ms) { samples = s; rate = r; madeAt = speed; this.ms = ms; }
        double seconds() { return samples.length / (double) rate; }
    }

    /** The model and voices (354 MB) aren't in the app itself, so updates stay small: they're
     *  downloaded once, from this app's own GitHub release, the first time. */
    static final String[][] PACK = {{"model.onnx", "325560556"}, {"voices.bin", "28200960"}};

    static boolean packInApk(Context ctx) {
        try { for (String h : ctx.getAssets().list(DIR)) if (h.equals("model.onnx")) return true; } catch (Exception ignored) { }
        return false;
    }
    static File packDir(Context ctx) { return new File(ctx.getFilesDir(), "voice"); }
    static boolean packReady(Context ctx) {
        if (packInApk(ctx)) return true;
        for (String[] f : PACK) { File x = new File(packDir(ctx), f[0]); if (x.length() != Long.parseLong(f[1])) return false; }
        return true;
    }
    static long packBytes() { long n = 0; for (String[] f : PACK) n += Long.parseLong(f[1]); return n; }

    /** Downloads whatever is missing of the voice files, reporting overall progress. */
    static void fetchPack(Context ctx, Net.Progress p) throws Exception {
        if (packInApk(ctx)) return;
        final long all = packBytes();
        long before = 0;
        for (String[] f : PACK) {
            final long base = before;
            long size = Long.parseLong(f[1]);
            Net.download(BuildInfo.VOICE_URL + f[0], new File(packDir(ctx), f[0]), size,
                (d, t) -> { if (p != null) p.on(base + d, all); });
            before += size;
        }
    }

    /** Slow (several seconds): call off the main thread. The voice files must be ready. */
    synchronized void load(Context ctx) throws Exception {
        if (tts != null) return;
        threads = fastCores();
        long t0 = System.currentTimeMillis();
        if (packInApk(ctx)) {
            AssetManager am = ctx.getAssets();
            OfflineTtsConfig config = config(DIR + "/model.onnx", DIR + "/voices.bin", DIR + "/tokens.txt",
                join(am, DIR, "lexicon-us-en.txt", "lexicon-zh.txt"), copyAssetDir(ctx, DIR + "/espeak-ng-data"));
            tts = new OfflineTts(am, config);
        } else {
            // everything as real files: the small parts come out of the app, the big ones were downloaded
            String small = copyAssetDir(ctx, DIR);
            File pack = packDir(ctx);
            StringBuilder lex = new StringBuilder();
            for (String n : new String[]{"lexicon-us-en.txt", "lexicon-zh.txt"})
                if (new File(small, n).exists()) { if (lex.length() > 0) lex.append(','); lex.append(small).append('/').append(n); }
            OfflineTtsConfig config = config(new File(pack, "model.onnx").getPath(), new File(pack, "voices.bin").getPath(),
                small + "/tokens.txt", lex.toString(), small + "/espeak-ng-data");
            tts = new OfflineTts(config);
        }
        sampleRate = tts.getSampleRate();
        try { tts.generate("Ready.", 3, 1.0f); } catch (Throwable t) { Log.w(TAG, "warm-up", t); }   // the first run is slow
        Log.i(TAG, "Kokoro loaded in " + (System.currentTimeMillis() - t0) + " ms, " + threads + " threads, " + tts.getNumSpeakers() + " voices");
    }

    private OfflineTtsConfig config(String model, String voices, String tokens, String lexicon, String dataDir) {
        OfflineTtsKokoroModelConfig kokoro = OfflineTtsKokoroModelConfig.builder()
            .setModel(model).setVoices(voices).setTokens(tokens).setLexicon(lexicon).setDataDir(dataDir).build();
        OfflineTtsModelConfig m = OfflineTtsModelConfig.builder()
            .setKokoro(kokoro).setNumThreads(threads).setDebug(false).setProvider("cpu").build();
        return OfflineTtsConfig.builder().setModel(m).setMaxNumSentences(1).build();
    }

    boolean ready() { return tts != null || pcOk; }
    boolean localReady() { return tts != null; }

    // ---------------------------------------------------------------- the PC voice
    // When a PC is paired (pc-server on the PC), the speech is made there and the phone just plays
    // it, which saves its battery. If the PC can't be reached, the phone's own voice takes over,
    // and the PC is tried again every half minute.
    private Context app;
    volatile boolean pcOk = false;
    volatile long pcRetryAt = 0;
    volatile String pcError = "", lastSource = "";
    volatile PcLink.Health pcHealth;

    void attach(Context c) { app = c.getApplicationContext(); }
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("lb", Context.MODE_PRIVATE); }

    /** The paired PC, if "read with my PC" is on. */
    PcLink pcLink() {
        if (app == null) return null;
        android.content.SharedPreferences p = prefs(app);
        if (!p.getBoolean("pcOn", false) || p.getString("pcToken", "").isEmpty()) return null;
        return new PcLink(p.getString("pcHost", ""), p.getInt("pcPort", PcLink.PORT), p.getString("pcFp", ""), p.getString("pcToken", ""));
    }

    /** Checks the PC now (a couple of seconds at most). */
    boolean checkPc() {
        PcLink l = pcLink();
        if (l == null) { pcOk = false; return false; }
        try { pcHealth = l.health(3000); pcOk = true; pcError = ""; pcRetryAt = 0; return true; }
        catch (Exception e) { pcOk = false; pcError = String.valueOf(e.getMessage()); pcRetryAt = System.currentTimeMillis() + 30_000; rediscover(l); return false; }
    }

    /** The PC may have a new address on the home network: look for the one with our fingerprint. */
    void rediscover(PcLink l) {
        for (PcLink.Found f : PcLink.discover(1200)) {
            if (f.fp != null && f.fp.equals(l.fp) && (!f.host.equals(l.host) || f.port != l.port)) {
                prefs(app).edit().putString("pcHost", f.host).putInt("pcPort", f.port).apply();
                pcRetryAt = 0;
                Log.i(TAG, "PC voice moved to " + f.host);
                return;
            }
        }
    }

    /** Thrown when the PC failed and the phone's own voice isn't loaded yet. */
    static final class NeedLocal extends RuntimeException { NeedLocal(String m) { super(m); } }

    /**
     * How many threads to give Kokoro: one per fast core. Phones mix fast and slow cores, and a
     * thread that lands on a slow core holds all the others up, so the slow ones are left out.
     */
    static int fastCores() {
        int n = Math.max(1, Runtime.getRuntime().availableProcessors());
        java.util.List<Long> f = new java.util.ArrayList<>();
        File[] cpus = new File("/sys/devices/system/cpu").listFiles();
        if (cpus != null) for (File c : cpus) {
            if (!c.getName().matches("cpu\\d+")) continue;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(c, "cpufreq/cpuinfo_max_freq")))) {
                f.add(Long.parseLong(r.readLine().trim()));
            } catch (Exception ignored) { }
        }
        long top = 0;
        for (long x : f) top = Math.max(top, x);
        if (top == 0) return Math.max(1, Math.min(4, n / 2));
        int fast = 0;
        for (long x : f) if (x >= top * 0.7) fast++;
        return Math.max(1, Math.min(4, Math.min(fast, n)));
    }

    /** One chunk of speech. sid: speaker number; speed: 1.0 = normal. */
    Clip speak(String text, int sid, float speed) {
        long t0 = System.currentTimeMillis();
        // nothing to say (a scene break, a row of symbols): a short silence, without asking anyone
        if (!text.codePoints().anyMatch(Character::isLetterOrDigit)) return new Clip(new float[2400], 24000, speed, 0);
        PcLink l = pcLink();
        if (l != null && (pcOk || System.currentTimeMillis() >= pcRetryAt)) {
            try {
                String engine = prefs(app).getString("pcEngine", "natural");
                String rec = prefs(app).getString("pcVoice", "");          // a voice recorded on the PC
                PcLink.Audio a = l.speak(text, sid, speed, engine, rec.isEmpty() ? null : rec, 30000);
                pcOk = true; pcError = ""; lastSource = "pc:" + a.engine;
                float[] f = new float[a.pcm.length];
                for (int i = 0; i < f.length; i++) f[i] = a.pcm[i] / 32768f;
                // the natural voice comes at normal speed; the phone speeds it up as it plays
                return new Clip(trim(f, a.rate), a.rate, a.madeAt, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                Log.w(TAG, "PC voice failed", e);
                pcOk = false; pcError = String.valueOf(e.getMessage());
                pcRetryAt = System.currentTimeMillis() + 30_000;
                new Thread(() -> rediscover(l), "lb-find-pc").start();
                if (tts == null) throw new NeedLocal(pcError);
            }
        }
        if (tts == null) throw new NeedLocal("the voice isn't loaded");
        lastSource = "phone";
        GeneratedAudio a;
        synchronized (this) { a = tts.generate(text, sid, speed); }
        return new Clip(trim(a.getSamples(), a.getSampleRate()), a.getSampleRate(), speed, System.currentTimeMillis() - t0);
    }

    /** Clips one after another, with a short breath between (all at the first one's rate and speed). */
    static Clip join(java.util.List<Clip> clips, double gapSecs) {
        Clip first = clips.get(0);
        int rate = first.rate, gap = (int) (gapSecs * rate);
        int n = 0;
        for (Clip c : clips) n += (c.rate == rate ? c.samples.length : (int) ((long) c.samples.length * rate / c.rate)) + gap;
        float[] out = new float[Math.max(0, n - gap)];
        int at = 0; long ms = 0;
        for (int k = 0; k < clips.size(); k++) {
            Clip c = clips.get(k);
            float[] x = c.samples;
            if (c.rate != rate) {                                  // (a different engine: match the rate)
                float[] y = new float[(int) ((long) x.length * rate / c.rate)];
                for (int j = 0; j < y.length; j++) { double p = (double) j * c.rate / rate; int q = (int) p; double f = p - q;
                    y[j] = (float) (x[Math.min(q, x.length - 1)] * (1 - f) + x[Math.min(q + 1, x.length - 1)] * f); }
                x = y;
            }
            System.arraycopy(x, 0, out, at, Math.min(x.length, out.length - at));
            at += x.length + (k < clips.size() - 1 ? gap : 0);
            ms += c.ms;
        }
        return new Clip(out, rate, first.madeAt, ms);
    }

    /** Kokoro pads each sentence with silence; keep just a natural sliver so sentences and
     *  paragraphs run on like speech. */
    static float[] trim(float[] s, int rate) {
        if (s == null || s.length == 0) return s;
        final float quiet = 0.004f;
        int first = 0, last = s.length - 1;
        while (first < s.length && Math.abs(s[first]) < quiet) first++;
        while (last > first && Math.abs(s[last]) < quiet) last--;
        if (first >= last) return s;
        int from = Math.max(0, first - rate * 60 / 1000);
        int to = Math.min(s.length, last + 1 + rate * 140 / 1000);
        if (from == 0 && to == s.length) return s;
        float[] out = new float[to - from];
        System.arraycopy(s, from, out, 0, out.length);
        return out;
    }

    // ---------------------------------------------------------------- assets
    private static String join(AssetManager am, String dir, String... names) throws Exception {
        String[] have = am.list(dir);
        StringBuilder sb = new StringBuilder();
        for (String n : names) for (String h : have) if (h.equals(n)) { if (sb.length() > 0) sb.append(','); sb.append(dir).append('/').append(n); }
        return sb.toString();
    }
    /** Copies an asset folder into the app's private files once; returns the real path. */
    static String copyAssetDir(Context ctx, String assetDir) throws Exception {
        File dest = new File(ctx.getFilesDir(), assetDir);
        File done = new File(dest, ".copied-" + BuildInfo.VERSION);
        if (done.exists()) return dest.getAbsolutePath();
        copy(ctx.getAssets(), assetDir, dest);
        done.createNewFile();
        return dest.getAbsolutePath();
    }
    private static void copy(AssetManager am, String path, File dest) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {           // a file
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        dest.mkdirs();
        for (String k : kids) copy(am, path + "/" + k, new File(dest, k));
    }
}
