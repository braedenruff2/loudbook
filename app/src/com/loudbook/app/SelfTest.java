package com.loudbook.app;

import android.content.Context;
import android.util.Log;

/**
 * Checks run on an emulator by the "Android test" GitHub workflow (adb shell am start ... --es
 * selftest commands). Each step logs under the tag LoudbookTest, so a crash shows exactly where.
 */
final class SelfTest {
    static final String TAG = "LoudbookTest";

    static void commands(Context c, VoiceCommands vc) {
        try {
            step("download");
            // (the emulator's network can take a while to come up after boot: the first try failed with no reason shown,
            // 2026-11-02. Android hides the stack of an UnknownHostException, so the reason goes in the message.)
            for (int attempt = 1; ; attempt++) {
                try { VoiceCommands.fetch(c, null); break; }
                catch (Exception e) {
                    step("download try " + attempt + " failed: " + e);
                    if (attempt >= 4) throw e;
                    Thread.sleep(15_000);
                }
            }
            for (String f : VoiceCommands.FILES) step("  " + f + " " + new java.io.File(VoiceCommands.dir(c), f).length());
            step("load");
            vc.load(c);
            step("spot silence -> " + vc.spot(new float[VoiceCommands.RATE]));
            float[] noise = new float[VoiceCommands.RATE];
            java.util.Random r = new java.util.Random(1);
            for (int i = 0; i < noise.length; i++) noise[i] = (float) (r.nextGaussian() * 0.05);
            step("embed noise -> " + vc.embed(noise).length);
            step("record 1 s");
            float[] rec = VoiceCommands.record(1.0);
            step("recorded " + rec.length + ", speech part " + (VoiceCommands.speechPart(rec) == null ? "none" : "some"));
            step("DONE commands");
        } catch (Throwable t) {
            Log.e(TAG, "FAILED commands: " + t, t);
        }
    }

    static void step(String s) { Log.i(TAG, s); }
}
