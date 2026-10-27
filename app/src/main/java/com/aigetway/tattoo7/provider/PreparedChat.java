package com.aigetway.tattoo7.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已建立连接的上游 chat 会话（上游已确认 200）。
 * 网关在调用本类的 streamTo 之前，才会写 SSE 响应头，
 * 保证上游错误能以真实 HTTP 状态码返回，而不是 200 空 body。
 */
public class PreparedChat {
    private static final int HEARTBEAT_MS = 15000;

    final InputStream in;
    final Map<String, String> fingerprintToClient;
    /** 非 null 时把上游私有 SSE 方言翻译成标准 OpenAI chunk。 */
    final SseTranslator translator;

    PreparedChat(InputStream in, Map<String, String> fingerprintToClient) {
        this(in, fingerprintToClient, null);
    }

    /**
     * 逐行模式（vc65 / TRAE 某些端点）：上游**每行一个 data: 帧、帧间没有空行**
     * （参考实现就是按 \n 逐行切、逐行 JSON.parse 的）。默认的按空行分事件会
     * 把整条流拼成一个事件 → JSON 解析失败 → 客户端看到空流。
     */
    final boolean lineOriented;

    PreparedChat(InputStream in, Map<String, String> fingerprintToClient, SseTranslator translator) {
        this(in, fingerprintToClient, translator, false);
    }

    PreparedChat(InputStream in, Map<String, String> fingerprintToClient, SseTranslator translator,
                 boolean lineOriented) {
        this.in = in;
        this.fingerprintToClient = fingerprintToClient;
        this.translator = translator;
        this.lineOriented = lineOriented;
    }

    /** 共享的上游字符流（预读与后续翻译必须是同一个 reader，否则会丢缓冲）。 */
    private BufferedReader readerCache;
    /** prelude 预读下来、需要在流开头回放的原始帧。 */
    private List<String[]> preludeFrames;

