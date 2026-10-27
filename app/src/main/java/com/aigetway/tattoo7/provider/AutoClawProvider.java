package com.aigetway.tattoo7.provider;

import android.content.Context;

import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.util.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AutoClaw（智谱 autoglm）**国内版**适配器。
 *
 * <p>协议来自 agent2api（aimod-cc/agent2api）的 Rust 实现
 * {@code providers/autoclaw/*}，完整规格见 {@code ref/autoclaw_cn_spec.md}。
 *
 * <p><b>本文件是自包含的</b>：除了账号池 / 模型条目 / Http 这些公共设施，
 * 不依赖任何其它 provider 的代码，也不修改它们。上游失效时按
 * {@code PROGRESS.md} 的「移除 AutoClaw」三步即可整块下线（见文件末注释）。
 *
 * <p>要点：
 * <ul>
 *   <li>国内版唯一登录方式 = 手机号 + 短信验证码（agent-send-code / agent-login）。</li>
 *   <li>所有 userapi 域请求带 {@code X-Auth-Sign = md5(appId&秒级ts&appKey)} 客户端指纹。</li>
 *   <li>LLM 转发认证头是 {@code X-Authorization}（不是 Authorization）；
 *       不发 {@code X-Harness-Type}；system 需前置 OpenClaw 身份前缀。</li>
 * </ul>
 */
public class AutoClawProvider implements ServiceProvider {

    public static final String ID = "autoclaw";

    // ---------------- 站点（region.rs，国内版） ----------------

    private static final String USERAPI_BASE = "https://autoglm-acceleration-api.zhipuai.cn";
    private static final String PROXY_BASE =
            "https://autoglm-acceleration-api.zhipuai.cn/autoclaw-proxy/proxy/autoclaw";
    /** 配置基址 = 代理基址砍掉尾部 /autoclaw 再补 /proxy/（catalog.rs config_base_url）。 */
    private static final String CONFIG_BASE =
            "https://autoglm-acceleration-api.zhipuai.cn/autoclaw-proxy/proxy/";

    // ---------------- 签名常量（refresh.rs） ----------------

    private static final String AUTH_APP_ID = "100003";
    private static final String AUTH_APP_KEY = "38d2391985e2369a5fb8227d8e6cd5e5";
    /** userapi 域版本门控（refresh.rs CLIENT_VERSION）。 */
    private static final String CLIENT_VERSION = "1.18.5";
    /** LLM 转发域版本（adapter.rs DESKTOP_APP_VERSION）。 */
    private static final String DESKTOP_APP_VERSION = "1.17.8";

    private static final String OK_CODE = "0";

    private final com.aigetway.tattoo7.account.AccountPool pool;

    public AutoClawProvider(Context ctx) {
        this.pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(ID);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "AutoClaw / 国内版";
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

    private String token() {
        JSONObject c = cred();
        if (c == null) return "";
        // 兜底剥 "Bearer " 前缀：老版本落库的凭据可能带前缀（双拼会 401）。
        return stripBearer(c.optString("token", ""));
    }

    @Override
    public boolean loggedIn() {
        return !token().isEmpty();
    }

    public com.aigetway.tattoo7.account.AccountPool pool() {
        return pool;
    }

    /** 上游报错时切到下一个可用账号，返回是否切换成功。 */
    public boolean rotateAccount() {
        return pool.rotate() != null;
    }

    // ---------------- 签名头（refresh.rs signed_auth_headers） ----------------

    /**
     * userapi 域签名头：{@code X-Auth-Sign = md5("{appId}&{秒级ts}&{appKey}")}。
     *
     * <p>这套头是 AutoClaw 桌面端客户端的固定指纹，缺任一个都会被判签名失败
     * （code 400002）。{@code X-Harness-Type: zcode} 只在这条 userapi 链路发，
     * LLM 转发域**不发**（会被 403 pay-view / 406，见 adapter.rs）。
     *
     * @param token 为 null/空时不带 authorization（登录时还没有 token）。
     */
    private static Map<String, String> signedHeaders(String token) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String sign = md5Hex(AUTH_APP_ID + "&" + ts + "&" + AUTH_APP_KEY);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "*/*");
        h.put("X-Version", CLIENT_VERSION);
        h.put("X-Product", "autoclaw");
        h.put("X-Client-Type", "pc");
        h.put("X-Harness-Type", "zcode");
        h.put("X-Tm", platformTm());
        h.put("X-Lang", "zh-CN");
        h.put("X-Channel", "official");
        h.put("X-Auth-Appid", AUTH_APP_ID);
        h.put("X-Auth-TimeStamp", ts);
        h.put("X-Auth-Sign", sign);
        h.put("X-Trace-Id", UUID.randomUUID().toString().replace("-", ""));
        if (token != null && !token.isEmpty()) h.put("authorization", "Bearer " + token);
        return h;
    }

    /** 平台标识：**绝不发 linux**（被绑为 Windows 的账号收到会 403）。 */
    private static String platformTm() {
        return "win";
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- 短信登录（login.rs） ----------------

    /** 生成设备 id：32 字节随机 → 64 位小写 hex（官方是 ed25519 公钥 SHA-256，服务端只当不透明串）。 */
    private static String newDeviceId() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 归一化手机号：容忍 +86 / 86 前缀与空格、连字符；只认 11 位大陆号码。 */
    private static String normalizePhone(String raw) throws IOException {
        String t = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (t.startsWith("+86")) t = t.substring(3);
        else if (t.startsWith("86") && t.length() == 13) t = t.substring(2);
        if (t.length() == 11 && t.charAt(0) == '1'
                && t.charAt(1) >= '2' && t.charAt(1) <= '9' && t.matches("\\d{11}")) {
            return t;
        }
        throw new IOException("请填写 11 位中国大陆手机号");
    }

    /**
     * 下发验证码。返回 device_id —— **必须**在随后的 login 里沿用
     * （上游把设备与登录会话关联，两次用不同 device_id 会让刚发的码配不上设备）。
     */
    public String sendCode(String phone) throws IOException {
        String p = normalizePhone(phone);
        String deviceId = newDeviceId();
        JSONObject body = new JSONObject();
        try {
            body.put("phone", p);
            body.put("source_id", "autoclaw");
            body.put("device_id", deviceId);
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        JSONObject resp = postUserapi("/userapi/v1/agent-send-code", body, "", "发送验证码");
        long code = resp.optLong("code", 0);
        if (code != 0) throw new IOException(describeLoginError(code, resp.optString("msg", "")));
        JSONObject data = resp.optJSONObject("data");
        boolean delivered = data != null && data.optBoolean("result", false);
        if (!delivered) throw new IOException("验证码发送失败，请稍后重试");
        return deviceId;
    }

    /**
     * 手机号 + 验证码登录，成功落库并返回 true。
     *
     * <p>⚠️ {@code code} 必须是 **JSON 数字**（传字符串上游回 400001 参数错误）。
     */
    public boolean loginWithCode(String phone, String code, String deviceId) throws IOException {
        String p = normalizePhone(phone);
        String digits = code == null ? "" : code.trim();
        if (!digits.matches("\\d{6}")) throw new IOException("请填写 6 位数字验证码");
        int codeNum = Integer.parseInt(digits);
        if (deviceId == null || deviceId.trim().isEmpty()) deviceId = newDeviceId();
        JSONObject body = new JSONObject();
        try {
            body.put("phone", p);
            body.put("code", codeNum);
            body.put("platform", "web");
            body.put("source_id", "autoclaw");
            body.put("device_id", deviceId);
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        // ⚠️ 路径不能带尾斜杠：上游会把 /agent-login/ 307 重定向到 /agent-login，
        // 而 HttpURLConnection 的 POST 不跟随 3xx → 真机表现为「登录返回 HTTP 307」。
        // （参考实现 LOGIN_PATH 带尾斜杠但其 HTTP 栈 redirect:'manual'+手动跟随；
        //  2026-10-16 实测无尾斜杠直接 200。）
        JSONObject resp = postUserapi("/userapi/v1/agent-login", body, "", "登录");
        long status = resp.optLong("code", 0);
        if (status != 0) throw new IOException(describeLoginError(status, resp.optString("msg", "")));
        JSONObject data = resp.optJSONObject("data");
        // ⚠️ 上游返回的 access_token 自带 "Bearer " 前缀，必须剥掉再落库——
        // 否则转发/查询时再拼一层 "Bearer Bearer eyJ..."，上游判登录态过期。
        // （refresh 链路参考实现同样过 crypto::strip_bearer；login 链漏了这一步。）
        String access = stripBearer(data == null ? "" : data.optString("access_token", ""));
        if (access.isEmpty()) throw new IOException("登录成功但上游没有返回 access_token");
        String refresh = stripBearer(data == null ? "" : data.optString("refresh_token", ""));

        JSONObject c = new JSONObject();
        try {
            c.put("token", access);
            if (!refresh.isEmpty()) c.put("refreshToken", refresh);
            c.put("deviceId", deviceId);
            c.put("phone", maskPhone(p));
            c.put("expiresAt", System.currentTimeMillis() + 6L * 3600_000L);
        } catch (org.json.JSONException e) {
            throw new IOException("save failed: " + e.getMessage());
        }
        pool.addAccount(c.toString(), maskPhone(p));
        return true;
    }

    private static String maskPhone(String phone) {
        if (phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 业务码 → 人话（上游参数类错误一律 400001，直接透出对用户没有指导意义）。 */
    private static String describeLoginError(long code, String upstreamMsg) {
        switch ((int) code) {
            case 400001: return "请求参数有问题（手机号格式或验证码类型不对）";
            case 630202: return "验证码错误，请重新输入";
            case 631002: return "当前客户端版本已停止服务，请稍后重试";
            default:
                return upstreamMsg == null || upstreamMsg.isEmpty()
                        ? ("登录失败 code=" + code) : upstreamMsg;
        }
    }

    /** 发一次带签名的 userapi POST。 */
    private JSONObject postUserapi(String path, JSONObject body, String token, String what)
            throws IOException {
        Http.Resp r = Http.postJson(USERAPI_BASE + path, signedHeaders(token), body.toString(), 20000);
        if (!r.ok()) throw new IOException(what + "返回 HTTP " + r.code);
        return Http.json(r);
    }

    // ---------------- token 刷新（refresh.rs） ----------------

    /**
     * 刷新当前账号 token。{@code /userapi/v1/refresh} 返回 code 400002（签名校验失败）
     * 时降级重试 {@code /userapi/v1/agent-refresh}。成功写回账号池。
     */
    @Override
    public boolean refreshFingerprint() throws IOException {
        JSONObject c = cred();
        if (c == null) return false;
        String cur = c.optString("token", "");
        String rt = c.optString("refreshToken", "");
        if (cur.isEmpty()) return false;
        JSONObject payload = refreshCall("/userapi/v1/refresh", cur, rt, c.optString("deviceId", ""));
        if (payload.optLong("code", 0) == 400002) {
            payload = refreshCall("/userapi/v1/agent-refresh", cur, rt, c.optString("deviceId", ""));
        }
        long code = payload.optLong("code", 0);
        JSONObject data = payload.optJSONObject("data");
        String access = data == null ? "" : stripBearer(data.optString("access_token", ""));
        if (code != 0 || access.isEmpty()) {
            if (code == 401 || code == 410000) throw new IOException("登录态已过期，请重新登录");
            throw new IOException("token 刷新失败 code=" + code);
        }
        String newRt = data.optString("refresh_token", "");
        try {
            c.put("token", access);
            if (!newRt.isEmpty()) c.put("refreshToken", stripBearer(newRt));
            c.put("expiresAt", System.currentTimeMillis() + 6L * 3600_000L);
        } catch (org.json.JSONException e) {
            throw new IOException("save failed: " + e.getMessage());
        }
        pool.updateCred(pool.activeId(), c.toString());
        return true;
    }

    private JSONObject refreshCall(String path, String token, String refreshToken, String deviceId)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refreshToken);
            body.put("source_id", "autoclaw");
            if (!deviceId.isEmpty()) body.put("device_id", deviceId);
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        return postUserapi(path, body, token, "刷新");
    }

    /** 最近一次刷新失败原因（供 UI 展示）。 */
    private String lastRefreshError = "";

    @Override
    public String lastRefreshError() {
        return lastRefreshError;
    }

    /**
     * 刷新**指定账号**（多账号服务商不能只刷「当前账号」）：
     * 按 uid / id 切到目标账号 → 刷新 → 恢复原激活账号。
     */
    @Override
    public boolean refreshFingerprintForUid(String uidOrId) throws IOException {
        com.aigetway.tattoo7.account.AccountPool.Account target = null;
        if (uidOrId != null && !uidOrId.isEmpty()) {
            for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
                if (uidOrId.equals(a.uid) || uidOrId.equals(a.id)) { target = a; break; }
            }
        }
        String prev = pool.activeId();
        boolean switched = false;
        try {
            if (target != null && !target.id.equals(prev)) {
                pool.setActive(target.id);
                switched = true;
            }
            boolean ok = refreshFingerprint();
            lastRefreshError = ok ? "" : "该账号无 refresh_token 或未登录";
            return ok;
        } catch (Exception e) {
            lastRefreshError = e.getMessage() == null ? e.toString() : e.getMessage();
            return false;
        } finally {
            if (switched) pool.setActive(prev);
        }
    }

    private static String stripBearer(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) return t.substring(7).trim();
        return t;
    }

    // ---------------- 模型（catalog.rs / models.rs） ----------------

    /** 默认路由（models.rs DEFAULT_ROUTE）。 */
    private static final String DEFAULT_ROUTE = "zai_auto";
    /** 已知路由前缀（**顺序有意义**：先 zaicoding_ 再 zai_）。 */
    private static final String[] KNOWN_ROUTE_PREFIXES = {"zaicoding_", "zai_"};

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        if (!loggedIn()) throw new IOException("AutoClaw 未登录");
        Http.Resp r = Http.get(CONFIG_BASE + "autoclaw-model-config",
                signedHeaders(token()), 15000);
        JSONObject j = Http.json(r);
        // 顶层直接是 {"models":[...]}，**没有 {code,data} 信封**
        JSONArray arr = j.optJSONArray("models");
        List<ModelEntry> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                ModelEntry e = normalizeEntry(arr.opt(i));
                if (e != null) out.add(e);
            }
        }
        if (out.isEmpty()) throw new IOException("AutoClaw 模型列表拉取失败");
        return out;
    }

    /** 把一条上游条目归一成 ModelEntry（catalog.rs normalize_entry）。 */
    private ModelEntry normalizeEntry(Object item) {
        String routeId;
        String name;
        boolean vision = false;
        boolean reasoning = true;
        int ctx = 0;
        int maxOut = 8192;
        if (item instanceof String) {
            routeId = ((String) item).trim();
            if (routeId.isEmpty()) return null;
            name = stripRoutePrefix(routeId).toUpperCase(Locale.US);
        } else if (item instanceof JSONObject) {
            JSONObject o = (JSONObject) item;
            routeId = o.optString("id", "").trim();
            if (routeId.isEmpty()) return null;
            String rawName = o.optString("name", "").trim();
            name = rawName.isEmpty() ? stripRoutePrefix(routeId).toUpperCase(Locale.US) : rawName;
            JSONArray input = o.optJSONArray("input");
            if (input != null) {
                for (int i = 0; i < input.length(); i++) {
                    if ("image".equals(input.optString(i))) { vision = true; break; }
                }
            }
            reasoning = o.optBoolean("reasoning", true);
            ctx = o.optInt("contextWindow", 0);
            maxOut = o.optInt("maxTokens", 8192);
        } else {
            return null;
        }
        String id = stripRoutePrefix(routeId);
        ModelEntry e = new ModelEntry(ID, id, name);
        e.vision = vision;
        e.reasoning = reasoning;
        // 倍率：上游只给「积分消耗等级」档位文案（实测 低/中/高，creditConsumptionLevel），
        // 官方都没有「档位→数字」映射（参考实现已穷举确认）—— 原样透传，不换算成 x?.??。
        if (item instanceof JSONObject) {
            String lvl = ((JSONObject) item).optString("creditConsumptionLevel", "").trim();
            if (!lvl.isEmpty()) e.rateText = lvl;
        }
        e.contextWindow = ctx > 0 ? ctx : 1048576;
        e.maxOutput = maxOut;
        e.live = true;
        // 路由 ID 随条目携带（转发时要发 X-Request-Model）—— 用 displayName 之外的
        // 字段承载不可行，这里放进 provider 私有的静态映射（见 routeForModel）。
        rememberRoute(id, name, routeId);
        return e;
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        l.add(fb("glm-5.3", "GLM-5.3", "zaicoding_glm-5.3", 1048576, 307200, false));
        l.add(fb("glm-5.3-flash", "GLM-5.3-Flash", "zai_glm-5.3-flash", 1048576, 131072, true));
        return l;
    }

    private ModelEntry fb(String id, String name, String routeId, int ctx, int maxOut, boolean vision) {
        ModelEntry e = new ModelEntry(ID, id, name);
        e.contextWindow = ctx;
        e.maxOutput = maxOut;
        e.vision = vision;
        e.reasoning = true;
        e.live = false;
        rememberRoute(id, name, routeId);
        return e;
    }

    // ---------------- 路由记忆（id → routeId） ----------------

    /** 目录 id / 展示名（小写）→ 完整路由 ID。进程内缓存，供 prepareChat 发 X-Request-Model。 */
    private static final Map<String, String> ROUTE_BY_ID =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void rememberRoute(String id, String routeId) {
        if (id != null && !id.isEmpty() && routeId != null && !routeId.isEmpty()) {
            ROUTE_BY_ID.put(id.toLowerCase(Locale.US), routeId);
        }
    }

    private static void rememberRoute(String id, String name, String routeId) {
        rememberRoute(id, routeId);
        if (name != null && !name.isEmpty() && routeId != null && !routeId.isEmpty()) {
            ROUTE_BY_ID.put(name.toLowerCase(Locale.US), routeId);
        }
    }

    /** 剥已知路由前缀（**顺序：先 zaicoding_ 再 zai_**）。 */
    static String stripRoutePrefix(String routeId) {
        if (routeId == null) return "";
        String t = routeId.trim();
        for (String p : KNOWN_ROUTE_PREFIXES) {
            if (t.startsWith(p)) return t.substring(p.length());
        }
        return t;
    }

    /** 解析客户端传入的 model → (routeId, bodyModelId)。 */
    private String[] resolveRoute(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return new String[]{DEFAULT_ROUTE, stripRoutePrefix(DEFAULT_ROUTE)};
        String cached = ROUTE_BY_ID.get(name.toLowerCase(Locale.US));
        if (cached != null) return new String[]{cached, stripRoutePrefix(cached)};
        for (String p : KNOWN_ROUTE_PREFIXES) {
            if (name.startsWith(p)) return new String[]{name, stripRoutePrefix(name)};
        }
        // 未知 → 默认路由（与参考实现一致：不静默换模型只在目录命中时避免）
        return new String[]{DEFAULT_ROUTE, stripRoutePrefix(DEFAULT_ROUTE)};
    }

    // ---------------- chat 转发（adapter.rs） ----------------

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        String tk = token();
        if (tk.isEmpty()) throw new IOException("AutoClaw 未登录");
        JSONObject b;
        try {
            b = new JSONObject(body.toString());
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }
        String[] route = resolveRoute(b.optString("model", ""));
        try {
            b.put("model", route[1]);
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }
        normalizeMaxTokens(b);
        AutoClawPrompt.normalize(b);

        Map<String, String> h = brandHeaders();
        h.put("Content-Type", "application/json");
        h.put("Accept", "*/*");
        // ⚠️ 认证头是 X-Authorization，不是 Authorization（发错稳定 401）
        h.put("X-Authorization", "Bearer " + tk);
        h.put("X-Request-Id", UUID.randomUUID().toString().replace("-", ""));
        h.put("X-Request-Model", route[0]);

        HttpURLConnection c = (HttpURLConnection)
                new java.net.URL(PROXY_BASE + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(600000);
        for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String eb = "";
            java.io.InputStream es = c.getErrorStream();
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
        PreparedChat pc = new PreparedChat(c.getInputStream(), null);
        pc.requestInputEstimate = com.aigetway.tattoo7.gateway.ContextGovernor
                .estimateMessagesTokens(b.optJSONArray("messages"));
        return pc;
    }

    /** LLM 转发域品牌头（adapter.rs brand_headers，**不发 X-Harness-Type**）。 */
    private static Map<String, String> brandHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Product", "autoclaw");
        h.put("X-Client-Type", "pc");
        h.put("X-Tm", platformTm());
        h.put("X-Version", DESKTOP_APP_VERSION);
        h.put("X-Lang", "zh-CN");
        h.put("X-Channel", "official");
        return h;
    }

    /** max_tokens 下限归一：0 < v < 10000 的小正整数被上游预校验拒成 406，抬到 10000。 */
    private static void normalizeMaxTokens(JSONObject b) {
        int v = b.optInt("max_tokens", 0);
        if (v > 0 && v < 10000) {
            try {
                b.put("max_tokens", 10000);
            } catch (org.json.JSONException ignored) {
            }
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
        if (c == null || c.optString("token", "").isEmpty()) {
            s.credits = "未登录";
            return s;
        }
        s.loggedIn = true;
        s.accountName = c.optString("phone", "");
        try {
            refreshCredits(s);
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        return s;
    }

    /** 积分查询（balance.rs）：v2 wallets 主链路 → v1 兜底 → expiring 附加。 */
    public void refreshCredits(AccountSnapshot s) throws IOException {
        String tk = token();
        JSONObject j = userapiGet("/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw", tk);
        long code = j.optLong("code", -1);
        if (code == 410000 || code == 400000) throw new IOException("登录态已过期");
        JSONObject data = j.optJSONObject("data");
        double total;
        if (code == 0 && data != null && data.optJSONArray("wallets") != null) {
            total = data.optDouble("total_balance", 0);
        } else {
            JSONObject leg = userapiGet("/agent-assetmgr/api/v1/wallets?biz_app_id=autoclaw", tk);
            long lc = leg.optLong("code", -1);
            if (lc == 410000 || lc == 400000) throw new IOException("登录态已过期");
            JSONObject ld = leg.optJSONObject("data");
            if (lc != 0 || ld == null) {
                throw new IOException("积分查询返回异常 code=" + lc);
            }
            total = ld.optDouble("total_balance", 0);
        }
        s.creditsTotal = total;
        s.credits = trim(total);
        // 即将过期（附加信息，失败不阻塞）
        try {
            JSONObject ex = userapiGet(
                    "/agent-assetmgr/api/v1/points/expiring?biz_app_id=autoclaw", tk);
            JSONObject ed = ex.optJSONObject("data");
            if (ex.optLong("code", -1) == 0 && ed != null) {
                String txt = ed.optString("expiring_points_text", "");
                if (!txt.isEmpty()) s.expiringCredits = txt;
            }
        } catch (Exception ignored) {
        }
    }

    private JSONObject userapiGet(String path, String token) throws IOException {
        Http.Resp r = Http.get(USERAPI_BASE + path, signedHeaders(token), 20000);
        if (!r.ok()) throw new IOException("积分查询返回 HTTP " + r.code);
        return Http.json(r);
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.2f", v);
    }

    /**
     * 每日签到（checkin.rs）：通用任务接口，task_id=daily_signin，按天幂等。
     * 签到前先刷新一次 token：服务端把「签发 token」当作一次登录（claimDailyCreditsOnSignIn），
     * 「一周福利」等活动奖励由登录后紧随的 userapi 请求触发发放；只拿存量 token 签到，
     * 服务端眼里从未登录，活动奖励永远不发。刷新失败不阻断签到（签到是既有价值，
     * 活动奖励是增量），降级用原 token 继续。轮换的 refresh_token 由 refreshFingerprint 落盘。
     */
    @Override
    public String checkin() throws IOException {
        String tk = token();
        try {
            if (refreshFingerprint()) tk = token();
        } catch (Exception ignored) {
            lastRefreshError = "签到前刷新失败（降级用原 token）";
        }
        JSONObject body = new JSONObject();
        try {
            body.put("task_id", "daily_signin");
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        JSONObject j = postUserapi("/autoclaw-proxy/proxy/autoclaw-task-complete",
                body, tk, "签到");
        long code = j.optLong("code", 0);
        if (code != 0) {
            if (code == 410000 || code == 400000) return "签到失败：登录态已过期";
            return "签到失败 code=" + code;
        }
        JSONObject d = j.optJSONObject("data");
        boolean already = d != null && d.optBoolean("already_completed", false);
        boolean reported = d != null && d.optBoolean("success", false);
        long reward = d == null ? 0 : d.optLong("reward_points", 0);
        // 「本次真的领到」= reward_points > 0 或 (success 且非已领)
        boolean claimed = reward > 0 || (reported && !already);
        if (claimed) {
            return reward > 0 ? ("签到成功，获得 " + reward + " 积分") : "签到成功";
        }
        return already ? "今天已签到" : "签到未成功（上游未发放）";
    }
}
