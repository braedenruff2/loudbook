package com.loudbook.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plays a chapter, sentence by sentence, in the background: makes each sentence with Kokoro a
 * few sentences ahead, plays it, and at the end asks the screen (MainActivity) to open the next
 * chapter. Runs as a foreground service with a media notification, so it keeps going with the
 * screen off and the lock-screen / headset buttons work.
 */
public class ReaderService extends Service {
    static final String TAG = "Loudbook";
    static final String ACT_PLAY = "lb.play", ACT_PAUSE = "lb.pause", ACT_TOGGLE = "lb.toggle",
        ACT_BACK = "lb.back", ACT_FWD = "lb.fwd", ACT_STOP = "lb.stop";
    static final int NOTIF_ID = 7;
    static final int READ_AHEAD = 8;

    /** What the screen hears about. Always called on the main thread. */
    interface Listener {
        void onState(boolean playing, boolean busy);
        void onSpeaking(Chapter ch, int pos);
        void onStatus(String text, boolean error);
        /** The chapter ended and there's a next one: please open it and call setChapter(..., true). */
        void onNeedChapter(String url);
        void onFinished(Chapter ch);
    }

    final class LocalBinder extends Binder { ReaderService service() { return ReaderService.this; } }
    private final IBinder binder = new LocalBinder();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Voice voice = new Voice();
    private final ExecutorService synth = Executors.newSingleThreadExecutor();
    private final Map<Integer, Future<Voice.Clip>> clips = new HashMap<>();
    private Listener listener;
    private SharedPreferences prefs;

    private Chapter chapter;
    private int pos = 0;
    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile int gen = 0;                  // bumped on every stop/seek/chapter change
    private int clipsGen = 0;                      // which chapter+voice the cached clips belong to
    private Thread player;
    private AudioTrack track;
    private long framesWritten = 0;
    private float speed = 1f;
    private int sid = 3;
    boolean autoNext = true;
    private long sleepAt = 0;                      // epoch ms, 0 = off
    boolean stopAtChapterEnd = false;
    private volatile boolean voiceLoading = false;
    private double audioSecs = 0, workSecs = 0;    // how fast Kokoro runs here

    private MediaSession session;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean foreground = false;

