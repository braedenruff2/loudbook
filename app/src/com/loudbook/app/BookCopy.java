package com.loudbook.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies the audiobook chapters the PC has recorded (pc-server's "Loudbook audiobooks" folder) to the phone, so they play
 * with no connection (in the car): the MP3s into the phone's Audiobooks folder, where any audiobook app and Android Auto
 * find them, and each chapter's sentence timings (.json) into Loudbook's own files. Only new or changed chapters are copied.
 */
final class BookCopy {
    interface Progress { void at(String line); }

    static final String TAG = "Loudbook";

    /** Returns a line saying what was copied. Runs on a background thread. */
    static String copyAll(Context ctx, PcLink l, Progress p) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("lb", Context.MODE_PRIVATE);
        Set<String> done = new HashSet<>(prefs.getStringSet("booksCopied", new HashSet<>()));
        List<PcLink.BookFile> files = l.books(15000);
        int todo = 0, copied = 0, mp3s = 0;
        for (PcLink.BookFile f : files) if (!done.contains(f.path + "|" + f.bytes)) todo++;
        for (PcLink.BookFile f : files) if (f.path.endsWith(".mp3")) mp3s++;
        if (todo == 0) return mp3s == 0 ? "Your PC hasn't finished any audiobook chapters yet." : "All " + mp3s + " recorded chapters are on this phone already.";
        java.util.Set<String> books = new java.util.TreeSet<>();
        for (PcLink.BookFile f : files) {
            String key = f.path + "|" + f.bytes;
            if (done.contains(key)) continue;
            int slash = f.path.indexOf('/');
            if (slash <= 0) continue;
            String book = f.path.substring(0, slash), name = f.path.substring(slash + 1);
            p.at("Copying " + (copied + 1) + " of " + todo + ": " + name);
            if (name.endsWith(".mp3")) saveAudio(ctx, l, f.path, book, name);
            else {
                File dir = new File(ctx.getExternalFilesDir(null), "books/" + book);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't make " + dir);
                File part = new File(dir, name + ".part");
                try (OutputStream out = new FileOutputStream(part)) { l.download(f.path, out, 60000); }
                if (!part.renameTo(new File(dir, name))) throw new java.io.IOException("can't save " + name);
            }
            done.add(key);
            prefs.edit().putStringSet("booksCopied", new HashSet<>(done)).apply();   // (kept as it goes: a dropped Wi-Fi keeps what came)
            copied++;
            books.add(book);
        }
        String where = Build.VERSION.SDK_INT >= 29 ? "the phone's Audiobooks folder" : "Loudbook's files";
        return "Copied " + copied + " file" + (copied == 1 ? "" : "s") + " (" + String.join(", ", books) + ") to " + where
            + ". Play them in any audiobook app or in the car, with no connection.";
    }

    private static void saveAudio(Context ctx, PcLink l, String path, String book, String name) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {                         // (no shared Audiobooks folder without a permission: Loudbook's own)
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't make " + dir);
            File part = new File(dir, name + ".part");
            try (OutputStream out = new FileOutputStream(part)) { l.download(path, out, 60000); }
            if (!part.renameTo(new File(dir, name))) throw new java.io.IOException("can't save " + name);
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        Uri coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = Environment.DIRECTORY_AUDIOBOOKS + "/" + book + "/";
        // (copied before and changed since: the old one goes first, so the folder doesn't get "name (1).mp3")
        try (android.database.Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{rel, name}, null)) {
            while (c != null && c.moveToNext()) cr.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null);
        } catch (Exception e) { Log.w(TAG, "audiobook: replacing " + name, e); }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        v.put(MediaStore.Audio.Media.IS_AUDIOBOOK, 1);
        Uri u = cr.insert(coll, v);
        if (u == null) throw new java.io.IOException("the phone wouldn't make " + name);
        try {
            try (OutputStream out = cr.openOutputStream(u)) {
                if (out == null) throw new java.io.IOException("can't write " + name);
                l.download(path, out, 60000);
            }
            ContentValues ok = new ContentValues();
            ok.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, ok, null, null);
        } catch (Exception e) {
            cr.delete(u, null, null);                             // (no half chapter left behind)
            throw e;
        }
    }
}
