package com.loudbook.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

/**
 * Plays an audiobook the PC recorded (copied to the phone by BookCopy): one MP3 per chapter, on to the next chapter at the
 * end, with the screen off, from the notification, headphones and the car. The book page (web/book.html) shows the chapter's
 * text and asks state() a few times a second to light the sentence being said (each chapter's .json has every sentence's
 * start and end). Where each book was left is kept per book ("bookAt:<book>" = chapter file + seconds).
 */
public final class BookPlayer extends Service {
    static final String TAG = "Loudbook";
    static final String ACT_TOGGLE = "com.loudbook.app.BOOK_TOGGLE", ACT_BACK = "com.loudbook.app.BOOK_BACK",
        ACT_FWD = "com.loudbook.app.BOOK_FWD", ACT_STOP = "com.loudbook.app.BOOK_STOP";
    static final int NOTIF_ID = 7;
    /** How far the back / forward buttons jump. */
    static final int JUMP_MS = 15000;

    private static volatile BookPlayer me;
    /** A play asked for before the service was up. */
    private static volatile Runnable pending;
    /** Last thing that went wrong (for the page), cleared by the next play. */
    private static volatile String error = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile MediaPlayer mp;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private SharedPreferences prefs;
    // (volatile: state() reads them on the page's thread)
    private volatile boolean prepared, playing;
    private boolean pausedForFocus, foreground;
    private volatile String book = "";
    private volatile List<Books.Chapter> chapters;
    private volatile int ch = -1;
    private float speed = 1f;
    /** Where to start once the chapter is ready (ms). */
    private volatile int startAt;

    // ---------------------------------------------------------------- called from the app (any thread)

