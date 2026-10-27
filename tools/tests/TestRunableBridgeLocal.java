package com.aigetway.tattoo7.provider;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 本地图片路径 → 公网直链 的离线断言测试（编译真实类，非复刻逻辑）。
 *
 * <p>覆盖上一版的漏网之处：Minis 传附件走的是**纯文本路径**（不是 image part），
 * 旧代码只处理 part，于是换传从未触发，模型收到的永远是一串字符。
 *
 * 编译：javac -cp tools/json-20240303.jar -d /tmp/rbl-out \
 *   app/src/main/java/com/aigetway/tattoo7/provider/{RunableBridge,ChatglmToolBridge,SseTranslator}.java \
 *   tools/tests/TestRunableBridgeLocal.java
 * 运行：java -cp /tmp/rbl-out:tools/json-20240303.jar com.aigetway.tattoo7.provider.TestRunableBridgeLocal
 */
public class TestRunableBridgeLocal {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 本地路径抽取 ==");
        testExtract();
        System.out.println("== 扩展名归一 ==");
        testExt();
        System.out.println("== 投递说明 ==");
        testNote();
        System.out.println("== 本地文件换传（联网） ==");
        testUploadLocal();
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void testExtract() {
        // Minis 实际话术：附件以「图片文件路径：…」文本送达
        List<String> a = RunableBridge.localFilePaths(
                "用户提供了 1 张图片。\n图片文件路径：/var/minis-euleros/attachments/uploads/1000132760.jpg\n请分析");
        ok(a.size() == 1, "从纯文本路径中捞到图片，size=" + a.size());
        ok(a.get(0).endsWith("1000132760.jpg"), "路径完整（含目录与文件名）");

        // 截图常见 png；句末标点要裁掉
        List<String> b = RunableBridge.localFilePaths(
                "见 /var/minis-euleros/attachments/uploads/1000132846.jpg。");
        ok(b.size() == 1 && b.get(0).endsWith("1000132846.jpg"), "句末中文句号不被吃进路径");

        List<String> c = RunableBridge.localFilePaths("/var/minis-euleros/workspace/shot.png)");
        ok(c.size() == 1 && c.get(0).endsWith("shot.png"), "右括号不被吃进路径");

        // 多扩展名
        ok(RunableBridge.localFilePaths("/var/minis-euleros/attachments/a.heic").size() == 1, "heic 认");
        ok(RunableBridge.localFilePaths("/var/minis-euleros/attachments/a.webp").size() == 1, "webp 认");

        // 非图片、外部路径不得误捞
        ok(RunableBridge.localFilePaths("/var/minis-euleros/workspace/notes.txt").isEmpty(), "txt 不当图");
        ok(RunableBridge.localFilePaths("/home/user/skills/x.png").isEmpty(), "非 Minis 路径不捞");
        ok(RunableBridge.localFilePaths("没有路径的一张图").isEmpty(), "无路径返回空");
        ok(RunableBridge.localFilePaths(null).isEmpty(), "null 安全");

        // 去重
        String dup = "/var/minis-euleros/attachments/uploads/1.jpg 和 /var/minis-euleros/attachments/uploads/1.jpg";
        ok(RunableBridge.localFilePaths(dup).size() == 1, "同一路径去重");

        // 旧接口仍可用
        ok(!RunableBridge.localImagePaths("/var/minis-euleros/attachments/uploads/1.jpg").isEmpty(),
                "localImagePaths 兼容保留");
    }

    static void testExt() {
        ok(RunableBridge.extOfPath("/a/b/c.JPG").equals("jpg"), "大写扩展名归一为小写");
        ok(RunableBridge.extOfPath("/a/b/c.jpeg").equals("jpg"), "jpeg→jpg");
        ok(RunableBridge.extOfPath("/a/b/c.heif").equals("heic"), "heif→heic");
        ok(RunableBridge.extOfPath("/a/b/c.png").equals("png"), "png 原样");
        ok(RunableBridge.extOfPath("/a/b/noext").equals("jpg"), "无扩展名兜底 jpg");
        ok(RunableBridge.extOfPath("/a/b/c.zip").equals("jpg"), "非图片扩展名兜底 jpg");
    }

