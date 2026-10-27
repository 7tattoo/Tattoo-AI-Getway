package com.aigetway.tattoo7.gateway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 网关侧上下文用量估算器（vc125）。
 *
 * <p><b>为什么需要它</b>：旧实现 {@link ContextGovernor} 内部用
 * {@code content 字符数} 当 token 数，且
 * <ol>
 *   <li>只数 content，<b>漏掉 tool_calls 的 arguments</b>（agent 场景下一个
 *       write_file 调用的正文全在这里，动辄上万字符）与
 *       <b>reasoning_content</b> —— 真实 token 被低估 2~5 倍；</li>
 *   <li>英文按 1 字符 = 1 token 高估约 4 倍，中文 1 字符 ≈ 1 token 恰好，
 *       混排时误差随机双向，无法用一个系数修正；</li>
 *   <li>没有把 tools 声明、system prompt 之外的固定开销算进去。</li>
 * </ol>
 * 低估 → 折叠迟迟不触发 → 会话被上游/客户端物理上限截断（"被吞上下文"）；
 * 高估 → 过早折叠 → 模型失去已完成工作的证据，把验证过的步骤再验一遍
 * （"绕圈圈"）。两者都是估算误差，不能靠调单一阈值解决。
 *
 * <p><b>本实现</b>：先按 Unicode 区块把字符分成 CJK / ASCII-非CJK / 其他三类，
 * 分别用 1.0 / 0.25 / 0.6 token-per-char 折算（对齐主流 BPE 的实测比率），
 * 再按消息加结构开销（role、分隔符）、按工具调用加 id/type 开销，
 * 最后整体乘 {@link #SAFETY} 安全系数（宁可略高估，宁早折勿被截）。
 * 纯静态无状态，不依赖 Android，可单测。
 */
public final class ContextEstimator {

    /** 每条消息的固定结构开销（role 字段、chat 模板分隔符等）。 */
    public static final int PER_MESSAGE_OVERHEAD = 4;
    /** 每个 tool_call 的固定结构开销（id / type / function.name）。 */
    public static final int PER_TOOL_CALL_OVERHEAD = 12;
    /** 安全系数：估算值 ×此系数后作为"等效 token"。>1 = 偏保守（早折）。 */
    public static final double SAFETY = 1.15;

    private ContextEstimator() {}

    /** 整个请求的等效 token 数（含 tools 声明与消息）。 */
    public static long estimateRequest(JSONObject body) {
        if (body == null) return 0;
        long n = 0;
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs != null) n += estimateMessages(msgs);
        n += estimateTools(body.opt("tools"));
        // 其他顶层字段（response_format / tool_choice 等）按 JSON 长度折半计
        for (String k : new String[] {"tool_choice", "response_format", "system"}) {
            Object v = body.opt(k);
            if (v != null && !(v instanceof JSONObject && ((JSONObject) v).length() == 0)) {
                n += jsonChars(v) / 2;
            }
        }
        return scale(n);
    }

    /** 消息数组的等效 token 数。 */
    public static long estimateMessages(JSONArray msgs) {
        if (msgs == null) return 0;
        long n = 0;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            n += estimateMessage(m);
        }
        return scale(n);
    }

    /** [from, to) 区间的等效 token 数（用于中段收益门槛）。 */
    public static long estimateRange(JSONArray msgs, int from, int to) {
        if (msgs == null) return 0;
        long n = 0;
        for (int i = Math.max(0, from); i < to && i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            n += estimateMessage(m);
        }
        return scale(n);
    }

    /** 单条消息的原始（未乘安全系数）代价。 */
    public static long estimateMessage(JSONObject m) {
        if (m == null) return 0;
        long n = PER_MESSAGE_OVERHEAD;
        String role = m.optString("role", "");
        n += 2; // role 字面量
        if ("tool".equals(role)) n += 6; // tool_call_id

        n += textTokens(m.opt("content"));
        n += textTokens(m.opt("reasoning_content"));
        n += textTokens(m.opt("reasoning"));
        n += textTokens(m.opt("name"));

        // tool_calls：arguments 是 JSON 字符串，往往是大头
        JSONArray tcs = m.optJSONArray("tool_calls");
        if (tcs != null) {
            for (int j = 0; j < tcs.length(); j++) {
                JSONObject tc = tcs.optJSONObject(j);
                if (tc == null) continue;
                n += PER_TOOL_CALL_OVERHEAD;
                JSONObject fn = tc.optJSONObject("function");
                if (fn != null) {
                    n += textTokens(fn.opt("name"));
                    n += textTokens(fn.opt("arguments"));
                }
            }
        }
        JSONObject fcall = m.optJSONObject("function_call"); // 旧式包装
        if (fcall != null) {
            n += PER_TOOL_CALL_OVERHEAD;
            n += textTokens(fcall.opt("name"));
            n += textTokens(fcall.opt("arguments"));
        }
        return n;
    }

    /** tools 声明（每隔一个请求都发一遍，容易被忽略）。 */
    public static long estimateTools(Object tools) {
        if (tools == null) return 0;
        if (tools instanceof JSONArray) {
            JSONArray a = (JSONArray) tools;
            if (a.length() == 0) return 0;
            return charTokens(jsonChars(tools)) + a.length() *PER_TOOL_CALL_OVERHEAD;
        }
        return charTokens(jsonChars(tools));
    }

    /**
     * 文本 → token 数：按字符类别加权。
     * CJK 1.0 / ASCII 0.25 / 其他 0.6 token-per-char。
     */
    public static long textTokens(Object content) {
        if (content == null) return 0;
        if (content instanceof String) return textTokensStr((String) content);
        if (content instanceof JSONArray) {
            long n = 0;
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                Object p = a.opt(i);
                if (p instanceof JSONObject) {
                    JSONObject po = (JSONObject) p;
                    n += textTokensStr(po.optString("text", ""));
                    // 图片按固定块计（真实视觉 token 由上游算，这里只做体积感知）
                    String type = po.optString("type", "");
                    if (type.contains("image") || type.contains("input_image")) n += 800;
                } else if (p instanceof String) {
                    n += textTokensStr((String) p);
                }
            }
            return n;
        }
        return charTokens(jsonChars(content));
    }

    /**
     * 字符串的加权 token 数。
     * CJK 1.0 / ASCII 0.25 / 其他 0.6（token per char），用千分定点做整数运算。
     */
    public static long textTokensStr(String s) {
        if (s == null || s.isEmpty()) return 0;
        long cjk = 0, ascii = 0, other = 0;
        int len = s.length();
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) cjk++;
            else if (cp < 128) ascii++;
            else other++;
        }
        // 千分定点：cjk×1000 + ascii×250 + other×600
        long milli = cjk * 1000L + ascii * 250L + other * 600L;
        return (milli + 500L) / 1000L; // 四舍五入
    }

    /** 纯字符折算（无类别信息时的兜底，取 0.3 token/char）。 */
    public static long charTokens(long chars) {
        if (chars <= 0) return 0;
        return (chars * 3 + 9) / 10;
    }

    /** 应用安全系数。 */
    private static long scale(long raw) {
        if (raw <= 0) return 0;
        return (long) Math.ceil(raw * SAFETY);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF) // 扩展 A
                || (cp >= 0x3000 && cp <= 0x303F) // CJK 标点
                || (cp >= 0xFF00 && cp <= 0xFFEF) // 全角
                || (cp >= 0x3040 && cp <= 0x30FF) // 日文假名
                || (cp >= 0xAC00 && cp <= 0xD7AF) // 韩文
                || (cp >= 0x20000 && cp <= 0x2FA1F); // 扩展 B+
    }

    private static long jsonChars(Object o) {
        if (o == null) return 0;
        return o.toString().length();
    }

    /** 单遍把字符串截到指定 token 预算（保留开头）。用于超大工具结果的体积治理，
     *  避免单条大文件读取把尾块撑爆、逼出"丢弃整轮"的中段折叠。 */
    public static String truncateToTokenBudget(String s, long maxTokens) {
        if (s == null || s.isEmpty()) return s;
        if (textTokensStr(s) <= maxTokens) return s;
        int n = s.length(), i = 0;
        long cjk = 0, ascii = 0, other = 0;
        int cut = n;
        while (i < n) {
            int cp = s.codePointAt(i);
            int step = Character.charCount(cp);
            if (isCjk(cp)) cjk++;
            else if (cp < 128) ascii++;
            else other++;
            long milli = cjk * 1000L + ascii * 250L + other * 600L;
            if ((milli + 500L) / 1000L > maxTokens) { cut = i; break; }
            i += step;
        }
        if (cut <= 0) cut = Math.min(1, n);
        return cut >= n ? s : s.substring(0, cut);
    }

    /** 供单测/诊断：列出每条消息的估算明细。 */
    public static void describe(JSONArray msgs, List<String> out) {
        if (msgs == null) return;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            out.add(i + " " + m.optString("role", "?") + " ~" + estimateMessage(m));
        }
    }
}
