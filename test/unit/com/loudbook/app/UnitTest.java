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
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
