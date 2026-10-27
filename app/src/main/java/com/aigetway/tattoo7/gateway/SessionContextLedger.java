package com.aigetway.tattoo7.gateway;

import java.util.LinkedHashMap;

/**
 * 会话级上下文账本（vc125）。
 *
 * <p><b>要解决的两个现网症状</b>：
 * <ol>
 *   <li><b>"会话折叠不及时，经常被吞上下文"</b> —— 每一轮请求都是独立重新估算，
 *       折叠阈值靠"本轮估算值 &gt; 窗口×30%"判断。估算器无论怎么修都有 ±20~40% 误差，
 *       而 agent 场景（工具调用多）误差是单边低估倾向；于是真实占用已经撞到上游
 *       硬上限了，网关这边还以为没过触发线，折叠一直不触发 → 上游/客户端截断旧消息
 *       → 模型看不到自己做过什么。</li>
 *   <li><b>"做过的验证后面又做一遍（绕圈圈）"</b> —— 折叠是"凭估算一次性多折"，
 *       折完只看"省了没有"，没有拿真实占用做闭环。折过头 → 模型失去已完成工作的证据，
 *       于是把同一件事再验一遍。</li>
 * </ol>
 *
 * <p><b>做法</b>：把上游 usage 回灌的真实值按会话记账，得到本会话"每 token 折算系数"，
 * 并且把 <b>上一轮真实占用 + 本轮净增</b> 作为本轮占用的下界估计：
 * <pre>
 *   realPrev   = 上一轮上游实测的 prompt_tokens
 *   netDelta   = 本轮估算(全量历史) − 上一轮估算(全量历史)   // 只信任"增量"，
 *                                                          // 因为它不含绝对误差只含相对误差
 *   占用下界   = realPrev + max(0, netDelta) × 会话系数
 * </pre>
 * 这样一来折叠触发与折叠力度都用"真实占用"闭环决定，误差被限制在单轮增量上（±10% 量级），
 * 而不是整个历史长度上的绝对误差（±40%）。同时折叠后按"目标占用"重估，避免折过头。
 *
 * <p>纯静态存储 + synchronized 小对象，无 Android 依赖，可单测。
 */
public final class SessionContextLedger {

    /** 保留的会话条目数（key 是会话指纹）。 */
    private static final int MAX_ENTRIES = 24;
    /** 一条会话多久没动静就丢弃（15 分钟）。 */
    private static final long TTL_MS = 15L * 60L * 1000L;
    /** 会话系数的 EMA 权重（新样本占 30%）。 */
    private static final double EMA_NEW = 0.3;
    /** 会话系数下限/上限：超出视为口径不同（缓存命中/上游另计），不采。 */
    private static final double RATIO_MIN = 0.25;
    private static final double RATIO_MAX = 4.0;

    /** 一条会话的记账。 */
    public static final class Entry {
        /** 上一轮上游实测的 prompt_tokens（输入占用）。 */
        long realPrompt;
        /** 上一轮治理后我们的全量估算（同一坐标系，用于算净增）。 */
        long estFull;
        /** 上一轮上游实测的输出 token。 */
        long realOutput;
        /** 本会话"实测/估算"的滑动平均系数。 */
        double ratio = 1.0;
        long samples;
        long updatedAt;
    }