    private BufferedReader reader() {
        if (readerCache == null) {
            readerCache = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return readerCache;
    }

    /**
     * 写响应头**之前**调用：让翻译器预读上游帧（用于把 HTTP 200 里包着的业务
     * 错误翻成真实 HTTP 状态码）。抛出的 IOException 会一路传到网关，
     * 由网关按 {@link UpstreamHttpException} 返回给客户端。
     */
    public void prelude() throws IOException {
        if (translator == null) return;
        preludeFrames = translator.prelude(reader());
    }

    /**
     * 放弃本次上游流（预读发现业务错误、改走重试时用），立刻释放连接，
     * 免得每次重试都在上游留一条挂着的 SSE。
     */
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    /** 把上游 SSE 透传/翻译到 out（带心跳 + 工具名还原 + 静默断开兜底）。 */
    public void streamTo(OutputStream out) throws IOException {
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread heartbeat = new Thread(() -> {
            try {
                while (!stop.get()) {
                    Thread.sleep(HEARTBEAT_MS);
                    if (!stop.get()) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "sse-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        try {
            if (translator == null) {
                passthrough(out);
            } else {
                translateStream(out);
            }
        } finally {
            stop.set(true);
            heartbeat.interrupt();
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 纯透传模式（无翻译器）：完全按原样输出，不解析 event:/data: 行。
     * 输出内容与 vc29 及更早版本逐字节一致。
     */
    private void passthrough(OutputStream out) throws IOException {
        BufferedReader reader = reader();
        StringBuilder eventBuf = new StringBuilder();
        int eventCount = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventBuf.length() > 0) {
                    eventCount++;
                    onFrame(eventBuf.toString());
                    out.write(rewrite(eventBuf.toString()).getBytes(StandardCharsets.UTF_8));
                    out.write("\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    eventBuf.setLength(0);
                }
            } else {
                if (eventBuf.length() > 0) eventBuf.append('\n');
                eventBuf.append(line);
            }
        }
        if (eventBuf.length() > 0) {
            eventCount++;
            onFrame(eventBuf.toString());
            out.write(rewrite(eventBuf.toString()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (eventCount == 0) writeEmptyStreamError(out);
        reportUsageToGovernor();
    }

    /** 流结束时把"估算 vs 上游实测"交给会话账本（每轮仅一次）。 */
    private void reportUsageToGovernor() {
        try {
            com.aigetway.tattoo7.gateway.SessionContextLedger.onTurnFinished(
                    requestInputEstimate, usageTotal);
        } catch (Throwable ignored) {
        }
        // vc155：真实用量记入「今日 Tokens 消耗」统计
        // vc159：整轮都没收到 usage 帧时改用本地估算兜底（UI 标注"估算"），
        //        避免上游不回 usage 的服务商把统计永远压在 0。
        try {
            com.aigetway.tattoo7.log.UsageStats us =
                    com.aigetway.tattoo7.core.App.get().usageStats();
            if (usagePromptTotal > 0 || usageTotal > 0) {
                us.add(usagePromptTotal, usageTotal);
            } else {
                long estOut = com.aigetway.tattoo7.gateway.ContextEstimator
                        .charTokens(outChars);
                if (requestInputEstimate > 0 || estOut > 0) {
                    us.addEstimated(requestInputEstimate, estOut);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 翻译模式（私有 SSE 方言 → 标准 OpenAI chunk）。
     * 按空行分事件：event: 行取事件名，data: 行累积 payload（多条 data: 用 \n 连接，
     * 与 SSE 规范一致），其余行（注释等）忽略。
     * 翻译出的 chunk 必须带 "data: " 前缀写回，客户端 SSE 解析器才认。
     */
    private void translateStream(OutputStream out) throws IOException {
        BufferedReader reader = reader();
        String line;
        String eventName = null;
        String dataBuf = null;
        int eventCount = 0;
        try {
            // 回放 prelude 预读下来的帧（顺序不能变）
            if (preludeFrames != null) {
                for (String[] f : preludeFrames) {
                    eventCount += flushEvent(out, f[0], f[1]);
                }
                preludeFrames = null;
            }
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    eventCount += flushEvent(out, eventName, dataBuf);
                    eventName = null;
                    dataBuf = null;
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String d = line.substring(5).trim();
                    if (lineOriented) {
                        // 逐行模式：每行都是独立一帧，写完立刻刷给客户端
                        eventCount += flushEvent(out, null, d);
                    } else {
                        dataBuf = dataBuf == null ? d : dataBuf + "\n" + d;
                    }
                }
                // 其他行（注释、未知字段）忽略
            }
            eventCount += flushEvent(out, eventName, dataBuf);
            // 上游没发 done 帧时（TRAE 工具流常见）补一个收尾 chunk，否则客户端
            // 拿不到 finish_reason，工具调用不会执行。
            List<String> terms = (eventCount > 0 || translator.terminalAlways())
                    ? translator.terminalChunks() : null;
            if (terms != null) {
                for (String term : terms) {
                    if (term == null || term.isEmpty()) continue;
                    out.write(("data: " + rewrite(term)).getBytes(StandardCharsets.UTF_8));
                    out.write("\n\n".getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
            // 参考成品 done 帧发 finish_reason chunk + "data: [DONE]" 哨兵；
            // 翻译模式只补 finish_reason 不发 [DONE]，OpenAI 客户端可能不收尾。
            boolean buffered = translator.terminalAlways();
            if ((eventCount > 0 || buffered) && !doneSentinel) {
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (eventCount == 0 && !buffered) writeEmptyStreamError(out);
            reportUsageToGovernor();
        } finally {
            String diag = translator.diagSummary();
            if (diag != null) {
                try {
                    com.aigetway.tattoo7.core.App.get().logSink().log(translator.logTag(), diag);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 翻译并写出一个事件（一帧可产出多个 chunk）。空列表表示丢弃。返回是否写出了事件。 */
    private int flushEvent(OutputStream out, String eventName, String data) throws IOException {
        onFrame(data);
        List<String> chunks = translator.translate(eventName, data, "");
        if (chunks == null || chunks.isEmpty()) return 0;
        for (String payload : chunks) {
            if (payload == null || payload.isEmpty()) continue;
            if ("[DONE]".equals(payload.trim())) doneSentinel = true;
            out.write(("data: " + rewrite(payload)).getBytes(StandardCharsets.UTF_8));
            out.write("\n\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
        return 1;
    }

    private boolean doneSentinel = false;

    /**
     * 上游 usage 回灌：从任一帧里抓 usage.prompt_tokens（或 input_tokens）。
     * 首个带 usage 的帧交给 ContextGovernor 校准估算系数（每流最多一次）；
     * vc155 起后续 usage 帧也继续跟踪（累计型 usage 帧取最后/最大者），
     * 流结束由 reportUsageToGovernor 把真实用量记入「今日 Tokens 消耗」统计。
     */
    /** 每帧统一入口：先统计输出字符（估算兜底用），再抓上游 usage。 */
    private void onFrame(String block) {
        countOutputChars(block);
        observeUsage(block);
    }

    /**
     * 上游 usage 回灌：从任一帧里抓 usage.prompt_tokens（或 input_tokens）。
     * 首个带 usage 的帧交给 ContextGovernor 校准估算系数（每流最多一次）；
     * vc155 起后续 usage 帧也继续跟踪（累计型 usage 帧取最后/最大者），
     * 流结束由 reportUsageToGovernor 把真实用量记入「今日 Tokens 消耗」统计。
     *
     * ⚠️ vc159 修 bug：透传模式（buddy / raccoon / loomy / autoclaw 四个服务商）
     * 传进来的是**整段原始事件**，带 "data: " 前缀，直接 new JSONObject()
     * 必然抛异常并被 catch 吞掉 → usage 永远抓不到、「今日 Tokens 消耗」恒为 0、
     * ContextGovernor 的 usage 校准也从未生效。现在先剥掉 SSE 行前缀再解析。
     */
    private void observeUsage(String block) {
        if (block == null || block.indexOf("usage") < 0) return;
        String payload = stripSseFraming(block);
        if (payload.isEmpty()) return;
        if (applyUsage(payload)) return;
        // 多帧粘连（逐行模式）时逐行再试
        for (String line : payload.split("\n")) {
            if (!line.isEmpty()) applyUsage(line);
        }
    }

    /** 去掉 data: / event: / id: / 注释 / [DONE] 等 SSE 行前缀，还原成 JSON 文本。 */
    private static String stripSseFraming(String block) {
        StringBuilder sb = new StringBuilder(block.length());
        for (String raw : block.split("\n")) {
            String t = raw.trim();
            if (t.startsWith("data:")) t = t.substring(5).trim();
            if (t.isEmpty() || t.startsWith("event:") || t.startsWith("id:")
                    || t.startsWith("retry:") || t.startsWith(":") || "[DONE]".equals(t)) {
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    /** 解析单个 JSON payload 里的 usage 并累计。返回是否解析成功。 */
    private boolean applyUsage(String json) {
        if (json == null || json.indexOf("usage") < 0) return false;
        try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            org.json.JSONObject u = j.optJSONObject("usage");
            if (u == null) {
                org.json.JSONObject r = j.optJSONObject("response");
                if (r != null) u = r.optJSONObject("usage");
            }
            if (u == null) return false;
            long p = u.optLong("prompt_tokens", 0L);
            if (p <= 0) p = u.optLong("input_tokens", 0L);
            long c = u.optLong("completion_tokens", 0L);
            if (c <= 0) c = u.optLong("output_tokens", 0L);
            if (p > 0) usagePromptTotal = p;                   // 累计型 usage 帧：最后一次即最终值
            if (c > 0) usageTotal = Math.max(usageTotal, c);   // 累计型 completion：取最大者
            if (usageSeen || p <= 0) return true;
            usageSeen = true;
            com.aigetway.tattoo7.gateway.ContextGovernor.observeUsage(
                    requestInputEstimate, p);
            // vc125：把"本轮估算 vs 上游实测"记入会话账本，
            // 下一轮折叠触发与折叠力度都按真实占用闭环决定。
            String ckey = com.aigetway.tattoo7.gateway.ContextGovernor.peekTurnKey();
            if (ckey != null) {
                com.aigetway.tattoo7.gateway.SessionContextLedger.onTurn(
                        ckey, requestInputEstimate, p, usageTotal);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 估算兜底用：粗略累计流式输出正文（content / reasoning_content）的字符数。 */
    private void countOutputChars(String block) {
        if (block == null || block.indexOf("content") < 0) return;
        long n = 0;
        for (String key : CONTENT_KEYS) {
            int i = 0;
            while ((i = block.indexOf(key, i)) >= 0) {
                int st = i + key.length();
                int en = block.indexOf('"', st);
                if (en < st) break;
                n += (en - st);
                i = en + 1;
            }
        }
        if (n > 0) outChars += n;
    }

    private static final String[] CONTENT_KEYS =
            {"\"content\":\"", "\"reasoning_content\":\""};

    private boolean usageSeen = false;
    /** 本流内累计的推理输出 token（completion_tokens，累计型 usage 帧取最大者）。 */
    private volatile long usageTotal = 0L;
    /** 本流上游回报的输入 token（prompt_tokens / input_tokens，取最后一次）。 */
    private volatile long usagePromptTotal = 0L;
    /** 本请求治理后、我们估算的输入 token（用于 usage 校准比值）。 */
    public volatile long requestInputEstimate = 0L;

    /** 本轮流式输出正文字符数（上游不回报 usage 时按它估算输出 token）。 */
    private long outChars = 0L;


    private void writeEmptyStreamError(OutputStream out) throws IOException {
        out.write(("data: {\"error\":{\"message\":\"upstream closed connection without any data event\",\"type\":\"upstream_error\"}}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String rewrite(String block) {
        if (fingerprintToClient == null || fingerprintToClient.isEmpty()) return block;
        String result = block;
        for (Map.Entry<String, String> e : fingerprintToClient.entrySet()) {
            result = result.replace("\"name\":\"" + e.getKey() + "\"", "\"name\":\"" + e.getValue() + "\"");
        }
        return result;
    }
}
