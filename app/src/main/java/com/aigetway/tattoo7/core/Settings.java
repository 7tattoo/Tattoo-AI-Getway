package com.aigetway.tattoo7.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

public class Settings {

    public static final String CHANNEL_GATEWAY = "gateway_channel";

    public static final int DEFAULT_PORT = 8798;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final String PREFS = "zen2api_settings";
    private static final String KEY_PORT = "port";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_KEY_REQUIRED = "key_required";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_FLOAT = "float_window";
    private static final String KEY_SESSION = "session_id";
    private static final String KEY_VERSION_NAME = "version_name";
    private static final String KEY_DISABLED_MODELS = "disabled_models";
    private static final String KEY_LAN = "lan_enabled";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int port() {
        return prefs.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) return;
        prefs.edit().putInt(KEY_PORT, port).apply();
    }

    /** 网关鉴权 Key。为空表示不校验。 */
    public String apiKey() {
        return prefs.getString(KEY_API_KEY, "").trim();
    }

    /**
     * 是否启用 API Key 校验。本地网关默认关闭（免鉴权），避免客户端 key 不匹配导致 401。 */
    public boolean keyRequired() {
        return prefs.getBoolean(KEY_KEY_REQUIRED, false);
    }

    public void setKeyRequired(boolean v) {
        prefs.edit().putBoolean(KEY_KEY_REQUIRED, v).apply();
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key == null ? "" : key.trim()).apply();
    }

    /** 已禁用的模型 id 集合（/v1/models 不再下发），JSON 数组持久化，重启仍生效。 */
    public java.util.Set<String> disabledModels() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    prefs.getString(KEY_DISABLED_MODELS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!v.isEmpty()) out.add(v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setDisabledModels(java.util.Set<String> ids) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (ids != null) {
            for (String id : ids) arr.put(id);
        }
        prefs.edit().putString(KEY_DISABLED_MODELS, arr.toString()).apply();
    }

    public void clearDisabledModels() {
        prefs.edit().putString(KEY_DISABLED_MODELS, "[]").apply();
    }

    // ---- 各服务商网页登录 Cookie（跨启动保留，避免每次重新扫码/登录） ----

    public void setProviderCookie(String providerId, String cookie) {
        prefs.edit().putString("cookie_" + providerId, cookie).apply();
    }

    public String providerCookie(String providerId) {
        return prefs.getString("cookie_" + providerId, null);
    }

    // ---- 通用 int 偏好 ----

    public int prefInt(String key, int def) {
        return prefs.getInt(key, def);
    }

    public void setPrefInt(String key, int v) {
        prefs.edit().putInt(key, v).apply();
    }

    // ---- 局域网访问（选择开启，默认仅本机） ----

    public boolean lanEnabled() {
        return prefs.getBoolean(KEY_LAN, false);
    }

    public void setLanEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_LAN, on).apply();
    }

    public boolean bootOnStart() {
        return prefs.getBoolean(KEY_BOOT, false);
    }

    public void setBootOnStart(boolean on) {
        prefs.edit().putBoolean(KEY_BOOT, on).apply();
    }

    public boolean floatWindow() {
        return prefs.getBoolean(KEY_FLOAT, false);
    }

    public void setFloatWindow(boolean on) {
        prefs.edit().putBoolean(KEY_FLOAT, on).apply();
    }

    /** 设备级稳定会话 ID，免密额度按会话计，同一会话避免频繁刷新导致限流。 */
    public String sessionId() {
        String s = prefs.getString(KEY_SESSION, "");
        if (s == null || s.trim().isEmpty()) {
            s = Build.MODEL + "-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_SESSION, s).apply();
        }
        return s;
    }

    public String versionName() {
        String v = prefs.getString(KEY_VERSION_NAME, "");
        return v == null || v.trim().isEmpty() ? "1.0.0" : v;
    }

    public void setVersionName(String v) {
        prefs.edit().putString(KEY_VERSION_NAME, v).apply();
    }
}