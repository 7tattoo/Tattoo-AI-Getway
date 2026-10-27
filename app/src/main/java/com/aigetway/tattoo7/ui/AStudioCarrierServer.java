package com.aigetway.tattoo7.ui;

import android.content.Context;

import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.provider.AStudioProvider;
import com.aigetway.tattoo7.util.QrDiagnostics;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AStudio 登录的**本机载体服务**（127.0.0.1）。
 *
 * <p>为什么要起本地服务而不是直接在页面里打讯飞接口：
 * <ol>
 *   <li>讯飞账号端点的「发送验证码 / 密码登录」都要**极验（geetest）**三参，而极验只能在真正的
 *       浏览器环境里跑（官方 gt.js）。WebView 里的页面是 {@code http://127.0.0.1} 源，
 *       直连 {@code agent.xfyun.cn} 会被同源策略挡住 ⇒ 必须有同源的服务端替它转发。</li>
 *   <li>会话 Cookie 与凭据装配留在原生侧，页面拿不到也不该拿到 rest 凭据。</li>
 * </ol>
 *
 * <p>端点与参考实现（PR 118 的 carrier server）逐条对齐：
 * <pre>
 *   GET  /astudio/login           登录页（assets/astudio_login.html）
 *   GET  /astudio/gee-captcha     极验初始化配置
 *   POST /astudio/sms/send        发短信验证码（multipart → 讯飞）
 *   POST /astudio/sms/login       短信验证码登录
 *   POST /astudio/password/login  账号密码登录
 *   GET  /astudio/wechat/qrcode   微信扫码：取二维码
 *   GET  /astudio/poll            微信扫码：轮询 → 绑定 → 登录
 * </pre>
 */
public class AStudioCarrierServer {

    public interface Listener {
        /** 登录成功且凭据已入池（在后台线程回调）。 */
        void onLoggedIn(String label);
    }

    private final Context ctx;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private Listener listener;
    private volatile String wechatFd = "";

    public AStudioCarrierServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public synchronized void start(int port, Listener l) throws Exception {
        stop();
        listener = l;
        server = new ServerSocket(port);
        server.setReuseAddress(true);
        running = true;
        thread = new Thread(this::loop, "astudio-carrier");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> {
                    try {
                        handle(s);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                }, "astudio-carrier-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            server = null;
        }
    }

    // ---------------- 请求处理 ----------------

    private void handle(Socket s) throws Exception {
        s.setSoTimeout(30000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0].toUpperCase(Locale.US) : "GET";
        String target = parts.length > 1 ? parts[1] : "/";

        int contentLength = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            if ("content-length".equalsIgnoreCase(line.substring(0, i).trim())) {
                try {
                    contentLength = Integer.parseInt(line.substring(i + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String body = "";
        if (contentLength > 0) {
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            body = new String(buf, 0, read);
        }

        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        QrDiagnostics.record("carrier <- " + path
                + (body.isEmpty() ? "" : " body=" + excerpt(body)));

        String out;
        String contentType = "application/json; charset=utf-8";
        try {
            switch (path) {
                case "/astudio/diag":
                    JSONObject d = new JSONObject();
                    d.put("ok", true);
                    d.put("diag", QrDiagnostics.summaryText());
                    out = json(d);
                    break;
                case "/astudio/login":
                case "/astudio/login/":
                case "/":
                    contentType = "text/html; charset=utf-8";
                    out = loginPage();
                    break;
                case "/astudio/gee-captcha":
                    out = json(okObject(AStudioProvider.geetestConfig()));
                    break;
                case "/astudio/sms/send":
                    out = smsSend(body);
                    break;
                case "/astudio/sms/login":
                    out = smsLogin(body);
                    break;
                case "/astudio/password/login":
                    out = passwordLogin(body);
                    break;
                case "/astudio/wechat/qrcode":
                    out = wechatQrcode();
                    break;
                case "/astudio/poll":
                    out = wechatPoll();
                    break;
                default:
                    out = json(errObject("未知端点：" + path));
            }
        } catch (Exception e) {
            QrDiagnostics.record("carrier " + path + " 异常: " + msg(e));
            out = json(errObject(msg(e) + "\n----扫码/登录诊断----\n" + QrDiagnostics.summaryText()));
        }

        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }

    private String smsSend(String bodyJson) throws Exception {
        JSONObject b = new JSONObject(bodyJson);
        AStudioProvider.sendSmsCode(
                b.optString("mobile", ""),
                b.optString("geetestChallenge", ""),
                b.optString("geetestValidate", ""),
                b.optString("geetestSeccode", ""));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", "验证码已发送");
        return json(o);
    }

    private String smsLogin(String bodyJson) throws Exception {
        JSONObject b = new JSONObject(bodyJson);
        JSONObject identity = AStudioProvider.loginWithSms(
                b.optString("mobile", ""), b.optString("verifyCode", ""));
        JSONObject cred = AStudioProvider.completeLogin(identity);
        save(cred);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", "登录成功");
        return json(o);
    }

    private String passwordLogin(String bodyJson) throws Exception {
        JSONObject b = new JSONObject(bodyJson);
        JSONObject identity = AStudioProvider.loginWithPassword(
                b.optString("accountName", ""),
                b.optString("accountPwd", ""),
                b.optString("geetestChallenge", ""),
                b.optString("geetestValidate", ""),
                b.optString("geetestSeccode", ""));
        JSONObject cred = AStudioProvider.completeLogin(identity);
        save(cred);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", "登录成功");
        return json(o);
    }

    private String wechatQrcode() throws Exception {
        String fd = AStudioProvider.generateFd();
        String qrUrl = AStudioProvider.wechatQr(fd);
        wechatFd = fd;
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("fd", fd);
        o.put("qrUrl", qrUrl);
        return json(o);
    }

    private String wechatPoll() throws Exception {
        String fd = wechatFd;
        if (fd == null || fd.isEmpty()) {
            JSONObject o = new JSONObject();
            o.put("status", "pending");
            o.put("message", "未获取二维码");
            return json(o);
        }
        JSONObject r = AStudioProvider.pollWechat(fd);
        String status = r.optString("status", "pending");
        if ("scanned".equals(status)) {
            String uid = r.optString("uid", "");
            String sid = r.optString("ssoSessionId", "");
            if (uid.isEmpty()) {
                // loginState 兜底路径：uid 未知，跳过 bind，completeLogin 会用 userInfo 回填
                QrDiagnostics.record("poll: uid 为空（loginState 兜底），跳过 bind");
            } else {
                AStudioProvider.bindWechat(fd, uid, sid);
            }
            JSONObject cred = AStudioProvider.completeWechatLogin(uid, sid, r.optString("username", ""));
            save(cred);
            JSONObject o = new JSONObject();
            o.put("status", "success");
            return json(o);
        }
        return json(r);
    }

    /** 凭据入池 + 通知界面收工。 */
    private void save(JSONObject cred) {
        AStudioProvider p = App.get().astudioProvider();
        String label = AStudioProvider.labelOf(cred);
        p.addCred(cred, label);
        QrDiagnostics.record("save: 凭据入池 label=" + label);
        wechatFd = "";
        Listener l = listener;
        if (l != null) l.onLoggedIn(label);
    }

    private String loginPage() throws Exception {
        try (InputStream is = ctx.getAssets().open("astudio_login.html")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------------- JSON 小工具 ----------------

    /** 截断长文本，诊断行用。 */
    private static String excerpt(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static JSONObject okObject(JSONObject data) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("data", data);
        return o;
    }

    private static JSONObject errObject(String message) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("message", message);
        return o;
    }

    private static String json(JSONObject o) {
        return o.toString();
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.toString() : m;
    }

    /** 供界面显示的服务地址。 */
    public static String url(int port) {
        return "http://127.0.0.1:" + port + "/astudio/login";
    }

    /** 预留：把 Map 转成 JSON（调试用）。 */
    static JSONObject toJson(Map<String, String> m) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(m).entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }
}
