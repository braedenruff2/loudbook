package com.loudbook.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps Loudbook up to date from its GitHub releases. Each push builds a new release; the app
 * notices it, downloads the APK in the background and installs it while you're not listening.
 * On Android 12+ that needs no tap once "Install unknown apps" is allowed for Loudbook.
 */
final class Updater {
    static final String TAG = "Loudbook";
    static final String PREFS = "lb-update";
    private static final long CHECK_EVERY_MS = 20 * 60 * 1000L;

    interface Listener { void onUpdateState(); }

    static volatile boolean busy;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled() { return BuildInfo.REPO.length() > 0; }

    /** The version waiting to be installed (0 if none). */
    static int readyCode(Context c) {
        int code = prefs(c).getInt("readyCode", 0);
        if (code <= BuildInfo.CODE || !apk(c, code).exists()) return 0;
        return code;
    }
    static String readyName(Context c) { return prefs(c).getString("readyName", ""); }
    static String lastError(Context c) { return prefs(c).getString("error", ""); }
    static File apk(Context c, int code) { return new File(c.getCacheDir(), "update-" + code + ".apk"); }

    static boolean mayInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** Looks for a newer release and downloads it. Off the main thread; quiet on failure. */
    static void check(final Context app, boolean force, final Listener l) {
        if (!enabled() || busy) return;
        final SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("checked", 0) < CHECK_EVERY_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                JSONObject v = new JSONObject(Net.text(BuildInfo.RELEASES + "latest/download/version.json"));
                p.edit().putLong("checked", System.currentTimeMillis()).putString("error", "").apply();
                int code = v.getInt("code");
                String name = v.optString("name", String.valueOf(code));
                cleanOld(app, code);
                if (code > BuildInfo.CODE) {
                    File f = apk(app, code);
                    Net.download(BuildInfo.RELEASES + "download/v" + code + "/Loudbook.apk", f, v.optLong("size", 0), null);
                    PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                    if (pi == null || !app.getPackageName().equals(pi.packageName)) { f.delete(); throw new java.io.IOException("the downloaded update didn't check out"); }
                    p.edit().putInt("readyCode", code).putString("readyName", name).apply();
                    Log.i(TAG, "update " + name + " downloaded");
                }
            } catch (Throwable t) {
                Log.w(TAG, "update check failed", t);
                p.edit().putString("error", String.valueOf(t.getMessage())).apply();
            } finally {
                busy = false;
                if (l != null) l.onUpdateState();
            }
        }, "lb-update").start();
    }

    private static void cleanOld(Context c, int keep) {
        File[] fs = c.getCacheDir().listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.getName().startsWith("update-") && !f.getName().equals("update-" + keep + ".apk")
            && !f.getName().equals("update-" + keep + ".apk.part")) f.delete();
    }

    /** Hands the downloaded APK to Android. The app restarts as part of it. */
    static boolean install(Context c) {
        int code = readyCode(c);
        if (code == 0 || !mayInstall(c)) return false;
        File f = apk(c, code);
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            sp.setSize(f.length());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("Loudbook.apk", 0, f.length())) {
                    byte[] buf = new byte[1 << 16]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(c, Result.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
            }
            prefs(c).edit().putString("error", "").apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "update install failed", t);
            prefs(c).edit().putString("error", "install: " + t.getMessage()).apply();
            return false;
        }
    }

    /** Android reports back here on how the install went. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Throwable t) { Log.w(TAG, "couldn't show the update prompt", t); }
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                prefs(c).edit().putString("error", "install: " + (msg == null ? "status " + st : msg)).apply();
                // a failed APK is dropped so the next check downloads it fresh
                if (st != PackageInstaller.STATUS_FAILURE_ABORTED) { int code = readyCode(c); if (code > 0) apk(c, code).delete(); }
            }
        }
    }

    /** Runs in the new version right after an update: say so, with a tap to open. */
    public static final class Replaced extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
            prefs(c).edit().remove("readyCode").remove("readyName").putString("error", "").apply();
            cleanOld(c, -1);
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26)
                    nm.createNotificationChannel(new NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_LOW));
                Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "updates") : new Notification.Builder(c);
                nm.notify(7, b.setSmallIcon(R.drawable.ic_stat).setContentTitle("Loudbook updated")
                    .setContentText("Now on version " + BuildInfo.VERSION + ". Tap to open.")
                    .setContentIntent(pi).setAutoCancel(true).build());
            } catch (Throwable t) { Log.w(TAG, "update notice failed", t); }
        }
    }
}
