package com.aigetway.tattoo7.key;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一个 API key 及其可访问的模型组（= 服务商）。 */
public class ApiKey {

    public String key = "";
    public String name = "";
    /** 可访问的服务商 id 列表；空 = 不限制（全部）。 */
    public final List<String> groups = new ArrayList<>();
    public long createdAt;
    public boolean enabled = true;

    public boolean allowsAll() {
        return groups.isEmpty();
    }

    public boolean allows(String providerId) {
        if (!enabled) return false;
        return allowsAll() || groups.contains(providerId);
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", key);
        o.put("name", name);
        o.put("createdAt", createdAt);
        o.put("enabled", enabled);
        JSONArray g = new JSONArray();
        for (String s : groups) g.put(s);
        o.put("groups", g);
        return o;
    }

    public static ApiKey fromJson(JSONObject o) {
        ApiKey k = new ApiKey();
        k.key = o.optString("key", "");
        k.name = o.optString("name", "");
        k.createdAt = o.optLong("createdAt", 0L);
        k.enabled = o.optBoolean("enabled", true);
        JSONArray g = o.optJSONArray("groups");
        if (g != null) {
            for (int i = 0; i < g.length(); i++) k.groups.add(g.optString(i));
        }
        return k;
    }

    public String groupsText() {
        if (allowsAll()) return "全部服务商";
        return groups.toString();
    }
}