    private static final LinkedHashMap<String, Entry> MAP = new LinkedHashMap<String, Entry>() {
        @Override
        protected boolean removeEldestEntry(
                java.util.Map.Entry<String, SessionContextLedger.Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private SessionContextLedger() {}

    private static Entry entry(String key) {
        if (key == null || key.isEmpty()) key = "#anonymous";
        Entry e = MAP.get(key);
        long now = System.currentTimeMillis();
        if (e != null && now - e.updatedAt > TTL_MS) {
            MAP.remove(key);
            e = null;
        }
        if (e == null) {
            e = new Entry();
            e.updatedAt = now;
            MAP.put(key, e);
        }
        return e;
    }

    /**
     * 本轮请求的"真实占用下界"。
     *
     * @param key            会话指纹（由 {@link ContextGovernor#conversationKey} 生成）
     * @param estFullTokens  本轮治理后、对<b>当前 messages 全量</b>的估算（已含校准系数）
     * @return 若本会话有实测样本，返回 realPrev + 净增×系数；否则返回 estFullTokens
     */
    public static long lowerBoundUsed(String key, long estFullTokens) {
        if (estFullTokens <= 0) return 0;
        Entry e = peek(key);
        if (e == null || e.realPrompt <= 0) return estFullTokens;
        double f = e.ratio <= 0 ? 1.0 : e.ratio;
        double delta = estFullTokens - e.estFull;
        // 净增可能为负（本轮发生了折叠）：那说明折叠已经释放了 estFull-e.estFull，
        // 真实值也应等比下降 —— 用 max 保证不会把占用算到比实测还低太多。
        long bound = e.realPrompt + (long) Math.ceil((delta > 0 ? delta : 0) * f);
        // 下界不得低于"估算折系数"（两条线取大者）
        long est = (long) Math.ceil(estFullTokens * f);
        return Math.max(bound, est);
    }

    /** 上一轮实测输入（无样本返回 0）。 */
    public static long lastRealPrompt(String key) {
        Entry e = peek(key);
        return e == null ? 0 : e.realPrompt;
    }

    /** 本会话系数（无样本返回 1.0）。 */
    public static double ratio(String key) {
        Entry e = peek(key);
        return e == null || e.ratio <= 0 ? 1.0 : e.ratio;
    }

    private static synchronized Entry peek(String key) {
        if (key == null || key.isEmpty()) key = "#anonymous";
        Entry e = MAP.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.updatedAt > TTL_MS) {
            MAP.remove(key);
            return null;
        }
        return e;
    }

    /**
     * 一轮结束时调用：把"本轮估算(全量)"与"上游实测"记账。
     *
     * @param key           会话指纹
     * @param estFullTokens 请求发出前，对全量 messages 的估算（治理后的形态）
     * @param actualPrompt  上游 usage.prompt_tokens
     * @param actualOutput  上游 usage.completion_tokens（可 0）
     */
    public static synchronized void onTurn(String key, long estFullTokens,
                                           long actualPrompt, long actualOutput) {
        if (key == null || key.isEmpty() || actualPrompt <= 0) return;
        Entry e = entry(key);
        e.realPrompt = actualPrompt;
        e.realOutput = actualOutput;
        e.estFull = estFullTokens > 0 ? estFullTokens : e.estFull;
        if (estFullTokens > 0) {
            double r = (double) actualPrompt / (double) estFullTokens;
            if (r >= RATIO_MIN && r <= RATIO_MAX) {
                e.ratio = e.samples == 0 ? r : (e.ratio * (1 - EMA_NEW) + r * EMA_NEW);
                e.samples++;
            }
        }
        e.updatedAt = System.currentTimeMillis();
    }

    /**
     * 流式收尾回调：按<b>上一轮登记的会话 key</b>记账。
     * PreparedChat 只知道估算与实测，不知道会话 key，key 由治理时的线程本地暂存。
     */
    public static void onTurnFinished(long estFullTokens, long actualOutputTokens) {
        String key = ContextGovernor.takeTurnKey();
        if (key == null) return;
        // 上游 usage 里的 prompt_tokens 已在 settleTurn 里记账；这里只补输出侧，
        // 供"下一轮占用下界"参考（部分上游把不带 usage 的流式帧合并到最后）。
        if (actualOutputTokens > 0) touchOutput(key, actualOutputTokens);
    }

    private static synchronized void touchOutput(String key, long out) {
        Entry e = MAP.get(key);
        if (e != null) {
            e.realOutput = out;
            e.updatedAt = System.currentTimeMillis();
        }
    }

    /** 清空（测试/切模型用）。 */
    public static synchronized void reset() {
        MAP.clear();
    }

    /** 诊断串，写日志用。 */
    public static String describe(String key) {
        Entry e = peek(key);
        if (e == null) return "no-sample";
        return "real=" + e.realPrompt + " ratio="
                + String.format(java.util.Locale.US, "%.2f", e.ratio)
                + " n=" + e.samples + " out=" + e.realOutput;
    }
}
