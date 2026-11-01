package com.loudbook.app;
import java.nio.file.*; import java.util.*;
/** Reads TimDump output turned into lines "text TAB frac,trueSecs;... TAB rms,rms,..." (see README) and prints how far
 *  SpeechTiming puts each comma from where Kokoro really says it. */
public class RmsEval { public static void main(String[] a) throws Exception {
  int rate=24000, win=240; double sum=0; int n=0, big=0; List<Double> errs=new ArrayList<>();
  for (String line : Files.readAllLines(Paths.get(a[0]))) {
    String[] t = line.split("\t"); String[] rm = t[2].split(",");
    float[] s = new float[rm.length*win];
    for (int w=0; w<rm.length; w++){ float v=Float.parseFloat(rm[w]); for(int k=0;k<win;k++) s[w*win+k]= (k%2==0? v : -v); }
    SpeechTiming st = SpeechTiming.of(t[0], s, rate);
    if (t[1].isEmpty()) continue;
    for (String bb : t[1].split(";")){ String[] b=bb.split(","); float f=Float.parseFloat(b[0]); double T=Double.parseDouble(b[1]);
      long lo=0, hi=s.length; while(lo<hi){ long mid=(lo+hi)/2; if (st.fracAt(mid) >= f-1e-4) hi=mid; else lo=mid+1; }
      double e=Math.abs(lo/(double)rate - T); errs.add(e); sum+=e; n++; if(e>0.4) big++; }
  }
  Collections.sort(errs);
  System.out.printf("mean %.3f median %.3f p90 %.3f big %d of %d%n", sum/n, errs.get(n/2), errs.get((int)(n*.9)), big, n);
}}
