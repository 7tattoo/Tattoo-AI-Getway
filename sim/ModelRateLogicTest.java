package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** ModelRate 全部倍率解析逻辑的纯逻辑自测（org.json 用 tools/json-20240303.jar，与 Android 同源）。 */
public class ModelRateLogicTest {

    static int pass = 0, fail = 0;

    static void check(String name, Object actual, Object expected) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "  actual=" + actual + " expected=" + expected); }
    }

    static long shanghai(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 16, hour, minute, 0, 0, ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
    }

    public static void main(String[] args) {
        // ---- normalize：前后缀两种写法 + 单位后缀 ----
        check("norm x0.29", ModelRate.normalize("x0.29"), "x0.29");
        check("norm credits后缀", ModelRate.normalize("x0.03 credits"), "x0.03");
        check("norm 0.50x", ModelRate.normalize("0.50x"), "x0.5");
        check("norm 0x", ModelRate.normalize("0x"), "x0");
        check("norm 大写X", ModelRate.normalize("X1.5"), "x1.5");
        check("norm 空", ModelRate.normalize(""), null);
        check("norm null", ModelRate.normalize(null), null);
        check("norm 非法", ModelRate.normalize("abc"), null);

        // ---- trimNum：浮点去噪 ----
        check("trim 1.0", ModelRate.trimNum(1.0), "1");
        check("trim 0.08", ModelRate.trimNum(0.08), "0.08");
        check("trim 浮点噪声", ModelRate.trimNum(0.07999999), "0.08");
        check("trim 12", ModelRate.trimNum(12.0), "12");
        check("trim 0.75", ModelRate.trimNum(0.75), "0.75");

        // ---- compose：促销箭头只在真降价时出现 ----
        check("compose 促销", ModelRate.compose("x0.29", "x0"), "x0.29→x0");
        check("compose 无促销", ModelRate.compose("x0.29", null), "x0.29");
        check("compose 只有促销", ModelRate.compose(null, "x0"), "x0");
        check("compose 等价", ModelRate.compose("x1", "x1"), "x1");
        check("compose 升价不出箭头", ModelRate.compose("x0.17", "x0.5"), "x0.17");
        check("compose 双脏", ModelRate.compose(null, null), null);

        // ---- splitRateSuffix：Loomy 展示名（全/半角括号 + 规范化形态） ----
        check("split 全角括号", ModelRate.splitRateSuffix("MiniMax M3 （x4.0）"), "x4.0");
        check("split 半角括号", ModelRate.splitRateSuffix("Qwen 3.8 Max (x12.0)"), "x12.0");
        check("split 无空格括号", ModelRate.splitRateSuffix("GLM 5.3 Flash(x0.8)"), "x0.8");
        check("split 规范化", ModelRate.splitRateSuffix("GLM-5.3 · x0.8"), "x0.8");
        check("split 无倍率", ModelRate.splitRateSuffix("Kimi K2.6"), null);
        check("split 整名是括号", ModelRate.splitRateSuffix("(x4.0)"), null);
        check("split null", ModelRate.splitRateSuffix(null), null);

        // ---- activePromotions / promotionActiveNow：促销窗口判活 ----
        // 1) 无 schedule：始终生效
        JSONObject p1 = new JSONObject("{\"enabled\":true,\"priority\":100,"
                + "\"discount\":{\"discountedCredits\":\"0.50x\",\"factor\":0.5},"
                + "\"modelIds\":[\"glm-5.2\"]}");
        JSONObject root1 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p1 + "]}}");
        check("promo 无窗生效", ModelRate.activePromotions(root1, shanghai(12, 0)).get("glm-5.2"), "x0.5");

        // 2) 夜间 23:00–7:50 窗口：凌晨生效、中午不生效（跨零点 + 不补零小时）
        JSONObject p2 = new JSONObject("{\"enabled\":true,\"priority\":100,"
                + "\"discount\":{\"discountedCredits\":\"0.50x\",\"factor\":0.5},"
                + "\"schedule\":{\"daily\":[{\"start\":\"23:00\",\"end\":\"7:50\"}],\"timezone\":\"Asia/Shanghai\"},"
                + "\"modelIds\":[\"glm-5.2\"]}");
        JSONObject root2 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p2 + "]}}");
        check("promo 夜间生效", ModelRate.activePromotions(root2, shanghai(2, 0)).get("glm-5.2"), "x0.5");
        check("promo 白天失效", ModelRate.activePromotions(root2, shanghai(12, 0)).get("glm-5.2"), null);
        check("promo 临界7:49生效", ModelRate.promotionActiveNow(p2, shanghai(7, 49)), true);
        check("promo 临界7:50失效", ModelRate.promotionActiveNow(p2, shanghai(7, 50)), false);

        // 3) factor:0 带窗 = 真限免；无窗 = 已结束占位
        JSONObject p3 = new JSONObject("{\"enabled\":true,"
                + "\"discount\":{\"discountedCredits\":\"0x\",\"factor\":0},"
                + "\"schedule\":{\"daily\":[{\"start\":\"0:00\",\"end\":\"23:59\"}],\"timezone\":\"Asia/Shanghai\"},"
                + "\"modelIds\":[\"hy4-preview\"]}");
        JSONObject root3 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p3 + "]}}");
        check("promo 限免x0", ModelRate.activePromotions(root3, shanghai(3, 0)).get("hy4-preview"), "x0");
        JSONObject p3b = new JSONObject("{\"enabled\":true,"
                + "\"discount\":{\"discountedCredits\":\"0x\",\"factor\":0},"
                + "\"modelIds\":[\"hy4-preview\"]}");
        JSONObject root3b = new JSONObject("{\"data\":{\"modelPromotions\":[" + p3b + "]}}");
        check("promo 无窗0x跳过", ModelRate.activePromotions(root3b, shanghai(3, 0)).get("hy4-preview"), null);

        // 4) enabled:false / validUntil 已过 → 不生效
        JSONObject p4 = new JSONObject("{\"enabled\":false,\"discount\":{\"discountedCredits\":\"0.5x\"},"
                + "\"modelIds\":[\"m4\"]}");
        JSONObject p5 = new JSONObject("{\"enabled\":true,\"discount\":{\"discountedCredits\":\"0.5x\"},"
                + "\"schedule\":{\"validUntil\":\"2026-10-01T00:00:00Z\"},\"modelIds\":[\"m5\"]}");
        JSONObject root45 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p4 + "," + p5 + "]}}");
        check("promo disabled跳过", ModelRate.activePromotions(root45, shanghai(12, 0)).get("m4"), null);
        check("promo 已过期跳过", ModelRate.activePromotions(root45, shanghai(12, 0)).get("m5"), null);

        // 5) 同模型多条取 priority 高者
        JSONObject p6a = new JSONObject("{\"enabled\":true,\"priority\":100,"
                + "\"discount\":{\"discountedCredits\":\"0.5x\"},\"modelIds\":[\"m6\"]}");
        JSONObject p6b = new JSONObject("{\"enabled\":true,\"priority\":200,"
                + "\"discount\":{\"discountedCredits\":\"0.8x\"},\"modelIds\":[\"m6\"]}");
        JSONObject root6 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p6a + "," + p6b + "]}}");
        check("promo priority高者胜", ModelRate.activePromotions(root6, shanghai(12, 0)).get("m6"), "x0.8");

        // 6) 时区非法 → 不误杀（视为生效）
        JSONObject p7 = new JSONObject("{\"enabled\":true,\"discount\":{\"discountedCredits\":\"0.5x\"},"
                + "\"schedule\":{\"daily\":[{\"start\":\"23:00\",\"end\":\"7:50\"}],\"timezone\":\"Mars/Olympus\"},"
                + "\"modelIds\":[\"m7\"]}");
        JSONObject root7 = new JSONObject("{\"data\":{\"modelPromotions\":[" + p7 + "]}}");
        check("promo 非法时区不误杀", ModelRate.activePromotions(root7, shanghai(12, 0)).get("m7"), "x0.5");

        // ---- buddyRate：credits + 促销组合 ----
        // （compose 保守规则：促销价 0.5 不低于原价 0.29 时不出箭头 —— 上游配对就是如此时只显示原价）
        JSONObject bm = new JSONObject("{\"id\":\"glm-5.2\",\"credits\":\"x1\"}");
        check("buddyRate 促销组合",
                ModelRate.buddyRate(bm, "glm-5.2", root1, shanghai(12, 0)), "x1→x0.5");
        JSONObject bm2 = new JSONObject("{\"id\":\"glm-5.2\",\"credits\":\"\"}");
        check("buddyRate 只有促销",
                ModelRate.buddyRate(bm2, "glm-5.2", root1, shanghai(12, 0)), "x0.5");
        JSONObject bm3 = new JSONObject("{\"id\":\"other\",\"credits\":\"\"}");
        check("buddyRate 全无 → null（不编造）",
                ModelRate.buddyRate(bm3, "other", root1, shanghai(12, 0)), null);

        // ---- traeRate：display_contact_config 字符串 JSON 二次 parse ----
        check("trae 常态",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{\\\"consumption_rate\\\":{\\\"enable\\\":true,\\\"data\\\":{\\\"rate\\\":0.08}}}\"}")),
                "x0.08");
        check("trae 免费x0",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0}}}\"}")),
                "x0");
        check("trae enable=false 不显示",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{\\\"consumption_rate\\\":{\\\"enable\\\":false,\\\"data\\\":{\\\"rate\\\":0.08}}}\"}")),
                null);
        // 活动期：before 0.8 → 现在 0.08（注意活动 JSON 主体不要再包一层大括号）
        String traeAct = "\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0.08}},"
                + "\\\"activity_discount\\\":{\\\"enable\\\":true,\\\"data\\\":{"
                + "\\\"current\\\":{\\\"discount_type\\\":\\\"limited\\\",\\\"before_consumption_rate\\\":0.8,\\\"consumption_rate\\\":0.08},"
                + "\\\"end\\\":{\\\"end_at\\\":9999999999}}}";
        check("trae 活动期箭头",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{" + traeAct + "}\"}")),
                "x0.8→x0.08");
        // discount_type none（off_peak 型）→ 不出箭头
        String traeNone = "\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0.13}},"
                + "\\\"activity_discount\\\":{\\\"enable\\\":true,\\\"data\\\":{"
                + "\\\"current\\\":{\\\"discount_type\\\":\\\"none\\\",\\\"before_consumption_rate\\\":0.13,\\\"consumption_rate\\\":0.13}}}";
        check("trae off_peak不出箭头",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{" + traeNone + "}\"}")),
                "x0.13");
        // before <= after → 不出箭头
        String traeFake = "\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0.13}},"
                + "\\\"activity_discount\\\":{\\\"enable\\\":true,\\\"data\\\":{"
                + "\\\"current\\\":{\\\"discount_type\\\":\\\"limited\\\",\\\"before_consumption_rate\\\":0.13,\\\"consumption_rate\\\":0.2}}}";
        check("trae 非真降价不出箭头",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{" + traeFake + "}\"}")),
                "x0.13");
        // end_at 已过期 → 不出箭头
        String traeExpired = "\\\"consumption_rate\\\":{\\\"data\\\":{\\\"rate\\\":0.08}},"
                + "\\\"activity_discount\\\":{\\\"enable\\\":true,\\\"data\\\":{"
                + "\\\"current\\\":{\\\"discount_type\\\":\\\"limited\\\",\\\"before_consumption_rate\\\":0.8,\\\"consumption_rate\\\":0.08},"
                + "\\\"end\\\":{\\\"end_at\\\":1000000000}}}";
        check("trae 活动过期不出箭头",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"{" + traeExpired + "}\"}")),
                "x0.08");
        // 大写键形态 + 非法 JSON
        check("trae 大写键",
                ModelRate.traeRate(new JSONObject("{\"DisplayContactConfig\":\"{\\\"ConsumptionRate\\\":{\\\"Data\\\":{\\\"Rate\\\":0.08}}}\"}")),
                "x0.08");
        check("trae 非法JSON null",
                ModelRate.traeRate(new JSONObject("{\"display_contact_config\":\"not json\"}")), null);
        check("trae 缺字段 null", ModelRate.traeRate(new JSONObject("{\"id\":\"x\"}")), null);

        // ---- raccoonRate：billing_* 数值字段 ----
        check("raccoon 免费x0",
                ModelRate.raccoonRate(new JSONObject("{\"billing_effective_multiplier\":0}")), "x0");
        check("raccoon 1倍也显示",
                ModelRate.raccoonRate(new JSONObject("{\"billing_effective_multiplier\":1,\"billing_multiplier\":1}")), "x1");
        check("raccoon 降价箭头",
                ModelRate.raccoonRate(new JSONObject("{\"billing_effective_multiplier\":0.75,\"billing_multiplier\":1.5}")),
                "x1.5→x0.75");
        check("raccoon 无base",
                ModelRate.raccoonRate(new JSONObject("{\"billing_effective_multiplier\":0.2}")), "x0.2");
        check("raccoon 限免状态",
                ModelRate.raccoonRate(new JSONObject("{\"billing_status\":\"limited_free\"}")), "x0");
        check("raccoon 全无 null",
                ModelRate.raccoonRate(new JSONObject("{\"id\":\"sn-x\"}")), null);

        System.out.println("----");
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}
