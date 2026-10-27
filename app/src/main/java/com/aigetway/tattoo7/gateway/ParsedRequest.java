package com.aigetway.tattoo7.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

/** 已解析的 HTTP 请求。 */
public class ParsedRequest {
    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> headers;
    private final byte[] body;

    public ParsedRequest(String method, String path, String query,
                         Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.query = query == null ? "" : query;
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.body = body == null ? new byte[0] : body;
    }

    public String method() { return method; }
    public String path() { return path; }
    public String query() { return query; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body; }

    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    public String authorizationBearer() {
        String h = header("Authorization");
        if (h == null) return null;
        h = h.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) return h.substring(7).trim();
        return null;
    }

    /** 取 query 参数值。 */
    public String queryParam(String name) {
        if (query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) { if (part.equals(name)) return ""; continue; }
            String k = part.substring(0, i);
            String v = part.substring(i + 1);
            if (k.equals(name)) return urlDecode(v);
        }
        return null;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}