package com.aigetway.tattoo7.provider;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TRAE 的请求体加密（与 traecn_tool 参考实现一致）。
 *
 * message 字段不是明文，而是 AES-256-GCM 密文：
 *   key   = 固定密钥 XOR pin(8字节随机)
 *   nonce = 12 字节随机
 *   AAD   = 十进制时间戳字符串（必须与 x-requested-at 头**完全相同**）
 *   输出  = base64(nonce || ciphertext)
 */
final class TraeCrypto {
    /** TRAE 客户端硬编码的 AES-256 密钥。 */
    private static final String KEY_HEX =
            "6195f24ca4d430f8a4833de7db8dac37d148a084e7464a351ffa68585c16b955";

    static final class Result {
        final String message;
        final String pinHex;
        final long requestedAt;

        Result(String message, String pinHex, long requestedAt) {
            this.message = message;
            this.pinHex = pinHex;
            this.requestedAt = requestedAt;
        }
    }

    private TraeCrypto() {
    }

    static Result encrypt(String plaintext) throws Exception {
        byte[] key = hex(KEY_HEX);
        byte[] pin = new byte[8];
        new SecureRandom().nextBytes(pin);
        for (int i = 0; i < 8; i++) key[i] ^= pin[i];

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        long at = System.currentTimeMillis() / 1000L;
        byte[] aad = String.valueOf(at).getBytes(StandardCharsets.UTF_8);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);

        return new Result(Base64.getEncoder().encodeToString(out),
                hexStr(pin), at);
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexStr(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
