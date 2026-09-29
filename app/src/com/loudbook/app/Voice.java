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
        threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
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
        Log.i(TAG, "Kokoro loaded in " + (System.currentTimeMillis() - t0) + " ms, " + threads + " threads, " + tts.getNumSpeakers() + " voices");
    }

    private OfflineTtsConfig config(String model, String voices, String tokens, String lexicon, String dataDir) {
        OfflineTtsKokoroModelConfig kokoro = OfflineTtsKokoroModelConfig.builder()
            .setModel(model).setVoices(voices).setTokens(tokens).setLexicon(lexicon).setDataDir(dataDir).build();
        OfflineTtsModelConfig m = OfflineTtsModelConfig.builder()
            .setKokoro(kokoro).setNumThreads(threads).setDebug(false).setProvider("cpu").build();
        return OfflineTtsConfig.builder().setModel(m).setMaxNumSentences(1).build();
    }

    boolean ready() { return tts != null; }

    /** One chunk of speech. sid: speaker number; speed: 1.0 = normal. */
    Clip speak(String text, int sid, float speed) {
        long t0 = System.currentTimeMillis();
        GeneratedAudio a;
        synchronized (this) { a = tts.generate(text, sid, speed); }
        return new Clip(a.getSamples(), a.getSampleRate(), speed, System.currentTimeMillis() - t0);
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
