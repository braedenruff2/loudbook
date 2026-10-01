#!/usr/bin/env bash
# Builds Loudbook.apk without Gradle: aapt2 (resources) -> javac -> d8 (dex) -> zip -> zipalign -> apksigner.
# GitHub Actions runs it on every push (.github/workflows/build.yml, parts from tools/ci-parts.sh).
#
#   PARTS=/path/to/parts bash build.sh
# Optional: BT / ANDROID_JAR (use these instead of searching PARTS), LB_CODE / LB_NAME (version),
# LB_REPO (owner/name on GitHub, turns on self-updates), VOICE_IN_APK=1 (put the 354 MB model in
# the APK instead of downloading it on first run), CORE=1 (stop after the unsigned APK).
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
PARTS=${PARTS:-$ROOT/parts}
BT=${BT:-$(find "$PARTS/build-tools" -maxdepth 2 -name aapt2 -printf "%h\n" | head -1)}
ANDROID_JAR=${ANDROID_JAR:-$(find "$PARTS/platform" -name android.jar | head -1)}
LB_CODE=${LB_CODE:-1}
LB_NAME=${LB_NAME:-0.2.0-local}
LB_REPO=${LB_REPO:-}
ABI=${ABI:-arm64-v8a}                     # x86_64 for the emulator test
JNI=$PARTS/jniLibs/$ABI
MODEL=$(find "$PARTS" -maxdepth 1 -type d -name "kokoro*" | head -1)
OUT=$ROOT/build
for need in "$BT/aapt2" "$BT/lib/d8.jar" "$BT/zipalign" "$BT/lib/apksigner.jar" "$ANDROID_JAR" "$JNI/libsherpa-onnx-jni.so" "$MODEL/tokens.txt"; do
  [ -e "$need" ] || { echo "missing: $need"; exit 1; }
done
echo "build-tools: $BT"
echo "platform:    $ANDROID_JAR"

rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex,apk}

echo "- page script"
# rebuilt from the Chrome extension when it's next door; otherwise the committed copy is used
if [ -d "${LOUDBOOK_EXT:-../loudbook}/lib" ]; then python3 tools/bundle_web.py; else echo "  (using app/assets/web/loudbook.js as committed)"; fi

echo "- version $LB_NAME ($LB_CODE)${LB_REPO:+, updates from github.com/$LB_REPO}"
mkdir -p "$OUT/gen/com/loudbook/app"
cat > "$OUT/gen/com/loudbook/app/BuildInfo.java" <<JAVA
package com.loudbook.app;

final class BuildInfo {
    static final String VERSION = "$LB_NAME";
    static final int CODE = $LB_CODE;
    static final String REPO = "$LB_REPO";
    static final String RELEASES = "https://github.com/$LB_REPO/releases/";
    static final String VOICE_URL = RELEASES + "download/voice-1/";
}
JAVA

echo "- resources"
"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml \
  --java "$OUT/gen" -o "$OUT/base.apk" --auto-add-overlay "$OUT/res.zip" \
  --version-code "$LB_CODE" --version-name "$LB_NAME" --replace-version

echo "- java"
find app/src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "- dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "- package"
cp "$OUT/base.apk" "$OUT/apk/unsigned.apk"
STAGE=$OUT/stage; rm -rf "$STAGE"; mkdir -p "$STAGE/lib/$ABI" "$STAGE/assets"
cp "$OUT/dex/classes.dex" "$STAGE/"
cp "$JNI/libsherpa-onnx-jni.so" "$JNI/libonnxruntime.so" "$STAGE/lib/$ABI/"
cp -r app/assets/web "$STAGE/assets/"
mkdir -p "$STAGE/assets/kokoro"
BIG=""; [ "${VOICE_IN_APK:-0}" = 1 ] && BIG="model.onnx voices.bin"
for f in $BIG tokens.txt lexicon-us-en.txt lexicon-zh.txt; do
  [ -e "$MODEL/$f" ] && cp "$MODEL/$f" "$STAGE/assets/kokoro/"
done
cp -r "$MODEL/espeak-ng-data" "$STAGE/assets/kokoro/"
( cd "$STAGE"
  zip -q "$OUT/apk/unsigned.apk" classes.dex
  zip -q -r "$OUT/apk/unsigned.apk" assets/web assets/kokoro/espeak-ng-data $(ls assets/kokoro/*.txt 2>/dev/null)
  # the model, voices and native code are stored uncompressed so they can be read in place
  zip -q -0 "$OUT/apk/unsigned.apk" $(ls assets/kokoro/*.onnx assets/kokoro/*.bin 2>/dev/null) lib/$ABI/*.so )
if [ "${CORE:-0}" = 1 ]; then cp "$OUT/apk/unsigned.apk" "$OUT/core-unsigned.apk"; ls -la "$OUT/core-unsigned.apk"; exit 0; fi

"$BT/zipalign" -P 16 -f 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk" 2>/dev/null || \
  "$BT/zipalign" -p -f 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "- sign"
KS=$ROOT/keystore/loudbook.jks
if [ -n "${KEYSTORE_B64:-}" ]; then KS=$OUT/key.jks; echo "$KEYSTORE_B64" | base64 -d > "$KS"; fi
if [ ! -e "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -keystore "$KS" -storepass loudbook -keypass loudbook -alias loudbook \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Loudbook, O=Personal" >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:loudbook --key-pass pass:loudbook \
  --out "$OUT/Loudbook.apk" "$OUT/apk/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify "$OUT/Loudbook.apk"
printf '{"code": %s, "name": "%s", "size": %s}\n' "$LB_CODE" "$LB_NAME" "$(stat -c %s "$OUT/Loudbook.apk")" > "$OUT/version.json"
ls -la "$OUT/Loudbook.apk"; cat "$OUT/version.json"
