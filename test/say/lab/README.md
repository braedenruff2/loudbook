# Pronunciation lab

Shows exactly how the phone voice (Kokoro) will pronounce a line: the text Loudbook gives it,
and the phonemes Kokoro turns that into. Used to find and fix lines that come out wrong
(Roman numerals, stat blocks, units, "Mr.", ellipses and so on); the fixes live in the Chrome
extension's lib/say.js (shared with the app) and are checked by test/say/run.js.

Needs the sherpa-onnx desktop JNI library (k2-fsa releases, linux-x64-jni) and the Kokoro model:

    javac -d out app/src/com/k2fsa/sherpa/onnx/*.java test/say/lab/Ipa.java   (plus test/jvm stubs)
    echo "Chapter IV" | node say.js | python3 ipa.py
