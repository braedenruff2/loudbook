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

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plays a chapter, sentence by sentence, in the background: makes each sentence a few sentences
 * ahead (Kokoro on the phone, the PC, or Gemini), plays it, and goes on to the next chapter by
 * itself (loaded ahead in a browser with no screen, see ChapterFetcher). Runs as a foreground
 * service with a media notification, so it keeps going with the screen off and the lock-screen,
 * headset and "Hey Google" controls work.
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
        /** Gemini: please load this chapter's text (quietly) so it can be recorded ahead of time. */
        default void onPrefetch(String url) { }
        /** The service went on to the next chapter by itself (it loads it ahead). */
        default void onTurned(Chapter from, Chapter to) { }
        /** Reading picked up the last story by itself (play with nothing open: the headphones, "Hey Google"). */
        default void onOpened(Chapter ch) { }
    }

    final class LocalBinder extends Binder { ReaderService service() { return ReaderService.this; } }
    private final IBinder binder = new LocalBinder();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Voice voice = new Voice();
    /** Loads the voice (its own thread: speech-making waits for it on the synth thread). */
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
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
    private boolean pausedForFocus = false;
    private boolean foreground = false;

    // ---------------------------------------------------------------- lifecycle
    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        speed = prefs.getFloat("speed", 1f);
        pauseScale = prefs.getFloat("pauseScale", 1f);
        cloudRetryAt = prefs.getLong("gemRetryAt", 0);
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
            // "Hey Google, rewind 30 seconds" / "start over": a time in the chapter, to its sentence
            @Override public void onSeekTo(long ms) { Chapter c = chapter; if (c != null) seek(sentenceAt(c, ms)); }
            // "Hey Google, play Loudbook" / "resume"
            @Override public void onPlayFromSearch(String query, android.os.Bundle extras) { play(); }
            @Override public void onPlayFromMediaId(String id, android.os.Bundle extras) { play(); }
        });
        session.setActive(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("reading", "Reading aloud", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        voice.attach(this);
        warmUp();
        // headphones unplugged or Bluetooth dropped: pause instead of carrying on out loud
        android.content.IntentFilter noisy = new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, noisy, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, noisy);
    }

    private final android.content.BroadcastReceiver noisyReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (playing && !paused) { pause(); status("Paused: headphones disconnected.", false); }
        }
    };

    // ---------------------------------------------------------------- PC voice
    Voice voice() { return voice; }
    /** Settings changed (paired, turned on or off): try the PC again straight away. */
    void pcChanged() {
        voice.pcOk = false; voice.pcRetryAt = 0;
        loader.submit(() -> { voice.checkPc(); main.post(this::state); return null; });
        if (voice.pcLink() == null && !voice.localReady()) warmUp();
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
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        commands.stop();
        cmdThread.shutdownNow();
        loader.shutdownNow();
        cloudPool.shutdownNow();
        kindleThread.shutdownNow();
        asrThread.shutdownNow();
        if (fetcher != null) fetcher.destroy();
        if (sayer != null) sayer.destroy();
        waiters.shutdownNow();
        if (beeper != null) beeper.release();
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
        if (voice.localReady() || voiceLoading) return;
        voiceLoading = true;
        status("Getting the voice ready…", false);
        loader.submit(() -> {
            try {
                // a paired PC on the network: no need to load the voice on the phone at all
                if (voice.pcLink() != null && voice.checkPc()) {
                    main.post(() -> { voiceLoading = false; status("", false); state(); });
                    return null;
                }
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
        dropClips();
        if (playing) seek(pos);                       // say the current sentence again in the new voice
    }
    /** Something about the voice changed (a recorded voice was chosen): make what's ahead again. */
    void restyle() {
        dropClips();
        if (playing) seek(pos);
    }
        void setAutoNext(boolean on) { autoNext = on; prefs.edit().putBoolean("autoNext", on).apply(); }
    /** minutes > 0: pause after that long; 0: off. */
    void setSleep(int minutes, boolean atChapterEnd) {
        sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        if (track != null) { try { track.setVolume(1f); } catch (Exception ignored) { } }
        stopAtChapterEnd = atChapterEnd;
    }
    long sleepAt() { return sleepAt; }

    /** A short sample in the chosen voice. */
    void preview() {
        if ((!voice.ready() && !cloudOn()) || playing) return;
        final int myGen = ++gen;
        synth.submit(() -> {
            String hello = "Hello. This is how I sound when I read your chapters to you.";
            Voice.Clip c;
            if (cloudOn()) {
                try {
                    countRequest();
                    CloudVoice.Audio a = CloudVoice.speak(prefs.getString("cloudKey", ""), prefs.getString("cloudModel", "gemini-3.8-flash-tts"),
                        prefs.getString("cloudVoice", "Sulafat"), prefs.getString("cloudStyle", CloudVoice.DEFAULT_STYLE), hello, 60_000);
                    c = new Voice.Clip(Voice.trim(a.samples, a.rate), a.rate, 1f, 0);
                    cloudError = "";
                } catch (Exception e) { cloudError = String.valueOf(e.getMessage()); main.post(() -> status("Gemini: " + cloudError, true)); return null; }
            } else c = voice.speak(hello, sid, speed);
            final Voice.Clip clip = c;
            new Thread(() -> { AudioTrack t = ensureTrack(clip.rate); t.play(); writeClip(t, clip, myGen); }).start();
            return null;
        });
    }

    /** Says exactly this, now (trying out how a word should sound). */
    void sampleRaw(String text) {
        if (!voice.ready() && voice.pcLink() == null) return;
        if (playing) stopPlayback();             // (play carries on from the same sentence afterwards)
        final int myGen = ++gen;
        synth.submit(() -> {
            Voice.Clip c = voice.speak(text, sid, speed);
            new Thread(() -> { AudioTrack t = ensureTrack(c.rate); t.play(); writeClip(t, c, myGen); }).start();
            return null;
        });
    }

    /** The pronunciation fixes changed: what the voice says is worked out again, and the rest of the chapter re-made. */
    void refreshSay() {
        final Chapter ch = chapter;
        if (ch == null) return;
        // Gemini's recording of this chapter is matched to its words: changing them would cost
        // another request, so with Gemini the fix starts with the next chapter
        if (cloudOn() && !"kindle".equals(ch.site)) { status("Saved. With Gemini it applies from the next chapter.", false); return; }
        kindleThread.submit(() -> {
            java.util.List<Chapter.Chunk> now = new java.util.ArrayList<>(ch.chunks);
            java.util.List<Chapter.Chunk> again = spokenKeepAll(now);
            if (again == null) return null;
            main.post(() -> {
                if (chapter != ch) return;
                for (int i = 0; i < again.size() && i < ch.chunks.size(); i++) ch.chunks.set(i, again.get(i));
                boolean was = playing && !paused;
                dropClips();
                if (was) seek(pos);
                status("Pronunciation updated.", false);
            });
            return null;
        });
    }

    /** Like spoken(), but one for one (nothing dropped), or null if it couldn't. */
    private java.util.List<Chapter.Chunk> spokenKeepAll(java.util.List<Chapter.Chunk> in) {
        if (sayer == null) sayer = new Sayer(this);
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Chapter.Chunk c : in) texts.add(c.text);
        java.util.List<String> said = sayer.say(texts, prefs.getString("fixes", ""), prefs.getBoolean("tidy", true));
        if (said == texts) return null;
        java.util.List<Chapter.Chunk> out = new java.util.ArrayList<>();
        for (int i = 0; i < in.size(); i++) out.add(new Chapter.Chunk(in.get(i).block, in.get(i).text, said.get(i).trim().isEmpty() ? in.get(i).say : said.get(i)));
        return out;
    }

    // ---------------------------------------------------------------- chapter + position
    Chapter chapter() { return chapter; }
    int pos() { return pos; }
    boolean isPlaying() { return playing; }

    /** A chapter arrived from the page. play: keep reading (after a page turn, or the user pressed play). */
    void setChapter(Chapter ch, int startAt, boolean play) { setChapter(ch, startAt, play, null); }

    /** premade: sentences of this chapter already made (the start of the next chapter, made ahead). */
    private void setChapter(Chapter ch, int startAt, boolean play, Map<Integer, Future<Voice.Clip>> premade) {
        stopPlayback();
        chapter = ch;
        pos = Math.max(0, Math.min(startAt, ch.size() - 1));
        dropClips();
        if (premade != null && startAt == 0) synchronized (clips) { clips.putAll(premade); }
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
        if (chapter == null) { resumeLast(); return; }
        if (track != null) { try { track.setVolume(1f); } catch (Exception ignored) { } }       // (after a sleep-timer fade)
        if (playing && paused) { paused = false; if (track != null) track.play(); state(); return; }
        if (playing) return;
        playing = true; paused = false;
        // the notification first: Android 15 only gives the audio to an app on screen or one with
        // a reading notification up (Kindle's button starts reading while Kindle is on screen)
        goForeground();
        if (!requestFocus()) {
            int mode = audio.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
                playing = false;
                status("A call is using the audio.", true);
                state();
                return;
            }
            Log.w(TAG, "audio focus refused; reading anyway");
            Log.i("LoudbookTest", "audio focus refused; reading anyway");
        }
        preloadNext(chapter);
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

    /** Forgets every sentence made or being made (new chapter, voice or engine). */
    private void dropClips() {
        synchronized (clips) {
            for (Future<Voice.Clip> f : clips.values()) f.cancel(false);
            clips.clear(); clipsGen++;
            book = null;
        }
    }

    private Future<Voice.Clip> clipFor(int i) {
        synchronized (clips) {
            Future<Voice.Clip> f = clips.get(i);
            if (f != null) return f;
            final Chapter ch = chapter;
            final int want = sid; final float sp = speed;
            if (ch == null || i < 0 || i >= ch.size()) return null;
            final String text = ch.chunks.get(i).say;
            if (cloudOn() && !"kindle".equals(ch.site) && !"text".equals(ch.site)) {
                // Gemini: the chapter is recorded in one request; each sentence is cut from it
                final int idx = i;
                f = waiters.submit(() -> {
                    try {
                        for (int tries = 0; tries < 4; tries++) {
                            GeminiBook b = bookFor(ch);
                            if (b.has(idx)) return learned(b.clip(idx), want);
                            // before the part Gemini recorded (you started further in, then went
                            // back): the other voice reads it, so it doesn't use up a request,
                            // unless the setting says to record it too
                            int first = b.firstHave(ch.size());
                            if (first > idx && !prefs.getBoolean("gemBackfill", false)) {
                                if (idx == pos) main.post(() -> status("Gemini didn't record this part (you started further on). The regular voice reads it.", false));
                                return learned(makeHere(text, want, sp), want);
                            }
                            recording(ch, b, idx).get();          // the request that covers it
                        }
                        throw new IOException("Gemini didn't record this sentence");
                    } catch (java.util.concurrent.ExecutionException | IOException e) {
                        return learned(makeHere(text, want, sp), want);    // Google failed: another voice reads it
                    }
                });
                clips.put(i, f);
                return f;
            }
            f = synth.submit(() -> learned(makeHere(text, want, sp), want));
            clips.put(i, f);
            return f;
        }
    }

    private Voice.Clip learned(Voice.Clip c, int want) {
        if (commands.loaded()) cmdThread.submit(() -> commands.learnReader(want, c.samples, c.rate));
        return c;
    }

    /** A sentence from the PC or the phone (not Gemini). */
    private Voice.Clip makeHere(String text, int want, float sp) throws Exception {
        {
            {
                Voice.Clip c = null;
                for (int attempt = 0; c == null; attempt++) {
                    while (!voice.ready()) Thread.sleep(100);        // first run: model still loading
                    try { c = voice.speak(text, want, sp); }
                    catch (Voice.NeedLocal e) {                       // the PC dropped out: switch to the phone
                        if (attempt >= 2) throw e;
                        main.post(() -> { status("Can't reach your PC. Using the phone's voice…", false); warmUp(); });
                        Thread.sleep(300);
                    }
                }
                audioSecs += c.seconds(); workSecs += c.ms / 1000.0;
                return c;
            }
        }
    }

    // ---------------------------------------------------------------- Kindle
    private volatile KindleText kt;
    private volatile String kindleSig = "";
    private volatile boolean kindleBusy, kindleEnded;
    private final ExecutorService kindleThread = Executors.newSingleThreadExecutor();

    boolean readingKindle() { Chapter c = chapter; return c != null && "kindle".equals(c.site); }
    /** Kindle was read to the end of the book (or of what could be turned to). */
    boolean kindleDone() { return readingKindle() && kindleEnded && !playing; }

    /** Starts reading the page open in Kindle (from KindleService's button). */
    void startKindle(KindleService.Page p) {
        kt = new KindleText(prefs.getString("fixes", ""));
        java.util.List<Chapter.Chunk> raw = kt.chunks(p.paragraphs, false);
        if (raw.isEmpty()) raw = new KindleText(prefs.getString("fixes", "")).chunks(p.paragraphs, true);
        kindleSig = p.signature;
        kindleEnded = false; kindleBusy = false;
        final java.util.List<Chapter.Chunk> page = raw;
        kindleThread.submit(() -> {
            java.util.List<Chapter.Chunk> first = spoken(page);
            main.post(() -> {
                Chapter ch = new Chapter("kindle:" + System.currentTimeMillis(), "kindle", p.title.isEmpty() ? "Kindle" : p.title, "Kindle", first);
                Log.i("LoudbookTest", "kindle start: " + p.paragraphs.size() + " paragraphs, " + first.size() + " sentences, title " + p.title);
                setChapter(ch, 0, true);
            });
            return null;
        });
    }

    /** Reads text shared from another app (not a web page): split into paragraphs and sentences like a Kindle page. */
    void readText(String title, String text) {
        java.util.List<String> paras = new java.util.ArrayList<>();
        for (String p : text.replace("\r", "").split("\n\\s*\n|\n")) if (!p.trim().isEmpty()) paras.add(p.trim());
        if (paras.isEmpty()) return;
        kindleThread.submit(() -> {
            java.util.List<Chapter.Chunk> raw = new KindleText(prefs.getString("fixes", "")).chunks(paras, true);
            java.util.List<Chapter.Chunk> said = spoken(raw);
            main.post(() -> {
                Chapter ch = new Chapter("text:" + System.currentTimeMillis(), "text", title, "Shared text", said);
                setChapter(ch, 0, true);
                if (listener != null) listener.onSpeaking(ch, 0);
            });
            return null;
        });
    }

    private Sayer sayer;
    /** Kindle sentences as the voice should get them: the same clean-ups as web pages (numbers, "Mr.", Roman numerals...). */
    private java.util.List<Chapter.Chunk> spoken(java.util.List<Chapter.Chunk> in) {
        if (sayer == null) sayer = new Sayer(this);
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Chapter.Chunk c : in) texts.add(c.text);
        java.util.List<String> said = sayer.say(texts, prefs.getString("fixes", ""), prefs.getBoolean("tidy", true));
        if (said == texts) return in;                                  // (it couldn't: keep the plain text)
        java.util.List<Chapter.Chunk> out = new java.util.ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            String s = said.get(i);
            if (s.codePoints().anyMatch(Character::isLetterOrDigit)) out.add(new Chapter.Chunk(in.get(i).block, in.get(i).text, s));
        }
        return out;
    }

    /** Turns Kindle's page and adds its sentences to what's being read. */
    private void kindleMore(Chapter ch) {
        if (kindleBusy || kindleEnded || kt == null) return;
        kindleBusy = true;
        kindleThread.submit(() -> {
            try {
                KindleService k = KindleService.me;
                KindleService.Page p = k == null ? new KindleService.Page() : k.nextPage(kindleSig);
                if (p.paragraphs.isEmpty()) {
                    kindleEnded = true;
                    java.util.List<Chapter.Chunk> rest = spoken(kt.chunks(new java.util.ArrayList<>(), true));   // whatever was held back
                    if (!rest.isEmpty()) ch.chunks.addAll(rest);
                    if (k == null || !k.kindleVisible()) main.post(() -> status("Kindle isn't on screen, so the page can't be turned. Open it to carry on.", false));
                } else {
                    kindleSig = p.signature;
                    java.util.List<Chapter.Chunk> more = spoken(kt.chunks(p.paragraphs, false));
                    ch.chunks.addAll(more);
                    Log.i("LoudbookTest", "kindle page turned: +" + more.size() + " sentences");
                }
            } catch (Throwable t) { Log.w(TAG, "kindle page", t); kindleEnded = true; }
            finally { kindleBusy = false; }
            return null;
        });
    }

    // ---------------------------------------------------------------- Gemini (online voice)
    private final ExecutorService cloudPool = Executors.newSingleThreadExecutor();   // one request at a time
    private final ExecutorService waiters = Executors.newCachedThreadPool();
    private volatile GeminiBook book;               // the playing chapter's recording
    private String bookKey = "";
    private Future<?> pending;                      // the request in progress
    private int pendingFrom = -1, pendingTo = -1;
    private String pendingKey = "";
    volatile String cloudError = "";
    volatile long cloudRetryAt = 0;
    volatile String cloudStatus = "";

    boolean cloudOn() {
        return prefs.getBoolean("cloudOn", false) && !prefs.getString("cloudKey", "").trim().isEmpty()
            && System.currentTimeMillis() >= cloudRetryAt;
    }
    void cloudChanged() { cloudRetryAt = 0; cloudError = ""; dropClips(); if (playing && !paused) seek(pos); }
    private String cVoice() { return prefs.getString("cloudVoice", "Sulafat"); }
    private String cModel() { return prefs.getString("cloudModel", "gemini-3.8-flash-tts"); }
    private String cStyle() { return prefs.getString("cloudStyle", CloudVoice.DEFAULT_STYLE); }
    private File cloudRoot() { return new File(getCacheDir(), "gemini"); }

    private synchronized GeminiBook bookFor(Chapter ch) {
        String k = GeminiBook.key(ch.url, cVoice(), cModel(), cStyle());
        if (book == null || !k.equals(bookKey)) { book = GeminiBook.open(cloudRoot(), k); bookKey = k; }
        book.bind(ch);
        return book;
    }

    /** Gemini requests made today (the free tier allows only a few). */
    int cloudRequestsToday() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        return today.equals(prefs.getString("gemDay", "")) ? prefs.getInt("gemCount", 0) : 0;
    }
    private void countRequest() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        prefs.edit().putString("gemDay", today).putInt("gemCount", cloudRequestsToday() + 1).apply();
    }

    /**
     * The request that will record sentence i: the one already on its way if it covers i, or a new
     * one from i to the end of the chapter (or up to what's already recorded).
     */
    private synchronized Future<?> recording(Chapter ch, GeminiBook b, int i) {
        String k = bookKey;
        if (pending != null && !pending.isDone() && k.equals(pendingKey) && i >= pendingFrom && i < pendingTo) return pending;
        if (pending != null && !pending.isDone() && k.equals(pendingKey) && i > pendingFrom) return pending;   // wait: it may run on far enough
        final int from = i, to = b.nextHave(i, ch.size());
        pendingFrom = from; pendingTo = to; pendingKey = k;
        pending = cloudPool.submit(() -> { record(ch, b, from, to, true); return null; });
        return pending;
    }

    /** One request: sentences [from, to). If Google stops early, the rest follows straight away. */
    private void record(Chapter ch, GeminiBook b, int from, int to, boolean follow) throws Exception {
        if (b.has(from)) return;
        cloudStatus = "Gemini is recording " + (from == 0 ? "the chapter" : "the rest of the chapter") + " (one request)\u2026";
        main.post(() -> status(cloudStatus, false));
        long t0 = System.currentTimeMillis();
        int got;
        GeminiBook.Made made;
        try {
            countRequest();
            double[] pace = new double[1];
            made = b.fetch(ch, from, to, prefs.getString("cloudKey", ""), cModel(), cVoice(), cStyle(),
                prefs.getFloat("gemPace:" + cVoice(), 0f), pace);
            got = made.count;
            if (pace[0] > 0) prefs.edit().putFloat("gemPace:" + cVoice(), (float) pace[0]).apply();
            cloudError = "";
        } catch (CloudVoice.Limited ex) {
            // Google says when requests are available again: wait exactly that long
            cloudRetryAt = System.currentTimeMillis() + ex.retryMs;
            prefs.edit().putLong("gemRetryAt", cloudRetryAt).apply();
            cloudError = ex.getMessage();
            String when = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(cloudRetryAt));
            main.post(() -> status("Gemini's free requests are used up until " + when + ". The regular voice reads until then.", false));
            throw ex;
        } catch (Exception ex) {
            cloudError = String.valueOf(ex.getMessage());
            cloudRetryAt = System.currentTimeMillis() + 60_000;
            main.post(() -> status("Gemini: " + cloudError + ". Another voice reads for now.", false));
            throw ex;
        }
        refineLater(ch, b, made);
        workSecs += (System.currentTimeMillis() - t0) / 1000.0;
        GeminiBook.trim(cloudRoot(), 800L << 20);
        main.post(() -> status("", false));
        // Google stopped early: ask for the rest now, so it's ready when it's needed
        if (follow && from + got < to && chapter == ch) {
            final int next = from + got;
            synchronized (this) { pendingFrom = next; pendingTo = to; pending = cloudPool.submit(() -> { record(ch, b, next, to, true); return null; }); }
        }
    }

    // exact sentence timing: a recogniser listens to each new recording once, on its own thread
    private final ExecutorService asrThread = Executors.newSingleThreadExecutor();
    private final AsrAligner asr = new AsrAligner();
    private void refineLater(Chapter ch, GeminiBook b, GeminiBook.Made m) {
        if (!prefs.getBoolean("gemExact", true)) return;
        asrThread.submit(() -> {
            try {
                File d = new File(getFilesDir(), "asr");
                if (!AsrAligner.downloaded(d)) AsrAligner.fetch(d, null);
                asr.load(d);
                long t0 = System.currentTimeMillis();
                b.refine(ch, m, asr);
                Log.i(TAG, "sentence timing: " + b.lastRefined + " cuts moved, " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable t) { Log.w(TAG, "sentence timing", t); }
            return null;
        });
    }

    /** Records a chapter before it's needed (the next one, near the end of this one). */
    void cloudPrefetch(Chapter ch) {
        if (!cloudOn() || ch == null || !prefs.getBoolean("gemPrefetch", false)) return;
        GeminiBook b = GeminiBook.open(cloudRoot(), GeminiBook.key(ch.url, cVoice(), cModel(), cStyle()));
        b.bind(ch);
        if (b.has(0)) return;
        cloudPool.submit(() -> { try { record(ch, b, 0, ch.size(), false); } catch (Exception ignored) { } return null; });
    }
    private String prefetchAsked = "";

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
                if (kt != null && "kindle".equals(ch.site)) {
                    // Kindle: turn the page a few sentences before the end, so the next is ready
                    if (next >= ch.size() - 2) kindleMore(ch);
                    long until = System.currentTimeMillis() + 10_000;
                    while (gen == myGen && next >= ch.size() && kindleBusy && System.currentTimeMillis() < until) { heard(ch, marks, myGen); sleep(60); }
                    if (gen != myGen) return;
                }
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
                    if (!rest(t, gapAfter(ch, next), myGen, ch, marks)) return;
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

    /**
     * The pause after a piece of text, like a reader would leave: a full stop gets a proper
     * beat, a new paragraph a little more, a comma-split piece only a breath. Scaled by the
     * "pause between sentences" setting and shortened at higher speeds.
     */
    private double gapAfter(Chapter ch, int i) {
        if (i + 1 >= ch.size()) return 0;
        GeminiBook b = book;
        if (b != null && b.joined(i) && prefs.getBoolean("cloudOn", false)) return 0;   // Gemini's own pause is in the recording
        Chapter.Chunk now = ch.chunks.get(i), nx = ch.chunks.get(i + 1);
        String t = now.text.trim().replaceAll("[\"'\u201d\u2019)\\]*]+$", "");
        double g;
        if (now.block == -1) g = 0.75;                                  // after the chapter title
        else if (nx.block != now.block) g = 0.6;                        // new paragraph
        else if (t.matches("(?s).*[.!?\u2026]$")) g = 0.38;            // end of a sentence
        else if (t.matches("(?s).*[,;:\u2014\u2013-]$")) g = 0.16;    // a long sentence split at a comma
        else g = 0.1;
        return g * pauseScale / Math.max(0.6, speed);
    }
    float pauseScale = 1f;
    void setPauseScale(float f) { pauseScale = f; prefs.edit().putFloat("pauseScale", f).apply(); }

    /** Queues silence (a pause between sentences) behind what's playing. false = interrupted. */
    private boolean rest(AudioTrack t, double secs, int myGen, Chapter ch, java.util.ArrayDeque<long[]> marks) {
        int frames = (int) (secs * t.getSampleRate());
        if (frames <= 0) return gen == myGen;
        float[] z = new float[Math.min(frames, 2048)];
        while (frames > 0) {
            if (gen != myGen) return false;
            if (paused) { sleep(40); continue; }
            int n = t.write(z, 0, Math.min(z.length, frames), AudioTrack.WRITE_BLOCKING);
            if (n < 0) return false;
            frames -= n; framesWritten += n;
            heard(ch, marks, myGen);
        }
        return gen == myGen;
    }

    /** Moves the highlight (and saved place) to the sentence now coming out of the speaker. */
    private void heard(Chapter ch, java.util.ArrayDeque<long[]> marks, int myGen) {
        AudioTrack t = track;
        if (t != null && sleepAt > 0) {
            // sleep timer: fade out gently over the last fifteen seconds, rather than stop dead
            long left = sleepAt - System.currentTimeMillis();
            if (left < 15_000) { try { t.setVolume(Math.max(0.05f, left / 15_000f)); } catch (Exception ignored) { } }
        }
        if (t == null || marks.isEmpty()) return;
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        int reached = -1;
        while (!marks.isEmpty() && head >= marks.peek()[0]) reached = (int) marks.poll()[1];
        if (reached < 0 || gen != myGen) return;
        final int here = reached;
        pos = here;
        { Chapter.Chunk cc = ch.chunks.get(here); String said = cc.text;
          Log.i("LoudbookTest", ("kindle".equals(ch.site) ? "kindle " : "") + "speaking " + here + ": " + (said.length() > 60 ? said.substring(0, 60) : said)
              + (cc.say.equals(cc.text) ? "" : "  [said: " + (cc.say.length() > 70 ? cc.say.substring(0, 70) : cc.say) + "]")); }
        if (here > ch.size() / 2 && ch.nextUrl != null && autoNext && !ch.nextUrl.equals(prefetchAsked) && cloudOn()
            && prefs.getBoolean("gemPrefetch", false)) {
            prefetchAsked = ch.nextUrl;
            Chapter n = nextCh;
            if (n != null && ch.nextUrl.equals(nextFor)) main.post(() -> cloudPrefetch(n));
            else main.post(() -> { if (listener != null) listener.onPrefetch(ch.nextUrl); });
        }
        if (here >= ch.size() - 4 && ch.nextUrl != null && autoNext && nextCh != null) premakeNext();
        main.post(() -> { if (listener != null && gen == myGen) listener.onSpeaking(ch, here); if (gen == myGen) state(); });
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
                turnTo(ch, myGen);
            } else {
                stopPlayback();
                dropForeground(false);
                status(ch.nextUrl == null ? "That's the latest chapter — you're caught up. Loudbook will tell you when the next one is out." : "End of chapter.", false);
                if (ch.nextUrl == null && ch.url.startsWith("http")) { saveProgress(); NewChapters.caughtUp(this, ch); NewChapters.schedule(this); }
                if (listener != null) listener.onFinished(ch);
            }
        });
    }
    boolean pendingPauseAfterTurn = false;

    /** Play with nothing open: carry on with the story last read, where it was left. */
    private boolean resuming;
    private void resumeLast() {
        String last = prefs.getString("lastUrl", "");
        if (last.isEmpty() || !last.startsWith("http")) { status("Open a chapter first.", false); return; }
        if (resuming) return;
        resuming = true;
        status("Opening where you left off\u2026", false);
        main.post(() -> fetcher().get(last, (c, err) -> {
            resuming = false;
            if (c == null) { status("Couldn't open your last chapter (" + err + "). Open it in Loudbook.", true); return; }
            if (chapter != null) return;                                   // something else was opened meanwhile
            setChapter(c, Math.min(savedPos(last), Math.max(0, c.size() - 1)), true);
            if (listener != null) listener.onOpened(c);
        }));
    }

    // ---------------------------------------------------------------- the next chapter, ahead of time
    private ChapterFetcher fetcher;
    private volatile Chapter nextCh;                 // the chapter after the playing one, once loaded
    private volatile String nextFor;                 // the address it was loaded from
    private final Map<Integer, Future<Voice.Clip>> nextClips = new HashMap<>();   // its first sentences, made ahead
    private String nextClipsUrl;
    private int nextClipsSid = -1;
    private float nextClipsSpeed;

    private ChapterFetcher fetcher() { if (fetcher == null) fetcher = new ChapterFetcher(this); return fetcher; }

    /** Loads the next chapter's text in the background while this one plays (it costs nothing). */
    private void preloadNext(Chapter ch) {
        if (ch == null || ch.nextUrl == null || !autoNext || "kindle".equals(ch.site)) return;
        final String url = ch.nextUrl;
        if (url.equals(nextFor) && nextCh != null) return;
        main.post(() -> {
            if (fetcher().busyWith(url)) return;
            fetcher().get(url, (c, err) -> {
                if (c == null) { Log.w(TAG, "next chapter ahead: " + err); return; }
                Chapter now = chapter;
                if (now != null && url.equals(now.nextUrl)) { nextFor = url; nextCh = c; }
                aheadFrom(c, AHEAD - 1);
            });
        });
    }

    static final int AHEAD = 3;
    /** Loads a few more chapters' text after this one, one at a time, so reading goes on without a signal. */
    private void aheadFrom(Chapter c, int more) {
        if (more <= 0 || c == null || c.nextUrl == null || fetcher().ready(c.nextUrl) != null) {
            if (more > 0 && c != null && c.nextUrl != null) aheadFrom(fetcher().ready(c.nextUrl), more - 1);
            return;
        }
        final String url = c.nextUrl;
        main.postDelayed(() -> {
            Chapter now = chapter;
            if (now == null || fetcher().busyWith(url) || fetcher().busyWith(now.nextUrl)) return;
            fetcher().get(url, (n, err) -> { if (n != null) aheadFrom(n, more - 1); });
        }, 3000);
    }

    /** Near the end of a chapter: makes the next one's first sentences, so it starts straight away. */
    private void premakeNext() {
        Chapter n = nextCh;
        if (n == null || cloudOn()) return;              // (Gemini records whole chapters; see gemPrefetch)
        synchronized (clips) {
            if (n.url.equals(nextClipsUrl) && nextClipsSid == sid && nextClipsSpeed == speed) return;
            for (Future<Voice.Clip> f : nextClips.values()) f.cancel(false);
            nextClips.clear();
            nextClipsUrl = n.url; nextClipsSid = sid; nextClipsSpeed = speed;
            final int want = sid; final float sp = speed;
            for (int k = 0; k < Math.min(3, n.size()); k++) {
                final String text = n.chunks.get(k).say;
                nextClips.put(k, synth.submit(() -> learned(makeHere(text, want, sp), want)));
            }
        }
        Log.i("LoudbookTest", "making the start of " + n.url + " ahead");
    }

    /** The chapter ended: go on to the next, loaded ahead if it's ready. Main thread. */
    private void turnTo(Chapter ch, int myGen) {
        Chapter n = nextCh;
        if (n != null && ch.nextUrl.equals(nextFor)) { turned(ch, n); return; }
        fetcher().get(ch.nextUrl, (c, err) -> {
            if (gen != myGen || chapter != ch) return;
            if (c != null) { turned(ch, c); return; }
            Log.w(TAG, "next chapter: " + err);
            Log.i("LoudbookTest", "next chapter failed: " + err);
            // the screen's own browser has a go (it can show a site's check to the user)
            if (listener != null) listener.onNeedChapter(ch.nextUrl);
            else { status("Couldn't open the next chapter (" + err + "). Open Loudbook to carry on.", true); stopPlayback(); }
        });
    }

    private void turned(Chapter from, Chapter n) {
        boolean go = !pendingPauseAfterTurn && !paused;
        pendingPauseAfterTurn = false;
        Map<Integer, Future<Voice.Clip>> pre = null;
        synchronized (clips) {
            if (n.url.equals(nextClipsUrl) && nextClipsSid == sid && nextClipsSpeed == speed) pre = new HashMap<>(nextClips);
            nextClips.clear(); nextClipsUrl = null;
        }
        nextCh = null; nextFor = null;
        Log.i("LoudbookTest", "turned to " + n.url + " (" + (pre != null ? pre.size() : 0) + " sentences made ahead), play " + go);
        setChapter(n, 0, go, pre);
        if (!go) status("Stopped at the end of the chapter. The next one is ready.", false);
        if (listener != null) listener.onTurned(from, n);
    }

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
    /**
     * One focus request for the life of the service. (A new request each time playback restarted
     * made Android tell the previous one it had lost focus, which paused the new chapter right
     * after a page turn, or after skipping a sentence.)
     */
    private boolean requestFocus() {
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change -> main.post(() -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) { pausedForFocus = false; pause(); }
                else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (playing && !paused) { pause(); pausedForFocus = true; }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedForFocus) { pausedForFocus = false; play(); }
            })).build();
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
        // with voice commands on, the microphone joins the foreground service so "play" and
        // "pause" still work with the screen off. Android only allows that while the app is open.
        boolean wantMic = Build.VERSION.SDK_INT >= 30 && commandsOn() && micAllowed() && appVisible;
        if (!foreground || (wantMic && !micInForeground)) {
            try { startService(new Intent(this, ReaderService.class)); } catch (Exception ignored) { }
            try {
                if (wantMic) {
                    try {
                        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                        micInForeground = true;
                    } catch (Exception e) {
                        Log.w(TAG, "microphone in foreground refused", e);
                        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    }
                } else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTIF_ID, n);
                foreground = true;
            } catch (Exception e) {
                Log.w(TAG, "foreground refused", e);           // started from the background: keep going without it
            }
        } else getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        updateListening();
    }
    private void dropForeground(boolean remove) {
        if (!foreground) return;
        stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        foreground = false;
        micInForeground = false;
        updateListening();
    }

    // ---------------------------------------------------------------- voice commands
    final VoiceCommands commands = new VoiceCommands();
    private final java.util.concurrent.ExecutorService cmdThread = Executors.newSingleThreadExecutor();
    private boolean appVisible = false, micInForeground = false;
    String lastHeard = "";
    private android.media.ToneGenerator beeper;

    boolean commandsOn() { return prefs.getBoolean("voiceCmds", false); }
    boolean micAllowed() { return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    /** The Loudbook screen opened or closed. */
    void setAppVisible(boolean v) {
        appVisible = v;
        if (v && foreground && !micInForeground && commandsOn() && micAllowed()) goForeground();
        else updateListening();
    }

    /** Listens while Loudbook is on screen, or while it's reading (or paused) in the background. */
    void updateListening() {
        boolean want = commandsOn() && micAllowed() && VoiceCommands.downloaded(this) && VoiceCommands.profile(this) != null
            && (appVisible || (foreground && (micInForeground || Build.VERSION.SDK_INT < 30)));
        cmdThread.submit(() -> {
            try {
                if (!want) { commands.stop(); return; }
                if (commands.listening()) return;
                commands.load(this);
                commands.start(this, cmdHandler);
            } catch (Throwable t) {
                Log.w(TAG, "voice commands", t);
                main.post(() -> status("Voice commands couldn't start: " + t.getMessage(), true));
            }
        });
    }
    /** Stops listening for a while (voice setup uses the microphone). */
    void holdListening(boolean hold) { if (hold) cmdThread.submit(commands::stop); else updateListening(); }

    private final VoiceCommands.Handler cmdHandler = new VoiceCommands.Handler() {
        @Override public void onCommand(String word, float score) {
            lastHeard = String.format(java.util.Locale.US, "\u201c%s\u201d (match %.2f)", word, score);
            main.post(() -> runCommand(word));
        }
        @Override public void onRejected(String word, float score, float reader) {
            lastHeard = String.format(java.util.Locale.US, "\u201c%s\u201d, ignored: %s (match %.2f)", word,
                reader >= score ? "the reading voice" : "not your voice", score);
        }
    };

    void runCommand(String word) {
        try {
            if (beeper == null) beeper = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 30);
            beeper.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80);
        } catch (Exception ignored) { }
        if (chapter == null) { status("Heard \u201c" + word + "\u201d, but no chapter is open.", false); return; }
        switch (word) {
            case "play": play(); break;
            case "pause": pause(); break;
            case "back": seek(pos - 1); break;
            case "forward": seek(pos + 1); break;
            case "beginning": seek(0); break;
            case "end": seek(chapter.size() - 1); break;
            default: return;
        }
        status("Heard \u201c" + word + "\u201d", false);
    }

    private android.graphics.Bitmap art;
    /** Loudbook's picture for the lock screen, the car and the headphones' app. */
    private android.graphics.Bitmap art() {
        if (art == null) try { art = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher); } catch (Exception ignored) { }
        return art;
    }

    private void updateMetadata() {
        if (chapter == null) return;
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, chapter.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, chapter.fiction)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Loudbook")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, msAt(chapter, chapter.size()))
            .putBitmap(MediaMetadata.METADATA_KEY_ART, art()).build());
    }

    /**
     * The chapter as a timeline (so the Assistant, the lock screen and car screens can seek): each
     * sentence takes time in proportion to its length, about 15 characters a second.
     */
    static long msAt(Chapter c, int i) {
        long chars = 0;
        for (int k = 0; k < Math.min(i, c.size()); k++) chars += c.chunks.get(k).text.length() + 4;
        return chars * 1000 / 15;
    }
    static int sentenceAt(Chapter c, long ms) {
        int i = 0;
        while (i + 1 < c.size() && msAt(c, i + 1) <= ms) i++;
        return i;
    }

    private void state() {
        boolean on = playing && !paused;
        commands.readerPlaying = on;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_PLAY_FROM_SEARCH | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(on ? PlaybackState.STATE_PLAYING : playing ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED,
                chapter != null ? msAt(chapter, pos) : PlaybackState.PLAYBACK_POSITION_UNKNOWN, on ? 1f : 0f).build());
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
        if (ch == null || "kindle".equals(ch.site) || "text".equals(ch.site)) return;   // Kindle keeps its own place; shared text has none
        prefs.edit().putInt("pos:" + ch.url, pos)
            .putString("lastUrl", ch.url).putString("lastTitle", ch.title).putString("lastFiction", ch.fiction)
            .putInt("lastPct", ch.size() > 0 ? Math.round(100f * pos / ch.size()) : 0).apply();
        shelve(ch, pos);
    }

    /** "Your stories" on the start page: each story read lately, where you got to (newest first, eight at most). */
    private void shelve(Chapter ch, int at) {
        if (ch.fiction == null || ch.fiction.trim().isEmpty()) return;
        try {
            org.json.JSONArray old = new org.json.JSONArray(prefs.getString("shelf", "[]")), now = new org.json.JSONArray();
            boolean caught = false;                       // (still caught up if it's the same, latest, chapter)
            for (int i = 0; i < old.length(); i++) if (old.getJSONObject(i).optString("url").equals(ch.url)) caught = old.getJSONObject(i).optBoolean("caughtUp");
            now.put(new org.json.JSONObject().put("fiction", ch.fiction).put("title", ch.title).put("url", ch.url).put("site", ch.site)
                .put("pct", ch.size() > 0 ? Math.round(100f * at / ch.size()) : 0).put("at", System.currentTimeMillis()).put("caughtUp", caught));
            for (int i = 0; i < old.length() && now.length() < 8; i++) {
                org.json.JSONObject o = old.getJSONObject(i);
                if (!(o.optString("fiction").equals(ch.fiction) && o.optString("site").equals(ch.site))) now.put(o);
            }
            prefs.edit().putString("shelf", now.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "shelf", e); }
    }
    int savedPos(String url) { return prefs.getInt("pos:" + url, 0); }

    static void start(Context c) { c.startService(new Intent(c, ReaderService.class)); }
}
