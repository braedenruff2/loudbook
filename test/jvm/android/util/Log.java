package android.util;
public class Log {
    public static int i(String t, String m) { System.out.println("I " + m); return 0; }
    public static int d(String t, String m) { System.out.println("D " + m); return 0; }
    public static int w(String t, String m) { System.out.println("W " + m); return 0; }
    public static int w(String t, String m, Throwable e) { System.out.println("W " + m + " " + e); return 0; }
    public static int e(String t, String m, Throwable e) { System.out.println("E " + m + " " + e); return 0; }
}
