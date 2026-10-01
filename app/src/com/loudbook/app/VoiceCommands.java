package com.loudbook.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.File;
import java.util.Locale;

/**
 * Hands-free control: a small keyword spotter listens for the six command words, and each time
 * it hears one, a speaker check compares the voice with the profile recorded in setup. Only your
 * voice counts, so the TV, other people and Loudbook's own reading are ignored. Everything runs
 * on the phone; no audio leaves it.
 */
final class VoiceCommands {
    static final String TAG = "Loudbook";
    static final String[] WORDS = {"play", "pause", "back", "forward", "beginning", "end"};
    static final String[] FILES = {"kws-encoder.onnx", "kws-decoder.onnx", "kws-joiner.onnx", "kws-tokens.txt", "keywords.txt", "speaker.onnx"};
    static final int RATE = 16000;

    interface Handler { void onCommand(String word, float score); void onRejected(String word, float score, float reader); }

    private KeywordSpotter kws;
    private SpeakerEmbeddingExtractor spk;
    private volatile Thread listener;
    /** The fingerprint of Loudbook's own reading voice, so a word it reads aloud is never taken as a command. */
    private volatile float[] readerVoice;
    private int readerVoiceId = -1;
    private volatile boolean listening;

    // ---------------------------------------------------------------- files + models
    static File dir(Context c) { return new File(c.getFilesDir(), "commands"); }
    static boolean downloaded(Context c) {
        for (String f : FILES) if (!new File(dir(c), f).isFile()) return false;
        return true;
    }
    static void fetch(Context c, Net.Progress p) throws Exception {
        for (int i = 0; i < FILES.length; i++) {
            final int k = i;
            Net.download(BuildInfo.RELEASES + "download/commands-1/" + FILES[i], new File(dir(c), FILES[i]), 0,
                (d, t) -> { if (p != null) p.on(k * 100L + (t > 0 ? 100 * d / t : 0), FILES.length * 100L); });
        }
    }

