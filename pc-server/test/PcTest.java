package com.loudbook.app;
public class PcTest { public static void main(String[] a) throws Exception {
  String cmd = a[0];
  if (cmd.equals("pair")) {
    try { PcLink l = PcLink.pair("127.0.0.1", 8770, a[1], "Test phone");
      System.out.println("PAIRED fp=" + l.fp.substring(0,16) + " token=" + l.token.length());
      java.nio.file.Files.write(java.nio.file.Paths.get("link.txt"), (l.fp + "\n" + l.token).getBytes());
    } catch (Exception e) { System.out.println("PAIR FAILED: " + e.getMessage()); }
  } else if (cmd.equals("use")) {
    String[] k = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("link.txt"))).split("\n");
    PcLink l = new PcLink("127.0.0.1", 8770, k[0], a.length > 1 && a[1].equals("badtoken") ? "AAAA" + k[1].substring(4) : k[1]);
    if (a.length > 1 && a[1].equals("badpin")) l = new PcLink("127.0.0.1", 8770, "00" + k[0].substring(2), k[1]);
    try { PcLink.Health h = l.health(5000);
      System.out.println("health: " + h.engine + " | natural: " + h.natural + " ok=" + h.naturalOk + " speed=" + h.naturalSpeed);
      for (String eng : new String[]{"kokoro", "natural"}) {
        PcLink.Audio au = l.speak("Hello there, \"friend\". This is a test of the reading voice.", 3, 1.25f, eng, 180000);
        System.out.println("speak " + eng + ": " + au.pcm.length + " samples @" + au.rate + " work " + au.workMs + "ms, engine " + au.engine + ", made at " + au.madeAt);
        if (a.length > 2) java.nio.file.Files.write(java.nio.file.Paths.get(eng + ".pcm"), toBytes(au.pcm));
      }
    } catch (Exception e) { System.out.println("USE FAILED: " + e.getMessage()); }
  } else if (cmd.equals("voice")) {
    // a "recorded voice": the Kokoro sentence from "use x save", four times over (about 10 s)
    String[] k = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("link.txt"))).split("\n");
    PcLink l = new PcLink("127.0.0.1", 8770, k[0], k[1]);
    try {
      byte[] one = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("kokoro.pcm"));
      java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
      for (int i = 0; i < 4; i++) b.write(one);
      byte[] wav = VoiceRecording.toWav(b.toByteArray(), 24000);
      System.out.println("recording: " + (wav == null ? "REJECTED" : wav.length + " bytes"));
      String id = l.addVoice("Test reader", wav);
      System.out.println("added voice " + id);
      for (PcLink.Recorded r : l.voices(5000)) System.out.println("voice on PC: " + r.id + " " + r.name);
      PcLink.Audio au = l.speak("This sentence should come out in the recorded voice.", 3, 1f, "natural", id, 300000);
      System.out.println("speak recorded: " + au.pcm.length + " samples @" + au.rate + " work " + au.workMs + "ms, engine " + au.engine);
      l.deleteVoice(id);
      System.out.println("after delete: " + l.voices(5000).size() + " voices");
      try { l.speak("Gone.", 3, 1f, "natural", id, 30000); System.out.println("DELETED VOICE STILL SPOKE"); }
      catch (Exception e) { System.out.println("deleted voice refused: " + e.getMessage()); }
    } catch (Exception e) { System.out.println("VOICE FAILED: " + e.getMessage()); }
  } else if (cmd.equals("discover")) {
    for (PcLink.Found f : PcLink.discover(1500)) System.out.println("found " + f.host + ":" + f.port + " " + f.fp.substring(0,16) + " " + f.name);
  }
}
  static byte[] toBytes(short[] p) { byte[] b = new byte[p.length * 2]; for (int i = 0; i < p.length; i++) { b[2*i] = (byte) p[i]; b[2*i+1] = (byte) (p[i] >> 8); } return b; }
}