    // ---------------------------------------------------------------- lifecycle
    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        speed = prefs.getFloat("speed", 1f);
        sid = prefs.getInt("voice", 3);
        autoNext = prefs.getBoolean("autoNext", true);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loudbook:reading");
        wake.setReferenceCounted(false);
        session = new MediaSession(this, "Loudbook");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { seek(pos + 1); }
            @Override public void onSkipToPrevious() { seek(pos - 1); }
            @Override public void onFastForward() { seek(pos + 1); }
            @Override public void onRewind() { seek(pos - 1); }
        });
        session.setActive(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("reading", "Reading aloud", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        warmUp();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_PLAY.equals(a)) play();
        else if (ACT_PAUSE.equals(a)) pause();
        else if (ACT_TOGGLE.equals(a)) { if (playing) pause(); else play(); }
        else if (ACT_BACK.equals(a)) seek(pos - 1);
        else if (ACT_FWD.equals(a)) seek(pos + 1);
        else if (ACT_STOP.equals(a)) { pause(); dropForeground(true); stopSelf(); }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        stopPlayback();
        synth.shutdownNow();
        session.release();
        if (track != null) { track.release(); track = null; }
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    void setListener(Listener l) { listener = l; if (l != null) { l.onState(playing, voiceLoading); if (chapter != null) l.onSpeaking(chapter, pos); } }

    // ---------------------------------------------------------------- the voice
    /** Load Kokoro in the background as soon as the app starts, so play is instant. */
    void warmUp() {
        if (voice.ready() || voiceLoading) return;
        voiceLoading = true;
        status("Getting the voice ready…", false);
        synth.submit(() -> {
            try {
                if (!Voice.packReady(this)) {
                    main.post(() -> status("Downloading the voice (one time, 354 MB — Wi-Fi is best)…", false));
                    int[] shown = {-1};
                    for (int attempt = 1; ; attempt++) {
                        try {
                            Voice.fetchPack(this, (d, t) -> {
                                int pct = (int) (100 * d / Math.max(1, t));
                                if (pct != shown[0]) { shown[0] = pct; main.post(() -> status("Downloading the voice (one time, 354 MB): " + pct + "%", false)); }
                            });
                            break;
                        } catch (java.io.IOException e) {
                            if (attempt >= 30) throw e;
                            final int wait = Math.min(60, 5 * attempt);
                            main.post(() -> status("Voice download paused (" + e.getMessage() + "). Trying again in " + wait + " s…", false));
                            Thread.sleep(wait * 1000L);
                        }
                    }
                    main.post(() -> status("Getting the voice ready…", false));
                }
                voice.load(this);
                main.post(() -> { voiceLoading = false; status("", false); state(); });
            } catch (Throwable t) {
                Log.e(TAG, "voice load failed", t);
                main.post(() -> { voiceLoading = false; status("The voice couldn't start: " + t.getMessage(), true); state(); });
            }
            return null;
        });
    }
    boolean voiceReady() { return voice.ready(); }
    int threads() { return voice.threads; }

    /** Seconds of speech made per second of work, or 0 if not measured yet. */
    double realtime() { return workSecs > 0.5 ? audioSecs / workSecs : 0; }

    // ---------------------------------------------------------------- settings
    float speed() { return speed; }
    int voiceId() { return sid; }
    void setSpeed(float s) {
        speed = s;
        prefs.edit().putFloat("speed", s).apply();
        AudioTrack t = track;
        Voice.Clip c = current;
        if (t != null && c != null) applyRate(t, c);
    }
    void setVoice(int id) {
        if (id == sid) return;
        sid = id;
        prefs.edit().putInt("voice", id).apply();
        synchronized (clips) { for (Future<Voice.Clip> f : clips.values()) f.cancel(false); clips.clear(); clipsGen++; }
        if (playing) seek(pos);                       // say the current sentence again in the new voice
    }
    void setAutoNext(boolean on) { autoNext = on; prefs.edit().putBoolean("autoNext", on).apply(); }
    /** minutes > 0: pause after that long; 0: off. */
    void setSleep(int minutes, boolean atChapterEnd) {
        sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        stopAtChapterEnd = atChapterEnd;
    }
    long sleepAt() { return sleepAt; }

    /** A short sample in the chosen voice. */
    void preview() {
        if (!voice.ready() || playing) return;
        final int myGen = ++gen;
        synth.submit(() -> {
            Voice.Clip c = voice.speak("Hello. This is how I sound when I read your chapters to you.", sid, speed);
            new Thread(() -> { AudioTrack t = ensureTrack(c.rate); t.play(); writeClip(t, c, myGen); }).start();
            return null;
        });
    }

    // ---------------------------------------------------------------- chapter + position
    Chapter chapter() { return chapter; }
    int pos() { return pos; }
    boolean isPlaying() { return playing; }

    /** A chapter arrived from the page. play: keep reading (after a page turn, or the user pressed play). */
    void setChapter(Chapter ch, int startAt, boolean play) {
        stopPlayback();
        chapter = ch;
        pos = Math.max(0, Math.min(startAt, ch.size() - 1));
        synchronized (clips) { for (Future<Voice.Clip> f : clips.values()) f.cancel(false); clips.clear(); clipsGen++; }
        updateMetadata();
        if (listener != null) listener.onSpeaking(chapter, pos);
        if (play) play(); else state();
    }

    void seek(int i) {
        if (chapter == null) return;
        boolean was = playing;
        stopPlayback();
        pos = Math.max(0, Math.min(i, chapter.size() - 1));
        saveProgress();
        if (listener != null) listener.onSpeaking(chapter, pos);
        if (was) play(); else state();
    }

    // ---------------------------------------------------------------- playing
    void play() {
        if (chapter == null) { status("Open a chapter first.", false); return; }
        if (playing && paused) { paused = false; if (track != null) track.play(); state(); return; }
        if (playing) return;
        if (!requestFocus()) { status("Another app is using the audio.", true); return; }
        playing = true; paused = false;
        goForeground();
        if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
        final int myGen = ++gen;
        player = new Thread(() -> loop(myGen), "loudbook-player");
        player.start();
        state();
    }

    void pause() {
        if (!playing) return;
        paused = true;
        if (track != null) track.pause();
        state();
        saveProgress();
    }

    boolean isPaused() { return paused; }

    private void stopPlayback() {
        gen++;
        playing = false; paused = false;
        AudioTrack t = track;
        if (t != null) { try { t.pause(); t.flush(); } catch (Exception ignored) {} framesWritten = 0; }
        Thread p = player;
        if (p != null) p.interrupt();
        player = null;
        if (wake.isHeld()) wake.release();
        state();
    }

    private Future<Voice.Clip> clipFor(int i) {
        synchronized (clips) {
            Future<Voice.Clip> f = clips.get(i);
            if (f != null) return f;
            final Chapter ch = chapter;
            final int want = sid; final float sp = speed;
            if (ch == null || i < 0 || i >= ch.size()) return null;
            final String text = ch.chunks.get(i).say;
            f = synth.submit(() -> {
                while (!voice.ready()) Thread.sleep(100);            // first run: model still loading
                Voice.Clip c = voice.speak(text, want, sp);
                audioSecs += c.seconds(); workSecs += c.ms / 1000.0;
                return c;
            });
            clips.put(i, f);
            return f;
        }
    }

    private volatile Voice.Clip current;

    /**
     * The player thread. Sentences are streamed into the AudioTrack back to back, with no waiting
     * for one to finish before the next goes in: that is what keeps paragraphs flowing without a
     * gap. Which sentence is being heard is worked out from the track's playback position.
     */
    private void loop(int myGen) {
        java.util.ArrayDeque<long[]> marks = new java.util.ArrayDeque<>();   // {first frame, sentence}
        try {
            final Chapter ch = chapter;
            if (ch == null) return;
            int next = pos;
            while (gen == myGen) {
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                    sleepAt = 0;
                    main.post(() -> { pause(); status("Sleep timer: paused.", false); });
                    return;
                }
                if (chapter != ch) return;
                if (next >= ch.size()) {
                    if (!drain(myGen, ch, marks)) return;          // let the last words finish
                    pos = ch.size();
                    endOfChapter(myGen, ch);
                    return;
                }
                if (next == pos && !voice.ready()) main.post(() -> status("Getting the voice ready…", false));
                if (marks.isEmpty()) { final int here = next; main.post(() -> { if (listener != null && gen == myGen) listener.onSpeaking(ch, here); }); }
                // this sentence first, then the ones after it; forget what's been played. (This used
                // to drop the current sentence while reading ahead, so each one was made twice and
                // waited behind the read-ahead: the pause at every sentence.)
                Future<Voice.Clip> f = clipFor(next);
                for (int k = 1; k <= READ_AHEAD; k++) clipFor(next + k);
                final int keepFrom = next - 1;
                synchronized (clips) { clips.keySet().removeIf(k -> k < keepFrom); }
                if (f == null) return;
                Voice.Clip c = null;
                while (gen == myGen) {
                    try { c = f.get(20, java.util.concurrent.TimeUnit.MILLISECONDS); break; }
                    catch (java.util.concurrent.TimeoutException te) { heard(ch, marks, myGen); }
                    catch (Exception e) { if (gen != myGen) return; Log.w(TAG, "sentence failed", e); c = null; break; }
                }
                if (gen != myGen) return;
                if (c != null) {
                    if (next == pos) main.post(() -> status("", false));
                    AudioTrack t = ensureTrack(c.rate);
                    current = c;
                    marks.add(new long[]{framesWritten, next});
                    if (!stream(t, c, myGen, ch, marks)) return;
                }
                next++;
            }
        } catch (Throwable t) {
            Log.e(TAG, "player", t);
            main.post(() -> status("Playback stopped: " + t.getMessage(), true));
        }
    }

    /** Queues one sentence's audio behind whatever is already playing. false = interrupted. */
    private boolean stream(AudioTrack t, Voice.Clip c, int myGen, Chapter ch, java.util.ArrayDeque<long[]> marks) {
        applyRate(t, c);
        float[] s = c.samples;
        int off = 0;
        while (off < s.length) {
            if (gen != myGen) return false;
            if (paused) { sleep(40); continue; }
            if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
            int n = t.write(s, off, Math.min(2048, s.length - off), AudioTrack.WRITE_BLOCKING);
            if (n < 0) return false;
            off += n;
            framesWritten += n;
            heard(ch, marks, myGen);
        }
        return gen == myGen;
    }

    /** Moves the highlight (and saved place) to the sentence now coming out of the speaker. */
    private void heard(Chapter ch, java.util.ArrayDeque<long[]> marks, int myGen) {
        AudioTrack t = track;
        if (t == null || marks.isEmpty()) return;
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        int reached = -1;
        while (!marks.isEmpty() && head >= marks.peek()[0]) reached = (int) marks.poll()[1];
        if (reached < 0 || gen != myGen) return;
        final int here = reached;
        pos = here;
        main.post(() -> { if (listener != null && gen == myGen) listener.onSpeaking(ch, here); });
        if (here % 3 == 0) saveProgress();
    }

    /** Waits for everything queued to be heard. false = interrupted. */
    private boolean drain(int myGen, Chapter ch, java.util.ArrayDeque<long[]> marks) {
        AudioTrack t = track;
        if (t == null) return gen == myGen;
        long lastHead = -1, stuckSince = System.currentTimeMillis();
        while (gen == myGen) {
            if (paused) { sleep(40); stuckSince = System.currentTimeMillis(); continue; }
            heard(ch, marks, myGen);
            long head = t.getPlaybackHeadPosition() & 0xffffffffL;
            if (head >= framesWritten - 32) return true;
            if (head != lastHead) { lastHead = head; stuckSince = System.currentTimeMillis(); }
            else if (System.currentTimeMillis() - stuckSince > 1500) return true;   // the track stalled: don't hang
            sleep(15);
        }
        return false;
    }

    private void endOfChapter(int myGen, Chapter ch) {
        saveProgress();
        boolean go = autoNext && ch.nextUrl != null;
        main.post(() -> {
            if (gen != myGen) return;
            if (go) {
                if (stopAtChapterEnd) { stopAtChapterEnd = false; pendingPauseAfterTurn = true; }
                status("Turning the page…", false);
                if (listener != null) listener.onNeedChapter(ch.nextUrl);
                else { status("Open Loudbook to carry on to the next chapter.", false); stopPlayback(); }
            } else {
                stopPlayback();
                dropForeground(false);
                status(ch.nextUrl == null ? "That's the latest chapter — you're caught up." : "End of chapter.", false);
                if (listener != null) listener.onFinished(ch);
            }
        });
    }
    boolean pendingPauseAfterTurn = false;

    private AudioTrack ensureTrack(int rate) {
        if (track != null && track.getSampleRate() == rate) return track;
        if (track != null) track.release();
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(min * 4, rate * 2))   // ~0.5 s of floats
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        if (Build.VERSION.SDK_INT >= 31) {
            // after running dry (a slow sentence), start again as soon as a little audio is in
            try { track.setStartThresholdInFrames(rate / 20); } catch (Exception e) { Log.w(TAG, "threshold", e); }
        }
        framesWritten = 0;
        return track;
    }

    /** Speed changes apply to what's playing now: faster/slower with the pitch kept. */
    private void applyRate(AudioTrack t, Voice.Clip c) {
        try {
            float r = Math.max(0.5f, Math.min(2.5f, speed / c.madeAt));
            t.setPlaybackParams(new PlaybackParams().setSpeed(r).setPitch(1f));
        } catch (Exception e) { Log.w(TAG, "rate", e); }
    }

    /** Writes one clip and waits until it has been heard (the voice preview). false = interrupted. */
    private boolean writeClip(AudioTrack t, Voice.Clip c, int myGen) {
        applyRate(t, c);
        float[] s = c.samples;
        int off = 0;
        while (off < s.length) {
            if (gen != myGen) return false;
            if (paused) { sleep(40); continue; }
            int n = t.write(s, off, Math.min(4096, s.length - off), AudioTrack.WRITE_BLOCKING);
            if (n < 0) return false;
            off += n;
        }
        framesWritten += s.length;
        while (gen == myGen) {
            if (paused) { sleep(40); continue; }
            long head = t.getPlaybackHeadPosition() & 0xffffffffL;
            if (head >= framesWritten - 32) return true;
            sleep(15);
        }
        return false;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ---------------------------------------------------------------- audio focus, notification
    private boolean requestFocus() {
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) main.post(this::pause);
            }).build();
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, ReaderService.class).setAction(act);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean on = playing && !paused;
        Notification.Builder b = new Notification.Builder(this, "reading")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(chapter != null ? chapter.title : "Loudbook")
            .setContentText(chapter != null ? chapter.fiction : "")
            .setContentIntent(content)
            .setOngoing(on)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_back), "Back", action(ACT_BACK, 1)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, on ? R.drawable.ic_pause : R.drawable.ic_play), on ? "Pause" : "Play", action(ACT_TOGGLE, 2)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_fwd), "Forward", action(ACT_FWD, 3)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (!foreground) {
            try { startService(new Intent(this, ReaderService.class)); } catch (Exception ignored) { }
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } else getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }
    private void dropForeground(boolean remove) {
        if (!foreground) return;
        stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        foreground = false;
    }

    private void updateMetadata() {
        if (chapter == null) return;
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, chapter.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter.fiction)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Loudbook").build());
    }

    private void state() {
        boolean on = playing && !paused;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
            .setState(on ? PlaybackState.STATE_PLAYING : playing ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed).build());
        // stays in the foreground while paused and across page turns (a background app may not
        // re-enter it); it lets go only when the story is finished or the reader is closed
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        final boolean busy = voiceLoading;
        main.post(() -> { if (listener != null) listener.onState(on, busy); });
    }

    private void status(String s, boolean err) {
        main.post(() -> { if (listener != null) listener.onStatus(s, err); });
    }

    // ---------------------------------------------------------------- progress
    void saveProgress() {
        Chapter ch = chapter;
        if (ch == null) return;
        prefs.edit().putInt("pos:" + ch.url, pos)
            .putString("lastUrl", ch.url).putString("lastTitle", ch.title).putString("lastFiction", ch.fiction)
            .putInt("lastPct", ch.size() > 0 ? Math.round(100f * pos / ch.size()) : 0).apply();
    }
    int savedPos(String url) { return prefs.getInt("pos:" + url, 0); }

    static void start(Context c) { c.startService(new Intent(c, ReaderService.class)); }
}
