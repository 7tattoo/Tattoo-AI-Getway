package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把上游私有 SSE 方言翻译成标准 OpenAI chunk。
 * TRAE 的 llm_utils_chat 每帧可能同时携带 reasoning_content / response / tool_calls
 * （模型"决定调工具"的思考与 tool_calls 就在同一帧），必须拆成多个 chunk 各自转发，
 * 任何一个字段都不能因为同帧有其他字段而被丢弃。
 * 帧类型判别：优先 event: 行；event 行缺失/未知时按帧内 "type" 字段（对齐参考实现）。
 */
interface SseTranslator {
    /**
     * @param eventName 事件名（空表示无 event: 行）
     * @param data      data 行内容
     * @return 标准 OpenAI chunk 的 JSON 字符串列表（一帧可产出多个 chunk）；
     *         空列表表示该事件应被丢弃
     */
    List<String> translate(String eventName, String data, String model);

    /**
     * 写响应头之前的**预读**（默认不预读）。
     *
     * <p>有些上游把业务错误放在 HTTP 200 的 SSE 信封里，
     * 不在写头之前读一帧就永远只能给客户端一个「200 空流」。实现方可以在这里
     * 读若干行、遇到业务错误直接抛 {@link UpstreamHttpException}；
     * **已经读掉的帧必须原样返回**，PreparedChat 会按顺序回放给翻译流程。
     *
     * @return 需要回放的原始帧（{eventName, data}）；null 表示无需回放
     */
    default List<String[]> prelude(BufferedReader reader) throws IOException {
        return null;
    }

    /**
     * 诊断摘要写进网关日志时用的 tag（默认 TRAE —— 历史日志格式不变）。
     */
    default String logTag() {
        return "TRAE-SSE";
    }

    /**
     * 流结束时的诊断摘要。只在"空流"（零内容 chunk 且零 tool_calls）时返回非空，
     * 避免成功流刷日志。由 PreparedChat 在流结束时写入网关日志。
     */
    default String diagSummary() {
        return null;
    }

    /**
     * 流自然结束（上游没发 done 帧）时的收尾 chunk；返回 null 表示不需要。
     * TRAE 的工具流不发 done 帧，客户端若靠 finish_reason 收尾就会停在半路。
     */
    default String terminalChunk() {
        return null;
    }

    /**
     * 流结束时可产出**多个**收尾 chunk（默认退回 terminalChunk 的单 chunk）。
     * 智谱文本工具桥需要：可选的正文 chunk + tool_calls chunk + finish_reason chunk。
     */
    default List<String> terminalChunks() {
        String s = terminalChunk();
        if (s == null) return java.util.Collections.emptyList();
        return java.util.Collections.singletonList(s);
    }

    /**
     * true = 即使中途一帧都没写出（正文被缓冲到流结束），也必须走 terminalChunks。
     * 缓冲型 translator（智谱文本工具桥）需要它，否则会被误判成「空流」。
     */
    default boolean terminalAlways() {
        return false;
    }


    /** TRAE 方言实现。 */
    final class Trae implements SseTranslator {
        private final String modelId;
        // —— 诊断计数（每次请求一个 Trae 实例；vc54）——
        private int respChars, reasonChars, toolCallCount;
        private String finishReason = "";
        private String errFrame = "";
        private final StringBuilder rawTail = new StringBuilder();

        Trae(String modelId) {
            this.modelId = modelId == null ? "" : modelId;
        }

