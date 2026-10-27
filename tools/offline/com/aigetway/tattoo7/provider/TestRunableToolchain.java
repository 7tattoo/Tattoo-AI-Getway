package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runable 工具链修复离线回归（直接编译真实类，不需要设备）。
 * 覆盖：rich 工具块含真实参数名、每轮工具提醒、首轮声明不重复、prompt 字节预算、标记协议解析。
 */
public class TestRunableToolchain {
    static int pass = 0, fail = 0;

    static void ok(boolean cond, String name) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL: " + name); }
    }
    static void eq(Object a, Object b, String name) {
        ok(a == null ? b == null : a.equals(b), name + " (got=" + a + " want=" + b + ")");
    }
    static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    /** Minis 真实形态的工具清单（描述 + parameters 里的真实参数名）。 */
    static JSONArray minisTools() {
        JSONArray a = new JSONArray();
        a.put(tool("shell_execute", "Execute a command in an isolated Linux process (EulerOS via PRoot). "
                + "Default timeout is 15 minutes.", "command", "delay", "timeout", "tool_title"));
        a.put(tool("ubuntu_shell", "Run a command inside the secondary Ubuntu 24.04 arm64 sandbox with "
                + "the full Ubuntu apt ecosystem.", "command", "cwd", "timeout", "tool_title"));
        a.put(tool("file_read", "Read a file from the Linux filesystem.", "path", "offset", "lines", "direction"));
        a.put(tool("file_write", "Write content to a file.", "path", "content", "append", "create_dirs"));
        a.put(tool("file_edit", "Edit a file with exact string replacement.", "path", "old_string", "new_string"));
        a.put(tool("read_image", "Read an image file and return it for visual analysis.", "path", "prompt"));
        a.put(tool("browser_use", "Control a web browser.", "action", "url", "tab_id"));
        a.put(tool("memory_write", "Write a memory entry.", "content"));
        a.put(tool("memory_get", "Recall memories.", "keywords", "scope"));
        return a;
    }
    static JSONObject tool(String name, String desc, String... params) {
        JSONObject props = new JSONObject();
        JSONArray req = new JSONArray();
        for (String p : params) {
            String type = (p.equals("timeout") || p.equals("delay") || p.equals("lines")
                    || p.equals("offset") || p.equals("amount")) ? "number" : "string";
            props.put(p, new JSONObject().put("type", type));
            req.put(p);
        }
        return new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject().put("name", name).put("description", desc)
                        .put("parameters", new JSONObject().put("type", "object")
                                .put("properties", props).put("required", req)));
    }

    static String bp(String env, String sys, String tools, List<String> res, List<String> users,
                     int fromTool, int fromUser, boolean fresh, boolean slim,
                     String tail, String head, String rem) {
        return RunableBridge.buildPrompt(env, sys, tools, res, users, fromTool, fromUser,
                fresh, slim, tail, head, RunableBridge.Budget.defaults(), rem);
    }

    public static void main(String[] args) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        String rich = ChatglmToolBridge.renderTools(minisTools(), names, true);

        // ---- A. rich 工具块：真实名字 + 描述 + 真实参数名 ----
        eq(names.size(), 9, "A1 工具数");
        ok(rich.contains("shell_execute"), "A2 含 shell_execute");
        ok(rich.contains("ubuntu_shell"), "A3 含 ubuntu_shell");
        ok(rich.contains("isolated Linux process"), "A4 含描述正文");
        ok(rich.contains("command(string!)"), "A5 含参数名 command(string!)");
        ok(rich.contains("timeout(number!)"), "A6 含参数名 timeout(number!)");
        ok(rich.indexOf("command(string!)") < rich.indexOf("delay("), "A6b required 参数排在可选前");
        ok(!rich.contains("(string!)s}") && !rich.contains(")s}"), "A6c 无占位符残留垃圾（td+16 bug）");
        ok(rich.contains("path(string!)"), "A7 含参数名 path");
        ok(rich.contains("| params:"), "A7b 参数段落标题");
        ok(rich.contains("old_string"), "A8 含参数名 old_string");
        ok(rich.contains("<<<TOOL_CALL>>>"), "A9 含标记协议");
        ok(rich.contains("arguments"), "A10 含 arguments 字段");
        ok(!rich.contains("type(string), properties(string), required"), "A11 不再泄漏 JSON Schema 骨架字段当参数名");
        ok(!rich.contains("target_index"), "A12 不泄漏别的通道的参数名");
        ok(!rich.contains("shell_execute=<") && !rich.contains("read_image=<"), "A13 不用 name= 调用样式");
        ok(bytes(rich) < 4600, "A14 rich 工具块 < 4.6KB（实测 " + bytes(rich) + "B）");
        System.out.println("   rich 工具块 " + bytes(rich) + "B / 提醒 "
                + bytes(ChatglmToolBridge.toolReminder(names)) + "B");

        // ---- B. 旧签名（ChatGLM 通道）行为不变 ----
        Set<String> n2 = new LinkedHashSet<>();
        String legacy = ChatglmToolBridge.renderTools(minisTools(), n2);
        ok(!legacy.contains("params:"), "B1 旧签名不含 params 描述");
        ok(legacy.contains("- shell_execute("), "B2 旧签名仍是 name(params) 样式");
        ok(!legacy.contains("<<<TOOL_CALL>>>"), "B3 旧签名不含标记协议");

        // ---- C. 每轮提醒 ----
        String rem = ChatglmToolBridge.toolReminder(names);
        ok(rem.contains("shell_execute") && rem.contains("ubuntu_shell") && rem.contains("read_image"),
                "C1 提醒含全部工具名");
        ok(rem.contains("<<<TOOL_CALL>>>") && rem.contains("arguments"), "C2 提醒含调用格式");
        ok(rem.contains("不是你自己的沙箱"), "C3 提醒含『不是你自己的沙箱』");
        ok(bytes(rem) < 1200, "C4 提醒 < 1.2KB（实测 " + bytes(rem) + "B）");

        // ---- D/E. buildPrompt ----
        List<String> toolRes = new ArrayList<>(Arrays.asList(
                "total 8\ndrwxr-xr-x 4 root root 4096 /var/minis-euleros"));
        List<String> users = new ArrayList<>(Arrays.asList("帮我看看工作区",
                "运行 shell 命令查看一下工作区，把结果列出来"));
        String env = "【运行环境事实（重要）】\n1) 你是 CodeArts7 网关；\n2) 用户设备文件在 /var/minis-euleros。";
        String sys = "You are an AI assistant running on an Android device with a fully functional Linux "
                + "sandbox. Reply in the language that best matches the user's input.";

        String first = bp(env, sys, rich, toolRes, users, 0, 0, true, false, "",
                "【图片已送达】", rem);
        ok(first.contains("isolated Linux process"), "E1 首轮含工具描述");
        ok(first.contains("运行环境事实"), "E2 首轮含环境声明");
        ok(first.contains("timeout(number!)"), "E3 首轮含真实参数名+类型");
        ok(!first.contains("上一轮清单仍然有效"), "E4 首轮不重复提醒");
        ok(first.trim().endsWith("运行 shell 命令查看一下工作区，把结果列出来"), "E5 首轮尾部是用户问题");
        ok(first.contains("total 8"), "E5b 首轮带本轮工具结果");

        String later = bp(env, sys, rich, toolRes, users, 0, 1, false, false, "", "", rem);
        ok(!later.contains("运行环境事实"), "D1 后续轮不再注入环境声明（避免被判 prompt injection）");
        ok(!later.contains("你是 CodeArts7 网关"), "D2 后续轮不含网关注入式措辞");
        ok(later.contains("客户端工具提醒"), "D3 后续轮含工具提醒");
        ok(later.contains("ubuntu_shell"), "D4 提醒里带真实工具名");
        ok(later.contains("arguments"), "D5 提醒里带参数格式");
        ok(later.contains("total 8"), "D6 后续轮带新增工具结果");
        ok(later.trim().endsWith("运行 shell 命令查看一下工作区，把结果列出来"), "D7 后续轮尾部是用户问题");
        ok(bytes(later) < 2500, "D8 后续轮 prompt 很小（实测 " + bytes(later) + "B）");

        // ---- F. 极端体积 ----
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append("这是一段很长的中文工具结果用于压测预算边界，包含命令输出与路径。\n");
        List<String> fatRes = new ArrayList<>();
        for (int i = 0; i < 12; i++) fatRes.add(big.toString());
        List<String> fatUsers = new ArrayList<>();
        for (int i = 0; i < 6; i++) fatUsers.add("第" + i + "个问题：" + big);
        String q = "最后一问：把 /var/minis-euleros/workspace 列出来";
        fatUsers.add(q);
        String fat = bp(env, sys + big, rich, fatRes, fatUsers, 0, 0, true, false, "", "【图片已送达】", rem);
        ok(bytes(fat) <= 9000, "F1 首轮顶格不超 9000B（实测 " + bytes(fat) + "B）");
        ok(fat.contains("shell_execute"), "F2 顶格时仍保留工具协议");
        ok(fat.trim().endsWith(q), "F3 顶格时最后一条用户问题完整保留");

        String fatLater = bp(env, sys + big, rich, fatRes, fatUsers, 0, 6, false, false, "", "", rem);
        ok(bytes(fatLater) <= 9000, "F4 后续轮顶格不超 9000B（实测 " + bytes(fatLater) + "B）");
        ok(fatLater.contains("shell_execute"), "F5 后续轮顶格仍保留工具提醒");
        ok(fatLater.trim().endsWith(q), "F6 后续轮顶格保住用户问题");

        // ---- G. 瘦身档 ----
        String slim = bp(env, sys, rich, toolRes, users, 0, 0, true, true, "", "", rem);
        ok(slim.contains("shell_execute"), "G1 瘦身仍列工具名");
        ok(slim.contains("shell_execute"), "G2 瘦身档仍列工具名");
        ok(slim.trim().endsWith("运行 shell 命令查看一下工作区，把结果列出来"), "G3 瘦身不砍用户问题");

        // ---- H. 标记协议解析 ----
        String text = "我来看一下。\n\n<<<TOOL_CALL>>>\n{\"tool\": \"shell_execute\", \"arguments\": "
                + "{\"command\": \"ls /var/minis-euleros\", \"timeout\": 60}}\n<<<END_TOOL_CALL>>>";
        Set<String> wl = new LinkedHashSet<>(Arrays.asList("shell_execute", "file_read", "read_image"));
        List<JSONObject> calls = ChatglmToolBridge.extractCalls(text, wl);
        eq(calls.size(), 1, "H1 解析出 1 个调用");
        if (!calls.isEmpty()) {
            JSONObject c0 = calls.get(0);
            eq(c0.getString("name"), "shell_execute", "H2 工具名（长名字不被截断）");
            ok(c0.getJSONObject("arguments").getString("command").contains("/var/minis-euleros"),
                    "H3 arguments 解析成 JSON 对象");
        }
        eq(ChatglmToolBridge.extractCalls("普通回答里没有标记", wl).size(), 0, "H4 无标记时不误判");
        eq(ChatglmToolBridge.extractCalls(
                "<<<TOOL_CALL>>>{\"tool\":\"unknown_tool\",\"arguments\":{}}<<<END_TOOL_CALL>>>", wl).size(), 0,
                "H5 白名单外的名字不调用");
        eq(ChatglmToolBridge.extractCalls(
                "<<<TOOL_CALL>>>\n{\"tool\":\"file_read\",\"arguments\":{\"path\":\"/a\"}}\n<<<END_TOOL_CALL>>>\n"
                        + "<<<TOOL_CALL>>>\n{\"tool\":\"read_image\",\"arguments\":{\"path\":\"/b.png\"}}\n"
                        + "<<<END_TOOL_CALL>>>", wl).size(), 2, "H6 一轮多调用");
        String unclosed = ChatglmToolBridge.CALL_START
                + "{\"name\":\"shell_execute\",\"arguments\":{\"command\":\"ls\"}"
                + ChatglmToolBridge.CALL_END;
        eq(ChatglmToolBridge.extractCalls(unclosed, wl).size(), 0, "H7 括号未闭合的坏段不误调用");
        String argsAsString = ChatglmToolBridge.CALL_START + new JSONObject()
                .put("name", "shell_execute")
                .put("arguments", new JSONObject().put("command", "ls").toString()).toString()
                + ChatglmToolBridge.CALL_END;
        List<JSONObject> strArgs = ChatglmToolBridge.extractCalls(argsAsString, wl);
        eq(strArgs.size(), 1, "H8 arguments 为 JSON 字符串时仍能调用");
        eq(strArgs.get(0).getJSONObject("arguments").optString("command"), "ls",
                "H9 arguments 字符串被二次解析（模型常这么写）");

        // ---- I. 正文剥离 ----
        String cleaned = ChatglmToolBridge.proseWithoutCalls(
                "结果如下<<<TOOL_CALL>>>\n{\"tool\":\"shell_execute\",\"arguments\":{\"command\":\"ls\"}}"
                        + "\n<<<END_TOOL_CALL>>>");
        ok(!cleaned.contains("TOOL_CALL"), "I1 标记已从正文剥离");
        ok(cleaned.contains("结果如下"), "I2 正常正文保留");

        System.out.println("\n== TestRunableToolchain: pass=" + pass + " fail=" + fail + " ==");
        if (fail > 0) System.exit(1);
    }
}
