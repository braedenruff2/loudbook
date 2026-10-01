#!/usr/bin/env bash
# Builds the stand-in Kindle used by the emulator test. BT and ANDROID_JAR as for the app.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out; mkdir -p out/classes out/dex
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -o out/base.apk
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d out/classes src/com/amazon/kindle/Reader.java
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/fake.apk
(cd out/dex && zip -q ../fake.apk classes.dex)
"$BT/zipalign" -f 4 out/fake.apk out/aligned.apk
keytool -genkeypair -keystore out/k.jks -storepass test12 -keypass test12 -alias t -keyalg RSA -keysize 2048 -validity 100 -dname CN=test >/dev/null 2>&1
java -jar "$BT/lib/apksigner.jar" sign --ks out/k.jks --ks-pass pass:test12 --key-pass pass:test12 --out out/FakeKindle.apk out/aligned.apk
ls -la out/FakeKindle.apk
