package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

/**
 * 智谱凭证续期的离线断言（vc85）—— 编译真实类 ChatglmToolBridge.mergedCred，不复刻逻辑。
 *
 * 覆盖真机 bug「账号到时间会自动过期，并且无法刷新凭证」的两个成因：
 *  1) 旧续期请求拿当前 access_token 做鉴权（要修的凭证正是过期的那个）→ 服务端
 *     {"message":"Unauthorized"}，刷新从未成功过。协议修好之后，凭证合并这一步
 *     同样能把账号彻底废掉，所以逐条钉住；
 *  2) 合并结果写回账号池时一旦缺字段，账号就从「可以救」变成「必须重新登录」。
 *
 * 编译：javac -cp tools/json-20240303.jar -d /tmp/glmref-out \
 *   app/src/main/java/com/aigetway/tattoo7/provider/{ChatglmToolBridge,LocalProbe,SseTranslator}.java \
 *   tools/tests/TestGlmRefresh.java
 * 运行：java -cp /tmp/glmref-out:tools/json-20240303.jar com.aigetway.tattoo7.provider.TestGlmRefresh
 */
public class TestGlmRefresh {

    static int pass = 0, fail = 0;
    static final long NOW = 1_760_000_000_000L;

    static void ok(boolean cond, String what) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] a) {
        System.out.println("== 刷新失败时绝不覆盖好凭证 ==");
        testNoAccess();
        System.out.println("== refresh_token 轮换与沿用 ==");
        testRotating();
        System.out.println("== 续期窗口 ==");
        testExpiry();
        System.out.println("== 其余字段保留 ==");
        testKeep();
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void testNoAccess() {
        JSONObject r = new JSONObject();
        r.put("refresh_token", "rt-new");   // 只有 rt、没有 access_token
        ok(ChatglmToolBridge.mergedCred("", "rt-old", "dev", "uid1", r, NOW) == null,
                "access_token 为空必须返回 null（调用方据此拒绝回写）");
        ok(ChatglmToolBridge.mergedCred(null, "rt-old", "dev", "uid1", r, NOW) == null,
                "null 同样拒绝");
        // 旧代码在这里把 cred 覆盖成 "{}"，device_id / uid / refresh_token 全丢
        ok(ChatglmToolBridge.mergedCred("at-new", "rt-old", "dev", "uid1", null, NOW) != null,
                "响应体缺失但拿到新 token 时仍可写入");
    }

    static void testRotating() {
        JSONObject r = new JSONObject();
        r.put("access_token", "at-new");
        r.put("refresh_token", "rt-rotated");
        JSONObject c = ChatglmToolBridge.mergedCred("at-new", "rt-old", "dev", "uid1", r, NOW);
        ok("rt-rotated".equals(c.optString("refreshToken")), "上游带轮换 rt → 用新的");

        JSONObject noRt = new JSONObject();
        noRt.put("access_token", "at-new");
        JSONObject c2 = ChatglmToolBridge.mergedCred("at-new", "rt-old", "dev", "uid1", noRt, NOW);
        ok("rt-old".equals(c2.optString("refreshToken")),
                "上游不带 rt → 沿用旧值（旧代码回写空串，下次刷新直接判「没有 refresh_token」，永久刷不动）");

        JSONObject blank = new JSONObject();
        blank.put("access_token", "at-new");
        blank.put("refresh_token", "");
        JSONObject c3 = ChatglmToolBridge.mergedCred("at-new", "rt-old", "dev", "uid1", blank, NOW);
        ok("rt-old".equals(c3.optString("refreshToken")), "上游回空串 rt 也沿用旧值");

        JSONObject c4 = ChatglmToolBridge.mergedCred("at-new", null, null, null, noRt, NOW);
        ok(c4 != null && c4.optString("refreshToken").isEmpty(),
                "旧 rt 为 null 不抛异常（写空串，下次走「需重新登录」分支）");

        // 两种命名都得认：better-auth 用 snake_case，历史端点用 camelCase
        JSONObject camel = new JSONObject();
        camel.put("accessToken", "at-camel");
        JSONObject c5 = ChatglmToolBridge.mergedCred("at-camel", "rt-old", "dev", "uid1", camel, NOW);
        ok("at-camel".equals(c5.optString("accessToken")), "camelCase 响应也能落地");
    }

    static void testExpiry() {
        JSONObject r = new JSONObject();
        r.put("access_token", "at");
        r.put("expires_in", 3600L);
        JSONObject c = ChatglmToolBridge.mergedCred("at", "rt", "dev", "uid", r, NOW);
        long exp = c.optLong("tokenExpiresAt", 0L);
        ok(exp == NOW + 3600_000L, "expires_in=3600 → 精确 now+1h");

        JSONObject shortLife = new JSONObject();
        shortLife.put("access_token", "at");
        shortLife.put("expires_in", 5L);      // 明显不可信（参考实现按天续期）
        JSONObject c2 = ChatglmToolBridge.mergedCred("at", "rt", "dev", "uid", shortLife, NOW);
        ok(c2.optLong("tokenExpiresAt") == NOW + 24 * 3600_000L,
                "expires_in 过小时兜底一天，避免刚刷新就又被判过期");

        JSONObject noLife = new JSONObject();
        noLife.put("access_token", "at");
        JSONObject c3 = ChatglmToolBridge.mergedCred("at", "rt", "dev", "uid", noLife, NOW);
        ok(c3.optLong("tokenExpiresAt") > NOW,
                "没有 expires_in 也必须写窗口（refreshFingerprint 靠它省掉无谓轮换）");

        // 手动刷新按钮必须无视这个窗口真的发请求；这里钉住窗口不是 0，
        // 否则守卫会把「用户点刷新」也吞掉，显示成功但其实没动。
        ok(c3.optLong("tokenExpiresAt", -1L) != -1L, "tokenExpiresAt 字段一定存在");
    }

    static void testKeep() {
        JSONObject r = new JSONObject();
        r.put("access_token", "at-new");
        JSONObject c = ChatglmToolBridge.mergedCred("at-new", "rt-old", "device-7", "uid-42", r, NOW);
        ok("device-7".equals(c.optString("deviceId")), "deviceId 保留（签名头要用）");
        ok("uid-42".equals(c.optString("uid")), "uid 保留（账号池按它定位）");
        ok("at-new".equals(c.optString("accessToken")), "accessToken 写入");
    }
}
