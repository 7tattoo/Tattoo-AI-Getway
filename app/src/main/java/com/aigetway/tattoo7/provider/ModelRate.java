package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计费倍率解析/展示（Buddy / TRAE / Loomy / 小浣熊 / AutoClaw 共用）。
 *
 * <p>纯 org.json + java.time，**零 Android 依赖** —— sim/ModelRateLogicTest 可直接跑。
 * 解析纪律对齐参考实现 deepseek-harness-codearts（buddy.ts / trae.ts / raccoon.ts /
 * loomy.ts 同名函数，全是用户报障换来的）：
 * <ul>
 *   <li>解析不出 → 返回 null，**不编造 x1** —— 编造倍率比不显示更糟；</li>
 *   <li>0 是合法倍率（免费/限免），必须与「没有倍率信息」区分 —— 判空用 null 而不是真值；</li>
 *   <li>促销只有**此刻生效且确实更便宜**（含 0）才显示 {@code x原价→x促销}。</li>
 * </ul>
 */
public final class ModelRate {

    private ModelRate() {
    }

    private static final Pattern PREFIX = Pattern.compile(
            "^x(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)x$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_ONLY = Pattern.compile(
            "^x(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);

    // ================= 通用解析 =================

    /**
     * 归一化上游倍率原文为 "x{数字}"。
     * 实测形态：{@code "x0.29"}（x 在前）/ {@code "x0.03 credits"}（带单位后缀）/
     * {@code "0.50x"}（促销值 x 在后，同一后端两套写法共存）—— 前后缀都认。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String head = raw.trim().split("\\s+")[0];
        Matcher m = PREFIX.matcher(head);
        if (m.matches()) return "x" + trimNum(Double.parseDouble(m.group(1)));
        m = SUFFIX.matcher(head);
        if (m.matches()) return "x" + trimNum(Double.parseDouble(m.group(1)));
        return null;
    }

    /** "x0.29" → 0.29；解析不出返回 null。 */
    public static Double parse(String text) {
        if (text == null) return null;
        Matcher m = RATE_ONLY.matcher(text.trim());
        if (!m.matches()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * 从**展示文本**里取数值倍率，供 /v1/models 的 multiplier 字段用。
     * 促销 "x1→x0.08" 取末段（实付价 0.08）；"x0.75" 取 0.75；
     * 档位文案（AutoClaw "低/中/高"）等解析不出返回 null —— **不编造**。
     */
    public static Double numericOf(String rateText) {
        if (rateText == null) return null;
        String t = rateText.trim();
        int arrow = t.lastIndexOf('→');
        if (arrow >= 0) t = t.substring(arrow + 1).trim();
        return parse(t);
    }

    /** 浮点去噪：1.0 → "1"、0.08 → "0.08"（最多 4 位小数去尾 0，与参考 formatMultiplier 同款）。 */
    public static String trimNum(double v) {
        String s = String.format(java.util.Locale.US, "%.4f", v).replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 组合展示倍率：无促销 → 原价；促销确实更便宜（含 0）→ {@code x原价→x促销}；
     * 促销价不低于原价时只显示原价（不产出「x0.17→x0.5」这种看不懂的升价箭头）。
     * 只有一边有值就直接用那一边。
     */
    public static String compose(String base, String discounted) {
        if (base == null) return discounted;
        if (discounted == null) return base;
        Double b = parse(base), d = parse(discounted);
        if (b == null || d == null || d >= b) return base;
        return base + "→" + discounted;
    }

    /**
     * 从 Loomy 展示名里抽出倍率（对齐 loomy.ts splitLoomyRate，认两种形态）：
     * <ul>
     *   <li>远端原值：末尾括号，全角半角都认 —— {@code "MiniMax M3 （x4.0）"} /
     *       {@code "Qwen 3.8 Max (x12.0)"} / {@code "GLM 5.3 Flash(x0.8)"}；</li>
     *   <li>已规范化：{@code "{name} · x1.0"}。</li>
     * </ul>
     * 抽不到返回 null。⚠️ 主体为空说明整个 name 就是个括号（畸形数据），返回 null 不产出空名。
     */
    public static String splitRateSuffix(String rawName) {
        if (rawName == null) return null;
        String original = rawName.trim();
        if (original.isEmpty()) return null;
        Matcher m = Pattern.compile("^(.*?)\\s*[（(]\\s*(x\\s*[\\d.]+)\\s*[)）]\\s*$",
                Pattern.CASE_INSENSITIVE).matcher(original);
        if (!m.matches()) {
            m = Pattern.compile("^(.*?)\\s*·\\s*(x\\s*[\\d.]+)\\s*$",
                    Pattern.CASE_INSENSITIVE).matcher(original);
        }
        if (m.matches() && m.group(1).trim().length() > 0) {
            return m.group(2).replaceAll("\\s+", "").toLowerCase();
        }
        return null;
    }

    // ================= Buddy：模型 credits + data.modelPromotions 促销表 =================

    /**
     * Buddy 条目倍率：模型自带 {@code credits}（如 "x0.29"）+ 此刻生效的促销价。
     * 上游没给就返回 null —— 不编造 x1。
     */
    public static String buddyRate(JSONObject model, String modelId, JSONObject promoTableRoot,
                                   long nowMs) {
        return compose(normalize(model.optString("credits", "")),
                activePromotions(promoTableRoot, nowMs).get(modelId));
    }

    /**
     * 解析 {@code data.modelPromotions} 为「模型 id → 此刻生效的促销价」。
     * 结构（实测数组，按 modelIds 关联）：{@code [{enabled, priority,
     * discount:{discountedCredits:"0.50x", factor:0.5},
     * schedule:{daily:[{start,end}],timezone,validFrom,validUntil}, modelIds:[...]}]}
     *
     * <p>四个退化情形（对齐参考 parsePromotions）：
     * enabled:false 跳过；时段/有效期未到或已过跳过；同一模型多条取 priority 高者；
     * **factor:0 + 带时间窗 = 真限免（x0）**，但无任何时间窗的 factor:0 按「已结束占位」跳过。
     */
    public static Map<String, String> activePromotions(JSONObject root, long nowMs) {
        Map<String, String> result = new HashMap<>();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        if (data == null) return result;
        JSONArray arr = data.optJSONArray("modelPromotions");
        if (arr == null) return result;
        // 按 priority 升序写入，高 priority 自然覆盖低 priority
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) sorted.add(o);
        }
        sorted.sort((a, b) -> Integer.compare(a.optInt("priority", 0), b.optInt("priority", 0)));
        for (JSONObject promo : sorted) {
            if (!promo.optBoolean("enabled", true)) continue;
            if (!promotionActiveNow(promo, nowMs)) continue;
            JSONObject discount = promo.optJSONObject("discount");
            if (discount == null) continue;
            boolean windowed = hasTimeWindow(promo.optJSONObject("schedule"));
            double factor = discount.optDouble("factor", Double.NaN);
            String rate = normalize(discount.optString("discountedCredits", ""));
            if (rate == null && !Double.isNaN(factor)) rate = "x" + trimNum(factor);
            if (rate == null) continue;
            if ("x0".equals(rate) && !windowed) continue; // 无窗的 0x 是已结束占位
            JSONArray ids = promo.optJSONArray("modelIds");
            if (ids == null) continue;
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.optString(i, "");
                if (!id.isEmpty()) result.put(id, rate);
            }
        }
        return result;
    }

    /** 活动是否带任何时间窗（每日时段或有效期）。 */
    private static boolean hasTimeWindow(JSONObject sch) {
        if (sch == null) return false;
        JSONArray daily = sch.optJSONArray("daily");
        return (daily != null && daily.length() > 0)
                || sch.has("validFrom") || sch.has("validUntil");
    }

    /**
     * 促销此刻是否生效。**必须本地推算**：enabled 只表示活动启用，是否正在打折由
     * schedule 决定（实测 glm-5.2 有两条互补活动：夜间 23:00–7:50 打折、白天恢复原价，
     * 不看时段就会全天显示夜间折扣价）。
     * 跨零点窗口（23:00–7:50）按「start<=end 区间内 / 否则取反」判。
     */
    public static boolean promotionActiveNow(JSONObject promo, long nowMs) {
        JSONObject sch = promo == null ? null : promo.optJSONObject("schedule");
        if (sch == null) return true;
        Double from = parseIsoMs(sch.optString("validFrom", ""));
        Double until = parseIsoMs(sch.optString("validUntil", ""));
        if (from != null && nowMs < from) return false;
        if (until != null && nowMs >= until) return false;
        JSONArray daily = sch.optJSONArray("daily");
        if (daily == null || daily.length() == 0) return true;
        Integer minutes = zonedMinutes(sch.optString("timezone", ""), nowMs);
        // 时区不可解析时不误杀：宁可多显示一次折扣，也不要让活动凭空消失
        if (minutes == null) return true;
        for (int i = 0; i < daily.length(); i++) {
            JSONObject slot = daily.optJSONObject(i);
            if (slot == null) continue;
            Integer start = parseHHMM(slot.optString("start", ""));
            Integer end = parseHHMM(slot.optString("end", ""));
            if (start == null || end == null) continue;
            if (start <= end ? minutes >= start && minutes < end : minutes >= start || minutes < end) {
                return true;
            }
        }
        return false;
    }

    /** 解析 ISO8601 时间（2026-10-12T00:00:00Z / 带 +08:00 偏移）为毫秒；非法返回 null。 */
    private static Double parseIsoMs(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return (double) Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return (double) OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** HH:MM（容忍不补零，如 7:50）→ 当日分钟数；非法返回 null。 */
    private static Integer parseHHMM(String text) {
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(text.trim());
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        return h < 24 && min < 60 ? h * 60 + min : null;
    }

    /** 指定 IANA 时区的「当前墙上时间」当日分钟数；时区非法返回 null（调用方不误杀）。 */
    private static Integer zonedMinutes(String zone, long nowMs) {
        ZoneId id;
        try {
            id = (zone == null || zone.trim().isEmpty())
                    ? ZoneId.of("Asia/Shanghai") : ZoneId.of(zone.trim());
        } catch (Exception e) {
            return null;
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), id);
        return now.getHour() * 60 + now.getMinute();
    }

    // ================= TRAE：display_contact_config（字符串 JSON，二次 parse） =================

    /**
     * TRAE 条目倍率：{@code display_contact_config} 里的 {@code consumption_rate.data.rate}
     * 与活动折扣。对齐参考 trae.ts 的 readConsumptionRate / readActivityDiscount：
     * <ul>
     *   <li>{@code consumption_rate.enable !== false} 且 {@code data.rate} 是有限非负数；
     *       ⚠️ {@code rate: 0} 是合法值（免费），不能用 &gt;0 过滤 —— 经典坑；</li>
     *   <li>活动折扣只在**真降价**时显示原价：{@code discount_type != "none"}（off_peak 型
     *       是 {@code "none"} + discount:100，照显会出 x0.13→x0.13 假折扣）、
     *       {@code before_consumption_rate > after}、{@code end_at} 未过期；</li>
     *   <li>解析不出返回 null，**不编造倍率**。</li>
     * </ul>
     * 形态：常态 {@code x0.08}；活动期 {@code x0.80→x0.08}。
     */
    public static String traeRate(JSONObject entry) {
        if (entry == null) return null;
        String raw = entry.optString("display_contact_config",
                entry.optString("DisplayContactConfig", ""));
        if (raw.isEmpty()) return null;
        JSONObject cfg;
        try {
            cfg = new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
        long nowSec = nowMs() / 1000L;
        // 1) 当前生效倍率
        Double cur = null;
        JSONObject cr = cfg.optJSONObject("consumption_rate");
        if (cr == null) cr = cfg.optJSONObject("ConsumptionRate");
        if (cr != null && cr.optBoolean("enable", true)) {
            JSONObject d = cr.optJSONObject("data");
            if (d == null) d = cr.optJSONObject("Data");
            if (d != null) {
                double v = d.optDouble("rate", d.optDouble("Rate", Double.NaN));
                if (!Double.isNaN(v) && v >= 0) cur = v;
            }
        }
        // 2) 活动原价（只在真降价且未过期时给出）
        Double before = null;
        JSONObject ad = cfg.optJSONObject("activity_discount");
        if (ad == null) ad = cfg.optJSONObject("ActivityDiscount");
        if (ad != null && ad.optBoolean("enable", true)) {
            JSONObject d = ad.optJSONObject("data");
            if (d == null) d = ad.optJSONObject("Data");
            if (d != null) {
                JSONObject cu = d.optJSONObject("current");
                if (cu == null) cu = d.optJSONObject("Current");
                if (cu != null) {
                    String type = cu.optString("discount_type",
                            cu.optString("discountType", "")).trim().toLowerCase();
                    if (!type.isEmpty() && !"none".equals(type)) {
                        double b = cu.optDouble("before_consumption_rate",
                                cu.optDouble("beforeConsumptionRate", Double.NaN));
                        double a = cu.optDouble("consumption_rate",
                                cu.optDouble("consumptionRate", Double.NaN));
                        // 必须是真的降价：before 有值、为正、且严格大于 after
                        if (!Double.isNaN(b) && b > 0 && (Double.isNaN(a) || b > a)
                                && !discountExpired(d, nowSec)) {
                            before = b;
                        }
                    }
                }
            }
        }
        if (cur == null) return null; // 无当前倍率不显示（与参考一致：原价单独存在也不拼）
        if (before != null && before > cur) {
            return "x" + trimNum(before) + "→x" + trimNum(cur);
        }
        return "x" + trimNum(cur);
    }

    /**
     * 活动截止检查：取 data 下任意带 {@code end_at}（Unix 秒）的子对象（limited 型，
     * 未来的新活动类型也认）。已过期 → 整个折扣视为不存在，否则用户会按折扣价预期、
     * 实际被按原价计费。没有 end_at → 永不过期。
     */
    private static boolean discountExpired(JSONObject data, long nowSec) {
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            JSONObject v = data.optJSONObject(keys.next());
            if (v == null || !v.has("end_at")) continue;
            long ea = v.optLong("end_at", 0);
            if (ea > 0 && nowSec >= ea) return true;
        }
        return false;
    }

    // ================= 小浣熊：billing_* 数值字段 =================

    /**
     * 小浣熊条目倍率（远端 billing_* 字段，对齐参考 raccoon.ts RaccoonModelMeta）：
     * {@code billing_effective_multiplier}=当前生效价，{@code billing_multiplier}=原价。
     * eff==0 免费；eff&lt;base 真降价才显示箭头；**1 倍也要显示** —— 用户要能区分
     * 「它就是 x1」与「我们没取到倍率」。
     */
    public static String raccoonRate(JSONObject model) {
        if (model == null) return null;
        double eff = model.optDouble("billing_effective_multiplier", Double.NaN);
        double base = model.optDouble("billing_multiplier", Double.NaN);
        if (!Double.isNaN(eff)) {
            if (eff == 0) return "x0";
            if (!Double.isNaN(base) && base > eff) {
                return "x" + trimNum(base) + "→x" + trimNum(eff);
            }
            return "x" + trimNum(eff);
        }
        if ("limited_free".equals(model.optString("billing_status", ""))) return "x0";
        return null;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }
}
