package android.media;
/** Test stand-in: no microphone on the build machine. */
public class AudioRecord {
    public static final int STATE_INITIALIZED = 1;
    public static int getMinBufferSize(int r, int c, int e) { return 3200; }
    public AudioRecord(int s, int r, int c, int e, int b) { }
    public int getState() { return 0; }
    public int getAudioSessionId() { return 0; }
    public void startRecording() { } public void stop() { } public void release() { }
    public int read(short[] b, int o, int n) { return -1; }
}
