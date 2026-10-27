#!/bin/bash
# 用真实 RunableBridge.upload() 逐源计时 —— 回答「换传到底要多久、哪张图床活着」。
# 结论（2026-10-10 本沙箱实测）：uguu 1.0s ✅ / tmpfiles 上传 4.3s 但 raw 端点不通 ❌ /
# litterbox·catbox·0x0 10~24s 超时 ❌。顺序据此排：uguu → litterbox → catbox → tmpfiles → 0x0。
# 用法: bash tools/tests/run_upload_timing.sh
set -e
cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/aigetway/tattoo7/provider
OUT=/tmp/rb-timing
rm -rf $OUT && mkdir -p $OUT
cat > $OUT/RbTiming.java <<'EOF'
package com.aigetway.tattoo7.provider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 与 RunableBridge 同包，只为能调它的包级私有方法做逐源计时。 */
public class RbTiming {
    public static void main(String[] a) throws Exception {
        String b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";
        String data = "data:image/png;base64," + b64;
        for (String h : new String[]{"uguu", "litterbox", "catbox", "tmpfiles", "x0at"}) {
            long t0 = System.currentTimeMillis();
            RunableBridge.Upload u = RunableBridge.upload(data, h);
            String url = u == null ? null : u.url;
            System.out.printf("%-9s %6dms -> %s%n", h, System.currentTimeMillis() - t0, url);
            if (url != null) System.out.printf("%-9s %6dms 直链 GET %s%n", "", 0, probe(url));
        }
    }

    static String probe(String url) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return code + " / " + bos.size() + "B / " + (System.currentTimeMillis() - t0) + "ms";
        } catch (Exception e) {
            return "ERR " + e + " (" + (System.currentTimeMillis() - t0) + "ms)";
        }
    }
}
EOF
javac -nowarn -encoding UTF-8 -cp tools/json-20240303.jar -d $OUT \
  $SRC/RunableBridge.java $SRC/ChatglmToolBridge.java $SRC/SseTranslator.java $OUT/RbTiming.java
java -Dstdout.encoding=UTF-8 -cp "$OUT:tools/json-20240303.jar" com.aigetway.tattoo7.provider.RbTiming
