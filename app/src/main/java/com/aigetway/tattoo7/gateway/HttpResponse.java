package com.aigetway.tattoo7.gateway;

import org.json.JSONException;
import org.json.JSONObject;

/** 结构化 HTTP 响应。 */
public class HttpResponse {
    private final int status;
    private final String contentType;
    private final byte[] body;

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
    }

    public int status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return body; }

    public static HttpResponse json(int status, JSONObject obj) {
        return new HttpResponse(status, "application/json; charset=utf-8",
                obj.toString().getBytes());
    }

    public static HttpResponse text(int status, String text) {
        return new HttpResponse(status, "text/plain; charset=utf-8",
                (text == null ? "" : text).getBytes());
    }

    public static HttpResponse errorJson(int status, String message, String type) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("error", new JSONObject()
                    .put("message", message)
                    .put("type", type == null ? "error" : type));
        } catch (JSONException ignored) {}
        return json(status, obj);
    }

    public static HttpResponse okJson(String service, String provider,
                                      int accounts, int models) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", true);
            obj.put("service", service);
            obj.put("provider", provider);
            obj.put("accounts", accounts);
            obj.put("models", models);
        } catch (JSONException ignored) {}
        return json(200, obj);
    }
}