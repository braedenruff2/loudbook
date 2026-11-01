# Highlight timing lab

Measures how well SpeechTiming (the word highlight for the phone/PC voice) keeps up with Kokoro, in the cloud:

1. Set up Kokoro as in test/say/lab (kokoro model in parts/, sherpa-onnx linux-x64-jni in /tmp/bench/jni).
2. `javac -d /tmp/lab -sourcepath test/jvm app/src/com/k2fsa/sherpa/onnx/*.java app/src/com/loudbook/app/{Chapter,SpeechTiming}.java test/timing/TimDump.java`
3. `LD_LIBRARY_PATH=$LIB java -Djava.library.path=$LIB -cp /tmp/lab com.loudbook.app.TimDump parts/kokoro-multi-lang-v1_0 3 out.jsonl < sentences.txt`
   (one sentence per line; voice 3 = Heart, 16 = Michael). Each comma's true time is where the sentence up to it, said
   alone, ends. Slow: ~10 s per sentence on 2 cores.
4. Turn it into TSV: `python3 -c "import json,sys; [print(r['text'].replace('\t',' ')+'\t'+';'.join('%s,%s'%(b[0],b[2]) for b in r['breaks'])+'\t'+','.join(map(str,r['rms']))) for r in map(json.loads, open('out.jsonl'))]" > out.tsv`
5. `javac -d /tmp/ut app/src/com/loudbook/app/SpeechTiming.java test/timing/RmsEval.java && java -cp /tmp/ut com.loudbook.app.RmsEval out.tsv`

2026-11-01, 100 Gutenberg sentences (voice 3) / 30 (voice 16): matching pauses one by one gave mean 0.25 s / 0.37 s,
36 of 219 / 19 of 76 commas more than 0.4 s out; the dynamic programme now in SpeechTiming: 0.13 s / 0.16 s, 6 / 4.
