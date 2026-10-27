package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

/**
 * AStudioProvider.cookieHeader 防回归。
 *
 * <p>vc170 实机事故：{@code completeLogin} 登录响应成功（sid/uid 都拿到），但紧随的
 * init-app / configs 全部 code=80000「登录异常」。根因是 {@code cookieHeader} 尾部
 * {@code sb.substring(2)} —— 原意是 sid 为空时剪掉前导 {@code "; "}，sid 非空时却把
 * 驼峰键 {@code ssoSessionId} 剪成了 {@code oSessionId}（cookieShape 日志铁证），
 * 服务端认不出会话键 → 判会话失效。
 */
public class AStudioCookieTest {
    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String extra) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " :: " + extra); }
    }

    public static void main(String[] argv) throws Exception {
        System.out.println("[1] sid 非空：串必须以完整驼峰键 ssoSessionId= 开头");
        JSONObject c = new JSONObject("{\"ssoSessionId\":\"56bdac-aaaa-bbbb-cccc-dddddddddd\","
                + "\"accountId\":\"22073711\"}");
        String s = AStudioProvider.cookieHeader(c);
        String expect = "ssoSessionId=56bdac-aaaa-bbbb-cccc-dddddddddd"
                + "; sso_sessionid=56bdac-aaaa-bbbb-cccc-dddddddddd"
                + "; account_id=22073711; token=56bdac-aaaa-bbbb-cccc-dddddddddd";
        ok("驼峰键完整未被剪", s.startsWith("ssoSessionId=56bdac"), s);
        ok("四键逐字吻合（token 兜底 sid）", expect.equals(s), s);
        ok("access_token 绝不进 Cookie", !s.contains("access_token"), s);

        System.out.println("[2] sid 为空：首键就是 account_id，不以 '; ' 开头");
        String s2 = AStudioProvider.cookieHeader(new JSONObject("{\"accountId\":\"a1\"}"));
        ok("空 sid 首键 account_id", s2.startsWith("account_id=a1"), s2);

        System.out.println("[3] token 独立值优先于 sid 兜底");
        String s3 = AStudioProvider.cookieHeader(new JSONObject(
                "{\"ssoSessionId\":\"sid1\",\"token\":\"tk1\",\"accountId\":\"acc1\"}"));
        ok("token 独立值", s3.endsWith("token=tk1"), s3);
        ok("驼峰与蛇形同值", s3.contains("ssoSessionId=sid1; sso_sessionid=sid1"), s3);

        System.out.println("[4] null 凭据：空串");
        ok("null → 空串", AStudioProvider.cookieHeader(null).isEmpty(), "");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
