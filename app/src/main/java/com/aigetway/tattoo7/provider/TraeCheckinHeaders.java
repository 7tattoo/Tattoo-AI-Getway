package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TRAE 签到专用请求头。
 *
 * <p>协议来自 _upstream/src/trae.ts 的 traeCheckinHeaders()：签到走的是
 * {@code VSCode 1.107.1 (TRAE SOLO CN)} 这一套<b>完整客户端头</b>（约 20 个），
 * 与 chat / models 用的那套（Trae/0.x）<b>完全不同</b>。
 *
 * <p>之前签到复用了 chat 的 headers()，服务端一律返回
 * {@code code=9004 The submitted order parameters are incorrect} —— 9004 就是
 * 「请求头不像 SOLO 客户端」。本类补齐这套头。
 *
 * <p>关键点：X-Device-Id 等设备身份必须<b>基于 user_id 确定性派生</b>，
 * 让每个账号天然拥有一套独立稳定的设备标识，规避服务端「每设备每天一次」配额。
 */
final class TraeCheckinHeaders {

    private static final String IDE_VERSION = "3.3.37";

    private TraeCheckinHeaders() {
    }

    static Map<String, String> build(JSONObject cred, String ideVersion) {
        Map<String, String> h = new LinkedHashMap<>();
        String token = cred == null ? "" : cred.optString("accessToken", "");
        String uid = cred == null ? "" : cred.optString("uid", "");
        // uid 缺失时退化为空串派生：仍能保证同一账号恒定同一设备身份
        String ver = (ideVersion == null || ideVersion.isEmpty()) ? IDE_VERSION : ideVersion;

        h.put("Content-Type", "application/json");
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "gzip, deflate");
        h.put("Accept-Language", "zh-CN");
        h.put("User-Agent", "VSCode 1.107.1 (TRAE SOLO CN)");
        h.put("Authorization", "Cloud-IDE-JWT " + token);
        h.put("X-Market-Client-Id", "VSCode 1.107.1");
        h.put("X-Market-User-Id", deriveMarketUserId(uid));
        h.put("X-User-Region", "CN");
        h.put("X-Device-Id", deriveDeviceId15(uid));
        h.put("X-Lgw-Req-Sdk-Type", "3");
        h.put("Package-Type", "stable_cn");
        h.put("X-Lscbd-Aid", "787976");
        h.put("X-Lscbd-Platform", "windows");
        h.put("App-Version", ver);
        h.put("X-Tt-Trace-Id", "00-" + randomHex(16) + "-01");
        h.put("Vscode-Sessionid", deriveSessionId(uid));
        h.put("X-Request-Id", UUID.randomUUID().toString());
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Sec-Fetch-Mode", "no-cors");
        h.put("Sec-Fetch-Site", "none");
        return h;
    }

    // ---- 设备身份确定性派生（对齐 trae.ts 的 seededStream / seededDigits） ----

    /**
     * SHA-256 确定性伪随机流：输入 (seed, salt) 永远产生同一串字节。
     * 算法：SHA256(utf8(salt + ":" + seed) ++ counterBE32) 串联到 nbytes。
     */
    private static byte[] seededStream(String seed, String salt, int nbytes) {
        byte[] prefix = (salt + ":" + seed).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[nbytes];
        int filled = 0;
        int counter = 0;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            while (filled < nbytes) {
                md.reset();
                md.update(prefix);
                md.update(new byte[]{
                        (byte) (counter >> 24), (byte) (counter >> 16),
                        (byte) (counter >> 8), (byte) counter});
                byte[] h = md.digest();
                int n = Math.min(h.length, nbytes - filled);
                System.arraycopy(h, 0, out, filled, n);
                filled += n;
                counter++;
            }
        } catch (Exception e) {
            for (int i = 0; i < nbytes; i++) out[i] = (byte) (i * 31 + 7);
        }
        return out;
    }

    /**
     * N 位数字字符串（逐字节取模 10）。
     *
     * <p>⚠️ 必须先 {@code (b & 0xFF)} 转成无符号再取模：上游用的是 JS 的 Uint8Array
     * （0..255），而 Java 的 byte 是有符号的（-128..127）。
     * 直接用 b % 10 或 Math.floorMod(b,10) 得到的设备 ID 与上游不一致。
     */
    private static String seededDigits(int n, String seed, String salt) {
        byte[] bs = seededStream(seed, salt, n);
        StringBuilder sb = new StringBuilder(n);
        for (byte b : bs) sb.append((b & 0xFF) % 10);
        return sb.toString();
    }

    private static String deriveDeviceId15(String userId) {
        return seededDigits(15, userId, "devid");
    }

    /** UUID v4 形态的确定性 Market User ID。 */
    private static String deriveMarketUserId(String userId) {
        byte[] bs = seededStream(userId, "market", 16);
        bs[6] = (byte) ((bs[6] & 0x0F) | 0x40);
        bs[8] = (byte) ((bs[8] & 0x3F) | 0x80);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bs) hex.append(String.format(Locale.US, "%02x", b));
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20, 32);
    }

    /** 64 位 hex Session ID。 */
    private static String deriveSessionId(String userId) {
        byte[] bs = seededStream(userId, "sess", 32);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bs) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    private static String randomHex(int n) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) {
            sb.append(String.format(Locale.US, "%016x",
                    java.util.UUID.randomUUID().getLeastSignificantBits()));
        }
        return sb.substring(0, n);
    }
}