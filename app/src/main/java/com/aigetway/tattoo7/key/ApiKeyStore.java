package com.aigetway.tattoo7.key;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * API key 管理：随机生成、一键复制（UI 侧）、按 key 授权可访问的服务商分组。
 * 网关按请求头 Authorization: Bearer sk-xxx 匹配 key，决定 /v1/models 与 chat 下发哪些模型。
 */
public class ApiKeyStore {

    private static final String PREFS = "api_keys";
    private static final String K_LIST = "list";
    private static final SecureRandom RND = new SecureRandom();

    private final SharedPreferences prefs;
    private final List<ApiKey> keys = new ArrayList<>();

    public ApiKeyStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** 生成随机 key：sk- + 32 位 base62。 */
    public static String randomKey() {
        final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(35);
        sb.append("sk-t7");
        for (int i = 0; i < 32; i++) sb.append(alphabet.charAt(RND.nextInt(alphabet.length())));
        return sb.toString();
    }

    private synchronized void load() {
        keys.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) keys.add(ApiKey.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        try {
            for (ApiKey k : keys) arr.put(k.toJson());
        } catch (Exception ignored) {
        }
        prefs.edit().putString(K_LIST, arr.toString()).apply();
    }

    public synchronized List<ApiKey> all() {
        return new ArrayList<>(keys);
    }

    public synchronized int size() {
        return keys.size();
    }

    /** 新建 key。groups 为空表示不限制。 */
    public synchronized ApiKey create(String name, List<String> groups) {
        ApiKey k = new ApiKey();
        k.key = randomKey();
        k.name = name == null || name.isEmpty() ? "key-" + (keys.size() + 1) : name;
        k.createdAt = System.currentTimeMillis();
        if (groups != null) k.groups.addAll(groups);
        keys.add(k);
        persist();
        return k;
    }

    public synchronized void remove(String key) {
        keys.removeIf(x -> x.key.equals(key));
        persist();
    }

    public synchronized void setGroups(String key, List<String> groups) {
        for (ApiKey k : keys) {
            if (k.key.equals(key)) {
                k.groups.clear();
                if (groups != null) k.groups.addAll(groups);
                persist();
                return;
            }
        }
    }

    public synchronized void setEnabled(String key, boolean enabled) {
        for (ApiKey k : keys) {
            if (k.key.equals(key)) {
                k.enabled = enabled;
                persist();
                return;
            }
        }
    }

    /** 按明文 key 查找；找不到返回 null。 */
    public synchronized ApiKey find(String raw) {
        if (raw == null) return null;
        for (ApiKey k : keys) if (k.key.equals(raw)) return k;
        return null;
    }

    /**
     * 从 Authorization 头解析出明文 key（去掉 Bearer 前缀）。
     */
    public static String parseBearer(String authHeader) {
        if (authHeader == null) return null;
        String s = authHeader.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) s = s.substring(7).trim();
        return s.isEmpty() ? null : s;
    }
}
