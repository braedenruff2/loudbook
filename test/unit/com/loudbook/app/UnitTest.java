package com.loudbook.app;

import java.util.Arrays;
import java.util.List;

/** Plain-Java checks of the parts that don't need a phone. Run before every release (build.yml). */
public class UnitTest {
    static int bad = 0;
    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "\n     want " + want + "\n     got  " + got));
    }

    static String str(java.util.List<String[]> parts) { StringBuilder b = new StringBuilder(); for (String[] p : parts) b.append('[').append(p[1]).append(':').append(p[0]).append(']'); return b.toString(); }

    public static void main(String[] a) {
        // Kindle sentences: titles and initials don't end a sentence
        check("Mr. stays in its sentence", KindleText.sentences("Then Mr. Hale arrived at the gate. It was very late by then, and cold."),
            "[Then Mr. Hale arrived at the gate., It was very late by then, and cold.]");
        check("an initial stays too", KindleText.sentences("She met J. Smith at the old mill by the river. He waved at her from the bridge."),
            "[She met J. Smith at the old mill by the river., He waved at her from the bridge.]");
        // a sentence cut by the page break is held back, then read whole
        KindleText kt = new KindleText("");
        List<Chapter.Chunk> p1 = kt.chunks(Arrays.asList("The first page ends here. And this one runs over"), false);
        List<Chapter.Chunk> p2 = kt.chunks(Arrays.asList("the page break, then stops."), true);
        check("page 1", p1.size() + ":" + p1.get(0).text, "1:The first page ends here.");
        check("page 2 joins the cut sentence", p2.get(0).text, "And this one runs over the page break, then stops.");
        // a recorded voice: too short is refused, long enough is a WAV
        byte[] quiet = new byte[24000 * 2 * 3];
        check("silence refused", VoiceRecording.toWav(quiet, 24000) == null, true);
        byte[] talk = new byte[24000 * 2 * 10];
        for (int i = 0; i < talk.length / 2; i++) { short v = (short) (8000 * Math.sin(i / 7.0) * (i % 9000 < 6000 ? 1 : 0.02)); talk[2 * i] = (byte) v; talk[2 * i + 1] = (byte) (v >> 8); }
        byte[] wav = VoiceRecording.toWav(talk, 24000);
        check("10 s of speech becomes a WAV", wav != null && new String(wav, 0, 4).equals("RIFF") && new String(wav, 8, 4).equals("WAVE"), true);
        // dialogue in a second voice: the quoted parts, also when a quote runs over sentences
        check("quote and tag", str(Dialogue.split("\u201cWe go,\u201d said Ada. \u201cNow.\u201d", false)), "[q:We go,][n:said Ada.][q:Now.]");
        check("straight quotes", str(Dialogue.split("He said \"stop it\" and left.", false)), "[n:He said][q:stop it][n:and left.]");
        check("inside a quote from before", str(Dialogue.split("And then we ran.\u201d She laughed.", true)), "[q:And then we ran.][n:She laughed.]");
        check("no quotes", str(Dialogue.split("The road was long.", false)), "[n:The road was long.]");
        // Kindle page geometry: where sentences and words are, and what a tap points at
        java.util.List<String> ls = Arrays.asList("The road out of the valley was", "older than the town. Mara walked", "it every morning, and liked it.");
        java.util.List<float[]> bx = Arrays.asList(new float[]{40, 100, 400, 140}, new float[]{0, 140, 400, 180}, new float[]{0, 180, 380, 220});
        KindleLayout kl = new KindleLayout(ls, bx, null);
        java.util.List<int[]> sp = kl.spans(Arrays.asList("The road out of the valley was older than the town.", "Mara walked it every morning, and liked it."));
        check("sentence spans", Arrays.toString(sp.get(0)) + Arrays.toString(sp.get(1)), "[0, 40][40, 74]");
        check("second sentence covers two lines", kl.boxesFor(sp.get(1)[0], sp.get(1)[1]).size(), 2);
        check("its first word starts mid-line", Math.round(kl.wordAt(sp.get(1)[0], sp.get(1)[1], 0f)[0]), 263);
        check("a tap on 'Mara' starts that sentence", kl.letterAt(300, 160), 40);
        check("a sentence begun on the page before is found by its end",
            Arrays.toString(kl.spans(Arrays.asList("Far off, the road out of the valley was older than the town.")).get(0)), "[0, 40]");
        // a line that wraps onto two rows on screen (known from the character boxes): a box per row
        float[][] cb = new float[11][];
        for (int i = 0; i < 11; i++) cb[i] = i < 5 ? new float[]{10 + i * 10, 100, 20 + i * 10, 120} : i == 5 ? new float[]{0, 0, 0, 0} : new float[]{10 + (i - 6) * 10, 120, 20 + (i - 6) * 10, 140};
        KindleLayout wrap = new KindleLayout(Arrays.asList("hello world"), Arrays.asList(new float[]{10, 100, 70, 140}), Arrays.asList(new float[][][]{cb}));
        check("wrapped line: one box per row", wrap.boxesFor(0, 10).size(), 2);
        // words found on a picture of the screen: rows of ink, words separated by gaps
        String inkText = "The road out of the valley was older than the town, and in places the stones had sunk deep.";
        int W = 600, H = 400;
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, 0xFF101010);                          // a dark page (light text)
        int x = 20, y = 20; java.util.Map<String, int[]> where = new java.util.HashMap<>();
        for (String word : inkText.split(" ")) {
            int w = word.length() * 18;
            if (x + w > W - 20) { x = 20; y += 60; }
            for (int yy = y; yy < y + 36; yy++) for (int xx = x; xx < x + w; xx++) if ((xx / 3) % 2 == 0 || yy % 7 == 0) px[yy * W + xx] = 0xFFE0E0E0;
            where.put(word, new int[]{x, y});
            x += w + 22;
        }
        java.util.List<InkLayout.Word> found = InkLayout.words(px, W, H, 0, 0);
        check("ink: every word found", found.size(), inkText.split(" ").length);
        float[][] icb = InkLayout.charBoxes(inkText, found);
        int vi = inkText.indexOf("valley");
        check("ink: 'valley' boxed where it is", icb != null && Math.abs(icb[vi][0] - where.get("valley")[0]) < 6 && Math.abs(icb[vi][1] - where.get("valley")[1]) < 4, true);
        // the whole screen: a clock above, "mins left" below, the text in two pieces, and the last
        // words of the page not on screen (they're on the next)
        int[] px2 = new int[W * (H + 120)];
        java.util.Arrays.fill(px2, 0xFFF8F0E0);                         // a light page (dark text)
        for (int yy = 0; yy < H; yy++) for (int xx = 0; xx < W; xx++) if (px[yy * W + xx] != 0xFF101010) px2[(yy + 60) * W + xx] = 0xFF202020;
        for (int yy = 10; yy < 30; yy++) for (int xx = 20; xx < 80; xx++) px2[yy * W + xx] = 0xFF202020;          // clock
        for (int yy = H + 80; yy < H + 100; yy++) for (int xx = 20; xx < 200; xx += 1) if (xx % 50 < 40) px2[yy * W + xx] = 0xFF202020;   // footer
        java.util.List<float[][]> pg = InkLayout.pageBoxes(Arrays.asList("The road out of the valley was older than the town,",
            "and in places the stones had sunk deep. Beyond them lay the river"), InkLayout.words(px2, W, H + 120, 0, 0));
        check("page: both pieces placed", pg != null && pg.get(0) != null && pg.get(1) != null, true);
        String pieceTwo = "and in places the stones had sunk deep. Beyond them lay the river";
        int si = pieceTwo.indexOf("stones");
        check("page: 'stones' boxed where it is", pg != null && pg.get(1) != null && pg.get(1)[si] != null
            && Math.abs(pg.get(1)[si][0] - where.get("stones")[0]) < 6 && Math.abs(pg.get(1)[si][1] - (where.get("stones")[1] + 60)) < 4, true);
        check("page: words not on screen have no box", pg != null && pg.get(1) != null && pg.get(1)[pieceTwo.indexOf("river")] == null, true);
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