    static void testNote() {
        // vc79 起：图已走官方附件通道（metadata.files）时不再注入路径说明，
        // 只有「图没送达、只剩路径文本」的兜底场景才提示用户重发图片消息。
        String n = RunableBridge.localPathNote(
                "图片文件路径：/var/minis-euleros/attachments/uploads/1000132760.jpg", false);
        ok(n.startsWith("[关于本轮图片]"), "兜底说明有标题");
        ok(n.contains("1000132760.jpg"), "说明里带上真实路径");
        ok(n.contains("服务端不可读"), "说明点明路径在服务端不可读");
        ok(n.contains("重新发送"), "给出可执行动作（让用户重发图片消息）");
        ok(RunableBridge.localPathNote(
                "图片文件路径：/var/minis-euleros/attachments/uploads/1000132760.jpg", true)
                .isEmpty(), "图已作附件送达 → 不注入路径说明");
        ok(RunableBridge.localPathNote("普通问题", false).isEmpty(), "无路径时不注入");

        // 每轮极简声明：短、中立、只陈述可验证事实（vc80 教训：命令式/否定模型自家沙箱的
        // 措辞会被 deep thinking 判成 prompt injection，整段排斥）
        String c = ChatglmToolBridge.envNoteCompact();
        ok(c.length() < 900, "极简声明够短，len=" + c.length());
        ok(c.contains("/var/minis-euleros"), "声明列出真实路径");
        ok(c.contains("attachments/uploads"), "声明点名聊天附件目录（apk/图片都在这）");
        ok(c.contains("两台机器"), "声明用「两台机器」而非否定服务端沙箱");
        ok(!c.contains("唯一事实来源") && !c.contains("优先级高于") && !c.contains("禁止"),
                "声明不含注入式命令措辞");
        String r = ChatglmToolBridge.toolReminder(namesOf("shell_execute", "file_read"));
        ok(r.contains("两台机器"), "每轮提醒重申两台机器");
        ok(r.contains("不区分对话/Ask/Agent"), "每轮提醒说明不分模式（图四模型的核心误解）");
        ok(r.contains("shell_execute") && r.contains(ChatglmToolBridge.CALL_START)
                && r.contains(ChatglmToolBridge.CALL_END),
                "每轮提醒带工具名与调用标记");
    }

    private static java.util.Set<String> namesOf(String... n) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (String x : n) s.add(x);
        return s;
    }

    static void testUploadLocal() throws Exception {
        // 不存在的文件 / 目录 → null，不抛异常
        ok(RunableBridge.uploadLocalFile("/var/minis-euleros/attachments/nope.jpg", null, null) == null,
                "文件不存在返回 null");
        ok(RunableBridge.uploadLocalFile("/var/minis-euleros/attachments", null, null) == null,
                "传目录返回 null");

        File tmp = File.createTempFile("rbl-", ".png");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");
        Files.write(tmp.toPath(), png);

        long t0 = System.currentTimeMillis();
        String[] nameOut = new String[1];
        String[] errOut = new String[1];
        RunableBridge.Upload u = RunableBridge.uploadLocalFile(
                tmp.getAbsolutePath(), null, nameOut, errOut);
        long ms = System.currentTimeMillis() - t0;
        ok(u != null, "真实本地文件换传成功（" + ms + "ms）"
                + (u == null ? " → " + errOut[0] : ""));
        if (u != null) {
            ok(u.url != null && u.url.startsWith("https://"), "拿到 https 直链 " + u.url);
            ok(u.host != null && u.host.length() > 0, "记录了命中图床 " + u.host);
            ok(tmp.getName().equals(nameOut[0]), "回填真实文件名 " + nameOut[0]);
        }

        // 已是 http 的路径字符串直接透传（不读盘）
        RunableBridge.Upload viaHttp = RunableBridge.uploadLocalFile(
                "https://n.uguu.se/x.jpg", null, null, null);
        ok(viaHttp != null && "https://n.uguu.se/x.jpg".equals(viaHttp.url), "http 直链原样透传");

        // 不存在的文件：必须返回 null 且给出可读原因（真机日志靠它定位）
        String[] missErr = new String[1];
        ok(RunableBridge.uploadLocalFile("/var/minis-euleros/attachments/nope.jpg",
                null, null, missErr) == null, "不存在的文件不抛异常");
        ok(missErr[0] != null && missErr[0].contains("文件不存在"), "给出可读原因：" + missErr[0]);

        // 自定义源填了但格式非法 → 必须回退公共图床（不能静默不发图）
        RunableBridge.Upload badSpec = RunableBridge.uploadLocalFile(
                tmp.getAbsolutePath(), "not-a-url", null, null);
        ok(badSpec != null && badSpec.url != null && badSpec.url.startsWith("https://"),
                "自定义源格式非法时回退公共图床：" + (badSpec == null ? "null" : badSpec.url));
        ok(badSpec != null && "custom".equals(badSpec.host) == false,
                "回退后 host 记录的是真实图床：" + (badSpec == null ? "-" : badSpec.host));

        tmp.delete();
    }
}
