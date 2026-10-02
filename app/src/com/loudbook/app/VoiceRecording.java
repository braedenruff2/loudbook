package com.loudbook.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A recorded voice sample, tidied for the PC: silence at the ends cut, level evened. Plain Java. */
final class VoiceRecording {
    /** 16-bit mono PCM (little-endian) to a WAV file; null if there isn't 8 s of clear speech. */
    static byte[] toWav(byte[] raw, int rate) {
        int n = raw.length / 2;
        short[] s = new short[n];
        ByteBuffer.wrap(raw, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        int win = rate / 50;                                  // 20 ms
        int frames = n / win;
        if (frames < 10) return null;
        double[] rms = new double[frames];
        double peak = 0;
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int i = f * win; i < (f + 1) * win; i++) sum += (double) s[i] * s[i];
            rms[f] = Math.sqrt(sum / win);
            peak = Math.max(peak, rms[f]);
        }
        if (peak < 300) return null;                          // nothing but quiet
        int first = -1, last = -1;
        for (int f = 0; f < frames; f++) if (rms[f] > peak * 0.08) { if (first < 0) first = f; last = f; }
        int from = Math.max(0, (first - 10) * win), to = Math.min(n, (last + 15) * win);
        if (to - from < rate * 8) return null;
        int max = 1;
        for (int i = from; i < to; i++) max = Math.max(max, Math.abs(s[i]));
        double g = Math.min(8.0, 29000.0 / max);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bytes = (to - from) * 2;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + bytes).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes()).putInt(bytes);
        out.write(h.array(), 0, 44);
        ByteBuffer d = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = from; i < to; i++) d.putShort((short) Math.max(-32767, Math.min(32767, Math.round(s[i] * g))));
        out.write(d.array(), 0, bytes);
        return out.toByteArray();
    }
}
