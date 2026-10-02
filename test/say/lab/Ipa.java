package b;
import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;
import java.util.*;
/** Prints the text Kokoro is given (one per line on stdin); the phonemes come out on stderr (debug). */
public class Ipa { public static void main(String[] a) throws Exception {
  String kokoro = a[0];
  OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder()
      .setKokoro(OfflineTtsKokoroModelConfig.builder().setModel(kokoro + "/model.onnx").setVoices(kokoro + "/voices.bin")
          .setTokens(kokoro + "/tokens.txt").setLexicon(kokoro + "/lexicon-us-en.txt").setDataDir(kokoro + "/espeak-ng-data").build())
      .setNumThreads(4).setDebug(true).build()).setMaxNumSentences(1).build());
  java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8"));
  for (String line; (line = r.readLine()) != null; ) {
    System.err.println("@@CASE " + line); System.err.flush();
    tts.generate(line, 3, 1.0f);
    System.err.println("@@END"); System.err.flush();
  }
}}
