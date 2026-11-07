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
        List<PcLink.BookFile> files = new java.util.ArrayList<>();
        // (deleted on the phone (Books.delete): not copied again, unless the PC makes the file again)
        Set<String> deleted = prefs.getStringSet("booksDeleted", new HashSet<>());
        for (PcLink.BookFile f : l.books(15000)) if (!deleted.contains(f.path + "|" + f.bytes)) files.add(f);
        // (copied before but gone from the phone since, deleted in a files app or with Loudbook's data: copied again. It said
        //  "all on this phone already" for good)
        Set<String> gone = missing(ctx, files, done);
        for (String g : gone) done.remove(g);
        if (!gone.isEmpty()) Log.i(TAG, "audiobook: " + gone.size() + " copied files are gone from the phone, copying them again");
        int todo = 0, copied = 0, mp3s = 0;
        files = chapterFiles(files);                         // (the Kindle books (EPUB) are got one by one: sendToKindle)
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
            if (name.endsWith(".mp3")) saveAudio(ctx, l, f, book, name);
            else {
                File dir = new File(ctx.getExternalFilesDir(null), "books/" + book);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't make " + dir);
                File part = new File(dir, name + ".part");
                try (OutputStream out = new FileOutputStream(part)) { whole(l.download(f.path, out, 60000), f, name); }
                File to = new File(dir, name);
                if (to.exists() && !to.delete() || !part.renameTo(to)) throw new java.io.IOException("can't save " + name);
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

    /** Only the chapters (MP3 and its .json). */
    static List<PcLink.BookFile> chapterFiles(List<PcLink.BookFile> files) {
        List<PcLink.BookFile> out = new java.util.ArrayList<>();
        for (PcLink.BookFile f : files) if (f.path.endsWith(".mp3") || f.path.endsWith(".json")) out.add(f);
        return out;
    }

    /** The Kindle books (EPUB, from the Whizzer Library download) the PC offers beside its audiobooks. */
    static List<PcLink.BookFile> kindleFiles(List<PcLink.BookFile> files) {
        List<PcLink.BookFile> out = new java.util.ArrayList<>();
        for (PcLink.BookFile f : files) if (f.path.toLowerCase(java.util.Locale.ROOT).endsWith(".epub") && f.path.indexOf('/') > 0) out.add(f);
        return out;
    }

    static final String KINDLE_DIR = "Loudbook";

    /** The EPUB already in the phone's Download/Loudbook/<book>/ at its full size, or null (Android 10+). */
    static Uri kindleHere(Context ctx, PcLink.BookFile f) {
        if (Build.VERSION.SDK_INT < 29) return null;
        int slash = f.path.indexOf('/');
        String book = f.path.substring(0, slash), name = f.path.substring(slash + 1);
        Uri coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor c = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{Environment.DIRECTORY_DOWNLOADS + "/" + KINDLE_DIR + "/" + book + "/", name}, null)) {
            while (c != null && c.moveToNext()) if (f.bytes <= 0 || c.getLong(1) == f.bytes) return android.content.ContentUris.withAppendedId(coll, c.getLong(0));
        } catch (Exception e) { Log.w(TAG, "kindle book: looking for " + name, e); }
        return null;
    }

    /** Copies the EPUB into the phone's Download/Loudbook/<book>/ (once) and returns it, to share to the Kindle app. */
    static Uri kindleCopy(Context ctx, PcLink l, PcLink.BookFile f, Progress p) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new java.io.IOException("this needs Android 10 or newer");
        Uri have = kindleHere(ctx, f);
        if (have != null) return have;
        int slash = f.path.indexOf('/');
        String book = f.path.substring(0, slash), name = f.path.substring(slash + 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + KINDLE_DIR + "/" + book + "/";
        // (a short copy from before: gone first, so the folder doesn't get "name (1).epub")
        try (android.database.Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{rel, name}, null)) {
            while (c != null && c.moveToNext()) cr.delete(android.content.ContentUris.withAppendedId(coll, c.getLong(0)), null, null);
        } catch (Exception e) { Log.w(TAG, "kindle book: replacing " + name, e); }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(coll, v);
        if (u == null) throw new java.io.IOException("the phone wouldn't make " + name);
        try {
            p.at("Copying " + name + " (" + Math.round(f.bytes / 1e5) / 10.0 + " MB)\u2026");
            try (OutputStream out = cr.openOutputStream(u)) {
                if (out == null) throw new java.io.IOException("can't write " + name);
                whole(l.download(f.path, out, 120000), f, name);
            }
            ContentValues ok = new ContentValues();
            ok.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, ok, null, null);
        } catch (Exception e) {
            cr.delete(u, null, null);
            throw e;
        }
        return u;
    }

    /** A copy that came short (the Wi-Fi dropped as it ended) isn't kept as done. */
    private static void whole(long got, PcLink.BookFile f, String name) throws java.io.IOException {
        if (f.bytes > 0 && got != f.bytes) throw new java.io.IOException(name + " came short (" + got + " of " + f.bytes + " bytes)");
    }

    /** The keys of files copied before that aren't on the phone any more (unsure counts as there: never copied twice for nothing). */
    static Set<String> missing(Context ctx, List<PcLink.BookFile> files, Set<String> done) {
        Set<String> out = new HashSet<>();
        java.util.Map<String, Set<String>> shared = new java.util.HashMap<>();   // book -> MP3 names in Audiobooks/<book>/ (null: unsure)
        for (PcLink.BookFile f : files) {
            String key = f.path + "|" + f.bytes;
            if (!done.contains(key)) continue;
            int slash = f.path.indexOf('/');
            if (slash <= 0) continue;
            String book = f.path.substring(0, slash), name = f.path.substring(slash + 1);
            try {
                if (!name.endsWith(".mp3")) { if (!new File(ctx.getExternalFilesDir(null), "books/" + book + "/" + name).isFile()) out.add(key); }
                else if (Build.VERSION.SDK_INT < 29) { if (!new File(new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book), name).isFile()) out.add(key); }
                else {
                    if (!shared.containsKey(book)) shared.put(book, audioNames(ctx, book));
                    Set<String> have = shared.get(book);
                    if (have != null && !have.contains(name)) out.add(key);
                }
            } catch (Exception e) { Log.w(TAG, "audiobook: checking " + name, e); }
        }
        return out;
    }

    /** The MP3s Loudbook put in Audiobooks/<book>/, or null if the phone wouldn't say. */
    private static Set<String> audioNames(Context ctx, String book) {
        if (Build.VERSION.SDK_INT < 29) return null;
        Set<String> out = new HashSet<>();
        Uri coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor c = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{Environment.DIRECTORY_AUDIOBOOKS + "/" + book + "/"}, null)) {
            if (c == null) return null;
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception e) { Log.w(TAG, "audiobook: listing " + book, e); return null; }
        return out;
    }

    private static void saveAudio(Context ctx, PcLink l, PcLink.BookFile f, String book, String name) throws Exception {
        String path = f.path;
        if (Build.VERSION.SDK_INT < 29) {                         // (no shared Audiobooks folder without a permission: Loudbook's own)
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't make " + dir);
            File part = new File(dir, name + ".part");
            try (OutputStream out = new FileOutputStream(part)) { whole(l.download(path, out, 60000), f, name); }
            File to = new File(dir, name);
            if (to.exists() && !to.delete() || !part.renameTo(to)) throw new java.io.IOException("can't save " + name);
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
                whole(l.download(path, out, 60000), f, name);
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
