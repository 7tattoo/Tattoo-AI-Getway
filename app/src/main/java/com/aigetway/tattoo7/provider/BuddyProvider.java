package com.aigetway.tattoo7.provider;

import android.content.Context;
import android.content.SharedPreferences;

import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buddy（腾讯 CodeBuddy / WorkBuddy）。
 * 协议来自 _upstream/SPEC-buddy.md：Bearer 直传，无签名；登录为 authUrl + 轮询（非 PKCE）。
 */
public class BuddyProvider implements ServiceProvider {

    public static final String ID = "buddy";

    private static final String PREFS = "svc_buddy";
    private static final String CK = "cred";
    private static final String SK_PEND = "pending_state";

    /**
     * CodeBuddy（中国版）。
     */
    protected String endpoint = "https://copilot.tencent.com";
    protected String apiDomain = "copilot.tencent.com";
    /** Origin / Referer 头用的站点根。 */
    protected String originUrl = "https://copilot.tencent.com";
    protected String platform = "ide";
    protected String productCode = "codebuddy";
    protected String ua = "CodeBuddyIDE/1.106.1";
    protected String billingUa = "WorkBuddy/5.5.6";
    protected String chatUa = "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1";
    /** 是否支持签到。 */
    /** IDE 身份。 */
    protected String ideName = "CodeBuddyIDE";
    protected String ideType = "ide";
    protected String ideVersion = "1.106.1";
    /** 请求体里的 agent 字段。 */
    protected String agent = "cli";
    /** 本次会话 id（base.apk 每次对话新生成）。 */
    protected String conversationId = "wb2api-conv-" + System.nanoTime();
    protected boolean hasCheckin = true;
    protected static final int EXPIRING_WINDOW_DAYS = 15;

    private final SharedPreferences prefs;
    private final com.aigetway.tattoo7.account.AccountPool pool;

    public BuddyProvider(Context ctx) {
        this(ctx, ID);
    }

    /** 按不同 providerId / prefs 名隔离账号池。 */
    protected BuddyProvider(Context ctx, String providerId) {
        this.providerId = providerId;
        this.prefsName = "svc_" + providerId;
        this.prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        this.pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(providerId);
        this.prefsKeyPending = "pending_state_" + providerId;
    }

    /** 运行时 providerId（子类不同），账号池 / prefs 都按它隔离。 */
    protected String providerId = ID;
    protected String prefsName = PREFS;
    protected String prefsKeyPending = SK_PEND;

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public String displayName() {
        return "WorkBuddy / 国内版";
    }

    // ---------------- 凭据 ----------------

    protected JSONObject cred() {
        try {
            com.aigetway.tattoo7.account.AccountPool.Account a = pool.current();
            return a == null ? null : new JSONObject(a.cred);
        } catch (Exception e) {
            return null;
        }
    }

    /** 登录成功：新增一个账号（不覆盖已有账号）。 */
    private void addCred(JSONObject o, String label) {
        pool.addAccount(o.toString(), label);
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

    private String tok() {
        JSONObject c = cred();
        return c == null ? null : c.optString("accessToken", null);
    }

    private boolean expired() {
        JSONObject c = cred();
        if (c == null) return true;
        long exp = c.optLong("expiresAt", 0L);
        return exp > 0 && System.currentTimeMillis() > exp - 60_000L;
    }

    /** 基础头（含 Bearer）。 */
    protected Map<String, String> headers() throws IOException {
        if (!loggedIn()) throw new IOException(displayName() + " 未登录");
        if (expired()) refreshFingerprint();
        JSONObject c = cred();
        String token = c == null ? "" : c.optString("accessToken");
        // billing 端点（积分/签到）与 chat 端点的头不同，见两个 protected 方法
        return billingHeaders(token);
    }

    /** chat 用头（子类可覆写以叠加设备指纹等）。 */
    protected Map<String, String> chatHeaders(String token) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Origin", originUrl);
        h.put("Referer", originUrl + "/");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("X-CodeBuddy-Request", "1");
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        h.put("X-Domain", apiDomain);
        h.put("X-Product", "SaaS");
        h.put("X-Product-Code", productCode);
        String uid = cred() == null ? "" : cred().optString("uid", "");
        if (!uid.isEmpty()) h.put("X-User-Id", uid);
        h.put("User-Agent", ua);
        return h;
    }