        @Override
        public List<String> translate(String eventName, String data, String model) {
            String ev = eventName == null ? "" : eventName.trim();
            String d = data == null ? "" : data.trim();
            if (d.isEmpty()) return Collections.emptyList();
            if (rawTail.length() > 600) rawTail.delete(0, rawTail.length() - 350);
            rawTail.append(ev.isEmpty() ? "-" : ev).append('|')
                    .append(d.length() > 240 ? d.substring(0, 240) + "..." : d).append('\n');
            // progress_notice 的 data 实测是 Processing_* / ;Processing_* 裸字符串，
            // 不是 JSON。它是上游进度提示，绝不能原样冒充 OpenAI chunk 发给客户端。
            boolean known = ev.equals("output") || ev.equals("token_usage") || ev.equals("error")
                    || ev.equals("done") || ev.equals("metadata") || ev.equals("progress_notice")
                    || ev.equals("timing_cost") || ev.equals("extra_info");
            if (!known) {
                // 无 event 行时兼容帧内 type；未知 SOLO 控制事件直接忽略。
                // 仅明确的 OpenAI chunk / [DONE] 可以原样放行，裸字符串不可放行。
                if (d.equals("[DONE]")) return Collections.singletonList(data);
                String type = null;
                boolean openAiChunk = false;
                try {
                    JSONObject frame = new JSONObject(d);
                    type = frame.optString("type", "");
                    openAiChunk = frame.optJSONArray("choices") != null;
                } catch (Exception ignored) {
                }
                if (type != null && (type.equals("output") || type.equals("done") || type.equals("error"))) {
                    ev = type;
                } else if (openAiChunk) {
                    return Collections.singletonList(data);
                } else {
                    return Collections.emptyList();
                }
            }
            if (ev.equals("token_usage") || ev.equals("metadata") || ev.equals("progress_notice")
                    || ev.equals("timing_cost") || ev.equals("extra_info")) {
                return Collections.emptyList();
            }
            // event: done —— 必须转成 finish_reason，否则客户端判定"空响应"
            if (ev.equals("done")) {
                String reason = "stop";
                try {
                    String r = new JSONObject(d).optString("finish_reason", "");
                    if (!r.isEmpty()) reason = r;
                } catch (Exception ignored) {
                }
                finishReason = reason;
                try {
                    JSONObject o2 = new JSONObject();
                    o2.put("object", "chat.completion.chunk");
                    o2.put("model", modelId);
                    JSONArray ch = new JSONArray();
                    JSONObject c = new JSONObject();
                    c.put("index", 0);
                    c.put("delta", new JSONObject());
                    c.put("finish_reason", reason);
                    ch.put(c);
                    o2.put("choices", ch);
                    return Collections.singletonList(o2.toString());
                } catch (Exception e) {
                    return Collections.emptyList();
                }
            }
            if (ev.equals("error")) {
                String msg = d;
                String code = "upstream_error";
                try {
                    JSONObject j = new JSONObject(d);
                    if (j.optString("message", "").isEmpty()) return Collections.emptyList();
                    msg = j.optString("message");
                    int c = j.optInt("code", 0);
                    if (c != 0) code = "upstream_code_" + c;
                } catch (Exception ignored) {
                }
                errFrame = code + ":" + (msg.length() > 120 ? msg.substring(0, 120) : msg);
                return Collections.singletonList(chunk("{\"error\":{\"message\":" + quote(msg)
                        + ",\"type\":" + quote(code) + "}}"));
            }
            // event: output —— 一帧可能同时带 reasoning/text/tool_calls，各自独立转发
            String text = "";
            String reasoning = "";
            try {
                JSONObject j = new JSONObject(d);
                text = j.optString("response", "");
                reasoning = j.optString("reasoning_content", "");
            } catch (Exception e) {
                text = d;
            }
            List<String> out = new ArrayList<>(3);
            if (!reasoning.isEmpty()) {
                out.add("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":"
                        + quote(reasoning) + "}}]}");
            }
            if (!text.isEmpty()) {
                out.add("{\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                        + quote(text) + "}}]}");
            }
            JSONArray tools = extractTools(d);
            respChars += text.length();
            reasonChars += reasoning.length();
            if (tools != null) toolCallCount += tools.length();
            if (tools != null && tools.length() > 0) {
                out.add("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                        + tools.toString() + "}}]}");
            }
            if (out.isEmpty()) return Collections.emptyList();
            List<String> res = new ArrayList<>(out.size());
            for (String inner : out) {
                String c = chunk(inner);
                if (c != null) res.add(c);
            }
            return res;
        }

        /**
         * 流自然结束时的收尾 chunk（上游不发 done 帧时补）。
         * 有工具调用 → finish_reason=tool_calls；否则 stop。
         */
        @Override
        public String terminalChunk() {
            if (!finishReason.isEmpty()) return null;
            String reason = toolCallCount > 0 ? "tool_calls" : "stop";
            finishReason = reason;
            try {
                JSONObject o = new JSONObject();
                o.put("object", "chat.completion.chunk");
                o.put("model", modelId);
                JSONArray ch = new JSONArray();
                JSONObject c = new JSONObject();
                c.put("index", 0);
                c.put("delta", new JSONObject());
                c.put("finish_reason", reason);
                ch.put(c);
                o.put("choices", ch);
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }

        /** 诊断：空流给全摘要（含尾巴）；工具轮/有丢弃给一行摘要（真机验证用）。 */
        @Override
        public String diagSummary() {
            if (toolCallCount > 0 || droppedToolElems > 0) {
                return "tools=" + toolCallCount
                        + (droppedToolElems > 0 ? " dropped=" + droppedToolElems : "")
                        + " resp=" + respChars + " reason=" + reasonChars
                        + " finish=[" + finishReason + "]"
                        + (errFrame.isEmpty() ? "" : " err=" + errFrame);
            }
            if (respChars > 0 || reasonChars > 0) return null;
            String tail = rawTail.length() == 0 ? "(zero-frames)"
                    : rawTail.toString().replace("\n", " || ");
            return "reason=[" + finishReason + "]"
                    + (errFrame.isEmpty() ? "" : " err=" + errFrame)
                    + " tail=" + tail;
        }

        private static int droppedToolElems = 0;

        private static JSONArray extractTools(String d) {
            try {
                JSONArray a = new JSONObject(d).optJSONArray("tool_calls");
                if (a == null || a.length() == 0) return null;
                JSONArray out = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.getJSONObject(i);
                    // 对拍参考成品 d2/A（响应侧逐帧转发器）：tool_call 元素可能用
                    // "function_call" 包裹（与请求侧 assistant 历史同款），必须改名
                    // "function" 再转发；两个都没有才丢弃（vc55 修的就是这里——
                    // vc53 只认 "function"，上游用 function_call 时全丢，
                    // 表现为"调工具的回合正文/工具全消失"）。
                    JSONObject f = t.optJSONObject("function");
                    if (f == null) f = t.optJSONObject("function_call");
                    if (f == null) {
                        droppedToolElems++;
                        continue;
                    }
                    // 参考成品同样剥掉这两个上游私有字段
                    f.remove("namespace");
                    f.remove("partial_arguments");
                    // 必须 put 真 JSONObject：org.json 的 JSONArray.put(String) 会把
                    // 字符串当**转义后的 JSON 字符串元素**塞进数组（["{\"index\":0,...}"]），
                    // 客户端反序列化 tool_calls[0] 时报
                    //   Unknown error: Value {"index":0,"id":"call_…","type":"function",…}
                    // 这个 bug 从 vc53 就潜伏（元素在 function_call 分支被丢弃所以没暴露）。
                    JSONObject el = new JSONObject();
                    el.put("index", t.optInt("index", i));
                    // 参考成品 id 缺省时不带字段（发空串可能干扰客户端按 id 累积）
                    String id = t.optString("id", "");
                    if (!id.isEmpty()) el.put("id", id);
                    String tp = t.optString("type", "");
                    el.put("type", tp.isEmpty() ? "function" : tp);
                    JSONObject fn = new JSONObject();
                    fn.put("name", f.optString("name", ""));
                    Object arg = f.opt("arguments");
                    if (arg instanceof String) {
                        fn.put("arguments", (String) arg);
                    } else if (arg == null || arg == JSONObject.NULL) {
                        fn.put("arguments", "");
                    } else {
                        // 上游万一发对象型 arguments（optString 会把它 toString 成
                        // "{...}" 塞进去正好是对的，这里显式序列化避免双重转义）
                        fn.put("arguments", arg.toString());
                    }
                    el.put("function", fn);
                    out.put(el);
                }
                return out.length() == 0 ? null : out;
            } catch (Exception e) {
                return null;
            }
        }

        private String chunk(String inner) {
            try {
                JSONObject o = new JSONObject();
                o.put("object", "chat.completion.chunk");
                o.put("model", modelId);
                o.put("choices", new JSONObject(inner).getJSONArray("choices"));
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }

        private static String quote(String s) {
            try {
                return JSONObject.quote(s == null ? "" : s);
            } catch (Exception e) {
                return "\"\"";
            }
        }
    }
}
