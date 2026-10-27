package com.aigetway.tattoo7.catalog;

import org.json.JSONObject;

/**
 * ModelEntry.toJson 的 AG 客户端视图字段防回归（vc172）。
 *
 * <p>背景：AG 客户端只认顶层 input（模态数组）、max_tokens（输出别名）与
 * reasoning（思考档位机读视图）；这些键缺失时客户端直接按「文本-only /
 * 8K 输出 / 无档位」处理 —— 与本地兜底表 1M/128K/档位选择完全对不上。
 * 参考 openai-gateway/models.ts：efforts 为空的模型整个 reasoning 视图字段缺省，
 * 绝不发空数组（空数组会被用户误解为「没有任何档位」）。
 */
public class ModelEntryToJsonTest {
    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String extra) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " :: " + extra); }
    }

    static ModelEntry base(String id) {
        ModelEntry e = new ModelEntry("astudio", id, id);
        e.contextWindow = 1000000;
        e.maxOutput = 128000;
        e.live = true;
        return e;
    }

    public static void main(String[] argv) throws Exception {
        System.out.println("[1] 带 reasoning 档位的模型：AG 视图三键齐全");
        ModelEntry e = base("xopglm52");
        e.vision = true;
        e.reasoning = true;
        e.efforts = java.util.Arrays.asList("none", "high", "max");
        e.defaultEffort = "high";
        JSONObject o = e.toJson();
        ok("input 数组 = [text,image]",
                o.optJSONArray("input") != null && o.optJSONArray("input").length() == 2
                        && "text".equals(o.optJSONArray("input").opt(0))
                        && "image".equals(o.optJSONArray("input").opt(1)),
                String.valueOf(o.optJSONArray("input")));
        ok("max_tokens 别名 = 128000", o.optInt("max_tokens", -1) == 128000,
                String.valueOf(o.opt("max_tokens")));
        JSONObject rv = o.optJSONObject("reasoning");
        ok("reasoning 视图存在", rv != null, String.valueOf(o.opt("reasoning")));
        ok("reasoning.efforts 顺序保留 none,high,max",
                rv != null && rv.optJSONArray("efforts") != null
                        && rv.optJSONArray("efforts").length() == 3
                        && "none".equals(rv.optJSONArray("efforts").optJSONObject(0).optString("id"))
                        && "high".equals(rv.optJSONArray("efforts").optJSONObject(1).optString("id"))
                        && "max".equals(rv.optJSONArray("efforts").optJSONObject(2).optString("id")),
                rv == null ? "null" : String.valueOf(rv.optJSONArray("efforts")));
        ok("reasoning.openai_efforts 同序",
                rv != null && rv.optJSONArray("openai_efforts") != null
                        && rv.optJSONArray("openai_efforts").length() == 3
                        && "max".equals(rv.optJSONArray("openai_efforts").opt(2)),
                rv == null ? "null" : String.valueOf(rv.optJSONArray("openai_efforts")));
        ok("reasoning.default = high", rv != null && "high".equals(rv.optString("default", "")),
                rv == null ? "null" : String.valueOf(rv.opt("default")));

        System.out.println("[2] defaultEffort 不在档位集合内：不发 default 键");
        e = base("m1");
        e.reasoning = true;
        e.efforts = java.util.Arrays.asList("none", "high");
        e.defaultEffort = "turbo"; // 档位集合外，必须被丢弃
        o = e.toJson();
        rv = o.optJSONObject("reasoning");
        ok("reasoning 仍在", rv != null, "null");
        ok("default 键缺省", rv == null || !rv.has("default"),
                rv == null ? "null" : String.valueOf(rv.opt("default")));

        System.out.println("[3] 无档位模型：reasoning 退化为布尔 false（不发空对象/空数组）");
        e = base("plain");
        o = e.toJson();
        ok("reasoning = 布尔 false", Boolean.FALSE.equals(o.opt("reasoning")),
                String.valueOf(o.opt("reasoning")));
        ok("input 只有 text", o.optJSONArray("input") != null
                && o.optJSONArray("input").length() == 1
                && "text".equals(o.optJSONArray("input").opt(0)),
                String.valueOf(o.optJSONArray("input")));
        ok("max_tokens = 128000", o.optInt("max_tokens", -1) == 128000, String.valueOf(o.opt("max_tokens")));

        System.out.println("[4] ModelCaps.enrich：AStudio slug 族兜底（目录缺失 = 置 0 后推断）");
        ModelEntry c = new ModelEntry("astudio", "xopglm52", "xopglm52");
        c.contextWindow = 0; c.maxOutput = 0; // 目录路径缺失字段时的真实输入
        ModelCaps.enrich(c);
        ok("xopglm52 ctx=1000000", c.contextWindow == 1000000, String.valueOf(c.contextWindow));
        ok("xopglm52 maxOutput=128000", c.maxOutput == 128000, String.valueOf(c.maxOutput));
        ok("xopglm52 reasoning=true", c.reasoning, "false");
        c = new ModelEntry("astudio", "xopdsv4flash0731in", "DSV4F");
        c.contextWindow = 0; c.maxOutput = 0;
        ModelCaps.enrich(c);
        ok("dsv4 ctx=1000000", c.contextWindow == 1000000, String.valueOf(c.contextWindow));
        ok("dsv4 maxOutput=384000", c.maxOutput == 384000, String.valueOf(c.maxOutput));
        ok("dsv4 reasoning=true", c.reasoning, "false");
        c = new ModelEntry("astudio", "spark-x2.5", "Spark");
        c.contextWindow = 0; c.maxOutput = 0;
        ModelCaps.enrich(c);
        ok("spark-x2.5 ctx=262144", c.contextWindow == 262144, String.valueOf(c.contextWindow));
        ok("spark-x2.5 maxOutput=256000", c.maxOutput == 256000, String.valueOf(c.maxOutput));
        c = new ModelEntry("astudio", "astronclaw-auto", "Auto");
        c.contextWindow = 0; c.maxOutput = 0;
        ModelCaps.enrich(c);
        ok("astronclaw ctx=1000000", c.contextWindow == 1000000, String.valueOf(c.contextWindow));
        ok("astronclaw maxOutput=384000", c.maxOutput == 384000, String.valueOf(c.maxOutput));
        ok("astronclaw reasoning=true", c.reasoning, "false");

        System.out.println((fail == 0 ? "ALL " + pass + " passed" : fail + " FAILED") + ", 0 failed");
        if (fail > 0) System.exit(1);
    }
}
