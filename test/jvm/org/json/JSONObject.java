package org.json;
import java.util.*;
/** A small stand-in for Android's org.json, enough for the app's classes in tests. */
public class JSONObject {
    final Map<String, Object> m = new LinkedHashMap<>();
    public JSONObject() { }
    public JSONObject(String s) throws JSONException { Object o = new P(s).value(); if (!(o instanceof JSONObject)) throw new JSONException("not an object"); m.putAll(((JSONObject) o).m); }
    public JSONObject put(String k, Object v) { m.put(k, v); return this; }
    public boolean isNull(String k) { return !m.containsKey(k) || m.get(k) == null; }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = m.get(k); return v == null ? d : String.valueOf(v); }
    public int optInt(String k, int d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
    public long optLong(String k, long d) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : d; }
    public int getInt(String k) throws JSONException { Object v = m.get(k); if (!(v instanceof Number)) throw new JSONException(k); return ((Number) v).intValue(); }
    public JSONArray getJSONArray(String k) throws JSONException { Object v = m.get(k); if (!(v instanceof JSONArray)) throw new JSONException(k); return (JSONArray) v; }
    public static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() throws JSONException {
            ws(); char c = s.charAt(i);
            if (c == '{') { i++; JSONObject o = new JSONObject(); ws(); if (s.charAt(i) == '}') { i++; return o; }
                while (true) { ws(); String k = (String) value(); ws(); i++; o.m.put(k, value()); ws(); char d = s.charAt(i++); if (d == '}') return o; } }
            if (c == '[') { i++; JSONArray a = new JSONArray(); ws(); if (s.charAt(i) == ']') { i++; return a; }
                while (true) { a.l.add(value()); ws(); char d = s.charAt(i++); if (d == ']') return a; } }
            if (c == '"') { StringBuilder b = new StringBuilder(); i++;
                while (s.charAt(i) != '"') { char d = s.charAt(i++); if (d == '\\') { char e = s.charAt(i++); if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; } else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); } else b.append(d); }
                i++; return b.toString(); }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            int j = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(j, i); return n.contains(".") || n.contains("e") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
    }
}
