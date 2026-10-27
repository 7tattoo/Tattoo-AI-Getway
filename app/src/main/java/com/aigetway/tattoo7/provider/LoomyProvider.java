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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Loomy（讯飞办公助手）。协议来自 _upstream/src/loomy-*.ts：
 * - 业务端点 https://loomyad.xunfei.cn/api/v1（chat / models / points）
 * - 账号端点 https://account.xfinfr.com（短信验证码登录）
 * - 账号端点是 **HMAC-SHA1 签名**（Authorization: account {ak}:{sig}），
 *   业务端点 chat 只认 Bearer + token 头。
 */
public class LoomyProvider implements ServiceProvider {

    public static final String ID = "loomy";

    private static final String PREFS = "svc_loomy";
    private static final String CK = "cred";
    private static final String SK_MSGID = "msgid";

    private static final String API_BASE = "https://loomyad.xunfei.cn/api/v1";
    private static final String ACCOUNT_BASE = "https://account.xfinfr.com";
    private static final String ACCESS_KEY_ID = "2thryby66wxi53sk";
    private static final String ACCESS_KEY_SECRET = "zsak6eadrbawz683wf5r3m2snrwj868r";
    private static final String APP_ID = "GM3LOOMY";
    private static final String OK_CODE = "000000";

    private final SharedPreferences prefs;
    private final com.aigetway.tattoo7.account.AccountPool pool;

    public LoomyProvider(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(ID);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Loomy / 讯飞";
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
        return c != null && !c.optString("session", "").isEmpty();
    }

    private String session() {
        JSONObject c = cred();
        return c == null ? null : c.optString("session", null);
    }

