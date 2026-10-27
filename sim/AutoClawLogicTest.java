package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

/** AutoClawPrompt / 路由前缀的纯逻辑自测（用 android.jar 里的 org.json）。 */
public class AutoClawLogicTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    /** 与 AutoClawProvider.stripRoutePrefix 同逻辑的内联副本。 */
    static String strip(String routeId, String[] prefixes) {
        String t = routeId.trim();
        for (String p : prefixes) if (t.startsWith(p)) return t.substring(p.length());
        return t;
    }

    public static void main(String[] args) throws Exception {
        // ---- stripRoutePrefix（与 AutoClawProvider 同逻辑，此处内联副本） ----
        String[] PREFIX = {"zaicoding_", "zai_"};
        check("strip zaicoding_", "glm-5.3".equals(strip("zaicoding_glm-5.3", PREFIX)));
        check("strip zai_", "glm-5.3-flash".equals(strip("zai_glm-5.3-flash", PREFIX)));
        check("strip order", "glm-5.3".equals(strip("zaicoding_glm-5.3", PREFIX)));
        check("strip none", "auto".equals(strip("auto", PREFIX)));

        // ---- normalize: 无 system → 插一条带前缀的 ----
        JSONObject b1 = new JSONObject();
        JSONArray m1 = new JSONArray();
        m1.put(new JSONObject().put("role", "user").put("content", "hi"));
        b1.put("messages", m1);
        AutoClawPrompt.normalize(b1);
        JSONArray r1 = b1.getJSONArray("messages");
        check("insert system at 0", "system".equals(r1.getJSONObject(0).optString("role")));
        check("prefix identity line",
                r1.getJSONObject(0).optString("content").startsWith(AutoClawPrompt.IDENTITY_LINE));
        check("prefix has Tooling", r1.getJSONObject(0).optString("content").contains("## Tooling"));
        check("user msg preserved", "user".equals(r1.getJSONObject(1).optString("role")));

        // ---- normalize: 已有 system 含外来身份句 → 改写 + 前置 ----
        JSONObject b2 = new JSONObject();
        JSONArray m2 = new JSONArray();
        m2.put(new JSONObject().put("role", "system")
                .put("content", "You are ZCode, an interactive coding agent. Hello."));
        b2.put("messages", m2);
        AutoClawPrompt.normalize(b2);
        String c2 = b2.getJSONArray("messages").getJSONObject(0).optString("content");
        check("foreign ZCode rewritten", !c2.contains("You are ZCode"));
        check("ZCode replaced with neutral", c2.contains("You are an interactive coding agent"));
        check("prefix prepended", c2.startsWith(AutoClawPrompt.IDENTITY_LINE));

        // ---- Claude Code ----
        JSONObject b3 = new JSONObject();
        JSONArray m3 = new JSONArray();
        m3.put(new JSONObject().put("role", "system").put("content", "You are Claude Code, Anthropic's CLI."));
        b3.put("messages", m3);
        AutoClawPrompt.normalize(b3);
        String c3 = b3.getJSONArray("messages").getJSONObject(0).optString("content");
        check("Claude Code rewritten", !c3.contains("You are Claude Code") && c3.contains("You are a coding assistant"));

        // ---- DeepSeek Harness ----
        JSONObject b4 = new JSONObject();
        JSONArray m4 = new JSONArray();
        m4.put(new JSONObject().put("role", "system").put("content", "You are an AI agent powered by DeepSeek Harness."));
        b4.put("messages", m4);
        AutoClawPrompt.normalize(b4);
        String c4 = b4.getJSONArray("messages").getJSONObject(0).optString("content");
        check("DeepSeek Harness rewritten", !c4.contains("DeepSeek Harness"));

        // ---- 幂等：再 normalize 一次不变 ----
        String before = b2.getJSONArray("messages").getJSONObject(0).optString("content");
        AutoClawPrompt.normalize(b2);
        String after = b2.getJSONArray("messages").getJSONObject(0).optString("content");
        check("idempotent", before.equals(after));

        // ---- 大小写不敏感 ----
        JSONObject b5 = new JSONObject();
        JSONArray m5 = new JSONArray();
        m5.put(new JSONObject().put("role", "system").put("content", "you are codex, based on GPT-5."));
        b5.put("messages", m5);
        AutoClawPrompt.normalize(b5);
        String c5 = b5.getJSONArray("messages").getJSONObject(0).optString("content");
        check("case-insensitive codex", !c5.toLowerCase().contains("you are codex"));

        // ---- content 为数组（多模态）：只改 text 块 ----
        JSONObject b6 = new JSONObject();
        JSONArray m6 = new JSONArray();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("type", "text").put("text", "You are Codex now"));
        parts.put(new JSONObject().put("type", "image_url").put("image_url", "x"));
        m6.put(new JSONObject().put("role", "system").put("content", parts));
        b6.put("messages", m6);
        AutoClawPrompt.normalize(b6);
        JSONArray rp = b6.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        String textBlock = rp.getJSONObject(0).optString("text");
        check("array text block rewritten", !textBlock.contains("You are Codex"));
        check("image block preserved", "image_url".equals(rp.getJSONObject(1).optString("type")));

        System.out.println("\n== " + pass + " passed / " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
