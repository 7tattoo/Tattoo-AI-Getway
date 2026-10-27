package com.aigetway.tattoo7.provider;

import java.util.List;

/** 用真实 SseTranslator + 真实 org.json 跑 vc56 回归（org.json 的 put(String) 语义与 Android 一致）。 */
public class TraeToolSim {
    static int failed = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        // 用例1：上游真实形状 function_call 包裹 → 元素必须是 JSON 对象，不能是字符串
        SseTranslator tr = new SseTranslator.Trae("m1");
        String frame = "{\"type\":\"output\",\"reasoning_content\":\"想调工具\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function_call\":{\"name\":\"shell\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\","
                + "\"namespace\":\"skill\",\"partial_arguments\":true}}]}";
        List<String> chunks = tr.translate(null, frame, "m1");
        check("用例1a 产出 2 个 chunk（思考+工具）", chunks.size() == 2);
        String toolChunk = chunks.get(chunks.size() - 1);
        check("用例1b chunk 是合法 JSON", true);
        org.json.JSONObject o = new org.json.JSONObject(toolChunk);
        org.json.JSONArray tcs = o.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getJSONArray("tool_calls");
        check("用例1c tool_calls 数组长度 1", tcs.length() == 1);
        // 关键断言：元素是 JSONObject（org.json 的 optJSONObject 对字符串元素返回 null）
        org.json.JSONObject el = tcs.optJSONObject(0);
        check("用例1d 元素是对象而非转义字符串（vc56 修的核心 bug）", el != null);
        if (el != null) {
            check("用例1e index=0", el.optInt("index", -1) == 0);
            check("用例1f id=call_1", "call_1".equals(el.optString("id")));
            check("用例1g type=function", "function".equals(el.optString("type")));
            org.json.JSONObject fn = el.optJSONObject("function");
            check("用例1h function 是对象", fn != null);
            if (fn != null) {
                check("用例1i name=shell", "shell".equals(fn.optString("name")));
                check("用例1j arguments 原样字符串", "{\"cmd\":\"ls\"}".equals(fn.optString("arguments")));
                check("用例1k 私有字段已剥", !fn.has("namespace") && !fn.has("partial_arguments"));
            }
        }

        // 用例2：客户端按 OpenAI 标准反序列化整个 chunk 流（含 arguments 解析）
        String acc = "";
        for (String c : chunks) {
            org.json.JSONObject co = new org.json.JSONObject(c);
            org.json.JSONArray tcs2 = co.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("delta").optJSONArray("tool_calls");
            if (tcs2 == null) continue;
            for (int i = 0; i < tcs2.length(); i++) {
                org.json.JSONObject e2 = tcs2.getJSONObject(i);
                org.json.JSONObject f2 = e2.getJSONObject("function");
                acc += f2.getString("arguments");
            }
        }
        check("用例2 累积 arguments 是合法 JSON 且值正确",
                acc.equals("{\"cmd\":\"ls\"}") && new org.json.JSONObject(acc).getString("cmd").equals("ls"));

        // 用例3：arguments 是对象时序列化成字符串（不能双重转义）
        SseTranslator tr3 = new SseTranslator.Trae("m1");
        List<String> c3 = tr3.translate(null,
                "{\"type\":\"output\",\"tool_calls\":[{\"index\":0,\"id\":\"c9\","
                + "\"function_call\":{\"name\":\"t\",\"arguments\":{\"a\":1}}}]}", "m1");
        String a3 = new org.json.JSONObject(c3.get(0)).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("arguments");
        check("用例3 对象型 arguments 序列化为 {\"a\":1}", a3.equals("{\"a\":1}"));

        // 用例4：空 id 不透传、id 存在则透传
        SseTranslator tr4 = new SseTranslator.Trae("m1");
        List<String> c4 = tr4.translate(null,
                "{\"type\":\"output\",\"tool_calls\":[{\"index\":0,"
                + "\"function_call\":{\"name\":\"t\",\"arguments\":\"\"}}]}", "m1");
        org.json.JSONObject e4 = new org.json.JSONObject(c4.get(0)).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0);
        check("用例4a 空 id 不带字段", !e4.has("id"));
        check("用例4b type 默认 function", "function".equals(e4.optString("type")));

        // 用例5：收尾 chunk —— 无 done 帧时补 finish_reason=tool_calls
        SseTranslator tr5 = new SseTranslator.Trae("m1");
        tr5.translate(null, frame, "m1");
        String term = tr5.terminalChunk();
        check("用例5a 上游不发 done 时补收尾 chunk", term != null);
        if (term != null) {
            String fr = new org.json.JSONObject(term).getJSONArray("choices")
                    .getJSONObject(0).optString("finish_reason");
            check("用例5b 有工具调用 → finish_reason=tool_calls", "tool_calls".equals(fr));
        }

        // 用例6：done 帧后不再补收尾
        SseTranslator tr6 = new SseTranslator.Trae("m1");
        tr6.translate(null, "{\"type\":\"done\",\"finish_reason\":\"stop\"}", "m1");
        check("用例6 已有 done 帧不重复补", tr6.terminalChunk() == null);

        // 用例7：诊断行包含 tools/dropped
        SseTranslator tr7 = new SseTranslator.Trae("m1");
        tr7.translate(null, frame, "m1");
        String diag = tr7.diagSummary();
        check("用例7 诊断行 tools=1", diag != null && diag.contains("tools=1"));

        // 用例8：既无 function 也无 function_call 的元素丢弃计数
        SseTranslator tr8 = new SseTranslator.Trae("m1");
        tr8.translate(null, "{\"type\":\"output\",\"tool_calls\":[{\"index\":0,\"x\":1}]}", "m1");
        check("用例8 非法元素丢弃并计入诊断", tr8.diagSummary().contains("dropped=1"));

        System.out.println(failed == 0 ? "\n全部用例通过" : "\n失败 " + failed + " 个用例");
        if (failed > 0) System.exit(1);
    }
}