    /** 业务端点头：chat 只认 Bearer + token。 */
    private Map<String, String> bizHeaders() throws IOException {
        if (!loggedIn()) throw new IOException("Loomy 未登录");
        String t = session();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + t);
        h.put("token", t);
        h.put("User-Agent", "Loomy|Desktop|Electron|macOS");
        return h;
    }

    // ---------------- 签名（账号端点） ----------------

    /** 讯飞账号签名：HMAC-SHA1 → base64，Authorization: account {ak}:{sig}。 */
    private static Map<String, String> accountHeaders(String method, String path, String body) {
        String date = utcNow();
        String nonce = UUID.randomUUID().toString();
        String contentType = "application/json";
        String md5 = md5Base64(body);

        StringBuilder sb = new StringBuilder();
        sb.append(method.toUpperCase()).append('\n');
        sb.append(path).append('\n');           // canonical path
        sb.append("").append('\n');              // canonical query（账号端点无 query）
        sb.append(md5).append('\n');
        sb.append(contentType).append('\n');
        sb.append(date).append('\n');
        sb.append(nonce).append('\n');
        sb.append("").append('\n');              // signedHeaders（本项目不发 x-* 头）
        sb.append("");                           // canonicalizedHeaders
        String signature = hmacSha1Base64(ACCESS_KEY_SECRET, sb.toString());

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "account " + ACCESS_KEY_ID + ":" + signature);
        h.put("Date", date);
        h.put("Nonce", nonce);
        h.put("Content-Type", contentType);
        if (!md5.isEmpty()) h.put("Content-MD5", md5);
        return h;
    }

    /** HTTP-date 格式（GMT），例：Tue, 06 Oct 2026 10:00:00 GMT */
    private static String utcNow() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new java.util.Date());
    }

    private static String md5Base64(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return java.util.Base64.getEncoder().encodeToString(md.digest(body.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hmacSha1Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JSONObject accountBody(JSONObject param) throws org.json.JSONException {
        JSONObject base = new JSONObject();
        base.put("appid", APP_ID);
        base.put("modelid", "Web");
        base.put("version", "1.0.0");
        base.put("devid", "web");
        base.put("ua", "Loomy|Desktop|Electron|macOS");
        base.put("traceid", UUID.randomUUID().toString().replace("-", ""));
        JSONObject body = new JSONObject();
        body.put("base", base);
        body.put("param", param);
        return body;
    }

    /** 发一次账号端点请求，返回 data。 */
    private JSONObject postAccount(String path, JSONObject param) throws IOException {
        String body;
        try {
            body = accountBody(param).toString();
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }
        Http.Resp r = Http.postJson(ACCOUNT_BASE + path, accountHeaders("POST", path, body), body, 30000);
        JSONObject j = Http.json(r);
        String code = j.optString("code", "");
        if (!OK_CODE.equals(code)) {
            String desc = j.optString("desc", j.optString("message", ""));
            throw new IOException(desc.isEmpty() ? ("code=" + code) : desc);
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) throw new IOException("响应缺少 data");
        return d;
    }

    // ---------------- 短信登录 ----------------

    /** 下发验证码，返回 msgid。 */
    public String sendSmsCode(String phone) throws IOException {
        JSONObject p = new JSONObject();
        try {
            p.put("ccode", "86");
            p.put("phone", phone);
            p.put("expire", 300);
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        JSONObject d = postAccount("/login/phone/sendMsgCode", p);
        String msgid = d.optString("msgid", "");
        if (msgid.isEmpty()) throw new IOException("响应缺少 msgid");
        prefs.edit().putString(SK_MSGID, msgid).apply();
        return msgid;
    }

    /** 提交验证码，登录成功返回 true。 */
    public boolean submitSmsCode(String phone, String code) throws IOException {
        String msgid = prefs.getString(SK_MSGID, "");
        JSONObject p = new JSONObject();
        try {
            p.put("ccode", "86");
            p.put("phone", phone);
            p.put("mcode", code);
            p.put("msgid", msgid);
            p.put("expire", 1209600);
        } catch (org.json.JSONException e) {
            throw new IOException("bad param: " + e.getMessage());
        }
        JSONObject d = postAccount("/login/phone/checkCode", p);
        String s = d.optString("session", "");
        if (s.isEmpty()) throw new IOException("登录响应缺少 session");
        JSONObject c = cred() == null ? new JSONObject() : cred();
        try {
            c.put("session", s);
            c.put("userid", d.optString("userid", ""));
            c.put("phone", phone);
            c.put("expiresAt", System.currentTimeMillis() + 1_209_600_000L);
        } catch (org.json.JSONException e) {
            throw new IOException("save failed: " + e.getMessage());
        }
        addCred(c, phone);
        prefs.edit().remove(SK_MSGID).apply();
        return true;
    }

    public void logout() {
        pool.clear();
        prefs.edit().clear().apply();
    }

    @Override
    public boolean refreshFingerprint() throws IOException {
        // 讯飞 session 有效期 14 天，登录时即申请了长有效期，无需单独刷新
        return loggedIn();
    }

    // ---------------- 模型 ----------------

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        Http.Resp r = Http.get(API_BASE + "/models", bizHeaders(), 25000);
        JSONObject j = Http.json(r);
        JSONArray arr = j.optJSONArray("data");
        if (arr == null && j.opt("data") instanceof JSONArray) arr = j.optJSONArray("data");
        List<ModelEntry> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                // 过滤判据是 type，不能看 input_modalities
                if (!"chat".equals(m.optString("type", ""))) continue;
                String id = m.optString("id", "");
                if (id.isEmpty()) continue;
                String nm = m.optString("name", id);
                ModelEntry e = new ModelEntry(ID, id, nm);
                // 计费倍率藏在展示名末尾（远端原值 "MiniMax M3 （x4.0）" 全/半角括号混用，
                // 规范化形态 "{name} · x4.0"）—— 抽出来单独展示，解析不出不编造。
                e.rateText = ModelRate.splitRateSuffix(nm);
                e.contextWindow = (int) m.optDouble("context_length", 262144);
                JSONObject caps = m.optJSONObject("capabilities");
                // ⚠️ 远端真实字段是 capabilities.input_modalities（对齐参考实现
                // readInputModalities）；旧代码读顶层 input_modalities 恒为 null。
                // 两形态都认：caps 内优先，顶层兜底。
                JSONArray modal = caps == null ? null : caps.optJSONArray("input_modalities");
                if (modal == null) modal = m.optJSONArray("input_modalities");
                // ⚠️ 必须过 loomySupportsImage（对齐参考 PR !96）：远端对 qwen-3.8-max
                // 漏报 input_modalities（只给 ['text']），照抄会让 Minis 在附件入库阶段
                // 按目录拒图（「当前模型不支持图片，请切换支持图片的模型」）。
                e.vision = loomySupportsImage(id, hasImageModal(modal));
                e.reasoning = caps != null && caps.optJSONArray("reasoning_efforts") != null;
                e.live = true;
                out.add(e);
            }
        }
        if (out.isEmpty()) throw new IOException("Loomy 模型列表拉取失败");
        return out;
    }

    private boolean hasImageModal(JSONArray arr) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if ("image".equalsIgnoreCase(arr.optString(i))) return true;
        }
        return false;
    }

    /**
     * 远端声明为**纯文本**、但实测**能读图**的模型（`input_modalities` 漏报）。
     *
     * 对齐参考实现 deepseek-harness-codearts PR !96 / src/loomy-product.ts 的
     * LOOMY_IMAGE_CAPABILITY_OVERRIDES：远端 GET /api/v1/models 对 qwen-3.8-max
     * 下发 capabilities.input_modalities=['text']（vision:false），而实测它读得出图
     * （判据=随机纯色 PNG，两轮 4/5 次答对颜色/RGB）。照抄声明值 ⇒ Minis 在附件
     * 入库阶段就按目录拒绝（MODEL_DOES_NOT_SUPPORT_IMAGES）。
     *
     * ⚠️ 表内条目必须**小写**（查表经 loomyCatalogKey 归一）。
     * ⚠️ 只覆盖实测确认的个案，**不要写成「该家恒支持图片」**——恒 true 会把图放给
     *    纯文本模型，让上游回一个更晚、更难懂的 400。
     */
    private static final java.util.Set<String> LOOMY_IMAGE_CAPABILITY_OVERRIDES =
            new java.util.HashSet<>(java.util.Arrays.asList("qwen-3.8-max"));

    /** 模型 id 的归一化查表键（小写、去首尾空白）——本表唯一查表口径。 */
    private static String loomyCatalogKey(String modelId) {
        return modelId == null ? "" : modelId.trim().toLowerCase(Locale.US);
    }

    /**
     * 取一个模型的**真实**图片能力：覆盖表优先于远端声明。
     *
     * ⚠️ 远端与兜底表**两条路径都必须过这里** —— 与 loomyContextWindow 同一条纪律：
     *    只改一侧，远端可用时那一侧就白改了。兜底表起算值恒 false，覆盖表是唯一
     *    真相源。
     */
    private static boolean loomySupportsImage(String modelId, boolean declared) {
        return LOOMY_IMAGE_CAPABILITY_OVERRIDES.contains(loomyCatalogKey(modelId)) || declared;
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        l.add(fb("deepseek-v4-flash-0731", 1048576));
        l.add(fb("MiniMax-M3", 1048576));
        l.add(fb("Kimi-k2.6", 262144));
        l.add(fb("qwen-3.8-max", 1000000));
        l.add(fb("GLM-5.3-Flash", 1048576));
        l.add(fb("qwen3.8-flash", 1000000));
        l.add(fb("spark-x", 1048576));
        l.add(fb("mimo-v2.5", 1048576));
        return l;
    }

    /**
     * 兜底条目：图片能力**同样要过覆盖表**（与远端路径同一判据，两条路径不得分叉）。
     * 兜底表起算值恒 false，覆盖表是唯一真相源 —— 对齐参考实现 fallbackToRemote。
     */
    private static ModelEntry fb(String id, int ctx) {
        ModelEntry e = ModelEntry.fallback(ID, id, ctx, 8192, false);
        e.vision = loomySupportsImage(id, false);
        return e;
    }

    // ---------------- chat ----------------

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        Map<String, String> h = bizHeaders();
        h.put("Accept", "text/event-stream");
        h.put("Content-Type", "application/json");
        JSONObject b;
        try {
            b = new JSONObject(body.toString());
            b.put("stream", true);
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(API_BASE + "/chat/completions").openConnection();
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
        s.accountName = c.optString("phone", c.optString("userid", ""));
        try {
            refreshCredits(s);
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        // 新手任务进度（一次性，与签到独立）。查询失败不影响卡片其余信息，静默。
        if (s.error == null || s.error.isEmpty()) {
            try {
                String tk = session();
                if (tk != null && !tk.isEmpty()) {
                    LoomyOnboarding.State ob = LoomyOnboarding.fetch(tk);
                    s.onboardingHint = ob.earned + "/" + ob.total;
                }
            } catch (Exception ignore) {
                // 保持空串
            }
        }
        return s;
    }

    /**
     * 查余额（只读端点）。字段口径对拍参考实现 {@code src/loomy-credits.ts}（对拍过官方客户端）。
     *
     * <p>Loomy 的积分是**两个分开计算的池**：
     * <ul>
     *   <li>{@code balance} —— 永久积分（注册奖励 + 新手任务），不过期；</li>
     *   <li>{@code dailyBalance} —— 每日赠送池余额（当日有效，消耗后不回补，次日重发）；</li>
     *   <li>{@code availableBalance} —— 两者之和（客户端账号卡片上的合计）。</li>
     * </ul>
     *
     * <p>⚠️ 旧实现只读同一个 data 里的 {@code remainPoints}，跟官方客户端显示的数字不是一个口径，
     * 于是出现「签到后账号卡片上纹丝不动」（vc167 前的实测症状）。现在按池读，
     * 合计取 {@code availableBalance}，缺失时用两池相加兜底。
     *
     * <p>⚠️「上游没给某池」与「该池为 0」是不同事实：{@code balance} 缺失时不显示永久池
     * （{@link AccountSnapshot#permanentCredits} 留空），不造 0。
     *
     * <p>{@code dailyQuota} 只在 {@code first-login} 响应里，本端点不返回，故卡片上的每日池
     * 只显示余额、不显示「已用/上限」。
     */
    public void refreshCredits(AccountSnapshot s) throws IOException {
        // vc152：20s→10s。该端点实测 0.4s 出头（含 TLS），10s 已是 25 倍裕量；
        // 上游偶尔黑洞时把单账号失败代价从 20s 降到 10s（3 账号最坏 30s 而非 60s）。
        Http.Resp r = Http.get(API_BASE + "/points/records?pageNo=1&pageSize=1&recordType=all",
                bizHeaders(), 10000);
        JSONObject j = Http.json(r);
        String code = j.optString("code", "");
        if (!OK_CODE.equals(code)) {
            s.error = j.optString("desc", "code=" + code);
            return;
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return;

        Double perm = optNum(d, "balance");
        Double daily = optNum(d, "dailyBalance");
        Double avail = optNum(d, "availableBalance");
        if (perm == null && daily == null && avail == null) {
            // 两池字段一个都没有：说明响应形状与预期不符（协议改动 / 特殊账号）。
            // 回落到旧口径，宁可数字口径存疑，也不编造 0。
            double pts = d.optDouble("remainPoints", d.optDouble("total", 0));
            s.creditsTotal = pts;
            s.credits = trim(pts);
            return;
        }
        double p = perm == null ? 0 : perm;
        double dl = daily == null ? 0 : daily;
        double total = avail != null ? avail : p + dl;
        if (perm != null) s.permanentCredits = trim(p);
        s.dailyCredits = trim(dl);
        s.creditsTotal = total;
        s.credits = trim(total);
    }

    /** 整数去小数尾巴，小数保留两位（与其他 provider 一致）。 */
    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** 读成 Double；字段缺失/为空/非数字返回 null（「没给」≠「是 0」）。 */
    private static Double optNum(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        Object v = o.opt(k);
        try {
            double dv = v instanceof Number
                    ? ((Number) v).doubleValue()
                    : Double.parseDouble(String.valueOf(v));
            return Double.isNaN(dv) ? null : dv;
        } catch (Exception e) {
            return null;
        }
    }


    @Override
    public String checkin() throws IOException {
        Http.Resp r = Http.postJson(API_BASE + "/points/first-login", bizHeaders(), "{}", 20000);
        loomyLog("签到响应体[" + r.body.length() + "]: " + truncate(r.body, 400));
        JSONObject j = Http.json(r);
        String code = j.optString("code", "");
        if (!OK_CODE.equals(code)) {
            return "签到失败 " + j.optString("desc", "code=" + code);
        }
        JSONObject d = j.optJSONObject("data");
        double quota = d == null ? 0 : d.optDouble("dailyQuota", 0);
        double dailyBal = d == null ? 0 : d.optDouble("dailyBalance", 0);
        boolean already = d != null && d.optBoolean("alreadyProcessed", false);
        // ⚠️ 语义是「初始化 / 重置每日赠送池」，**不是**「+N 积分」：
        // dailyBalance = dailyQuota - dailyConsumed，消耗后不回补。
        // 写成「本次 +N 积分」会让人以为总数该涨，一旦签完没涨就变成「签到不给分」的误会。
        String msg = already ? "今日额度已初始化" : "签到成功";
        msg += quota > 0
                ? "（每日 " + trim(dailyBal) + "/" + trim(quota) + "）"
                : "（服务端未返回今日额度）";
        // ⚠️ vc175：新手任务**不**参与签到（单账号「签到」与页头「一键签到」都不碰它）。
        // 它是一次性奖励（每号只能领一次），入口只有用户自己点的那两个按钮：
        // 账号卡片上的「新手任务」、服务商页的「一键领新手任务（全部 N 个账号）」。
        // 历史：vc164/vc165 曾把「签到后立刻 claimAll」接进本方法（对齐官方客户端），
        // 但签到是每天都会跑的动作，于是「一键签到结果」里每天都混进一条
        // 「新手任务无新增（进度 10000/10000）」，既与签到语义无关，
        // 也容易让人误以为签到会顺带发新手任务分。用户明确要求退回纯手动的一次性功能，
        // 因此这里不再有任何自动领取；新手任务状态仍由 account() 只读展示（见上）。
        return msg;
    }

    /** 签到 / 新手任务过程写网关日志（只记响应体摘要，不落凭据）。 */
    private void loomyLog(String msg) {
        try {
            com.aigetway.tattoo7.core.App.get().logSink().log("LOOMY-CHECKIN", "Loomy " + msg);
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---------------- 新手任务（一次性，与签到独立） ----------------

    @Override
    public boolean supportsOnboardingTasks() {
        return true;
    }

    @Override
    public String claimOnboarding() throws IOException {
        String tk = session();
        if (tk == null || tk.isEmpty()) throw new IOException("Loomy 未登录");
        LoomyOnboarding.Result r = LoomyOnboarding.claimAll(tk);
        if (r.fullyClaimed()) {
            return "新手任务已全部领取，累计 " + LoomyOnboarding.TOTAL
                    + " 积分（本次补领 +" + r.claimedPoints + "）";
        }
        return "本次领取 " + r.claimedKeys.size() + " 项（+" + r.claimedPoints
                + "），新手任务进度 " + r.earned + "/" + r.total;
    }
}