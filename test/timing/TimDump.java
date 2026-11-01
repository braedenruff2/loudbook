package com.loudbook.app;
import com.k2fsa.sherpa.onnx.*;
import java.util.*;
/** JSON per sentence: rms (10 ms windows) of the full clip, breaks, and each break's true time (prefix said alone). */
public class TimDump {
  static OfflineTts tts; static int sid;
  static float[] gen(String t){ return tts.generate(Chapter.forKokoro(t), sid, 1.0f).getSamples(); }
  public static void main(String[] a) throws Exception {
  String k = a[0]; sid = Integer.parseInt(a[1]);
  tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
      .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(k + "/model.onnx").setVoices(k + "/voices.bin")
          .setTokens(k + "/tokens.txt").setLexicon(k + "/lexicon-us-en.txt").setDataDir(k + "/espeak-ng-data").build())
      .setNumThreads(2).setDebug(false).build()).setMaxNumSentences(1).build());
  int rate = 24000;
  java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8"));
  java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream(a[2], true), true, "UTF-8");
  for (String line; (line = r.readLine()) != null; ) {
    float[] s = gen(line);
    int win = rate/100, nw = s.length/win; StringBuilder rm = new StringBuilder();
    for (int w=0;w<nw;w++){double sum=0; for(int q=w*win;q<(w+1)*win;q++) sum+=s[q]*(double)s[q]; rm.append(w>0?",":"").append(String.format(Locale.ROOT,"%.5f",Math.sqrt(sum/win)));}
    SpeechTiming st = SpeechTiming.of(line, s, rate);
    List<float[]> br = SpeechTiming.breaks(line);
    int total=0; for(char c: line.toCharArray()) if(Character.isLetterOrDigit(c)) total++;
    StringBuilder bs = new StringBuilder();
    for (float[] b : br) {
      int want = Math.round(b[0]*total), n=0, ci=0;
      for (; ci<line.length(); ci++){ if(Character.isLetterOrDigit(line.charAt(ci))){ n++; if(n==want) break; } }
      int end = ci+1; while(end<line.length() && !Character.isWhitespace(line.charAt(end))) end++;
      SpeechTiming pst = SpeechTiming.of(line.substring(0,end), gen(line.substring(0,end)), rate);
      double trueT = (st.speechStart + (pst.speechEnd - pst.speechStart))/(double)rate;
      bs.append(bs.length()>0?",":"").append(String.format(Locale.ROOT,"[%.4f,%d,%.3f]", b[0], (int)b[1], trueT));
    }
    StringBuilder an = new StringBuilder();
    for (int i=0;i<st.at.length;i++) an.append(i>0?",":"").append(String.format(Locale.ROOT,"[%.3f,%.4f]", st.at[i]/(double)rate, st.frac[i]));
    out.println("{\"text\":" + org.json.JSONObject.quote(line) + ",\"breaks\":[" + bs + "],\"anchors\":[" + an + "],\"rms\":[" + rm + "]}");
  }
}}
