package com.loudbook.app;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The audiobooks on this phone: what BookCopy copied from the PC. Each chapter is "NNNN - name.json" in Loudbook's files
 * (books/<book>/: its title, length and every sentence's text with its start and end) and "NNNN - name.mp3" in the phone's
 * Audiobooks/<book>/ folder (Android 10+, through MediaStore) or Loudbook's own Audiobooks folder (older phones).
 */
final class Books {
    static final String TAG = "Loudbook";

    static final class Chapter {
        final String json, mp3, title; final double seconds;
        Chapter(String json, String title, double seconds) {
            this.json = json; this.mp3 = json.substring(0, json.length() - 5) + ".mp3"; this.title = title; this.seconds = seconds;
        }
    }

    /** Where a book was left. */
    static final class Spot { final String file; final double sec; Spot(String f, double s) { file = f; sec = s; } }

    static File root(Context ctx) { return new File(ctx.getExternalFilesDir(null), "books"); }

    /** The books with at least one chapter's text here, by name. (Only the names are looked at: a book from the PC's text
     *  has a file per chapter, over a thousand in all, and this is asked for by the start page.) */
    static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        String[] names = root(ctx).list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            String[] fs = new File(root(ctx), n).list();
            if (fs != null) for (String f : fs) if (f.endsWith(".json")) { out.add(n); break; }
        }
        return out;
    }

    /** A chapter that comes as text, read by the reader's voice (BookPages), not a recording. */
    static boolean isText(Chapter c) { return c.json.endsWith(".text.json"); }

    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SECONDS = Pattern.compile("\"seconds\"\\s*:\\s*([0-9.]+)");

    /** A book's chapters in order (the file names start with the chapter's number). */
    static List<Chapter> chapters(Context ctx, String book) {
        List<Chapter> out = new ArrayList<>();
        if (!safe(book)) return out;
        File dir = new File(root(ctx), book);
        String[] names = dir.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            if (!n.endsWith(".json")) continue;
            String head = head(new File(dir, n));
            Matcher t = TITLE.matcher(head), s = SECONDS.matcher(head);
            String title = t.find() ? PcLink.unescape(t.group(1)) : n.replaceFirst("^\\d+ - ", "").replaceFirst("\\.json$", "");
            double sec = 0;
            try { if (s.find()) sec = Double.parseDouble(s.group(1)); } catch (NumberFormatException ignored) { }
            out.add(new Chapter(n, title, sec));
        }
        return out;
    }

    /** The first bit of a chapter's .json, where its title and length are (written first by the PC). */
    private static String head(File f) {
        byte[] b = new byte[600];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    /** A chapter's whole .json, for the book page. */
    static String chapterText(Context ctx, String book, String file) {
        if (!safe(book) || !safe(file) || !file.endsWith(".json")) return "";
        File f = new File(new File(root(ctx), book), file);
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { Log.w(TAG, "book: reading " + file, e); return ""; }
    }

    /** One file or folder name, nothing that climbs out. */
    static boolean safe(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.equals(".") && !name.equals("..");
    }

    /** The chapter's recording on this phone, or null if it isn't here. */
    static Uri audio(Context ctx, String book, Chapter c) {
        // (Loudbook's own Audiobooks folder: where older phones keep them, and where the emulator test puts them)
        File own = new File(new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book), c.mp3);
        if (own.isFile()) return Uri.fromFile(own);
        if (Build.VERSION.SDK_INT < 29) return null;
        Uri coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor q = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{Environment.DIRECTORY_AUDIOBOOKS + "/" + book + "/", c.mp3}, null)) {
            if (q != null && q.moveToFirst()) return ContentUris.withAppendedId(coll, q.getLong(0));
        } catch (Exception e) { Log.w(TAG, "book: finding " + c.mp3, e); }
        return null;
    }

    /** The recordings of a book that are on this phone (MP3 names). */
    static java.util.Set<String> audioHere(Context ctx, String book) {
        java.util.Set<String> out = new java.util.HashSet<>();
        String[] own = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book).list();
        if (own != null) out.addAll(Arrays.asList(own));
        if (Build.VERSION.SDK_INT < 29) return out;
        Uri coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor q = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{Environment.DIRECTORY_AUDIOBOOKS + "/" + book + "/"}, null)) {
            while (q != null && q.moveToNext()) out.add(q.getString(0));
        } catch (Exception e) { Log.w(TAG, "book: listing " + book, e); }
        return out;
    }

    /**
     * Deletes a book from this phone: its recordings (MP3s in Audiobooks/<book>/) and its text. What was deleted isn't
     * copied from the PC again ("booksDeleted": its files as the PC listed them); a chapter the PC makes again (a new file)
     * is. Returns how many files went.
     */
    static int delete(Context ctx, SharedPreferences prefs, String book) {
        if (!safe(book)) return 0;
        java.util.Set<String> del = new java.util.HashSet<>(prefs.getStringSet("booksDeleted", new java.util.HashSet<>()));
        for (String k : prefs.getStringSet("booksCopied", new java.util.HashSet<>())) if (k.startsWith(book + "/")) del.add(k);
        int n = 0;
        // (what was made ahead for its chapters goes too: AheadMaker)
        List<String> urls = new ArrayList<>();
        for (Chapter c : chapters(ctx, book)) if (isText(c)) urls.add(BookPages.url(book, c.json));
        AheadMaker.delete(ctx, urls);
        File dir = new File(root(ctx), book);
        String[] fs = dir.list();
        if (fs != null) for (String f : fs) if (new File(dir, f).delete()) n++;
        dir.delete();
        File own = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_AUDIOBOOKS), book);
        fs = own.list();
        if (fs != null) for (String f : fs) if (new File(own, f).delete()) n++;
        own.delete();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            List<Long> ids = new ArrayList<>();
            try (android.database.Cursor q = ctx.getContentResolver().query(coll, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{Environment.DIRECTORY_AUDIOBOOKS + "/" + book + "/"}, null)) {
                while (q != null && q.moveToNext()) ids.add(q.getLong(0));
            } catch (Exception e) { Log.w(TAG, "book: listing " + book + " to delete", e); }
            for (long id : ids) {
                try { if (ctx.getContentResolver().delete(ContentUris.withAppendedId(coll, id), null, null) > 0) n++; }
                catch (Exception e) { Log.w(TAG, "book: deleting a recording of " + book, e); }
            }
        }
        prefs.edit().putStringSet("booksDeleted", del).remove("bookAt:" + book).apply();
        BookAudio.forget(ctx);
        Log.i(TAG, "book: " + book + " deleted from the phone (" + n + " files)");
        return n;
    }

    // ---------------------------------------------------------------- where each book was left

    static Spot spot(SharedPreferences prefs, String book) {
        String v = prefs.getString("bookAt:" + book, "");
        int bar = v.lastIndexOf('|');
        if (bar <= 0) return null;
        try { return new Spot(v.substring(0, bar), Double.parseDouble(v.substring(bar + 1))); } catch (NumberFormatException e) { return null; }
    }

    static int spotMs(SharedPreferences prefs, String book, Chapter c) {
        Spot s = spot(prefs, book);
        return s != null && s.file.equals(c.json) ? (int) (s.sec * 1000) : 0;
    }

    static void saveSpot(SharedPreferences prefs, String book, String file, double sec) {
        prefs.edit().putString("bookAt:" + book, file + "|" + String.format(java.util.Locale.US, "%.1f", Math.max(0, sec))).apply();
    }

    /** For the book page: [{name, chapters: [{file, title, seconds, audio, text}], text, at: {file, sec}}]. text: read by the
     *  reader's voice (a book from the PC's text, see BookPages). */
    static String listJson(Context ctx, SharedPreferences prefs) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (String b : list(ctx)) {
                List<Chapter> cs = chapters(ctx, b);
                boolean text = !cs.isEmpty() && isText(cs.get(0));
                java.util.Set<String> here = text ? new java.util.HashSet<>() : audioHere(ctx, b);
                org.json.JSONArray chs = new org.json.JSONArray();
                for (Chapter c : cs)
                    chs.put(new org.json.JSONObject().put("file", c.json).put("title", c.title).put("seconds", c.seconds).put("audio", here.contains(c.mp3)).put("text", isText(c)));
                org.json.JSONObject o = new org.json.JSONObject().put("name", b).put("chapters", chs).put("text", text);
                Spot s = spot(prefs, b);
                if (s != null) o.put("at", new org.json.JSONObject().put("file", s.file).put("sec", s.sec));
                a.put(o);
            }
        } catch (Exception e) { Log.w(TAG, "book: list", e); }
        return a.toString();
    }

    private Books() { }
}
