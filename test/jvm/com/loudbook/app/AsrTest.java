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
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        double[] truth = new double[TEXT.length];
        int[] chars = new int[TEXT.length];
        int rate = 24000; long n = 0;
        for (int round = 0; round < 3; round++) {                      // a longer chapter: the passage three times
            for (int i = 0; i < TEXT.length; i++) {
                if (round == 0) chars[i] = TEXT[i].length();
                float[] x = Voice.trim(tts.generate(TEXT[i], 3, 1.0f).getSamples(), rate);
                if (round == 0) truth[i] = n / (double) rate;
                for (float f : x) { short s = (short) (f * 32767); pcm.write(s & 0xff); pcm.write((s >> 8) & 0xff); n++; }
                int gap = (int) (rate * (TEXT[i].endsWith("\"") ? 0.15 + rnd.nextDouble() * 0.25 : 0.3 + rnd.nextDouble() * 0.5));
                for (int g = 0; g < gap; g++) { pcm.write(0); pcm.write(0); n++; }
            }
        }
        java.io.File f = new java.io.File(files, "chapter.pcm");
        java.nio.file.Files.write(f.toPath(), pcm.toByteArray());
        CloudVoice.Pcm p = new CloudVoice.Pcm(f, 0, f.length(), rate);
        int N = TEXT.length;
        // only the first round's sentences are asked for: the rest looks like a cut-off recording would
        Align.Result r = Align.align(p, chars, 0);
        System.out.println("pause timing: found " + r.count + " of " + N + ", cut short " + r.cutShort);
        AsrAligner asr = new AsrAligner();
        long t0 = System.currentTimeMillis();
        asr.load(new java.io.File(files, "asr"));
        java.util.List<AsrAligner.Word> words = asr.words(p, 0, (long) (truth[N - 1] * rate + rate * 8));
        System.out.println("recogniser: " + words.size() + " words in " + (System.currentTimeMillis() - t0) + " ms; first: " + words.subList(0, Math.min(12, words.size())));
        double[] st = AsrAligner.sentenceStarts(words, java.util.Arrays.asList(TEXT));
        double errPause = 0, errAsr = 0; int nAsr = 0;
        for (int i = 0; i < N; i++) {
            double pz = i < r.count ? r.start[i] / (double) rate : Double.NaN;
            System.out.printf("%2d truth %6.2f  pauses %6.2f  words %6.2f  %s%n", i, truth[i], pz, st[i], TEXT[i].length() > 40 ? TEXT[i].substring(0, 40) : TEXT[i]);
            if (!Double.isNaN(pz)) errPause = Math.max(errPause, Math.abs(pz - truth[i]));
            if (!Double.isNaN(st[i])) { errAsr = Math.max(errAsr, Math.abs(st[i] - truth[i])); nAsr++; }
        }
        System.out.printf("worst error: pauses %.2f s, words %.2f s (%d of %d sentences timed by words)%n", errPause, errAsr, nAsr, N);
        System.out.println("ALL OK");
    }
}
