package com.aigetway.tattoo7.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本机 OAuth 回调服务器（127.0.0.1）。
 * 必须在登录前真正监听端口，让服务端的 302 回调能落地 —— 只靠 WebView 的
 * shouldOverrideUrlLoading 收不到 POST 回调，网页会一直停在「认证中」。
 */
public class CallbackServer {

    public interface Listener {
        /** 收到回调（GET/POST 都可能）。返回要回给网页的提示文案。 */
        String onCallback(Map<String, String> params);
    }

    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;
    private Listener listener;

    public synchronized void start(int port, Listener l) throws Exception {
        stop();
        listener = l;
        server = new ServerSocket(port);
        server.setReuseAddress(true);
        running = true;
        thread = new Thread(this::loop, "oauth-callback");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
                handle(s);
            } catch (Exception e) {
                if (running) {
                    // 单个连接失败不致命，继续 accept
                }
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket s) throws Exception {
        s.setSoTimeout(10_000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        int contentLength = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim().toLowerCase(java.util.Locale.US);
            String v = line.substring(i + 1).trim();
            headers.put(k, v);
            if ("content-length".equals(k)) {
                try {
                    contentLength = Integer.parseInt(v);
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

        Map<String, String> params = new LinkedHashMap<>();
        String target = parts.length > 1 ? parts[1] : "/";
        int q = target.indexOf('?');
        if (q >= 0) parseQuery(target.substring(q + 1), params);
        if ("POST".equalsIgnoreCase(method)) {
            int qi = body.indexOf('?');
            String b = qi >= 0 ? body.substring(qi + 1) : body;
            if (b.contains("=")) parseQuery(b, params);
        }
        // 也支持 application/x-www-form-urlencoded
        String ct = headers.get("content-type");
        if (ct != null && ct.contains("x-www-form-urlencoded")) parseQuery(body, params);

        String msg;
        try {
            msg = listener.onCallback(params);
        } catch (Exception e) {
            msg = "处理回调失败: " + e.getMessage();
        }
        if (msg == null) msg = "OK";

        byte[] out = msg.getBytes("UTF-8");
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + out.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    private void parseQuery(String q, Map<String, String> into) {
        if (q == null || q.isEmpty()) return;
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = dec(kv.substring(0, eq));
            String v = dec(kv.substring(eq + 1));
            into.put(k, v);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
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
}