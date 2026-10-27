package com.aigetway.tattoo7.provider;

import com.aigetway.tattoo7.util.Http;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loomy 新手任务（一次性，合计 10000 积分）。
 *
 * <p>协议参考上游 {@code _upstream/src/loomy-onboarding.ts}（实测 2026-09-26）：
 * <ul>
 *   <li>服务端<b>不校验前置行为</b>：对 8 个任务逐个发 {@code POST /complete} 全部返回
 *       {@code alreadyCompleted:false}，余额 0 → 10000，没有真的发对话 / 生成 PPT / 装技能。
 *       完成条件全部在客户端本地判定，所以是<b>纯 API 直领</b>，一个模型 token 都不花。</li>
 *   <li>端点：
 *       {@code GET  /onboarding/tasks           → { tasks:{8key:bool}, earned, total }}<br>
 *       {@code POST /onboarding/tasks/complete  body { key } → { alreadyCompleted, balance }}</li>
 *   <li>上报 body <b>只有 {@code key}</b>（无设备指纹 / 版本号 / 渠道号）。</li>
 *   <li>幂等判据是响应体的 {@code alreadyCompleted}，<b>不是</b> HTTP 码、<b>不是</b> {@code code}。</li>
 *   <li>不采信服务端 {@code earned}，按本地 {@link #TASK_POINTS} 现算（官方客户端也这么做）。</li>
 * </ul>
 *
 * <p>与「签到」语义独立、不能互相推断：新手任务<b>一次性</b>（每号只能领一次），签到每天有收益。
 * 因此它在账号卡片上有独立按钮、独立端点，<b>不参与</b>页头「一键签到」遍历。
 *
 * <p>⚠️ vc175：签到路径（{@code LoomyProvider.checkin()}）<b>不再</b>自动 {@code claimAll}。
 * vc164/vc165 曾为了对齐官方客户端把它接进签到，结果一键签到结果里每天都多一条
 * 「新手任务无新增（进度 N/10000）」。现在唯一入口是用户自己点的按钮，
 * 见 {@code ProviderDetailActivity.claimOnboardingAccount / claimOnboardingProvider}。
 */
public final class LoomyOnboarding {

    /** 任务积分合计（10000）。 */
    public static final int TOTAL = 10000;

    /** 任务 key → 积分。顺序即执行顺序（= 官方注册表顺序）。 */
    public static final Map<String, Integer> TASK_POINTS = new LinkedHashMap<>();

    /** 任务 key → 中文标题（官方客户端注册表里的 title）。 */
    public static final Map<String, String> TASK_TITLES = new LinkedHashMap<>();

    static {
        TASK_POINTS.put("first_message", 500);
        TASK_POINTS.put("pick_skill", 1000);
        TASK_POINTS.put("generate_ppt", 1500);
        TASK_POINTS.put("set_schedule", 1000);
        TASK_POINTS.put("install_skill", 1500);
        TASK_POINTS.put("configure_remote", 1000);
        TASK_POINTS.put("create_soul", 1500);
        TASK_POINTS.put("share_soul", 2000);

        TASK_TITLES.put("first_message", "发送你的第一条消息");
        TASK_TITLES.put("pick_skill", "试试选择一个技能");
        TASK_TITLES.put("generate_ppt", "生成第一份 PPT");
        TASK_TITLES.put("set_schedule", "设置定时任务");
        TASK_TITLES.put("install_skill", "在技能广场安装一个技能");
        TASK_TITLES.put("configure_remote", "配置远程控制");
        TASK_TITLES.put("create_soul", "创建你的第一个搭子");
        TASK_TITLES.put("share_soul", "把搭子分享给朋友");
    }

    private static final String API_BASE = "https://loomyad.xunfei.cn/api/v1";
    private static final String OK_CODE = "000000";
    private static final int TIMEOUT_MS = 10000;

    private LoomyOnboarding() {}

    /** 业务端点头：与 chat / points 一致，只认 Bearer + token。 */
    private static Map<String, String> headers(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + token);
        h.put("token", token);
        h.put("User-Agent", "Loomy|Desktop|Electron|macOS");
        return h;
    }

    /** 新手任务状态：8 个 key 的完成标记 + 本地现算的已领积分。 */
    public static final class State {
        public final Map<String, Boolean> tasks;
        public final int earned;
        public final int total;

        State(Map<String, Boolean> tasks, int earned, int total) {
            this.tasks = tasks;
            this.earned = earned;
            this.total = total;
        }
    }

    /** 按本地表现算已领积分；未知 key 忽略。 */
    public static int earned(Map<String, Boolean> tasks) {
        int sum = 0;
        for (Map.Entry<String, Integer> e : TASK_POINTS.entrySet()) {
            if (Boolean.TRUE.equals(tasks.get(e.getKey()))) sum += e.getValue();
        }
        return sum;
    }

    /** 一次「领取全部新手任务」的结果。 */
    public static final class Result {
        public final List<String> claimedKeys;
        public final int claimedPoints;
        public final int skipped;
        public final int earned;
        public final int total;

        Result(List<String> claimedKeys, int claimedPoints, int skipped, int earned, int total) {
            this.claimedKeys = claimedKeys;
            this.claimedPoints = claimedPoints;
            this.skipped = skipped;
            this.earned = earned;
            this.total = total;
        }

        public boolean fullyClaimed() {
            return earned >= total;
        }
    }

    /**
     * 查询新手任务状态。
     *
     * <p>{@code earned} 按本地表现算 —— 服务端回传的 {@code earned} 只是参考，
     * 且历史上出现过与本地表不一致的情况。
     */
    public static State fetch(String token) throws IOException {
        Http.Resp r = Http.get(API_BASE + "/onboarding/tasks", headers(token), TIMEOUT_MS);
        JSONObject j = Http.json(r);
        String code = j.optString("code", "");
        if (!OK_CODE.equals(code)) {
            throw new IOException("查询新手任务失败: " + j.optString("desc", "code=" + code));
        }
        JSONObject d = j.optJSONObject("data");
        JSONObject tasksRaw = d == null ? null : d.optJSONObject("tasks");
        Map<String, Boolean> tasks = new LinkedHashMap<>();
        for (String key : TASK_POINTS.keySet()) {
            tasks.put(key, tasksRaw != null && tasksRaw.optBoolean(key, false));
        }
        int total = d == null ? TOTAL : (int) d.optDouble("total", TOTAL);
        return new State(tasks, earned(tasks), total);
    }

    /** 完成（领取）单个新手任务；重复调用返回 alreadyCompleted:true 视为成功。 */
    private static void complete(String token, String key) throws IOException {
        if (!TASK_POINTS.containsKey(key)) {
            throw new IOException("未知的 task key: " + key);
        }
        JSONObject body = new JSONObject();
        try {
            body.put("key", key);
        } catch (org.json.JSONException e) {
            throw new IOException("构造领取请求失败", e);
        }
        Http.Resp r = Http.postJson(API_BASE + "/onboarding/tasks/complete", headers(token), body.toString(), TIMEOUT_MS);
        JSONObject j = Http.json(r);
        String code = j.optString("code", "");
        if (!OK_CODE.equals(code)) {
            throw new IOException("领取「" + TASK_TITLES.get(key) + "」失败: " + j.optString("desc", "code=" + code));
        }
        // 无论服务端回 alreadyCompleted 与否，都记为已领（幂等重放同样算成功）。
    }

    /**
     * 领取全部新手任务（补差额）。
     *
     * <p><b>串行</b>逐个完成（官方客户端也用 Set 去重、串行）；已完成的<b>跳过不发请求</b>。
     * 任一任务失败（认证失效等）立即抛出，不再对后续任务发请求。
     */
    public static Result claimAll(String token) throws IOException {
        State s = fetch(token);
        List<String> claimedKeys = new ArrayList<>();
        int claimedPoints = 0;
        int skipped = 0;
        Map<String, Boolean> next = new LinkedHashMap<>(s.tasks);
        for (String key : TASK_POINTS.keySet()) {
            if (Boolean.TRUE.equals(s.tasks.get(key))) {
                skipped++;
                continue;
            }
            complete(token, key);
            claimedKeys.add(key);
            claimedPoints += TASK_POINTS.get(key);
            next.put(key, true);
        }
        return new Result(claimedKeys, claimedPoints, skipped, earned(next), s.total);
    }
}