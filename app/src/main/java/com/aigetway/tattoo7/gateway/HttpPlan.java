package com.aigetway.tattoo7.gateway;

/** 处理结果：普通响应或 SSE 流。 */
public class HttpPlan {
    public final HttpResponse response;
    public final Runnable stream;

    private HttpPlan(HttpResponse response, Runnable stream) {
        this.response = response;
        this.stream = stream;
    }

    public static HttpPlan respond(HttpResponse r) {
        return new HttpPlan(r, null);
    }

    public static HttpPlan stream(Runnable r) {
        return new HttpPlan(null, r);
    }

    public boolean isStream() { return stream != null; }
}