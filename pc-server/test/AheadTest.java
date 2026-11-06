package com.loudbook.app;
/** Best quality on the PC (pc-server with --fake and LOUDBOOK_FAKE_BEST=1): sentences sent ahead are made and then
 *  answered from the PC's folder; a sentence not made yet is read live. Args: port, pairing code. */
public class AheadTest { public static void main(String[] a) throws Exception {
  int port = Integer.parseInt(a[0]);
  PcLink l = PcLink.pair("127.0.0.1", port, a[1], "Test phone");
  int bad = 0;
  java.util.List<String[]> items = new java.util.ArrayList<>();
  for (int i = 0; i < 12; i++) items.add(new String[]{"Sentence number " + i + " of the chapter, read ahead.", null, "3"});
  items.add(new String[]{"Close the door.", "Cloze the door.", "16"});
  int[] r = l.ahead(items, null, 10000);
  System.out.println("sent " + r[0] + ", made already " + r[1]);
  if (r[0] != 13 || r[1] != 0) { bad++; System.out.println("FAIL counts"); }
  // the first one is wanted at once: read live (not made yet), or made already
  PcLink.Audio first = l.speak("Sentence number 0 of the chapter, read ahead.", null, 3, 1.2f, "best", null, 20000);
  System.out.println("first: " + first.engine);
  long until = System.currentTimeMillis() + 20000;
  PcLink.Health h;
  do { Thread.sleep(300); h = l.health(5000); } while (h.aheadWaiting > 0 && System.currentTimeMillis() < until);
  System.out.println("health: best=" + h.best + " total=" + h.aheadTotal + " waiting=" + h.aheadWaiting + " made=" + h.aheadMade);
  if (h.aheadWaiting != 0 || h.aheadMade < 12) { bad++; System.out.println("FAIL not all made"); }
  for (int i = 1; i < 12; i++) {
    PcLink.Audio au = l.speak("Sentence number " + i + " of the chapter,  read ahead.", null, 3, 1.2f, "best", null, 20000);
    if (!"best".equals(au.engine) || au.madeAt != 1f || au.pcm.length == 0) { bad++; System.out.println("FAIL sentence " + i + ": " + au.engine + " at " + au.madeAt); }
  }
  PcLink.Audio other = l.speak("Close the door.", "Cloze the door.", 16, 1f, "best", null, 20000);
  System.out.println("other voice: " + other.engine);
  if (!"best".equals(other.engine)) { bad++; System.out.println("FAIL other voice not from the folder"); }
  // another voice for the same text isn't the same recording
  PcLink.Audio wrong = l.speak("Close the door.", "Cloze the door.", 3, 1f, "best", null, 20000);
  System.out.println("same text, voice 3: " + wrong.engine);
  if ("best".equals(wrong.engine)) { bad++; System.out.println("FAIL voice 3 got voice 16's recording"); }
  // sent again: all made already
  r = l.ahead(items, null, 10000);
  System.out.println("sent again: " + r[0] + ", made already " + r[1]);
  // (all 13, or 12 when the first one was read live before the PC got to it: it's passed over then)
  if (r[1] != 13 && !(r[1] == 12 && !"best".equals(first.engine))) { bad++; System.out.println("FAIL not kept"); }
  // a sentence read live while it waits is passed over by the maker
  java.util.List<String[]> more = new java.util.ArrayList<>();
  for (int i = 0; i < 40; i++) more.add(new String[]{"A later sentence, number " + i + ", in the next chapter.", null, "3"});
  l.ahead(more, null, 10000);
  PcLink.Audio live = l.speak("A later sentence, number 39, in the next chapter.", null, 3, 1f, "best", null, 20000);
  System.out.println("asked before it was made: " + live.engine);
  // a recorded voice that isn't on the PC
  try { l.ahead(items, "abcdefabcdef", 10000); bad++; System.out.println("FAIL unknown voice accepted"); }
  catch (java.io.IOException e) { System.out.println("unknown voice refused: " + e.getMessage()); }
  // the audiobook (test.job.json): both chapters made, listed, and copied whole
  java.util.List<PcLink.BookFile> bf = null;
  long end = System.currentTimeMillis() + 60000;
  while (System.currentTimeMillis() < end) {
    bf = l.books(5000);
    int mp3 = 0; for (PcLink.BookFile f : bf) if (f.path.endsWith(".mp3")) mp3++;
    if (mp3 == 2) break;
    Thread.sleep(1000);
  }
  System.out.println("audiobook files: " + (bf == null ? 0 : bf.size()));
  if (bf == null || bf.size() != 4) { bad++; System.out.println("FAIL expected 2 chapters (mp3 + json each)"); }
  else for (PcLink.BookFile f : bf) {
    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
    long n = l.download(f.path, o, 20000);
    boolean ok = n == f.bytes && o.size() == n && (!f.path.endsWith(".mp3") || n > 1000);
    if (f.path.endsWith(".json") && !new String(o.toByteArray(), "UTF-8").contains("\"sentences\"")) ok = false;
    System.out.println("  " + f.path + " " + n + " bytes" + (ok ? "" : " FAIL"));
    if (!ok) bad++;
  }
  // the Kindle book: only when asked for (older phones copied every file listed), downloaded whole
  java.util.List<PcLink.BookFile> kf = l.books(5000, true);
  PcLink.BookFile epub = null;
  for (PcLink.BookFile f : kf) if (f.path.endsWith(".epub")) epub = f;
  System.out.println("with Kindle books: " + kf.size() + " files" + (epub != null ? ", " + epub.path : ""));
  if (kf.size() != 5 || epub == null || !epub.path.equals("Test Book/Test Book 01 - Volume 1.epub") || epub.bytes != 300000) { bad++; System.out.println("FAIL expected the 4 chapter files + 1 EPUB"); }
  else {
    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
    long n = l.download(epub.path, o, 20000);
    if (n != 300000 || o.size() != 300000) { bad++; System.out.println("FAIL EPUB came " + n + " bytes"); }
  }
  for (PcLink.BookFile f : bf) if (f.path.endsWith(".epub")) { bad++; System.out.println("FAIL EPUB listed without asking: " + f.path); }
  for (String p : new String[]{"../state/devices.json", "Test Book/../../x.mp3", "Test Book/0001 - One.part.mp3", "x",
      "Test Book/notes.txt", "Test Book/nope.epub", "Test Book/../Test Book 01 - Volume 1.epub", "Other/Test Book 01 - Volume 1.epub"}) {
    try { l.download(p, new java.io.ByteArrayOutputStream(), 5000); bad++; System.out.println("FAIL served " + p); }
    catch (java.io.IOException e) { }
  }
  System.out.println(bad == 0 ? "AHEAD TEST OK" : bad + " AHEAD CHECKS FAILED");
  System.exit(bad == 0 ? 0 : 1);
}}
