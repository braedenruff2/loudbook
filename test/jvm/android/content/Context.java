package android.content;
/** Just enough of Android's Context to run VoiceCommands on a desktop JVM (tests). */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final java.io.File files;
    private final java.util.Map<String, SharedPreferences> prefs = new java.util.HashMap<>();
    public Context(java.io.File files) { this.files = files; }
    public java.io.File getFilesDir() { return files; }
    public java.io.File getCacheDir() { return files; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs.computeIfAbsent(name, k -> new SharedPreferences()); }
}
