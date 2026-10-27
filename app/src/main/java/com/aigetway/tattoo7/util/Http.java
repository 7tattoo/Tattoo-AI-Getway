package com.aigetway.tattoo7.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 极简 JSON HTTP 客户端（JDK only）。
 *
 * <p>小浣熊（xiaohuanxiong.com → CNAME volcwaf.com）前置火山 WAF，对同一 IP 的
 * <b>新建连接速率</b>有风控：短时间内连打一批新 TCP+TLS 会被直接丢 SYN，客户端表现为
 * {@code java.net.ConnectException: Failed to connect to xiaohuanxiong.com/...:443}。
 * 多账号并发刷新正好踩这个阈值（症状：2 个账号全挂，删到 1 个又好了）。
 *
 * <p>对策三条：
 * <ol>
 *   <li><b>keep-alive 复用</b>：body 读完不再 disconnect()，让连接回到 HttpURLConnection
 *       连接池复用 —— 长期只维持 1 条 TCP，而不是每个请求一条；</li>
 *   <li><b>同 host 串行 + 建连节流</b>：每 host 一个公平信号量（同时只 1 个在途），
 *       且两次请求发起至少间隔 NEW_CONN_GAP_MS，避免成突发刺穿 WAF 阈值；</li>
 *   <li><b>连接层失败退避重试</b>：未拿到响应码就失败（SYN 被丢 / 复用到已被服务端关闭的连接）
 *       时按 BACKOFF 退避重试，WAF 的丢包是秒级恢复的，重试即可掩盖。</li>
 * </ol>
 */
public final class Http {

    private Http() {}

    /** 同 host 请求最小间隔（毫秒）：把新建连接速率压到 WAF 阈值之下。 */
    private static final long NEW_CONN_GAP_MS = 150L;
    /** 连接层失败的重试退避（毫秒）。 */
    private static final long[] BACKOFF = {400L, 1200L, 2500L};
    /** 等待同 host 闸门的上限（毫秒）；所有 Http 调用都是 15-30s 短请求，60s 足够。 */
    private static final long GATE_WAIT_MS = 60000L;

    private static final ConcurrentHashMap<String, Semaphore> GATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAST_START = new ConcurrentHashMap<>();

    /** 连接层失败（可重试）标记。 */
    private static final class ConnFail extends IOException {
        ConnFail(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class Resp {
        public int code;
        public String body = "";
        public String error = "";
        /** vc136：响应头（小写键）。exchangeCode 需要从 Set-Cookie 里兜底抓 refresh_token。 */
        public final Map<String, java.util.List<String>> headers = new java.util.HashMap<>();

        /** 取 Set-Cookie 头（可能多条）。 */
        public java.util.List<String> setCookies() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie") && e.getValue() != null) {
                    out.addAll(e.getValue());
                }
            }
            return out;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        public Resp fail(String e) {
            error = e;
            return this;
        }
    }

