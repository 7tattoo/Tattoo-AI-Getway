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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raccoon（商汤小浣熊）。协议来自 _upstream/SPEC-raccoon.md：
 * base=https://xiaohuanxiong.com，Bearer 直传，扫码登录（本地生成 32hex + 轮询 login_with_qrcode_code）。
 */
public class RaccoonProvider implements ServiceProvider {

    public static final String ID = "raccoon";

    private static final String PREFS = "svc_raccoon";
    private static final String CK = "cred";
    private static final String SK_PEND = "pending_code";
    private static final SecureRandom RND = new SecureRandom();

    private static final String BASE = "https://xiaohuanxiong.com";
    private static final String AUTH = "/api/web/auth/v1";
    private static final String LLM = "/api/web/llm/v2";
    private static final String POINTS = "/api/web/points/v1";
    private static final String DESKTOP = "/api/web/desktop/v1";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final SharedPreferences prefs;
    private final com.aigetway.tattoo7.account.AccountPool pool;
    private final RaccoonCheckin checkinState;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>> refreshFlights
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** vc134：最近一次已被认领（正在换票或已换成功）的授权码 + 认领锁，防双通道重复消费。 */
    private final Object codeLock = new Object();
    private String lastRedeemedCode = "";
    /** vc141：换票(exchangeCode→saveLogin)进行中标志，网页抓取落库路径见此即退避。 */
    private volatile boolean exchangeInFlight = false;

