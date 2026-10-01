package com.loudbook.app;

import com.k2fsa.sherpa.onnx.*;

/** Runs the app's VoiceCommands (load, spot, embed, profile) on the real sherpa-onnx JNI with the
 *  model files the phone downloads, using Kokoro to say the words. */
public class CommandsTest {
    public static void main(String[] a) throws Exception {
        java.io.File files = new java.io.File(a[0]);          // contains commands/ with the downloaded files
        String kokoro = a[1];
        android.content.Context ctx = new android.content.Context(files);
        VoiceCommands vc = new VoiceCommands();
        System.out.println("loading..."); System.out.flush();
        vc.load(ctx);
        System.out.println("loaded"); System.out.flush();
        OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
            .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(kokoro + "/model.onnx").setVoices(kokoro + "/voices.bin")
                .setTokens(kokoro + "/tokens.txt").setLexicon(kokoro + "/lexicon-us-en.txt").setDataDir(kokoro + "/espeak-ng-data").build())
            .setNumThreads(2).build()).build());
        java.util.List<float[]> embs = new java.util.ArrayList<>();
        int found = 0;
        for (String w : VoiceCommands.WORDS) {
            GeneratedAudio g = tts.generate(Character.toUpperCase(w.charAt(0)) + w.substring(1) + ".", 16, 1.0f);
            float[] x = resample(g.getSamples(), g.getSampleRate());
            float[] rec = new float[x.length + 8000]; System.arraycopy(x, 0, rec, 4000, x.length);
            String got = vc.spot(rec);
            float[] part = VoiceCommands.speechPart(rec);
            float[] e = part == null ? null : vc.embed(part);
            if (e != null) embs.add(e);
            if (w.equals(got)) found++;
            System.out.println(w + ": spotted=" + got + " speech=" + (part == null ? 0 : part.length) + " emb=" + (e == null ? 0 : e.length));
        }
        float[] thr = new float[1];
        float[] prof = VoiceCommands.buildProfile(embs, thr);
        System.out.println("profile dim " + prof.length + " threshold " + thr[0] + " spotted " + found + "/" + VoiceCommands.WORDS.length);
        GeneratedAudio other = tts.generate("Pause.", 3, 1.0f);
        float[] ox = resample(other.getSamples(), other.getSampleRate());
        System.out.println("other voice match: " + VoiceCommands.dot(prof, vc.embed(VoiceCommands.speechPart(pad(ox)))));
        // quieter, farther away: the same words at a fifth of the level, with some room noise
        int quiet = 0;
        java.util.Random rnd = new java.util.Random(7);
        for (String w : VoiceCommands.WORDS) {
            GeneratedAudio g = tts.generate(Character.toUpperCase(w.charAt(0)) + w.substring(1) + ".", 2, 1.1f);
            float[] x = resample(g.getSamples(), g.getSampleRate());
            for (int i = 0; i < x.length; i++) x[i] = x[i] * 0.2f + (float) (rnd.nextGaussian() * 0.004);
            if (w.equals(vc.spot(pad(x)))) quiet++;
        }
        System.out.println("quiet + noisy words spotted: " + quiet + "/" + VoiceCommands.WORDS.length);
        // a story read aloud: how often a command word is heard in it (the voice check then decides)
        String story = "He went back to the beginning of the road and waited. At the end of the day, they would play the old songs, and she would pause before the last verse. Forward, he thought. The children played by the river.";
        GeneratedAudio st = tts.generate(story, 3, 1.0f);
        float[] sx = resample(st.getSamples(), st.getSampleRate());
        int hits = 0;
        for (int at = 0; at < sx.length; at += 16000 * 2) {
            float[] piece = java.util.Arrays.copyOfRange(sx, at, Math.min(sx.length, at + 16000 * 2));
            if (vc.spot(piece) != null) hits++;
        }
        System.out.println("story pieces with a command word heard: " + hits + " of " + ((sx.length + 31999) / 32000));
        vc.learnReader(16, tts.generate("He walked back to the end of the road and waited for the others.", 16, 1f).getSamples(), 24000);
        System.out.println("ALL OK");
    }
    static float[] pad(float[] x) { float[] r = new float[x.length + 8000]; System.arraycopy(x, 0, r, 4000, x.length); return r; }
    static float[] resample(float[] s, int rate) {
        int m = (int) ((long) s.length * 16000 / rate); float[] r = new float[m];
        for (int i = 0; i < m; i++) { double x = (double) i * rate / 16000; int j = (int) x; double f = x - j;
            r[i] = (float) (s[Math.min(j, s.length - 1)] * (1 - f) + s[Math.min(j + 1, s.length - 1)] * f); }
        return r;
    }
}