    /** billing 用头（积分/签到端点不吃 chat 那套身份）。 */
    protected Map<String, String> billingHeaders(String token) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Origin", originUrl);
        h.put("Referer", originUrl + "/");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("X-CodeBuddy-Request", "1");
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        h.put("X-Domain", apiDomain);
        h.put("X-Product", "SaaS");
        h.put("X-Product-Code", productCode);
        String uid = cred() == null ? "" : cred().optString("uid", "");
        if (!uid.isEmpty()) h.put("X-User-Id", uid);
        h.put("User-Agent", billingUa.isEmpty() ? ua : billingUa);
        return h;
    }

    /** chat 端点用的头（带 token 的便捷版）。 */
    protected Map<String, String> headersForChat() throws IOException {
        if (!loggedIn()) throw new IOException(displayName() + " 未登录");
        if (expired()) refreshFingerprint();
        JSONObject c = cred();
        return chatHeaders(c == null ? "" : c.optString("accessToken"));
    }

    // ---------------- 登录（authUrl + 轮询） ----------------

    /** Step1：向服务端取授权 state 与 authUrl。返回可交给 WebView 的 URL。 */
    public String beginLogin() throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Domain", apiDomain);
        h.put("X-No-Authorization", "true");
        h.put("X-No-User-Id", "true");
        h.put("X-No-Enterprise-Id", "true");
        h.put("X-No-Department-Info", "true");
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("X-CodeBuddy-Request", "1");
        h.put("Origin", originUrl);
        h.put("Referer", originUrl + "/");
        h.put("User-Agent", ua);
        Http.Resp r = Http.postJson(endpoint + "/v2/plugin/auth/state?platform=" + platform, h, null, 15000);
        JSONObject j = Http.json(r);
        int code = j.optInt("code", -1);
        if (code != 0) throw new IOException("获取登录态失败 code=" + code + " " + j.optString("msg", ""));
        JSONObject d = j.optJSONObject("data");
        if (d == null) throw new IOException("响应缺少 data");
        String state = d.optString("state", "");
        String url = d.optString("authUrl", "");
        if (state.isEmpty() || url.isEmpty()) throw new IOException("响应缺少 state/authUrl");
        prefs.edit().putString(prefsKeyPending, state).apply();
        // 记住授权 URL：Google 拒绝内嵌窗口时，可以改用系统浏览器完成授权
        // （本登录是服务端 state 轮询，不依赖网页回调，所以在真浏览器里授权同样有效）
        prefs.edit().putString(prefsKeyPending + "_url", url).apply();
        return url;
    }

    /** 当前待完成登录的授权 URL（没有则空串）。 */
    public String pendingAuthUrl() {
        return prefs.getString(prefsKeyPending + "_url", "");
    }

    /**
     * Step2/3：轮询登录结果。
     * 返回 "pending" | "ok" | "error: 说明"
     */
    public String pollLogin() {
        String state = prefs.getString(prefsKeyPending, "");
        if (state.isEmpty()) return "error: 没有进行中的登录";
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-No-Authorization", "true");
            h.put("User-Agent", ua);
            Http.Resp r = Http.get(endpoint + "/v2/plugin/auth/token?state=" + java.net.URLEncoder.encode(state, "UTF-8"), h, 15000);
            JSONObject j = Http.json(r);
            int code = j.optInt("code", -1);
            if (code == 11217) return "pending";                 // 未就绪，继续轮询
            if (code != 0) return "error: code=" + code + " " + j.optString("msg", "");
            JSONObject d = j.optJSONObject("data");
            if (d == null) return "pending";
            String at = d.optString("accessToken", d.optString("access_token", ""));
            if (at.isEmpty()) return "pending";
            String rt = d.optString("refreshToken", d.optString("refresh_token", ""));
            long exp = jwtExpMs(at);
            if (exp <= 0) exp = System.currentTimeMillis() + d.optLong("expiresIn", 3600) * 1000L;

            // 取账户信息（12151 表示还没好，重试一次）
            Map<String, String> ah = new LinkedHashMap<>();
            ah.put("Authorization", "Bearer " + at);
            ah.put("X-Domain", apiDomain);
            ah.put("X-No-User-Id", "true");
            ah.put("X-No-Enterprise-Id", "true");
            ah.put("User-Agent", ua);
            Http.Resp ar = Http.get(endpoint + "/v2/plugin/login/account?state="
                    + java.net.URLEncoder.encode(state, "UTF-8"), ah, 15000);
            String uid = "", nickname = jwtNickname(at);
            try {
                JSONObject aj = Http.json(ar);
                if (aj.optInt("code", -1) == 12151) return "pending";
                JSONObject ad = aj.optJSONObject("data");
                if (ad != null) uid = ad.optString("uid", "");
            } catch (Exception ignored) {
            }

            JSONObject c = new JSONObject();
            c.put("accessToken", at);
            c.put("refreshToken", rt);
            c.put("expiresAt", exp);
            c.put("uid", uid);
            c.put("nickname", nickname);
            addCred(c, nickname.isEmpty() ? null : nickname);
            prefs.edit().remove(prefsKeyPending).apply();
            prefs.edit().remove(prefsKeyPending + "_url").apply();
            return "ok";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public void logout() {
        pool.clear();
        prefs.edit().clear().apply();
    }

    @Override
    public boolean refreshFingerprint() throws IOException {
        JSONObject c = cred();
        if (c == null) return false;
        String rt = c.optString("refreshToken", "");
        if (rt.isEmpty()) return false;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Domain", apiDomain);
        h.put("User-Agent", ua);
        h.put("Authorization", "Bearer " + c.optString("accessToken"));
        h.put("X-Refresh-Token", rt);
        h.put("X-Auth-Refresh-Source", "ide-main");
        try {
            Http.Resp r = Http.postJson(endpoint + "/v2/plugin/auth/token/refresh", h, null, 15000);
            JSONObject j = Http.json(r);
            if (j.optInt("code", -1) != 0) return false;
            JSONObject d = j.optJSONObject("data");
            if (d == null) return false;
            String at = d.optString("accessToken", c.optString("accessToken"));
            long exp = jwtExpMs(at);
            if (exp <= 0) exp = System.currentTimeMillis() + d.optLong("expiresIn", 3600) * 1000L;
            c.put("accessToken", at);
            c.put("refreshToken", d.optString("refreshToken", rt));
            c.put("expiresAt", exp);
            saveCred(c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** JWT 不验签，只 base64url 解 payload。 */
    private static JSONObject jwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return new JSONObject();
            byte[] p = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return new JSONObject(new String(p, "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static long jwtExpMs(String jwt) {
        return jwtPayload(jwt).optLong("exp", 0L) * 1000L;
    }

    private static String jwtNickname(String jwt) {
        JSONObject p = jwtPayload(jwt);
        String n = p.optString("nickname", "");
        return n.isEmpty() ? p.optString("preferred_username", "") : n;
    }

    // ---------------- 模型 ----------------

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        Map<String, String> h = headersForChat();
        h.put("X-Product", "SaaS");
        h.put("X-Product-Code", productCode);
        Map<String, ModelEntry> out = new LinkedHashMap<>();

        // 权威源：personal models
        try {
            Http.Resp r = Http.get(endpoint + "/console/enterprises/personal/models", h, 20000);
            JSONObject j = Http.json(r);
            collectModels(j, out, true);
        } catch (Exception ignored) {
        }
        // 补充源：/v3/config
        try {
            Http.Resp r = Http.get(endpoint + "/v3/config", h, 20000);
            JSONObject j = Http.json(r);
            collectModels(j, out, false);
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) throw new IOException("Buddy 模型列表拉取失败");
        List<ModelEntry> list = new ArrayList<>(out.values());
        for (ModelEntry e : list) e.live = true;
        return list;
    }

    private void collectModels(JSONObject j, Map<String, ModelEntry> out, boolean authoritative) {
        JSONArray arr = null;
        Object data = j.opt("data");
        if (data instanceof JSONObject) {
            Object m = ((JSONObject) data).opt("models");
            if (m instanceof JSONArray) arr = (JSONArray) m;
        } else if (data instanceof JSONArray) {
            arr = (JSONArray) data;
        }
        if (j.opt("models") instanceof JSONArray) arr = (JSONArray) j.opt("models");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", o.optString("model", ""));
            if (id.isEmpty() || isFiltered(id, o)) continue;
            // 未授权的模型调用时报 11102「only available for authorized users」，
            // /v3/config 这个全局配置源会把它们也列出来 —— 直接剔除，
            // 否则用户会逐个点进去才发现用不了。
            if (!authoritative && isUnauthorized(o)) continue;
            if (out.containsKey(id) && !authoritative) continue;
            // 必须用 id()（运行时 providerId）而不是静态常量 ID。
            ModelEntry e = new ModelEntry(id(), id, o.optString("name", id));
            e.contextWindow = o.optInt("maxInputTokens", 128000);
            e.maxOutput = o.optInt("maxOutputTokens", 8192);
            e.vision = o.optBoolean("supportsImages", false);
            JSONObject rs = o.optJSONObject("reasoning");
            e.reasoning = rs != null && (rs.optJSONArray("supportedEfforts") != null);
            if (e.maxOutput <= 0) e.maxOutput = 8192;
            if (e.contextWindow <= 0) e.contextWindow = 128000;
            // 计费倍率：模型自带 credits + data.modelPromotions 里此刻生效的促销价
            // （解析/促销窗口判活全在 ModelRate）。上游没给就不显示，不编造 x1。
            e.rateText = ModelRate.buddyRate(o, id, j, System.currentTimeMillis());
            out.put(id, e);
        }
    }

    /** 上游是否把该模型标记为「未授权 / 需要开通」。 */
    private static boolean isUnauthorized(JSONObject o) {
        if (o.optBoolean("authorized", true) == false) return true;
        if (o.optBoolean("isAuthorized", true) == false) return true;
        if (o.optBoolean("enabled", true) == false) return true;
        if (o.optInt("authorizationStatus", 1) == 2) return true;
        String s = o.optString("availability", o.optString("access", ""));
        return s.equalsIgnoreCase("unauthorized") || s.equalsIgnoreCase("restricted");
    }

    /**
     * 上游**强制要求首条消息是 system**，否则报
     * {@code 11128 first message is not system prompt}。
     *
     * <p>参考实现 wb_proxy.build_upstream_body：
     * {@code if not messages or messages[0].get("role") != "system":
     *       messages = [{"role":"system","content":SYSTEM_PROMPT}] + messages}
     *
     * <p>OpenAI 规范的客户端常常第一条直接是 user，必须在网关侧补。
     */
    protected static void ensureSystemPrompt(JSONObject body) {
        try {
            org.json.JSONArray msgs = body.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return;
            JSONObject first = msgs.optJSONObject(0);
            if (first != null && "system".equals(first.optString("role"))) return;
            org.json.JSONArray out = new org.json.JSONArray();
            out.put(new JSONObject().put("role", "system")
                    .put("content", "You are a helpful assistant."));
            for (int i = 0; i < msgs.length(); i++) {
                Object m = msgs.opt(i);
                if (m != null) out.put(m);
            }
            body.put("messages", out);
        } catch (Exception ignored) {
        }
    }

    private boolean isFiltered(String id, JSONObject o) {
        if (id.startsWith("nes-") || id.startsWith("completion-") || id.startsWith("codewise-")) return true;
        if (o.optBoolean("supportsExtra", false)) return true;
        // ⚠️ hidden / isHidden / is_invisible_to_user 是「账号无权访问」的权威标记：
        // 这些模型会列在目录里，但一调用就报 11102。不过滤的话用户会在客户端
        // 看到一堆灰色（Hidden）模型，逐个点才发现用不了。
        if (o.optBoolean("hidden", false)) return true;
        if (o.optBoolean("isHidden", false)) return true;
        if (o.optBoolean("is_invisible_to_user", false)) return true;
        if (o.optBoolean("invisible", false)) return true;
        if (o.optInt("maxOutputTokens", 0) > 0 && o.optInt("maxOutputTokens", 0) <= 256) return true;
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                if ("text-to-image".equalsIgnoreCase(tags.optString(i))) return true;
            }
        }
        return false;
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        String[] ids = {"deepseek-v4-flash", "deepseek-v4-pro", "hy4-preview", "glm-5.3", "glm-5.3-flash",
                "glm-5.2", "kimi-k3-1", "kimi-k2.7", "minimax-m3", "deepseek-v4.1-flash"};
        for (String s : ids) l.add(ModelEntry.fallback(id(), s, 128000, 8192, false));
        return l;
    }

    // ---------------- chat ----------------

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        Map<String, String> h = headersForChat();
        h.put("Accept", "text/event-stream");
        h.put("Content-Type", "application/json");
        h.put("X-Agent-Purpose", "conversation");
        h.put("X-IDE-Name", "CodeBuddyIDE");
        h.put("X-IDE-Type", "ide");
        h.put("X-IDE-Version", "1.106.1");

        String model = body.optString("model", "");
        JSONObject b;
        try {
            b = new JSONObject(body.toString());
            b.put("stream", true);
            ensureSystemPrompt(b);
            if (!b.has("max_tokens") || b.optInt("max_tokens", 0) <= 0) {
                for (ModelEntry e : fallbackModels()) {
                    if (e.id.equals(model)) {
                        b.put("max_tokens", e.maxOutput);
                        break;
                    }
                }
            }
            if (!b.has("prompt_cache_key")) {
                JSONObject c = cred();
                b.put("prompt_cache_key", "t7-" + (c == null ? "anon" : c.optString("uid", "anon")));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }

        HttpURLConnectionHolder con = new HttpURLConnectionHolder();
        con.open(endpoint + "/v2/chat/completions", h, b.toString());
        PreparedChat pc = con.toPrepared();
        // 记下治理后的输入估算，流里收到 usage 时按真实 prompt_tokens 校准估算系数
        pc.requestInputEstimate = com.aigetway.tattoo7.gateway.ContextGovernor
                .estimateMessagesTokens(b.optJSONArray("messages"));
        return pc;
    }

    /** 建连、读状态码，失败抛 UpstreamHttpException。 */
    static final class HttpURLConnectionHolder {
        private java.net.HttpURLConnection conn;

        void open(String url, Map<String, String> headers, String json) throws IOException {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            c.setDoOutput(true);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                java.io.InputStream es = c.getErrorStream();
                String eb = "";
                if (es != null) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = es.read(buf)) > 0) bo.write(buf, 0, n);
                    eb = bo.toString("UTF-8");
                }
                c.disconnect();
                throw new UpstreamHttpException(code, eb);
            }
            conn = c;
        }

        PreparedChat toPrepared() throws IOException {
            return new PreparedChat(conn.getInputStream(), null);
        }
    }

    public void openChat(JSONObject body, java.io.OutputStream out) throws IOException {
        PreparedChat pc = prepareChat(body);
        pc.streamTo(out);
    }

    // ---------------- 账户 / 积分 / 签到 ----------------

    @Override
    public boolean supportsCheckin() {
        return hasCheckin;
    }

    @Override
    public boolean supportsPermanentLock() {
        return true;
    }

    private String lockKey() {
        return "lock_permanent_" + providerId;
    }

    public boolean permanentLocked() {
        return prefs.getBoolean(lockKey(), false);
    }

    public void setPermanentLocked(boolean v) {
        prefs.edit().putBoolean(lockKey(), v).apply();
    }

    @Override
    public AccountSnapshot account() {
        AccountSnapshot s = new AccountSnapshot(id(), displayName());
        s.supportsCheckin = true;
        s.supportsLock = true;
        s.permanentLocked = permanentLocked();
        JSONObject c = cred();
        if (c == null) {
            s.credits = "未登录";
            return s;
        }
        s.loggedIn = true;
        s.accountName = c.optString("nickname", c.optString("uid", ""));
        try {
            refreshCredits(s);
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        return s;
    }

    /** 拉积分（同时算永久/临时分桶）。 */
    protected void refreshCredits(AccountSnapshot s) throws IOException {
        Http.Resp r = Http.postJson(endpoint + "/v2/billing/meter/get-user-resource", headers(), "{}", 20000);
        JSONObject j = Http.json(r);
        JSONArray accs = null;
        Object data = j.opt("data");
        if (data instanceof JSONObject) {
            Object resp = ((JSONObject) data).opt("Response");
            if (resp instanceof JSONObject) {
                Object dd = ((JSONObject) resp).opt("Data");
                if (dd instanceof JSONObject) accs = ((JSONObject) dd).optJSONArray("Accounts");
            }
        }
        if (accs == null) {
            s.credits = "—";
            return;
        }
        double permanent = 0, expiring = 0;
        long now = System.currentTimeMillis();
        long window = (long) EXPIRING_WINDOW_DAYS * 24 * 3600_000L;
        for (int i = 0; i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a == null) continue;
            int status = a.optInt("Status", 0);
            long expiredAt = parseTime(a.opt("ExpiredTime"));
            if (status == 3) continue;
            if (expiredAt > 0 && expiredAt < now) continue;
            double remain = a.optDouble("CycleCapacityRemainPrecise", 0);
            if (remain == 0) remain = a.optDouble("CycleCapacityRemain", 0);
            long dedEnd = parseTime(a.opt("DeductionEndTime"));
            if (dedEnd > 0 && dedEnd - now < window) expiring += remain;
            else permanent += remain;
        }
        s.permanentCredits = trim(permanent);
        s.expiringCredits = trim(expiring);
        double total = permanent + expiring;
        s.creditsTotal = total;
        s.credits = permanentLocked()
                ? trim(expiring) + "（已锁永久）"
                : trim(total);
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** 兼容时间戳(秒/毫秒)与 ISO 串。 */
    private static long parseTime(Object v) {
        if (v == null) return 0;
        try {
            if (v instanceof Number) {
                long n = ((Number) v).longValue();
                if (n <= 0) return 0;
                return n < 100000000000L ? n * 1000L : n;
            }
            String s = String.valueOf(v);
            if (s.isEmpty()) return 0;
            try {
                long n = Long.parseLong(s);
                return n < 100000000000L ? n * 1000L : n;
            } catch (NumberFormatException ignored) {
            }
            String iso = s.endsWith("Z") ? s.substring(0, s.length() - 1) + "+0000" : s;
            java.text.SimpleDateFormat f1 = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
            return f1.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String checkin() throws IOException {
        Map<String, String> h = headers();
        h.put("Accept", "application/json");
        Http.Resp sr = Http.postJson(endpoint + "/v2/billing/meter/checkin-activity-status", h, "{}", 15000);
        JSONObject sj = Http.json(sr);
        JSONObject d = sj.optJSONObject("data");
        boolean done = d != null && d.optBoolean("today_checked_in", false);
        if (done) {
            return "今日已签到，连签 " + d.optInt("streak_days", 0) + " 天"
                    + (d.optString("activity_name", "").isEmpty() ? "" : "（" + d.optString("activity_name") + "）");
        }
        Http.Resp r = Http.postJson(endpoint + "/v2/billing/meter/daily-checkin", h, "{}", 15000);
        JSONObject j = Http.json(r);
        int code = j.optInt("code", -1);
        if (code == 10001 || code == 1001) return "今日已签到";
        if (code != 0) return "签到失败 code=" + code + " " + j.optString("msg", "");
        JSONObject dd = j.optJSONObject("data");
        double credit = dd == null ? 0 : dd.optDouble("credit", 0);
        int streak = dd == null ? 0 : dd.optInt("streak_days", 0);
        String msg = dd == null ? "" : dd.optString("message", "");
        return "签到成功 +" + trim(credit) + "，连签 " + streak + " 天" + (msg.isEmpty() ? "" : " · " + msg);
    }

    /** 今日签到状态（供 UI 展示）。 */
    public boolean checkedInToday() {
        try {
            Http.Resp sr = Http.postJson(endpoint + "/v2/billing/meter/checkin-activity-status", headers(), "{}", 15000);
            JSONObject d = Http.json(sr).optJSONObject("data");
            return d != null && d.optBoolean("today_checked_in", false);
        } catch (Exception e) {
            return false;
        }
    }
}
