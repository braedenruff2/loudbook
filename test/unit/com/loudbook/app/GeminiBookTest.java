package com.loudbook.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * GeminiBook's saved recordings (index.json + part files) when one part file is gone: a request that failed while
 * another one saved the index, or the app closed mid-request. The other recordings must still be there.
 * Plain Java (javac against android.jar with GeminiBook and what it uses; no phone).
 */
public class GeminiBookTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("gbt").toFile();
        List<Chapter.Chunk> cs = new ArrayList<>();
        String[] said = {"The first sentence.", "The second one.", "A third.", "And the fourth."};
        for (String t : said) cs.add(new Chapter.Chunk(0, t, t));
        Chapter ch = new Chapter("https://example.com/1", "web", "Test", "Story", cs);
        String key = GeminiBook.key("https://example.com/1", "Sulafat", "m", "");
        File dir = new File(root, key);
        dir.mkdirs();
        // part0 (sentences 0-1) was never written; part1 (sentences 2-3) is there
        File p1 = new File(dir, "part1-2.pcm");
        Files.write(p1.toPath(), new byte[48000 * 2]);
        int h0 = GeminiBook.hash(cs.get(0)), h1 = GeminiBook.hash(cs.get(1)), h2 = GeminiBook.hash(cs.get(2)), h3 = GeminiBook.hash(cs.get(3));
        String idx = "{\"v\":2,\"parts\":[[\"part0-1.pcm\",0,0,24000],[\"part1-2.pcm\",0,96000,24000]],\"segs\":["
            + "[0," + h0 + ",0,0,100,1],[1," + h1 + ",0,100,200,0],[2," + h2 + ",1,0,24000,1],[3," + h3 + ",1,24000,48000,0]]}";
        Files.write(new File(dir, "index.json").toPath(), idx.getBytes(StandardCharsets.UTF_8));
        GeminiBook b = GeminiBook.open(root, key);
        b.bind(ch);
        check("a missing part's sentences aren't there", b.secondsOf(0) == 0 && b.secondsOf(1) == 0);
        check("the other part's sentences still are (they were all forgotten)", Math.abs(b.secondsOf(2) - 1.0) < 1e-6 && Math.abs(b.secondsOf(3) - 1.0) < 1e-6);
        check("the first one missing is sentence 0", b.nextMissing(0, 4) == 0 && b.nextMissing(2, 4) == 4);
        if (fails > 0) { System.out.println(fails + " FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
