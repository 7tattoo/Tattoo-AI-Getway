package com.aigetway.tattoo7.provider;

import android.content.Context;
import android.content.SharedPreferences;

import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TRAE（字节）。协议来自 _upstream/src/trae-*.ts：
 * agentHost=https://trae-api-cn.mchost.guru（chat/models），ugHost=https://api.trae.cn（签到/积分），
 * oauthHost=https://api.trae.com.cn（换 token），登录门户 https://www.trae.cn/authorization。
 * 鉴权：Authorization: Cloud-IDE-JWT <token>（直传，无签名）。
 */
public class TraeProvider implements ServiceProvider {

    public static final String ID = "trae";

    private static final String PREFS = "svc_trae";
    private static final String CK = "cred";

    private static final String AGENT_HOST = "https://trae-api-cn.mchost.guru";
    private static final String UG_HOST = "https://api.trae.cn";
    private static final String OAUTH_HOST = "https://api.trae.com.cn";
    private static final String CONSOLE_HOST = "https://www.trae.cn";

    private static final String CHAT_PATH = "/api/agent/v3/llm_utils_chat";
    private static final String BATCH_MODELS_PATH = "/api/ide/v1/batch_get_detail_param";
    private static final String EXCHANGE_PATH = "/cloudide/api/v3/trae/oauth/ExchangeToken";
    private static final String CHECKIN_STATUS_PATH = "/trae/api/v2/ug/checkin_credits/status";
    private static final String CHECKIN_CLAIM_PATH = "/trae/api/v2/ug/checkin_credits/claim";
    /** 积分余额端点（协议来自 _upstream/src/trae-credits.ts 的 fetchTraeCreditBalance）。 */
    private static final String ENT_USAGE_PATH = "/trae/api/v2/pay/ide_user_ent_usage";
    /** 「15 天内到期」视为临时积分（与 BuddyProvider 一致）。 */
    private static final int EXPIRING_WINDOW_DAYS = 15;

    private static final String CLIENT_ID = "en1oxy7wnw8j9n";
    private static final String APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8";
    private static final String IDE_VERSION = "0.1.52";
    private static final String IDE_VERSION_CODE = "20260811";
    private static final String PLUGIN_VERSION = "2.3.62834";
    private static final String UA = "Trae/" + IDE_VERSION;
    private static final String FUNCTION = "solo_work_lite";

