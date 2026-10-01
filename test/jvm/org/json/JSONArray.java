package org.json;
public class JSONArray {
    final java.util.List<Object> l = new java.util.ArrayList<>();
    public int length() { return l.size(); }
    public JSONObject getJSONObject(int i) throws JSONException { Object v = l.get(i); if (!(v instanceof JSONObject)) throw new JSONException("" + i); return (JSONObject) v; }
}
