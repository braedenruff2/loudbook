package com.loudbook.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Every few hours (with a network), looks at the stories you've caught up on (read to their latest
 * chapter) and tells you when one has a new chapter: the latest chapter's page is opened again in
 * a browser with no screen, and if it now has a "next" link, a notification says so.
 */
public class NewChapters extends JobService {
    static final int JOB = 4711;
    static final String TAG = "Loudbook";

    /** Sets up the check (once; it carries on by itself, also after a restart). */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB) != null) return;
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, NewChapters.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 3600_000L)
            .setPersisted(true)
            .build();
        try { js.schedule(job); } catch (Exception e) { Log.w(TAG, "new-chapter check", e); }
    }

    /** The story's latest chapter was just read to the end: watch it for a next one. */
    static void caughtUp(Context c, Chapter ch) { mark(c, ch.url, true); }
    private static void mark(Context c, String url, boolean on) {
        SharedPreferences p = Voice.prefs(c);
        try {
            JSONArray a = new JSONArray(p.getString("shelf", "[]"));
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optString("url").equals(url)) a.getJSONObject(i).put("caughtUp", on);
            p.edit().putString("shelf", a.toString()).apply();
        } catch (Exception e) { Log.w(TAG, "shelf", e); }
    }

    private ChapterFetcher fetcher;

    @Override public boolean onStartJob(JobParameters params) {
        SharedPreferences p = Voice.prefs(this);
        if (!p.getBoolean("newChapterAlerts", true)) return false;
        List<JSONObject> watch = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("shelf", "[]"));
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optBoolean("caughtUp")) watch.add(a.getJSONObject(i));
        } catch (Exception e) { return false; }
        if (watch.isEmpty()) return false;
        fetcher = new ChapterFetcher(this);
        next(watch, 0, params);
        return true;
    }

    private void next(List<JSONObject> watch, int i, JobParameters params) {
        if (i >= watch.size()) { fetcher.destroy(); jobFinished(params, false); return; }
        JSONObject s = watch.get(i);
        String url = s.optString("url");
        fetcher.fresh(url, (ch, err) -> {
            if (ch != null && ch.nextUrl != null) {
                Log.i("LoudbookTest", "new chapter of " + s.optString("fiction") + ": " + ch.nextUrl);
                notify(s.optString("fiction"), ch.nextUrl, i);
                mark(this, url, false);
            } else Log.i("LoudbookTest", "no new chapter of " + s.optString("fiction") + (err != null ? " (" + err + ")" : ""));
            next(watch, i + 1, params);
        });
    }

    private void notify(String fiction, String url, int n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("new-chapters", "New chapters", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 100 + n, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, "new-chapters")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("New chapter: " + fiction)
            .setContentText("Tap to open it in Loudbook.")
            .setContentIntent(pi).setAutoCancel(true).build();
        try { nm.notify(("new:" + fiction).hashCode(), note); } catch (Exception e) { Log.w(TAG, "notify", e); }
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (fetcher != null) fetcher.destroy();
        return true;                                   // try again later
    }
}
