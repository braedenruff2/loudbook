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
    try { System.out.println("health: " + l.health(3000));
      PcLink.Audio au = l.speak("Hello there, \"friend\". This is a test.", 3, 1.0f, 10000);
      System.out.println("speak: " + au.pcm.length + " samples @" + au.rate + " work " + au.workMs + "ms");
    } catch (Exception e) { System.out.println("USE FAILED: " + e.getMessage()); }
  } else if (cmd.equals("discover")) {
    for (PcLink.Found f : PcLink.discover(1500)) System.out.println("found " + f.host + ":" + f.port + " " + f.fp.substring(0,16) + " " + f.name);
  }
}}
