package com.aigetway.tattoo7.gateway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 网关侧上下文治理（中段折叠）。vc125 起估算与阈值全部重做。
 *
 * <p>背景：minis 客户端不消费网关 /v1/models 下发的 context_window（它依赖
 * models.dev 目录对模型做 enrichment，本地网关的自定义免密 id 查不到，ID
 * heuristic 兜底成 128k）。而 space-bunny-free 这类免密模型上游窗口极大
 * （实测 &gt;260k），minis 的自动压缩阈值（128k 档 = 88k tokens）在会话被
 * 网络/客户端物理上限打断之前几乎不会被触发 —— 于是"重来不压缩，直到撑爆"。
 *
 * <p>方案：在透传前主动治理 messages。触发条件改为 <b>占模型窗口的比例</b>
 * （见 {@link #TRIGGER_RATIO}，窗口取自 /v1/models 下发的同一份目录数据），
 * 并加双阈值防抖；体积用 {@link ContextEstimator}（分字符类别加权 +
 * tool_calls/reasoning 计入 + 安全系数）估算，替代旧版"content 字符数 = token"。
 * 折叠时保留 system + 开头锚点 + 最近 K 条（K 按剩余预算动态取，安全网 = 窗口
 * 的 1/4），中间整轮丢弃。
 * 不依赖客户端任何行为。
 *
 * <p><b>vc125 关键改动（修"折叠不及时 → 被吞上下文 → 绕圈圈"）</b>
 * <ol>
 *   <li><b>估算不再漏项</b>：旧版只数 content 字符 ⇒ 漏 tool_calls.arguments
 *       与 reasoning_content，真实 token 低估数倍，于是总也触发不了折叠。</li>
 *   <li><b>阈值相对窗口</b>：旧的固定 24k 触发 / 24k 尾块预算，对小窗口模型
 *       太晚、对大窗口模型太早。改为 window 的 30% 触发、25% 时开始回收尾块，
 *       硬上限 85% —— 折叠后占用被钳到窗口预算内，不会再撞上游上限被截断。</li>
 *   <li><b>真实用量回灌</b>：上游每帧若带 usage.total_tokens 就记录比例系数，
 *       下次估算直接按实测校准（离线时退回默认系数）。</li>
 *   <li><b>折叠横幅带恢复指引</b>：横幅文案明确说明"已完成的工作已落盘到文件/
 *       待办列表，不要重复验证"，抑制模型绕圈。</li>
 * </ol>
 *
 * <p><b>安全保证（工具调用优先）</b>：
 * <ul>
 *   <li>首部 head 只保留 <b>无工具</b> 的消息（遇到带 tool_calls 的 assistant 或
 *       role:"tool" 就停止扩展），避免 head 结尾出现悬空 tool_calls / 孤立 tool；</li>
 *   <li>尾部 tail 从后往前保留，遇到 tool / 带 tool_calls 的 assistant 会继续向前
 *       扩展，直到自洽；绝不切开 tool 与其 assistant 的配对；</li>
 *   <li>组装后做 <b>全量配对校验</b>，尾界落在孤立 tool 上时先<b>前移尾界</b>，
 *       校验仍失败不再回滚，照常折叠并交 Router.chat 随后的 selfHeal 兜底；</li>
 *   <li>绝不丢弃 messages 最后一条。</li>
 * </ul>
 */
public final class ContextGovernor {

    /** 触发折叠的窗口占用比例。 */
    public static final double TRIGGER_RATIO = 0.30;
    /** 尾块预算占比：超过即从最老的对话轮开始回收，防止尾块自己撑爆窗口。 */
    public static final double TAIL_BUDGET_RATIO = 0.25;
    /** 折叠后硬上限占比：无论尾块多大都不能超过它（保命线）。 */
    public static final double HARD_CAP_RATIO = 0.85;
    /** 目录查不到模型时的兜底窗口。 */
    private static final int FALLBACK_WINDOW = 128_000;
    /** 触发下限（太小的会话不折，避免短会话被无谓治理）。 */
    private static final int MIN_TRIGGER_TOKENS = 6_000;
    /** 折叠后释放量不足该比例时跳过（防抖：刚折完不必再折）。 */
    private static final double MIN_GAIN_RATIO = 0.08;

    /** 尾部（最近对话）保留的消息条数基数。 */
    private static final int TAIL_KEEP_BASE = 10;
    /** 尾部自洽展开的硬上限。 */
    private static final int TAIL_KEEP_HARD = 40;
    /** 首部锚点保留的原始消息条数硬上限。 */
    private static final int HEAD_KEEP_HARD = 6;
    /** 中段折叠最低收益门槛：中段至少要有 N 条消息才值得折（vc61）。 */
    private static final int MIN_MIDDLE_MSGS = 4;
    /** 中段折叠最低收益门槛：中段估算至少 N tokens，否则折了不省（vc61）。 */
    private static final int MIN_MIDDLE_TOKENS = 6_000;

    /** 单条工具结果的最大等效 token 数（vc137，修"绕圈圈"）。
     *  折叠会整轮丢消息，模型就忘了自己读过某文件 → 反复读同一文件、反复跑已验证过的
     *  工具调用（绕圈圈）。根因常是单条大文件读取（1 万+ token）把尾块撑爆，逼出折叠。
     *  与其折叠，不如把超大工具结果压到固定上限、保留每轮开头，让所有轮次都留下。 */
    private static final int MAX_TOOL_RESULT_TOKENS = 2_000;

    // ── vc125：会话级真实占用闭环 ──
    /** 折算系数(clamped)：把"折算后"的等效 token 换回模型真实 token 量级。
     *  <p>注意区分两个系数：
     *  <ul><li>{@code calibFactor}（全局）：applyCalib 已把折算值乘进去，
     *  于是 {@code raw × calib} 就直接近似真实 token；</li>
     *  <li>会话 ratio：会话账本里实测/估算的比值，用于"上一轮真实 + 本轮净增"这条线。</li>
     *  </ul> */
    private static final double CLAMP_RATIO_MIN = 0.25;
    private static final double CLAMP_RATIO_MAX = 4.0;
    /** 折叠目标：折完后希望落到窗口的哪个比例（低于 HARD_CAP，留出后续增长空间）。 */
    public static final double FOLD_TARGET_RATIO = 0.55;

    /** 本轮请求的会话指纹（由 Router 在治理时设置，随线程本地传给 PreparedChat 收尾）。 */
    private static final ThreadLocal<String> TURN_KEY = new ThreadLocal<>();
    /** 本轮治理前的全量估算（同一坐标系），用于对齐上游实测。 */
    private static final ThreadLocal<Long> TURN_EST = new ThreadLocal<>();

    // ── 实测校准：上游 usage 回灌的比例系数（默认 1.0 = 纯估算） ──
    private static volatile double calibFactor = 1.0;
    private static volatile long calibSamples = 0;
    private static volatile long calibUpdatedAt = 0L;

    private ContextGovernor() {}

    /**
     * 对 body 的 messages 做中段折叠治理。返回是否发生了折叠。
     * 输入 messages 为空/超小时原样返回 false。
     *
     * @param windowTokens 该模型的上游上下文窗口（&lt;=0 表示未知，用兜底值）
     */
    public static boolean govern(JSONObject body, String model, int windowTokens) {
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs == null || msgs.length() == 0) return false;

        int window = windowTokens > 0 ? windowTokens : FALLBACK_WINDOW;
        long budget = clampBudget(window);

        // ── 0) 超大工具结果截断（vc137）──
        // 必须在估算之前做：单条大文件读取能到 1 万+ token，直接决定"折不折"。
        // 截断后估算显著下降，很多会话根本不再触发折叠；即便触发，尾块也能装更多轮。
        truncateOversizedToolResults(msgs);

        long est = estimateRequestTokens(body);
        int trigger = Math.max(MIN_TRIGGER_TOKENS, (int) (window * TRIGGER_RATIO));
        if (msgs.length() <= TAIL_KEEP_BASE) return false;

        // ── 0) 会话级真实占用闭环（vc125 核心）──
        // 纯估算有 ±30% 误差，而折叠触发是"估算 vs 30%窗口"的绝对值比较，
        // 误差直接变成"折叠不及时（被吞上下文）"或"过早折叠（模型绕圈圈）"。
        // 这里用会话账本把上一轮的真实占用作为基准，只信本轮的净增，误差被限制在
        // 单轮增量上；没有实测样本时退化为纯估算（与旧行为一致）。
        String ckey = conversationKey(body);
        long estFull = est;
        long used = SessionContextLedger.lowerBoundUsed(ckey, estFull);
        if (used < est) used = est;              // 取大：宁可早折，不被截断
        if (used < trigger) return false;
        log("usage model=" + model + " win=" + window + " trigger=" + trigger
                + " est=" + est + " realBound=" + used
                + " [" + SessionContextLedger.describe(ckey) + "]");
        // 折叠力度按"真实占用"定：把历史压到窗口的 FOLD_TARGET_RATIO，而不是"能省就省"。
        long target = Math.max((long) (window * FOLD_TARGET_RATIO), MIN_TRIGGER_TOKENS);
        // vc137：尾块预算改为"整段折叠目标 target"（窗口×FOLD_TARGET_RATIO），
        // 不再用旧的 max(budget/2, min(尾块比例, target/2))。旧式把尾块压到折叠目标的
        // 一半，折完中段后尾块容不下 10+ 轮最近对话 → 模型记不住已读文件 → 绕圈圈。
        // 目标=折叠后整体要落到的大小，尾块应占满 target（首部与折叠横幅只占零头）。
        long tailBudgetEff = Math.max(target, tailBudget(window));
        int total = msgs.length();

        // ── 1) 尾块初始界：从后往前保留到"自洽且达基数" ──
        int tailStart = total;
        for (int i = total - 1; i >= 0; i--) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            int kept = total - i;
            boolean needsMore = !isSelfConsistentEnd(m); // tool/带tool_calls需要继续向前
            if (kept >= TAIL_KEEP_BASE && !needsMore) { tailStart = i; break; }
            if (kept >= TAIL_KEEP_HARD) { tailStart = i; break; }
            if (i == 0) { tailStart = 0; }
        }
        if (tailStart <= 0) return false; // 尾块已覆盖全部，无须折叠

        // ── 1.5) 尾界落在 role:"tool" 上：其 assistant 必在折叠区 ⇒ 尾块以孤立
        // tool 开头，上游必 400。前移尾界，让它们连同 assistant 一起并入折叠。
        tailStart = skipLeadingToolMessages(msgs, tailStart, total);
        if (tailStart >= total) return false;

        // ── 1.7) 尾块预算：尾块自身不能超过窗口的 TAIL_BUDGET_RATIO，
        //     否则"折了中段还是超窗口"。超出时从最老的对话轮开始回收，
        //     每次回收一整轮（user → 下一条 user 之前）。
        tailStart = shrinkTailToBudget(msgs, tailStart, total, tailBudgetEff);

        // ── 2) 首部 head：[headEnd) 内只收无工具消息 ──
        int headEnd = 0;
        int scanned = 0;
        for (int i = 0; i < total && i < tailStart; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if (isToolish(m)) break;
            scanned++;
            headEnd = i + 1;
            if (scanned >= HEAD_KEEP_HARD) break;
            if (scanned >= 2) break; // 至少保留 1 条完整对话轮
        }

        // 必须至少保留 system（若有）
        if (headEnd <= 0 && total > 0) {
            JSONObject first = msgs.optJSONObject(0);
            if (first != null && "system".equals(first.optString("role", ""))) headEnd = 1;
        }

        // ── 2.5) 收益门槛：中段必须真的有料才折（vc61）──
        if (tailStart <= headEnd) {
            log("govern skip: middle empty (headEnd=" + headEnd + " tailStart=" + tailStart
                    + " total~" + est + "/trig" + trigger + ") - no fold");
            return false;
        }
        int middleMsgs = tailStart - headEnd;
        long middleEst = ContextEstimator.estimateRange(msgs, headEnd, tailStart);
        if (middleMsgs < MIN_MIDDLE_MSGS || middleEst < MIN_MIDDLE_TOKENS) {
            log("govern skip: middle too small (msgs=" + middleMsgs + "/" + MIN_MIDDLE_MSGS
                    + " tok~" + middleEst + "/" + MIN_MIDDLE_TOKENS
                    + " total~" + est + "/trig" + trigger + ") - no fold");
            return false;
        }

        // ── 3) 组装折叠后的 messages ──
        JSONArray kept = new JSONArray();
        for (int i = 0; i < headEnd; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null) kept.put(m);
        }
        int dropped = tailStart - headEnd;
        if (headEnd > 0 && headEnd < total) {
            kept.put(foldNotice(dropped));
        }
        for (int i = tailStart; i < total; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null) kept.put(m);
        }

        // ── 4) 配对校验 ──
        if (!toolPairingIntact(kept)) {
            log("govern model=" + model + " pairing broken after fold (head=" + headEnd
                    + " tailStart=" + tailStart + ") -> proceed, selfHeal will fix");
        }

        long after = ContextEstimator.estimateMessages(kept);
        // 防抖（vc125 改造）：旧判据是"释放量 ≥ 窗口×8%"才折，太钝 —— 真实占用
        // 已经越过 HARD_CAP 时哪怕只释放 5% 也必须折。改成两条任一满足即折：
        //   a) 释放量够大（≥窗口×8%，避免无谓折腾）；
        //   b) 真实占用超过硬上限（必须出手，不然下一轮就被上游截断）。
        long gainNeed = (long) (window * MIN_GAIN_RATIO);
        long hardCap = (long) (window * HARD_CAP_RATIO);
        boolean gainOk = (est - after) >= gainNeed;
        boolean overCap = used > hardCap;
        if (!gainOk && !overCap) {
            log("govern skip: gain too small (" + est + "->" + after
                    + ", need " + gainNeed + ", realBound=" + used
                    + "/cap" + hardCap + ") - no fold");
            return false;
        }
        try {
            body.put("messages", kept);
        } catch (JSONException ignored) {
            return false;
        }

        // 本轮登记：PreparedChat 流结束时按这个 key 回灌上游 usage。
        TURN_KEY.set(ckey);
        TURN_EST.set(estFull);
        log("govern model=" + model + " win=" + window + " trig=" + trigger
                + " msgs " + total + "->" + kept.length() + " (drop " + dropped + ")"
                + " tok ~" + est + "->" + after
                + " [" + (after * 100L / window) + "% of window]"
                + " realBound=" + used + " target=" + target
                + " calib=" + String.format(java.util.Locale.US, "%.2f", calibFactor)
                + " sess=" + String.format(java.util.Locale.US, "%.2f",
                        SessionContextLedger.ratio(ckey)));
        return true;
    }

    /** 未折叠时也要登记，好让本轮 usage 回灌到正确的会话。 */
    public static void markTurn(JSONObject body, String model, long estFull) {
        TURN_KEY.set(conversationKey(body));
        TURN_EST.set(estFull);
    }

    /** 只读当前轮 key（usage 帧到达时用，不清除，收尾再清）。 */
    public static String peekTurnKey() {
        return TURN_KEY.get();
    }

    /** PreparedChat 收尾时取走（取走即清，避免串轮）。 */
    public static String takeTurnKey() {
        String k = TURN_KEY.get();
        TURN_KEY.remove();
        return k;
    }

    public static long takeTurnEstimate() {
        Long v = TURN_EST.get();
        TURN_EST.remove();
        return v == null ? 0L : v;
    }

    /**
     * 会话指纹：不依赖客户端会话 id（OpenAI 兼容请求里没有），
     * 用"首条消息（system 提示块）哈希 + 第二条 user 消息指纹"构造。
     * <p>agent 客户端每轮都会把完整历史重发，首条 system 内容稳定、
     * 第二条 user 消息是会话的第一句话也稳定，二者足以区分同一 App 内的不同会话。
     */
    public static String conversationKey(JSONObject body) {
        JSONArray msgs = body == null ? null : body.optJSONArray("messages");
        if (msgs == null || msgs.length() == 0) return "#empty";
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < msgs.length() && taken < 2; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if (!"system".equals(role) && !"user".equals(role)) continue;
            Object c = m.opt("content");
            String text = c == null ? "" : c.toString();
            if ("system".equals(role)) {
                // system 只取前 160 字符：不同会话的 system 通常相同，
                // 靠它兜底区分"不同 App/不同工具集"。
                if (text.length() > 160) text = text.substring(0, 160);
            } else {
                if (text.length() > 96) text = text.substring(0, 96);
            }
            sb.append(role).append('|').append(text).append('');
            taken++;
        }
        String seed = sb.length() == 0 ? msgs.toString() : sb.toString();
        return "#" + sha1Hex(seed).substring(0, 16);
    }

    private static String sha1Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : d) out.append(String.format(java.util.Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 兼容旧签名（无窗口信息）。 */
    public static boolean govern(JSONObject body, String model) {
        return govern(body, model, 0);
    }

    // ────────────────────────── 估算 ──────────────────────────

    /** 整个请求的等效 token（含 tools 声明 + 校准系数）。 */
    public static long estimateRequestTokens(JSONObject body) {
        long raw = ContextEstimator.estimateRequest(body);
        return applyCalib(raw);
    }

    /** 消息数组的等效 token（含校准系数）。 */
    public static long estimateMessagesTokens(JSONArray msgs) {
        return applyCalib(ContextEstimator.estimateMessages(msgs));
    }

    private static long applyCalib(long raw) {
        double f = calibFactor;
        if (f <= 0.1 || f > 5.0) f = 1.0; // 异常样本不采信
        return (long) Math.ceil(raw * f);
    }

    /**
     * 上游返回 usage 时回灌校准：把"我们估算的输入 token"与"上游实际 prompt_tokens"
     * 的比值做滑动平均。下次估算直接按实测比例缩放，误差被真实数据持续纠正。
     *
     * @param estimatedInputTokens 本次请求治理后、我们估算的输入 token
     * @param actualPromptTokens   上游返回的 usage.prompt_tokens（&lt;=0 忽略）
     */
    public static void observeUsage(long estimatedInputTokens, long actualPromptTokens) {
        if (estimatedInputTokens <= 0 || actualPromptTokens <= 0) return;
        double ratio = (double) actualPromptTokens / (double) estimatedInputTokens;
        if (ratio < 0.2 || ratio > 5.0) return; // 明显异常（缓存命中/上游口径不同）不采
        double prev = calibFactor;
        double next = prev * 0.7 + ratio * 0.3; // EMA
        calibFactor = next;
        calibSamples++;
        calibUpdatedAt = System.currentTimeMillis();
        log("calib ratio=" + String.format(java.util.Locale.US, "%.3f", ratio)
                + " factor " + String.format(java.util.Locale.US, "%.3f", prev)
                + "->" + String.format(java.util.Locale.US, "%.3f", next)
                + " (n=" + calibSamples + ")");
    }

    public static double calibFactor() { return calibFactor; }
    public static long calibSamples() { return calibSamples; }
    public static long calibUpdatedAt() { return calibUpdatedAt; }

    // ────────────────────────── 尾块 ──────────────────────────

    private static int skipLeadingToolMessages(JSONArray msgs, int tailStart, int total) {
        while (tailStart < total) {
            JSONObject bm = msgs.optJSONObject(tailStart);
            if (bm != null && "tool".equals(bm.optString("role", ""))) {
                tailStart++;
            } else {
                break;
            }
        }
        return tailStart;
    }

    /**
     * 尾块超预算 → 从最老的"对话轮"开始回收。
     * 一轮 = 一条 user 消息起，到下一个 user 之前。
     * 回收后仍要保证：不切开 tool 配对（前移到自洽边界）、至少留 TAIL_KEEP_BASE 条。
     */
    private static int shrinkTailToBudget(JSONArray msgs, int tailStart, int total, long budget) {
        long tailEst = ContextEstimator.estimateRange(msgs, tailStart, total);
        if (tailEst <= budget) return tailStart;

        int idx = tailStart;
        while (idx < total && tailEst > budget && (total - idx) > TAIL_KEEP_BASE) {
            int next = nextTurnStart(msgs, idx + 1, total);
            if (next <= idx) break;
            tailEst -= ContextEstimator.estimateRange(msgs, idx, next);
            idx = next;
        }
        // 前移到自洽边界，避免以孤立 tool 开头
        idx = skipLeadingToolMessages(msgs, idx, total);
        // 不把最后一条丢掉
        if (idx >= total - 1) idx = total - 1;
        return Math.max(idx, 0);
    }

    /** 从 from 起找下一条 user 消息下标（=下一轮起点），找不到返回 total。 */
    private static int nextTurnStart(JSONArray msgs, int from, int total) {
        for (int i = from; i < total; i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role", ""))) return i;
        }
        return total;
    }

    /** 折叠后预算：window × HARD_CAP_RATIO，最小不低于兜底窗口的 1/4。 */
    private static long clampBudget(int window) {
        long b = (long) (window * HARD_CAP_RATIO);
        long floor = FALLBACK_WINDOW / 4L;
        return Math.max(b, floor);
    }

    /** 尾块预算。 */
    public static long tailBudget(int window) {
        int w = window > 0 ? window : FALLBACK_WINDOW;
        return (long) (w * TAIL_BUDGET_RATIO);
    }

    /** 触发阈值。 */
    public static long triggerTokens(int window) {
        int w = window > 0 ? window : FALLBACK_WINDOW;
        return Math.max(MIN_TRIGGER_TOKENS, (long) (w * TRIGGER_RATIO));
    }

    // ────────────────────────── 超大工具结果截断（vc137） ──────────────────────────

    /**
     * vc137：把超过 {@link #MAX_TOOL_RESULT_TOKENS} 的工具结果截成"保留开头"。
     * 原因见 {@link #MAX_TOOL_RESULT_TOKENS} 注释：大文件读取单条可上万 token，
     * 直接把尾块预算吃光 → 只能靠"丢整轮"的折叠降体积 → 模型忘记已读文件 → 绕圈圈。
     * 截断比折叠温柔：所有轮次都留下，每轮都能看到对应文件的开头。
     * 只在 content 估算超标时动手，正常小结果零副作用。
     */
    private static void truncateOversizedToolResults(JSONArray msgs) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            if (!"tool".equals(m.optString("role", ""))) continue;
            truncateToolResult(m);
        }
    }

    private static void truncateToolResult(JSONObject m) {
        Object content = m.opt("content");
        if (content == null) return;
        if (content instanceof JSONArray) {
            truncateToolResultArray(m, (JSONArray) content);
            return;
        }
        String text = content.toString();
        if (text.isEmpty()) return;
        if (ContextEstimator.textTokensStr(text) <= MAX_TOOL_RESULT_TOKENS) return;
        String truncated = ContextEstimator.truncateToTokenBudget(text, MAX_TOOL_RESULT_TOKENS);
        try {
            m.put("content", truncated
                    + "\n\n[…工具结果过长，网关已截断，仅保留开头 " + text.length() + " 字符中的前 "
                    + truncated.length() + " 字符…]");
        } catch (JSONException ignored) {
        }
        log("truncTool chars " + text.length() + "->" + truncated.length()
                + " (" + ContextEstimator.textTokensStr(text) + " tok)");
    }

    /** 多块内容的工具结果：逐块按剩余预算截断，保留数组结构。 */
    private static void truncateToolResultArray(JSONObject m, JSONArray a) {
        if (a.length() == 0) return;
        if (ContextEstimator.textTokens(a) <= MAX_TOOL_RESULT_TOKENS) return;
        long kept = 0;
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                long room = MAX_TOOL_RESULT_TOKENS - kept;
                if (room <= 0) break;
                Object p = a.opt(i);
                if (p instanceof JSONObject) {
                    JSONObject po = (JSONObject) p;
                    String t = po.optString("text", "");
                    String tr = ContextEstimator.truncateToTokenBudget(t, room);
                    if (!t.equals(tr)) {
                        po.put("text", tr + "\n[…工具结果过长，网关已截断…]");
                    }
                    kept += ContextEstimator.textTokensStr(tr);
                    out.put(po);
                } else {
                    String s = String.valueOf(p);
                    String tr = ContextEstimator.truncateToTokenBudget(s, room);
                    kept += ContextEstimator.textTokensStr(tr);
                    out.put(tr);
                }
            }
            if (out.length() > 0) m.put("content", out);
        } catch (JSONException ignored) {
        }
    }

    // ────────────────────────── 折叠横幅 ──────────────────────────

    /**
     * 折叠提示（横幅）。放在 head 与 tail 之间作为一条 user 消息。
     * vc125 起明确给出"恢复指引"，抑制模型重复已验证过的步骤。
     */
    private static JSONObject foldNotice(int dropped) {
        JSONObject m = new JSONObject();
        try {
            m.put("role", "user");
            m.put("content", "[网关自动上下文治理] 中间 " + dropped
                    + " 条历史消息已折叠省略（旧对话内容不再完整保留）。"
                    + "请直接基于下方保留的最近对话内容继续完成任务，无需回顾被省略的部分。"
                    + "折叠掉的是已完成的历史步骤：不要重新执行或重新验证已完成的工作，"
                    + "已完成结论以工作区文件（如分析产物、PROGRESS 笔记）与待办列表为准。");
        } catch (JSONException ignored) {
        }
        return m;
    }

    // ────────────────────────── 配对 / 自愈（保持原语义） ──────────────────────────

    /** 一条消息是否携带工具相关结构（assistant 的 tool_calls 或 role:"tool"）。 */
    private static boolean isToolish(JSONObject m) {
        if ("tool".equals(m.optString("role", ""))) return true;
        return m.has("tool_calls") && !m.isNull("tool_calls")
                && m.optJSONArray("tool_calls") != null
                && m.optJSONArray("tool_calls").length() > 0;
    }

    /**
     * 校验 messages 的 tool 配对完整：
     * <ul>
     *   <li>带 tool_calls 的 assistant 后必须紧跟 role:"tool" 结果；</li>
     *   <li>role:"tool" 结果必须能匹配到前面最近的 assistant.tool_calls 的 id；</li>
     *   <li>不得存在"assistant 带 tool_calls 但无 tool 结果"或"孤立 tool 结果"。</li>
     * </ul>
     */
    private static boolean toolPairingIntact(JSONArray msgs) {
        Set<String> outstanding = new HashSet<>(); // 已发出、等待 tool 结果的 id
        Set<String> valid = new HashSet<>();       // 可被 tool 结果匹配的 id
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("assistant".equals(role)) {
                JSONArray tcs = m.optJSONArray("tool_calls");
                if (tcs != null && tcs.length() > 0) {
                    for (int j = 0; j < tcs.length(); j++) {
                        JSONObject tc = tcs.optJSONObject(j);
                        if (tc == null) continue;
                        String id = tc.optString("id", "");
                        if (!id.isEmpty()) { outstanding.add(id); valid.add(id); }
                    }
                }
            } else if ("tool".equals(role)) {
                String tid = m.optString("tool_call_id", "");
                if (!valid.contains(tid)) return false;  // 孤立 tool 结果
                outstanding.remove(tid);                  // 配对闭合
            }
        }
        return outstanding.isEmpty(); // 所有 tool_calls 都已配对闭合
    }

    /** 一条消息是否是"安全结尾"：不是孤立的 tool 结果，也没有悬空 tool_calls。 */
    private static boolean isSelfConsistentEnd(JSONObject m) {
        String role = m.optString("role", "");
        if ("tool".equals(role)) return false;
        if ("assistant".equals(role)) {
            JSONArray tcs = m.optJSONArray("tool_calls");
            if (tcs != null && tcs.length() > 0) return false;
            if (m.has("function_call") && !m.isNull("function_call")) return false;
        }
        return true;
    }

    /**
     * 工具配对兜底自愈：把无法匹配到前面 assistant.tool_calls 的孤立
     * role:"tool" 消息改写成 role:"user"（保内容）。
     * 只处理 tool 这一侧；悬空 tool_calls 由上游自行容忍。
     */
    public static boolean selfHeal(JSONObject body) {
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs == null || msgs.length() == 0) return false;
        Set<String> known = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("assistant".equals(role)) {
                collectCallIds(m.opt("tool_calls"), known);
                collectCallIds(m.opt("function_call"), known);
            } else if ("tool".equals(role)) {
                String tid = m.optString("tool_call_id", "");
                if (tid.isEmpty() || !known.contains(tid)) {
                    try {
                        m.put("role", "user");
                        m.remove("tool_call_id");
                        Object c = m.opt("content");
                        m.put("content", "[工具结果（已脱离原始调用）] "
                                + (c == null ? "" : c.toString()));
                        changed = true;
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        return changed;
    }

    /** 从 tool_calls 数组或单个 function_call 对象里收集 id（兼容两种上游包装）。 */
    private static void collectCallIds(Object o, Set<String> out) {
        if (o == null || JSONObject.NULL.equals(o)) return;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject tc = a.optJSONObject(i);
                if (tc == null) continue;
                String id = tc.optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String id = j.optString("id", "");
            if (!id.isEmpty()) out.add(id);
        }
    }

    private static void log(String s) {
        try {
            com.aigetway.tattoo7.core.App.get().logSink().log("CTX", s);
        } catch (Throwable ignored) {
        }
    }
}
