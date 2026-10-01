package com.loudbook.app;

import com.k2fsa.sherpa.onnx.*;

/** Makes a "chapter" recording with Kokoro (sentences with uneven pauses, like a narrator), then
 *  times the sentences two ways: by pauses alone (Align) and with the recogniser (AsrAligner). */
public class AsrTest {
    static final String[] TEXT = {
        "The rain had stopped by the time Mara reached the bridge.",
        "She waited.",
        "Lamps were coming on along the river, one after another, and somewhere below a dog was barking at nothing in particular.",
        "\"You're late,\" said Tobin, stepping out of the shadow of the toll house.",
        "\"I know.\"",
        "\"Twenty minutes late.\"",
        "She shrugged and handed him the letter, still sealed, its wax gone soft from the heat of her coat.",
        "He turned it over twice before he spoke again.",
        "\"Who else has seen this?\"",
        "Nobody, she told him, and that was almost true.",
        "The clerk at the inn had looked at the seal for a long moment, but clerks looked at everything.",
        "Tobin broke the wax with his thumb.",
        "Inside was a single page, written in a careful, cramped hand, and a map of the old quarter with three streets marked in red ink.",
        "\"Well,\" he said quietly.",
        "\"That changes things.\"",
        "Mara glanced back the way she had come.",
        "The street was empty, but the feeling of being followed had not left her since the market, and she had learned long ago to trust it.",
        "\"We should not stay here,\" she said.",
        "They walked north, keeping to the narrow lanes where the cobbles were uneven and the lamps were few.",
        "By the time the bells rang eleven, they had reached the door with the green shutters.",
    };

    public static void main(String[] a) throws Exception {
        java.io.File files = new java.io.File(a[0]);
        String k = a[1];
        OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
            .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(k + "/model.onnx").setVoices(k + "/voices.bin")
                .setTokens(k + "/tokens.txt").setLexicon(k + "/lexicon-us-en.txt").setDataDir(k + "/espeak-ng-data").build())
            .setNumThreads(2).setDebug(false).build()).build());
        java.util.Random rnd = new java.util.Random(7);
        int R = 3, N = TEXT.length * R;                 // a longer chapter: the passage three times over
        String[] all = new String[N];
        for (int i = 0; i < N; i++) all[i] = TEXT[i % TEXT.length];
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        double[] truth = new double[N];
        int[] chars = new int[N];
        int rate = 24000; long n = 0;
        for (int i = 0; i < N; i++) {
            chars[i] = all[i].length();
            float[] x = Voice.trim(tts.generate(all[i], 3, 1.0f).getSamples(), rate);
            truth[i] = n / (double) rate;
            for (float f : x) { short s = (short) (f * 32767); pcm.write(s & 0xff); pcm.write((s >> 8) & 0xff); n++; }
            int gap = (int) (rate * (all[i].endsWith("\"") ? 0.15 + rnd.nextDouble() * 0.25 : 0.3 + rnd.nextDouble() * 0.5));
            for (int g = 0; g < gap; g++) { pcm.write(0); pcm.write(0); n++; }
        }
        byte[] bytes = pcm.toByteArray();
        // as if Google stopped the recording early: cut it off in the middle of sentence 44
        int cutSentence = 44;
        long cutAt = (long) ((truth[cutSentence] + 1.3) * rate) * 2;
        java.io.File f = new java.io.File(files, "chapter.pcm");
        java.nio.file.Files.write(f.toPath(), java.util.Arrays.copyOf(bytes, (int) cutAt));
        CloudVoice.Pcm p = new CloudVoice.Pcm(f, 0, cutAt, rate);
        Align.Result r = Align.align(p, chars, 0);
        System.out.println("pause timing: found " + r.count + " of " + N + " (really " + cutSentence + " whole ones), cut short " + r.cutShort);
        AsrAligner asr = new AsrAligner();
        long t0 = System.currentTimeMillis();
        asr.load(new java.io.File(files, "asr"));
        java.util.List<AsrAligner.Word> words = asr.words(p, 0, p.samples());
        System.out.println("recogniser: " + words.size() + " words in " + (System.currentTimeMillis() - t0) + " ms for " + (int) (p.samples() / rate) + " s; first: " + words.subList(0, Math.min(10, words.size())));
        double[] st = AsrAligner.sentenceStarts(words, java.util.Arrays.asList(all).subList(0, r.count));
        double errPause = 0, errAsr = 0; int nAsr = 0;
        for (int i = 0; i < r.count; i++) {
            double pz = r.start[i] / (double) rate;
            System.out.printf("%2d truth %6.2f  pauses %6.2f  words %6.2f  %s%n", i, truth[i], pz, st[i], all[i].length() > 40 ? all[i].substring(0, 40) : all[i]);
            errPause = Math.max(errPause, Math.abs(pz - truth[i]));
            if (!Double.isNaN(st[i])) { errAsr = Math.max(errAsr, Math.abs(st[i] - truth[i])); nAsr++; }
        }
        System.out.printf("worst error: pauses %.2f s, words %.2f s (%d of %d sentences timed by words)%n", errPause, errAsr, nAsr, r.count);
        System.out.println("ALL OK");
    }
}