    /** 远端目录中模型所属 function；同一模型只在列出它的通道里可调用。 */
    static final Map<String, String> MODEL_CHANNELS =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>());

    private final SharedPreferences prefs;
    private final Context app;
    private volatile String pendingMachineId = "";
    private volatile String pendingDeviceId = "";
    private final com.aigetway.tattoo7.account.AccountPool pool;
    /** 最近一次刷新失败的真实原因（UI 会原样显示）。 */
    private volatile String lastRefreshError = "";

    public TraeProvider(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.app = ctx.getApplicationContext();
        this.pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(ID);
    }

    public String lastRefreshError() {
        return lastRefreshError;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "TRAE / 字节";
    }

    // ---------------- 凭据 ----------------

    private JSONObject cred() {
        try {
            com.aigetway.tattoo7.account.AccountPool.Account a = pool.current();
            return a == null ? null : new JSONObject(a.cred);
        } catch (Exception e) {
            return null;
        }
    }

    /** 登录成功：新增账号；同一 uid 再次登录覆盖旧账号，避免列表自我复制。 */
    private void addCred(JSONObject o, String label) {
        String uid = o.optString("uid", "");
        if (!uid.isEmpty()) pool.addOrUpdate(uid, o.toString(), label);
        else pool.addAccount(o.toString(), label);
    }

    /** 刷新凭据：更新当前账号。 */
    private void saveCred(JSONObject o) {
        if (pool.isEmpty()) pool.addAccount(o.toString(), null);
        else pool.updateCred(pool.activeId(), o.toString());
    }

    /** 当前账号 id（供上层做失败轮转）。 */
    public String activeAccountId() {
        return pool.activeId();
    }

    public com.aigetway.tattoo7.account.AccountPool pool() {
        return pool;
    }

    /** 上游报错时切到下一个可用账号，返回是否切换成功。 */
    public boolean rotateAccount() {
        return pool.rotate() != null;
    }

    @Override
    public boolean loggedIn() {
        JSONObject c = cred();
        return c != null && !c.optString("accessToken", "").isEmpty();
    }

    private boolean expired() {
        JSONObject c = cred();
        if (c == null) return true;
        long exp = c.optLong("expiresAt", 0L);
        return exp > 0 && System.currentTimeMillis() > exp - 120_000L;
    }

    private Map<String, String> headers() throws IOException {
        if (!loggedIn()) throw new IOException("TRAE 未登录");
        if (expired()) refreshFingerprint();
        JSONObject c = cred();
        String t = c.optString("accessToken");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("User-Agent", UA);
        h.put("Authorization", "Cloud-IDE-JWT " + t);
        h.put("X-Cloudide-Token", t);
        h.put("X-Ide-Token", t);
        h.put("X-Uid", c.optString("uid", ""));
        h.put("X-App-Id", APP_ID);
        h.put("X-App-Version", "default");
        h.put("X-Ide-Version", IDE_VERSION);
        h.put("X-Ide-Version-Code", IDE_VERSION_CODE);
        h.put("X-App-Version-Code", IDE_VERSION_CODE);
        h.put("X-Ide-Version-Type", "stable");
        h.put("X-Device-Type", "macos");
        h.put("X-OS-Version", "macOS 15.7.4");
        h.put("X-Device-Brand", "Apple");
        h.put("Request-Traffic-Type", "prod");
        String mid = c.optString("machineId", "");
        if (!mid.isEmpty()) h.put("X-Machine-Id", mid);
        String did = c.optString("deviceId", "");
        // 加密请求必须回传 pin/at，且 x-requested-at 要与 GCM 的 AAD 完全一致
        if (!did.isEmpty()) h.put("X-Device-Id", did);
        return h;
    }

    // ---------------- 登录 ----------------

    /** 生成本机回调地址与登录 URL（WebView 打开）。 */
    public String beginLogin(int port) {
        // 每次登录生成**新的** machineId/deviceId 指纹（加账号时与已有账号隔离），
        // 但先不落盘 —— 落盘会覆盖当前账号的设备信息。
        String mid = UUID.randomUUID().toString().replace("-", "");
        String did = UUID.randomUUID().toString().replace("-", "");
        pendingMachineId = mid;
        pendingDeviceId = did;
        String callback = "http://127.0.0.1:" + port + "/authorize";
        StringBuilder q = new StringBuilder();
        q.append("login_version=1");
        q.append("&auth_from=solo");
        q.append("&login_channel=native_ide");
        q.append("&plugin_version=").append(PLUGIN_VERSION);
        q.append("&auth_type=local");
        q.append("&client_id=").append(CLIENT_ID);
        q.append("&redirect=0");
        q.append("&login_trace_id=").append(machineTraceId(mid, did));
        q.append("&auth_callback_url=").append(enc(callback));
        q.append("&machine_id=").append(mid);
        q.append("&device_id=").append(did);
        q.append("&x_device_id=").append(did);
        q.append("&x_machine_id=").append(mid);
        q.append("&x_device_brand=PC");
        q.append("&x_device_type=PC");
        q.append("&x_os_version=1.0");
        q.append("&x_app_version=").append(IDE_VERSION);
        q.append("&x_app_type=stable");
        return CONSOLE_HOST + "/authorization?" + q;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** login_trace_id：由 machine/device 派生的短串。 */
    private static String machineTraceId(String machineId, String deviceId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((machineId + "|" + deviceId).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 处理回调 URL（本机 127.0.0.1 的 /authorize）。
     * TRAE 回调是**扁平 query 参数**，不是单个 info blob：
     * userInfo=JSON(URL编码) → UserID/ScreenName/TenantID；
     * userJwt=JSON(URL编码) → Token/RefreshToken；refreshToken=明文。
     */
    public String handleCallback(String url) {
        if (url == null) return null;
        java.net.URI u;
        try {
            u = java.net.URI.create(url);
        } catch (Exception e) {
            return null;
        }
        String host = u.getHost();
        boolean local = host != null && (host.equals("127.0.0.1") || host.equals("localhost"));
        String path = u.getPath() == null ? "" : u.getPath();
        if (!local || !path.startsWith("/authorize")) return null;
        return handleCallbackParams(parseParams(url));
    }

    /** 解析回调 query 参数（含 # 片段兜底）。 */
    public static java.util.Map<String, String> parseParams(String url) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        int h = url.indexOf('#');
        putQuery(m, h >= 0 ? url.substring(0, h) : url);
        if (h >= 0) putQuery(m, url.substring(h + 1));
        return m;
    }

    private static void putQuery(java.util.Map<String, String> m, String s) {
        int q = s.indexOf('?');
        String qs;
        if (q >= 0) qs = s.substring(q + 1);
        else qs = s.contains("=") ? s : "";
        if (qs.isEmpty()) return;
        for (String kv : qs.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = dec(kv.substring(0, eq));
            String v = dec(kv.substring(eq + 1));
            if (!k.isEmpty() && !m.containsKey(k)) m.put(k, v);
        }
    }

    private static String dec(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 用回调参数换取凭据。 */
    public String handleCallbackParams(java.util.Map<String, String> params) {
        if (params == null || params.isEmpty()) return "error: 回调没有参数";
        try {
            JSONObject userInfo = jsonParam(params.get("userInfo"));
            JSONObject userJwt = jsonParam(params.get("userJwt"));
            String uid = userInfo == null ? "" : userInfo.optString("UserID", "");
            String nickname = userInfo == null ? "" : userInfo.optString("ScreenName", "");
            String enterprise = userInfo == null ? "" : userInfo.optString("TenantID", "");

            String refreshToken = str(params.get("refreshToken"));
            if (refreshToken.isEmpty() && userJwt != null) refreshToken = userJwt.optString("RefreshToken", "");
            String accessToken = userJwt == null ? "" : userJwt.optString("Token", "");

            if (!refreshToken.isEmpty()) {
                String r = exchange(refreshToken, uid, nickname, enterprise, true);
                if ("ok".equals(r)) return r;
                // ExchangeToken 失败但回调里带着 userJwt.Token 时，退回直接用它
                if (!accessToken.isEmpty()) {
                    JSONObject c = new JSONObject();
                    try {
                        c.put("accessToken", accessToken);
                        c.put("refreshToken", refreshToken);
                        c.put("expiresAt", 0L);
                        if (!uid.isEmpty()) c.put("uid", uid);
                        if (!nickname.isEmpty()) c.put("nickname", nickname);
                        addCred(c, nickname.isEmpty() ? null : nickname);
                        return "ok";
                    } catch (org.json.JSONException ignored) {
                    }
                }
                return r;
            }
            if (!accessToken.isEmpty()) {
                JSONObject c = new JSONObject();
                c.put("accessToken", accessToken);
                c.put("refreshToken", "");
                c.put("expiresAt", 0L);
                if (!uid.isEmpty()) c.put("uid", uid);
                if (!nickname.isEmpty()) c.put("nickname", nickname);
                if (!enterprise.isEmpty()) c.put("enterpriseId", enterprise);
                addCred(c, nickname.isEmpty() ? null : nickname);
                return "ok";
            }
            String authCode = str(params.get("code"));
            if (authCode.isEmpty()) authCode = str(params.get("authCode"));
            if (authCode.isEmpty() && params.get("authCodeInfo") != null) {
                JSONObject aci = jsonParam(params.get("authCodeInfo"));
                authCode = aci != null ? aci.optString("code", aci.optString("authCode", ""))
                        : str(params.get("authCodeInfo"));
            }
            if (!authCode.isEmpty()) {
                return "error: 上游返回了 PKCE 授权码，暂不支持，请重试";
            }
            return "error: 回调未携带凭据，参数=" + dump(params);
        } catch (Throwable e) {
            // e.getMessage() 可能是 null（NPE），用 toString 保证可诊断
            return "error: 解析回调失败 [" + e.getClass().getSimpleName() + "] "
                    + (e.getMessage() == null ? "(无详细信息)" : e.getMessage())
                    + "，回调参数=" + dump(params);
        }
    }

    /** 诊断用：把回调参数打成可读文本（值截断），便于截图反馈。 */
    private static String dump(java.util.Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            if (v.length() > 60) v = v.substring(0, 60) + "…";
            sb.append(e.getKey()).append("=").append(v).append("; ");
        }
        return sb.toString();
    }

    private static String str(String s) {
        return s == null ? "" : s.trim();
    }

    /** 参数可能是 JSON，也可能是 base64 包的 JSON。 */
    private static JSONObject jsonParam(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.trim();
        try {
            return new JSONObject(s);
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(new String(java.util.Base64.getDecoder().decode(s), "UTF-8"));
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(s), "UTF-8"));
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v == null || v.isEmpty()) {
                Object obj = o.opt(k);
                if (obj instanceof JSONObject) {
                    JSONObject j = (JSONObject) obj;
                    v = j.optString("Token", j.optString("token", j.optString("tokenRaw", "")));
                }
            }
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** ExchangeToken：refreshToken 换 accessToken（刷新当前账号）。 */
    private String exchange(String refreshToken) {
        return doExchange(refreshToken, "", "", "", false, null, null);
    }

    /** asNewAccount=true 表示这是登录成功（新增账号）；false 表示仅刷新当前账号。 */
    private String exchange(String refreshToken, String uid, String nickname, String enterprise,
                            boolean asNewAccount) {
        return doExchange(refreshToken, uid, nickname, enterprise, asNewAccount, null, null);
    }

    /**
     * 刷新**指定**账号：以旧 cred 为底（保住 machineId/deviceId/uid 等设备绑定），
     * 只替换 token，写回时按 accountId 精确更新，不动当前激活账号。
     */
    private String exchangeForAccount(com.aigetway.tattoo7.account.AccountPool.Account a,
                                      JSONObject old, String refreshToken) {
        return doExchange(refreshToken, old.optString("uid", ""), old.optString("nickname", ""),
                old.optString("enterpriseId", ""), false, a.id, old);
    }

    private String doExchange(String refreshToken, String uid, String nickname, String enterprise,
                              boolean asNewAccount, String accountId, JSONObject old) {
        try {
            JSONObject body = new JSONObject();
            body.put("ClientID", CLIENT_ID);
            body.put("RefreshToken", refreshToken);
            body.put("ClientSecret", "-");
            body.put("UserID", "");
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Accept", "application/json");
            h.put("User-Agent", UA);
            h.put("X-App-Id", APP_ID);
            h.put("X-App-Version", IDE_VERSION);
            h.put("X-Ide-Version", IDE_VERSION);
            Http.Resp r = Http.postJson(OAUTH_HOST + EXCHANGE_PATH, h, body.toString(), 20000);
            if (!r.ok()) {
                return "error: ExchangeToken HTTP " + r.code + " " + brief(r.body);
            }
            JSONObject j = Http.json(r);
            JSONObject data = j.optJSONObject("data");
            if (data == null) data = j.optJSONObject("Result"); // 字节网关风格：token 包在 Result 里
            JSONObject src = data == null ? j : data;
            String at = pick(src, "Token", "accessToken", "access_token", "token");
            if (at.isEmpty()) {
                String biz = src.optString("Message", src.optString("message", ""));
                return "error: ExchangeToken 响应缺少 Token" + brief(r.body)
                        + (biz.isEmpty() ? "" : "（业务信息：" + biz + "）");
            }
            // ⚠️ ExchangeToken 每次都会**轮换** refreshToken，本地那条一次性 rt 立刻作废。
            // 之前这里固定写回传进来的旧 rt，导致「新登录的账号第二次刷新必 401」。
            String rotated = pick(src, "RefreshToken", "refreshToken", "refresh_token");
            if (rotated.isEmpty()) rotated = refreshToken;

            // 刷新路径保留旧 cred（设备指纹、uid、昵称都在里面），登录路径从空对象开始
            JSONObject c = old == null ? new JSONObject() : new JSONObject(old.toString());
            String mid = c.optString("machineId", "");
            if (mid.isEmpty()) mid = pendingMachineId;
            if (mid.isEmpty()) mid = UUID.randomUUID().toString().replace("-", "");
            String did = c.optString("deviceId", "");
            if (did.isEmpty()) did = pendingDeviceId;
            if (did.isEmpty()) did = UUID.randomUUID().toString().replace("-", "");
            c.put("machineId", mid);
            c.put("deviceId", did);
            c.put("accessToken", at);
            c.put("refreshToken", rotated);
            long dur = src.optLong("ExpireDuration", src.optLong("TokenExpireDuration", 7200));
            c.put("expiresAt", System.currentTimeMillis() + dur * 1000L);
            if (!uid.isEmpty()) c.put("uid", uid);
            if (!nickname.isEmpty()) c.put("nickname", nickname);
            if (!enterprise.isEmpty()) c.put("enterpriseId", enterprise);
            if (asNewAccount) addCred(c, nickname.isEmpty() ? null : nickname);
            else if (accountId != null) pool.updateCred(accountId, c.toString());
            else saveCred(c);
            return "ok";
        } catch (Exception e) {
            return "error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String brief(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String queryParam(String url, String name) {
        int i = url.indexOf('?');
        if (i < 0) return null;
        for (String kv : url.substring(i + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (!name.equals(kv.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    public void logout() {
        prefs.edit().remove(CK).apply();
    }

    @Override
    public boolean refreshFingerprint() throws IOException {
        com.aigetway.tattoo7.account.AccountPool.Account a = pool.current();
        if (a == null) {
            lastRefreshError = "账号池是空的";
            return false;
        }
        // 请求路径上的自动续期：只用本地 rt 换票。云端刷新最长要等 45 秒，
        // 放在聊天请求链路里会把请求拖死，所以这里绝不走云端。
        return refreshLocal(a);
    }

    /** 本地 rt 换票，成功返回 true（失败原因写进 lastRefreshError）。 */
    private boolean refreshLocal(com.aigetway.tattoo7.account.AccountPool.Account a) {
        JSONObject old = pool.credOf(a.id);
        if (old == null) {
            lastRefreshError = "该账号没有凭据";
            return false;
        }
        String rt = old.optString("refreshToken", "");
        if (rt.isEmpty()) {
            lastRefreshError = "登录时没抓到续期凭据";
            return false;
        }
        String r = exchangeForAccount(a, old, rt);
        if ("ok".equals(r)) {
            lastRefreshError = "";
            return true;
        }
        lastRefreshError = "本地续期凭据已被服务端拒绝（" + r + "）";
        return false;
    }

    /**
     * 用户点「刷新凭证」走这里：先本地换票，失败再<b>云端静默重授权</b>
     * ——用 WebView 里保存的 trae.cn 网页登录态重新拿一套 Token+RefreshToken，
     * 而不是逼用户重新扫码。uidOrId 传池内 accountId 或上游 uid，null=当前账号。
     */
    public boolean refreshFingerprintForUid(String uidOrId) {
        com.aigetway.tattoo7.account.AccountPool.Account a = findAccount(uidOrId);
        if (a == null) {
            lastRefreshError = pool.isEmpty() ? "账号池是空的" : "账号池里找不到该账号";
            return false;
        }
        if (refreshLocal(a)) return true;
        String localErr = lastRefreshError;
        String cloudErr = cloudRefresh(a, pool.credOf(a.id));
        if (cloudErr == null) {
            lastRefreshError = "";
            return true;
        }
        lastRefreshError = localErr + "；云端刷新也没成功：" + cloudErr;
        return false;
    }

    /** 按 accountId 或 uid 找账号；传空取当前账号。 */
    private com.aigetway.tattoo7.account.AccountPool.Account findAccount(String uidOrId) {
        if (uidOrId == null || uidOrId.isEmpty()) return pool.current();
        for (com.aigetway.tattoo7.account.AccountPool.Account x : pool.all()) {
            if (uidOrId.equals(x.id) || (!uidOrId.isEmpty() && uidOrId.equals(x.uid))) return x;
        }
        return null;
    }

    /**
     * 云端刷新：静默重跑 OAuth 授权拿回新 token。
     *
     * @return null 表示成功，否则为失败原因
     */
    private String cloudRefresh(com.aigetway.tattoo7.account.AccountPool.Account a, JSONObject old) {
        if (old == null) old = new JSONObject();
        String url;
        try {
            url = beginLogin(TraeCloudRefresh.CALLBACK_PORT);
        } catch (Throwable e) {
            return "生成授权地址失败：" + e.getClass().getSimpleName();
        }
        TraeCloudRefresh.Result res = TraeCloudRefresh.run(app, url);
        if (!res.ok) return res.message;

        JSONObject userInfo = jsonParam(res.params.get("userInfo"));
        JSONObject userJwt = jsonParam(res.params.get("userJwt"));
        String newUid = userInfo == null ? "" : userInfo.optString("UserID", "");
        String nick = userInfo == null ? "" : userInfo.optString("ScreenName", "");
        String cloudRt = str(res.params.get("refreshToken"));
        if (cloudRt.isEmpty() && userJwt != null) cloudRt = userJwt.optString("RefreshToken", "");
        String cloudAt = userJwt == null ? "" : userJwt.optString("Token", "");

        // 网页登录态属于谁？和点刷新的账号不是同一个时必须拒绝，否则会把 A 的 token
        // 写进 B 的账号，表现为「额度对不上 / 模型列表串号」。
        String wantUid = a.uid.isEmpty() ? old.optString("uid", "") : a.uid;
        if (!wantUid.isEmpty() && !newUid.isEmpty() && !wantUid.equals(newUid)) {
            return "网页里当前登录的是另一个 Trae 账号" + (nick.isEmpty() ? "" : "（" + nick + "）")
                    + "，请先在 trae.cn 退出并登录本账号再刷新";
        }
        if (cloudRt.isEmpty() && cloudAt.isEmpty()) {
            return "云端回调没有携带凭据，参数=" + dump(res.params);
        }
        // 优先用新 rt 正式换票（拿到与设备指纹绑定的 IDE token）；不行再退回回调里的 Token
        if (!cloudRt.isEmpty()) {
            String r = exchangeForAccount(a, mergeIdentity(old, newUid, nick, res.params), cloudRt);
            if ("ok".equals(r)) return null;
            if (cloudAt.isEmpty()) return "云端已返回新凭据但换票失败：" + r;
        }
        try {
            JSONObject c = new JSONObject(old.toString());
            c.put("accessToken", cloudAt);
            if (!cloudRt.isEmpty()) c.put("refreshToken", cloudRt);
            c.put("expiresAt", 0L);   // 不知道有效期 → 0 表示「按未过期处理」
            mergeIdentity(c, newUid, nick, res.params);
            pool.updateCred(a.id, c.toString());
            return null;
        } catch (Throwable e) {
            return "写入云端凭据失败：" + e.getClass().getSimpleName();
        }
    }

    /** 把云端回调里的身份信息并进 cred（缺才补，不覆盖已有值）。 */
    private static JSONObject mergeIdentity(JSONObject c, String uid, String nickname,
                                            Map<String, String> params) {
        try {
            if (c.optString("uid", "").isEmpty() && !uid.isEmpty()) c.put("uid", uid);
            if (c.optString("nickname", "").isEmpty() && !nickname.isEmpty()) c.put("nickname", nickname);
            String tenant = params == null ? "" : str(params.get("tenantId"));
            if (tenant.isEmpty() && params != null) tenant = str(params.get("TenantID"));
            if (c.optString("enterpriseId", "").isEmpty() && !tenant.isEmpty()) {
                c.put("enterpriseId", tenant);
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    // ---------------- 模型 ----------------

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        JSONObject body;
        try {
            JSONArray fns = new JSONArray();
            for (String f : new String[]{"ui_builder_v2", "solo_coder", "chat_v3", "solo_builder",
                    "builder_v3", "builder", "chat", "inline_chat", "git_ai",
                    "custom_agent_generation", "utils", "code_reviewer", "code_review_summary",
                    "solo_agent", "solo_agent_remote", "solo_work_remote", "solo_agent_lite",
                    "solo_work_lite", "solo_design_lite", "solo_design_remote", "multimodal",
                    "system_diagnosis"}) {
                fns.put(f);
            }
            JSONObject cfg = new JSONObject();
            cfg.put("config_name", "");
            cfg.put("is_custom_model", false);
            body = new JSONObject();
            body.put("functions", fns);
            body.put("agent_type", "");
            body.put("current_config_info", cfg);
            body.put("mode_type", 0);
            body.put("access_type", 0);
            body.put("ab_force_vids", "");
            body.put("ab_autotest_advanced_mode", 0);
            body.put("show_custom_model", true);
        } catch (Exception e) {
            throw new IOException("bad body: " + e.getMessage());
        }
        Http.Resp r = Http.postJson(AGENT_HOST + BATCH_MODELS_PATH, headers(), body.toString(), 25000);
        JSONObject j = Http.json(r);
        List<ModelEntry> out = new ArrayList<>();
        MODEL_CHANNELS.clear();
        collect(j, out, "");
        if (out.isEmpty()) throw new IOException("TRAE 模型列表解析为空");
        for (ModelEntry e : out) e.live = true;
        return out;
    }

    /** 递归找出所有 {id, name/contextWindow} 形态的模型条目，去重。 */
    /**
     * 解析 batch_get_detail_param 响应。
     *
     * <p>真实结构（_upstream/src/trae.ts 的 parseBatchDetailParam）：
     * {@code { function_configs: [{ function, config_info_list: [条目...] }, ...] }}，
     * 每个 function 一套模型目录。条目字段是
     * {@code config_name} / {@code display_config.display_name} /
     * {@code context_window_tokens.dev} / {@code model_detail_list[].max_tokens}。
     *
     * <p>⚠️ 旧实现只认 {@code id/model + context_window}，与真实结构完全对不上，
     * 导致「TRAE 模型列表解析为空」。
     *
     * <p>四条过滤（对齐上游）：usage 非 chat_completion / config_switch===false /
     * is_invisible_to_user===true / is_custom_model===true 全部排除
     * ——最后一条是「仅可见但不可调用」的权威判据，勾中的模型一发请求就被上游 4001 拒。
     */
    private static void collect(Object node, List<ModelEntry> out, String channel) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, channel);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String ownChannel = o.optString("function", channel);

        // 命中单条模型条目？保存其所属 function，不能只保存扁平模型名。
        String id = o.optString("config_name", o.optString("ConfigName", ""));
        if (id.isEmpty()) id = o.optString("id", o.optString("model", ""));
        if (!id.isEmpty() && looksLikeModelEntry(o)) {
            if (!passesTraeFilters(o)) return;
            if (!ownChannel.isEmpty()) MODEL_CHANNELS.put(id, ownChannel);
            if (containsModel(out, id)) return;
            ModelEntry e = new ModelEntry(ID, id, traeDisplayName(o, id));
            e.contextWindow = readContextWindow(o);
            e.maxOutput = readMaxOutput(o);
            e.rateText = ModelRate.traeRate(o);
            e.vision = supportsVision(o);
            e.live = true;
            out.add(e);
            return;
        }
        // 否则继续往下递归
        for (String k : new String[]{"function_configs", "data", "result", "models",
                "model_list", "config_info_list", "functions"}) {
            Object v = o.opt(k);
            if (v != null) collect(v, out, ownChannel);
        }
    }

    /** 是否像一条模型条目（避免把 function_configs 这类容器误判成模型）。 */
    private static boolean looksLikeModelEntry(JSONObject o) {
        return o.has("context_window_tokens") || o.has("ContextWindowTokens")
                || o.has("model_detail_list") || o.has("ModelDetailList")
                || o.has("context_window") || o.has("contextWindow")
                || o.has("display_config") || o.has("usage") || o.has("config_switch");
    }

    private static boolean containsModel(List<ModelEntry> out, String id) {
        for (ModelEntry e : out) if (e.id.equals(id)) return true;
        return false;
    }

    /** 上游的四条过滤。 */
    static boolean passesTraeFilters(JSONObject o) {
        String usage = o.optString("usage", "");
        // usage 缺失时不过滤（单通道端点 get_detail_param 没有这个字段）
        if (!usage.isEmpty() && !usage.equals("chat_completion")) return false;
        if (!o.optBoolean("config_switch", o.optBoolean("ConfigSwitch", true))) return false;
        JSONObject dc = o.optJSONObject("display_config");
        if (o.optBoolean("is_invisible_to_user", false)
                || (dc != null && dc.optBoolean("is_invisible_to_user", false))) return false;
        // 两个参考实现均按标志值过滤；标志实际位于 display_config 内。
        if (o.optBoolean("is_custom_model", false)
                || (dc != null && dc.optBoolean("is_custom_model", false))) return false;
        return true;
    }

    private static boolean supportsVision(JSONObject o) {
        JSONObject dc = o.optJSONObject("display_config");
        return dc != null && dc.optBoolean("multimodal", false);
    }

    /** 模型必须走目录所属通道；兜底目录无通道信息时才回退 work_lite。 */
    static String channelFor(String model) {
        String base = model == null ? "" : model.split("__", 2)[0];
        String channel = MODEL_CHANNELS.get(base);
        return channel == null || channel.isEmpty() ? FUNCTION : channel;
    }

    private static String traeDisplayName(JSONObject o, String fallback) {
        JSONObject dc = o.optJSONObject("display_config");
        if (dc == null) dc = o.optJSONObject("DisplayConfig");
        if (dc != null) {
            String n = dc.optString("display_name", dc.optString("DisplayName", ""));
            if (!n.isEmpty()) return n;
        }
        return o.optString("display_name", fallback);
    }

    /**
     * 上下文窗口取 context_window_tokens.dev（**不是 max**）：
     * 真实条目形如 {dev:200000, max:1000000}，max 需开 Max 模式才可用，
     * 采信 max 会让客户端以为有 1M 窗口、实际请求被上游拒。
     */
    private static int readContextWindow(JSONObject o) {
        Object raw = o.opt("context_window_tokens");
        if (raw == null) raw = o.opt("ContextWindowTokens");
        if (raw instanceof JSONObject) {
            JSONObject c = (JSONObject) raw;
            int dev = c.optInt("dev", 0);
            if (dev > 0) return dev;
            int max = c.optInt("max", 0);
            if (max > 0) return max;
        }
        int direct = o.optInt("context_window", o.optInt("contextWindow", 0));
        return direct > 0 ? direct : 200000;
    }

    /** 输出上限取 model_detail_list 里的 max_tokens。 */
    private static int readMaxOutput(JSONObject o) {
        Object raw = o.opt("model_detail_list");
        if (raw == null) raw = o.opt("ModelDetailList");
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                if (d == null) continue;
                int mt = d.optInt("max_tokens", d.optInt("MaxTokens", 0));
                if (mt > 0) return mt;
            }
        }
        return 0;
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        String[] ids = {"DeepSeek-V4-Flash", "DeepSeek-V4-Pro", "DeepSeek-V4-Flash-Official",
                "glm-5.2", "glm-5-turbo", "glm-5", "kimi-k3", "kimi-k2.7-code", "kimi-k2.6",
                "minimax-m3", "qwen-3.7-plus", "Doubao-Seed-2.1-Pro", "Doubao-Seed-2.1-Turbo",
                "Doubao-Seed-2.0-Code", "sagitta", "aquila"};
        for (String s : ids) l.add(ModelEntry.fallback(ID, s, 200000, 32000, false));
        return l;
    }

    // ---------------- chat ----------------

    /** 请求侧诊断摘要：模型 / max_tokens / 角色序列（含 tool_call_id）/ 工具数。 */
    private static String summarize(JSONObject body) {
        try {
            StringBuilder sb = new StringBuilder("model=").append(body.optString("model"));
            sb.append(" function=").append(body.optString("function", channelFor(body.optString("model"))));
            Object mt = body.opt("max_tokens");
            sb.append(" max_tokens=").append(mt == null ? "-" : String.valueOf(mt));
            org.json.JSONArray ms = body.optJSONArray("messages");
            sb.append(" msgs=");
            int toolRes = 0;
            if (ms == null) {
                sb.append('-');
            } else {
                sb.append('[');
                for (int i = 0; i < ms.length(); i++) {
                    org.json.JSONObject m = ms.optJSONObject(i);
                    if (m == null) continue;
                    if (i > 0) sb.append(',');
                    String r = m.optString("role", "?");
                    if ("assistant".equals(r) && m.has("tool_calls")) {
                        sb.append("asst(tc)");
                    } else if ("tool".equals(r)) {
                        toolRes++;
                        sb.append("tool:").append(m.optString("tool_call_id", "-"));
                    } else {
                        sb.append(r.length() > 4 ? r.substring(0, 4) : r);
                    }
                }
                sb.append(']');
            }
            sb.append(" toolResults=").append(toolRes);
            org.json.JSONArray ts = body.optJSONArray("tools");
            sb.append(" tools=").append(ts == null ? 0 : ts.length());
            return sb.toString();
        } catch (Exception e) {
            return "summarize-failed:" + e.getClass().getSimpleName();
        }
    }

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        // TRAE 的 llm_raw_chat 收的是 {"model_name", "message"(密文)}。
        // messages 先转成 parts 形态，再整体加密进 message 字段。
        try {
            com.aigetway.tattoo7.core.App.get().logSink().log("TRAE-REQ", summarize(body));
        } catch (Exception ignored) {
        }
        JSONObject built = buildRawChatBody(body);
        requestInputEstimate = com.aigetway.tattoo7.gateway.ContextGovernor
                .estimateMessagesTokens(built.optJSONArray("messages"));
        String json = built.toString();
        Map<String, String> h = headers();
        h.put("Accept", "text/event-stream");
        try {
            return post(AGENT_HOST + CHAT_PATH, h, json);
        } catch (UpstreamHttpException e) {
            // 服务端说 token 过期（APIG.0602 / "security token has expired"）但本地
            // expiresAt 还没到 —— 强制刷新指纹后重试一次，别让整个服务商不可用。
            if (isTokenExpiredError(e)) {
                forceRefresh();
                Map<String, String> h2 = headers();
                h2.put("Accept", "text/event-stream");
                return post(AGENT_HOST + CHAT_PATH, h2, json);
            }
            // 瞬时网络/上游抖动（空响应、5xx）重试一次
            if (e.code >= 500 || e.code == 0) {
                sleepQuietly(1500);
                Map<String, String> h3 = headers();
                h3.put("Accept", "text/event-stream");
                return post(AGENT_HOST + CHAT_PATH, h3, json);
            }
            throw e;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * TRAE 的 messages.content 是多模态数组类型（Go: []*LLMRawMessageContent），
     * 客户端按 OpenAI 规范发字符串时会被拒：
     *   "cannot unmarshal string into Go struct field LLMRawMessage.messages.content"
     * 这里把字符串 content 规范化为 [{type:"text", text:"..."}]。
     */
    private static void normalizeMessages(JSONObject body) {
        org.json.JSONArray msgs = body.optJSONArray("messages");
        if (msgs == null) return;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            // role:"tool" 原样透传（vc55 回退 vc54 的 tool→user 转换）：
            // 对拍参考成品 d2/A：上游响应侧 tool_call 元素就是 function_call 包裹，
            // 断流真因是响应侧丢工具调用，不是请求侧格式——思考行为证明模型能看到
            // 工具结果（收到后思考、随即要调下一个工具）。参考成品对 role:"tool"
            // 不做任何转换（全包无 "tool" 角色常量），只统一 parts 化，照抄。
            Object c = m.opt("content");
            if (c instanceof String) {
                JSONArray arr = new JSONArray();
                JSONObject part = new JSONObject();
                try {
                    part.put("type", "text");
                    part.put("text", (String) c);
                    arr.put(part);
                    m.put("content", arr);
                } catch (org.json.JSONException ignored) {
                }
            }
        }
    }

    /**
     * 构造 llm_utils_chat 请求体（明文，不加密 —— 三个参考实现交叉验证后确认
     * 这条链路不需要 TraeCrypto；只有 /api/ide/v1/llm_raw_chat 才需要）。
     *
     * 必填字段（TraeWeb 参考实现 SPEC §4.4）：
     *   function    = "solo_work_lite"
     *   config_name = 模型名
     *   model       = 模型名
     *   stream      = true
     */
    private JSONObject buildRawChatBody(JSONObject body) throws IOException {
        try {
            JSONObject b = new JSONObject(body.toString());
            normalizeMessages(b);
            normalizeTools(b);
            String requestedModel = b.optString("model", "");
            String modelName = requestedModel.split("__", 2)[0];
            if (modelName.isEmpty()) throw new IOException("TRAE model is empty");
            b.put("stream", true);
            b.put("function", channelFor(modelName));
            b.put("config_name", modelName);
            b.put("model", modelName);
            if (!b.has("max_tokens")) {
                b.put("max_tokens", 32000);
            } else {
                // 客户端可能声明 131072 等超出 SOLO 普通通道上限的值。
                long requested = b.optLong("max_tokens", 32000);
                if (requested <= 0) b.put("max_tokens", 32000);
                else if (requested > 64000) b.put("max_tokens", 64000);
            }
            pendingModel = modelName;
            return b;
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }
    }

    private static final String SOLO_FUNCTION = "solo_work_lite";

    /**
     * TRAE llm_utils_chat 的 tools schema 差异（对拍参考成品 AI.apk，x/t.smali y() 方法）：
     * 1. tools[].function.parameters 上游要求是【字符串】（schema 序列化后的 JSON 字符串），
     *    客户端按 OpenAI 规范发对象会被拒：
     *    "cannot unmarshal object into Go struct field
     *     FunctionDefinition.tools.function.parameters of type string"
     *    参考实现：opt("parameters") 是对象就 toString() 写回。
     * 2. assistant 消息 tool_calls[].function 上游叫 function_call：
     *    参考实现把 function 对象改名 function_call 写回，name 为空的 tool_call 丢弃，
     *    处理完为空则移除 tool_calls 字段。
     * 3. tool_choice 为字符串：trim；"none"（忽略大小写）→ 移除 tool_choice/tools/functions；
     *    否则转小写保留。
     * 4. tool_choice 为对象 {"type":"function","function":{"name":X}} → tool_choice = X；
     *    {"type":"none"} → 移除三件套；auto/required → 保留小写字符串；未识别 → 移除。
     * 5. tools 数组处理完为空 → 移除 tools 字段（不发空数组）。
     */
    private static void normalizeTools(JSONObject body) {
        // 1+5. tools[].function.parameters 对象 → 字符串；空数组移除
        org.json.JSONArray tools = body.optJSONArray("tools");
        if (tools != null) {
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn != null) {
                    Object p = fn.opt("parameters");
                    if (p instanceof JSONObject) {
                        try {
                            fn.put("parameters", p.toString());
                        } catch (org.json.JSONException ignored) {
                        }
                    }
                }
                out.put(t);
            }
            try {
                if (out.length() == 0) body.remove("tools");
                else body.put("tools", out);
            } catch (org.json.JSONException ignored) {
            }
        } else if (body.has("tools")) {
            body.remove("tools");
        }

        // 2. assistant 消息 tool_calls[].function → function_call
        org.json.JSONArray msgs = body.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null || !"assistant".equals(m.optString("role", ""))) continue;
                org.json.JSONArray tcs = m.optJSONArray("tool_calls");
                if (tcs == null) continue;
                org.json.JSONArray out = new org.json.JSONArray();
                for (int j = 0; j < tcs.length(); j++) {
                    JSONObject tc = tcs.optJSONObject(j);
                    if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    if (fn != null) {
                        try {
                            tc.put("function_call", fn);
                            tc.remove("function");
                        } catch (org.json.JSONException ignored) {
                        }
                    }
                    JSONObject fc = tc.optJSONObject("function_call");
                    String name = fc != null ? fc.optString("name", "") : "";
                    if (name.trim().length() > 0) out.put(tc);
                }
                try {
                    if (out.length() == 0) m.remove("tool_calls");
                    else m.put("tool_calls", out);
                } catch (org.json.JSONException ignored) {
                }
            }
        }

        // 3+4. tool_choice
        Object tc = body.opt("tool_choice");
        try {
            if (tc instanceof String) {
                String s = ((String) tc).trim();
                if (s.length() == 0) {
                    body.remove("tool_choice");
                } else if ("none".equalsIgnoreCase(s)) {
                    body.remove("tool_choice");
                    body.remove("tools");
                    body.remove("functions");
                } else {
                    body.put("tool_choice", s.toLowerCase(java.util.Locale.ROOT));
                }
            } else if (tc instanceof JSONObject) {
                JSONObject o = (JSONObject) tc;
                String type = o.optString("type", "").trim().toLowerCase(java.util.Locale.ROOT);
                if ("function".equals(type)) {
                    JSONObject f = o.optJSONObject("function");
                    String name = (f != null ? f.optString("name", "") : o.optString("name", "")).trim();
                    if (name.length() > 0) body.put("tool_choice", name);
                    else body.remove("tool_choice");
                } else if ("none".equals(type)) {
                    body.remove("tool_choice");
                    body.remove("tools");
                    body.remove("functions");
                } else if ("auto".equals(type) || "required".equals(type)) {
                    body.put("tool_choice", type);
                } else {
                    body.remove("tool_choice");
                }
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    /** 本次请求要回传的模型名。 */
    private volatile String pendingModel = "";

    /** 本次请求治理后的输入估算（喂给 PreparedChat 做 usage 校准）。 */
    private volatile long requestInputEstimate = 0L;

    /** 上游是否在说"令牌已过期"。 */
    private static boolean isTokenExpiredError(UpstreamHttpException e) {
        if (e.code != 400 && e.code != 401 && e.code != 403) return false;
        String m = (e.friendly == null ? "" : e.friendly).toLowerCase();
        return m.contains("apig.0602") || m.contains("token has expired")
                || m.contains("token expired") || m.contains("invalid token");
    }

    /** 忽略本地过期判断，强制刷新一次指纹。 */
    private synchronized void forceRefresh() {
        try {
            JSONObject c = cred();
            if (c == null) return;
            String rt = c.optString("refreshToken", "");
            if (!rt.isEmpty()) exchange(rt);
        } catch (Exception ignored) {
        }
    }

    private PreparedChat post(String url, Map<String, String> h, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        // 推理模型首 token 可能要等很久（90s+），读超时给足 10 分钟
        c.setReadTimeout(600000);
        for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = c.getErrorStream();
            String eb = "";
            if (es != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = es.read(buf)) > 0) bo.write(buf, 0, n);
                eb = bo.toString("UTF-8");
            }
            c.disconnect();
            throw new UpstreamHttpException(code, eb);
        }
        // 上游 200 但可能一个事件都不发（客户端会判"空响应"）。
        // 这里探一下第一行：拿到就能知道是被秒拒（有错误报文）还是纯空流。
        try {
            InputStream probe = c.getInputStream();
            java.io.PushbackInputStream pin = new java.io.PushbackInputStream(probe, 8192);
            int first = pin.read();
            if (first < 0) {
                c.disconnect();
                throw new UpstreamHttpException(502,
                        "TRAE 上游返回 200 但响应体为空（请求被秒拒）。"
                        + "已发送请求体前 300 字节: " + json.substring(0, Math.min(300, json.length())));
            }
            pin.unread(first);
            PreparedChat pc = new PreparedChat(pin, null, new SseTranslator.Trae(pendingModel));
            pc.requestInputEstimate = requestInputEstimate;
            return pc;
        } catch (UpstreamHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TRAE 读取响应失败: " + e.getMessage(), e);
        }
    }

    public void openChat(JSONObject body, OutputStream out) throws IOException {
        prepareChat(body).streamTo(out);
    }

    // ---------------- 账户 / 积分 / 签到 ----------------

    @Override
    public boolean supportsCheckin() {
        return true;
    }

    @Override
    public AccountSnapshot account() {
        AccountSnapshot s = new AccountSnapshot(ID, displayName());
        s.supportsCheckin = true;
        JSONObject c = cred();
        if (c == null) {
            s.credits = "未登录";
            return s;
        }
        s.loggedIn = true;
        s.accountName = c.optString("nickname", c.optString("uid", ""));
        try {
            // 1) 签到状态：只取签到态（checked_in），这里的 credits 是「每日签到赠送额度」
            //    （实测恒 100/150），不是账号总余额 —— 旧代码把它当总余额导致界面永远显示 100。
            Http.Resp r = Http.postJson(UG_HOST + CHECKIN_STATUS_PATH, checkinHeaders(), "{}", 15000);
            JSONObject j = Http.json(r);
            s.checkedInToday = j.optBoolean("checked_in", false);
            s.checkinHint = s.checkedInToday ? "今日已签到" : "今日未签到";
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        // 2) 真实余额：ide_user_ent_usage 端点，remain = Σ(credits_limit - credits_amount)。
        //    与签到查询分开捕获：余额失败不应抹掉签到态，签到失败也不应掩盖余额。
        try {
            refreshCredits(s);
        } catch (Exception e) {
            if (s.error == null || s.error.isEmpty()) s.error = e.getMessage();
        }
        return s;
    }

    /** 拉真实积分余额（永久/临时分桶，对齐 fetchTraeCreditBalance）。 */
    public void refreshCredits(AccountSnapshot s) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("require_usage", true);
            body.put("req_source", 2);
        } catch (org.json.JSONException ignored) {
        }
        Http.Resp r = Http.postJson(UG_HOST + ENT_USAGE_PATH, headers(), body.toString(), 15000);
        JSONObject j = Http.json(r);
        JSONArray packs = j.optJSONArray("user_entitlement_pack_list");
        // 字段缺失/结构不对 → null 语义：显示「—」，不显示 0，也不编造。
        // 注意与「确认为空列表」区分：空列表 = 服务端确认的 0 余额（全新账号/额度耗尽）。
        if (packs == null) return;
        double permanent = 0, expiring = 0;
        long now = System.currentTimeMillis();
        long window = (long) EXPIRING_WINDOW_DAYS * 24 * 3600_000L;
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) continue;
            JSONObject base = pack.optJSONObject("entitlement_base_info");
            if (base == null) continue;
            JSONObject quota = base.optJSONObject("quota");
            if (quota == null) continue;
            double limit = quota.optDouble("credits_limit", 0);
            if (limit <= 0) continue;
            JSONObject usage = pack.optJSONObject("usage");
            double used = usage == null ? 0 : usage.optDouble("credits_amount", 0);
            double remain = limit - used;
            // expire_time 是秒级 Unix 时间戳（0/缺失 = 永久），×1000 才能与毫秒比。
            long expireSec = pack.optLong("expire_time", 0L);
            long expireMs = expireSec > 0 ? expireSec * 1000L : 0L;
            if (expireMs > now && expireMs - now < window) expiring += remain;
            else permanent += remain;
        }
        s.permanentCredits = trim(permanent);
        s.expiringCredits = trim(expiring);
        s.credits = trim(permanent + expiring);
        s.creditsTotal = permanent + expiring;
    }

    /** 整数去掉小数尾巴，小数保留两位（与 BuddyProvider.trim 一致）。 */
    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    @Override
    public String checkin() throws IOException {
        Map<String, String> h = checkinHeaders();
        Http.Resp st = Http.postJson(UG_HOST + CHECKIN_STATUS_PATH, h, "{}", 15000);
        JSONObject sj = Http.json(st);
        int code0 = readCode(sj);
        if (code0 != 0) {
            return "查询签到状态失败 code=" + code0 + " " + readMsg(sj);
        }
        if (sj.optBoolean("checked_in", false)) {
            int daily = (int) sj.optDouble("credits", 0);
            int streak = sj.optInt("streak_days", 0);
            return "今日已签到（每日 " + daily + " 积分，连签 " + streak + " 天）";
        }
        Http.Resp r = Http.postJson(UG_HOST + CHECKIN_CLAIM_PATH, h, "{}", 15000);
        JSONObject j = Http.json(r);
        int code = readCode(j);
        if (code != 0) {
            // 9004 = 请求头/参数不像 SOLO 客户端（历史上就是复用了 chat 的头导致的）
            String tip = code == 9004 ? "（9004 = 请求头未被识别为 SOLO 客户端）" : "";
            return "签到失败 code=" + code + " " + readMsg(j) + tip;
        }
        // 领取响应不带 credits，真实数值要回 status 端点读
        Http.Resp st2 = Http.postJson(UG_HOST + CHECKIN_STATUS_PATH, h, "{}", 15000);
        JSONObject sj2 = Http.json(st2);
        int got = (int) sj2.optDouble("credits", 0);
        int streak = sj2.optInt("streak_days", 0);
        return "签到成功，本次 +" + got + " 积分（连签 " + streak + " 天）";
    }

    /** 签到/积分专用请求头：VSCode SOLO CN 那一套，与 chat 的 headers() 不同。 */
    private Map<String, String> checkinHeaders() {
        return TraeCheckinHeaders.build(cred(), IDE_VERSION);
    }

    /** 业务码可能是数字也可能是字符串，���认数字。 */
    private static int readCode(JSONObject j) {
        Object raw = j.opt("code");
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof String) {
            String t = ((String) raw).trim();
            if (t.matches("-?\\d+")) {
                try {
                    return Integer.parseInt(t);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return raw == null ? 0 : -1;
    }

    private static String readMsg(JSONObject j) {
        String m = j.optString("message", "");
        if (m.isEmpty()) m = j.optString("msg", "");
        return m;
    }
}
