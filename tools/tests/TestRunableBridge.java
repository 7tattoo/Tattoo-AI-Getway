package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RunableBridge 离线断言测试（真实类，非复刻逻辑）。
 * 编译：javac -cp tools/json-20240303.jar -d /tmp/rb-out \
 *   app/src/main/java/com/aigetway/tattoo7/provider/{RunableBridge,ChatglmToolBridge,SseTranslator}.java tools/tests/TestRunableBridge.java
 * 运行：java -cp /tmp/rb-out:tools/json-20240303.jar com.aigetway.tattoo7.provider.TestRunableBridge
 */
public class TestRunableBridge {

    static int pass = 0, fail = 0;
    static List<String> failures = new ArrayList<>();

    static void ok(boolean cond, String msg) {
        if (cond) pass++;
        else { fail++; failures.add(msg); }
    }

    static void eq(Object actual, Object expect, String msg) {
        boolean same = (actual == null) ? expect == null : actual.equals(expect);
        if (same) pass++;
        else { fail++; failures.add(msg + " (expect=[" + expect + "] actual=[" + actual + "])"); }
    }

    static void contains(String haystack, String needle, String msg) {
        if (haystack != null && haystack.contains(needle)) pass++;
        else { fail++; failures.add(msg + " (缺 [" + needle + "])"); }
    }

    static void notContains(String haystack, String needle, String msg) {
        if (haystack == null || !haystack.contains(needle)) pass++;
        else { fail++; failures.add(msg + " (竟含 [" + needle + "])"); }
    }

    /** 图床响应抠直链：纯文本 / JSON 转义斜杠（uguu）/ HTML 403 / 无链接。 */
    static void testExtractUrl() {
        eq(RunableBridge.extractUrl("https://litter.catbox.moe/abc.png\n"),
                "https://litter.catbox.moe/abc.png", "纯文本直链");
        String uguu = "{\n    \"success\": true,\n    \"files\": [\n        {\n"
                + "            \"url\": \"https:\\/\\/d.uguu.se\\/mRwTGBRZ.png\",\n"
                + "            \"size\": 92\n        }\n    ]\n}";
        eq(RunableBridge.extractUrl(uguu), "https://d.uguu.se/mRwTGBRZ.png",
                "JSON 转义斜杠必须还原（uguu 早先因此白传）");
        eq(RunableBridge.extractUrl("https://catbox.moe/files/x.jpg"),
                "https://catbox.moe/files/x.jpg", "catbox 文本");
        eq(RunableBridge.extractUrl("<!doctype html><title>403 | Forbidden</title>"), null,
                "403 页面里没有图片直链 → null");
        eq(RunableBridge.extractUrl("{\"error\":\"too large\"}"), null, "无链接 → null");
        eq(RunableBridge.extractUrl(null), null, "null 响应");
        eq(RunableBridge.extractUrl("http://h.uguu.se/a.jpeg"), "http://h.uguu.se/a.jpeg",
                "http 直链也认");
    }

    public static void main(String[] args) {
        testConvStable();
        testBuildPrompt();
        testDataUrl();
        testCustomHost();
        testExtractUrl();
        testLocalPaths();
        testBridgeProtocol();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        for (String f : failures) System.out.println("  ✗ " + f);
        if (fail > 0) System.exit(1);
    }

    // ---------- 会话 id 稳定 ----------
    static void testConvStable() {
        RunableBridge.Conv a = RunableBridge.convFor("k1", 1, 0);
        RunableBridge.Conv b = RunableBridge.convFor("k1", 2, 0);
        eq(a.chatId, b.chatId, "同 key 复用 chatId");
        b.bump(2, 0);
        eq(RunableBridge.convFor("k1", 2, 0).sentUsers, 2, "conv 记录推进");
        // 历史被治理折叠（计数回退）→ 必须重建会话
        ok(!RunableBridge.convFor("k1", 1, 0).chatId.equals(b.chatId), "计数回退时重建 chatId");
        RunableBridge.Conv c = RunableBridge.convFor("k2", 1, 0);
        ok(!a.chatId.equals(c.chatId), "不同 key 不同 chatId");
    }