    public RaccoonProvider(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(ID);
        this.checkinState = new RaccoonCheckin(ctx.getFilesDir());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Raccoon / 小浣熊";
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

    /** 登录成功：按上游 uid 覆盖或新增（同一账号不会自我复制）。 */
    private void addCred(JSONObject o, String label) {
        pool.addOrUpdate(o.optString("uid", ""), o.toString(), label);
    }

    /**
     * 刷新凭据：更新当前账号。
     *
     * <p>已改为走 CAS：读的时候记住 accessToken，写回时校验它没被别的线程换掉。
     * 旧实现无条件写 activeId，账号轮转会把 A 的新凭证写到 B 头上。
     */
    private void saveCred(JSONObject o) {
        saveCred(o.optString("uid", ""), null, o);
    }

    /**
     * 把 {@code next} 合并写回账号 {@code uid}。
     *
     * <p>{@code prevAccess} 非空时走 CAS（刷新路径必须传），为空则直接覆盖
     * （登录/补字段路径，此时本地就是唯一写者）。
     */
    private void saveCred(String uid, String prevAccess, JSONObject next) {
        String id = accountIdOfUid(uid);
        if (id == null) {
            pool.addOrUpdate(uid, next.toString(),
                    next.optString("nickname", null));
            return;
        }
        if (prevAccess == null) {
            pool.updateCred(id, next.toString());
        } else if (!pool.updateCredIfCurrent(id, prevAccess, next.toString())) {
            // 期间已被其它线程刷新过：丢弃本次结果，不能覆盖更新的凭证
            refreshLog("CAS 冲突，丢弃本次刷新结果（账号已被其它线程续期）");
        }
    }

    /** uid → 账号 id。找不到回退到当前账号 id。 */
    private String accountIdOfUid(String uid) {
        if (uid != null && !uid.isEmpty()) {
            for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
                if (uid.equals(a.uid)) return a.id;
            }
        }
        return pool.activeId();
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

    /** 当前已存的 accessToken（"增加账号"时用于区分"旧令牌"和"本次新令牌"）。 */
    public String currentToken() {
        return tok();
    }

    private boolean expired() {
        JSONObject c = cred();
        if (c == null) return true;
        long exp = c.optLong("expiresAt", 0L);
        return exp > 0 && System.currentTimeMillis() > exp - 300_000L;
    }

    private Map<String, String> headers() throws IOException {
        if (!loggedIn()) throw new IOException("Raccoon 未登录");
        if (expired()) refreshOrThrow();
        // ⚠️ 只读一次 activeId 并用它取凭据：旧代码 refreshOrThrow() 里可能触发
        // 账号轮转，之后再 cred() 拿到的就是另一个账号的 token，
        // 会出现「用 A 的过期 token 去验 B 的接口」这种错配。
        JSONObject c = pool.credOf(pool.activeId());
        if (c == null) throw new IOException("Raccoon 未登录");
        return headersFor(c);
    }

    /**
     * 用指定凭据对象构造请求头（不读账号池）。
     *
     * <p>vc135：登录落库前要用**新拿到的** accessToken 去查 user_info 拿 uid，
     * 此时账号池里的激活账号还是旧账号，不能走 {@link #headers()}（会拿旧 token 查到旧用户）。
     */
    private Map<String, String> headersFor(JSONObject c) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + c.optString("accessToken"));
        h.put("Accept", "application/json");
        // 兼容两种历史键名；真正的来源是 user_info 的 office_identity
        String office = c.optString("officeIdentity", c.optString("orgCode", ""));
        h.put("X-Org-Code", office);
        h.put("X-Raccoon-Language", "zh");
        h.put("X-Client-Platform", "desktop-windows");
        h.put("User-Agent", UA);
        return h;
    }

    // ---------------- 扫码登录 ----------------

    // ---- 授权码流程（对齐可用参考实现 raccoon2api）----
    // 打开 {base}/code/authorize?login_source=desktop&appname=...，
    // 用户在网页里用微信/验证码/密码任一方式登录后，页面会跳转到
    // office-raccoon://auth/callback?... ，我们从自定义 scheme 里取授权码。
    private static final String CALLBACK_SCHEME = "office-raccoon://auth/callback";

    /**
     * 生成登录页 URL。提供多个变体：站点对 appname / login_source 敏感，
     * 不同变体走的服务端分支可能不同，逐个尝试即可。
     * 0 = 桌面授权页 /authorize（appname=Raccoon-Work）—— **站点权威路径**
     * 1 = 桌面授权页（appname 带空格）
     * 2 = 网页登录页 /login
     * 3 = 扫码页 /login/mp（code 本地生成）
     *
     * <p>2026-07-15 起站点前端把 `/code/authorize` 301 到 `/authorize`（见 page-*.js 的
     * redirects 表：`{source:"/code/authorize", destination:"/authorize"}`）。这里直接走新路径，
     * 省一跳重定向 —— WebView 里重定向会重建 JS 上下文，早期版本正是在这一跳上把注入的
     * hook 与上报桥弄丢，表现为「点了授权没反应」。
     */
    public String beginLogin(int variant) {
        String work = java.net.URLEncoder.encode("Raccoon-Work", java.nio.charset.StandardCharsets.UTF_8);
        String spaced = java.net.URLEncoder.encode("Raccoon Work", java.nio.charset.StandardCharsets.UTF_8);
        switch (variant) {
            case 1:
                return BASE + "/authorize?login_source=desktop&appname=" + spaced;
            case 2:
                return BASE + "/login?appname=" + work;
            case 3: {
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder(32);
                for (byte x : b) sb.append(String.format("%02x", x));
                return BASE + "/login/mp?code=" + sb + "&appname=" + work;
            }
            default:
                return BASE + "/authorize?login_source=desktop&appname=" + work;
        }
    }

    public String beginLogin() {
        return beginLogin(0);
    }

    /** 是否是 Raccoon 的登录回调（自定义 scheme）。 */
    /**
     * 是否「可换票」的回调。
     *
     * <p>2026-10-15 从站点前端 bundle（`app/(identity)/authorize/page-*.js`）读到桌面模式的
     * 权威交付形状：`window.location.assign("office-raccoon://auth/callback?code=<authorization_code>&state=<state>")`
     * （desktop 用 `code` 键，web 才用 `authorization_code`）。旧实现只要 URL 里出现
     * `office-raccoon:` 就返回 true，于是**任何**带该 scheme 的前导跳转（例如站点先
     * `window.open("about:blank")` 再赋值、或带 state 但不带 code 的半成品 URL）都会被当成
     * 回调立刻消费掉 → 表现为「点授权后一闪而过、token 一直抓不到」。
     *
     * <p>现在只认真正带凭据的回调：scheme 对 + 路径是 /auth/callback + 带 code /
     * authorization_code / access_token 之一。带 state 无 code 的一律放行继续等。
     */
    public static boolean isLoginCallback(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.toLowerCase().indexOf("office-raccoon:") < 0) return false;
        String low = u.toLowerCase();
        // 必须是回调路径（允许 //auth/callback 这种 WebView 规范化写法）
        if (low.indexOf("auth/callback") < 0) {
            // 早期版本见过 office-raccoon://callback?... 的变体，宽松放过但必须带凭据（下面的判定管）
            if (low.indexOf("callback") < 0) return false;
        }
        String q = u.indexOf('?') < 0 ? "" : u.substring(u.indexOf('?') + 1);
        if (q.isEmpty()) return false;
        String ql = q.toLowerCase();
        return ql.contains("code=") || ql.contains("access_token=") || ql.contains("accesstoken=")
                || ql.contains("authorization_code=");
    }

    /** 从任意字符串里抠出回调 URL（iframe src / 文本节点 / 编码形式都适用）。 */
    public static String extractCallback(String text) {
        if (text == null) return null;
        String u = text;
        int i = u.toLowerCase().indexOf("office-raccoon:");
        if (i < 0) return null;
        u = u.substring(i);
        // 截到明显的分隔符为止
        int end = u.length();
        for (char c : new char[]{'\'', '"', '>', '<', ' ', '\\', ')', '('}) {
            int p = u.indexOf(c, 1);
            if (p > 0 && p < end) end = p;
        }
        return u.substring(0, end);
    }

    /** 处理回调：直接带 token 就存，否则用 code 换。 */
    public String handleLoginCallback(String url) {
        try {
            String q = url.substring(url.indexOf('?') < 0 ? url.length() : url.indexOf('?') + 1);
            Map<String, String> params = new LinkedHashMap<>();
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                try {
                    params.put(java.net.URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
            String at = params.getOrDefault("access_token", params.getOrDefault("accessToken", ""));
            String rt = params.getOrDefault("refresh_token", params.getOrDefault("refreshToken", ""));
            if (!at.isEmpty()) {
                saveLogin(at, rt, params.get("expires_at"), null);
                return "ok";
            }
            String code = params.getOrDefault("code", params.getOrDefault("authorization_code", ""));
            if (code.isEmpty()) return "error: 回调里没有 code / access_token，参数=" + params.keySet();
            // vc134：scheme 回调 + bodyScan 兜底会对同一个 code 投递两次，第二次
            // 拿已消费的 code 去换票必报 200035，还会把刚打出的成功状态覆盖掉。
            // 原子认领：先占位再发请求，失败则释放（真正的换票失败仍会照常报错）。
            synchronized (codeLock) {
                if (code.equals(lastRedeemedCode)) {
                    android.util.Log.i("RaccoonLogin", "exchangeCode skip dup code=" + code);
                    // vc139：不能返回 "ok" —— UI 侧 done() 一看到 ok 就读 hasRefreshToken()，
                    // 而此刻真线程还在 saveLogin/fillUserInfo 里（rt 尚未落库），
                    // 会误弹「未抓到续期凭据」。返回 ok-dup 让 UI 侧跳过 done，
                    // 成功文案由真线程换票完成后给出。
                    return "ok-dup";
                }
                lastRedeemedCode = code;
            }
            String r;
            exchangeInFlight = true;
            try {
                r = exchangeCode(code);
            } finally {
                exchangeInFlight = false;
            }
            if (!r.startsWith("ok")) {
                synchronized (codeLock) {
                    if (code.equals(lastRedeemedCode)) lastRedeemedCode = "";
                }
            }
            android.util.Log.i("RaccoonLogin", "exchangeCode: " + (r.length() > 200 ? r.substring(0, 200) : r));
            return r;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** POST /login_with_authorization_code 换凭据。 */
    private String exchangeCode(String code) {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            // 2026-10-14：对齐参考实现 xhxnew.apk（只发 authorization_code，不带任何
            // X-Client-Platform/X-Client-Version 身份头）。服务端按这些头给 rt 打客户端标识，
            // 声明 desktop-windows 会让 refresh_token 与后续 refresh 请求的客户端上下文不一致。
            h.put("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("authorization_code", code);
            Http.Resp r = Http.postJson(BASE + AUTH + "/login_with_authorization_code",
                    h, body.toString(), 30000);
            android.util.Log.i("RaccoonLogin", "exchangeCode http=" + (r == null ? "null" : r.code)
                    + " body=" + (r == null || r.body == null ? "null" : r.body));
            // vc137：logcat 缓冲太小总被滚掉，把换票现场写进 App 记录页（logSink）。
            try {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "exchangeCode http=" + (r == null ? "null" : r.code)
                                + " bodyHead=" + (r == null || r.body == null ? "null"
                                : r.body.substring(0, Math.min(r.body.length(), 240))));
            } catch (Exception ignored) {
            }
            JSONObject j = Http.json(r);
            if (j == null) {
                return "error: 响应非JSON http=" + r.code
                        + " body=" + (r.body == null ? "null" : r.body);
            }
            JSONObject d = j.optJSONObject("data");
            if (d == null) d = j;
            String at = d.optString("access_token", d.optString("accessToken", ""));
            if (at.isEmpty()) {
                return "error: 换取失败 http=" + r.code
                        + " 响应=" + (r.body.length() > 300 ? r.body.substring(0, 300) : r.body);
            }
            String rt = d.optString("refresh_token", d.optString("refreshToken", ""));
            // vc137：logcat 缓冲太小总被滚掉，把换票现场写进 App 记录页（logSink）。
            // vc144：rt 脏检查结果也进 logSink —— vc143 落库回读证明 rt 是在这里被丢的，
            // 但旧实现只写 logcat，记录页看不到「为什么入参 432 落库变 0」。
            final boolean rtDirty = !rt.isEmpty() && (rt.equals(at)
                    || (rt.length() > 40 && at.startsWith(rt)));
            try {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "exchangeCode rt 脏检查: rtLen=" + rt.length()
                                + " atLen=" + at.length()
                                + " equals=" + rt.equals(at)
                                + " atStartsWithRt=" + (rt.length() > 40 && at.startsWith(rt))
                                + " rtHead=" + (rt.isEmpty() ? "空"
                                        : rt.substring(0, Math.min(24, rt.length())))
                                + " atHead=" + at.substring(0, Math.min(24, at.length())));
            } catch (Exception ignored) {
            }
            // vc136：真机 17:52 实测「换票 ok 但落库 rt 为空」——站点可能把 refresh_token
            // 只写进 HttpOnly Set-Cookie（网页 JS/CookieManager 都读不到），body 里不再回。
            // 这里从响应头 Set-Cookie 兜底抓：名字含 refresh_token 且值 >=20。
            // vc144：body 里的 rt 若是脏数据（= access 或其前缀截断），同样走 Set-Cookie 兜底 ——
            // 21:35 真机实锤：body 的 rt 与 access 同 JWT 头（一眼脏），旧代码因 body「有」rt
            // 跳过兜底 → 丢弃后 rt 落库为 0 → 3 小时后必重登。
            if (rt.isEmpty() || rtDirty) {
                String crt = refreshFromSetCookies(r.setCookies());
                android.util.Log.i("RaccoonLogin", "exchangeCode body rt " + (rtDirty ? "脏" : "空")
                        + "，Set-Cookie 兜底="
                        + (crt.isEmpty() ? "无（headers=" + r.headers.keySet() + "）" : "命中 len=" + crt.length()));
                // 同步进 App 记录页（logcat 抓不到现场）
                try {
                    java.util.List<String> scs = r.setCookies();
                    com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                            "exchangeCode body" + (rtDirty ? "rt脏" : "无rt")
                                    + " SetCookie兜底=" + (crt.isEmpty() ? "无" : "命中len=" + crt.length())
                                    + " scCount=" + scs.size()
                                    + " headerKeys=" + r.headers.keySet());
                } catch (Exception ignored) {
                }
                // vc144：Set-Cookie 兜底命中的值也要过脏检查（不能把 access 又当 rt 填回来）。
                // vc147：body 脏时不再置空 —— 原样传给 saveLogin 作实验凭据（rtUnverified），
                // 由 /refresh 裁决。22:19 实锤：置空后 saveLogin 只拿到 len=0，实验凭据丢失。
                if (!crt.isEmpty() && !crt.equals(at)
                        && !(crt.length() > 40 && at.startsWith(crt))) {
                    rt = crt;
                }
            }
            // vc145：换票接口不给独立 rt（21:44 实锤：body rt == access，Set-Cookie 里也没有
            // refresh_token）→ 最后一条路是 WebView 里的 HttpOnly cookie raccoon_refresh_token
            //（站点网页版刷新就靠它：site.js se.post(refresh,{refresh_token:cookie})）。
            // CookieManager.getCookie() 能读到 HttpOnly cookie。这里不落库（落在 saveLogin/
            // addOrUpdate 的写库路径里），只记现场，由 handleLoginCallback 的调用方收割。
            try {
                String wvRt = "";
                String ck = android.webkit.CookieManager.getInstance()
                        .getCookie("https://xiaohuanxiong.com");
                if (ck != null) {
                    for (String kv : ck.split(";")) {
                        String p = kv.trim();
                        int eq = p.indexOf('=');
                        if (eq <= 0) continue;
                        String nm = p.substring(0, eq).toLowerCase(java.util.Locale.US);
                        if (nm.contains("refresh_token") || nm.contains("refreshtoken")) {
                            String vv = p.substring(eq + 1).trim();
                            try { vv = java.net.URLDecoder.decode(vv, "UTF-8"); } catch (Exception ignored) { }
                            if (vv.length() > wvRt.length()) wvRt = vv;
                        }
                    }
                }
                final String fWv = wvRt;
                webviewRt = fWv;
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "exchangeCode WebView cookie 收割: "
                                + (fWv.isEmpty() ? "无 refresh_token cookie" : "命中 len=" + fWv.length()
                                        + (fWv.equals(at) ? "（=access，脏）" : "（与 access 不同，可用）")));
            } catch (Exception ignored) {
                webviewRt = "";
            }
            saveLogin(at, rt,
                    d.optString("expires_at", d.optString("expiresAt", "")), null);
            android.util.Log.i("RaccoonLogin", "exchangeCode OK rtLen=" + rt.length()
                    + " bodyKeys=" + d.names());
            try {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "exchangeCode OK rtLen=" + rt.length() + " bodyKeys=" + d.names());
            } catch (Exception ignored) {
            }
            return "ok";
        } catch (Throwable e) {
            android.util.Log.i("RaccoonLogin", "exchangeCode exception", e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String st = sw.toString();
            int nl = st.indexOf('\n', st.indexOf('\n') + 1);
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " @" + (nl > 0 ? st.substring(0, Math.min(nl, 220)) : st.substring(0, Math.min(st.length(), 220)));
        }
    }

    /** 从 Set-Cookie 头列表里抓 refresh_token（名字含 refresh_token 即命中，值 >=20）。 */
    private static String refreshFromSetCookies(java.util.List<String> scs) {
        if (scs == null) return "";
        for (String sc : scs) {
            if (sc == null) continue;
            String low = sc.toLowerCase(java.util.Locale.US);
            if (!low.contains("refresh_token") && !low.contains("refreshtoken")) continue;
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';', eq);
            if (eq <= 0) continue;
            String v = semi > eq ? sc.substring(eq + 1, semi) : sc.substring(eq + 1);
            v = v.trim();
            try { v = java.net.URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }
            if (v.length() >= 20) return v;
        }
        return "";
    }

    /** vc145：换票后从 WebView cookie 收割到的 rt（可能为空；handleLoginCallback 完成后由 LoginActivity 收割落库）。 */
    public volatile String webviewRt = "";

    /** 换票落库后，把 WebView cookie 里的 rt 补写进当前账号（vc145）。返回是否补上了。 */
    public boolean harvestWebviewRt() {
        try {
            String wv = webviewRt == null ? "" : webviewRt.trim();
            if (wv.isEmpty()) return false;
            com.aigetway.tattoo7.account.AccountPool.Account acc = pool.current();
            if (acc == null) return false;
            JSONObject c = pool.credOf(acc.id);
            if (c == null) return false;
            String at = c.optString("accessToken", "");
            if (wv.equals(at) || (wv.length() > 40 && at.startsWith(wv))) {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "harvestWebviewRt: cookie rt 与 access 相同（脏），不采用");
                return false;
            }
            String oldRt = c.optString("refreshToken", "");
            if (!oldRt.isEmpty()) return false;   // 已有 rt 不动
            c.put("refreshToken", wv);
            pool.updateCred(acc.id, c.toString());
            com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                    "harvestWebviewRt: 已补写 rt len=" + wv.length() + " acc=" + acc.id);
            return true;
        } catch (Exception e) {
            try {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "harvestWebviewRt 异常: " + e.getMessage());
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private void saveLogin(String accessToken, String refreshToken, String expiresAt, String nickname) {
        final String rtDirtySource = refreshToken; // vc146：exchangeCode 传入的原始 body rt（脏也保留作实验凭据）
        // vc135：登录落库必须「以新 accessToken 自洽」——
        // 旧实现以 cred()（=账号池当前激活账号）为底本，登录新账号时会：
        //   ① 抓不到 rt 时把**旧账号的 refreshToken** 带进新账号（必 401 的脏 rt）；
        //   ② 把新账号的凭据 addOrUpdate 进旧账号槽位（uid 还是旧的）。
        // 真机表现：登录后「续期凭据其实是 access_token」「账号串号」。
        // 正确做法：新凭据**自建对象**（绝不继承旧账号的 rt），
        // 用新 access 拉 user_info 拿 uid/office 后按 uid upsert，最后把激活账号切到它。
        JSONObject c = new JSONObject();
        try {
            c.put("accessToken", accessToken);
            String rt = refreshToken == null ? "" : refreshToken.trim();
            // 最后一道闸：rt 与 access 都是 JWT，网页抓取侧分不清；相同（或 access 以 rt
            // 为前缀的截断）一律丢弃，宁缺毋滥（脏 rt 会让续期恒 401）。
            if (!rt.isEmpty() && (rt.equals(accessToken)
                    || (rt.length() > 40 && accessToken.startsWith(rt)))) {
                android.util.Log.i("RaccoonLogin", "saveLogin 丢弃与 access 相同的 refresh_token（len=" + rt.length() + "）");
                rt = "";
            }
            c.put("refreshToken", rt);
            // vc146：换票接口 body 的 rt 就是 access 本身（21:44 实锤 equals=true），
            // 站点已确认不发独立 rt、Set-Cookie/WebView cookie 里也没有。最后一搏：
            // 把这个 JWT 当 rt 存下来直接发给 /refresh，让服务端裁决认不认
            // （可能服务端按端点+请求上下文区分同一 JWT 的用途）。
            // rtUnverified=true 标记它是「实验凭据」：refreshAll 的脏 rt 清理跳过它，
            // /refresh 若明确拒绝（200003/verify failed）则清空并给出终局文案。
            if (rt.isEmpty() && rtDirtySource != null) {
                c.put("refreshToken", rtDirtySource);
                c.put("rtUnverified", true);
                try {
                    com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                            "saveLogin(vc146实验): body 的 rt 与 access 相同，仍存入（rtUnverified）"
                                    + " len=" + rtDirtySource.length() + "，待 /refresh 裁决");
                } catch (Exception ignored) {
                }
            }
            long exp = jwtExpMs(accessToken);
            if (exp <= 0) {
                long n = parseNum(expiresAt);
                exp = n > 100000000000L ? n : (n > 0 ? n * 1000L : System.currentTimeMillis() + 86400_000L);
            }
            c.put("expiresAt", exp);
            if (nickname != null && !nickname.isEmpty()) c.put("nickname", nickname);
        } catch (org.json.JSONException ignored) {
        }
        // 用新 token 拉 user_info 补 uid / office_identity / officeOrg / officeRole / nickname。
        String uid = fillUserInfo(c, accessToken);
        // 按真实 uid 落库（同一账号覆盖、新账号新增），并把激活账号切过去。
        com.aigetway.tattoo7.account.AccountPool.Account acc =
                pool.addOrUpdate(uid, c.toString(), c.optString("nickname", null));
        if (acc != null) {
            pool.setActive(acc.id);
            String role = c.optString("officeRole", "");
            if (!role.isEmpty()) pool.setOfficeRole(acc.id, role);
        }
        // vc143 诊断：落库后立刻回读，确认 rt 真的进了库（20:09 登录全绿但 20:31 rt 已空的现场取证）。
        try {
            String back = acc == null || pool.credOf(acc.id) == null ? ""
                    : pool.credOf(acc.id).optString("refreshToken", "");
            String msg = "saveLogin 落库回读 uid=" + uid + " acc=" + (acc == null ? "null" : acc.id)
                    + " rtLen=" + back.length() + " (入参rtLen=" + (refreshToken == null ? 0 : refreshToken.length()) + ")";
            android.util.Log.i("RaccoonLogin", msg);
            com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN", msg);
        } catch (Exception ignored) {
        }
        // 登录成功后自动领客户端登录奖励（按 uid × 当日去重）。
        // 对拍参考实现的 v1.29：每次成功登录都自动 POST desktop/v1/login/points/grant
        // 并把 granted=true 当作「今日成功领取 3000 积分」展示给用户。
        try {
            if (!uid.isEmpty() && !checkinState.isCheckedInToday(uid)) {
                String grant = claimLoginPoints(accessToken, uid);
                if (grant != null) refreshLog("登录奖励: " + grant);
            }
        } catch (Exception e) {
            refreshLog("登录奖励异常: " + e.getMessage());
        }
    }

    /**
     * 用给定的凭据对象 {@code c} 和 {@code accessToken} 调 user_info，
     * 把 uid / nickname / officeIdentity / officeOrg / officeRole 补进 {@code c}。
     *
     * <p>vc135：登录落库前的关键一步 —— 换票/网页登录路径此前不写 uid，
     * addOrUpdate("") 会把新账号并进旧槽位，真机表现为「登录成功但账号未新增」。
     *
     * @return 上游 user_id（拿不到返回 ""）
     */
    private String fillUserInfo(JSONObject c, String accessToken) {
        try {
            Http.Resp r = Http.get(BASE + AUTH + "/user_info", headersFor(c), 15000);
            if (r.code == 401 || r.code == 403) return "";
            JSONObject d = Http.json(r).optJSONObject("data");
            if (d == null) return "";
            String n = d.optString("nickname", d.optString("name", ""));
            if (!n.isEmpty()) c.put("nickname", n);
            String uid = d.optString("user_id", d.optString("id", ""));
            if (!uid.isEmpty()) c.put("uid", uid);
            String office = d.optString("office_identity", d.optString("officeIdentity", ""));
            if (!office.isEmpty()) c.put("officeIdentity", office);
            String org = d.optString("office_org_name", "");
            if (!org.isEmpty()) c.put("officeOrg", org);
            String role = d.optString("office_role", d.optString("officeRole", ""));
            if (!role.isEmpty()) c.put("officeRole", role);
            return uid;
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseNum(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private JSONObject unwrapData(JSONObject j, String failMsg) {
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data");
        if (d != null) return d;
        // 顶层本身就是 data
        if (j.has("granted") || j.has("granted_points") || j.has("items") || j.has("point_grant_popups")) {
            return j;
        }
        return null;
    }

    /**
     * 拉 user_info 补齐 nickname 与 office_identity。
     *
     * <p>⚠️ office_identity 必须存：它是 X-Org-Code 头的值，
     * 而 model_catalog / chat / balance 都要求这个头，缺失时上游会拒。
     */
    private void fetchUserName() {
        try {
            JSONObject c = cred();
            if (c == null) return;
            // 已有昵称且已有 office_identity 就别白跑一趟
            if (!c.optString("nickname", "").isEmpty()
                    && !c.optString("officeIdentity", "").isEmpty()) return;
            Http.Resp r = Http.get(BASE + AUTH + "/user_info", headers(), 15000);
            if (r.code == 401 || r.code == 403) return;
            JSONObject d = Http.json(r).optJSONObject("data");
            if (d == null) return;
            String n = d.optString("nickname", d.optString("name", ""));
            if (!n.isEmpty()) c.put("nickname", n);
            // vc134：uid 必须在这里补 —— 换票路径不写 uid，落库 addOrUpdate("") 会丢账号。
            String uid = d.optString("user_id", d.optString("id", ""));
            if (!uid.isEmpty()) c.put("uid", uid);
            String office = d.optString("office_identity", d.optString("officeIdentity", ""));
            if (!office.isEmpty()) c.put("officeIdentity", office);
            String org = d.optString("office_org_name", "");
            if (!org.isEmpty()) c.put("officeOrg", org);
            // 对拍参考实现：账号级 officeRole 字段（"成员" / "管理员" / ""）
            String role = d.optString("office_role", d.optString("officeRole", ""));
            String roleBefore = "";
            for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
                if (a.id.equals(pool.activeId())) { roleBefore = a.officeRole; break; }
            }
            saveCred(c);
            if (!role.isEmpty() && !role.equals(roleBefore)) {
                pool.setOfficeRole(pool.activeId(), role);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 从网页里抓到的 token 直接落盘（短信/密码/微信/扫码等任意方式登录都能覆盖）。
     * 只接受形如 a.b.c 的 JWT。
     *
     * ⚠️ refreshToken 必须一起抓：站点把 access/refresh 分别存在 cookie
     * {@code raccoon_token} / {@code raccoon_refresh_token}（js-cookie，domain=.xiaohuanxiong.com）。
     * 只存 access 的话，3 小时后必然要重新登录（refreshFingerprint 会因 rt 为空直接失败）。
     */
    public boolean saveTokenFromWeb(String token) {
        return saveTokenFromWeb(token, null);
    }

    public boolean saveTokenFromWeb(String token, String refreshToken) {
        if (token == null || token.length() < 32) return false;
        // vc141：换票线程正在 exchangeCode→saveLogin 落库途中时，网页抓取一律拒绝。
        // （onStorage 线程此刻读 cred() 拿到的还是旧账号，sameIdentity 判不出同一账号，
        //  等 1s 后它的 user_info 回来 addCred 就会把刚落库的 rt=432 洗成空 —— 18:50 现场。）
        if (exchangeInFlight) {
            try {
                com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                        "saveTokenFromWeb 拒绝：换票落库进行中，跳过网页抓取写入");
            } catch (Exception ignored) {
            }
            return false;
        }
        String t = token.trim();
        if (t.split("\\.").length < 3) return false;
        long exp = jwtExpMs(t);
        // 已过期的 JWT 一定是登录页里的历史/占位 token
        if (exp > 0 && exp < System.currentTimeMillis()) return false;
        // 必须调 user_info 验证确实是登录 token —— 登录页本身也会存各种
        // 验证码/风控/埋点用的 JWT，直接存会把未登录状态当成已登录。
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Authorization", "Bearer " + t);
            h.put("Accept", "application/json");
            h.put("X-Raccoon-Language", "zh");
            h.put("X-Client-Platform", "desktop-windows");
            h.put("User-Agent", UA);
            Http.Resp r = Http.get(BASE + AUTH + "/user_info", h, 15000);
            if (r.code == 401 || r.code == 403) return false;
            JSONObject j = Http.json(r);
            JSONObject d = j.optJSONObject("data");
            if (d == null) return false;
            String uid = d.optString("user_id", d.optString("id", ""));
            String nickname = d.optString("nickname", d.optString("name", ""));
            if (uid.isEmpty() && nickname.isEmpty()) return false;
            // ★ 只有「同一个 uid」才允许沿用已存的字段。
            // 旧实现无条件以 cred()（当前激活账号）为底本，于是新登录在抓不到
            // refresh_token 时，会把**上一个账号**的 rt 写进新账号：
            // 那是格式合法的 JWT（iss/sid/exp 都在，能过 parseJwt），但签名属于
            // 别人的会话 → 服务端验签失败，正是「新登录账号」刷出一句
            // authorization verify failed 的来历。宁可留空（只提示不能免登录续期），
            // 也不能存一个必定 401 的别人的 token。
            JSONObject prev = cred();
            String oldUid = prev == null ? "" : prev.optString("uid", "");
            // vc140：token 相等 = 同一账号（uid 可能因 user_info 抖动拿不到，
            // 但 access_token 相同必是同一会话）——否则网页抓取路径会用空 rt
            // 把换票刚写好的账号凭据覆盖掉（18:34 真机 rtLen=432 落库后被洗成空）。
            boolean sameIdentity = !uid.isEmpty() && uid.equals(oldUid)
                    || (prev != null && t.equals(prev.optString("accessToken", "")));
            JSONObject c = sameIdentity ? prev : new JSONObject();
            c.put("accessToken", t);
            c.put("expiresAt", exp);
            String newRt = refreshToken == null ? "" : refreshToken.trim();
            // vc134：rt 与 access 都是 JWT，网页抓取侧无法可靠区分，曾把 access 当 rt 存下，
            // 续期时服务端必报 authorization verify failed。这里做最后一道闸：rt 与 access
            // 相同（或 rt 本身是 access 的截断）一律视为没抓到，宁缺毋滥。
            if (!newRt.isEmpty() && (newRt.equals(t)
                    || (newRt.length() > 40 && t.startsWith(newRt)))) {
                android.util.Log.i("RaccoonLogin", "RACCOON-REFRESH 丢弃与 access 相同的 refresh_token（长度 "
                        + newRt.length() + "）");
                newRt = "";
            }
            if (newRt.isEmpty() && sameIdentity && prev != null) {
                newRt = prev.optString("refreshToken", "");
            }
            // vc140：已有有效 rt 的账号，绝不允许被「空 rt 的网页抓取」降级覆盖。
            //（异账号新增不受限 —— 那是 vc135 语义；这里只拦同槽位洗掉 rt。）
            String prevRt = prev == null ? "" : prev.optString("refreshToken", "");
            if (newRt.isEmpty() && !prevRt.isEmpty() && prev == c) {
                android.util.Log.i("RaccoonLogin",
                        "saveTokenFromWeb 拒绝降级：账号已有 rt(len=" + prevRt.length()
                                + ")，网页抓取未获 rt，保留原值");
                newRt = prevRt;
            }
            // vc141：落库前按 uid 复查账号池 —— 网页抓取的 prev 是「进入本方法时」的激活账号，
            // 期间换票线程可能已把同一 uid 的新凭据（带 rt）写进池子。此处若发现池内同 uid
            // 已有非空 rt 而本次没抓到 rt，一律保留池内原值，绝不用空 rt 覆盖。
            if (newRt.isEmpty() && !uid.isEmpty()) {
                for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
                    if (!uid.equals(a.uid)) continue;
                    String poolRt = "";
                    try {
                        JSONObject pc = pool.credOf(a.id);
                        if (pc != null) poolRt = pc.optString("refreshToken", "");
                    } catch (Exception ignored) {
                    }
                    if (!poolRt.isEmpty()) {
                        newRt = poolRt;
                        android.util.Log.i("RaccoonLogin",
                                "saveTokenFromWeb 拒绝降级(按uid复查)：uid=" + uid
                                        + " 池内已有 rt(len=" + poolRt.length() + ")，保留原值");
                        try {
                            com.aigetway.tattoo7.core.App.get().logSink().log("LOGIN",
                                    "saveTokenFromWeb 拒绝降级(按uid复查) uid=" + uid
                                            + " 保留池内 rt(len=" + poolRt.length() + ")");
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
            }
            c.put("refreshToken", newRt);
            if (!nickname.isEmpty()) c.put("nickname", nickname);
            if (!uid.isEmpty()) c.put("uid", uid);
            // office_identity（X-Org-Code 的值）必须抓，否则后续 model_catalog 会被拒
            String office = d.optString("office_identity", d.optString("officeIdentity", ""));
            if (!office.isEmpty()) c.put("officeIdentity", office);
            addCred(c, nickname.isEmpty() ? null : nickname);
            // 对拍参考实现：登录同时记 officeRole（"成员" / "管理员"）便于 UI 展示
            String role = d.optString("office_role", d.optString("officeRole", ""));
            if (!role.isEmpty()) {
                String accId = pool.activeId();
                if (accId != null) pool.setOfficeRole(accId, role);
            }
            fetchUserName();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void logout() {
        pool.clear();
        prefs.edit().clear().apply();
    }

    /** 当前账号是否具备自动续期能力（有 refresh_token）。 */
    public boolean hasRefreshToken() {
        JSONObject c = cred();
        return c != null && !c.optString("refreshToken", "").isEmpty();
    }

    /** 最近一次刷新失败的原因（供 UI/日志展示，避免「静默失败」）。 */
    private volatile String lastRefreshError = "";

    public String lastRefreshError() {
        return lastRefreshError;
    }

    /**
     * 过期时必须换到新 token 才能发请求；失败要把**真实原因**抛出去，
     * 否则只会看到上游一句含糊的 "Invalid API key"。
     */
    private void refreshOrThrow() throws IOException {
        if (refreshFingerprint()) return;
        throw new IOException("小浣熊登录已过期且自动续期失败：" + lastRefreshError
                + "。请到「账号」页删除该账号后重新登录一次。");
    }

    @Override
    public boolean refreshFingerprint() throws IOException {
        // ⚠️ 账号绑定：refreshFingerprint 一定会跨越一次网络请求，这期间网关可能
        // 已经 rotate() 换了 activeId。旧代码读 active、也写 active（写的时候 active
        // 已经不是刚才那个了），于是新凭证写进了另一个账号。
        // 症状：两个账号轮着用，总有一个「自动过期」且 refresh_token 已作废，刷不回来。
        String accId = pool.activeId();
        JSONObject c = pool.credOf(accId);
        if (c == null) {
            lastRefreshError = "没有已登录账号";
            return false;
        }
        String uid = c.optString("uid", "");
        return refreshFingerprintForUid(uid, false);
    }

    /**
     * 账号页「刷新凭证」按钮入口（ServiceProvider 契约）。必须显式实现：
     * 本类另有 {@code refreshFingerprintForUid(String, boolean)}，若只靠接口默认实现，
     * 会退化成只刷「当前激活账号」——用户点第二个账号却刷了第一个。
     * 手动刷新一律 force=true，绕开 30 秒锁与在途去重，给一次真实的服务端往返。
     */
    @Override
    public boolean refreshFingerprintForUid(String uidOrId) {
        String uid = resolveUid(uidOrId);
        if (uid.isEmpty()) {
            lastRefreshError = "账号池里找不到该账号（没有 uid，无法续期）";
            return false;
        }
        return refreshFingerprintForUid(uid, true);
    }

    /** 入参可能是池内 accountId，也可能是上游 uid，统一换成上游 uid。 */
    private String resolveUid(String uidOrId) {
        if (uidOrId == null || uidOrId.isEmpty()) {
            String accId = pool.activeId();
            JSONObject c = accId == null ? null : pool.credOf(accId);
            return c == null ? "" : c.optString("uid", "");
        }
        for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
            if (uidOrId.equals(a.uid)) return a.uid;
        }
        for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
            if (uidOrId.equals(a.id)) {
                String u = a.uid;
                if (u == null || u.isEmpty()) {
                    try {
                        JSONObject c = pool.credOf(a.id);
                        if (c != null) u = c.optString("uid", "");
                    } catch (Exception ignored) {
                    }
                }
                return u == null ? "" : u;
            }
        }
        return "";
    }

    /**
     * 刷新指定 uid 的凭据。
     *
     * <p>对拍参考实现 AutoRefresh：后台每 30 分钟把**所有** enabled 且有 refresh_token
     * 的账号都续一遍。我方旧实现只在 {@code active} 账号 expired() 时才刷新 →
     * 非 active 账号的 accessToken 永远不续 → 切过去时 access 已过期、refresh_token
     * 也可能因长期未用被服务端轮换失效 → 表现就是"两个账号总有一个自动过期且刷不出来"。
     *
     * <p>并发去重：同一 uid 短时间内并发调用只跑一次，剩下的复用同一次的结果。
     * 强制刷新（401 路径）必须传 {@code force=true}，避免被之前失败/卡住的 future 拖累。
     */
    public boolean refreshFingerprintForUid(String uid, boolean force) {
        if (uid == null || uid.isEmpty()) {
            lastRefreshError = "uid 为空";
            return false;
        }
        if (!force) {
            java.util.concurrent.CompletableFuture<Boolean> existing = refreshFlights.get(uid);
            if (existing != null && !existing.isDone()) {
                try {
                    return existing.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            }
            java.util.concurrent.CompletableFuture<Boolean> mine =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<Boolean> prev = refreshFlights.putIfAbsent(uid, mine);
            if (prev != null) {
                try {
                    return prev.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    return false;
                }
            }
            try {
                boolean ok = doRefreshAccount(uid);
                mine.complete(ok);
                return ok;
            } finally {
                refreshFlights.remove(uid, mine);
            }
        }
        return doRefreshAccount(uid);
    }

    /**
     * 把账号池里所有能续期的账号都刷一遍。失败只记日志，不动账号状态、不旋转。
     * 对拍参考实现 AutoRefresh.refreshAll。
     */
    public void refreshAll() {
        java.util.List<com.aigetway.tattoo7.account.AccountPool.Account> all = pool.all();
        int ok = 0, fail = 0;
        for (com.aigetway.tattoo7.account.AccountPool.Account a : all) {
            if (!a.enabled) {
                refreshLog("refreshAll 跳过 acc=" + a.id + " label=" + a.label
                        + "（enabled=false）");
                continue;
            }
            String uid = a.uid;
            if (uid == null || uid.isEmpty()) {
                // 兼容旧数据：uid 还没存上（早期登录路径里没拉 user_info 就关 App 了）
                try {
                    JSONObject cc = new JSONObject(a.cred);
                    uid = cc.optString("uid", "");
                } catch (Exception ignored) {
                }
            }
            if (uid == null || uid.isEmpty()) {
                refreshLog("refreshAll 跳过 acc=" + a.id + " label=" + a.label
                        + "（uid 为空；cred.uid 也为空）");
                continue;
            }
            if (!a.canRefresh()) {
                String rtNow = "";
                try {
                    rtNow = pool.credOf(a.id) == null ? ""
                            : pool.credOf(a.id).optString("refreshToken", "");
                } catch (Exception ignored) {
                }
                refreshLog("refreshAll 跳过 uid=" + uid + " acc=" + a.id
                        + "（refreshToken 为空，无法续期；cred.rtLen=" + rtNow.length()
                        + " accessTokenLen=" + (pool.credOf(a.id) == null ? -1
                                : pool.credOf(a.id).optString("accessToken", "").length()) + "）");
                continue;
            }
            // vc136：结构性脏 rt（值 = access_token）的账号永远刷不成功，
            // 直接跳过并清理，否则 refreshAll 每 30 分钟都会拿它报一次错、把日志刷屏。
            try {
                JSONObject cc = pool.credOf(a.id);
                if (cc != null) {
                    String rt0 = cc.optString("refreshToken", "");
                    if (isDirtyRt(rt0, cc.optString("accessToken", ""))) {
                        // vc146：脏 rt 不再清空 —— 换票接口确认不发独立 rt，这个 JWT 是唯一的
                        // 续期候选，留给 doRefreshAccount 送 /refresh 裁决；明确拒绝才清。
                        refreshLog("cleanup 跳过 uid=" + uid + "：rt 与 access 相同（vc146 实验，"
                                + "不清空，待 /refresh 裁决）");
                        continue;
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                boolean r = refreshFingerprintForUid(uid, false);
                if (r) ok++; else fail++;
            } catch (Throwable t) {
                fail++;
                refreshLog("refreshAll uid=" + uid + " 异常: " + t.getMessage());
            }
        }
        refreshLog("refreshAll 完成: ok=" + ok + " fail=" + fail + "/" + all.size());
    }

    /**
     * 强制刷新（401 路径）：不走 in-flight 去重，不等 30s 锁，直接拿服务端新凭据。
     * 失败时把 lastRefreshError 写明，让调用方决定是否 rotate。
     */
    public boolean forceRefreshActive() throws IOException {
        String accId = pool.activeId();
        JSONObject c = pool.credOf(accId);
        if (c == null) {
            lastRefreshError = "没有已登录账号";
            return false;
        }
        return doRefreshAccount(c.optString("uid", ""));
    }

    private boolean doRefreshAccount(String uid) {
        if (uid == null || uid.isEmpty()) {
            lastRefreshError = "uid 为空";
            return false;
        }
        // 1) 找到这个 uid 对应的账号快照（按 accId 绑定写回）
        com.aigetway.tattoo7.account.AccountPool.Account acc = null;
        for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
            if (uid.equals(a.uid)) { acc = a; break; }
        }
        if (acc == null) {
            lastRefreshError = "账号池里找不到 uid=" + uid;
            return false;
        }
        String accId = acc.id;
        JSONObject c = pool.credOf(accId);
        if (c == null) {
            lastRefreshError = "cred 为空 (uid=" + uid + ")";
            return false;
        }
        final String prevAccess = c.optString("accessToken", "");
        String rt = c.optString("refreshToken", "");
        if (rt.isEmpty()) {
            lastRefreshError = "该账号没有保存 refresh_token（登录时没抓到续期凭据），需重新登录一次";
            return false;
        }
        if (isDirtyRt(rt, prevAccess)) {
            // vc146：换票接口确认不发独立 rt（body rt==access、Set-Cookie 无、WebView cookie 无）。
            // 这个 JWT 可能是服务端故意复用的（按端点区分用途）——不再清理，直接发给 /refresh
            // 让服务端裁决。只有 /refresh 明确拒绝时才清空。
            refreshLog("experiment: rt 与 access 相同（len=" + rt.length()
                    + "），仍尝试 /refresh 裁决（vc146）");
        }
        refreshLog("开始 refresh uid=" + uid + " acc=" + accId + " rt=…"
                + (rt.length() > 6 ? rt.substring(rt.length() - 6) : rt));
        // ⚠️ 2026-10-14 石锤修复（对拍官网前端 + 参考实现 raccoon2api）：
        // 官网网页端刷新（site.js: se=axios.create({baseURL:"/api",withCredentials:true})，
        // J0=()=>se.post(`${pe}/refresh`,{refresh_token:cookie})）只带三类头：
        //   Authorization: Bearer <当前 access_token>（withCredentials 拦截器自动加）、
        //   Accept-Language、Content-Type；浏览器再自动带 Cookie。
        //   **没有** X-Client-Platform / X-Client-Version / X-Raccoon-Language。
        // 参考实现 raccoon2api（桌面授权码流程拿 token）也只发 Content-Type。
        // 我们的 rt 是【网页端签发】的，之前却声明 X-Client-Platform: desktop-windows，
        // 服务端验签时客户端身份与 rt 签发渠道对不上 → 401 authorization verify failed。
        Map<String, String> h = new LinkedHashMap<>();
        // 2026-10-14 二次石锤（对拍参考实现 xhxnew.apk，RaccoonProvider.refresh()）：
        // 参考实现刷新**只带 Content-Type: application/json**，没有 Authorization / Accept /
        // Accept-Language / User-Agent。之前我们带了 Authorization: Bearer <过期 access>，
        // 服务端优先校验这个 Bearer 头 → access 已过期/无效 → 直接回
        // "authorization verify failed"，**根本走不到 refresh_token 校验**。
        // 参考实现能稳定刷新，说明 /refresh 接口不认（也不要求）Authorization 头。
        // 结论：refresh 请求**绝不携带 Authorization**，其余头也一律不设。
        h.put("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", rt);
        } catch (org.json.JSONException e) {
            lastRefreshError = "构造请求失败: " + e.getMessage();
            return false;
        }
        try {
            Http.Resp r = Http.postJson(BASE + AUTH + "/refresh", h, body.toString(), 15000);
            if (r.code == 401 || r.code == 403) {
                // 2026-10-14：服务端将 refresh_token 迁移为 JWT（payload 需 iss/sid/exp，HS256 签名校验）。
                // 旧 opaque rt 会触发 401；body 里带 code=200003/100002 + details 佐证，尽量把诊断信息给到用户。
                String dt = "";
                try {
                    JSONObject jj = Http.json(r);
                    dt = jj.optString("details", jj.optString("message", ""));
                }
                catch (Exception ignored) {
                    dt = r.body;
                }
                lastRefreshError = "服务端拒绝（HTTP " + r.code + "），refresh_token 已失效或格式过期："
                        + trunc(dt) + "，需重新登录";
                refreshLog("failed: " + lastRefreshError);
                return false;
            }
            JSONObject j = Http.json(r);
            int code = j.optInt("code", -1);
            String rjDetails = j.optString("details", "");
            // 200003 = refresh_token 无效/过期（SPEC 第4 节确证）；100002 + format/verify 校验 = 新 JWT 迁移
            if (code == 200003 || code == 200035 || code == 100002
                    || rjDetails.contains("verify failed") || rjDetails.contains("invalid")) {
                lastRefreshError = "服务端返回 code=" + code + " " + trunc(rjDetails)
                        + "（refresh_token 已失效或格式过期，需重新登录）";
                refreshLog("failed: " + lastRefreshError);
                return false;
            }
            if (code != 0 && code != 200 && !j.has("data")) {
                lastRefreshError = "服务端返回 code=" + code + " " + j.optString("message", "");
                return false;
            }
            JSONObject d = j.optJSONObject("data");
            if (d == null) d = j;   // 顶层可能直接就是凭据
            // ⚠️ 两种键名写法都要认（登录/callback 路径就是两种都认的）：
            // 只认下划线时，服务端轮换下发的驼峰 refreshToken 会被丢掉，
            // 下一轮继续用已作废的旧 RT → 服务端 401
            // → 表现就是「主账号早刷过一次就再也刷不了，备用新号却能刷」。
            // access_token 同理：拿不到新 access 会沿用旧的，
            // expiresAt 按旧 JWT 算出来还是过去时间 → 每次调用都触发刷新 → 401 死循环。
            String nrt = d.optString("refresh_token", "");
            if (nrt.isEmpty()) nrt = d.optString("refreshToken", "");
            String nexp = d.optString("expires_at", d.optString("expiresAt", ""));
            String at = d.optString("access_token", "");
            if (at.isEmpty()) at = d.optString("accessToken", "");
            if (at.isEmpty()) {
                at = c.optString("accessToken", "");
                if (at.isEmpty() || jwtExpMs(at) <= System.currentTimeMillis()) {
                    lastRefreshError = "刷新响应里没有新的 access_token，且旧的也已过期："
                            + trunc(r.body);
                    refreshLog("failed: " + lastRefreshError);
                    return false;
                }
            }
            c.put("accessToken", at);
            // vc136：轮换回来的新 rt 也可能被上游/解析污染成 access 本身，同样过滤，
            // 否则脏 rt 会被写进池子，下一轮 refreshAll 又变成刷屏源。
            // vc146：但若本轮我们就是拿「脏 rt 实验」刷成功的（服务端认了同一 JWT），
            // 返回的 rt 也会是脏的 —— 此时必须原样保留，否则实验凭据被自己的成功路径洗掉。
            if (!nrt.isEmpty() && (!isDirtyRt(nrt, at) || c.optBoolean("rtUnverified", false))) {
                c.put("refreshToken", nrt);
            }
            long exp = jwtExpMs(at);
            if (exp <= 0) {
                long n = parseNum(nexp);
                exp = n > 100000000000L ? n : (n > 0 ? n * 1000L
                        : System.currentTimeMillis() + 3600_000L);
            }
            c.put("expiresAt", exp);
            saveCred(uid, prevAccess, c);
            lastRefreshError = "";
            refreshLog("ok uid=" + uid + " rt_rotated=" + (!nrt.isEmpty() ? "yes" : "no"));
            return true;
        } catch (Exception e) {
            lastRefreshError = "网络/解析异常: " + e.getMessage();
            refreshLog("failed: " + lastRefreshError);
            return false;
        }
    }

    /**
     * rt 是否「结构性脏」（把 access_token 误存成了 refresh_token）。
     *
     * <p>两种形态都算：
     * <ul>
     *   <li>完全相同 —— 最典型；</li>
     *   <li>一个是另一个的前缀且长度 &gt; 40 —— 网页抓取侧被截断的变体。</li>
     * </ul>
     * 判据只用于**清理**（清空 rt 让账号退出续期循环），不用于放行。
     */
    private static boolean isDirtyRt(String rt, String access) {
        if (rt == null || rt.isEmpty()) return false;
        if (access == null || access.isEmpty()) return false;
        if (rt.equals(access)) return true;
        return rt.length() > 40 && (access.startsWith(rt) || rt.startsWith(access));
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** 刷新过程写网关日志（只记 RT 尾 6 位，不落全量凭据）。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
    private void refreshLog(String msg) {
        try {
            com.aigetway.tattoo7.core.App.get().logSink()
                    .log("RACCOON-REFRESH", displayName() + " " + msg);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject jwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return new JSONObject();
            return new JSONObject(new String(
                    java.util.Base64.getUrlDecoder().decode(parts[1]), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static long jwtExpMs(String jwt) {
        return jwtPayload(jwt).optLong("exp", 0L) * 1000L;
    }

    // ---------------- 模型 ----------------

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        // X-Org-Code 为空时先补 user_info 拿 office_identity 再重试一次
        // （老账号凭据里没有这个字段，model_catalog 会被拒）
        JSONObject c0 = cred();
        if (c0 != null && c0.optString("officeIdentity", c0.optString("orgCode", "")).isEmpty()) {
            fetchUserName();
        }
        // ⚠️ 用 model_catalog 专用头：上游（raccoon-auth.ts:481）只发这 4 个，
        // 多发 X-Client-Platform / User-Agent 属于画蛇添足。
        Http.Resp r = Http.get(BASE + LLM + "/model_catalog", catalogHeaders(), 20000);
        if (r.code < 200 || r.code >= 300) {
            throw new IOException("model_catalog HTTP " + r.code + " code="
                    + safeCode(r.body) + " " + trunc(r.body));
        }
        JSONObject j;
        try {
            j = Http.json(r);
        } catch (IOException e) {
            throw new IOException("model_catalog 响应不是 JSON: " + trunc(r.body));
        }
        JSONObject dataObj = j.optJSONObject("data");
        if (dataObj == null) {
            throw new IOException("响应里没有 data 字段，code="
                    + j.optInt("code", -1) + " " + trunc(r.body));
        }
        JSONArray cats = dataObj.optJSONArray("categories");
        if (cats == null) {
            throw new IOException("data 里没有 categories，data 的键="
                    + dataObj.names() + " 原始=" + trunc(r.body));
        }
        List<ModelEntry> out = new ArrayList<>();
        {
            for (int i = 0; i < cats.length(); i++) {
                JSONObject cat = cats.optJSONObject(i);
                if (cat == null || !"chat".equals(cat.optString("type", ""))) continue;
                JSONArray ms = cat.optJSONArray("models");
                if (ms == null) continue;
                for (int k = 0; k < ms.length(); k++) {
                    JSONObject m = ms.optJSONObject(k);
                    if (m == null) continue;
                    if (!m.optBoolean("visible", true)) continue;
                    // ⚠️ 模型 id 取的是 **name** 字段，不是 id
                    // （上游 raccoon-auth.ts 的 normalizeRemoteModel：
                    //   `const id = entry.name.trim()`）。
                    // 之前读 `id` 恒为空 → 整张目录被过滤光 → 「模型列表拉取失败」。
                    String id = m.optString("name", "").trim();
                    if (id.isEmpty()) id = m.optString("id", "").trim();
                    if (id.isEmpty() || id.startsWith("raccoon-")) continue;
                    ModelEntry e = new ModelEntry(ID, id, m.optString("description", id));
                    // 计费倍率（billing_* 数值字段，解析在 ModelRate.raccoonRate）
                    e.rateText = ModelRate.raccoonRate(m);
                    JSONObject p = m.optJSONObject("params");
                    e.contextWindow = p == null ? m.optInt("context_window", 256000)
                            : p.optInt("context_window", m.optInt("context_window", 256000));
                    e.maxOutput = p == null ? m.optInt("max_tokens", 8192)
                            : p.optInt("max_tokens", m.optInt("max_tokens", 8192));
                    // 远端不下发模态字段，用白名单
                    e.vision = "sn-deepseek-v4-1-flash".equals(id) || "sn-glm-5-3-flash".equals(id);
                    e.live = true;
                    out.add(e);
                }
            }
        }
        if (out.isEmpty()) {
            // 分类拿到了但一条都没留下：说明过滤条件太严，把 ids 打出来便于定位
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < cats.length(); i++) {
                JSONObject cat = cats.optJSONObject(i);
                if (cat == null) continue;
                JSONArray ms = cat.optJSONArray("models");
                if (ms == null) continue;
                for (int k = 0; k < ms.length(); k++) {
                    JSONObject m = ms.optJSONObject(k);
                    if (m == null) continue;
                    ids.append(m.optString("name", m.optString("id", "?")))
                            .append("(vis=").append(m.optBoolean("visible", true))
                            .append(",type=").append(cat.optString("type", "?")).append(") ");
                }
            }
            throw new IOException("分类里有 " + (ids.length() == 0 ? 0 : ids.toString().split(" ").length)
                    + " 条但全部被过滤：" + trunc(ids.toString()));
        }
        return out;
    }

    /** model_catalog 专用头：严格对齐上游 raccoon-auth.ts:481 的 4 个。 */
    private Map<String, String> catalogHeaders() throws IOException {
        if (!loggedIn()) throw new IOException("Raccoon 未登录");
        if (expired()) refreshOrThrow();
        JSONObject c = cred();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + c.optString("accessToken"));
        h.put("X-Org-Code", c.optString("officeIdentity", c.optString("orgCode", "")));
        h.put("X-Raccoon-Language", "zh");
        return h;
    }

    /** 从响应体里尽力抠出业务 code（用于错误信息）。 */
    private static int safeCode(String body) {
        try {
            return new JSONObject(body).optInt("code", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        l.add(ModelEntry.fallback(ID, "sn-sensenova-6-8-flash", 256000, 63999, false));
        l.add(ModelEntry.fallback(ID, "sn-sensenova-6-8-flash-lite", 256000, 63999, false));
        l.add(ModelEntry.fallback(ID, "sn-glm-5-3", 1000000, 100000, false));
        l.add(ModelEntry.fallback(ID, "sn-kimi-k3", 1000000, 100000, false));
        l.add(ModelEntry.fallback(ID, "sn-glm-5-3-flash", 1000000, 100000, true));
        l.add(ModelEntry.fallback(ID, "sn-deepseek-v4-1-flash", 1000000, 100000, true));
        return l;
    }

    // ---------------- chat ----------------

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        try {
            return prepareChatOnce(body);
        } catch (UpstreamHttpException e) {
            // 上游 401/403 一律当作「token 失效」：先强制续期再重试一次。
            // 客户端只会看到 "Invalid API key"，没有这层重试就只能重新登录。
            if (e.code == 401 || e.code == 403) {
                lastRefreshError = "";
                if (refreshFingerprint()) {
                    return prepareChatOnce(body);
                }
                throw new IOException("小浣熊登录已失效且自动续期失败：" + lastRefreshError
                        + "。请到「账号」页删除该账号后重新登录一次。");
            }
            throw e;
        }
    }

    private PreparedChat prepareChatOnce(JSONObject body) throws IOException {
        Map<String, String> h = headers();
        h.put("Accept", "text/event-stream");
        h.put("Content-Type", "application/json");
        JSONObject b;
        try {
            b = new JSONObject(body.toString());
            b.put("stream", true);
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }

        HttpURLConnection c = (HttpURLConnection) new java.net.URL(BASE + LLM + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b.toString().getBytes("UTF-8"));
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
        PreparedChat pc = new PreparedChat(c.getInputStream(), null);
        pc.requestInputEstimate = com.aigetway.tattoo7.gateway.ContextGovernor
                .estimateMessagesTokens(b.optJSONArray("messages"));
        return pc;
    }

    public void openChat(JSONObject body, OutputStream out) throws IOException {
        prepareChat(body).streamTo(out);
    }

    // ---------------- 账户 / 积分 ----------------

    @Override
    public boolean supportsCheckin() {
        return true;
    }

    /**
     * 一键签到（smali 全量对拍参考实现 RaccoonProvider.checkin）：
     * <ol>
     *   <li>POST desktop /api/web/desktop/v1/login/points/grant —— 头只有 4 项
     *       （Accept/Authorization/X-Client-Platform: <b>desktop-windows</b>/X-Client-Version: <b>v1.0.0</b>），
     *       无 body。旧版拿 app-android/v1.0.4 头打桌面端点：服务端按平台键控 → granted=false，
     *       且旧版在此提前 return，两个账号全显示"已领取或无可领积分"。</li>
     *   <li>POST mobile /api/web/mobile/v1/login/points/grant —— <b>每次都调</b>
     *       （参考实现不是失败才回退），7 项头（app-android/v1.0.4 + X-Raccoon-Language +
     *       X-Requested-With），积分数取 data.popup.points。</li>
     *   <li>GET /api/web/office/v3/setting_info —— point_grant_popups / point_grant_toast。</li>
     *   <li>GET /api/web/points/v1/bills?paging.limit=50&amp;paging.offset=0 —— 以账单为准：
     *       items[] 里 points&gt;0 且 created_at 为今日的全部入账，success = 今日合计 &gt; 0。
     *       grant 端点说 granted=false 也如实报"今日未见积分入账（可能已领取）"，不编造成功。</li>
     * </ol>
     * markCheckedIn 条件对齐参考 MainActivity：账单请求无错误即标记（grantsError==null）。
     */
    @Override
    public String checkin() throws IOException {
        JSONObject c = cred();
        if (c == null) return "未登录";
        String token = c.optString("accessToken", "");
        if (token.isEmpty()) return "未登录";
        String uid = c.optString("uid", "");
        JSONObject res = checkinCore(token, uid, true);
        StringBuilder sb = new StringBuilder(res.optString("msg", ""));
        JSONArray t = res.optJSONArray("grantsToday");
        if (t != null && t.length() > 0) {
            sb.append("\n\n今日入账明细：");
            for (int i = 0; i < t.length(); i++) {
                JSONObject it = t.optJSONObject(i);
                if (it == null) continue;
                sb.append("\n• ").append(it.optString("name", "积分发放"))
                  .append(" +").append(it.optLong("points", 0L))
                  .append("（").append(it.optString("at", "")).append("）");
            }
        }
        refreshLog("checkin: " + res);
        return sb.toString();
    }

    /**
     * 参考实现 checkin 的核心流程：桌面 grant → 移动 grant → (setting_info → 账单核对) → 结论。
     * 桌面/移动 grant 失败也继续（error 记进 desktopGrant/mobileGrant），账单核对照做。
     *
     * @param withBills false 时跳过 setting_info 与账单（登录后自动领取的轻量路径）
     */
    private JSONObject checkinCore(String token, String uid, boolean withBills) {
        // 1) 桌面 grant：desktop-windows / v1.0.0，4 项头，无 body
        JSONObject desktopGrant = new JSONObject();
        try {
            Http.Resp r = Http.post(BASE + DESKTOP + "/login/points/grant",
                    desktopGrantHeaders(token), 20000);
            JSONObject d = unwrapData(Http.json(r), "签到响应结构异常");
            desktopGrant.put("granted", d != null && d.optBoolean("granted", false));
            refreshLog("checkin desktop 响应体[" + r.body.length() + "]: " + truncate(r.body, 500));
        } catch (Exception e) {
            try {
                desktopGrant.put("granted", false).put("error", String.valueOf(e.getMessage()));
            } catch (org.json.JSONException ignored) {
            }
            refreshLog("checkin desktop 失败: " + e.getMessage());
        }
        // 2) 移动 grant：app-android / v1.0.4，7 项头，每次都调（参考实现不是失败才回退）
        JSONObject mobileGrant = new JSONObject();
        try {
            Http.Resp r = Http.post(BASE + "/api/web/mobile/v1/login/points/grant",
                    grantHeaders(token, "app-android", "v1.0.4"), 20000);
            JSONObject d = unwrapData(Http.json(r), "签到响应结构异常");
            mobileGrant.put("granted", d != null && d.optBoolean("granted", false));
            Object popup = d == null ? null : d.opt("popup");
            if (popup != null) mobileGrant.put("popup", popup);
            refreshLog("checkin mobile 响应体[" + r.body.length() + "]: " + truncate(r.body, 500));
        } catch (Exception e) {
            try {
                mobileGrant.put("granted", false).put("error", String.valueOf(e.getMessage()));
            } catch (org.json.JSONException ignored) {
            }
            refreshLog("checkin mobile 失败: " + e.getMessage());
        }

        if (!withBills) {
            return lightweightResult(uid, desktopGrant, mobileGrant);
        }

        // 3) setting_info：point_grant_popups / point_grant_toast
        JSONObject settings = new JSONObject();
        try {
            Http.Resp r = Http.get(BASE + "/api/web/office/v3/setting_info",
                    grantHeaders(token, "app-android", "v1.0.4"), 20000);
            JSONObject d = unwrapData(Http.json(r), "签到响应结构异常");
            settings.put("popups", d == null ? JSONObject.NULL : d.opt("point_grant_popups"));
            settings.put("toast", d == null ? JSONObject.NULL : d.opt("point_grant_toast"));
        } catch (Exception e) {
            try {
                settings.put("popups", JSONObject.NULL).put("toast", JSONObject.NULL)
                        .put("error", String.valueOf(e.getMessage()));
            } catch (org.json.JSONException ignored) {
            }
            refreshLog("checkin setting_info 失败: " + e.getMessage());
        }

        // 4) 账单核对：今日 points>0 的入账为准
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        JSONArray grantsToday = new JSONArray();
        LinkedHashMap<String, Long> weekPoints = new LinkedHashMap<>();
        String billsError = null;
        try {
            Map<String, String> bh = new LinkedHashMap<>();
            bh.put("Accept", "application/json");
            bh.put("Authorization", "Bearer " + token);
            Http.Resp r = Http.get(BASE + POINTS + "/bills?paging.limit=50&paging.offset=0", bh, 25000);
            JSONObject d = unwrapData(Http.json(r), "账单响应结构异常");
            JSONArray items = d == null ? null : d.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    Long pts = toLong(it.opt("points"));
                    if (pts == null || pts <= 0) continue;
                    String at = it.optString("created_at", "");
                    if (at.length() >= 10) {
                        weekPoints.merge(at.substring(0, 10), pts.longValue(), Long::sum);
                    }
                    if (at.length() < 10 || !today.equals(at.substring(0, 10))) continue;
                    String event = it.optString("event_name", "");
                    if (event.isEmpty()) event = "积分发放";
                    String ts = at.replace('T', ' ');
                    String hhmmss = ts.length() > 11
                            ? ts.substring(11, Math.min(19, ts.length())) : "";
                    grantsToday.put(new JSONObject()
                            .put("name", event)
                            .put("points", pts.longValue())
                            .put("at", hhmmss));
                }
            }
        } catch (Exception e) {
            billsError = e.getMessage();
            refreshLog("checkin bills 失败: " + billsError);
        }
        long sum = 0L;
        for (int i = 0; i < grantsToday.length(); i++) {
            sum += grantsToday.optJSONObject(i).optLong("points", 0L);
        }

        // 5) 结论文案（逐字对齐参考实现，分隔符"；"）
        List<String> parts = new ArrayList<>();
        if (desktopGrant.optBoolean("granted", false)) parts.add("桌面登录奖励已发放");
        if (mobileGrant.optBoolean("granted", false)) {
            long pts = mobileGrant.optJSONObject("popup") == null
                    ? 0L : mobileGrant.optJSONObject("popup").optLong("points", 0L);
            parts.add(pts > 0 ? "移动端登录奖励已发放（+" + pts + "）" : "移动端登录奖励已发放");
        }
        refreshLog("checkin bills 近7日入账: " + weekPoints);
        if (sum > 0) {
            parts.add("今日积分 +" + sum);
        } else if (billsError != null) {
            parts.add("未能核对今日入账");
        } else {
            parts.add("今日未见积分入账（可能已领取）");
        }
        if (billsError != null) parts.add("账单核对失败：" + billsError);
        if (desktopGrant.has("error")) parts.add("桌面登录奖励未领取");
        if (mobileGrant.has("error")) parts.add("移动端登录奖励未领取");

        try {
            JSONObject res = new JSONObject();
            res.put("success", sum > 0);
            res.put("msg", String.join("；", parts));
            res.put("grantsToday", grantsToday);
            res.put("desktopGrant", desktopGrant);
            res.put("mobileGrant", mobileGrant);
            res.put("settings", settings);
            res.put("grantsError", billsError == null ? JSONObject.NULL : billsError);
            // 对齐参考 MainActivity：账单请求无错误 → markCheckedIn
            if (billsError == null && uid != null && !uid.isEmpty()) {
                checkinState.markCheckedIn(uid);
            }
            return res;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 登录自动领取的轻量结论（无账单核对）：任一 grant granted=true 即标记去重。 */
    private JSONObject lightweightResult(String uid, JSONObject desktopGrant, JSONObject mobileGrant) {
        boolean anyGranted = desktopGrant.optBoolean("granted", false)
                || mobileGrant.optBoolean("granted", false);
        List<String> parts = new ArrayList<>();
        if (desktopGrant.optBoolean("granted", false)) parts.add("桌面登录奖励已发放");
        if (mobileGrant.optBoolean("granted", false)) {
            long pts = mobileGrant.optJSONObject("popup") == null
                    ? 0L : mobileGrant.optJSONObject("popup").optLong("points", 0L);
            parts.add(pts > 0 ? "移动端登录奖励已发放（+" + pts + "）" : "移动端登录奖励已发放");
        }
        if (desktopGrant.has("error")) parts.add("桌面登录奖励未领取");
        if (mobileGrant.has("error")) parts.add("移动端登录奖励未领取");
        try {
            JSONObject res = new JSONObject();
            res.put("success", anyGranted);
            res.put("msg", String.join("；", parts));
            res.put("desktopGrant", desktopGrant);
            res.put("mobileGrant", mobileGrant);
            if (anyGranted && uid != null && !uid.isEmpty()) {
                checkinState.markCheckedIn(uid);
            }
            return res;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 登录后自动领取：无账单核对（省 2 个请求），返回日志文案。 */
    private String claimLoginPoints(String accessToken, String uid) {
        if (accessToken == null || accessToken.isEmpty()) return null;
        JSONObject res = checkinCore(accessToken, uid, false);
        String msg = res.optString("msg", "");
        return msg.isEmpty() ? null : msg;
    }

    /** grant 请求通用头（对齐参考实现 MainActivity 的移动端 LoginHeaders，7 项）。 */
    private Map<String, String> grantHeaders(String token, String platform, String version) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        h.put("X-Client-Platform", platform);
        h.put("X-Client-Version", version);
        h.put("X-Raccoon-Language", "zh");
        h.put("X-Requested-With", "com.xiaohuanxiong.office");
        return h;
    }

    /** 桌面 grant 专用头（对齐参考实现：仅 4 项，无 Content-Type，平台 desktop-windows / v1.0.0）。 */
    private java.util.Map<String, String> desktopGrantHeaders(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + token);
        h.put("X-Client-Platform", "desktop-windows");
        h.put("X-Client-Version", "v1.0.0");
        return h;
    }

    /** points 字段兼容 Integer/Long/String；解析失败返回 null（跳过该条）。 */
    private static Long toLong(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Number) return ((Number) o).longValue();
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return (long) Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AccountSnapshot account() {
        AccountSnapshot s = new AccountSnapshot(ID, displayName());
        JSONObject c = cred();
        if (c == null) {
            s.credits = "未登录";
            return s;
        }
        s.loggedIn = true;
        s.accountName = c.optString("nickname", "");
        try {
            Http.Resp r = Http.get(BASE + POINTS + "/balance", headers(), 15000);
            JSONObject d = Http.json(r).optJSONObject("data");
            if (d != null) {
                // available_points 缺失即视为响应形状不对 → 保持"未知"，不编造 0
                if (d.has("available_points")) {
                    double pts = d.optDouble("available_points", 0);
                    s.creditsTotal = pts;
                    s.credits = trim(pts);
                } else {
                    s.credits = "—";
                }
                s.checkinHint = "每日积分由服务端自动发放，无需签到";
            }
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        return s;
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
