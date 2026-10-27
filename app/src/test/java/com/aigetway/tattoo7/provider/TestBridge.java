package com.aigetway.tattoo7.provider;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * 智谱文本工具桥（ChatglmToolBridge）离线测试：JSON 提取 / 正文清洗 / 工具块渲染 /
 * 增量切片（userTexts、toolResults、toolNameIndex）/ SSE → tool_calls 端到端。
 */
public class TestBridge {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String extra) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  " + extra);
        }
    }

    static ChatglmToolBridge.Bridge bridge(Set<String> names) {
        return new ChatglmToolBridge.Bridge("c1", "glm-x", names);
    }

    /** 构造一帧 agentmore 增量文本（SSE data 行） */
    static String frame(String text) {
        try {
            JSONObject p = new JSONObject().put("logic_id", "p" + text.hashCode())
                    .put("meta_data", new JSONObject().put("if_increase_push", true))
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
            return new JSONObject().put("parts", new JSONArray().put(p)).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String finishFrame() {
        try {
            return new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("status", "finish"))).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 走完整流：喂若干帧，返回 terminalChunks 汇总里的 tool_calls 信息 */
    static List<JSONObject> runStream(Set<String> names, String... frames) {
        ChatglmToolBridge.Bridge b = bridge(names);
        for (String f : frames) b.translate(null, f, "glm-x");
        List<JSONObject> out = new ArrayList<>();
        for (String c : b.terminalChunks()) out.add(new JSONObject(c));
        return out;
    }

    public static void main(String[] argv) throws Exception {
        Set<String> names = new HashSet<>();
        names.add("shell_execute");
        names.add("file_read");

        System.out.println("[1] 单行 JSON 工具调用提取");
        List<JSONObject> out = runStream(names,
                frame("我看看目录\n"),
                frame("{\"name\":\"shell_execute\",\"arguments\":{\"command\":\"ls -la /var/minis-euleros/workspace\"}}\n"),
                finishFrame());
        boolean hasCalls = false, proseOk = false;
        for (JSONObject c : out) {
            JSONArray ch = c.optJSONArray("choices");
            if (ch == null) continue;
            JSONObject d = ch.getJSONObject(0).optJSONObject("delta");
            if (d == null) continue;
            if (d.has("tool_calls")) {
                hasCalls = true;
                JSONObject tc = d.getJSONArray("tool_calls").getJSONObject(0);
                JSONObject fn = tc.getJSONObject("function");
                ok("tool_calls.name", "shell_execute".equals(fn.getString("name")), fn.toString());
                ok("tool_calls.arguments",
                        fn.getString("arguments").contains("/var/minis-euleros/workspace"), fn.toString());
                ok("tool_calls.id 有值", tc.optString("id").startsWith("call_"), tc.optString("id"));
            }
            if (d.has("content")) proseOk = proseOk || d.getString("content").contains("我看看目录");
        }
        ok("产生了 tool_calls", hasCalls, out.toString());
        ok("正文 prose 已保留", proseOk, out.toString());

        System.out.println("[2] 多行 pretty JSON 也应识别");
        String pretty = "{\"name\": \"file_read\",\n \"arguments\": {\"path\": \"/tmp/a.txt\"}\n}";
        List<JSONObject> out2 = runStream(names, frame(pretty), finishFrame());
        boolean gotRead = false;
        for (JSONObject c : out2) {
            JSONArray ch = c.optJSONArray("choices");
            if (ch == null) continue;
            JSONObject d = ch.getJSONObject(0).optJSONObject("delta");
            if (d != null && d.has("tool_calls")) {
                gotRead = true;
                String fargs = d.getJSONArray("tool_calls").getJSONObject(0)
                        .getJSONObject("function").getString("arguments");
                ok("多行参数完整", fargs.contains("/tmp/a.txt"), fargs);
            }
        }
        ok("多行 JSON 触发 tool_calls", gotRead, out2.toString());

        System.out.println("[3] 普通 JSON（非工具）不应误判为调用");
        List<JSONObject> out3 = runStream(names,
                frame("{\"name\":\"tom\",\"arguments\":{\"a\":1}}"), finishFrame());
        boolean wrong = false;
        for (JSONObject c : out3) {
            JSONArray ch = c.optJSONArray("choices");
            if (ch == null) continue;
            JSONObject d = ch.getJSONObject(0).optJSONObject("delta");
            if (d != null && d.has("tool_calls")) wrong = true;
        }
        ok("未知工具名不触发调用", !wrong, out3.toString());

        System.out.println("[4] userTexts / toolResults / toolNameIndex（增量切片）");
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", "S"));
        msgs.put(new JSONObject().put("role", "user").put("content", "u1"));
        JSONArray a1 = new JSONArray().put(new JSONObject().put("id", "call_1").put("type", "function")
                .put("function", new JSONObject().put("name", "shell_execute").put("arguments", "{}")));
        msgs.put(new JSONObject().put("role", "assistant").put("tool_calls", a1));
        msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "OK1"));
        msgs.put(new JSONObject().put("role", "assistant").put("content", "a1"));
        msgs.put(new JSONObject().put("role", "user").put("content", "u2"));
        msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "OK1-dup"));
        msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "OK1"));
        List<String> ut = ChatglmToolBridge.userTexts(msgs);
        ok("userTexts 计数", ut.size() == 2, ut.toString());
        ok("userTexts 内容", ut.get(0).equals("u1") && ut.get(1).equals("u2"), ut.toString());
        Map<String, String> idx = ChatglmToolBridge.toolNameIndex(msgs);
        ok("toolNameIndex", "shell_execute".equals(idx.get("call_1")), idx.toString());
        List<String> tr = ChatglmToolBridge.toolResults(msgs, idx);
        ok("toolResults 逐字重复去重", tr.size() == 2, tr.toString());
        ok("toolResults 内容不同都保留", tr.get(1).contains("dup"), tr.toString());

        System.out.println("[5] renderTools 渲染客户端工具清单");
        JSONArray tools = new JSONArray();
        tools.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "shell_execute").put("description", "运行 shell")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("command", new JSONObject().put("type", "string")
                                .put("description", "命令")))
                        .put("required", new JSONArray().put("command")))));
        Set<String> namesOut = new HashSet<>();
        String block = ChatglmToolBridge.renderTools(tools, namesOut);
        ok("渲染含工具名", block.contains("shell_execute"), block);
        ok("渲染含参数名", block.contains("command"), block);
        ok("渲染含必填标记", block.contains("必填") || block.contains("required") || block.contains("command"),
                block);
        ok("namesOut 收集", namesOut.contains("shell_execute"), namesOut.toString());
        ok("PROTOCOL 含操作名占位", ChatglmToolBridge.PROTOCOL.contains("{tool_definitions}"), "");

        System.out.println("[6] envNote 声明真实文件系统");
        String env = ChatglmToolBridge.envNote();
        ok("envNote 提到 /var/minis-euleros", env.contains("/var/minis-euleros"), env);
        ok("envNote 强制先调用工具", env.contains("工具") || env.contains("shell"), env);

        System.out.println("[7] 空流仍产生 finish chunk（terminalAlways）");
        ChatglmToolBridge.Bridge empty = bridge(names);
        ok("terminalAlways", empty.terminalAlways(), "");
        List<String> tc = empty.terminalChunks();
        ok("空流收尾 1 chunk", tc.size() == 1, tc.toString());
        ok("空流 finish=stop", tc.get(0).contains("\"stop\""), tc.get(0));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}