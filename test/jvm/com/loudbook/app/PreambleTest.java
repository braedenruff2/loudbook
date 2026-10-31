package com.loudbook.app;

import com.k2fsa.sherpa.onnx.*;

/**
 * Gemini sometimes reads its instructions aloud ("...natural and engaging, following the mood...")
 * before the chapter, which used to put every sentence one off. Makes recordings with Kokoro: the
 * passage alone, with the whole instructions said first, and with just a few words of them first.
 * Each must time sentence 1 where it really starts, both by pauses alone (Align) and by the words
 * heard (AsrAligner), and the clean one must not be fooled into passing over anything.
 */
public class PreambleTest {
    static int bad = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) bad++; }

    public static void main(String[] a) throws Exception {
        java.io.File files = new java.io.File(a[0]);
        String k = a[1];
        OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
            .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(k + "/model.onnx").setVoices(k + "/voices.bin")
                .setTokens(k + "/tokens.txt").setLexicon(k + "/lexicon-us-en.txt").setDataDir(k + "/espeak-ng-data").build())
            .setNumThreads(2).setDebug(false).build()).build());
        AsrAligner asr = new AsrAligner();
        asr.load(new java.io.File(files, "asr")); AsrTest.warm(asr);
        String style = CloudVoice.DEFAULT_STYLE;
        String[][] leads = {{"none", null}, {"whole instructions", style}, {"a few words", "Natural and engaging."}};
        String[] text = AsrTest.TEXT;
        int[] chars = new int[text.length];
        for (int i = 0; i < text.length; i++) chars[i] = text[i].length();
        int rate = 24000;
        java.util.Random rnd = new java.util.Random(3);
        for (String[] lead : leads) {
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            long n = 0;
            if (lead[1] != null) {
                float[] x = Voice.trim(tts.generate(lead[1], 3, 1.0f).getSamples(), rate);
                for (float f : x) { short s = (short) (f * 32767); pcm.write(s & 0xff); pcm.write((s >> 8) & 0xff); n++; }
                for (int g = 0; g < rate * 0.7; g++) { pcm.write(0); pcm.write(0); n++; }
            }
            double[] truth = new double[text.length];
            for (int i = 0; i < text.length; i++) {
                float[] x = Voice.trim(tts.generate(text[i], 3, 1.0f).getSamples(), rate);
                truth[i] = n / (double) rate;
                for (float f : x) { short s = (short) (f * 32767); pcm.write(s & 0xff); pcm.write((s >> 8) & 0xff); n++; }
                int gap = (int) (rate * (text[i].endsWith("\"") ? 0.15 + rnd.nextDouble() * 0.25 : 0.3 + rnd.nextDouble() * 0.5));
                for (int g = 0; g < gap; g++) { pcm.write(0); pcm.write(0); n++; }
            }
            java.io.File f = new java.io.File(files, "pre.pcm");
            java.nio.file.Files.write(f.toPath(), pcm.toByteArray());
            CloudVoice.Pcm p = new CloudVoice.Pcm(f, 0, n * 2, rate);
            System.out.println("== " + lead[0] + " (sentence 1 really starts at " + String.format("%.2f", truth[0]) + " s)");

            Align.Result before = Align.align(p, chars, 0);           // as it was: no instructions allowed for
            System.out.printf("   old timing: sentence 1 at %.2f s, sentence 2 at %.2f s%n", before.start[0] / (double) rate, before.count > 1 ? before.start[1] / (double) rate : -1);
            Align.Result r = Align.align(p, chars, 0, CloudVoice.preambleChars(style));
            double worst = 0;
            for (int i = 0; i < r.count; i++) worst = Math.max(worst, Math.abs(r.start[i] / (double) rate - truth[i]));
            System.out.printf("   pauses: sentence 1 at %.2f s, %d of %d found, worst error %.2f s%n", r.start[0] / (double) rate, r.count, text.length, worst);
            check(lead[0] + ": pause timing starts sentence 1 where it is", Math.abs(r.start[0] / (double) rate - truth[0]) < 0.5);
            check(lead[0] + ": pause timing finds every sentence, none one off", r.count == text.length && worst < 0.9);

            java.util.List<AsrAligner.Word> words = asr.words(p, 0, p.samples());
            double[] st = AsrAligner.sentenceStarts(words, java.util.Arrays.asList(text));
            long[] old = new long[text.length];
            for (int i = 0; i < text.length; i++) old[i] = i < before.count ? before.start[i] : 0;   // starting from the old (one off) timing
            long[] cut = AsrAligner.cuts(st, java.util.Arrays.asList(text), Align.pauses(p), rate, old);
            StringBuilder sts = new StringBuilder();
            for (int i = 0; i < text.length; i++) sts.append(String.format(" %d:%.2f/%.2f/%.2f", i, st[i], cut[i] / (double) rate, truth[i]));
            System.out.println("   heard/cut/truth:" + sts);
            double worstW = 0; int timed = 0;
            for (int i = 0; i < text.length; i++) if (cut[i] >= 0) { timed++; worstW = Math.max(worstW, Math.abs(cut[i] / (double) rate - truth[i])); }
            System.out.printf("   words: sentence 1 at %.2f s, %d of %d timed, worst error %.2f s; first heard: %s%n",
                cut[0] / (double) rate, timed, text.length, worstW, words.subList(0, Math.min(8, words.size())));
            // (with nothing said first, the first sentence may simply stay where it is)
            check(lead[0] + ": word timing starts sentence 1 where it is", lead[1] == null ? cut[0] < 0 || Math.abs(cut[0] / (double) rate - truth[0]) < 0.5
                : cut[0] >= 0 && Math.abs(cut[0] / (double) rate - truth[0]) < 0.5);
            check(lead[0] + ": word timing re-cuts the rest correctly", timed >= text.length - 1 && worstW < 0.6);
        }
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
