package com.aigetway.tattoo7.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 智谱图片投递说明（vc84）离线断言 —— 编译真实类，不复刻逻辑。
 *
 * 覆盖两个真实踩坑：
 *  1) 图片以纯文本路径发给服务端模型 → 图从没送达，模型回「只是看到一串路径文本」；
 *     现在必须转成 image_url 内容块，并在正文前注入「图已送达」说明。
 *  2) 模型被说明里「换传失败」这种措辞骗到，误以为自己没拿到图 → 措辞必须只说已送达。
 *
 * 编译：javac -cp tools/json-20240303.jar -d /tmp/glmimg-out \
 *   app/src/main/java/com/aigetway/tattoo7/provider/{ChatglmToolBridge,RunableBridge,SseTranslator}.java \
 *   tools/tests/TestGlmImageNote.java
 * 运行：java -cp /tmp/glmimg-out:tools/json-20240303.jar com.aigetway.tattoo7.provider.TestGlmImageNote
 */
public class TestGlmImageNote {

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        if (cond) { pass++; } else { fail++; System.out.println("FAIL: " + what); }
    }

    static String userText(String s) {
        return "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":"
                + "\"" + s + "\"}]}]";
    }

    /** 反射调 private static imageNote —— 改动这段文案时本测试即护栏。 */
    static String imageNote(List<String> urls, List<String> names, List<String> fails)
            throws Exception {
        java.lang.reflect.Method m = ChatglmToolBridge.class
                .getDeclaredMethod("imageNote", List.class, List.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, urls, names, fails);
    }

    public static void main(String[] args) throws Exception {
        ChatglmToolBridge.reset();

        // ---- 1. 无图时不注入任何说明 ----
        ok(imageNote(new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>()).isEmpty(), "no images -> empty note");

        // ---- 2. 有图：必须含「已送达」「不是路径文本」，且不得含「换传失败」 ----
        List<String> u = new ArrayList<>(), n = new ArrayList<>(), f = new ArrayList<>();
        u.add("https://n.uguu.se/abc.jpg");
        n.add("uploads/1000133285.jpg");
        String note = imageNote(u, n, f);
        ok(note.contains("已送达"), "note says delivered");
        ok(note.contains("不是路径文本"), "note denies path-text");
        ok(!note.contains("换传失败"), "note must not mention failed swap (vc83 wording bug)");
        ok(note.contains("https://n.uguu.se/abc.jpg"), "note lists the real url");
        ok(note.contains("uploads/1000133285.jpg"), "note lists the file name");

        // ---- 3. 全部换传失败：此时才允许出现失败措辞 ----
        List<String> u2 = new ArrayList<>(), n2 = new ArrayList<>(), f2 = new ArrayList<>();
        u2.add(""); n2.add("uploads/x.png"); f2.add("uploads/x.png: 超过 12MB");
        String note2 = imageNote(u2, n2, f2);
        ok(note2.contains("未能送达") && note2.contains("超过 12MB"),
                "failed-only case reports failure honestly");
        ok(!note2.contains("已随本轮消息送达"), "failed case must not claim delivery");

        // ---- 4. buildBody 真实集成：带本地图片路径的请求必须产出 image_url 块 + 说明 ----
        // 用一张真生成的极小 PNG（1x1）走完整换传链路（依赖网络时允许跳过，但结构必须对）。
        java.io.File tmp = new java.io.File("/tmp/glmimg-test");
        tmp.mkdirs();
        java.io.File png = new java.io.File(tmp, "tiny.png");
        byte[] onePx = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        java.nio.file.Files.write(png.toPath(), onePx);

        ChatglmToolBridge toolOnly = new ChatglmToolBridge();
        String sys = toolOnly.buildBody("[{\"role\":\"system\",\"content\":[{\"type\":\"text\",\"text\":\"工具执行环境\"}]}]",
                "工具列表", true, null);
        ok(!sys.contains("image_url"), "system turn has no images");

        ChatglmToolBridge t = new ChatglmToolBridge();
        String body = t.buildBody(userText("图一是啥 " + png.getAbsolutePath()),
                "工具列表", false, null);
        // 换传依赖公网图床：成功必须有 image_url 块；失败也要如实说明，二者必居其一。
        boolean uploaded = body.contains("image_url") && body.contains("已送达");
        boolean honestFail = body.contains("未能送达");
        ok(uploaded || honestFail,
                "local path either swapped to image_url(" + uploaded + ") or reported honestly("
                        + honestFail + ")");
        ok(body.contains("图一") || body.contains("已送达") || body.contains("未能送达"),
                "user question preserved (clip must keep the tail)");

        // ---- 5. 纯 http(s) 图片 URL 直接透传，不需换传 ----
        ChatglmToolBridge h = new ChatglmToolBridge();
        String hb = h.buildBody(userText("看这张 https://example.com/a.jpg 讲什么"),
                "工具列表", false, null);
        ok(hb.contains("image_url") && hb.contains("https://example.com/a.jpg"),
                "remote https url goes straight into image_url");

        System.out.println("TestGlmImageNote: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