    public static Resp get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return doReq("GET", url, headers, null, timeoutMs);
    }

    public static Resp postJson(String url, Map<String, String> headers, String json, int timeoutMs)
            throws IOException {
        Map<String, String> h = headers;
        if (h == null) h = new java.util.HashMap<>();
        else h = new java.util.HashMap<>(headers);
        h.put("Content-Type", "application/json");
        return doReq("POST", url, h, json == null ? "{}" : json, timeoutMs);
    }

    /** POST 无 body（对齐参考实现：grant 请求 body=null，桌面 grant 不带 Content-Type）。 */
    public static Resp post(String url, Map<String, String> headers, int timeoutMs)
            throws IOException {
        return doReq("POST", url, headers, null, timeoutMs);
    }

    /**
     * POST 原始 body，**不改 Content-Type**（由调用方在 headers 里给）。
     *
     * <p>给 AStudio 登录用：讯飞账号端点的三个登录接口要的是
     * {@code multipart/form-data; boundary=…}，而 {@link #postJson} / {@link #postForm}
     * 都会把 Content-Type 覆写掉。
     */
    public static Resp postRaw(String url, Map<String, String> headers, String body, int timeoutMs)
            throws IOException {
        return doReq("POST", url, headers, body, timeoutMs);
    }

    public static Resp postForm(String url, Map<String, String> headers, String form, int timeoutMs)
            throws IOException {
        Map<String, String> h = new java.util.HashMap<>();
        if (headers != null) h.putAll(headers);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return doReq("POST", url, h, form, timeoutMs);
    }

    private static Resp doReq(String method, String url, Map<String, String> headers, String body,
            int timeoutMs) throws IOException {
        String host = hostOf(url);
        Semaphore gate = gateFor(host);
        boolean held = false;
        try {
            try {
                held = gate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted: " + url);
            }
            if (!held) throw new IOException("同 host 请求排队超时(" + GATE_WAIT_MS + "ms): " + host);

            IOException last = null;
            for (int attempt = 0; attempt <= BACKOFF.length; attempt++) {
                if (attempt > 0) {
                    sleepMs(BACKOFF[attempt - 1]);
                    pace(host);
                } else {
                    pace(host);
                }
                try {
                    return once(method, url, headers, body, timeoutMs);
                } catch (ConnFail cf) {
                    // 未收到响应码的连接层失败：WAF 丢 SYN / 复用死连接，退避后重试
                    last = cf;
                }
            }
            throw last == null ? new IOException("request failed: " + url) : last;
        } finally {
            if (held) gate.release();
        }
    }

    private static Resp once(String method, String url, Map<String, String> headers, String body,
            int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        boolean reusable = false; // body 读完 → 连接可回池
        int code = 0;
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            Resp r = new Resp();
            code = c.getResponseCode();
            r.code = code;
            try {
                for (Map.Entry<String, java.util.List<String>> e
                        : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null) r.headers.put(e.getKey(), e.getValue());
                }
            } catch (Exception ignored) {
            }
            InputStream is = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            if (is != null) {
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    r.body = bo.toString("UTF-8");
                } finally {
                    is.close();
                }
            }
            reusable = true; // 已读到 EOF，socket 归还 keep-alive 池复用
            return r;
        } catch (IOException e) {
            if (code == 0 && isConnFailure(e)) throw new ConnFail(e.toString(), e);
            throw e;
        } finally {
            if (!reusable) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 是否属于「连接层」失败（值得退避重试）。 */
    private static boolean isConnFailure(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
        return m.contains("failed to connect")
                || m.contains("connection refused")
                || m.contains("connect timed out")
                || m.contains("socket timeout")
                || m.contains("no route to host")
                || m.contains("network is unreachable")
                || m.contains("unexpected end of stream")
                || m.contains("socket closed")
                || m.contains("connection reset")
                || m.contains("econnreset")
                || m.contains("econnrefused")
                || m.contains("protocol error")
                || m.contains("stream was reset");
    }

    private static Semaphore gateFor(String host) {
        Semaphore s = GATES.get(host);
        if (s == null) {
            s = new Semaphore(1, true);
            Semaphore prev = GATES.putIfAbsent(host, s);
            if (prev != null) s = prev;
        }
        return s;
    }

    /** 同 host 请求发起节流：距上一次发起至少 NEW_CONN_GAP_MS。 */
    private static void pace(String host) {
        long now = System.currentTimeMillis();
        long last = LAST_START.containsKey(host) ? LAST_START.get(host) : 0L;
        long wait = NEW_CONN_GAP_MS - (now - last);
        if (wait > 0) sleepMs(wait);
        LAST_START.put(host, System.currentTimeMillis());
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    /** 读 JSON，失败抛 IOException。 */
    public static org.json.JSONObject json(Resp r) throws IOException {
        try {
            return new org.json.JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("bad json (HTTP " + r.code + "): "
                    + (r.body.length() > 300 ? r.body.substring(0, 300) : r.body));
        }
    }
}
