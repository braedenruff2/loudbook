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

    /** Where a look-behind in a regex has an unlimited repeat (+, *, {n,}), which Android's regex refuses; null if none. */
    static String unboundedLookBehind(String re) {
        java.util.ArrayDeque<Boolean> groups = new java.util.ArrayDeque<>();     // per open group: is it (inside) a look-behind
        boolean inClass = false;
        for (int i = 0; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '\\') { if (i + 1 < re.length() && re.charAt(i + 1) == 'Q') { int e = re.indexOf("\\E", i); i = e < 0 ? re.length() : e + 1; } else i++; continue; }
            if (inClass) { if (c == ']') inClass = false; continue; }
            if (c == '[') { inClass = true; if (i + 1 < re.length() && re.charAt(i + 1) == '^') i++; if (i + 1 < re.length() && re.charAt(i + 1) == ']') i++; continue; }
            boolean inside = !groups.isEmpty() && groups.peek();
            if (c == '(') {
                boolean lb = re.startsWith("(?<=", i) || re.startsWith("(?<!", i);
                groups.push(inside || lb);
                continue;
            }
            if (c == ')') { if (!groups.isEmpty()) groups.pop(); continue; }
            if (!inside) continue;
            if (c == '+' || c == '*') {
                char p = i > 0 ? re.charAt(i - 1) : ' ';
                if (c == '+' && (p == '+' || p == '*' || p == '?' || p == '}')) continue;   // (a possessive mark after a repeat, judged there)
                return c + " at " + i;
            }
            if (c == '{') { int e = re.indexOf('}', i); if (e > 0 && re.substring(i, e).endsWith(",")) return "{n,} at " + i; }
        }
        return null;
    }

    public static void main(String[] a) {
        // a "next" link back to the same page isn't a next chapter
        check("the letter A for the phone voice", Chapter.forKokoro("Plan A failed. He got an A on the test, an A-plus, Grade A. A man came. Type A people. A. Smith, a plan A."),
            "Plan Eigh failed. He got an Eigh on the test, an A-plus, Grade Eigh. A man came. Type Eigh people. A. Smith, a plan Eigh.");
        check("mm-hmm for the phone voice", Chapter.forKokoro("Mm-hmm. She said mm-hmm and left."), "Um-hmm. She said um-hmm and left.");
        check("verbs for the phone voice: close", Chapter.forKokoro("Close the door. \"Close your eyes,\" she said, and close the gate."), "Cloze the door. \"Cloze your eyes,\" she said, and cloze the gate.");
        check("verbs for the phone voice: close as \"near\" stays", Chapter.forKokoro("She stood close the whole time. He was so close the heat burned. Up close the beast was worse. It's close to midnight. The door was closed."), "She stood close the whole time. He was so close the heat burned. Up close the beast was worse. It's close to midnight. The door was closed.");
        check("verbs for the phone voice: \"came to a close\" stays", Chapter.forKokoro("It came to a close the next day. The day drew to a close his labours done."), "It came to a close the next day. The day drew to a close his labours done.");
        check("verbs for the phone voice: excuse", Chapter.forKokoro("Excuse me, sir. Please excuse my manners. That's a poor excuse for a knight. An excuse the size of a mountain."), "Excuze me, sir. Please excuze my manners. That's a poor excuse for a knight. An excuse the size of a mountain.");
        check("verbs for the phone voice: at a sentence start", Chapter.forKokoro("Use your head. He fell. \"Use the rope!\" Abuse the system. Separate them. Record the meeting. Present the gift. Polish the silver."), "Uze your head. He fell. \"Uze the rope!\" Abuze the system. Sepper-ate them. Re-cord the meeting. Pree-zent the gift. Pollish the silver.");
        check("verbs for the phone voice: more at a sentence start", Chapter.forKokoro("Escort the prisoner. \"Tear it down!\" Increase the power. Protest the ruling. Suspect everyone."), "Ess-cort the prisoner. \"Tare it down!\" In-crease the power. Pruh-test the ruling. Suh-spect everyone.");
        check("verbs for the phone voice: \"Live the King!\" stays", Chapter.forKokoro("Live the King! Subject that hath broken the Law."), "Live the King! Subject that hath broken the Law.");
        check("verbs for the phone voice: nouns and adjectives stay", Chapter.forKokoro("The use of force. A separate room. A new record. Present company excepted. Polish soldiers marched. They can use it."), "The use of force. A separate room. A new record. Present company excepted. Polish soldiers marched. They can use it.");
        check("verbs for the phone voice: wind, wound", Chapter.forKokoro("He had to wind the clock. The wind the night before was fierce. He wound the rope around his arm. The wound the healer closed was deep. A path wound through the hills."), "He hadd to wined the clock. The wind the night before was fierce. He wownd the rope around his arm. The wound the healer closed was deep. A path wownd through the hills.");
        check("verbs for the phone voice: read (past)", Chapter.forKokoro("She read the note twice. He slowly read it. The sign read \"Stop\". Did he read it? Let her read. She will read it. They read every night. He's well read."), "She red the note twice. He slowly red it. The sign red \"Stop\". Did he read it? Let her read. She will read it. They read every night. He's well read.");
        check("verbs for the phone voice: dove", Chapter.forKokoro("She dove into the lake. Harry dove for the ball. The dove in the cage. A white dove flew."), "She dohv into the lake. Harry dohv for the ball. The dove in the cage. A white dove flew.");
        check("moped and sow for the phone voice", Chapter.forKokoro("He moped around. She just moped. The moped stalled. He rode his moped. The sow grunted; an old sow and the sows. The sow ate sausage. To sow the seeds, they sow discord."), "He mohpt around. She just mohpt. The moped stalled. He rode his moped. The sau grunted; an old sau and the sauz. The sau ate sausage. To sow the seeds, they sow discord.");
        check("had to for the phone voice", Chapter.forKokoro("He had to use it. Had to live with it. She had  to record the meeting. He had to. They had To Kill a Mockingbird. She has to read the letter. He has read it."), "He hadd to use it. Hadd to live with it. She hadd  to record the meeting. He had to. They had To Kill a Mockingbird. She has to reed the letter. He has read it.");
        check("excuse himself for the phone voice", Chapter.forKokoro("He had to excuse himself. She excused herself. An excuse himself would make."), "He hadd to excuze himself. She excused herself. An excuse himself would make.");
        check("verbs after an adverb for the phone voice", Chapter.forKokoro("They always use it. She carefully recorded it and then record the rest. He quickly polish the boots. You really suspect me? The only use the army had. A lovely present the king gave. They were also present the whole time. Her early record the clerk kept."), "They always uze it. She carefully recorded it and then re-cord the rest. He quickly pollish the boots. You really suh-spect me? The only use the army had. A lovely present the king gave. They were also present the whole time. Her early record the clerk kept.");
        check("verbs after let's / you'd / did he for the phone voice", Chapter.forKokoro("Let's use the map. You'd record it. Let me present the gift. Did he object the first time? Why not use it? Go record them. Now, use the key. The last record the clerk kept. So present the whole time."), "Let's uze the map. You'd re-cord it. Let me pree-zent the gift. Did he ub-ject the first time? Why not uze it? Go re-cord them. Now, uze the key. The last record the clerk kept. So present the whole time.");
        check("desert the verb for the phone voice", Chapter.forKokoro("Don't desert me now. They had to desert the army. Never desert your friends. They crossed the desert. The desert sun. He deserted them."), "Don't dessert me now. They hadd to dessert the army. Never dessert your friends. They crossed the desert. The desert sun. He deserted them.");
        check("minute (tiny) for the phone voice", Chapter.forKokoro("The minute details. A minute amount of dust. Minute traces of blood. Every minute detail. Wait a minute. Last minute details. A five minute examination. A 10-minute inspection. The minute hand."), "The my-nute details. A my-nute amount of dust. My-nute traces of blood. Every my-nute detail. Wait a minute. Last minute details. A five minute examination. A 10-minute inspection. The minute hand.");
        check("does the deer for the phone voice", Chapter.forKokoro("The does ran. Two does and a buck. The bucks and does. Two does not make four. The does and don'ts. What does it mean? She does."), "The doze ran. Two doze and a buck. The bucks and doze. Two does not make four. The does and don'ts. What does it mean? She does.");
        check("lead the metal for the phone voice", Chapter.forKokoro("A lead pipe. Made of lead. Legs like lead, and molten lead. A lead-lined box. He took the lead. Lead the way. Cast as lead in the play. The lead singer."), "A led pipe. Made of led. Legs like led, and molten led. A led-lined box. He took the lead. Lead the way. Cast as lead in the play. The lead singer.");
        check("verbs for the phone voice: bow", Chapter.forKokoro("He took a bow. She gave a deep bow to the king. Bow your head. They bow to the emperor. He bows his head. She drew her bow and nocked an arrow. He raised his bow to his shoulder. A small bow and a quiver."), "He took a bough. She gave a deep bough to the king. Bough your head. They bough to the emperor. He boughs his head. She drew her bow and nocked an arrow. He raised his bow to his shoulder. A small bow and a quiver.");
        check("next link to itself", Chapter.samePage("https://x.com/s/ch4/#comments", "https://x.com/s/ch4"), true);
        check("next link to the next page", Chapter.samePage("https://x.com/s/ch5", "https://x.com/s/ch4"), false);
        check("no next link", Chapter.samePage(null, "https://x.com/s/ch4"), false);
        // Kindle sentences: titles and initials don't end a sentence
        check("Mr. stays in its sentence", KindleText.sentences("Then Mr. Hale arrived at the gate. It was very late by then, and cold."),
            "[Then Mr. Hale arrived at the gate., It was very late by then, and cold.]");
        check("an initial stays too", KindleText.sentences("She met J. Smith at the old mill by the river. He waved at her from the bridge."),
            "[She met J. Smith at the old mill by the river., He waved at her from the bridge.]");
        check("Kindle: \"I.\" ends a sentence", KindleText.sentences("Nobody in the whole village believed him, and neither did I. Then the ship came in sight."),
            "[Nobody in the whole village believed him, and neither did I., Then the ship came in sight.]");
        check("Kindle: \"No.\" ends a sentence, \"No. 5\" doesn't", KindleText.sentences("She asked if he would stay and he said No. Then he walked out of the room. Room No. 5 was empty and dark."),
            "[She asked if he would stay and he said No., Then he walked out of the room., Room No. 5 was empty and dark.]");
        check("Kindle: the page's last sentence after \"I.\"", KindleText.lastSentence("Nobody had known. Neither did I. Then he"), "Then he");
        check("Kindle: scrolled page, the overlap ends in a dash and quote", KindleText.afterOverlap(Arrays.asList("The first paragraph is long enough to match.", "Then he stopped and said\u2014\u201d"),
            Arrays.asList("Then he stopped and said\u2014\u201d", "A new paragraph.")).toString(), "[A new paragraph.]");
        check("Kindle: \"U.S.\" doesn't end a sentence", KindleText.sentences("They flew to the U.S. Army base at dawn. It was very cold there that morning."),
            "[They flew to the U.S. Army base at dawn., It was very cold there that morning.]");
        check("Kindle: an initial after a quote mark", KindleText.lastSentence("He spoke. \u201cJ. Smith sent me,\u201d she"), "\u201cJ. Smith sent me,\u201d she");
        check("Kindle: the next page's first sentence, \"Vol. 2\"", KindleText.firstSentence("Vol. 2 was missing from the shelf. The rest were there."), "Vol. 2 was missing from the shelf.");
        // a sentence cut by the page break is held back, then read whole
        KindleText kt = new KindleText("");
        List<Chapter.Chunk> p1 = kt.chunks(Arrays.asList("The first page ends here. And this one runs over"), false);
        List<Chapter.Chunk> p2 = kt.chunks(Arrays.asList("the page break, then stops."), true);
        check("page 1", p1.size() + ":" + p1.get(0).text, "1:The first page ends here.");
        check("page 2 joins the cut sentence", p2.get(0).text, "And this one runs over the page break, then stops.");
        // a page's first long sentence starts with a short piece, so the voice starts sooner after a turn
        check("quick start at a comma", KindleText.quickStart("She liked the quiet, and she liked that nobody else since the war had walked that road at dawn."),
            "[She liked the quiet,, and she liked that nobody else since the war had walked that road at dawn.]");
        check("quick start before and", KindleText.quickStart("The road out of the valley was older than the town and in places the stones had sunk into the grass."),
            "[The road out of the valley was older than the town, and in places the stones had sunk into the grass.]");
        check("a long rest is cut again at a comma", KindleText.quickStart("She liked the quiet, and she liked that nobody else bothered to come this way anymore, not since the new highway had opened on the far side of the hill."),
            "[She liked the quiet,, and she liked that nobody else bothered to come this way anymore,, not since the new highway had opened on the far side of the hill.]");
        check("short sentence stays whole", KindleText.quickStart("Mara walked it every morning."), "[Mara walked it every morning.]");
        check("no good place stays whole", KindleText.quickStart("Abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghij."),
            "[Abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcdefghij.]");
        KindleText kq = new KindleText("");
        List<Chapter.Chunk> pq = kq.chunks(Arrays.asList("It was late in the year, and the first snow had already come to the high passes. Nobody went up."), true);
        check("only the page's first sentence is cut", pq.size() + ":" + pq.get(0).text + "|" + pq.get(1).block, "2:It was late in the year,|0");
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
        // the highlight follows the voice's own pauses: silence at either end doesn't count, and a pause at a comma or
        // full stop is matched to it in the text (a straight line through the clip would run ahead at each pause)
        {
            int rate = 24000;
            String[] wordsT = {"One", "two", "three,", "four", "five", "six.", "Seven", "eight."};
            int[] gapMs = {30, 30, 200, 30, 30, 400, 30, 0};
            java.util.List<Float> snd = new java.util.ArrayList<>();
            java.util.function.IntConsumer hush = ms -> { for (int k = 0; k < ms * rate / 1000; k++) snd.add(0f); };
            hush.accept(300);
            long[] wordStart = new long[wordsT.length];
            for (int w = 0; w < wordsT.length; w++) {
                wordStart[w] = snd.size();
                int letters = wordsT[w].replaceAll("[^A-Za-z]", "").length();
                for (int k = 0; k < letters * 60 * rate / 1000; k++) snd.add((float) (0.3 * Math.sin(k * 0.12)));
                hush.accept(gapMs[w]);
            }
            hush.accept(500);
            float[] sm = new float[snd.size()];
            for (int k = 0; k < sm.length; k++) sm[k] = snd.get(k);
            SpeechTiming st = SpeechTiming.of("One two three, four five six. Seven eight.", sm, rate);
            check("timing: pauses matched to the comma and the full stop", st.matched, 2);
            check("timing: at the start of the speech, nothing said yet", Math.round(st.fracAt(wordStart[0]) * 100), 0);
            check("timing: at 'four' (after the comma's pause), 11 of 32 letters said", Math.round(st.fracAt(wordStart[3] + 10) * 32), 11);
            check("timing: at 'Seven' (after the full stop's pause), 22 of 32", Math.round(st.fracAt(wordStart[6] + 10) * 32), 22);
            check("timing: in the trailing silence, all said", st.fracAt(sm.length - 10), 1.0f);
            check("timing: the reverse, where 'four' starts", Math.abs(st.frameAt(11 / 32f) - wordStart[3]) < rate / 20, true);
        }
        {   // words said longer than they're written ("Mr." -> "Mister", "12" -> "twelve") take their share of the time
            int rate = 24000;
            String[] said = {"Mister", "Smith", "had", "twelve", "apples,", "then", "left."};
            int[] gapMs = {30, 30, 30, 30, 200, 30, 0};
            java.util.List<Float> snd = new java.util.ArrayList<>();
            for (int k = 0; k < 300 * rate / 1000; k++) snd.add(0f);
            long[] ws = new long[said.length];
            for (int w = 0; w < said.length; w++) {
                ws[w] = snd.size();
                int letters = said[w].replaceAll("[^A-Za-z]", "").length();
                for (int k = 0; k < letters * 60 * rate / 1000; k++) snd.add((float) (0.3 * Math.sin(k * 0.12)));
                for (int k = 0; k < gapMs[w] * rate / 1000; k++) snd.add(0f);
            }
            for (int k = 0; k < 300 * rate / 1000; k++) snd.add(0f);
            float[] sm = new float[snd.size()];
            for (int k = 0; k < sm.length; k++) sm[k] = snd.get(k);
            SpeechTiming st = SpeechTiming.of("Mr. Smith had 12 apples, then left.", "Mister Smith had twelve apples, then left.", sm, rate);
            check("timing by the said text: the comma's pause matched", st.matched, 1);
            check("timing by the said text: at 'Smith', 2 of 26 written letters", Math.round(st.fracAt(ws[1] + 10) * 26), 2);
            check("timing by the said text: at 'apples', 12 of 26", Math.round(st.fracAt(ws[4] + 10) * 26), 12);
            check("timing by the said text: no change when said as written", SpeechTiming.said2text("a b", "a b") == null, true);
            check("timing: \"Mr.\" isn't a place to pause", SpeechTiming.breaks("Mr. Collins then returned, slowly.").size(), 1);
            // a sentence that ends in "No." or "I." still ends there (the voice pauses): only "No. 5", "J. Smith" are short forms
            check("timing: \"No.\" ending a sentence is a pause", SpeechTiming.breaks("He said no. Then he left.").size(), 1);
            check("timing: \"No. 5\" isn't", SpeechTiming.breaks("Order No. 5 came, late.").size(), 1);
            check("timing: \"I.\" ending a sentence is a pause", SpeechTiming.breaks("Neither did I. She laughed.").size(), 1);
            check("timing: an initial isn't", SpeechTiming.breaks("J. Smith came.").size(), 0);
            check("timing: \"Ch. 3\" isn't", SpeechTiming.breaks("See Ch. 3 now.").size(), 0);
            // said words with nothing written before the first written word (or after the last) still count
            float[][] pre = SpeechTiming.said2text("Hello there", "Um Hello there");
            check("timing by the said text: a said word before the first written one goes with it", Math.round(pre[1][0] * 100), 58);
            float[][] post = SpeechTiming.said2text("Hello there", "Hello there friend");
            check("timing by the said text: a said word after the last written one goes with it", Math.round(post[1][0] * 100), 31);
        }
        // Kindle page geometry: where sentences and words are, and what a tap points at
        java.util.List<String> ls = Arrays.asList("The road out of the valley was", "older than the town. Mara walked", "it every morning, and liked it.");
        java.util.List<float[]> bx = Arrays.asList(new float[]{40, 100, 400, 140}, new float[]{0, 140, 400, 180}, new float[]{0, 180, 380, 220});
        KindleLayout kl = new KindleLayout(ls, bx, null);
        java.util.List<int[]> sp = kl.spans(Arrays.asList("The road out of the valley was older than the town.", "Mara walked it every morning, and liked it."));
        check("sentence spans", Arrays.toString(Arrays.copyOf(sp.get(0), 2)) + Arrays.toString(Arrays.copyOf(sp.get(1), 2)), "[0, 40][40, 74]");
        check("second sentence covers two lines", kl.boxesFor(sp.get(1)[0], sp.get(1)[1]).size(), 2);
        check("its first word starts mid-line", Math.round(kl.wordAt(sp.get(1)[0], sp.get(1)[1], 0f)[0]), 263);
        check("a tap on 'Mara' starts that sentence", kl.letterAt(300, 160), 40);
        check("a sentence begun on the page before is found by its end",
            Arrays.toString(kl.spans(Arrays.asList("Far off, the road out of the valley was older than the town.")).get(0)), "[0, 40, 6, 46]");
        // a sentence that runs on to the next page: found by its start; the word is on this page only for its first part
        int[] runOn = kl.spans(Arrays.asList("The road out of the valley was older than the town.", "Mara walked it every morning, and liked it, until the snow came.")).get(1);
        check("a sentence running on to the next page is found by its start", Arrays.toString(runOn), "[40, 74, 0, 50]");
        check("halfway through it, the word is past this page's part", KindleLayout.localFrac(runOn, 0.9f), 1.0f);
        // ... also when only a few of its words are on this page (fewer than 30 letters), at the foot or the top of the page
        int[] shortRun = kl.spans(Arrays.asList("The road out of the valley was older than the town. Mara walked it every morning,", "and liked it. Snow fell and fell for days on end.")).get(1);
        check("a sentence with only a few words at the foot of the page is found", Arrays.toString(shortRun), "[64, 74, 0, 37]");
        KindleLayout top = new KindleLayout(Arrays.asList("ment to me. The road out of the", "valley was old."), Arrays.asList(new float[]{0, 100, 400, 140}, new float[]{0, 140, 400, 180}), null);
        check("a sentence with only a few words at the top of the page is found", Arrays.toString(top.spans(Arrays.asList("His attachment to me.", "The road out of the valley was old.")).get(0)), "[0, 8, 9, 17]");
        check("on the next page, the start isn't there", KindleLayout.localFrac(new int[]{0, 15, 34, 49}, 0.5f), -1.0f);
        check("on the next page, its end is", Math.round(KindleLayout.localFrac(new int[]{0, 15, 34, 49}, 0.9f) * 100), 67);
        {   // the page didn't turn after the sentence over the break was read whole: it isn't read again
            KindleText kx = new KindleText("");
            kx.chunks(Arrays.asList("It was late. He agreed with the plan"), false);
            check("sentence over the break made", kx.bridge("and left.") != null, true);
            check("no next page after it: nothing read again", kx.chunks(new java.util.ArrayList<>(), true).size(), 0);
        }
        // continuous scrolling: the new screen starts with some of the last one again; only what's new is read
        check("scrolled screen: overlap with the last one dropped", KindleText.afterOverlap(
            Arrays.asList("The road out of the valley was older than the town.", "Mara walked it every morning, and liked it, until"),
            Arrays.asList("Mara walked it every morning, and liked it, until the snow came.", "Then she stayed in.")),
            "[the snow came., Then she stayed in.]");
        check("scrolled screen: a whole paragraph read already is dropped", KindleText.afterOverlap(
            Arrays.asList("The road out of the valley was older than the town.", "Mara walked it every morning, and liked it."),
            Arrays.asList("Mara walked it every morning, and liked it.", "Then she stayed in.")), "[Then she stayed in.]");
        check("turned page (no overlap): unchanged", KindleText.afterOverlap(
            Arrays.asList("The road out of the valley was older than the town."), Arrays.asList("Mara walked it every morning.")), "[Mara walked it every morning.]");
        // the sentence over a page break, whole, when the next page's start is known
        KindleText kb = new KindleText("");
        java.util.List<Chapter.Chunk> pgb = kb.chunks(Arrays.asList("It was late. He agreed with Mr. Morris"), false);
        Chapter.Chunk br = kb.bridge("immediately; that he is to take possession. \u201cWhat is his name?\u201d");
        check("page break: the page's whole sentences", pgb.size() + " " + pgb.get(0).text, "1 It was late.");
        check("page break: the sentence over it, whole (not cut at Mr.)", br.text, "He agreed with Mr. Morris immediately; that he is to take possession.");
        check("page break: where the next page starts in it", kb.bridgeHead, 25);
        java.util.List<Chapter.Chunk> pg2 = kb.chunks(Arrays.asList("immediately; that he is to take possession. \u201cWhat is his name?\u201d"), false);
        check("page break: the next page goes on after it", pg2.size() + " " + pg2.get(0).text, "1 \u201cWhat is his name?\u201d");
        {   // the next page's start, as shared before the turn, with other quote marks than the page itself: not read twice
            KindleText kq2 = new KindleText("");
            kq2.chunks(Arrays.asList("It was late. Then he said"), false);
            check("page break, other quotes: sentence over it", kq2.bridge("\u201cno.\u201d She left the room.").text, "Then he said \u201cno.\u201d");
            java.util.List<Chapter.Chunk> q2 = kq2.chunks(Arrays.asList("\"no.\" She left the room."), false);
            check("page break, other quotes: the next page goes on after it", q2.size() + " " + q2.get(0).text, "1 She left the room.");
            check("letters at the start: a different start isn't taken", KindleText.afterLetters("and right.", "and left."), -1);
            check("letters at the start: marks after them go too", KindleText.afterLetters("No!\u201d he said.", "\"No!\""), 4);
        }
        // a word hyphenated over the page break comes as two pieces ("there" | "fore"): one word again
        check("split word, no dictionary: two words", KindleText.joinBreak("You must there", "fore allow me"), "You must there fore allow me");
        java.util.Set<String> dict = new java.util.HashSet<>(Arrays.asList("therefore", "attachment", "often", "sand", "fora"));
        KindleText.dictionary = dict::contains;
        check("split word: one word again", KindleText.joinBreak("You must there", "fore allow me"), "You must therefore allow me");
        check("split word: attach|ment", KindleText.joinBreak("his father\u2019s uncommon attach", "ment to me"), "his father\u2019s uncommon attachment to me");
        check("two words that also make one: of|ten stays", KindleText.joinBreak("one of", "ten men"), "one of ten men");
        check("two words that also make one: for|a stays", KindleText.joinBreak("waited for", "a while"), "waited for a while");
        check("a single letter isn't half a word", KindleText.joinBreak("Bob's", "and"), "Bob's and");
        check("not a word together: two words", KindleText.joinBreak("nobody else", "bothered to come"), "nobody else bothered to come");
        check("a capital starts a new word", KindleText.joinBreak("You must there", "Fore"), "You must there Fore");
        check("a kept hyphen joins", KindleText.joinBreak("a well-", "known man"), "a well-known man");
        KindleText kw = new KindleText("");
        kw.chunks(Arrays.asList("It was late. You must there"), false);
        check("split word over the break, whole sentence", kw.bridge("fore allow me to go. Then he left.").text, "You must therefore allow me to go.");
        KindleText kw2 = new KindleText("");
        kw2.chunks(Arrays.asList("It was late. You must there"), false);
        check("split word, next page (no bridge)", kw2.chunks(Arrays.asList("fore allow me to go. Then he left."), false).get(0).text.startsWith("You must therefore allow"), true);
        KindleText.dictionary = null;
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
        // a chapter heading: bigger and spaced out ("C H A P T E R  2"), each letter its own run of ink
        int[] px3 = new int[W * (H + 200)];
        java.util.Arrays.fill(px3, 0xFFF8F0E0);
        for (int yy = 0; yy < H; yy++) for (int xx = 0; xx < W; xx++) if (px[yy * W + xx] != 0xFF101010) px3[(yy + 200) * W + xx] = 0xFF202020;
        for (int c = 0; c < 9; c++) { int hx = 100 + c * 45 + (c == 8 ? 40 : 0); for (int yy = 60; yy < 140; yy++) for (int xx = hx; xx < hx + 30; xx++) px3[yy * W + xx] = 0xFF202020; }
        java.util.List<float[][]> pg3 = InkLayout.pageBoxes(Arrays.asList("CHAPTER 2", inkText), InkLayout.words(px3, W, H + 200, 0, 0));
        check("heading page: the text placed", pg3 != null && pg3.get(1) != null && pg3.get(1)[vi] != null
            && Math.abs(pg3.get(1)[vi][1] - (where.get("valley")[1] + 200)) < 4, true);
        check("heading page: the heading spread along its row", pg3 != null && pg3.get(0) != null && pg3.get(0)[0] != null
            && Math.abs(pg3.get(0)[0][0] - 100) < 3 && pg3.get(0)[0][1] == 60 && Math.abs(pg3.get(0)[8][2] - 530) < 3 && pg3.get(0)[7] == null, true);
        // spaces in justified text: loose rows (26-28 px) and tight ones (9-11 px), letters 1-6 px apart
        java.util.List<Integer> gs = new java.util.ArrayList<>();
        int[][] hist = {{1, 29}, {2, 36}, {3, 51}, {4, 13}, {5, 6}, {6, 4}, {9, 1}, {10, 1}, {11, 4}, {13, 4}, {14, 4}, {20, 2}, {26, 3}, {27, 3}, {28, 4}};
        for (int[] hb : hist) for (int c = 0; c < hb[1]; c++) gs.add(hb[0]);
        int cutG = InkLayout.wordGap(gs, 48);
        check("ink: tight spaces still count as spaces, letter gaps don't", cutG > 6 && cutG <= 9, true);
        // Android's regex (ICU) refuses a look-behind that can match without a length limit ("[a-z]+" inside "(?<=...)"),
        // which plain Java allows: Chapter's class then fails to load on the phone and the app closes (0.2.203, 2026-11-01)
        for (Class<?> c : new Class<?>[]{Chapter.class, KindleText.class, SpeechTiming.class, Dialogue.class, KindleLayout.class, InkLayout.class, VoiceRecording.class})
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != java.util.regex.Pattern.class) continue;
                try { f.setAccessible(true); java.util.regex.Pattern pt = (java.util.regex.Pattern) f.get(null);
                    String u = unboundedLookBehind(pt.pattern());
                    if (u != null) check("Android regex: " + c.getSimpleName() + "." + f.getName() + " look-behind has a length limit", u, "none");
                } catch (IllegalAccessException e) { check("read " + f.getName(), e, "ok"); }
            }
        check("Android regex check finds \"[a-z]+\" in a look-behind", unboundedLookBehind("(?<=\\b(?:he|[A-Z][a-z]+) )dove"), "+ at 22");
        check("Android regex check: bounded ones pass", unboundedLookBehind("(?<!\\b(?:a|the)[ \\t])(?<=[a-z]{1,20} )x+[(?<=+]"), null);
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