    /** Plays chapter index ch of book from sec seconds (sec < 0: where it was left, if it's that chapter). */
    static void play(Context ctx, String book, int chapter, double sec) {
        Runnable r = () -> { BookPlayer p = me; if (p != null) p.start(book, chapter, sec); };
        if (me != null) { new Handler(Looper.getMainLooper()).post(r); return; }
        pending = r;
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, BookPlayer.class);
        try { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i); }
        catch (Exception e) { Log.w(TAG, "book: couldn't start the player", e); error = "Couldn't start playing (" + e.getMessage() + ")."; pending = null; }
    }
    static void toggle() { run(p -> { if (p.playing) p.pause(); else p.resume(); }); }
    static void pauseIfPlaying() { run(p -> { if (p.playing) p.pause(); }); }
    static void seekMs(long ms) { run(p -> p.seek((int) ms)); }
    static void jump(int ms) { run(p -> p.seek(p.posMs() + ms)); }
    static void setSpeed(float s) { run(p -> p.speed(s)); }
    static boolean isPlaying() { BookPlayer p = me; return p != null && p.playing; }

    private interface Op { void on(BookPlayer p); }
    private static void run(Op op) { BookPlayer p = me; if (p != null) p.main.post(() -> op.on(p)); }

    /** {book, ch (index), file, title, ms, dur, playing, speed, error} for the book page. */
    static String state(Context ctx) {
        JSONObject o = new JSONObject();
        BookPlayer p = me;
        try {
            o.put("error", error);
            o.put("speed", ctx.getSharedPreferences("lb", Context.MODE_PRIVATE).getFloat("bookSpeed", 1f));
            List<Books.Chapter> list = p == null ? null : p.chapters;
            int k = p == null ? -1 : p.ch;
            if (list == null || k < 0 || k >= list.size()) return o.put("playing", false).toString();
            Books.Chapter c = list.get(k);
            o.put("book", p.book).put("ch", k).put("file", c.json).put("title", c.title).put("playing", p.playing)
                .put("ms", p.posMs()).put("dur", p.durMs(c))
                .put("ready", p.prepared);
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---------------------------------------------------------------- service

    @Override public void onCreate() {
        super.onCreate();
        me = this;
        prefs = getSharedPreferences("lb", MODE_PRIVATE);
        speed = prefs.getFloat("bookSpeed", 1f);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loudbook:book");
        wake.setReferenceCounted(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel nc = new NotificationChannel("book", "Audiobook", NotificationManager.IMPORTANCE_LOW);
        nc.setShowBadge(false);
        nm.createNotificationChannel(nc);
        session = new MediaSession(this, "Loudbook audiobook");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { if (posMs() > 5000) seek(0); else prev(); }
            @Override public void onFastForward() { seek(posMs() + JUMP_MS); }
            @Override public void onRewind() { seek(posMs() - JUMP_MS); }
            @Override public void onSeekTo(long ms) { seek((int) ms); }
        });
        session.setActive(true);
        android.content.IntentFilter noisy = new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, noisy, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, noisy);
        goForeground(true);                               // (started with startForegroundService: must say so within 5 s)
        Runnable r = pending; pending = null;
        if (r != null) r.run();                           // (now, not posted: onStartCommand comes next and stops an empty player)
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (playing) pause(); }   // (headphones out: don't carry on aloud)
    };

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (ACT_TOGGLE.equals(a)) { if (playing) pause(); else { if (chapters == null) restore(); resume(); } }
        else if (ACT_BACK.equals(a)) seek(posMs() - JUMP_MS);
        else if (ACT_FWD.equals(a)) seek(posMs() + JUMP_MS);
        else if (ACT_STOP.equals(a)) { pause(); stopSelfNow(); }
        else if (chapters == null && !restore()) stopSelfNow();   // (restarted by Android with nothing to play)
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        save();
        if (me == this) me = null;
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        release();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        session.release();
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private void stopSelfNow() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    // ---------------------------------------------------------------- playing

    /** The book listened to last, where it was left (the service was stopped while paused, and Play pressed in the notification). */
    private boolean restore() {
        String b = prefs.getString("bookLast", "");
        if (b.isEmpty()) return false;
        List<Books.Chapter> list = Books.chapters(this, b);
        Books.Spot at = Books.spot(prefs, b);
        int k = -1;
        for (int i = 0; at != null && i < list.size(); i++) if (list.get(i).json.equals(at.file)) k = i;
        if (k < 0) return false;
        book = b; chapters = list; ch = k;
        return true;
    }

    private void start(String b, int chapter, double sec) {
        error = "";
        List<Books.Chapter> list = Books.chapters(this, b);
        if (chapter < 0 || chapter >= list.size()) { error = "That chapter isn't on this phone."; return; }
        if (!b.equals(book)) save();
        book = b; chapters = list; ch = chapter;
        prefs.edit().putString("bookLast", b).apply();
        int ms = (int) Math.round(sec * 1000);
        if (sec < 0) {                                      // (where it was left, if that's this chapter)
            Books.Spot at = Books.spot(prefs, b);
            ms = at != null && at.file.equals(list.get(chapter).json) ? (int) (at.sec * 1000) : 0;
        }
        open(ms, true);
    }

    /** Opens chapter ch at ms; plays when ready if go. */
    private void open(int ms, boolean go) {
        release();
        Books.Chapter c = chapters.get(ch);
        Uri u = Books.audio(this, book, c);
        if (u == null) {
            error = "The recording of \"" + c.title + "\" isn't on this phone. Tap “Get chapters from my PC”.";
            playing = false; update();
            return;
        }
        startAt = ms;
        prepared = false;
        mp = new MediaPlayer();
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        final MediaPlayer mine = mp;
        mp.setOnPreparedListener(m -> {
            if (m != mp) return;
            prepared = true;
            int d = m.getDuration();
            if (startAt < 0) startAt = Math.max(0, d + startAt);       // (from the end: back over a chapter's start)
            if (startAt > 0 && startAt < d - 500) m.seekTo(startAt);
            applySpeed();
            if (go) resume(); else { playing = false; update(); }
        });
        mp.setOnCompletionListener(m -> { if (m == mp) { Log.i("LoudbookTest", "book: chapter " + (ch + 1) + " ended"); next(); } });
        mp.setOnErrorListener((m, what, extra) -> {
            if (m != mp) return true;
            Log.w(TAG, "book: player error " + what + "/" + extra + " in " + c.mp3);
            error = "Couldn't play \"" + c.title + "\" (error " + what + "/" + extra + ").";
            playing = false; prepared = false; update();
            return true;
        });
        try { mp.setDataSource(this, u); mp.prepareAsync(); }
        catch (Exception e) {
            Log.w(TAG, "book: opening " + c.mp3, e);
            error = "Couldn't open \"" + c.title + "\" (" + e.getMessage() + ").";
            if (mp == mine) { mine.release(); mp = null; }
            playing = false; update();
            return;
        }
        playing = go;                                     // (shown as playing while it opens)
        update();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
        prepared = false;
    }

    private void resume() {
        if (chapters == null) return;
        if (mp == null) { open(Books.spotMs(prefs, book, chapters.get(ch)), true); return; }
        if (!prepared) { playing = true; update(); return; }        // (starts when ready)
        if (!requestFocus()) { error = "Another app is using the sound."; playing = false; update(); return; }
        applySpeed();
        try { mp.start(); } catch (Exception e) { Log.w(TAG, "book: start", e); }
        playing = true; pausedForFocus = false;
        if (!wake.isHeld()) wake.acquire(6 * 3600 * 1000L);
        Log.i("LoudbookTest", "book: playing chapter " + (ch + 1) + " at " + posMs() + " ms");
        update();
        tick();
    }

    private void pause() {
        if (mp != null && prepared) { try { mp.pause(); } catch (Exception ignored) { } }
        playing = false;
        save();
        if (wake.isHeld()) wake.release();
        update();
    }

    private void seek(int ms) {
        if (mp == null || !prepared) { startAt = Math.max(0, ms); return; }
        int d = mp.getDuration();
        if (ms >= d - 300) { next(); return; }            // (forward past the end: the next chapter)
        if (ms < 0) {
            // (back past the start: the end of the chapter before)
            if (ch > 0 && posMs() < 2000) { ch--; open(-JUMP_MS, playing); return; }
            ms = 0;
        }
        mp.seekTo(ms);
        save();
        update();
    }

    private void next() {
        if (chapters == null) return;
        if (ch + 1 >= chapters.size()) {
            // (the last chapter on the phone: the PC may have finished more since, copied next time)
            chapters = Books.chapters(this, book);
            if (ch + 1 >= chapters.size()) { playing = false; error = "That's the last chapter on this phone."; release(); save(chapters.size() - 1, 0); update(); return; }
        }
        ch++;
        open(0, true);                                     // (on to the next chapter: plays)
    }

    private void prev() {
        if (chapters == null || ch <= 0) { seek(0); return; }
        ch--;
        open(0, playing);
    }

    private void speed(float s) {
        speed = Math.max(0.5f, Math.min(3f, s));
        prefs.edit().putFloat("bookSpeed", speed).apply();
        applySpeed();
        update();
    }

    private void applySpeed() {
        if (mp == null || !prepared || Build.VERSION.SDK_INT < 23) return;
        try {
            boolean was = mp.isPlaying();
            PlaybackParams pp = mp.getPlaybackParams();
            mp.setPlaybackParams(pp.setSpeed(speed));
            if (!was && mp.isPlaying()) mp.pause();       // (setting the speed starts a paused player)
        } catch (Exception e) { Log.w(TAG, "book: speed", e); }
    }

    /** (also asked from the page's thread: the player can be released meanwhile) */
    int posMs() {
        MediaPlayer m = mp;
        if (m == null || !prepared) return Math.max(0, startAt);
        try { return m.getCurrentPosition(); } catch (Exception e) { return Math.max(0, startAt); }
    }
    private long durMs(Books.Chapter c) {
        MediaPlayer m = mp;
        try { if (m != null && prepared) return m.getDuration(); } catch (Exception ignored) { }
        return (long) (c.seconds * 1000);
    }

    /** Saved now and then while playing, so a killed app comes back near where it was. */
    private void tick() {
        main.removeCallbacks(ticker);
        if (playing) main.postDelayed(ticker, 5000);
    }
    private final Runnable ticker = () -> {
        save(); updateSession(); tick();
    };

    private void save() { if (chapters != null && ch >= 0 && ch < chapters.size()) save(ch, posMs()); }
    private void save(int c, int ms) {
        if (chapters == null || c < 0 || c >= chapters.size() || book.isEmpty()) return;
        Books.saveSpot(prefs, book, chapters.get(c).json, ms / 1000.0);
    }

    private boolean requestFocus() {
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change -> main.post(() -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) { pausedForFocus = false; if (playing) pause(); }
                else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (playing) { pause(); pausedForFocus = true; }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedForFocus) { pausedForFocus = false; resume(); }
            })).build();
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    // ---------------------------------------------------------------- notification, lock screen, car

    private void update() { updateSession(); goForeground(false); }

    private void updateSession() {
        PlaybackState.Builder s = new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, posMs(), playing ? speed : 0f);
        session.setPlaybackState(s.build());
        if (chapters != null && ch >= 0 && ch < chapters.size()) {
            Books.Chapter c = chapters.get(ch);
            session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, c.title).putString(MediaMetadata.METADATA_KEY_ALBUM, book)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, book)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, prepared && mp != null ? mp.getDuration() : (long) (c.seconds * 1000))
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, ch + 1).putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, chapters.size()).build());
        }
    }

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, BookPlayer.class).setAction(act);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openBooks", true);
        PendingIntent content = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Books.Chapter c = chapters != null && ch >= 0 && ch < chapters.size() ? chapters.get(ch) : null;
        return new Notification.Builder(this, "book")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(c != null ? c.title : "Audiobook")
            .setContentText(book)
            .setContentIntent(content)
            .setOngoing(playing)
            .setDeleteIntent(action(ACT_STOP, 14))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_back), "Back 15 seconds", action(ACT_BACK, 11)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Pause" : "Play", action(ACT_TOGGLE, 12)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_fwd), "Forward 15 seconds", action(ACT_FWD, 13)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build();
    }

    /** Foreground while playing (or when it must be, just started); paused, the notification stays but can be swiped away.
     *  (Not foreground again on every change while paused: from the background Android 12+ refuses that.) */
    private void goForeground(boolean must) {
        Notification n = notification();
        try {
            if (playing || must) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTIF_ID, n);
                foreground = true;
            }
            if (!playing) {
                // (paused: the notification stays, to play again, but can be swiped away; the service may be stopped)
                stopForeground(STOP_FOREGROUND_DETACH);
                foreground = false;
                getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
            }
        } catch (Exception e) { Log.w(TAG, "book: foreground refused", e); }
    }
}
