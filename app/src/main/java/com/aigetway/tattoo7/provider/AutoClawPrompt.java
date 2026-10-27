package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * AutoClaw 出站 system 提示词规范化（移植 agent2api {@code prompt.rs}）。
 *
 * <p>上游 2026-09-22 起给 LLM 代理加了 system 白名单（国内 / 国际版同闸）：
 * <ul>
 *   <li>system **必须**以身份句 {@code You are a personal assistant running inside OpenClaw.}
 *       开头，且带 {@code ## Tooling} 段；否则 403 pay-view / 406。</li>
 *   <li>**不得**含外来 harness 身份句（{@code You are ZCode…} / {@code You are Claude Code…}
 *       / {@code You are Codex…} / {@code You are an AI agent powered by DeepSeek Harness…}）。</li>
 * </ul>
 *
 * <p>本类**只改 system/developer 消息的正文**：给首条 system 前置身份前缀、把外来身份句
 * 改写成中性说法。不重排、不删除任何消息。
 */
final class AutoClawPrompt {

    private AutoClawPrompt() {}

    /** 上游要求的身份句（幂等判定用）。 */
    static final String IDENTITY_LINE = "You are a personal assistant running inside OpenClaw.";

    /** 出站身份前缀（官方 prompt 开头两行，逐字照抄；末尾 \n，join 时再补一个空行）。 */
    private static final String IDENTITY_PREFIX =
            "You are a personal assistant running inside OpenClaw.\n\n"
            + "## Tooling\n"
            + "Available tools are policy-filtered. Names are case-sensitive; call exactly as listed.\n";

    /**
     * 外来身份句 → 中性说法（**长的在前**，短的在前会把长匹配串切碎）。
     * 每条替换串**不含产品名**（闸门拦的就是产品名本身）。
     */
    private static final String[][] FOREIGN_IDENTITIES = {
        {"You are ZCode, an interactive coding agent", "You are an interactive coding agent"},
        {"You are a coding agent running in the Codex CLI tool", "You are a coding agent running in a terminal CLI tool"},
        {"You are a coding agent running in the Codex CLI", "You are a coding agent running in a terminal CLI"},
        {"You are running as a coding agent in the Codex CLI", "You are running as a coding agent in a terminal CLI"},
        {"You are Claude Code", "You are a coding assistant"},
        {"You are ZCode", "You are an interactive coding agent"},
        {"You are an AI agent powered by DeepSeek Harness", "You are an AI agent powered by a local coding harness"},
        {"You are Codex", "You are a coding agent"},
    };

    /** 就地规范化 body 的 messages（无 messages / 非数组时直接返回）。 */
    static void normalize(JSONObject body) {
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null) return;

        // 逐条改写外来身份句
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !isSystemRole(m)) continue;
            if (m.has("content")) {
                try {
                    m.put("content", rewriteContent(m.opt("content")));
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        // 保证首条是 system/developer 且正文以身份句开头
        if (messages.length() > 0) {
            JSONObject first = messages.optJSONObject(0);
            if (first != null && isSystemRole(first)) {
                prependPrefix(first);
            } else {
                insertSystemAtFront(body, messages);
            }
        } else {
            insertSystemAtFront(body, messages);
        }
    }

    /** 在 messages 最前插一条带身份前缀的 system 消息。 */
    private static void insertSystemAtFront(JSONObject body, JSONArray messages) {
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", IDENTITY_PREFIX);
            JSONArray rebuilt = new JSONArray();
            rebuilt.put(sys);
            for (int i = 0; i < messages.length(); i++) rebuilt.put(messages.opt(i));
            body.put("messages", rebuilt);
        } catch (org.json.JSONException ignored) {
        }
    }

    /**
     * 把身份前缀拼进这条 system 消息（对齐 prompt.rs prepend_prefix）：
     * content 是字符串就直接拼；是数组就拼进**第一个含 text 的 part**（保持数组形态）。
     * 已经以身份句开头则不动。
     */
    private static void prependPrefix(JSONObject message) {
        if (!message.has("content")) {
            try {
                message.put("content", IDENTITY_PREFIX);
            } catch (org.json.JSONException ignored) {
            }
            return;
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            String text = (String) content;
            if (!text.startsWith(IDENTITY_LINE)) {
                try {
                    message.put("content", joinPrefix(text));
                } catch (org.json.JSONException ignored) {
                }
            }
        } else if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object p = parts.opt(i);
                if (p instanceof JSONObject && ((JSONObject) p).has("text")) {
                    JSONObject part = (JSONObject) p;
                    String text = part.optString("text", "");
                    if (text.startsWith(IDENTITY_LINE)) return;
                    try {
                        part.put("text", joinPrefix(text));
                    } catch (org.json.JSONException ignored) {
                    }
                    return;
                }
            }
            // 没有任何 text part：在最前插一个带前缀的 text part
            try {
                JSONObject part = new JSONObject();
                part.put("type", "text");
                part.put("text", IDENTITY_PREFIX);
                JSONArray rebuilt = new JSONArray();
                rebuilt.put(part);
                for (int i = 0; i < parts.length(); i++) rebuilt.put(parts.opt(i));
                message.put("content", rebuilt);
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    private static boolean isSystemRole(JSONObject m) {
        String role = m.optString("role", "");
        return "system".equals(role) || "developer".equals(role);
    }

    /** 前缀 + 空行 + 正文（正文为空时只有前缀）。 */
    private static String joinPrefix(String text) {
        if (text == null || text.isEmpty()) return IDENTITY_PREFIX;
        return IDENTITY_PREFIX + "\n" + text;
    }

    /** 改写 content 里的外来身份句（字符串保持字符串，数组只改 text 块）。 */
    private static Object rewriteContent(Object content) {
        if (content instanceof String) {
            return rewriteIdentities((String) content);
        }
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            for (int i = 0; i < a.length(); i++) {
                Object it = a.opt(i);
                if (it instanceof JSONObject) {
                    JSONObject o = (JSONObject) it;
                    if ("text".equals(o.optString("type", ""))) {
                        try {
                            o.put("text", rewriteIdentities(o.optString("text", "")));
                        } catch (org.json.JSONException ignored) {
                        }
                    }
                } else if (it instanceof String) {
                    try {
                        a.put(i, rewriteIdentities((String) it));
                    } catch (org.json.JSONException ignored) {
                    }
                }
            }
            return a;
        }
        return content;
    }

    /** 忽略 ASCII 大小写地逐条替换（闸门本身大小写不敏感）。 */
    private static String rewriteIdentities(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (String[] pair : FOREIGN_IDENTITIES) {
            out = replaceIgnoreCase(out, pair[0], pair[1]);
        }
        return out;
    }

    private static String replaceIgnoreCase(String src, String find, String repl) {
        String lower = src.toLowerCase(Locale.US);
        String findLower = find.toLowerCase(Locale.US);
        int idx = lower.indexOf(findLower);
        if (idx < 0) return src;
        StringBuilder sb = new StringBuilder();
        int from = 0;
        while (idx >= 0) {
            sb.append(src, from, idx).append(repl);
            from = idx + find.length();
            idx = lower.indexOf(findLower, from);
        }
        sb.append(src.substring(from));
        return sb.toString();
    }
}
