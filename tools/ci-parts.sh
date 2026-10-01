#!/usr/bin/env bash
# GitHub Actions: fetches sherpa-onnx's Android libraries and the Kokoro model (cached between
# runs), and finds the runner's Android build tools. Writes BT / ANDROID_JAR to $GITHUB_ENV.
set -euo pipefail
cd "$(dirname "$0")/.."
SV=v1.13.8
mkdir -p downloads parts
get() { [ -s "downloads/$2" ] || curl -fsSL --retry 5 -o "downloads/$2" "$1"; }
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SV/sherpa-onnx-$SV-android.tar.bz2" sherpa-onnx-android.tar.bz2
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2" kokoro-multi-lang-v1_0.tar.bz2
tar xjf downloads/sherpa-onnx-android.tar.bz2 -C parts ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so ./jniLibs/arm64-v8a/libonnxruntime.so \
  ./jniLibs/x86_64/libsherpa-onnx-jni.so ./jniLibs/x86_64/libonnxruntime.so
[ -e parts/kokoro-multi-lang-v1_0/model.onnx ] || tar xjf downloads/kokoro-multi-lang-v1_0.tar.bz2 -C parts

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
pick_bt() {
  for d in $(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -rV); do
    [ -e "$d/aapt2" ] && [ -e "$d/lib/d8.jar" ] && [ -e "$d/core-lambda-stubs.jar" ] && [ -e "$d/zipalign" ] && { echo "$d"; return; }
  done
}
BT=$(pick_bt || true)
JAR=$SDK/platforms/android-35/android.jar
if [ -z "$BT" ] || [ ! -e "$JAR" ]; then
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "build-tools;35.0.0" "platforms;android-35" >/dev/null
  BT=$(pick_bt)
fi
echo "build tools: $BT"; echo "android.jar: $JAR"
{ echo "BT=$BT"; echo "ANDROID_JAR=$JAR"; } >> "${GITHUB_ENV:-/dev/null}"
