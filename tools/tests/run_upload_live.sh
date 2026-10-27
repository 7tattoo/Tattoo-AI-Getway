#!/bin/bash
# 用真实 RunableBridge 做端到端换传实测 —— 回答「图到底出门了没有、多久、失败为什么」。
# 结论（2026-10-10 本沙箱实测）：
#   uploadLocalFile(真附件 542KB) 2.9s → uguu 直链回读 200/555632B/image/jpeg，真实文件名保留
#   空 spec / 非法 spec → 都正确退回公共图床链（vc78 修：以前非法 spec = 静默丢图）
#   不存在的文件 → err=「文件不存在或不可读：…（网关进程是用户手机上的 Minis，这个路径应当就在本机）」
# 用法: bash tools/tests/run_upload_live.sh [要换传的本地文件路径]
set -e
cd "$(dirname "$0")/../.."
S=app/src/main/java/com/aigetway/tattoo7/provider
OUT=/tmp/rb-live
TARGET="${1:-/var/minis-euleros/attachments/uploads/1000132846.jpg}"
rm -rf $OUT && mkdir -p $OUT

cat > $OUT/RbLive.java <<'EOF'
package com.aigetway.tattoo7.provider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/** 与 RunableBridge 同包，只为能调它的包级私有方法。 */
public class RbLive {
    public static void main(String[] a) throws Exception {
        String target = a.length > 0 ? a[0] : null;

        String[] name = new String[1], err = new String[1];
        long t0 = System.currentTimeMillis();
        RunableBridge.Upload u = RunableBridge.uploadLocalFile(target, "", name, err);
        System.out.printf("本地文件换传 %6dms url=%s name=%s err=%s%n",
                System.currentTimeMillis() - t0, u == null ? null : u.url, name[0], err[0]);
        if (u != null) System.out.printf("           直链回读 %s%n", probe(u.url));

        String[] e2 = new String[1];
        t0 = System.currentTimeMillis();
        RunableBridge.Upload u2 = RunableBridge.upload(miniPng(), "", e2);
        System.out.printf("空 spec 兜底 %6dms url=%s err=%s%n",
                System.currentTimeMillis() - t0, u2 == null ? null : u2.url, e2[0]);

        String[] e3 = new String[1];
        t0 = System.currentTimeMillis();
        RunableBridge.Upload u3 = RunableBridge.upload(miniPng(), "not-a-valid-spec", e3);
        System.out.printf("非法 spec 兜底 %6dms url=%s err=%s%n",
                System.currentTimeMillis() - t0, u3 == null ? null : u3.url, e3[0]);

        String[] e4 = new String[1];
        RunableBridge.Upload u4 = RunableBridge.upload("https://example.com/a.jpg", "", e4);
        System.out.println("http 直链透传 url=" + (u4 == null ? null : u4.url) + " err=" + e4[0]);

        System.out.println("路径识别: " + RunableBridge.localFilePaths(
                "看图 " + target + " 和 workspace/x.png"));
    }

    static String miniPng() {
        byte[] px = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(px);
    }

    static String probe(String url) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return code + " / " + bos.size() + "B / " + c.getContentType()
                    + " / " + (System.currentTimeMillis() - t0) + "ms";
        } catch (Exception e) {
            return "ERR " + e;
        }
    }
}
EOF

# 只编 RunableBridge + 它的两个依赖；通配 provider/*.java 会拖进 BuddyProvider（依赖 android.*）编不过
javac -nowarn -encoding UTF-8 -cp tools/json-20240303.jar -d $OUT \
  $S/RunableBridge.java $S/ChatglmToolBridge.java $S/SseTranslator.java $OUT/RbLive.java
java -Dstdout.encoding=UTF-8 -cp "$OUT:tools/json-20240303.jar" \
  com.aigetway.tattoo7.provider.RbLive "$TARGET"
