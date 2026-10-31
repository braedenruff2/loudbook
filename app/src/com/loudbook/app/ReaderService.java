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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private volatile int pos = 0;
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
        if (!prefs.getBoolean("gemFix1", false)) {
            // (until 0.2.16x the end of every recording was taken for Google stopping early, which made
            // each request a little smaller and could turn streaming off for the day: start afresh)
            prefs.edit().remove("gemCapChars").remove("gemNoStream").putBoolean("gemFix1", true).apply();
        }
        if (!prefs.getBoolean("gemFix2", false)) {
            // (and still, until 0.2.15x, a recording with its last sentence left out or not made out:
            // the requests had shrunk to the smallest size and weren't streamed. Start afresh again)
            prefs.edit().remove("gemCapChars").remove("gemNoStream").putBoolean("gemFix2", true).apply();
        }
        if (KindleService.testBuild() && !prefs.getString("gemHost", "").isEmpty()) CloudVoice.HOST = prefs.getString("gemHost", "");   // (the emulator test's stand-in Google)
        if (prefs.getBoolean("cloudOn", false)) main.postDelayed(this::asrReady, 3000);   // (so Gemini can stream from the first chapter)
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
        heardIdx = -1;
        heardTiming = null; timings.clear();
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
        playAt = System.currentTimeMillis();
        if (offsetIdx != pos) offsetIdx = -1;
        { Chapter ch0 = chapter; int p0 = Math.max(0, Math.min(pos, ch0.size() - 1));
          String line = "play: sentence " + pos + " \"" + (ch0.size() > 0 ? clip40(ch0.chunks.get(p0).text) : "") + "\" of " + ch0.title + (geminiReads(ch0) ? " (Gemini)" : "");
          if (offsetIdx == pos) trail(line); else trailStart(line); }
        // (Gemini: the recogniser that finds the sentences, loaded now rather than when the request goes)
        if (cloudOn() && !asr.loaded()) asrThread.submit(() -> { asrReady(); return null; });
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
            for (Future<Voice.Clip> f : fills.values()) f.cancel(false);
            clips.clear(); fills.clear(); phoneRead.clear(); clipsGen++; filling = false;
            srcOf.clear(); clipOffset.clear();
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
                            if (first > idx && !prefs.getBoolean("gemBackfill", false) && !covered(ch, idx)) {
                                // (in turn, near when it's heard: a request that starts meanwhile can still take it.
                                // These used to be made all at once, every sentence read ahead at the same time,
                                // when the first request started further on: the next sentence took a minute)
                                Voice.Clip pc = phoneInTurn(ch, idx, () -> b.has(idx) || covered(ch, idx));
                                if (pc == null) continue;
                                if (idx == pos && !filling) main.post(() -> status("Gemini didn't record this part (you started further on). The regular voice reads it.", false));
                                return pc;
                            }
                            // a sentence or two Gemini left out in the middle of a recording (or that
                            // couldn't be told apart in it): the phone's voice reads it, rather than a
                            // request just for it (each one costs one of the day's few, and a wait)
                            int after = b.nextHave(idx, ch.size());
                            if (first >= 0 && first < idx && after < ch.size() && after - idx <= 3 && !covered(ch, idx)) {
                                Log.i("LoudbookTest", "gemini: sentence " + idx + " isn't in the recording; the phone's voice reads it");
                                phoneRead.add(idx);
                                return learned(makeVoiced(ch, idx, text, want, sp), want);
                            }
                            // the request that covers it (its sentences are ready one by one as it arrives)
                            Future<?> rec = recording(ch, b, idx);
                            while (!rec.isDone() && !b.has(idx)) Thread.sleep(120);
                            if (rec.isDone()) rec.get();
                        }
                        throw new IOException("Gemini didn't record this sentence");
                    } catch (java.util.concurrent.ExecutionException | IOException e) {
                        // Google failed: another voice reads it (in turn, not every sentence ahead at once)
                        if (chapter == ch) { Voice.Clip pc = phoneInTurn(ch, idx, () -> false); if (pc != null) return pc; }
                        phoneRead.add(idx);
                        return learned(makeHere(text, want, sp), want);
                    }
                });
                clips.put(i, f);
                return f;
            }
            final int at = i;
            f = synth.submit(() -> learned(makeVoiced(ch, at, text, want, sp), want));
            clips.put(i, f);
            return f;
        }
    }

    /** Sentences of a Gemini chapter that the phone's voice read instead (so the log and status say so). */
    private final java.util.Set<Integer> phoneRead = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The phone's voice for sentence idx of a Gemini chapter, made in turn: once reading is within
     * two sentences of it, on the one voice thread, so the sentence about to be heard never waits
     * behind the ones after it. null: stop() came true meanwhile (Gemini has it after all).
     */
    private Voice.Clip phoneInTurn(Chapter ch, int idx, java.util.function.BooleanSupplier stop) throws Exception {
        while (idx > pos + 2 && chapter == ch && !stop.getAsBoolean()) Thread.sleep(150);
        if (chapter != ch) throw new java.util.concurrent.CancellationException();
        // (already read: the waiter only got here once its request was done. Making it again took
        // the phone's voice away from the sentences still to come)
        if (idx < pos - 1) throw new java.util.concurrent.CancellationException();
        if (stop.getAsBoolean()) return null;
        if (phoneRead.add(idx)) Log.i("LoudbookTest", "gemini: sentence " + idx + " isn't recorded; the phone's voice reads it");
        Future<Voice.Clip> f = fillFor(ch, idx);
        return f != null ? f.get() : null;
    }

    private Voice.Clip learned(Voice.Clip c, int want) {
        if (commands.loaded()) cmdThread.submit(() -> commands.learnReader(want, c.samples, c.rate));
        return c;
    }

    // ---------------------------------------------------------------- dialogue in a second voice
    /** The voice for what characters say, when that's on (one that contrasts with the reader's). */
    int dialogueVoice(int narrator) {
        int d = prefs.getInt("dialogueVoice", -1);
        if (d >= 0 && d != narrator) return d;
        boolean narratorFemale = narrator <= 10 || (narrator >= 20 && narrator <= 23);
        return narratorFemale ? 16 : 3;                        // Michael, or Heart
    }

    /** A sentence, with the parts in quotes said in the dialogue voice when that's on. */
    private Voice.Clip makeVoiced(Chapter ch, int i, String text, int want, float sp) throws Exception {
        if (!prefs.getBoolean("dualVoice", false) || !prefs.getString("pcVoice", "").isEmpty()) return makeHere(text, want, sp);
        java.util.List<String[]> parts = Dialogue.split(text, Dialogue.openAt(ch, i));
        int other = dialogueVoice(want);
        if (parts.size() == 1) return makeHere(text, "q".equals(parts.get(0)[1]) ? other : want, sp);
        Log.i("LoudbookTest", "two voices: " + parts.size() + " parts, characters in voice " + other);
        java.util.List<Voice.Clip> clips = new java.util.ArrayList<>();
        for (String[] p : parts) clips.add(makeHere(p[0], "q".equals(p[1]) ? other : want, sp));
        return Voice.join(clips, 0.08);
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
                Log.i("LoudbookTest", "voice: " + text.length() + " letters in " + c.ms + " ms (" + Math.round(c.seconds() * 10) / 10.0 + " s of speech)");
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

    /** The sentences of the Kindle page on screen now: [from, to) in the chapter. */
    private volatile int kindleFrom = 0, kindleTo = Integer.MAX_VALUE;
    /** The sentence being heard, its first frame and its length, for the highlight on Kindle's page. */
    private volatile long heardStart, heardLen;
    private volatile int heardIdx = -1;
    /** How the voice moves through the sentence being heard (from its own pauses), if known; and each queued sentence's. */
    private volatile SpeechTiming heardTiming;
    private final java.util.Map<Integer, SpeechTiming> timings = new java.util.concurrent.ConcurrentHashMap<>();

    /** How far through the sentence being heard the voice is (0..1), by the sound's own pauses when known. */
    private float heardFrac(AudioTrack t) {
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        SpeechTiming st = heardTiming;
        if (st != null) return st.fracAt(head - heardStart);
        return heardLen > 0 ? Math.max(0f, Math.min(1f, (head - heardStart) / (float) heardLen)) : 0f;
    }

    /** For the highlight on a web page: {sentence being heard, how far through it (0..1)}; null if nothing is. */
    float[] now() {
        AudioTrack t = track;
        int i = heardIdx;
        if (t == null || i < 0 || !playing) return null;
        return new float[]{i, heardFrac(t)};
    }

    /** For the highlight: {sentence, how far through it (0..1), page's first sentence, page's end}. */
    float[] kindleNow() {
        AudioTrack t = track;
        int i = heardIdx;
        if (t == null || i < 0) return null;
        return new float[]{i, heardFrac(t), kindleFrom, kindleTo};
    }

    /** For the tests: the page's paragraphs and the sentences made of them (ends only). */
    private static void logKindle(String what, java.util.List<String> paras, java.util.List<Chapter.Chunk> chunks) {
        for (int i = 0; i < paras.size(); i++) Log.i("LoudbookTest", "kindle " + what + " para " + i + ": " + ends(paras.get(i)));
        for (int i = 0; i < chunks.size(); i++) Log.i("LoudbookTest", "kindle " + what + " sentence " + i + ": " + ends(chunks.get(i).text));
    }
    private static String ends(String t) { return t.length() <= 70 ? "[" + t + "]" : "[" + t.substring(0, 30) + " … " + t.substring(t.length() - 35) + "] (" + t.length() + ")"; }

    /** The sentence that runs over the page break (if Kindle shared how the next page starts), and how far into it the next page starts (0..1); -1 if none. */
    private volatile int splitIdx = -1;
    private volatile float splitFrac;
    private volatile boolean splitTurned;
    private float addBridge(java.util.List<Chapter.Chunk> list, Chapter.Chunk bridge) {
        if (bridge == null) return -1;
        java.util.List<Chapter.Chunk> said = spoken(java.util.Collections.singletonList(bridge));
        if (said.isEmpty()) return -1;
        list.add(said.get(0));
        float f = Math.max(0.05f, Math.min(0.98f, kt.bridgeHead / (float) Math.max(1, bridge.text.length())));
        Log.i("LoudbookTest", "kindle: sentence over the page break, next page from " + Math.round(f * 100) + "%: " + ends(bridge.text));
        return f;
    }

    /** Starts reading the page open in Kindle (from KindleService's button); letter: where on the page to start (0 = the top). */
    void startKindle(KindleService.Page p, int letter) {
        kt = new KindleText(prefs.getString("fixes", ""));
        final android.content.Context app = getApplicationContext();
        KindleText.dictionary = w -> Voice.hasWord(app, w);
        java.util.List<Chapter.Chunk> raw = kt.chunks(p.paragraphs, false);
        if (raw.isEmpty()) raw = new KindleText(prefs.getString("fixes", "")).chunks(p.paragraphs, true);
        logKindle("start", p.paragraphs, raw);
        kindleSig = p.signature;
        kindleParas = p.paragraphs;
        kindleEnded = false; kindleBusy = false; kindleHidden = false;
        splitIdx = -1; splitTurned = false;
        final java.util.List<Chapter.Chunk> page = raw;
        final KindleText kts = kt;
        kindleThread.submit(() -> {
            final Chapter.Chunk bridge = kts.bridge(p.nextStart);            // (may look in the dictionary: not on the main thread)
            java.util.List<Chapter.Chunk> first = new java.util.ArrayList<>(spoken(page));
            float frac = addBridge(first, bridge);
            // the sentence at the place tapped (or the first)
            int at = 0;
            if (letter > 0) {
                java.util.List<String> texts = new java.util.ArrayList<>();
                for (Chapter.Chunk c : first) texts.add(c.text);
                java.util.List<int[]> sp = p.layout(false).spans(texts);
                for (int k = 0; k < sp.size(); k++) if (sp.get(k) != null && sp.get(k)[1] > letter) { at = k; break; }
            }
            if (at > 0) {
                // started mid-page: give the voice a short first piece there too, so it starts sooner
                Chapter.Chunk c = first.get(at);
                java.util.List<String> q = KindleText.quickStart(c.text);
                if (q.size() > 1) {
                    java.util.List<Chapter.Chunk> parts = new java.util.ArrayList<>();
                    for (String t : q) parts.add(new Chapter.Chunk(c.block, t, t));
                    java.util.List<Chapter.Chunk> said = spoken(parts);
                    if (said.size() == q.size()) { first.remove(at); first.addAll(at, said); }
                }
            }
            if (at == 0 && letter == 0 && first.size() > 1) {
                // the page starts with the end of a word split over the page break ("cumstances which..."): start after it
                Chapter.Chunk c0 = first.get(0);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\p{Ll}{2,})(?=[\\s,;:.!?])").matcher(c0.text);
                java.util.function.Predicate<String> dict = KindleText.dictionary;
                if (m.find() && dict != null) {
                    boolean word = true;
                    try { word = dict.test(m.group(1)); } catch (RuntimeException ignored) { }
                    String rest = c0.text.substring(m.end()).replaceFirst("^[\\s,;:]+", "");
                    if (!word && rest.matches("(?s).*\\p{L}.*")) {
                        java.util.List<Chapter.Chunk> fixed = spoken(java.util.Collections.singletonList(new Chapter.Chunk(c0.block, rest, kts.sayOf(rest))));
                        if (fixed.size() == 1) { first.set(0, fixed.get(0)); Log.i("LoudbookTest", "kindle: the page starts with the end of a split word (\"" + m.group(1) + "\"): starting after it"); }
                    }
                }
            }
            final int startAt = at;
            main.post(() -> {
                Chapter ch = new Chapter("kindle:" + System.currentTimeMillis(), "kindle", p.title.isEmpty() ? "Kindle" : p.title, "Kindle", first);
                Log.i("LoudbookTest", "kindle start: " + p.paragraphs.size() + " paragraphs, " + first.size() + " sentences, from sentence " + startAt + ", title " + p.title);
                kindleFrom = 0; kindleTo = first.size();
                splitTurned = false; splitIdx = frac >= 0 ? first.size() - 1 : -1; splitFrac = frac;
                setChapter(ch, startAt, true);
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
    private volatile boolean kindleQueued;
    /** The paragraphs on the Kindle screen being read (to tell what's new on the next one). */
    private volatile java.util.List<String> kindleParas;
    /** The page couldn't be turned because Kindle wasn't on screen: turn it when Kindle's back. */
    private volatile boolean kindleHidden;
    /** Waits (paused) while Kindle is away, then turns the page once it's back and reading goes on. false = interrupted. */
    private boolean kindleBack(int myGen, Chapter ch, java.util.ArrayDeque<long[]> marks) {
        while (gen == myGen && kindleHidden && chapter == ch) {
            heard(ch, marks, myGen);
            sleep(200);
            if (gen != myGen || chapter != ch || !kindleHidden) return false;   // (something else is being read now)
            KindleService k = KindleService.me;
            if (paused || k == null || !k.kindleVisible()) continue;
            kindleHidden = false;
            Log.i("LoudbookTest", "kindle: back in Kindle, turning the page now");
            kindleMore(ch);
            long until = System.currentTimeMillis() + 10_000;
            while (gen == myGen && (kindleBusy || !kindleQueued) && System.currentTimeMillis() < until) { heard(ch, marks, myGen); sleep(40); }
        }
        return gen == myGen;
    }
    private void kindleMore(Chapter ch) {
        if (kindleBusy || kindleEnded || kt == null || chapter != ch) return;
        kindleBusy = true;
        kindleQueued = false;
        kindleThread.submit(() -> {
            try {
                if (chapter != ch) return null;                                  // (a new start since)
                KindleService k = KindleService.me;
                KindleService.Page p = k == null ? new KindleService.Page() : k.nextPage(kindleSig);
                if (p.paragraphs.isEmpty() && k != null && k.kindleVisible() && chapter == ch) {
                    // (one more go after a moment before taking it as the end of the book: Kindle may have been busy)
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                    Log.i("LoudbookTest", "kindle: the page didn't turn, trying again");
                    p = k.nextPage(kindleSig);
                }
                if (p.paragraphs.isEmpty() && k != null && !k.kindleVisible()) {
                    // Kindle isn't on screen (he switched apps just as the page ended, or Kindle's own
                    // screens flickered): not the end of the book. Wait, and turn the page once Kindle is
                    // back and reading goes on (leaving Kindle pauses reading by itself, see KindleService)
                    kindleHidden = true;
                    Log.i("LoudbookTest", "kindle: not on screen at the page turn, waiting for it");
                    main.post(() -> status("Kindle isn't on screen, so the page can't be turned. Open it to carry on.", false));
                } else if (p.paragraphs.isEmpty()) {
                    kindleEnded = true;
                    java.util.List<Chapter.Chunk> rest = spoken(kt.chunks(new java.util.ArrayList<>(), true));   // whatever was held back
                    if (!rest.isEmpty()) ch.chunks.addAll(rest);
                    if (k == null || !k.kindleVisible()) main.post(() -> status("Kindle isn't on screen, so the page can't be turned. Open it to carry on.", false));
                } else {
                    kindleSig = p.signature;
                    // (with Kindle's continuous scrolling the new screen can start with the end of the last one again)
                    java.util.List<String> paras = KindleText.afterOverlap(kindleParas, p.paragraphs);
                    if (paras.size() != p.paragraphs.size() || (!paras.isEmpty() && !paras.get(0).equals(p.paragraphs.get(0))))
                        Log.i("LoudbookTest", "kindle: this screen starts with words already read, from: " + ends(paras.isEmpty() ? "" : paras.get(0)));
                    kindleParas = p.paragraphs;
                    java.util.List<Chapter.Chunk> got = kt.chunks(paras, false);
                    logKindle("turned", paras, got);
                    Chapter.Chunk bridge = kt.bridge(p.nextStart);
                    java.util.List<Chapter.Chunk> more = new java.util.ArrayList<>(spoken(got));
                    float frac = addBridge(more, bridge);
                    int from = ch.size();
                    // the sentence that ran over the page break (being heard now) is partly on this page too
                    int pageFrom = splitIdx >= 0 && splitTurned && splitIdx == from - 1 ? splitIdx : from;
                    ch.chunks.addAll(more);
                    kindleFrom = pageFrom; kindleTo = ch.size();
                    splitTurned = false; splitIdx = frac >= 0 ? ch.size() - 1 : -1; splitFrac = frac;
                    Log.i("LoudbookTest", "kindle page turned: +" + more.size() + " sentences");
                }
            } catch (Throwable t) { Log.w(TAG, "kindle page", t); kindleEnded = true; }
            finally { kindleQueued = true; kindleBusy = false; }
            return null;
        });
    }

    // ---------------------------------------------------------------- Gemini (online voice)
    private final ExecutorService cloudPool = Executors.newSingleThreadExecutor();   // recording ahead: one request at a time
    // a request that reading is waiting for (a chapter just opened) doesn't queue behind a
    // recording-ahead request for another chapter, which can take minutes
    private final ExecutorService cloudNow = Executors.newSingleThreadExecutor();
    private final ExecutorService waiters = Executors.newCachedThreadPool();
    private volatile GeminiBook book;               // the playing chapter's recording
    private String bookKey = "";
    /** A request on its way, and what it covers: {book key, from, to} for each chapter in it. */
    private static final class Req { Future<?> f; final List<Object[]> covers = new ArrayList<>(); }
    private final List<Req> inflight = new ArrayList<>();
    volatile String cloudError = "";
    volatile long cloudRetryAt = 0;
    volatile String cloudStatus = "";

    boolean cloudOn() {
        return prefs.getBoolean("cloudOn", false) && !prefs.getString("cloudKey", "").trim().isEmpty()
            && System.currentTimeMillis() >= cloudRetryAt;
    }
    void cloudChanged() { cloudRetryAt = 0; cloudError = ""; dropClips(); if (cloudOn()) asrReady(); if (playing && !paused) seek(pos); }
    private String cVoice() { return prefs.getString("cloudVoice", "Sulafat"); }
    private String cModel() { return prefs.getString("cloudModel", "gemini-3.8-flash-tts"); }
    private String cStyle() { return prefs.getString("cloudStyle", CloudVoice.DEFAULT_STYLE); }
    private File cloudRoot() { return new File(getCacheDir(), "gemini"); }

    private synchronized GeminiBook bookFor(Chapter ch) {
        String k = GeminiBook.key(ch.url, cVoice(), cModel(), cStyle());
        if (book == null || !k.equals(bookKey)) {
            book = GeminiBook.open(cloudRoot(), k); bookKey = k;
            // recorded before Gemini's read-out instructions were allowed for: time it again (free)
            for (GeminiBook.Made m : book.needsRetiming()) refineLater(ch, book, m);
        }
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


    private GeminiBook bookOf(Chapter c) {
        GeminiBook b = GeminiBook.open(cloudRoot(), GeminiBook.key(c.url, cVoice(), cModel(), cStyle()));
        b.bind(c);
        return b;
    }

    /**
     * The request that will record sentence i: the one already on its way if it covers i, or a new
     * one from i, packed as full as a request can be (on into the next chapter).
     */
    private synchronized Future<?> recording(Chapter ch, GeminiBook b, int i) {
        String k = GeminiBook.key(ch.url, cVoice(), cModel(), cStyle());
        inflight.removeIf(r -> r.f.isDone());
        for (Req r : inflight) for (Object[] c : r.covers) if (k.equals(c[0]) && i >= (int) c[1] && i < (int) c[2]) return r.f;
        for (Req r : inflight) for (Object[] c : r.covers) if (k.equals(c[0]) && i > (int) c[1] && i - (int) c[2] < 40) return r.f;   // wait: it may run on far enough
        // just before one on its way (it was started a little ahead, while the phone's voice reads up to it)
        for (Req r : inflight) for (Object[] c : r.covers) if (k.equals(c[0]) && i < (int) c[1] && (int) c[1] - i < 80) return r.f;
        return request(ch, b, i, true, true);
    }

    /** Whether sentence i of ch comes before where the request on its way for ch starts (so Gemini won't have it soon). */
    private synchronized boolean beforeRequest(Chapter ch, int i) {
        String k = GeminiBook.key(ch.url, cVoice(), cModel(), cStyle());
        for (Req r : inflight) if (!r.f.isDone()) for (Object[] c : r.covers) if (k.equals(c[0]) && i < (int) c[1]) return true;
        return false;
    }

    /** Whether a request on its way covers sentence i of ch. */
    private synchronized boolean covered(Chapter ch, int i) {
        String k = GeminiBook.key(ch.url, cVoice(), cModel(), cStyle());
        inflight.removeIf(r -> r.f.isDone());
        for (Req r : inflight) for (Object[] c : r.covers) if (k.equals(c[0]) && i >= (int) c[1] && i < (int) c[2]) return true;
        return false;
    }

    private synchronized boolean requesting() { inflight.removeIf(r -> r.f.isDone()); return !inflight.isEmpty(); }

    /** Starts a request from sentence i of a chapter. now: reading is waiting for it (it goes ahead of recording ahead). */
    private synchronized Future<?> request(Chapter ch, GeminiBook b, int i, boolean follow, boolean now) {
        inflight.removeIf(r -> r.f.isDone());
        int at = pos;
        // (from where reading is, not from i: the sentences ahead are waited for all at once, and when sentence 2's waiter got
        // here first the request started at 2, and sentences 0-1 waited for all of it, then the phone's voice read them: a minute
        // of silence at the start, gemini-test 2026-10-30. With the phone's voice filling in, leadFrom says where Gemini takes over)
        if (now && ch == chapter && i >= at && i - at <= READ_AHEAD && b.nextHave(at, ch.size()) > i) i = leadFrom(ch, b, at);
        else if (now && ch == chapter && i > at && i - at <= READ_AHEAD + 2) {
            // (from the first sentence not recorded yet after where reading is, not from the waiter
            // that got here first: sentence 9's waiter asked from 9 while 7 and 8 were missing too,
            // so the phone's voice read them, then a request just for them followed. gemini-test 2026-10-31)
            int m = b.nextMissing(at, i);
            while (m < i && covered(ch, m)) m = b.nextMissing(m + 1, i);
            if (m < i) i = m;
        }
        List<GeminiBook.Span> plan = plan(ch, b, i, sizeFor(ch, b, i));
        Req r = new Req();
        for (GeminiBook.Span sp : plan) r.covers.add(new Object[]{GeminiBook.key(sp.ch.url, cVoice(), cModel(), cStyle()), sp.from, sp.to});
        r.f = (now && !inflight.isEmpty() ? cloudNow : cloudPool).submit(() -> { record(plan, follow); return null; });
        inflight.add(r);
        return r.f;
    }
    private Future<?> request(Chapter ch, GeminiBook b, int i, boolean follow) { return request(ch, b, i, follow, false); }

    /** Letters a request can hold (Google stops longer ones early): learned from the recordings. */
    private int capChars() { return prefs.getInt("gemCapChars", 24000); }

    /**
     * What one request covers, from sentence i: the rest of this chapter (up to what's recorded
     * already, or what a request can hold), and if there's room left, on into the next chapters
     * whose text is already loaded, so no part of a request goes to waste.
     */
    private List<GeminiBook.Span> plan(Chapter ch, GeminiBook b, int i, int cap) {
        List<GeminiBook.Span> out = new ArrayList<>();
        int used = 0;
        Chapter c = ch; GeminiBook cb = b; int f = i;
        for (int hops = 0; hops < 5 && c != null; hops++) {
            int end = cb.nextHave(f, c.size()), t = f;
            while (t < end && (out.isEmpty() && t == f || used + c.chunks.get(t).say.length() <= cap)) { used += c.chunks.get(t).say.length() + 1; t++; }
            if (t > f) out.add(new GeminiBook.Span(c, cb, f, t));
            if (t < c.size() || used >= cap * 0.95 || !autoNext) break;   // stopped inside this chapter
            // room left: on into the next chapter, if its text is here already
            Chapter nx = loadedNext(c);
            if (nx == null || "kindle".equals(nx.site)) break;
            GeminiBook nb = bookOf(nx);
            c = nx; cb = nb; f = nb.nextMissing(0, nx.size());      // (its start may be recorded already, packed into an earlier request)
        }
        if (out.isEmpty()) out.add(new GeminiBook.Span(ch, b, i, Math.min(ch.size(), i + 1)));
        return out;
    }

    /** The chapter after c, if its text has been loaded already (no waiting for it). */
    private Chapter loadedNext(Chapter c) {
        if (c == null || c.nextUrl == null) return null;
        if (c.nextUrl.equals(nextFor) && nextCh != null) return nextCh;
        final Chapter[] got = new Chapter[1];
        Runnable look = () -> { try { got[0] = fetcher().ready(c.nextUrl); } catch (Throwable ignored) { } };
        if (Looper.myLooper() == Looper.getMainLooper()) look.run();
        else {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            main.post(() -> { look.run(); done.countDown(); });
            try { done.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        return got[0];
    }

    /** One request for the planned sentences. If Google stops early, the rest of this chapter follows straight away. */
    private void record(List<GeminiBook.Span> plan, boolean follow) throws Exception {
        GeminiBook.Span first = plan.get(0);
        Chapter ch = first.ch; GeminiBook b = first.book;
        int from = first.from, to = first.to;
        if (b.has(from)) return;
        cloudStatus = "Gemini is recording " + (from == 0 ? "the chapter" : "the rest of the chapter") + (plan.size() > 1 ? " and the start of the next" : "") + " (one request)\u2026";
        if (ch == chapter) main.post(() -> status(cloudStatus, false));
        long t0 = System.currentTimeMillis();
        int got;
        GeminiBook.Made made;
        try {
            countRequest();
            double[] pace = new double[1];
            // streamed and listened to as it arrives (reading starts in seconds), when the
            // recogniser is ready; otherwise the whole recording first, timed afterwards
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            boolean stream = streamToday();
            AsrAligner live = asrNow();
            trail("asked Google for sentences " + from + "-" + (to - 1) + (plan.size() > 1 ? " and the next chapter's start" : "")
                + (stream ? ", streamed" : ", all at once") + (live == null ? " (no recogniser)" : ""));
            Log.i("LoudbookTest", "gemini: asking Google for sentences " + from + "-" + (to - 1) + (plan.size() > 1 ? " and the next chapter's start" : "")
                + (ch == chapter && playAt > 0 ? ", " + (System.currentTimeMillis() - playAt) + " ms after play" : "") + (live == null ? " (no recogniser: not streamed)" : ""));
            made = b.fetch(ch, from, to, prefs.getString("cloudKey", ""), cModel(), cVoice(), cStyle(),
                prefs.getFloat("gemPace:" + cVoice(), 0f), pace, live, new ArrayList<>(plan.subList(1, plan.size())), stream);
            got = made.count;
            prefs.edit().putString("gemHow", CloudVoice.how + (made.timed ? "; timed by the words as it arrived" : "")
                + (CloudVoice.howProblem.isEmpty() ? "" : " (first try: " + CloudVoice.howProblem + ")")).apply();
            Log.i("LoudbookTest", "gemini: " + got + " of " + (to - from) + " sentences" + (plan.size() > 1 ? " + " + made.extra + " of the next chapter's" : "")
                + ", " + made.chars + " letters, " + Math.round(made.seconds) + " s, " + CloudVoice.how + (made.timed ? ", timed live" : "")
                + ", ready after " + (System.currentTimeMillis() - t0) / 1000 + " s");
            // a stream that stops early (Google sometimes cuts streamed speech off after a minute)
            // would cost a request per minute: ask for whole recordings for the rest of the day
            // (one sentence left out at the very end isn't Google stopping early: the next request
            // starts with it. Counting it as stopping early shrank every request to the smallest
            // size, stopped streaming for the day, and asked again for that one sentence)
            int missing = made.asked - made.count - made.extra;
            boolean short_ = missing >= 2;
            boolean cutOff = made.streamed && short_ && !made.broke && made.seconds < 150;
            if (cutOff) prefs.edit().putString("gemNoStream", today).apply();
            // how much a request can hold: when Google stops one early, that's the most (a little
            // less, to be safe); when a long one comes back whole, it can hold at least that much
            else if (short_ && !made.broke && made.chars > 3000) prefs.edit().putInt("gemCapChars", Math.max(4000, made.chars * 95 / 100)).apply();
            else if (!short_ && made.chars > capChars() * 0.85) prefs.edit().putInt("gemCapChars", Math.min(60000, made.chars * 115 / 100)).apply();
            if (pace[0] > 0) prefs.edit().putFloat("gemPace:" + cVoice(), (float) pace[0]).apply();
            learnSpeed(made, short_);
            noteTiming(made, short_, cutOff);
            trail("Google's recording: sentences " + from + "-" + (from + made.count - 1) + (made.extra > 0 ? " + " + made.extra + " of the next chapter" : "")
                + (missing > 0 ? ", " + missing + " left out" : "") + (made.note.isEmpty() ? "" : " (" + made.note + ")"));
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
        if (KindleService.testBuild())
            for (int k = from; k < from + got; k++) Log.i("LoudbookTest", "gemini cut " + k + ": " + Math.round(b.startOf(k) * 1000.0 / 24000) / 1000.0
                + " " + b.secondsOf(k) + " | " + ch.chunks.get(k).say);
        if (!made.timed) refineLater(ch, b, made);
        workSecs += (System.currentTimeMillis() - t0) / 1000.0;
        GeminiBook.trim(cloudRoot(), 800L << 20);
        if (ch == chapter) main.post(() -> status("", false));
        // Google stopped early: ask for the rest now, so it's ready when it's needed (one sentence
        // left out at the end isn't worth a request of its own: reading ahead packs it into the next)
        if (follow && from + got < to - 1 && chapter == ch) {
            final int next = from + got;
            waiters.submit(() -> { request(ch, b, next, true); return null; });
        }
    }

    private String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); }

    /** Whether today's requests are streamed (Google sends the audio as it's made; reading starts within seconds). */
    private boolean streamToday() { return !today().equals(prefs.getString("gemNoStream", "")) && prefs.getBoolean("gemExact", true); }

    /** Google's wait before audio comes (s), and how fast it makes a whole recording (seconds of audio a second), learned from requests. */
    private double gemLatency() { return prefs.getFloat("gemLat", 6f); }

    /**
     * Whether the next request will be streamed. record() fetches the recogniser first if it isn't
     * loaded yet (asrNow), so that's only a short wait, not a whole recording: guessing "all at once"
     * here made the first request of a fresh install start near the end of the chapter.
     */
    private boolean willStream() { return streamToday() && (asr.loaded() || asrFetching || AsrAligner.downloaded(new File(getFilesDir(), "asr"))); }
    private double gemRate() { return prefs.getFloat("gemRate", 1.5f); }
    private double gemPace() { float p = prefs.getFloat("gemPace:" + cVoice(), 0f); return p > 0.02 && p < 0.2 ? p : 0.065; }

    private void learnSpeed(GeminiBook.Made m, boolean short_) {
        if (m.firstAudioMs <= 0 || m.seconds < 5) return;
        SharedPreferences.Editor e = prefs.edit();
        double first = m.firstAudioMs / 1000.0;
        if (m.streamed) e.putFloat("gemLat", (float) (0.6 * gemLatency() + 0.4 * Math.min(60, first)));
        else {
            // all at once: the audio comes once the whole recording is made
            double r = m.seconds / Math.max(1, first - Math.min(gemLatency(), first * 0.5));
            e.putFloat("gemRate", (float) Math.max(0.3, Math.min(20, 0.5 * gemRate() + 0.5 * r)));
        }
        e.apply();
    }

    /** One line per request, newest first, for Settings ("Gemini timing"): what took how long. */
    private void noteTiming(GeminiBook.Made m, boolean short_, boolean noStream) {
        String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
        String line = when + "  " + m.chars + " letters, " + mmss(m.seconds) + " of audio (" + (m.count + m.extra) + " of " + m.asked + " sentences), "
            + (m.streamed ? "streamed" : "all at once") + ": first audio after " + (m.firstAudioMs < 0 ? "-" : secs(m.firstAudioMs))
            + ", first sentence ready " + secs(m.firstReadyMs) + ", all in after " + mmss(m.totalMs / 1000.0)
            + (short_ ? "; Google stopped early" : m.count + m.extra < m.asked ? "; the last sentence was left out (the next request starts with it)" : "")
            + (noStream ? " (so whole recordings for the rest of today)" : "") + (m.note.isEmpty() ? "" : "\n      (" + m.note + ")");
        Log.i("LoudbookTest", "gemini timing: " + line);
        String old = prefs.getString("gemTimes", "");
        String[] ls = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int k = 0; k < Math.min(11, ls.length); k++) sb.append('\n').append(ls[k]);
        prefs.edit().putString("gemTimes", sb.toString()).apply();
    }
    private static String secs(long ms) { return String.format(java.util.Locale.US, "%.1f s", ms / 1000.0); }
    private static String mmss(double s) { long t = Math.round(s); return t < 60 ? t + " s" : (t / 60) + ":" + String.format(java.util.Locale.US, "%02d", t % 60); }

    /** Seconds of recording ready from where reading is up to sentence i of ch (ch: this chapter or the next). */
    private double readyAhead(Chapter ch, GeminiBook b, int i) {
        Chapter cur = chapter;
        if (cur == null) return 0;
        double s = 0;
        if (ch == cur) { for (int k = pos; k < i; k++) s += b.secondsOf(k); return s; }
        GeminiBook cb = book;
        if (cb != null) for (int k = pos; k < cur.size(); k++) s += cb.secondsOf(k);
        for (int k = 0; k < i; k++) s += b.secondsOf(k);
        return s;
    }

    /**
     * How many letters a request from sentence i should ask for. Streamed: as many as Google
     * allows (the audio comes as it's made). All at once: only as much as Google can make before
     * the recording already there runs out, so reading never stops to wait (the first request,
     * with nothing ahead, about a minute's worth); each one after can be bigger.
     */
    private int sizeFor(Chapter ch, GeminiBook b, int i) {
        int cap = capChars();
        if (willStream()) return cap;
        double ahead = readyAhead(ch, b, i);
        // (the first: smaller when nothing else reads meanwhile, so Gemini's voice comes sooner)
        boolean fill = prefs.getBoolean("gemFill", false);
        double secs = Math.max(ahead < 1 ? (fill ? FIRST_SECS : 45) : fill ? 120 : 60, (ahead - gemLatency() - 10) * gemRate());
        return (int) Math.max(900, Math.min(cap, secs / gemPace()));
    }

    /**
     * Reading starts with nothing recorded: the phone's voice reads while Google makes the first of
     * the recording, so the request starts where the phone's voice will have got to by then (not
     * at the first sentence, which would be read twice over, and the recording of it wasted).
     */
    private int leadFrom(Chapter ch, GeminiBook b, int i) {
        if (!prefs.getBoolean("gemFill", false) || !voice.ready()) return i;
        boolean streamed = streamToday();              // (record() streams whenever today allows; it fetches the recogniser first if need be)
        double wait = gemLatency() + (streamed ? (asr.loaded() ? 3 : 8) : FIRST_SECS / gemRate());
        double phonePace = 0.068 / Math.max(0.5, speed);                 // (the phone's voice: seconds a letter)
        double t = 0;
        int k = i;
        while (k < ch.size() - 1 && t < wait && !b.has(k)) { t += Math.max(1, ch.chunks.get(k).say.length()) * phonePace + 0.3; k++; }
        // (from the start of a paragraph if one starts near there, so Gemini takes over at a natural place)
        for (int q = k; q < Math.min(ch.size(), k + 4); q++) if (q > 0 && ch.chunks.get(q).block != ch.chunks.get(q - 1).block) { k = q; break; }
        // (a short chapter: not past its middle, so the request isn't a sentence or two at its very end)
        k = Math.min(k, i + (ch.size() - i) / 2);
        if (k > i) Log.i("LoudbookTest", "gemini: starting from sentence " + k + " (about " + Math.round(wait) + " s ahead); the phone's voice reads up to it");
        return k;
    }
    /** All at once (not streamed), the first request at a start: about this many seconds of audio. */
    private static final double FIRST_SECS = 150;

    /** How much recording to keep ready ahead of reading: when less is left, the next request starts. */
    private double aheadWanted() {
        if (willStream()) return 180;
        return Math.min(1800, gemLatency() + capChars() * gemPace() / gemRate() + 60);
    }

    /**
     * Records ahead while reading: when less than a few minutes of recording is left ahead of
     * where reading is (in this chapter and on into the next), the next request starts, packed
     * full from the first sentence not yet recorded. So there's no wait, at a page turn or a
     * chapter turn.
     */
    private void recordAhead(Chapter ch, int here) {
        if (!cloudOn() || !prefs.getBoolean("gemPrefetch", true) || "kindle".equals(ch.site) || "text".equals(ch.site)) return;
        if (requesting()) return;
        GeminiBook b = bookFor(ch);
        double ahead = 0;
        int k = here;
        // (the phone's voice is reading up to where Gemini's recording starts: count from there, or
        // this asks again for the part the phone's voice is reading)
        int nh = b.nextHave(here, ch.size());
        if (!b.has(here) && nh < ch.size() && !prefs.getBoolean("gemBackfill", false)) k = nh;
        for (; k < ch.size() && b.has(k); k++) ahead += b.secondsOf(k);
        // (a gap of a sentence or two inside the recording is read by the phone's voice, as clipFor
        // does: not a request of its own. The recording goes on after it)
        for (int hop = 0; hop < 4 && k < ch.size() && k > b.firstHave(ch.size()); hop++) {
            int after = b.nextHave(k, ch.size());
            if (after >= ch.size() || after - k > 3) break;
            for (int q = k; q < after; q++) ahead += ch.chunks.get(q).say.length() * 0.068;
            for (k = after; k < ch.size() && b.has(k); k++) ahead += b.secondsOf(k);
        }
        Chapter c = ch; GeminiBook cb = b; int miss = k;
        if (k >= ch.size()) {
            if (!autoNext) return;
            Chapter nx = loadedNext(ch);
            if (nx == null) return;
            GeminiBook nb = bookOf(nx);
            int j = 0;
            for (; j < nx.size() && nb.has(j); j++) ahead += nb.secondsOf(j);
            if (j >= nx.size()) return;                               // (all of the next one is recorded too)
            c = nx; cb = nb; miss = j;
        }
        if (ahead > aheadWanted()) return;
        Log.i("LoudbookTest", "gemini: " + Math.round(ahead) + " s recorded ahead, recording on from " + (c == ch ? "sentence " + miss : "the next chapter's sentence " + miss));
        request(c, cb, miss, c == ch);
    }
    private int aheadAsked = -1;

    // exact sentence timing: a recogniser listens to each new recording once, on its own thread
    private final ExecutorService asrThread = Executors.newSingleThreadExecutor();
    private final AsrAligner asr = new AsrAligner();
    private volatile boolean asrFetching;

    /** The recogniser, if exact timing is on and it's on the phone (it's fetched in the background otherwise). */
    private AsrAligner asrReady() {
        if (!prefs.getBoolean("gemExact", true)) return null;
        File d = new File(getFilesDir(), "asr");
        if (AsrAligner.downloaded(d)) {
            try { asr.load(d); return asr; } catch (Throwable t) { Log.w(TAG, "recogniser", t); return null; }
        }
        if (!asrFetching) {
            asrFetching = true;
            asrThread.submit(() -> { try { AsrAligner.fetch(d, null); } catch (Throwable t) { Log.w(TAG, "recogniser download", t); } asrFetching = false; return null; });
        }
        return null;
    }

    /**
     * The recogniser for a request about to go: fetched now if it isn't on the phone yet (20 MB,
     * once). Without it a recording can't be streamed, and is timed by its pauses only, which on a
     * long chapter can lose its place (sentences out of step with the voice).
     */
    private AsrAligner asrNow() {
        AsrAligner a = asrReady();
        if (a != null || !prefs.getBoolean("gemExact", true)) return a;
        File d = new File(getFilesDir(), "asr");
        try {
            // (asrReady started fetching it in the background: wait for that, a minute and a half at most)
            for (int k = 0; k < 900 && asrFetching && !AsrAligner.downloaded(d); k++) Thread.sleep(100);
            if (!AsrAligner.downloaded(d)) return null;
            asr.load(d);
            return asr;
        } catch (Throwable t) { Log.w(TAG, "recogniser", t); return null; }
    }

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

    /** The next chapter's text, loaded by the screen near the end of this one: record ahead into it. */
    void cloudPrefetch(Chapter n) {
        Chapter ch = chapter;
        if (!cloudOn() || n == null || ch == null || !n.url.equals(ch.nextUrl)) return;
        if (nextCh == null) { nextFor = n.url; nextCh = n; }
        final int here = pos;
        waiters.submit(() -> { recordAhead(ch, here); return null; });
    }
    private String prefetchAsked = "";

    private volatile Voice.Clip current;

    // the phone's voice reading while Gemini gets ready
    private volatile boolean filling;
    private final Map<Integer, Future<Voice.Clip>> fills = new HashMap<>();
    private volatile long playAt;
    private volatile int firstSoundFor = -1;

    private boolean geminiReads(Chapter ch) { return cloudOn() && !"kindle".equals(ch.site) && !"text".equals(ch.site); }

    /** The phone's voice for sentence i (made once, a little ahead). */
    private Future<Voice.Clip> fillFor(Chapter ch, int i) {
        if (i < 0 || i >= ch.size()) return null;
        synchronized (clips) {
            Future<Voice.Clip> f = fills.get(i);
            if (f != null) return f;
            final int want = sid; final float sp = speed; final String text = ch.chunks.get(i).say;
            f = synth.submit(() -> learned(makeVoiced(ch, i, text, want, sp), want));
            fills.put(i, f);
            return f;
        }
    }

    private void logFirstSound(int myGen, boolean gem, boolean byPhone) {
        firstSoundFor = myGen;
        long ms = System.currentTimeMillis() - playAt;
        String how = !gem ? "" : byPhone ? " (the phone's voice, while Gemini got ready)" : " (Gemini)";
        Log.i("LoudbookTest", "start: first sound after " + ms + " ms" + how);
        if (gem) prefs.edit().putString("gemStart", new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date())
            + "  pressed play: first sound after " + secs(ms) + how).apply();
    }

    /**
     * Plays Gemini's recording from sentence start as it streams in, straight from the file it's
     * saved to: Gemini's voice from its first word, rather than waiting until the first sentence
     * can be told apart (or the phone's voice reading meanwhile). Each sentence's highlight goes in
     * at its place in the audio as soon as the recogniser has found where it starts, so it moves
     * when the voice gets there; a sentence not found yet just isn't highlighted until it is.
     * Stops at the end of this chapter's part of the recording (or where Google stopped).
     * Returns the first sentence it didn't play, or -1 if interrupted.
     */
    private int playLive(GeminiBook b, GeminiBook.Live lv, int start, Chapter ch, int myGen, java.util.ArrayDeque<long[]> marks) {
        final int rate = 24000;
        AudioTrack t = ensureTrack(rate);
        applyRate(t, new Voice.Clip(new float[0], rate, 1f, 0));
        long at = b.partOf(start) == lv.part ? Math.max(0, b.startOf(start)) : 0;
        if (offsetIdx == start && b.partOf(start) == lv.part) { at += Math.max(0, offsetSamples); }
        offsetIdx = -1;
        clipOffset.put(start, b.partOf(start) == lv.part ? at - Math.max(0, b.startOf(start)) : 0L);
        srcOf.put(start, "Gemini as it arrives, " + (b.has(start) ? b.describe(start) : "from the start of its recording (not told apart yet)"));
        trail("Gemini plays as it arrives, from sentence " + start + String.format(java.util.Locale.US, " (%.1f s into its recording; %.1f s in so far)", at / 24000.0, lv.bytes / 2 / 24000.0));
        final long base = framesWritten - at;                    // (track frame of the recording's sample 0)
        marks.add(new long[]{framesWritten, start, 0});
        int nextMark = start + 1;
        long endAt = -1;
        boolean starved = false;
        Log.i("LoudbookTest", "gemini: playing as it arrives, from sentence " + start + " (" + lv.bytes / 2 / rate + " s in so far)");
        while (gen == myGen) {
            if (paused) { sleep(40); continue; }
            // sentences found since: each one's highlight goes in where it starts
            while (nextMark < lv.to && b.has(nextMark) && b.partOf(nextMark) == lv.part) {
                long s = b.startOf(nextMark);
                long len = Math.max(0, b.endOf(nextMark) - s);
                marks.add(new long[]{base + s, nextMark, len});
                srcOf.put(nextMark, "Gemini as it arrives, " + b.describe(nextMark) + (base + s < framesWritten ? String.format(java.util.Locale.US, " (found %.1f s after the voice got there)", (framesWritten - base - s) / (double) rate) : ""));
                Log.i("LoudbookTest", "clip " + nextMark + ": " + Math.round(len * 100.0 / rate) / 100.0 + " s, gemini (as it arrives)");
                nextMark++;
            }
            if (nextMark >= lv.to) endAt = b.endOf(lv.to - 1);                // (the end of this chapter's part)
            boolean done = lv.done;
            long have = lv.bytes / 2;
            long upTo;
            if (endAt >= 0) upTo = Math.min(have, endAt);
            else if (done) upTo = nextMark - 1 >= start && b.partOf(nextMark - 1) == lv.part ? b.endOf(nextMark - 1) : have;
            else upTo = have - rate / 2;                              // (half a second behind what's in: Google sends it in pieces)
            if (at < upTo) {
                int n = (int) Math.min(rate / 5, upTo - at);
                float[] x;
                try { x = new CloudVoice.Pcm(lv.f, 0, have * 2, rate).read(at, n); }
                catch (IOException e) { Log.w(TAG, "gemini live", e); return nextMark; }
                if (x.length == 0) { sleep(30); continue; }
                if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
                if (firstSoundFor != myGen) logFirstSound(myGen, true, false);
                int off = 0;
                while (off < x.length && gen == myGen) {
                    if (paused) { sleep(40); continue; }
                    int w = t.write(x, off, Math.min(2048, x.length - off), AudioTrack.WRITE_BLOCKING);
                    if (w < 0) return -1;
                    off += w; framesWritten += w; at += w; starved = false;
                    heard(ch, marks, myGen);
                }
                continue;
            }
            if (done || (endAt >= 0 && at >= endAt)) break;
            if (!starved && queuedSecs() < 0.05 && at > 0) { starved = true; Log.i("LoudbookTest", "gemini: waiting for Google's audio (" + at / rate + " s played)"); }
            heard(ch, marks, myGen);
            sleep(30);
        }
        if (gen != myGen) return -1;
        Log.i("LoudbookTest", "gemini: played as it arrived up to sentence " + (nextMark - 1));
        return nextMark;
    }

    /** Seconds of audio queued in the track and not heard yet. */
    private double queuedSecs() {
        AudioTrack t = track;
        if (t == null) return 0;
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        return Math.max(0, framesWritten - head) / (double) Math.max(1, t.getSampleRate());
    }

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
                if (kt != null && "kindle".equals(ch.site) && next >= ch.size() && !kindleEnded && splitIdx == ch.size() - 1) {
                    // Kindle: the page ends inside a sentence, and that sentence is queued whole: the
                    // page turns when the voice gets to the next page's words (splitCheck)
                    long until = System.currentTimeMillis() + 60_000;
                    while (gen == myGen && !splitTurned && splitIdx == ch.size() - 1 && System.currentTimeMillis() < until) { heard(ch, marks, myGen); sleep(20); }
                    if (gen != myGen) return;
                    if (!splitTurned && splitIdx == ch.size() - 1) { splitTurned = true; kindleMore(ch); }   // (never got there: turn anyway)
                    until = System.currentTimeMillis() + 10_000;
                    while (gen == myGen && next >= ch.size() && (kindleBusy || !kindleQueued) && System.currentTimeMillis() < until) { heard(ch, marks, myGen); sleep(40); }
                    if (!kindleBack(myGen, ch, marks)) return;
                }
                else if (kt != null && "kindle".equals(ch.site) && next >= ch.size() && !kindleEnded) {
                    // Kindle: everything on this page is queued. Turn the page only once its last
                    // word has been heard (the screen shows what's being read), then carry on
                    if (!drain(myGen, ch, marks)) return;
                    Log.i("LoudbookTest", "kindle: page read to its last word, turning");
                    kindleMore(ch);
                    long until = System.currentTimeMillis() + 10_000;
                    while (gen == myGen && next >= ch.size() && (kindleBusy || !kindleQueued) && System.currentTimeMillis() < until) { heard(ch, marks, myGen); sleep(40); }
                    if (!kindleBack(myGen, ch, marks)) return;
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
                synchronized (clips) { clips.keySet().removeIf(k -> k < keepFrom); fills.keySet().removeIf(k -> k < keepFrom); }
                if (f == null) return;
                Voice.Clip c = null;
                boolean gem = geminiReads(ch), byPhone = false;
                long waitFrom = System.currentTimeMillis();
                Future<Voice.Clip> fill = null;
                int liveNext = -1;
                while (gen == myGen) {
                    // Gemini's recording from this sentence is streaming in: play it as it arrives, no
                    // waiting for its sentences to be told apart (the highlight catches up as they are)
                    GeminiBook lb = gem && !filling ? book : null;
                    GeminiBook.Live lv = lb == null ? null : lb.liveAt(next);
                    if (lv != null && lv.bytes >= 72000) { liveNext = playLive(lb, lv, next, ch, myGen, marks); break; }   // (1.5 s in)
                    try { c = f.get(20, java.util.concurrent.TimeUnit.MILLISECONDS); break; }
                    catch (java.util.concurrent.TimeoutException te) { heard(ch, marks, myGen); }
                    catch (Exception e) { if (gen != myGen) return; Log.w(TAG, "sentence failed", e); c = null; break; }
                    // Gemini hasn't got this sentence yet (a chapter just opened, or Google is slow) and
                    // nothing is left playing: the phone's voice reads until Gemini catches up, rather
                    // than silence. Gemini takes over at the first sentence it has ready.
                    if (gem && fill == null && prefs.getBoolean("gemFill", false) && voice.ready() && queuedSecs() < 0.3
                        && System.currentTimeMillis() - waitFrom > (filling || beforeRequest(ch, next) ? 0 : 1500)) {
                        fill = fillFor(ch, next);
                        for (int k = 1; k <= 2; k++) fillFor(ch, next + k);
                        if (!filling) {
                            Log.i("LoudbookTest", "gemini: not ready at sentence " + next + "; the phone's voice reads meanwhile");
                            trail("Gemini not ready at sentence " + next + ": the phone's voice reads meanwhile");
                            main.post(() -> status("Gemini is getting ready; the phone's voice reads until it is\u2026", false));
                        }
                    }
                    if (fill != null && fill.isDone()) {
                        try { c = fill.get(); byPhone = true; } catch (Exception e) { c = null; }
                        if (c != null) break;
                        fill = null;
                    }
                }
                if (gen != myGen) return;
                if (liveNext >= 0) {
                    if (firstSoundFor != myGen) logFirstSound(myGen, true, false);
                    next = Math.max(next + 1, liveNext);
                    continue;
                }
                if (gem && c != null && !byPhone && phoneRead.contains(next)) byPhone = true;
                if (gem && c != null) {
                    if (filling && !byPhone) { Log.i("LoudbookTest", "gemini: ready again from sentence " + next); trail("Gemini takes over at sentence " + next); main.post(() -> status("", false)); }
                    filling = byPhone;
                }
                if (c != null && firstSoundFor != myGen) logFirstSound(myGen, gem, byPhone);
                if (c != null && offsetIdx >= 0) {
                    // (the highlight was moved by hand: on from where the voice was, not the sentence's start)
                    if (offsetIdx == next && gem && !byPhone && offsetSamples > 0 && offsetSamples < c.samples.length - c.rate / 10) {
                        c = new Voice.Clip(java.util.Arrays.copyOfRange(c.samples, (int) offsetSamples, c.samples.length), c.rate, c.madeAt, c.ms);
                        clipOffset.put(next, offsetSamples);
                    } else clipOffset.remove(next);
                    offsetIdx = -1;
                } else clipOffset.remove(next);
                if (c != null) {
                    GeminiBook bk = book;
                    if (gem && !byPhone && bk != null && !bk.has(next)) byPhone = true;      // (not Gemini's: it isn't in the recording)
                    srcOf.put(next, gem && !byPhone ? "Gemini, " + (bk != null ? bk.describe(next) : "") : gem ? "the phone's voice (Gemini not ready)" : "the phone's voice");
                    Log.i("LoudbookTest", "clip " + next + ": " + Math.round(c.seconds() * 100) / 100.0 + " s" + (!gem ? "" : byPhone ? ", phone's voice" : ", gemini"));
                    if (next == pos) main.post(() -> status("", false));
                    AudioTrack t = ensureTrack(c.rate);
                    current = c;
                    try {
                        SpeechTiming st = SpeechTiming.of(ch.chunks.get(next).text, ch.chunks.get(next).say, c.samples, c.rate);
                        timings.put(next, st);
                        Log.i("LoudbookTest", "timing " + next + ": speech " + st.speechStart * 1000 / c.rate + "-" + st.speechEnd * 1000 / c.rate + " ms of "
                            + c.samples.length * 1000L / c.rate + ", " + st.matched + " of " + SpeechTiming.breaks(ch.chunks.get(next).text).size() + " pauses matched");
                    } catch (Throwable e) { timings.remove(next); }
                    marks.add(new long[]{framesWritten, next, c.samples.length, 1});   // (1: a whole clip, timed by its sound)
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
        if (b != null && !filling && b.joined(i) && prefs.getBoolean("cloudOn", false)) return 0;   // Gemini's own pause is in the recording
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
    /** Kindle: the voice has got to the words on the next page (in the sentence over the page break): turn now. */
    private void splitCheck(Chapter ch, AudioTrack t) {
        int si = splitIdx;
        if (si < 0 || splitTurned || heardIdx != si || kt == null || !"kindle".equals(ch.site)) return;
        float f = heardFrac(t);
        float secs = heardLen / (float) Math.max(1, t.getSampleRate());
        float lead = secs > 0 ? Math.min(0.15f, 0.25f / secs) : 0f;              // (the turn takes a moment to show)
        if (f < splitFrac - lead) return;
        splitTurned = true;
        Log.i("LoudbookTest", "kindle: the voice is at the next page's words (" + Math.round(f * 100) + "% of sentence " + si + "), turning");
        kindleMore(ch);
    }

    private void heard(Chapter ch, java.util.ArrayDeque<long[]> marks, int myGen) {
        AudioTrack t = track;
        if (t != null && sleepAt > 0) {
            // sleep timer: fade out gently over the last fifteen seconds, rather than stop dead
            long left = sleepAt - System.currentTimeMillis();
            if (left < 15_000) { try { t.setVolume(Math.max(0.05f, left / 15_000f)); } catch (Exception ignored) { } }
        }
        if (t != null) splitCheck(ch, t);
        if (t == null || marks.isEmpty()) return;
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        int reached = -1;
        while (!marks.isEmpty() && head >= marks.peek()[0]) {
            long[] m = marks.poll();
            reached = (int) m[1];
            heardStart = m[0]; heardLen = m.length > 2 ? m[2] : 0; heardIdx = reached;
            SpeechTiming st = timings.get(reached);
            heardTiming = st != null && m.length > 3 && m[3] == 1 ? st : null;
        }
        if (reached < 0 || gen != myGen) return;
        final int here = reached;
        pos = here;
        if (here != trailHeard) {
            trailHeard = here;
            Chapter.Chunk tc = ch.chunks.get(here);
            String src = srcOf.get(here);
            trail("hearing " + here + " \"" + clip40(tc.text) + "\"" + (src != null ? ": " + src : ""));
        }
        { Chapter.Chunk cc = ch.chunks.get(here); String said = cc.text;
          Log.i("LoudbookTest", ("kindle".equals(ch.site) ? "kindle " : "") + "speaking " + here + ": " + (said.length() > 60 ? said.substring(0, 60) : said)
              + (cc.say.equals(cc.text) ? "" : "  [said: " + (cc.say.length() > 70 ? cc.say.substring(0, 70) : cc.say) + "]")); }
        if (cloudOn() && here != aheadAsked && !"kindle".equals(ch.site)) {
            aheadAsked = here;
            waiters.submit(() -> { try { recordAhead(ch, here); } catch (Throwable e) { Log.w(TAG, "record ahead", e); } return null; });
            // (the next chapter's text, for packing it in: the screen's browser loads it if the quiet one couldn't)
            if (here > ch.size() / 2 && ch.nextUrl != null && autoNext && nextCh == null && !ch.nextUrl.equals(prefetchAsked)) {
                prefetchAsked = ch.nextUrl;
                main.post(() -> { if (listener != null) listener.onPrefetch(ch.nextUrl); });
            }
        }
        if (here >= ch.size() - 4 && ch.nextUrl != null && autoNext && nextCh != null) premakeNext();
        if (here > 0 && here % 15 == 0 && ch.nextUrl != null && autoNext && nextCh == null) main.post(() -> preloadAgain(ch));
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
            if (c.size() > 0 && savedPos(last) >= c.size()) {                // that chapter was read to the end
                if (c.nextUrl != null) { resumeNext(c); return; }
                setChapter(c, c.size() - 1, false);
                if (listener != null) listener.onOpened(c);
                status(caughtUpLine(c), false);
                return;
            }
            setChapter(c, Math.min(savedPos(last), Math.max(0, c.size() - 1)), true);
            if (listener != null) listener.onOpened(c);
        }));
    }

    /** The last chapter was finished and there's one after it now (a new chapter, or reading stopped at the end): read that. */
    private void resumeNext(Chapter done) {
        resuming = true;
        status("On to the next chapter\u2026", false);
        fetcher().get(done.nextUrl, (n, err) -> {
            resuming = false;
            if (chapter != null) return;
            if (n == null || n.size() == 0) {                                // can't load it: stay on the last one
                setChapter(done, done.size() - 1, false);
                if (listener != null) listener.onOpened(done);
                status("Couldn't open the next chapter (" + err + "). Try again in a moment.", true);
                return;
            }
            Log.i("LoudbookTest", "resumed with the next chapter: " + n.url);
            setChapter(n, Math.min(savedPos(n.url), n.size() - 1), true);
            if (listener != null) listener.onOpened(n);
        });
    }
    static String caughtUpLine(Chapter c) {
        String f = c.fiction == null || c.fiction.trim().isEmpty() ? "this story" : c.fiction.trim();
        return "You're caught up on " + f + ". Loudbook will tell you when the next chapter is out.";
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

    // the next chapter didn't load ahead (no signal, or the site was slow): try again now and then
    // while this one plays, so the page turn doesn't wait for it (at most 3 more tries a chapter)
    private String againFor;
    private int againTries;
    private void preloadAgain(Chapter ch) {
        if (chapter != ch || nextCh != null || ch.nextUrl == null) return;
        if (!ch.nextUrl.equals(againFor)) { againFor = ch.nextUrl; againTries = 0; }
        if (againTries >= 3 || fetcher().busyWith(ch.nextUrl)) return;
        againTries++;
        Log.i("LoudbookTest", "next chapter ahead: trying again (" + againTries + ")");
        preloadNext(ch);
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
    // ---------------------------------------------------------------- what happened, step by step
    /**
     * Since play was last pressed: which sentence was heard when, in which voice, and from where
     * in Gemini's recording (with the words the recogniser heard there), the requests, and any
     * highlight moved by hand. Settings > Gemini timing shows it, so a highlight that went out of
     * step can be traced. The first lines after play are kept, then the latest.
     */
    private final java.util.List<String> trailHead = new java.util.ArrayList<>();
    private final java.util.ArrayDeque<String> trailTail = new java.util.ArrayDeque<>();
    private volatile long trailFrom;
    private volatile int trailHeard = -1;
    /** Where each queued sentence's sound comes from, for the trail. */
    private final java.util.Map<Integer, String> srcOf = new java.util.concurrent.ConcurrentHashMap<>();
    /** How far into its recording each queued Gemini sentence's sound starts after its own start (samples; after a move by hand). */
    private final java.util.Map<Integer, Long> clipOffset = new java.util.concurrent.ConcurrentHashMap<>();

    void trail(String what) {
        long t = trailFrom == 0 ? 0 : System.currentTimeMillis() - trailFrom;
        String line = String.format(java.util.Locale.US, "%d:%04.1f  %s", t / 60000, (t % 60000) / 1000.0, what);
        synchronized (trailHead) {
            if (trailHead.size() < 45) trailHead.add(line);
            else { trailTail.add(line); while (trailTail.size() > 60) trailTail.poll(); }
        }
        Log.i("LoudbookTest", "trail: " + what);
    }

    private void trailStart(String what) {
        synchronized (trailHead) { trailHead.clear(); trailTail.clear(); }
        trailFrom = System.currentTimeMillis();
        trailHeard = -1;
        trail(what);
    }

    String trailText() {
        synchronized (trailHead) {
            StringBuilder sb = new StringBuilder(String.join("\n", trailHead));
            if (!trailTail.isEmpty()) sb.append("\n  ...\n").append(String.join("\n", trailTail));
            return sb.toString();
        }
    }

    private static String clip40(String t) { t = t.trim(); return t.length() > 40 ? t.substring(0, 40) + "..." : t; }

    /** Whether the voice now is Gemini's, on a web chapter (the highlight can be moved by hand). */
    boolean geminiNow() {
        Chapter ch = chapter;
        int i = heardIdx;
        String src = i < 0 ? null : srcOf.get(i);
        return playing && ch != null && geminiReads(ch) && src != null && src.startsWith("Gemini");
    }

    /** A sentence to start at, part way in (samples), after the highlight was moved by hand. */
    private volatile int offsetIdx = -1;
    private volatile long offsetSamples;

    /**
     * The highlight is out of step with Gemini's voice: by = +1, the voice is really a sentence
     * further on than the highlight; -1, a sentence back. The recording is re-cut there to match
     * (and kept so), and reading carries on from the same place in the audio with the highlight
     * on the right sentence. With any other voice it's just a sentence on or back.
     */
    void nudge(int by) {
        Chapter ch = chapter;
        GeminiBook b = book;
        AudioTrack t = track;
        int i = heardIdx;
        if (ch == null) return;
        if (i < 0) i = pos;
        int j = Math.max(0, Math.min(ch.size() - 1, i + by));
        if (j == i) return;
        if (b == null || t == null || !geminiNow() || !b.has(i) || !b.has(j)) {
            trail("you moved the highlight " + (by > 0 ? "on" : "back") + " from sentence " + i + " (not Gemini's recording: just a sentence " + (by > 0 ? "on" : "back") + ")");
            seek(j);
            return;
        }
        long head = t.getPlaybackHeadPosition() & 0xffffffffL;
        long into = Math.max(0, head - heardStart);
        long x = b.startOf(i) + into + clipOffset.getOrDefault(i, 0L);
        final int fi = i, fj = j;
        trail(String.format(java.util.Locale.US, "you moved the highlight %s: from sentence %d, %.1f s into it (%.1f s into Gemini's recording; was: %s)",
            by > 0 ? "on" : "back", i, into / 24000.0, x / 24000.0, b.describe(i)));
        waiters.submit(() -> {
            long ns;
            try { ns = b.resync(fi, fj, x); } catch (Throwable e) { Log.w(TAG, "resync", e); ns = -1; }
            final long start = ns;
            main.post(() -> {
                if (chapter != ch) return;
                synchronized (clips) {
                    int from = Math.min(fi, fj);
                    clips.entrySet().removeIf(e -> { if (e.getKey() >= from) { e.getValue().cancel(false); return true; } return false; });
                }
                if (start >= 0 && x > start) { offsetIdx = fj; offsetSamples = x - start; }
                trail("  now: sentence " + fj + " " + b.describe(fj) + (start >= 0 ? String.format(java.util.Locale.US, "; reading on from %.1f s into it", Math.max(0, x - start) / 24000.0) : " (couldn't re-cut: reading from its start)"));
                seek(fj);
            });
            return null;
        });
    }

    void saveProgress() {
        Chapter ch = chapter;
        if (ch == null || "kindle".equals(ch.site) || "text".equals(ch.site)) return;   // Kindle keeps its own place; shared text has none
        prefs.edit().putInt("pos:" + ch.url, pos).putString("gemTrail", trailText())
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
            String newUrl = null;                         // (and a new chapter found after it is still new)
            for (int i = 0; i < old.length(); i++) if (old.getJSONObject(i).optString("url").equals(ch.url)) {
                caught = old.getJSONObject(i).optBoolean("caughtUp");
                newUrl = old.getJSONObject(i).optString("newUrl", null);
            }
            org.json.JSONObject entry = new org.json.JSONObject().put("fiction", ch.fiction).put("title", ch.title).put("url", ch.url).put("site", ch.site)
                .put("pct", ch.size() > 0 ? Math.round(100f * at / ch.size()) : 0).put("at", System.currentTimeMillis()).put("caughtUp", caught);
            if (newUrl != null && !newUrl.isEmpty()) entry.put("newUrl", newUrl);
            now.put(entry);
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
