// Loudbook: replaces sherpa-onnx's desktop loader (which unpacks a native library from a jar).
// On Android the library ships in the APK's lib/ folder and is loaded by name.
package com.k2fsa.sherpa.onnx;

public class LibraryLoader {
    private static volatile boolean isLoaded = false;

    static synchronized void loadLibrary() {
        if (!isLoaded) {
            System.loadLibrary("sherpa-onnx-jni");
            isLoaded = true;
        }
    }

    public static void setAutoLoadEnabled(boolean enabled) { }

    static void maybeLoad() { loadLibrary(); }
}