    // ---------- prompt 组装 ----------
    static void testBuildPrompt() {
        List<String> users = new ArrayList<>();
        users.add("第一条");
        users.add("第二条");
        List<String> none = new ArrayList<>();
        String tools = "- shell_execute(command): 执行命令\n调用格式：<tool_call>{...}";

        // 首轮：声明在最前 + system + 工具 + 只发未发送的 user
        String first = RunableBridge.buildPrompt("ENVNOTE", "SYS", tools, none,
                users, 0, 0, true, false, "", "HEAD");
        ok(first.startsWith("ENVNOTE"), "system 最前是运行环境声明");
        contains(first, "SYS", "包含 system");
        contains(first, "shell_execute", "包含工具定义");
        contains(first, "第二条", "包含最后一条 user");
        contains(first, "第一条", "未发送过的历史 user 也在");
        contains(first, "HEAD", "首轮也带极简声明（紧贴用户消息）");

        // 后续轮：不重发 system/工具
        String later = RunableBridge.buildPrompt("ENVNOTE", "SYS", tools, none,
                users, 0, 1, false, false, "", "HEAD");
        notContains(later, "ENVNOTE", "后续轮不重发首轮长声明");
        contains(later, "HEAD", "后续轮仍带极简环境声明");
        ok(later.indexOf("HEAD") < later.indexOf("User: 第二条"), "极简声明紧贴用户消息之前");
        notContains(later, "shell_execute", "后续轮不重发工具");
        contains(later, "第二条", "后续轮只发新增 user");
        notContains(later, "第一条", "后续轮不重发旧 user");

        // 工具结果轮
        List<String> toolMsgs = new ArrayList<>();
        toolMsgs.add("result-A");
        String withTools = RunableBridge.buildPrompt("ENVNOTE", "SYS", tools, toolMsgs,
                users, 0, 1, false, false, "", "HEAD");
        contains(withTools, "result-A", "工具结果送上游");

        // 尾部说明必须在最末尾（离生成点最近）
        String tail = RunableBridge.buildPrompt("ENVNOTE", "SYS", "", none,
                users, 0, 0, true, false, "[图片投递说明 · 必读]\nTAIL", "HEAD");
        ok(tail.endsWith("TAIL"), "tailNote 落在 prompt 最末尾");
        ok(tail.indexOf("ENVNOTE") < tail.indexOf("TAIL"), "tail 在 system 之后");

        // 瘦身模式：工具块只留名字
        String slimmed = RunableBridge.buildPrompt("ENVNOTE", "SYS", tools, none,
                users, 0, 0, true, true, "", "HEAD");
        contains(slimmed, "shell_execute", "瘦身仍保留工具名");
        notContains(slimmed, "调用格式", "瘦身丢掉协议长文");

        // 超长裁剪不至于炸
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40000; i++) big.append("0123456789");
        List<String> bigUsers = new ArrayList<>();
        bigUsers.add(big.toString());
        String clipped = RunableBridge.buildPrompt("N", "S", "", none,
                bigUsers, 0, 0, true, false, "", "HEAD");
        ok(clipped.length() < 30000, "超长 user 文本被裁剪，len=" + clipped.length());
    }

    // ---------- data: 解析 / 直链透传 ----------
    static void testDataUrl() {
        // http 直链原样透传（曾经被 indexOf(',') 提前吞掉）
        eq(RunableBridge.hostDataUrl("https://a.com/x.png?w=1,2"), "https://a.com/x.png?w=1,2",
                "带逗号的 https 直链不被吞");
        eq(RunableBridge.hostDataUrl("http://a.com/x.png"), "http://a.com/x.png", "http 直链透传");
        eq(RunableBridge.hostDataUrl("data:image/png,notbase64"), null, "非 base64 data → null");
        eq(RunableBridge.hostDataUrl("data:image/png;base64,@@@not-base64@@@"), null, "坏 base64 → null");
        eq(RunableBridge.upload(null, null), null, "null → null");
        eq(RunableBridge.hostDataUrl("  "), null, "空白 → null");
    }

    // ---------- 自定义图床 spec ----------
    static void testCustomHost() {
        String[] sp = RunableBridge.parseHostSpec(
                "https://my.host/upload|fileToUpload|reqtype=fileupload|a=b");
        eq(sp.length, 3, "spec 三段");
        eq(sp[0], "https://my.host/upload", "spec url");
        eq(sp[1], "fileToUpload", "spec field");
        contains(sp[2], "reqtype=fileupload", "spec extra 1");
        contains(sp[2], "a=b", "spec extra 2");
        eq(RunableBridge.parseHostSpec(""), null, "空 spec → null");
        eq(RunableBridge.parseHostSpec("   "), null, "空白 spec → null");
        eq(RunableBridge.parseHostSpec("ftp://x"), null, "非 http spec → null");
        String[] sp2 = RunableBridge.parseHostSpec("https://h/u");
        eq(sp2[1], "fileToUpload", "缺字段名用默认");
        eq(sp2[2], null, "无额外参数");
        String[] sp3 = RunableBridge.parseHostSpec("https://h/u|f|k=v");
        eq(sp3[1], "f", "自定义字段名");
        eq(sp3[2], "k=v", "自定义参数");
        // 公共图床在沙箱机房 IP 上通常全被墙；只验证不抛异常（真机验证换传成功）
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        RunableBridge.uploadBytes(png, "png", "");
        RunableBridge.upload("data:image/png;base64,aGk=", null);
    }

    // ---------- 本地路径识别 + 尾部说明 ----------
    static void testLocalPaths() {
        String minisMsg = "这是图片 /var/minis-euleros/attachments/uploads/1000132760.jpg\n\n你能收到图片吗";
        List<String> ps = RunableBridge.localImagePaths(minisMsg);
        eq(ps.size(), 1, "Minis 内联路径能识别");
        eq(ps.get(0), "/var/minis-euleros/attachments/uploads/1000132760.jpg", "路径完整（含 uploads 子目录）");

        List<String> none = RunableBridge.localImagePaths("今天天气不错，聊聊音乐");
        eq(none.size(), 0, "无路径 → 空");

        List<String> multi = RunableBridge.localImagePaths(
                "a /var/minis-euleros/workspace/x.png b /var/minis-euleros/shared/y.jpeg c");
        eq(multi.size(), 2, "多路径都识别");

        List<String> dedup = RunableBridge.localImagePaths(
                "/var/minis-euleros/attachments/a.webp 和 /var/minis-euleros/attachments/a.webp");
        eq(dedup.size(), 1, "重复路径去重");

        // vc79 契约：图走官方附件通道后，只有「图没送达、只剩路径文本」才注入兜底说明
        String note = RunableBridge.localPathNote(minisMsg, false);
        contains(note, "[关于本轮图片]", "note 有标题");
        contains(note, "1000132760.jpg", "note 带真实路径");
        contains(note, "服务端不可读", "note 说明路径在服务端不可读");
        contains(note, "重新发送", "note 让用户重发图片消息");
        contains(note, "重复贴路径没有作用", "note 堵住「再贴一次路径」的话术");
        eq(RunableBridge.localPathNote(minisMsg, true), "", "图已作附件送达 → 不注入路径说明");
        eq(RunableBridge.localPathNote("纯文本", true), "", "无路径 → 空 note");
    }

    // ---------- 文本工具协议 → tool_calls ----------
    static void testBridgeProtocol() {
        Set<String> names = new HashSet<>();
        names.add("shell_execute");
        BridgeProbe.run(names);
    }

    /** 单独抽出，避免 Bridge 内部类可见性问题。 */
    static class BridgeProbe {
        static void run(Set<String> names) {
            RunableBridge.Conv conv = RunableBridge.convFor("probe", 0, 0);
            SseTranslator tr = new RunableBridge.Bridge(conv.chatId, "zai/glm-5.3-flash", names);
            try {
                // 上游吐一段含工具调用 JSON 的文本
                String payload = "我来执行一下\n{\"name\":\"shell_execute\",\"arguments\":{\"command\":\"ls\"}}\n";
                JSONObject f = new JSONObject().put("type", "text-delta").put("delta", payload);
                List<String> mid = tr.translate("text-delta", f.toString(), "m");
                eq(mid.size(), 0, "text-delta 不直接转发");
                List<String> end = tr.terminalChunks();
                ok(!end.isEmpty(), "终帧非空");
                boolean sawToolCalls = false, sawProse = false;
                for (String c : end) {
                    JSONObject j = new JSONObject(c);
                    JSONObject delta = j.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
                    if (delta.has("tool_calls")) {
                        sawToolCalls = true;
                        JSONArray tc = delta.getJSONArray("tool_calls");
                        eq(tc.getJSONObject(0).getJSONObject("function").getString("name"),
                                "shell_execute", "tool_call 名字还原");
                        contains(tc.getJSONObject(0).getString("id"), "call_", "tool_call 有 id");
                    }
                    if (delta.has("content")) {
                        sawProse = true;
                        notContains(delta.getString("content"), "shell_execute", "工具 JSON 不泄漏到正文");
                    }
                }
                ok(sawToolCalls, "还原出 tool_calls");
                ok(sawProse, "正文与工具调用分离");
            } catch (Exception e) {
                ok(false, "Bridge 协议异常: " + e);
            }
        }
    }
}
