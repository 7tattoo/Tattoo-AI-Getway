package com.aigetway.tattoo7.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 极简 HTTP/1.1 读写器（不依赖三方库，足够支撑本地 SSE 网关）。 */
public class HttpIo {

    private static final int MAX_HEADER = 64 * 1024;
    private static final long MAX_BODY = 64L * 1024 * 1024;

    public static ParsedRequest readRequest(InputStream in) throws IOException, BadRequestException {
        // 读请求行
        String requestLine = readLine(in, MAX_HEADER);
        if (requestLine == null || requestLine.isEmpty()) throw new BadRequestException("empty request");

        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) throw new BadRequestException("malformed request line");
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        // 拆 path 与 query
        String path, query;
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        else { path = target; query = null; }

        // 读 headers
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in, MAX_HEADER);
            if (line == null || line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim();
            String value = line.substring(c + 1).trim();
            headers.put(name, value);
        }

        // 读 body
        byte[] body = new byte[0];
        String cl = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Content-Length")) { cl = e.getValue(); break; }
        }
        if (cl != null && !cl.isEmpty()) {
            long len;
            try { len = Long.parseLong(cl.trim()); } catch (NumberFormatException e) {
                throw new BadRequestException("bad content-length");
            }
            if (len < 0 || len > MAX_BODY) throw new BadRequestException("body too large");
            body = readExactly(in, (int) len);
        }

        return new ParsedRequest(method, path, query, headers, body);
    }

    public static void writeResponse(Socket socket, int status, String statusText,
                                     String contentType, byte[] body,
                                     boolean keepAlive) throws IOException {
        OutputStream out = socket.getOutputStream();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** 写 SSE 流式响应头。 */
    public static void writeStreamHeaders(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        String sb = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(sb.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 逐行读 SSE 并透传到 out。返回是否读到数据。 */
    public static void pumpSse(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        boolean tooBig = false;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
            if (baos.size() > max) {
                tooBig = true;
                // 继续读到行尾以同步输入流位置
            }
        }
        if (tooBig) throw new IOException("header too large");
        if (baos.size() == 0 && b < 0) return null;
        return baos.toString("UTF-8");
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        if (off < len) {
            byte[] r = new byte[off];
            System.arraycopy(buf, 0, r, 0, off);
            return r;
        }
        return buf;
    }
}