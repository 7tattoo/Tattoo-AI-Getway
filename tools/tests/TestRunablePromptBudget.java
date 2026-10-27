package com.aigetway.tattoo7.provider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 首轮 prompt 体积预算的离线断言（编译真实类，非复刻逻辑）。
 *
 * <p>真机现象：vc76 首轮发 11923B → 上游 PROMPT_LIMIT_EXCEEDED，网关重发的「瘦身版」
 * 仍是同一个超限体积，整轮失败。Minis 的 system prompt 单条就有 25KB，所以裁剪必须
 * 从「system 让位」而不是「砍用户问题」。
 *
 * 编译：javac -cp tools/json-20240303.jar -d /tmp/rbp-out \
 *   app/src/main/java/com/aigetway/tattoo7/provider/{RunableBridge,ChatglmToolBridge,SseTranslator}.java \
 *   tools/tests/TestRunablePromptBudget.java
 * 运行：java -cp /tmp/rbp-out:tools/json-20240303.jar com.aigetway.tattoo7.provider.TestRunablePromptBudget
 */
public class TestRunablePromptBudget {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(unit);
        return sb.toString();
    }

    public static void main(String[] args) {
        // 复刻 Minis 真实量级：超长英文 system + 中文工具清单 + 带图片路径的用户消息
        String system = repeat("You are Minis, a capable AI assistant running on an Android device. ", 400); // ~32KB
        org.json.JSONArray toolDefs = new org.json.JSONArray();
        for (int i = 0; i < 12; i++) {
            org.json.JSONObject props = new org.json.JSONObject();
            for (int k = 0; k < 6; k++) props.put("arg_" + i + "_" + k, "string");
            toolDefs.put(new org.json.JSONObject()
                    .put("name", "shell_execute_" + i)
                    .put("parameters", props));
        }
        String toolBlock = ChatglmToolBridge.renderTools(toolDefs, new java.util.LinkedHashSet<>());
        String env = ChatglmToolBridge.envNoteCompact() + "\n\n补充：本网关会把本地文件换传成 https 直链。";
        String user = "图片文件路径：/var/minis-euleros/attachments/uploads/1000132846.jpg\n你能收到图片吗";
        String note = RunableBridge.localPathNote(user, false);
        List<String> tools = new ArrayList<>();
        List<String> users = Arrays.asList(user);

        System.out.println("== 组成体积 ==");
        System.out.println("  system=" + system.getBytes(StandardCharsets.UTF_8).length
                + "B toolBlock=" + toolBlock.getBytes(StandardCharsets.UTF_8).length
                + "B env=" + env.getBytes(StandardCharsets.UTF_8).length
                + "B note=" + note.getBytes(StandardCharsets.UTF_8).length);

        System.out.println("== 首轮（正常模式）必须落进预算 ==");
        String p = RunableBridge.buildPrompt(env, system, toolBlock, tools, users,
                0, 0, true, false, note, null);
        int bytes = RunableBridge.utf8Len(p);
        System.out.println("  prompt=" + bytes + "B chars=" + p.length());
        ok(bytes <= 6000, "首轮 prompt " + bytes + "B ≤ 6000B");
        ok(p.length() <= 6000, "首轮字符数 ≤ 6000");
        ok(p.contains("[运行环境]"), "保留了环境声明");
        ok(p.contains("shell_execute"), "保留了工具清单");
        ok(p.contains("你能收到图片吗"), "保留了用户问题原文");
        ok(p.contains("read_image"), "保留了看图指引");
        ok(p.indexOf("环境事实") < p.indexOf("shell_execute"), "声明在工具清单之前");
        ok(p.indexOf("shell_execute") < p.indexOf("你能收到图片吗"), "用户问题在末尾（不被前置内容挤掉）");

        System.out.println("== 首轮（瘦身模式） ==");
        String ps = RunableBridge.buildPrompt(env, system, toolBlock, tools, users,
                0, 0, true, true, note, null);
        int sbytes = RunableBridge.utf8Len(ps);
        System.out.println("  slim=" + sbytes + "B chars=" + ps.length());
        ok(sbytes <= 6000, "瘦身版 " + sbytes + "B ≤ 6000B");
        ok(ps.length() <= 6000, "瘦身版字符数 ≤ 6000");
        ok(ps.contains("你能收到图片吗"), "瘦身版仍保留用户问题");
        ok(ps.contains("shell_execute"), "瘦身版仍保留工具名");

        System.out.println("== 极端：system 巨无霸 + 多条工具结果 ==");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) many.add(repeat("工具输出内容。", 400)); // ~21KB
        String pe = RunableBridge.buildPrompt(env, system, toolBlock, many, users,
                0, 0, true, false, note, null);
        int ebytes = RunableBridge.utf8Len(pe);
        System.out.println("  extreme=" + ebytes + "B chars=" + pe.length());
        ok(pe.length() <= 6000, "极端场景字符数 ≤ 6000（实际 " + pe.length() + "）");
        ok(pe.contains("你能收到图片吗"), "极端场景用户问题没被挤掉（尾部优先）");
        // 网关的最后一道闸：按 UTF-8 字节硬裁剪，绝不切断多字节字符
        String capped = RunableBridge.clipBytes(pe, 6000);
        ok(RunableBridge.utf8Len(capped) <= 6000, "clipBytes 后 ≤ 6000B（实际 "
                + RunableBridge.utf8Len(capped) + "B）");
        ok(capped.contains("[关于本轮图片]"), "clipBytes 保尾部：图片说明仍在");
        ok(capped.startsWith("…"), "clipBytes 头部补省略标记");
        ok(RunableBridge.utf8Len("中文abc") == 9, "utf8Len 计算正确（3+3+1+1+1）");
        String cjk = RunableBridge.clipBytes("中文中文", 7);
        ok("…文".equals(cjk), "clipBytes 保尾部且不切断多字节（实际 " + cjk + "）");
        ok(RunableBridge.utf8Len(cjk) <= 7, "结果真的 ≤ 上限");
        ok(RunableBridge.clipBytes("😀abc", 6).equals("…abc"), "clipBytes 不切断代理对");
        ok(RunableBridge.clipBytes("短", 100).equals("短"), "未超限时原样返回");

        System.out.println("== 收紧档位 ==");
        RunableBridge.Budget b = RunableBridge.Budget.defaults();
        for (int i = 0; i < 4; i++) {
            b = b.tighter();
            String pb2 = RunableBridge.buildPrompt(env, system, toolBlock, many, users,
                    0, 0, true, true, note, null, b);
            ok(pb2.length() <= b.total, "第 " + (i + 1) + " 档 total=" + b.total
                    + " → 实际 " + pb2.length() + " 字符");
            ok(pb2.contains("你能收到图片吗"), "第 " + (i + 1) + " 档用户问题仍在");
        }

        System.out.println("== 续轮（fresh=false）只带增量 ==");
        List<String> users2 = Arrays.asList("第一轮的问题", "第二轮的问题");
        String pc = RunableBridge.buildPrompt(env, system, toolBlock, tools, users2,
                0, 1, false, false, "", "每轮环境提示");
        ok(!pc.contains("第一轮的问题"), "续轮不重发历史用户消息");
        ok(pc.contains("第二轮的问题"), "续轮带本轮用户消息");
        ok(pc.contains("每轮环境提示"), "续轮带每轮短声明");
        ok(RunableBridge.utf8Len(pc) < 2000, "续轮体积很小（"
                + RunableBridge.utf8Len(pc) + "B）");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