    synchronized void load(Context c) {
        if (kws != null) return;
        File d = dir(c);
        // the word list gets tuned now and then: pick up the latest (it's tiny); offline is fine
        try {
            String k = Net.text(BuildInfo.RELEASES + "download/commands-1/keywords.txt");
            File f = new File(d, "keywords.txt");
            if (k.contains("@play") && k.contains("@pause")) {
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(k.getBytes("UTF-8")); }
            }
        } catch (Exception e) { Log.i(TAG, "keywords refresh skipped: " + e.getMessage()); }
        OnlineTransducerModelConfig tr = OnlineTransducerModelConfig.builder()
            .setEncoder(new File(d, "kws-encoder.onnx").getPath())
            .setDecoder(new File(d, "kws-decoder.onnx").getPath())
            .setJoiner(new File(d, "kws-joiner.onnx").getPath()).build();
        OnlineModelConfig m = OnlineModelConfig.builder().setTransducer(tr)
            .setTokens(new File(d, "kws-tokens.txt").getPath()).setNumThreads(1).setProvider("cpu").build();
        KeywordSpotterConfig kc = KeywordSpotterConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(80).build())
            .setOnlineModelConfig(m).setKeywordsFile(new File(d, "keywords.txt").getPath())
            .setKeywordsThreshold(0.25f).setKeywordsScore(1.0f).setMaxActivePaths(4).build();
        kws = new KeywordSpotter(kc);
        spk = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
            .setModel(new File(d, "speaker.onnx").getPath()).setNumThreads(1).setProvider("cpu").build());
    }
    boolean loaded() { return kws != null; }

    /** A unit-length voice fingerprint of a bit of speech. */
    synchronized float[] embed(float[] audio) {
        OnlineStream s = spk.createStream();
        s.acceptWaveform(audio, RATE);
        s.inputFinished();
        float[] e = spk.compute(s);
        s.release();
        return unit(e);
    }

    /** Which command word, if any, the spotter hears in a recording. */
    synchronized String spot(float[] audio) {
        OnlineStream s = kws.createStream();
        float[] pad = new float[RATE / 2];
        s.acceptWaveform(pad, RATE); s.acceptWaveform(audio, RATE); s.acceptWaveform(pad, RATE);
        s.inputFinished();
        String found = null;
        while (kws.isReady(s)) {
            kws.decode(s);
            String k = kws.getResult(s).getKeyword();
            if (k != null && !k.isEmpty()) { found = k; kws.reset(s); }
        }
        s.release();
        return found;
    }

    /** Learns what the reading voice sounds like from a bit of its speech (any sample rate). */
    void learnReader(int voiceId, float[] samples, int rate) {
        if (!loaded() || voiceId == readerVoiceId || samples == null || samples.length < rate) return;
        int n = (int) Math.min(samples.length, rate * 4L);
        int m = (int) ((long) n * RATE / rate);
        float[] r = new float[m];
        for (int i = 0; i < m; i++) {
            double x = (double) i * rate / RATE; int j = (int) x; double f = x - j;
            r[i] = (float) (samples[Math.min(j, n - 1)] * (1 - f) + samples[Math.min(j + 1, n - 1)] * f);
        }
        float[] part = speechPart(r);
        if (part == null) return;
        readerVoice = embed(part);
        readerVoiceId = voiceId;
    }

    // ---------------------------------------------------------------- your voice
    static float[] profile(Context c) {
        String s = prefs(c).getString("voiceProfile", "");
        if (s.isEmpty()) return null;
        String[] p = s.split(",");
        float[] e = new float[p.length];
        for (int i = 0; i < p.length; i++) e[i] = Float.parseFloat(p[i]);
        return e;
    }
    static float threshold(Context c) { return prefs(c).getFloat("voiceThreshold", 0.45f) + prefs(c).getFloat("voiceStrict", 0f); }
    static void saveProfile(Context c, float[] e, float threshold) {
        StringBuilder sb = new StringBuilder();
        for (float x : e) { if (sb.length() > 0) sb.append(','); sb.append(String.format(Locale.US, "%.5f", x)); }
        prefs(c).edit().putString("voiceProfile", sb.toString()).putFloat("voiceThreshold", threshold).apply();
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("lb", Context.MODE_PRIVATE); }

    /**
     * Builds the profile from the setup recordings: the average fingerprint, and a bar set a
     * little below how well each recording matches the others.
     */
    static float[] buildProfile(java.util.List<float[]> embs, float[] outThreshold) {
        int dim = embs.get(0).length;
        float[] mean = new float[dim];
        for (float[] e : embs) for (int i = 0; i < dim; i++) mean[i] += e[i];
        mean = unit(mean);
        float worst = 1f;
        for (int k = 0; k < embs.size(); k++) {
            float[] rest = new float[dim];
            for (int j = 0; j < embs.size(); j++) if (j != k) for (int i = 0; i < dim; i++) rest[i] += embs.get(j)[i];
            worst = Math.min(worst, dot(unit(rest), embs.get(k)));
        }
        outThreshold[0] = Math.max(0.3f, Math.min(0.7f, worst - 0.12f));
        return mean;
    }

    static float dot(float[] a, float[] b) { float s = 0; for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i]; return s; }
    static float[] unit(float[] e) {
        double n = 0; for (float x : e) n += x * x;
        n = Math.sqrt(n) + 1e-9;
        float[] o = new float[e.length];
        for (int i = 0; i < e.length; i++) o[i] = (float) (e[i] / n);
        return o;
    }

    // ---------------------------------------------------------------- microphone
    static AudioRecord openMic() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));       // ~0.5 s
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); throw new IllegalStateException("the microphone is busy"); }
        // cut down on Loudbook's own voice coming back in through the mic
        try { if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler a = AcousticEchoCanceler.create(r.getAudioSessionId()); if (a != null) a.setEnabled(true); } } catch (Throwable ignored) { }
        try { if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(r.getAudioSessionId()); if (n != null) n.setEnabled(true); } } catch (Throwable ignored) { }
        return r;
    }

    /** Records for a while (setup). Blocking: call off the main thread. */
    static float[] record(double secs) {
        AudioRecord r = openMic();
        short[] buf = new short[(int) (secs * RATE)];
        try {
            r.startRecording();
            int off = 0;
            while (off < buf.length) { int n = r.read(buf, off, Math.min(1600, buf.length - off)); if (n <= 0) break; off += n; }
        } finally { try { r.stop(); } catch (Exception ignored) { } r.release(); }
        float[] f = new float[buf.length];
        for (int i = 0; i < buf.length; i++) f[i] = buf[i] / 32768f;
        return f;
    }

    /** The loud part of a recording (the word), with a little either side; null if it's silent. */
    static float[] speechPart(float[] a) {
        int win = RATE / 50;                                          // 20 ms
        int n = a.length / win;
        if (n == 0) return null;
        double[] rms = new double[n];
        double peak = 0;
        for (int w = 0; w < n; w++) { double s = 0; for (int i = w * win; i < (w + 1) * win; i++) s += a[i] * a[i]; rms[w] = Math.sqrt(s / win); peak = Math.max(peak, rms[w]); }
        if (peak < 0.01) return null;
        int first = -1, last = -1;
        for (int w = 0; w < n; w++) if (rms[w] > peak * 0.12) { if (first < 0) first = w; last = w; }
        int from = Math.max(0, (first - 8) * win), to = Math.min(a.length, (last + 9) * win);
        float[] o = new float[to - from];
        System.arraycopy(a, from, o, 0, o.length);
        return o;
    }

    // ---------------------------------------------------------------- listening
    boolean listening() { return listening; }

    void start(Context c, Handler h) {
        if (listening) return;
        final float[] prof = profile(c);
        if (prof == null || !loaded()) return;
        listening = true;
        Thread t = new Thread(() -> listen(c, prof, h), "loudbook-commands");
        listener = t;
        t.start();
    }

    void stop() {
        listening = false;
        Thread t = listener;
        listener = null;
        if (t != null) { try { t.join(1500); } catch (InterruptedException ignored) { } }
    }

    private void listen(Context c, float[] prof, Handler h) {
        AudioRecord r = null;
        OnlineStream s = null;
        try {
            r = openMic();
            r.startRecording();
            synchronized (this) { s = kws.createStream(); }
            short[] buf = new short[1600];                            // 100 ms
            float[] ring = new float[RATE * 2];                       // the last 2 s
            int ringPos = 0;
            long cooldownUntil = 0;
            while (listening) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) { Thread.sleep(20); continue; }
                float[] f = new float[n];
                for (int i = 0; i < n; i++) { f[i] = buf[i] / 32768f; ring[ringPos] = f[i]; ringPos = (ringPos + 1) % ring.length; }
                String heard = null;
                synchronized (this) {
                    s.acceptWaveform(f, RATE);
                    while (kws.isReady(s)) {
                        kws.decode(s);
                        KeywordSpotterResult res = kws.getResult(s);
                        if (res.getKeyword() != null && !res.getKeyword().isEmpty()) { heard = res.getKeyword(); kws.reset(s); }
                    }
                }
                if (heard == null || System.currentTimeMillis() < cooldownUntil) continue;
                // the word is in the last second or so: check whose voice it is
                int len = (int) (RATE * 1.3);
                float[] seg = new float[len];
                for (int i = 0; i < len; i++) seg[i] = ring[(ringPos - len + i + ring.length * 2) % ring.length];
                float[] part = speechPart(seg);
                float[] e = part == null ? null : embed(part);
                float score = e == null ? 0 : dot(prof, e);
                float[] rv = readerVoice;
                float reader = e == null || rv == null ? -1 : dot(rv, e);
                if (score >= threshold(c) && score > reader) { cooldownUntil = System.currentTimeMillis() + 1200; h.onCommand(heard, score); }
                else h.onRejected(heard, score, reader);
            }
        } catch (Throwable t) {
            Log.w(TAG, "voice commands stopped", t);
        } finally {
            listening = false;
            if (s != null) s.release();
            if (r != null) { try { r.stop(); } catch (Exception ignored) { } r.release(); }
        }
    }
}
