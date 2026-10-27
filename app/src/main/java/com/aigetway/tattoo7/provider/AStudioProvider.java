package com.aigetway.tattoo7.provider;

import com.aigetway.tattoo7.account.AccountPool;
import com.aigetway.tattoo7.account.AccountPoolHolder;
import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.util.Http;
import com.aigetway.tattoo7.util.QrDiagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AStudio（讯飞 AstronStudio，桌面端 AUMID {@code cn.xfyun.acode}）。
 *
 * <p>协议来自参考实现 PR 118 的 {@code src/astudio-*.ts}（本地副本
 * {@code /workspace/work/astudio-ref/}）与其实测笔记 {@code docs/agent-notes/providers-astudio.md}。
 *
 * <h2>双域架构（与其余服务商最大的不同）</h2>
 * <table>
 *   <tr><td>推理网关</td><td>https://astronstudio-api-volces-prod.xf-yun.com/model-gateway/v1</td>
 *       <td>Authorization: Bearer &lt;modelBearerToken&gt;</td><td>/chat/completions</td></tr>
 *   <tr><td>模型目录</td><td>https://astronstudio-api-volces-prod.xf-yun.com/api/v1/model-manager/models</td>
 *       <td>同上</td><td>模型清单（+/bot/models/configs 账号可调并集）</td></tr>
 *   <tr><td>账号控制面</td><td>https://agent.xfyun.cn/xingchen-studio</td>
 *       <td>Cookie: ssoSessionId=…（四字段串）</td><td>积分 / 签到 / 凭据轮换</td></tr>
 *   <tr><td>SSO</td><td>https://sso.xfyun.cn/SSOService/wx/scanlogin</td>
 *       <td>fd 随机串</td><td>微信扫码授权</td></tr>
 * </table>
 *
 * <h2>六条实测结论（照抄「OpenAI 兼容」的直觉会踩）</h2>
 * <ol>
 *   <li><b>两个基址不共用前缀</b>：推理在 {@code /model-gateway/v1}，目录在
 *       {@code /api/v1/model-manager}。{@code /model-gateway/v1/models} 实测恒 404。</li>
 *   <li><b>上行 {@code model} 只认裸 slug</b>（{@code xopglm52}）：发
 *       {@code builtin/<base64url>} 内部别名回 502 {@code model_not_found}。见 {@link #bareSlug}。</li>
 *   <li><b>两种错误信封</b>：目录/账号面是 {@code {code,message,data}} 且 <b>HTTP 仍 200</b>
 *       （{@code code != 0} 即失败）；推理面是 OpenAI 裸体 + 非 2xx。</li>
 *   <li><b>会话失效的硬终点是 {@code code=80000}</b>「登录异常，请重新登录」（HTTP 仍是 200）
 *       —— 必须按码值判，只靠中文文案正则会退化成「可重试、每轮白撞」。</li>
 *   <li><b>非法思考档位上游不报错</b>：{@code reasoning_effort:"bogus"} 回 200 并按最大档跑
 *       （档位关联计费）⇒ 必须在本地拦截，且<b>就近映射是错的</b>（见 {@link #effortForRequest}）。</li>
 *   <li><b>推理端点认小写 {@code uid} 头</b>（AStudio2API 同款），真实 uid 在
 *       {@code /userInfo} 的 {@code data.userInfo.*} 里，登录响应里的可能是占位
 *       {@code "0"}；占位值过 {@link #normalizeUid} 会被滤掉、不发头。</li>
 * </ol>
 *
 * <p>登录不走 OAuth 授权 URL：本家没有可程序化走完的授权端点（Electron 客户端内做微信扫码）。
 * 本 App 的做法是<b>内置浏览器页 + 本机载体服务</b>：
 * {@link com.aigetway.tattoo7.ui.AStudioLoginActivity} 起一个 127.0.0.1 上的小 HTTP 服务，
 * 页面（{@code assets/astudio_login.html}）用极验官方 gt.js 跑人机验证（这一步只能在 WebView 里做），
 * 再把结果交回本类完成登录与凭据装配。短信 / 密码 / 微信扫码三条通路都落在这里。
 */
public class AStudioProvider implements ServiceProvider {

    public static final String ID = "astudio";
    public static final String DISPLAY_NAME = "AStudio / 讯飞";

    // ---------------- 端点 ----------------

    /**
     * 推理网关基址（OpenAI 兼容）。
     *
     * <p>⚠️ vc168 线上事故的根因：configs 下发的 {@code api_key} 只认
     * {@code maas-api.cn-huabei-1.xf-yun.com/v1}；打
     * {@code astronstudio-api-volces-prod.xf-yun.com/model-gateway/v1} 会回
     * 401 {@code INVALID_API_KEY}（对照 AStudio2API 的 DefaultUpstreamBase 坐实）。
     * 目录域（astronstudio-api…/api/v1/model-manager）与推理域**不通用**。
     */
    static final String MAAS_BASE = "https://maas-api.cn-huabei-1.xf-yun.com/v1";
    static final String CHAT_URL = MAAS_BASE + "/chat/completions";
    /** 模型目录（⚠️ 与推理网关**同域不同前缀**，不是 /model-gateway/v1/models）。 */
    static final String CATALOG_URL =
            "https://astronstudio-api-volces-prod.xf-yun.com/api/v1/model-manager/models";
    /** 账号控制面基址。 */
    static final String AUTH_BASE = "https://agent.xfyun.cn/xingchen-studio";
    /** SSO（微信扫码）。 */
    static final String SSO_BASE = "https://sso.xfyun.cn/SSOService/wx/scanlogin";

    /** 客户端自报类型（21 = win32，与真实客户端一致；不带也能 200，但不按当前 OS 去猜）。 */
    static final String CLIENT_TYPE = "21";
    /** 客户端自报产品版本（AStudio2API 实测可用值；另一参考实现取 3.5.2）。 */
    static final String STUDIO_VERSION = "3.5.2";

    /** 会话失效码。80000 = 「登录异常，请重新登录」，实测的硬终点。 */
    private static final Set<Integer> SESSION_DEAD_CODES =
            new LinkedHashSet<>(Arrays.asList(1002, 1005, 80000));

    /** 每日签到弹窗类型。 */
    static final String POPUP_DAILY = "DAILY_REWARD_DIALOG";
    /** 新人礼（新用户）弹窗类型 —— 对应 UI 上的「新手任务」按钮。 */
    static final String POPUP_NEW_USER = "NEW_USER_DIALOG";
    /** 客户端下载奖励弹窗（一次性，已领过会回业务错误，属正常）。 */
    static final String POPUP_DOWNLOAD = "CLIENT_DOWNLOAD_REWARD_DIALOG";

    private final AccountPool pool;

    /**
     * 远端目录里每个模型声明的思考档位（{@code supported_reasoning_levels[].effort}）。
     * fetchModels() 刷新；{@link #effortForRequest} 用它拦非法档位。
     */
    private static final Map<String, Set<String>> EFFORTS = new ConcurrentHashMap<>();

    public AStudioProvider() {
        this.pool = AccountPoolHolder.get(ID);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    // ---------------- 凭据 ----------------

    /** 当前账号凭据（JSON）。字段：ssoSessionId / token / uid / accountId / modelBearerToken / …。 */
    JSONObject cred() {
        try {
            AccountPool.Account a = pool.current();
            if (a == null) return null;
            return new JSONObject(a.cred);
        } catch (Exception e) {
            return null;
        }
    }

    public AccountPool pool() {
        return pool;
    }

    public String activeAccountId() {
        return pool.activeId();
    }

    /** 上游报错时切到下一个可用账号。 */
    public boolean rotateAccount() {
        return pool.rotate() != null;
    }

    @Override
    public boolean loggedIn() {
        JSONObject c = cred();
        if (c == null) return false;
        // 对齐 AStudio2API 的 Session.Valid()：account_id + token 是会话的最小集合；
        // ssoSessionId 缺失时部分端点（扫码 bind 后）也能靠 token 走通。
        return (!c.optString("accountId", "").isEmpty() && !c.optString("token", "").isEmpty())
                || !c.optString("ssoSessionId", "").isEmpty();
    }

    /** 登录成功：新增一个账号（不覆盖已有账号）。 */
    /**
     * 登录成功落库：按归一化 uid 走 upsert（对齐 vc141 的 addOrUpdate 语义）——同一账号
     * 用验证码\/密码\/微信反复登录只更新一条记录，不堆重复行；uid 解析不出来才退化为无条件新增。
     */
    public void addCred(JSONObject o, String label) {
        String uid = normalizeUid(o.optString("uid", ""));
        if (uid.isEmpty()) {
            pool.addAccount(o.toString(), label);
        } else {
            pool.addOrUpdate(uid, o.toString(), label);
        }
    }

    /** 凭据自愈：更新当前账号。 */
    void saveCred(JSONObject o) {
        if (pool.isEmpty()) pool.addAccount(o.toString(), null);
        else pool.updateCred(pool.activeId(), o.toString());
    }

    private String modelBearerToken() {
        JSONObject c = cred();
        return c == null ? "" : c.optString("modelBearerToken", "");
    }

    /**
     * 账号面 Cookie 串：驼峰与蛇形**同值各发一次**（服务端认的键名集合，不是冗余失误）；
     * {@code account_id} / {@code token} **恒带**（AStudio2API 的 Session.Cookie() 同款，
     * 空串也带键）；{@code access_token}（modelBearerToken）绝不进 Cookie。
     */
    static String cookieHeader(JSONObject cred) {
        if (cred == null) return "";
        String sid = cred.optString("ssoSessionId", "");
        String token = cred.optString("token", sid);
        String accountId = cred.optString("accountId", cred.optString("uid", ""));
        StringBuilder sb = new StringBuilder();
        if (!sid.isEmpty()) {
            sb.append("ssoSessionId=").append(sid);
            sb.append("; sso_sessionid=").append(sid);
            sb.append("; ");
        }
        sb.append("account_id=").append(accountId);
        sb.append("; token=").append(token);
        return sb.toString();
    }

    /**
     * uid 归一化（对齐 AStudio2API NormalizeUID）：登录响应里的 {@code uid} 可能是占位
     * {@code "0"}（真实 uid 只在 userInfo 里），占位值既不能进 {@code uid} 请求头也不能当账号 id。
     */
    static String normalizeUid(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.isEmpty() || "0".equals(v) || v.length() > 256) return "";
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '@' || ch == '-';
            if (!ok) return "";
        }
        return v;
    }

    /**
     * 推理/目录端点公共头（Bearer 是唯一必需项；账号面**不能**带它）。
     *
     * <p>⚠️ 推理域认的是小写 {@code uid} 头（AStudio2API 同款），不是
     * {@code X-Account-Id}；uid 取归一化后的值，占位 "0" 不发。
     */
    Map<String, String> inferHeaders() throws IOException {
        String tk = modelBearerToken();
        if (tk.isEmpty()) throw new IOException("AStudio 未登录（缺少 modelBearerToken）");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + tk);
        h.put("User-Agent", "AStudio/" + STUDIO_VERSION + " (Windows)");
        h.put("clientType", CLIENT_TYPE);
        JSONObject c = cred();
        String uid = c == null ? "" : normalizeUid(c.optString("uid", ""));
        if (!uid.isEmpty()) h.put("uid", uid);
        return h;
    }

    /** 账号控制面请求头（Cookie 鉴权，**不带** Bearer）。 */
    static Map<String, String> authHeaders(JSONObject cred) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Cookie", cookieHeader(cred));
        h.put("clientType", CLIENT_TYPE);
        h.put("studioVersion", STUDIO_VERSION);
        h.put("User-Agent", "AStudio/" + STUDIO_VERSION + " (Windows)");
        return h;
    }

    /** 账号面信封判读：HTTP 恒 200 + {@code code}；返回 null 表示成功（并把 data 交回）。 */
    static JSONObject authEnvelope(Http.Resp r, String what) throws IOException {
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            String msg = errText(j);
            String suffix = SESSION_DEAD_CODES.contains(code)
                    ? "（登录会话已失效，请重新登录）" : "";
            throw new IOException(what + "失败：code=" + code + " " + msg + suffix);
        }
        JSONObject d = j.optJSONObject("data");
        return d == null ? j : d;
    }

    static int codeOf(JSONObject j) {
        Object raw = j.opt("code");
        if (raw == null) return 0;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static String errText(JSONObject j) {
        String s = j.optString("desc", "");
        if (s.isEmpty()) s = j.optString("message", "");
        if (s.isEmpty()) s = j.optString("msg", "");
        return s;
    }

    static boolean sessionDead(int code, String msg) {
        if (SESSION_DEAD_CODES.contains(code)) return true;
        String m = msg == null ? "" : msg;
        return m.contains("重新登录") || m.contains("登录异常");
    }

    // ---------------- 模型 ----------------

    /**
     * {@code builtin/<base64url(裸 slug)>} → 裸 slug（上游只认后者，见文件头第 2 条）。
     * 解不出就原样返回 —— 解析失败绝不能让整个 provider 不可用。
     */
    static String bareSlug(String slug) {
        if (slug == null) return "";
        if (!slug.startsWith("builtin/")) return slug;
        try {
            String enc = slug.substring("builtin/".length());
            // base64url 常不带 padding，JDK 的解码器要求 4 字节对齐，补齐再解。
            int pad = (4 - enc.length() % 4) % 4;
            if (pad > 0) enc = enc + "====".substring(0, pad);
            byte[] raw = Base64.getUrlDecoder().decode(enc);
            String decoded = new String(raw, StandardCharsets.UTF_8);
            if (!decoded.isEmpty() && !decoded.equals(enc)
                    && !decoded.contains("\u0000") && !decoded.contains("/")) {
                return decoded;
            }
        } catch (Exception ignored) {
            // 落到原样返回
        }
        return slug;
    }

    @Override
    public List<ModelEntry> fetchModels() throws IOException {
        Map<String, String> catHeaders = inferHeaders();
        catHeaders.put("Accept", "application/json");
        Http.Resp r = Http.get(CATALOG_URL, catHeaders, 25000);
        JSONObject j = Http.json(r);
        if (codeOf(j) != 0) throw new IOException("AStudio 模型目录拉取失败：" + errText(j));
        JSONObject data = j.optJSONObject("data");
        JSONArray models = data == null ? null : data.optJSONArray("models");
        if (models == null) throw new IOException("AStudio 模型目录形状不符（无 data.models）");

        // 按裸 slug 去重：本地回环网关会把同一个模型下发两份（builtin/… 为 list、
        // 裸 slug 为 hide 别名）。两份都在时保留 visibility:list 的那份。
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        Map<String, String> visById = new LinkedHashMap<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            String rawSlug = m.optString("slug", m.optString("id", ""));
            if (rawSlug.isEmpty()) continue;
            // supported_in_api=false 是上游明确说「API 不可调」的条目，不下发
            //（字段缺席按支持处理，避免老目录把全部模型藏掉）。
            if (m.has("supported_in_api") && !m.optBoolean("supported_in_api", true)) continue;
            String sid = bareSlug(rawSlug);
            if (sid.isEmpty()) continue;
            String vis = m.optString("visibility", "list");
            if (vis.isEmpty()) vis = "list";
            JSONObject exists = byId.get(sid);
            if (exists == null || ("hide".equals(visById.get(sid)) && !"hide".equals(vis))) {
                byId.put(sid, m);
                visById.put(sid, vis);
            }
        }

        List<ModelEntry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 目录原始索引（小写 slug → 条目，**含被 supported_in_api=false 过滤掉的**）：
        // bot/models/configs 并集里的模型若目录里同名（只是 API 不可调或 hide），
        // 能力就从这里继承，别给 AG 客户端发全默认的假条目。
        Map<String, JSONObject> rawBySlug = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> e : byId.entrySet()) {
            rawBySlug.put(e.getKey().toLowerCase(Locale.US), e.getValue());
        }
        for (Map.Entry<String, JSONObject> e : byId.entrySet()) {
            String sid = e.getKey();
            if ("hide".equals(visById.get(sid)) && byId.size() > 1) continue; // 内部别名不下发
            JSONObject m = e.getValue();
            ModelEntry me = new ModelEntry(ID, sid, m.optString("display_name", sid));
            // 上游没给就置 0，交给 MultiCatalog 的 ModelCaps.enrich 按名族推断，
            // 不吃 ModelEntry 的 128000/8192 保底默认（那会让 enrich 的 <=0 分支失效）。
            me.contextWindow = (int) m.optDouble("context_window", 0);
            me.maxOutput = (int) m.optDouble("max_output_tokens", 0);
            // ⚠️ 图片能力取远端 input_modalities；缺省 false（fail-closed，不是「未知=放行」）。
            me.vision = hasImageModality(m.optJSONArray("input_modalities"));
            Set<String> efforts = readEfforts(m);
            me.reasoning = !efforts.isEmpty();
            if (!efforts.isEmpty()) {
                EFFORTS.put(sid.toLowerCase(Locale.US), efforts);
                me.efforts = new ArrayList<>(efforts);
                // 默认档必须落在档位集合内才下发（参考实现同口径），否则按未知处理
                String de = m.optString("default_reasoning_level", "");
                if (!de.isEmpty() && efforts.contains(de)) me.defaultEffort = de;
            }
            me.live = true;
            out.add(me);
            seen.add(sid.toLowerCase(Locale.US));
        }
        // 并上 bot/models/configs 的账号可调清单（AStudio2API 同款并集口径）：
        // 目录是「面向 agent 的展示清单」，configs 才是「这个凭据真正能调的清单」，
        // 只展示目录会漏掉目录未上架但账号可用的条目。
        try {
            JSONObject cred = cred();
            if (cred != null) {
                Http.Resp cr = Http.get(AUTH_BASE + "/bot/models/configs", authHeaders(cred), 20000);
                JSONObject cj = Http.json(cr);
                if (codeOf(cj) == 0 && cj.opt("data") instanceof JSONArray) {
                    JSONArray rows = cj.optJSONArray("data");
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        String slug = bareSlug(row.optString("model", row.optString("id", "")));
                        if (slug.isEmpty()) continue;
                        String key = slug.toLowerCase(Locale.US);
                        if (seen.contains(key)) continue;
                        seen.add(key);
                        ModelEntry me = new ModelEntry(ID, slug, row.optString("name", slug));
                        JSONObject dir = rawBySlug.get(key);
                        if (dir != null) {
                            // 目录里有同名条目（只是 supported_in_api=false / hide 被跳过）：
                            // 精确继承能力 + 档位，别让 AG 客户端看到全默认假条目。
                            me.contextWindow = (int) dir.optDouble("context_window", 0);
                            me.maxOutput = (int) dir.optDouble("max_output_tokens", 0);
                            me.vision = hasImageModality(dir.optJSONArray("input_modalities"));
                            Set<String> eff = readEfforts(dir);
                            me.reasoning = !eff.isEmpty();
                            if (!eff.isEmpty()) {
                                EFFORTS.put(key, eff);
                                me.efforts = new ArrayList<>(eff);
                                String de = dir.optString("default_reasoning_level", "");
                                if (!de.isEmpty() && eff.contains(de)) me.defaultEffort = de;
                            }
                        } else {
                            // 目录完全没有：contextWindow/maxOutput 置 0，交给
                            // MultiCatalog 的 ModelCaps.enrich 按模型名族推断
                            //（直接吃 128000/8192 构造默认会让 enrich 以为「已有值」不填）。
                            me.contextWindow = 0;
                            me.maxOutput = 0;
                        }
                        me.live = true;
                        out.add(me);
                    }
                }
            }
        } catch (Exception e) {
            asLog("configs 模型并集失败（忽略，仅用目录）：" + e.getMessage());
        }
        if (out.isEmpty()) throw new IOException("AStudio 模型列表为空");
        return out;
    }

    private static boolean hasImageModality(JSONArray arr) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if ("image".equalsIgnoreCase(arr.optString(i))) return true;
        }
        return false;
    }

    /** {@code supported_reasoning_levels[].effort} → 档位集合（保持下发顺序）。 */
    private static Set<String> readEfforts(JSONObject m) {
        Set<String> s = new LinkedHashSet<>();
        JSONArray arr = m.optJSONArray("supported_reasoning_levels");
        if (arr == null) return s;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject lv = arr.optJSONObject(i);
            String eff = lv == null ? "" : lv.optString("effort", "");
            if (eff.isEmpty()) eff = arr.optString(i, "");
            if (!eff.isEmpty()) s.add(eff);
        }
        return s;
    }

    @Override
    public List<ModelEntry> fallbackModels() {
        List<ModelEntry> l = new ArrayList<>();
        l.add(fb("xopglm52", "GLM-5.2", 1000000, 128000, "none,high,max"));
        l.add(fb("astronclaw-auto", "Auto", 1000000, 384000, "none,high"));
        l.add(fb("xopdsv4flash0731in", "DeepSeek-V4-Flash", 1000000, 384000, "none,high,max"));
        l.add(fb("xopdeepseekv4pro0813", "DeepSeek-V4-Pro", 1000000, 384000, "none,high,max"));
        l.add(fb("spark-x2.5", "Spark-X2.5", 262144, 256000, "none,high"));
        l.add(fb("spark-x2.6-preview", "Spark-X2.6-Preview", 262144, 256000, "none,high"));
        return l;
    }

    /**
     * 兜底条目。⚠️ GLM-5.2 的输出上限是 <b>128K</b>，与同族的 384K 不同（照同族抄会错）。
     */
    private static ModelEntry fb(String id, String name, int ctx, int maxOut, String efforts) {
        // 用带展示名的构造器（ModelEntry.fallback 会把 displayName 设成 id，
        // 兜底表在 UI/模型页里就会显示成 slug，不好认）。
        ModelEntry e = new ModelEntry(ID, id, name);
        e.contextWindow = ctx;
        e.maxOutput = maxOut;
        e.vision = false;
        e.reasoning = !efforts.isEmpty();
        e.live = false;
        return e;
    }

    // ---------------- chat ----------------

    @Override
    public PreparedChat prepareChat(JSONObject body) throws IOException {
        Map<String, String> h = inferHeaders();
        h.put("Accept", "application/json, text/event-stream");
        h.put("Content-Type", "application/json");

        JSONObject b;
        String model;
        boolean hadImage;
        int stripped = 0;
        try {
            b = new JSONObject(body.toString());
            model = bareSlug(b.optString("model", ""));
            b.put("model", model);
            b.put("stream", true);
            // 两个参考实现都带：流末尾多一帧 usage，官方内核按此记账。
            b.put("stream_options", new JSONObject().put("include_usage", true));
            applyEffort(b, model);
            hadImage = containsImageInput(b);
            if (hadImage && IMAGE_INCAPABLE.contains(model)) {
                // 已确认不吃图的模型：直接剥图 + 告知，省掉一次注定失败的往返。
                stripped = stripImageInput(b, "该模型已确认不接受图片输入");
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad request body: " + e.getMessage());
        }

        HttpURLConnection c = openChat(b, h);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String eb = readErr(c);
            c.disconnect();
            // 上游拒图（10005 / 10042 / 10045）时不把语焉不详的 400 丢给客户端：
            // 剥掉图片二进制，把「这次带了图、但当前模型读不到」写进上下文后重发一次（vc174）。
            if (hadImage && stripped == 0 && isImageUpstreamError(eb)) {
                try {
                    stripped = stripImageInput(b, imageReason(eb));
                } catch (org.json.JSONException e) {
                    stripped = 0;
                }
            }
            if (stripped > 0) {
                IMAGE_INCAPABLE.add(model);
                asLog("上游拒图（" + excerpt(eb) + "）：已剥离 " + stripped
                        + " 张图片并重发，模型已被告知本次请求带图但读不到");
                c = openChat(b, h);
                code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    String eb2 = readErr(c);
                    c.disconnect();
                    throw new UpstreamHttpException(code, explainUpstreamError(eb2));
                }
            } else {
                throw new UpstreamHttpException(code, explainUpstreamError(eb));
            }
        } else if (hadImage && stripped == 0) {
            asLog("上游接受了带图请求（model=" + model + "）；若回答未体现图片内容，可能是被上游静默忽略");
        }
        PreparedChat pc = new PreparedChat(c.getInputStream(), null);
        pc.requestInputEstimate = com.aigetway.tattoo7.gateway.ContextGovernor
                .estimateMessagesTokens(b.optJSONArray("messages"));
        return pc;
    }

    private static HttpURLConnection openChat(JSONObject b, Map<String, String> h) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(CHAT_URL).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        // ⚠️ 长上下文模型的两次 SSE 字节之间实测可静默 >45s（官方内核自己用 300s 空闲超时）。
        c.setReadTimeout(300000);
        for (Map.Entry<String, String> e : h.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b.toString().getBytes(StandardCharsets.UTF_8));
        }
        return c;
    }

    private static String readErr(HttpURLConnection c) {
        try {
            java.io.InputStream es = c.getErrorStream();
            if (es == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = es.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }


    /**
     * 图片输入处理（vc174，替换 vc173 的硬拦截）。
     *
     * <p>背景：AStudio 分组多数模型的 {@code input_modalities} 只有 text，带图请求会被上游回
     * 10005 invalid request（形态不对回 10042，尺寸越界回 10045）。但「整个分组都不吃图」是过强
     * 的结论 —— 实测里同一个带图请求打在 {@code xopdeepseekv4pro0813} 上是过的，所以网关不再一刀切
     * 拒绝，改成三步：
     * <ol>
     *   <li>先原样透传（也许这个模型真能读图）；</li>
     *   <li>上游明确拒图时才剥掉图片二进制，把「本条消息带了 N 张图、但当前模型读不到」作为同一条
     *       消息里的文本说明补进去，重发一次；</li>
     *   <li>记下这个模型（进程内），下次直接走剥图路径，不再白撞一次 400。</li>
     * </ol>
     * 这样模型至少知道「用户发了图、我看不到」，可以自己派支持视觉的子代理或走 OCR，
     * 而不是收到一句语焉不详的 10005。
     */
    private static final Set<String> IMAGE_INCAPABLE = ConcurrentHashMap.newKeySet();

    /** message / body 里可能出现图片的字段名（对齐上游识别口径）。 */
    private static final String[] IMAGE_FIELDS =
            {"images", "attachments", "image_url", "image_base64", "image_data"};

    /** 请求体里是否有图片：content 数组里的 image 块，或 message / body 级的图片字段。 */
    private static boolean containsImageInput(JSONObject b) {
        for (String k : IMAGE_FIELDS) if (b.has(k)) return true;
        JSONArray msgs = b.optJSONArray("messages");
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            for (String k : IMAGE_FIELDS) if (m.has(k)) return true;
            Object c = m.opt("content");
            if (!(c instanceof JSONArray)) continue;
            JSONArray parts = (JSONArray) c;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject it = parts.optJSONObject(j);
                if (it != null && isImagePart(it)) return true;
            }
        }
        return false;
    }

    private static boolean isImagePart(JSONObject it) {
        String t = it.optString("type", "").toLowerCase(Locale.US);
        return t.contains("image") || it.has("image_url") || it.has("image_base64")
                || it.has("image_data") || it.has("image");
    }

    /** 只保留可复用的图片地址；base64 不写进上下文。 */
    private static String imageRef(JSONObject it) {
        Object u = it.opt("image_url");
        if (u instanceof JSONObject) {
            String s = ((JSONObject) u).optString("url", "");
            if (!s.isEmpty()) return s;
        }
        if (u instanceof String && !((String) u).isEmpty()) return (String) u;
        String s = it.optString("url", "");
        if (!s.isEmpty()) return s;
        return "base64 内联图片（无外部地址）";
    }

    private static int countImages(Object v) {
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof JSONArray) return ((JSONArray) v).length();
        return 1;
    }

    private static void collectRefs(Object v, List<String> out) {
        if (out.size() >= 4) return;
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length() && out.size() < 4; i++) collectRefs(a.opt(i), out);
            return;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String s = o.optString("url", "");
            if (s.isEmpty()) s = o.optString("image_url", "");
            out.add(s.isEmpty() ? "base64 内联图片（无外部地址）" : excerpt(s));
            return;
        }
        if (v instanceof String) {
            String s = (String) v;
            out.add((s.startsWith("data:") || s.length() > 512)
                    ? "base64 内联图片（无外部地址）" : excerpt(s));
        }
    }

    /**
     * 从请求体里剥掉图片，并在原消息末尾补一段说明。返回剥掉的图片张数。
     * 只动图片本身，其余字段与 part 顺序原样保留。
     */
    private static int stripImageInput(JSONObject b, String reason) throws org.json.JSONException {
        int total = 0;
        JSONArray msgs = b.optJSONArray("messages");
        for (int i = 0; msgs != null && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            int here = 0;
            List<String> refs = new ArrayList<>();
            Object c = m.opt("content");
            if (c instanceof JSONArray) {
                JSONArray parts = (JSONArray) c;
                JSONArray kept = new JSONArray();
                for (int j = 0; j < parts.length(); j++) {
                    Object raw = parts.opt(j);
                    JSONObject it = parts.optJSONObject(j);
                    if (it != null && isImagePart(it)) {
                        here++;
                        if (refs.size() < 4) refs.add(imageRef(it));
                        continue;
                    }
                    kept.put(raw);
                }
                if (here > 0) m.put("content", kept);
            }
            for (String k : IMAGE_FIELDS) {
                if (!m.has(k)) continue;
                Object v = m.opt(k);
                here += countImages(v);
                collectRefs(v, refs);
                m.remove(k);
            }
            if (here > 0) {
                total += here;
                appendNotice(m, imageNotice(here, reason, refs));
            }
        }
        int top = 0;
        List<String> topRefs = new ArrayList<>();
        for (String k : IMAGE_FIELDS) {
            if (!b.has(k)) continue;
            Object v = b.opt(k);
            top += countImages(v);
            collectRefs(v, topRefs);
            b.remove(k);
        }
        if (top > 0) {
            total += top;
            JSONObject lastUser = lastUserMessage(b);
            if (lastUser != null) appendNotice(lastUser, imageNotice(top, reason, topRefs));
        }
        return total;
    }

    private static JSONObject lastUserMessage(JSONObject b) {
        JSONArray msgs = b.optJSONArray("messages");
        for (int i = msgs == null ? -1 : msgs.length() - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null && "user".equalsIgnoreCase(m.optString("role", ""))) return m;
        }
        return null;
    }

    private static void appendNotice(JSONObject m, String notice) throws org.json.JSONException {
        Object c = m.opt("content");
        if (c instanceof JSONArray) {
            ((JSONArray) c).put(new JSONObject().put("type", "text").put("text", notice));
            return;
        }
        if (c instanceof String) {
            String s = (String) c;
            m.put("content", s.isEmpty() ? notice : s + "\n\n" + notice);
            return;
        }
        m.put("content", notice);
    }

    private static String imageNotice(int n, String reason, List<String> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统说明] 本条消息原本带 ").append(n).append(" 张图片；")
          .append("当前分组（AStudio / 讯飞）的模型没有视觉输入能力，网关已把图片从请求里移除（")
          .append(reason).append("），因此你看不到图片内容。\n")
          .append("不要猜测或编造图片内容。需要读图时：① 派一个支持视觉的子代理模型")
          .append("（例如 OpenAI / Anthropic 等多模态分组）去读这些图片；② 或用 OCR / 文件工具解析");
        if (refs.isEmpty()) {
            sb.append("（本次没有留下可访问的图片地址，只能请用户改用视觉模型或补充文字描述）");
        } else {
            sb.append("下面的引用：");
            for (int i = 0; i < refs.size(); i++) sb.append(i == 0 ? "" : "；").append(refs.get(i));
        }
        sb.append("；③ 两条路都走不通就直接告诉用户当前模型看不到图。");
        return sb.toString();
    }

    private static boolean isImageUpstreamError(String raw) {
        return raw != null && (raw.contains("10005") || raw.contains("10042") || raw.contains("10045"));
    }

    private static String imageReason(String raw) {
        if (raw == null || raw.isEmpty()) return "上游拒绝了带图请求";
        if (raw.contains("10045")) return "上游报图片尺寸越界 10045";
        if (raw.contains("10042")) return "上游报 10042 image content is empty";
        if (raw.contains("10005")) return "上游回 10005 invalid request";
        return "上游拒绝了带图请求";
    }

    /**
     * 上游错误体 → 可读中文。只翻译已实测的图片类错误码，其它原样返回：
     * Router 与凭证刷新逻辑会按报文内容判鉴权 \u002f 额度 \u002f 会话失效，不能随意改写。
     */
    private static String explainUpstreamError(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.contains("10045")) {
            return "AStudio 上游：图片尺寸越界（10045，要求 50x50~6000x6000）。原文：" + excerpt(raw);
        }
        if (raw.contains("10042")) {
            return "AStudio 上游：没取到图片内容（10042 image content is empty）。原文：" + excerpt(raw);
        }
        if (raw.contains("10005")) {
            return "AStudio 上游拒绝该请求（10005 invalid request）：该分组模型不接受图片等非文本输入。原文："
                    + excerpt(raw);
        }
        return raw;
    }

    /**
     * 思考档位：**集合外一律丢弃，不做就近映射**（档位关联计费，替用户决定语义是错的）；
     * 上游对非法值不报错、按最大档跑，所以这一步不拦就等于悄悄多收钱。
     */
    private void applyEffort(JSONObject b, String model) throws org.json.JSONException {
        String v = b.optString("reasoning_effort", "");
        if (v.isEmpty()) v = b.optString("reasoningEffort", "");
        if (v.isEmpty()) return;
        Set<String> allowed = EFFORTS.get(model.toLowerCase(Locale.US));
        if (allowed == null) {
            // 目录还没拉到（首启 / 拉取失败）：用本地兜底表；未知模型则丢弃（fail-closed）。
            allowed = fallbackEfforts(model);
        }
        b.remove("reasoningEffort");
        if (allowed.contains(v)) {
            b.put("reasoning_effort", v);
        } else {
            b.remove("reasoning_effort");
            asLog("丢弃非法思考档位 reasoning_effort=" + v + "（模型 " + model
                    + " 的档位集合 " + allowed + "）");
        }
    }

    private static Set<String> fallbackEfforts(String model) {
        String m = model.toLowerCase(Locale.US);
        if (m.equals("xopglm52") || m.equals("xopdsv4flash0731in") || m.equals("xopdeepseekv4pro0813")) {
            return new LinkedHashSet<>(Arrays.asList("none", "high", "max"));
        }
        if (m.equals("astronclaw-auto") || m.equals("spark-x2.5") || m.equals("spark-x2.6-preview")) {
            return new LinkedHashSet<>(Arrays.asList("none", "high"));
        }
        return new LinkedHashSet<>();
    }

    private static void asLog(String msg) {
        try {
            com.aigetway.tattoo7.core.App.get().logSink().log("ASTUDIO", msg);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 账号 / 积分 / 签到 ----------------

    @Override
    public boolean supportsCheckin() {
        return true;
    }

    @Override
    public boolean supportsOnboardingTasks() {
        return true;
    }

    @Override
    public AccountSnapshot account() {
        AccountSnapshot s = new AccountSnapshot(ID, displayName());
        s.supportsCheckin = true;
        JSONObject c = cred();
        if (c == null || c.optString("ssoSessionId", "").isEmpty()) {
            s.credits = "未登录";
            return s;
        }
        s.loggedIn = true;
        s.accountName = c.optString("nickname",
                c.optString("mobile", c.optString("uid", "")));
        try {
            refreshCredits(s);
        } catch (Exception e) {
            s.error = e.getMessage();
        }
        // 待领弹窗提示（查询失败静默，不影响卡片其余信息）。
        if (s.error == null || s.error.isEmpty()) {
            try {
                JSONArray pops = pendingPopups(c);
                boolean daily = findPopup(pops, POPUP_DAILY) != null;
                boolean newUser = findPopup(pops, POPUP_NEW_USER) != null;
                if (daily || newUser) {
                    s.checkinHint = daily ? "今日可领" : "今日已领";
                    if (newUser) s.onboardingHint = "新人礼待领";
                }
            } catch (Exception ignored) {
                // 保持空串
            }
        }
        return s;
    }

    /**
     * 查积分。口径取自实测：{@code totalBalance} 是上游**权威**的主池可用合计
     * （不是逐包求和；{@code totalAmount - totalBalance} 才是已消耗）。
     * 星火积分是**独立池**，不参与主池合计 ⇒ 只在文案里如实标注，不并进数字。
     */
    public void refreshCredits(AccountSnapshot s) throws IOException {
        JSONObject c = cred();
        Http.Resp r = Http.get(AUTH_BASE + "/points/balance", authHeaders(c), 15000);
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            throw new IOException("积分查询失败：code=" + code + " " + errText(j)
                    + (sessionDead(code, errText(j)) ? "（请重新登录）" : ""));
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return;
        if (d.opt("totalBalance") == null) {
            s.error = "响应未包含余额字段（totalBalance）";
            return;
        }
        double total = d.optDouble("totalBalance", 0);
        s.creditsTotal = total;
        s.permanentCredits = trim(total);
        double spark = d.optDouble("sparkTotalBalance", Double.NaN);
        String text = trim(total);
        if (!Double.isNaN(spark)) text += "（星火独立池 " + trim(spark) + "）";
        s.credits = text;
    }

    /** 待领取弹窗（DAILY_REWARD_DIALOG / NEW_USER_DIALOG）。 */
    static JSONArray pendingPopups(JSONObject cred) throws IOException {
        Http.Resp r = Http.get(AUTH_BASE + "/client-popups/pending", authHeaders(cred), 15000);
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            throw new IOException("弹窗查询失败：code=" + code + " " + errText(j)
                    + (sessionDead(code, errText(j)) ? "（请重新登录）" : ""));
        }
        JSONArray arr = j.optJSONArray("data");
        return arr == null ? new JSONArray() : arr;
    }

    /**
     * 找指定类型的待领弹窗。⚠️ 实测字段是 {@code componentType}（AStudio2API 同款），
     * DSH 笔记里的 {@code type} 是它的别名形态，两个都认。
     */
    private static JSONObject findPopup(JSONArray arr, String type) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("componentType", o.optString("type", ""));
            if (type.equals(t)) return o;
        }
        return null;
    }

    /**
     * 提交领取（popupId + instanceKey）。返回领取响应（data 优先），供上层取本次入账积分。
     * 实测成功响应 {@code code:0, flag:true}，积分在 {@code data.points}（或弹窗 payload）。
     */
    private static JSONObject completePopup(JSONObject cred, JSONObject popup) throws IOException {
        JSONObject body = new JSONObject();
        try {
            Object id = popup.opt("popupId");
            body.put("popupId", id instanceof Number ? id : Long.parseLong(String.valueOf(id)));
            body.put("instanceKey", popup.optString("instanceKey", ""));
        } catch (org.json.JSONException e) {
            throw new IOException("领取请求构造失败：" + e.getMessage());
        }
        Http.Resp r = Http.postJson(AUTH_BASE + "/client-popups/complete", authHeaders(cred),
                body.toString(), 15000);
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            throw new IOException("领取失败：code=" + code + " " + errText(j));
        }
        Object data = j.opt("data");
        return data instanceof JSONObject ? (JSONObject) data : j;
    }


    /**
     * 每日签到。
     *
     * <p>⚠️ 真正发奖的动作是 {@code POST tenant-app/v2/init-app}（每日首次调用入账当日奖励，
     * 重复调用是幂等的 no-op）；{@code client-popups/complete} 只是**关闭到账横幅**，不发放积分。
     * 只 complete 弹窗会出现「显示成功但积分不涨」。流程：余额快照 → init-app → 关横幅 → 余额对账。
     */
    @Override
    public String checkin() throws IOException {
        JSONObject c = cred();
        if (c == null) throw new IOException("AStudio 未登录");
        double before = balanceTotal(c);
        StringBuilder msg = new StringBuilder();
        // ① 每日登录（发奖动作，幂等）
        try {
            initApp(c);
            msg.append("每日登录已完成");
        } catch (Exception e) {
            msg.append("每日登录失败：").append(e.getMessage());
        }
        // ② 关闭到账横幅（含每日奖励 / 新人礼 / 客户端下载奖励）
        try {
            JSONArray arr = pendingPopups(c);
            int closed = 0;
            for (String type : new String[]{POPUP_DAILY, POPUP_NEW_USER, POPUP_DOWNLOAD}) {
                JSONObject popup = findPopup(arr, type);
                if (popup == null) continue;
                try {
                    completePopup(c, popup);
                    closed++;
                } catch (Exception e) {
                    msg.append("；关闭 ").append(type).append(" 失败：").append(e.getMessage());
                }
            }
            msg.append(closed > 0 ? "，已确认 " + closed + " 项奖励" : "，无待确认奖励横幅");
        } catch (Exception e) {
            msg.append("；弹窗查询失败：").append(e.getMessage());
        }
        // ③ 余额对账（上游不回「本次发了多少」，只能前后差值如实报）
        try {
            double after = balanceTotal(c);
            double delta = after - before;
            msg.append(delta > 0 ? "，本次入账 +" + trim(delta) + " 积分"
                    : "，积分无变化（可能今日已领）");
            msg.append("（当前 ").append(trim(after)).append("）");
        } catch (Exception e) {
            msg.append("；余额对账失败：").append(e.getMessage());
        }
        asLog("签到结果: " + msg);
        return msg.toString();
    }

    /** 主池 + 星火池合计（签到对账口径，与 AStudio2API 的 PointsDelta 同口径）。 */
    private double balanceTotal(JSONObject cred) throws IOException {
        Http.Resp r = Http.get(AUTH_BASE + "/points/balance", authHeaders(cred), 15000);
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            throw new IOException("code=" + code + " " + errText(j));
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return 0;
        return d.optDouble("totalBalance", 0) + d.optDouble("sparkTotalBalance", 0);
    }

    @Override
    public String claimOnboarding() throws IOException {
        JSONObject c = cred();
        if (c == null) throw new IOException("AStudio 未登录");
        double before = balanceTotal(c);
        try {
            initApp(c);
        } catch (Exception e) {
            asLog("claimOnboarding init-app 失败：" + e.getMessage());
        }
        JSONArray arr = pendingPopups(c);
        JSONObject newUser = findPopup(arr, POPUP_NEW_USER);
        if (newUser == null) {
            return "新人礼已领取或无可领礼包";
        }
        completePopup(c, newUser);
        double after = balanceTotal(c);
        String msg = "新人礼已领取（" + (after > before ? "+" + trim(after - before) + " 积分" : "积分无变化，入账可能延迟")
                + "）";
        asLog(msg);
        return msg;
    }

    /** {@code POST tenant-app/v2/init-app}：每日登录发奖动作（幂等），也是新用户开户初始化。 */
    private static void initApp(JSONObject cred) throws IOException {
        Http.post(AUTH_BASE + "/tenant-app/v2/init-app", authHeaders(cred), 20000);
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.2f", v);
    }

    // ---------------- 凭据轮换（Bearer 失效时用会话 Cookie 重取） ----------------

    private volatile String lastRefreshError = "";

    @Override
    public String lastRefreshError() {
        String e = lastRefreshError;
        return e == null ? "" : e;
    }

    /**
     * 权威刷新路径：{@code GET /bot/models/configs}（Cookie 鉴权）→ 取活体 {@code api_key}。
     * 实测本家 token **幂等不轮换**，所以「configs 拉取成功」即视为刷新成功（凭据重验通过），
     * 值不变不算失败——账号页的「刷新凭证」按钮按此口径展示，不再误报「无法刷新凭证」。
     */
    @Override
    public boolean refreshFingerprint() throws IOException {
        JSONObject c = cred();
        if (c == null) throw new IOException("AStudio 未登录");
        String fresh = fetchModelBearerToken(c);
        if (fresh.isEmpty()) throw new IOException("AStudio 凭据刷新失败（未取到 api_key）");
        if (!fresh.equals(c.optString("modelBearerToken", ""))) {
            try {
                c.put("modelBearerToken", fresh);
            } catch (org.json.JSONException e) {
                throw new IOException(e.getMessage());
            }
            saveCred(c);
            asLog("凭据已更新（modelBearerToken 已变化）");
        }
        return true;
    }

    /**
     * 账号页「刷新凭证」按钮入口。AStudio 的 token 幂等，这里按指定账号（上游 uid 或池内 id）
     * 逐个重取 configs；找不到账号时记录原因并返回 false。
     */
    @Override
    public boolean refreshFingerprintForUid(String uidOrId) {
        try {
            return refreshAccount(resolveUidOrId(uidOrId));
        } catch (IOException e) {
            lastRefreshError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            return false;
        }
    }

    /** 入参可能是池内 accountId、上游 uid 或空（空 = 当前账号）。 */
    private String resolveUidOrId(String uidOrId) {
        if (uidOrId == null || uidOrId.isEmpty()) return "";
        for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
            if (uidOrId.equals(a.uid) || uidOrId.equals(a.id)) {
                return (a.uid == null || a.uid.isEmpty()) ? a.id : a.uid;
            }
        }
        return uidOrId; // 找不到就原样传下去，让刷新路径报「未登录」
    }

    private boolean refreshAccount(String uidOrId) throws IOException {
        String target = uidOrId == null ? "" : uidOrId;
        com.aigetway.tattoo7.account.AccountPool.Account hit = null;
        for (com.aigetway.tattoo7.account.AccountPool.Account a : pool.all()) {
            if (target.isEmpty() ? a.id.equals(pool.activeId())
                    : target.equals(a.uid) || target.equals(a.id)) {
                hit = a;
                break;
            }
        }
        if (hit == null) {
            if (!target.isEmpty()) {
                lastRefreshError = "账号池里找不到该账号";
                return false;
            }
            throw new IOException("AStudio 未登录");
        }
        JSONObject c;
        try {
            c = new JSONObject(hit.cred);
        } catch (Exception e) {
            lastRefreshError = "账号凭据不是合法 JSON";
            return false;
        }
        String fresh = fetchModelBearerToken(c);
        if (fresh.isEmpty()) {
            lastRefreshError = "凭据刷新失败（未取到 api_key，会话可能已过期）";
            return false;
        }
        if (!fresh.equals(c.optString("modelBearerToken", ""))) {
            try {
                c.put("modelBearerToken", fresh);
            } catch (org.json.JSONException e) {
                throw new IOException(e.getMessage());
            }
            pool.updateCred(hit.id, c.toString());
        }
        return true;
    }

    /**
     * {@code /bot/models/configs} 的解析规则（实测 fixture 逐字）：
     * {@code data} 是**数组**，每行有 {@code api_key} / {@code is_current} / {@code is_default}。
     * 取值阶梯：{@code is_current} → {@code is_default} → {@code data[0]} → 全表扫（空 api_key 继续下探）。
     * ⚠️ 实测账号 9 行里**没有一行 is_current** ⇒ 只判 is_current 会永远取不到凭据。
     */
    static String fetchModelBearerToken(JSONObject cred) throws IOException {
        Map<String, String> h = authHeaders(cred);
        asLog("configs 请求 Cookie 形状: " + cookieShape(h.get("Cookie")));
        Http.Resp r = Http.get(AUTH_BASE + "/bot/models/configs", h, 20000);
        JSONObject j = Http.json(r);
        int code = codeOf(j);
        if (code != 0) {
            asLog("configs 失败: http=" + r.code + " body=" + excerpt(j.toString()));
            throw new IOException("取模型凭据失败：code=" + code + " " + errText(j)
                    + (sessionDead(code, errText(j)) ? "（请重新登录）" : ""));
        }
        String key = pickApiKey(j.opt("data"));
        if (key.isEmpty()) key = pickApiKey(j);
        if (key.isEmpty()) asLog("configs 成功但未解析到 api_key: " + excerpt(j.toString()));
        return key;
    }

    /** 诊断用：Cookie 各键值只留长度与首 6 位，避免明文会话落日志。 */
    private static String cookieShape(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "<empty>";
        StringBuilder sb = new StringBuilder();
        for (String kv : cookie.split(";\\s*")) {
            int eq = kv.indexOf('=');
            if (eq < 0) { sb.append(kv).append("(novalue) "); continue; }
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            sb.append(k).append("=")
              .append(v.length() <= 6 ? "*".repeat(v.length()) : v.substring(0, 6) + "…" + v.length())
              .append(" ");
        }
        return sb.toString().trim();
    }

    /** 按 is_current → is_default → first → 全表 的阶梯取一行 live api_key。 */
    private static String pickApiKey(Object node) {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            String byCurrent = "";
            String byDefault = "";
            String first = "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("api_key", o.optString("apiKey", "")).trim();
                if (k.isEmpty()) continue;
                if (first.isEmpty()) first = k;
                if (byDefault.isEmpty() && o.optBoolean("is_default", false)) byDefault = k;
                if (byCurrent.isEmpty() && o.optBoolean("is_current", false)) byCurrent = k;
            }
            if (!byCurrent.isEmpty()) return byCurrent;
            if (!byDefault.isEmpty()) return byDefault;
            if (!first.isEmpty()) return first;
            return pickApiKeyDeep(node);
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : new String[]{"api_key", "apiKey", "access_token", "accessToken"}) {
                String v = o.optString(k, "").trim();
                if (!v.isEmpty()) return v;
            }
            return pickApiKeyDeep(node);
        }
        return "";
    }

    private static String pickApiKeyDeep(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String v = pickApiKey(o.opt(it.next()));
                if (!v.isEmpty()) return v;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String v = pickApiKey(a.opt(i));
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    // ---------------- 登录（内置浏览器页的三条通路都走这里） ----------------

    /** 极验初始化配置：{@code GET /chat/gee-captcha}。 */
    public static JSONObject geetestConfig() throws IOException {
        Http.Resp r = Http.get(AUTH_BASE + "/chat/gee-captcha?t=" + System.currentTimeMillis(),
                basicHeaders(), 15000);
        JSONObject j = Http.json(r);
        if (codeOf(j) != 0) throw new IOException("获取验证码配置失败：" + errText(j));
        Object data = j.opt("data");
        JSONObject d = data instanceof JSONObject ? (JSONObject) data : j;
        if (d.optString("gt", "").isEmpty() || d.optString("challenge", "").isEmpty()) {
            throw new IOException("验证码配置缺失 gt / challenge");
        }
        return d;
    }

    private static Map<String, String> basicHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("clientType", CLIENT_TYPE);
        h.put("studioVersion", STUDIO_VERSION);
        h.put("User-Agent", "AStudio/" + STUDIO_VERSION + " (Windows)");
        return h;
    }

    /** 发短信验证码：{@code POST /login/mobile/send-verify-code}（multipart，含极验三参）。 */
    public static void sendSmsCode(String mobile, String challenge, String validate, String seccode)
            throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mobile", mobile);
        f.put("countryCode", "86");
        f.put("geetest_challenge", challenge);
        f.put("geetest_validate", validate);
        f.put("geetest_seccode", base64(seccode));
        JSONObject j = postMultipart(AUTH_BASE + "/login/mobile/send-verify-code", f);
        if (codeOf(j) != 0) throw new IOException(errText(j).isEmpty()
                ? "短信验证码发送失败" : errText(j));
    }

    /** 短信验证码登录：{@code POST /login/phone-quick-login}。 */
    public static JSONObject loginWithSms(String mobile, String code) throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mobile", mobile);
        f.put("verifyCode", code);
        f.put("countryCode", "86");
        JSONObject j = postMultipart(AUTH_BASE + "/login/phone-quick-login", f);
        if (codeOf(j) != 0) throw new IOException(errText(j).isEmpty()
                ? "短信登录失败" : errText(j));
        return identity(j.opt("data") instanceof JSONObject ? (JSONObject) j.opt("data") : j, "sms", mobile);
    }

    /** 账号密码登录：{@code POST /login/check-account}（multipart，含极验三参）。 */
    public static JSONObject loginWithPassword(String account, String pwd,
                                        String challenge, String validate, String seccode)
            throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("accountName", account);
        f.put("accountPwd", pwd);
        f.put("geetest_challenge", challenge);
        f.put("geetest_validate", validate);
        f.put("geetest_seccode", base64(seccode));
        JSONObject j = postMultipart(AUTH_BASE + "/login/check-account", f);
        if (codeOf(j) != 0) throw new IOException(errText(j).isEmpty()
                ? "密码登录失败" : errText(j));
        return identity(j.opt("data") instanceof JSONObject ? (JSONObject) j.opt("data") : j,
                "password", account);
    }

    /** 从登录响应里抽会话标识（uid / ssoSessionId / token / accountId）。 */
    private static JSONObject identity(JSONObject d, String method, String fallbackName)
            throws IOException {
        String uid = d.optString("uid", d.optString("account_id", ""));
        String sid = d.optString("ssoSessionId", d.optString("sso_session_id", d.optString("token", "")));
        // 诊断：登录响应真实字段形状（值脱敏，只留键名与长度）——排查 80000 靠它拿实证。
        StringBuilder ks = new StringBuilder();
        java.util.Iterator<String> kit = d.keys();
        while (kit.hasNext()) {
            String k = kit.next();
            Object v = d.opt(k);
            ks.append(k).append(v instanceof String ? "(" + ((String) v).length() + ")" : "")
              .append(" ");
        }
        asLog("登录响应[" + method + "]字段: " + ks.toString().trim()
                + " sid.len=" + sid.length() + " uid.len=" + uid.length());
        if (uid.isEmpty() && sid.isEmpty()) {
            throw new IOException("登录响应缺少会话标识（uid / ssoSessionId）");
        }
        JSONObject o = new JSONObject();
        try {
            o.put("uid", uid.isEmpty() ? sid : uid);
            o.put("ssoSessionId", sid.isEmpty() ? uid : sid);
            String token = d.optString("token", "");
            o.put("token", token.isEmpty() ? (sid.isEmpty() ? uid : sid) : token);
            String accountId = d.optString("accountId", d.optString("account_id", ""));
            o.put("accountId", accountId.isEmpty() ? (uid.isEmpty() ? sid : uid) : accountId);
            o.put("mobile", d.optString("mobile", "sms".equals(method) ? fallbackName : ""));
            o.put("nickname", d.optString("nickname", d.optString("username", "")));
            o.put("loginMethod", method);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        return o;
    }

    /**
     * 登录收尾：init-app（新用户自动注册开户）→ userInfo（昵称）→ bot/models/configs（活体 Bearer）。
     * 装好的凭据直接入账号池。
     */
    public static JSONObject completeLogin(JSONObject identity) throws IOException {
        JSONObject cred = new JSONObject();
        try {
            for (java.util.Iterator<String> it = identity.keys(); it.hasNext(); ) {
                String k = it.next();
                cred.put(k, identity.opt(k));
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        // ① init-app：无条件执行租户开户初始化 —— 未注册手机号靠这一步自动开户。
        //    参考实现是**空 POST**（只带 Accept + Cookie），任何异常都吞掉（非阻塞）。
        try {
            Http.Resp ir = Http.post(AUTH_BASE + "/tenant-app/v2/init-app", authHeaders(cred), 20000);
            JSONObject ij = Http.json(ir);
            int ic = codeOf(ij);
            if (ic != 0) asLog("init-app code=" + ic + " http=" + ir.code + " body=" + excerpt(ij.toString()));
        } catch (Exception e) {
            asLog("init-app 非阻塞失败：" + e.getMessage());
        }
        // ② userInfo：**所有登录方式**都要走（不只微信扫码）——登录响应里的 uid 可能是
        //    占位 "0"，真实 uid / 手机号 / 昵称都在 userInfo 里，而 maas-api 推理端点认
        //    uid 请求头。⚠️ 字段嵌在 data.userInfo.* 下（AStudio2API 实测口径）。
        String uid = normalizeUid(cred.optString("uid", ""));
        String mobile = cred.optString("mobile", "");
        if (uid.isEmpty() || mobile.isEmpty()) {
            try {
                Http.Resp r = Http.get(AUTH_BASE + "/userInfo", authHeaders(cred), 15000);
                JSONObject j = Http.json(r);
                int uc = codeOf(j);
                if (uc != 0) asLog("userInfo code=" + uc + " http=" + r.code + " body=" + excerpt(j.toString()));
                Object data = j.opt("data");
                JSONObject d = data instanceof JSONObject ? (JSONObject) data : null;
                JSONObject u = d == null ? null
                        : (d.opt("userInfo") instanceof JSONObject
                                ? d.optJSONObject("userInfo") : d);
                if (u != null) {
                    if (uid.isEmpty()) {
                        uid = normalizeUid(u.optString("uid", u.optString("userId", "")));
                        if (!uid.isEmpty()) cred.put("uid", uid);
                    }
                    String m2 = u.optString("mobile", u.optString("telephone", ""));
                    if (mobile.isEmpty() && !m2.isEmpty()) {
                        mobile = m2;
                        cred.put("mobile", mobile);
                    }
                    String nick = u.optString("nickname", u.optString("userName", ""));
                    if (!nick.isEmpty()) cred.put("nickname", nick);
                    String avatar = u.optString("avatar", "");
                    if (!avatar.isEmpty()) cred.put("avatar", avatar);
                }
            } catch (Exception e) {
                asLog("userInfo 降级：" + e.getMessage());
            }
        }
        if (uid.isEmpty()) {
            // 对齐 AStudio2API：解析不出 uid 就视为失败——没有 uid，推理端点的
            // uid 头和账号粒度统计都立不住。
            throw new IOException("登录成功但无法解析账号 uid（userInfo 未返回 uid）");
        }
        // ③ 活体模型凭据（推理与目录都要这一份）。
        String key = fetchModelBearerToken(cred);
        if (key.isEmpty()) throw new IOException("登录成功但未取到 modelBearerToken（/bot/models/configs）");
        try {
            cred.put("modelBearerToken", key);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        return cred;
    }

    // ---------------- 微信扫码（SSO） ----------------

    private static final String FD_ALPHABET =
            "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";

    /** 生成 8 位 fd（官方微信扫码唯一凭证）。 */
    public static String generateFd() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(FD_ALPHABET.charAt(r.nextInt(FD_ALPHABET.length())));
        return sb.toString();
    }

    /** 取二维码：{@code GET {SSO}/qrcode?fd=…} → 微信 ticket 图 URL。 */
    public static String wechatQr(String fd) throws IOException {
        Http.Resp r = Http.get(SSO_BASE + "/qrcode?fd=" + fd, ssoHeaders(), 15000);
        QrDiagnostics.record("qrcode http=" + r.code + " body=" + excerpt(r.body));
        JSONObject j = Http.json(r);
        if (codeOf(j) != 0) throw new IOException("获取二维码失败：" + errText(j));
        String url = j.optString("data", "");
        if (url.isEmpty()) {
            // 兜底：有的部署把 data 包成对象（url/qrUrl/ticket），或直接回一个
            // terminal/#/loginState?token=… 链接文本
            Object d = j.opt("data");
            if (d instanceof JSONObject) {
                JSONObject dj = (JSONObject) d;
                url = dj.optString("url", "");
                if (url.isEmpty()) url = dj.optString("qrUrl", "");
                if (url.isEmpty()) url = dj.optString("ticket", "");
            }
            if (url.isEmpty() && r.body != null && r.body.contains("loginState")) {
                url = firstUrlContaining(r.body, "loginState");
                QrDiagnostics.record("qrcode: data 为空，从正文兜底取 loginState 链接");
            }
        }
        if (url.isEmpty()) throw new IOException("获取二维码失败：未返回二维码 URL");
        return url;
    }

    private static Map<String, String> ssoHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("clientType", CLIENT_TYPE);
        h.put("studioVersion", STUDIO_VERSION);
        return h;
    }

    /**
     * 轮询扫码状态：{@code GET {SSO}/uids?fd=…}。
     * {@code code=-1} 未扫码；{@code code=0} 已确认（data 里带 uid + ssoSessionId）。
     */
    public static JSONObject pollWechat(String fd) throws IOException {
        Http.Resp r = Http.get(SSO_BASE + "/uids?fd=" + fd, ssoHeaders(), 15000);
        QrDiagnostics.record("poll http=" + r.code + " body=" + excerpt(r.body));
        JSONObject j;
        try {
            j = new JSONObject(r.body == null ? "" : r.body);
        } catch (Exception notJson) {
            // 网关/代理可能把轮询响应替换成 HTML 重定向（terminal/#/loginState?token=…）：
            // 此时按「已扫码」处理，直接拿 token 当 ssoSessionId 走 completeLogin（uid 由 userInfo 回填）
            String raw = r.body == null ? "" : r.body;
            String token = raw.contains("loginState") ? extractParam(raw, "token") : "";
            if (token.isEmpty()) {
                throw new IOException("扫码状态响应不是 JSON：" + excerpt(raw));
            }
            QrDiagnostics.record("poll: 响应非 JSON，走 loginState 兜底 token=" + token);
            JSONObject out = new JSONObject();
            try {
                out.put("status", "scanned");
                out.put("uid", "");
                out.put("ssoSessionId", token);
            } catch (org.json.JSONException e) {
                throw new IOException(e.getMessage());
            }
            return out;
        }
        int code = codeOf(j);
        JSONObject out = new JSONObject();
        try {
            if (code == -1) {
                out.put("status", "pending");
                out.put("message", errText(j).isEmpty() ? "未扫码" : errText(j));
                return out;
            }
            if (code != 0) {
                out.put("status", "error");
                out.put("message", "扫码状态查询失败：code=" + code + " " + errText(j));
                return out;
            }
            Object data = j.opt("data");
            JSONArray list = null;
            if (data instanceof JSONArray) {
                list = (JSONArray) data;
            } else if (data instanceof JSONObject) {
                JSONObject d = (JSONObject) data;
                list = d.optJSONArray("infoList");
                if (list == null && !d.optString("uid", "").isEmpty()) list = new JSONArray().put(d);
            }
            if (list == null || list.length() == 0) {
                out.put("status", "unbound");
                out.put("message", "已扫码但未关联账号");
                return out;
            }
            JSONObject first = list.optJSONObject(0);
            String uid = first == null ? "" : first.optString("uid", first.optString("accountId", ""));
            String sid = first == null ? "" : first.optString("ssoSessionId",
                    first.optString("sso_session_id", ""));
            if (uid.isEmpty() || sid.isEmpty()) {
                out.put("status", "unbound");
                out.put("message", "已扫码但响应缺少 uid / ssoSessionId");
                return out;
            }
            out.put("status", "scanned");
            out.put("uid", uid);
            out.put("ssoSessionId", sid);
            out.put("username", first.optString("username", ""));
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** 确认绑定：{@code POST {SSO}/bind?fd=…&accountId=…&ssoSessionId=…&isNew=false}。 */
    public static void bindWechat(String fd, String uid, String ssoSessionId) throws IOException {
        StringBuilder q = new StringBuilder();
        q.append("fd=").append(enc(fd));
        q.append("&accountId=").append(enc(uid));
        if (ssoSessionId != null && !ssoSessionId.isEmpty()) {
            q.append("&ssoSessionId=").append(enc(ssoSessionId));
        }
        q.append("&isNew=false");
        Http.Resp r = Http.postRaw(SSO_BASE + "/bind?" + q, ssoHeaders(), null, 15000);
        QrDiagnostics.record("bind http=" + r.code + " body=" + excerpt(r.body));
        JSONObject j = Http.json(r);
        if (codeOf(j) != 0) throw new IOException("绑定失败：" + errText(j));
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /** 诊断行用：压成单行并截断。 */
    private static String excerpt(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** 从文本里抠出 query 参数（遇到 & " ' 空格 # 截止）。 */
    private static String extractParam(String raw, String key) {
        if (raw == null) return "";
        int i = raw.indexOf(key + "=");
        if (i < 0) return "";
        i += key.length() + 1;
        StringBuilder sb = new StringBuilder();
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '&' || c == '"' || c == '\'' || c == ' ' || c == '#') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 从文本里找出第一段包含 keyword 的 URL（http 开头）。 */
    private static String firstUrlContaining(String raw, String keyword) {
        if (raw == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>]+").matcher(raw);
        while (m.find()) {
            String u = m.group();
            if (u.contains(keyword)) return u;
        }
        return "";
    }

    /** 扫码登录的完整收尾：identity → completeLogin → 入池。 */
    public static JSONObject completeWechatLogin(String uid, String ssoSessionId, String username)
            throws IOException {
        JSONObject id = new JSONObject();
        try {
            id.put("uid", uid);
            id.put("ssoSessionId", ssoSessionId);
            id.put("token", ssoSessionId);
            id.put("accountId", uid);
            id.put("nickname", username == null ? "" : username);
            id.put("loginMethod", "wechat");
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        return completeLogin(id);
    }

    // ---------------- 小工具 ----------------

    /** multipart/form-data（讯飞账号端点的三个登录接口都吃这个形状）。 */
    private static JSONObject postMultipart(String url, Map<String, String> fields)
            throws IOException {
        String boundary = "----astudio" + java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(e.getKey()).append("\"\r\n\r\n");
            sb.append(e.getValue() == null ? "" : e.getValue()).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        Map<String, String> h = basicHeaders();
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        Http.Resp r = Http.postRaw(url, h, sb.toString(), 20000);
        return Http.json(r);
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    /** 供 UI 显示的账号标签。 */
    public static String labelOf(JSONObject cred) {
        if (cred == null) return "AStudio";
        String n = cred.optString("nickname", "");
        if (!n.isEmpty()) return n;
        String m = cred.optString("mobile", "");
        if (m.length() >= 11) return m.substring(0, 3) + "****" + m.substring(m.length() - 4);
        if (!m.isEmpty()) return m;
        String uid = cred.optString("uid", "");
        return uid.isEmpty() ? "AStudio" : ("uid " + uid);
    }

    /** HashSet 版档位集合（避免调用方误改共享实例）。 */
    static Set<String> immutable(Set<String> s) {
        return new HashSet<>(s);
    }
}
