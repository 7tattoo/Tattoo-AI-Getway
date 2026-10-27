package com.aigetway.tattoo7.provider;

import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.core.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用模型可用性探测：对某个服务商的每个模型发一次**最小**对话请求，
 * 按响应判定可用/限流/不可用，供 UI「探测 / 禁用失效」使用。
 *
 * <p>为什么自建探测：早期版本另有一套探测逻辑，把 Zen 的 URL、请求头和
 * 「tools 指纹」硬编码在里面，只对一个上游有效。这里改为走各 provider 自己的
 * {@link ServiceProvider#prepareChat}，因此任意服务商都能探测。
 */
public class ModelProber {

    public static final int OK = 0;
    public static final int RATE = 1;      // 限流，动态可能恢复
    public static final int UNAUTHORIZED = 2; // 账号无权限（模型列出来了但用不了）
    public static final int EMPTY = 3;     // 空响应
    public static final int ERROR = 4;     // 网络/其它错误
    public static final int QUOTA = 5;     // 额度/权益不足（HTTP 402）

    public static class Result {
        public final int status;
        public final String reason;
        public final String modelId;

        public Result(int status, String reason) {
            this(status, reason, "");
        }

        public Result(int status, String reason, String modelId) {
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.modelId = modelId == null ? "" : modelId;
        }
    }

    /** 全局探测结果：providerId -> (modelId -> Result)。
     * Router.byModel 要读它来选路由，而探测是在 UI 层触发的，故放静态并落盘，
     * 重启后仍能按上次结果选路由。 */
    private static final Map<String, Map<String, Result>> GLOBAL = new LinkedHashMap<>();

    static {
        try {
            android.content.SharedPreferences sp = com.aigetway.tattoo7.core.App.get()
                    .getSharedPreferences("model_probes", android.content.Context.MODE_PRIVATE);
            String json = sp.getString("data", "");
            if (!json.isEmpty()) {
                org.json.JSONObject root = new org.json.JSONObject(json);
                for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String pid = it.next();
                    org.json.JSONObject models = root.optJSONObject(pid);
                    if (models == null) continue;
                    Map<String, Result> m = new LinkedHashMap<>();
                    for (java.util.Iterator<String> it2 = models.keys(); it2.hasNext(); ) {
                        String mid = it2.next();
                        org.json.JSONObject r = models.optJSONObject(mid);
                        if (r != null) {
                            m.put(mid, new Result(r.optInt("status", ERROR),
                                    r.optString("reason", "")));
                        }
                    }
                    GLOBAL.put(pid, m);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persist() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            for (Map.Entry<String, Map<String, Result>> en : GLOBAL.entrySet()) {
                org.json.JSONObject models = new org.json.JSONObject();
                for (Map.Entry<String, Result> e2 : en.getValue().entrySet()) {
                    models.put(e2.getKey(), new org.json.JSONObject()
                            .put("status", e2.getValue().status)
                            .put("reason", e2.getValue().reason));
                }
                root.put(en.getKey(), models);
            }
            com.aigetway.tattoo7.core.App.get()
                    .getSharedPreferences("model_probes", android.content.Context.MODE_PRIVATE)
                    .edit().putString("data", root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 查某服务商某模型的历史探测结果；没探过返回 null。 */
    public static Result peek(String providerId, String modelId) {
        Map<String, Result> m = GLOBAL.get(providerId);
        return m == null ? null : m.get(modelId);
    }

    /**
     * 运行时标记「该服务商的这个模型无权限」。
     * 调用时收到 11102 就调它——**反馈驱动**比静态路由决策可靠：
     * 静态路由只能靠探测结果猜，而探测范围/时机都可能不覆盖全部候选。
     */
    /** 运行时标记「该服务商的这个模型可用」（换服务商重试成功后调用）。 */
    public static void markUsable(String providerId, String modelId) {
        synchronized (GLOBAL) {
            Map<String, Result> m = GLOBAL.get(providerId);
            if (m == null) {
                m = new LinkedHashMap<>();
                GLOBAL.put(providerId, m);
            }
            m.put(modelId, new Result(OK, ""));
            persist();
        }
    }

    public static void markUnauthorized(String providerId, String modelId) {
        synchronized (GLOBAL) {
            Map<String, Result> m = GLOBAL.get(providerId);
            if (m == null) {
                m = new LinkedHashMap<>();
                GLOBAL.put(providerId, m);
            }
            m.put(modelId, new Result(UNAUTHORIZED, "账号无权限"));
            persist();
        }
    }

    private final String providerId;
    private final Map<String, Result> results = new LinkedHashMap<>();
    private boolean probing;
    private String lastError = "";

    public ModelProber(String providerId) {
        this.providerId = providerId;
    }

    public String providerId() {
        return providerId;
    }

    public synchronized boolean isProbing() {
        return probing;
    }

    public synchronized int probedCount() {
        return results.size();
    }

    public synchronized int failedCount() {
        int n = 0;
        for (Result r : results.values()) if (r.status != OK) n++;
        return n;
    }

    public synchronized Result of(String modelId) {
        return results.get(modelId);
    }

    public synchronized String getProbeError() {
        return lastError;
    }

    /** 全部探测结果（含模型 id），供 UI 决定要不要自动禁用无权限模型。 */
    public synchronized List<Result> allResults() {
        List<Result> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Result> en : results.entrySet()) {
            out.add(new Result(en.getValue().status, en.getValue().reason, en.getKey()));
        }
        return out;
    }

    /** 探测失败的模型 id 集合。 */
    public synchronized List<String> failedModelIds() {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Result> en : results.entrySet()) {
            if (en.getValue().status != OK) out.add(en.getKey());
        }
        return out;
    }

    /**
     * 真正「该拉黑」的失效模型：{@link #QUOTA}（额度/权益不足）与 {@link #RATE}（限流）都**不算**。
     *
     * <p>为什么单列一个方法：额度不足与限流都是**账号级/时间级**状态，攒到积分、订阅、或过一会儿
     * 就会好；把它当模型失效一键拉黑，等额度恢复了模型还是被禁着（用户看到的是「模型不见了」，
     * 比报红更难排查）。{@link #failedModelIds()} 保持「OK 之外都算报红」的原语义给文案用。
     */
    public synchronized List<String> disableWorthyModelIds() {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Result> en : results.entrySet()) {
            int st = en.getValue().status;
            if (st != OK && st != QUOTA && st != RATE) out.add(en.getKey());
        }
        return out;
    }

    /** 因额度/权益不足（HTTP 402）失败的模型数 —— 用来把「额度问题」和「模型失效」分开说。 */
    public synchronized int quotaCount() {
        int n = 0;
        for (Result r : results.values()) if (r.status == QUOTA) n++;
        return n;
    }

    /** 开始探测（后台线程逐个跑）。 */
    public synchronized void start(List<ModelEntry> entries, Runnable onDone) {
        if (probing) return;
        probing = true;
        lastError = "";
        results.clear();
        GLOBAL.put(providerId, new LinkedHashMap<>());
        Thread t = new Thread(() -> {
            for (ModelEntry e : entries) {
                Result r = probeOne(e.id);
                synchronized (ModelProber.this) {
                    results.put(e.id, r);
                }
                if (r.status != OK) {
                    lastError = r.reason;
                    // 失败原因同时落日志：界面上只放得下一行时，去「记录」页还能看到完整归因。
                    try {
                        App.get().logSink().log("PROBE", providerId + "/" + e.id + " → "
                                + statusName(r.status)
                                + (r.reason.isEmpty() ? "" : "：" + r.reason));
                    } catch (Throwable ignored) {
                    }
                }
            }
            synchronized (ModelProber.this) {
                probing = false;
                GLOBAL.put(providerId, new LinkedHashMap<>(results));
                persist();
            }
            if (onDone != null) onDone.run();
        }, "prober-" + providerId);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 发一个最小 chat 请求，走该 provider 自己的 prepareChat ——
     * 这样鉴权头、设备指纹、加密体全由 provider 自己处理，
     * 探测结果与真实调用一致。
     */
    private Result probeOne(String id) {
        ServiceProvider p = App.get().registry().byId(providerId);
        if (p == null) return new Result(ERROR, "找不到服务商 " + providerId);
        try {
            org.json.JSONArray msgs = new org.json.JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
            JSONObject body = new JSONObject()
                    .put("model", id)
                    .put("stream", true)
                    .put("max_tokens", 8)
                    .put("messages", msgs);

            PreparedChat pc = p.prepareChat(body);
            // 读到首个 data 事件即算通；流内业务错误帧按失败处理
            try (InputStream in = pc.in) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n < 60) {
                    n++;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if (payload.isEmpty()) continue;
                    if (payload.equals("[DONE]")) return new Result(OK, "");
                    // 流内错误帧（很多服务商把错误放在 HTTP 200 的 SSE 里）
                    if (payload.contains("\"error\"") || payload.contains("\"code\":")) {
                        int bizCode = extractCode(payload);
                        if (bizCode != 0) return classify(bizCode, payload);
                    }
                    return new Result(OK, "");
                }
                return new Result(EMPTY, "无内容返回");
            }
        } catch (UpstreamHttpException e) {
            return classify(e.code, e.friendly);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("11102") || m.contains("authorized")) {
                return new Result(UNAUTHORIZED, "账号无权限");
            }
            if (m.contains("429") || m.contains("限流") || m.contains("rate")) {
                return new Result(RATE, "限流");
            }
            lastError = m;
            return new Result(ERROR, m.length() > 80 ? m.substring(0, 80) : m);
        } catch (Throwable t) {
            return new Result(ERROR, String.valueOf(t.getMessage()));
        }
    }

    private static int extractCode(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("code")) return o.optInt("code", 0);
            if (o.has("error")) {
                Object e = o.opt("error");
                if (e instanceof JSONObject) return ((JSONObject) e).optInt("code", 0);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static Result classify(int httpCode, String body) {
        String b = body == null ? "" : body;
        if (b.contains("11102") || b.contains("only available for authorized")) {
            return new Result(UNAUTHORIZED, "账号无权限（11102）" + tail(b));
        }
        if (httpCode == 429 || b.contains("429")) {
            return new Result(RATE, "限流（HTTP 429）" + tail(b));
        }
        // 并发会话超限 / 排队处理：
        // 超限报 HTTP 400 + TM.00001041「并发会话数已达上限(3个)」（报文里没有
        // "429" 字样，friendly 文案是中文）。它**不是模型失效**——归到 ERROR 会让
        // 「禁用失效」把能用的模型全禁掉（用户实况：9/9 全红全被禁用）。
        // 对齐参考实现：排队/限流一律当 RATE（可恢复）。
        if (b.contains("TM.00001041") || b.contains("ModelArts.81111")
                || b.contains("排队") || b.contains("限流")
                || b.contains("并发会话数") || b.contains("queue_full")
                || b.contains("too many requests")) {
            return new Result(RATE, "排队/限流（并发会话数上限 3，稍后自动重试）" + tail(b));
        }
        if (b.contains("pricingUrl") || b.contains("code\":\"112")) {
            return new Result(QUOTA, "订阅权益不足（112）：该模型不含在当前账号权益内"
                    + "，先免费模型或去服务商开通订阅" + tail(b));
        }
        if (httpCode == 401 || httpCode == 403) {
            return new Result(UNAUTHORIZED, "鉴权失败（HTTP " + httpCode + "）" + tail(b));
        }
        if (httpCode == 404) return new Result(UNAUTHORIZED, "模型不存在（HTTP 404）" + tail(b));
        // 402：额度/权益不足。
        if (httpCode == 402) {
            String low = b.toLowerCase(java.util.Locale.ROOT);
            boolean creditish = low.contains("credit") || low.contains("quota")
                    || low.contains("balance") || low.contains("insufficient")
                    || b.contains("积分") || b.contains("额度") || b.contains("权益");
            return new Result(QUOTA, "额度/权益不足（HTTP 402）"
                    + (creditish ? "，疑似积分耗尽：去服务商官网/客户端确认积分与订阅" : "")
                    + tail(b));
        }
        return new Result(ERROR, "HTTP " + httpCode + tail(b));
    }

    /** 上游报文压成一行短尾巴（最长 160 字），附在原因后面，便于直接定位。 */
    private static String tail(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return "：" + s;
    }

    /** UI 用的状态文案。 */
    public static String statusName(int status) {
        if (status == OK) return "可用";
        if (status == RATE) return "限流";
        if (status == UNAUTHORIZED) return "无权限";
        if (status == EMPTY) return "空响应";
        if (status == QUOTA) return "额度不足";
        return "错误";
    }

    /** 轻量门面（避免各包 import core.App）。 */
    private static final class App {
        static com.aigetway.tattoo7.core.App get() {
            return com.aigetway.tattoo7.core.App.get();
        }
    }
}