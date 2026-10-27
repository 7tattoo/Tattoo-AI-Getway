package com.aigetway.tattoo7.gateway;

import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.core.LogSink;
import com.aigetway.tattoo7.core.Settings;
import com.aigetway.tattoo7.key.ApiKey;
import com.aigetway.tattoo7.key.ApiKeyStore;
import com.aigetway.tattoo7.log.AccessLog;
import com.aigetway.tattoo7.provider.PreparedChat;
import com.aigetway.tattoo7.provider.ServiceProvider;
import com.aigetway.tattoo7.provider.UpstreamHttpException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地网关路由：/health、/v1/models、/v1/chat/completions。
 * - chat 按 model 路由到对应服务商
 * - /v1/models 按 API key 授权的服务商分组下发，并带模型能力字段
 * - 每次调用写入访问日志（服务商 + 模型 + key + 状态 + 耗时）
 */
public class Router {

    private final LogSink log;

    public Router() {
        this.log = App.get().logSink();
    }

    public void handle(Socket socket) throws IOException {
        socket.setSoTimeout(300_000);
        // 发热优化：请求处理期间短暂持有 CPU（内部引用计数，空闲时归零放锁）
        com.aigetway.tattoo7.service.GatewayService.wake();
        try {
            ParsedRequest req = HttpIo.readRequest(socket.getInputStream());
            HttpPlan plan = route(req, socket);
            if (plan.isStream()) {
                HttpIo.writeStreamHeaders(socket);
                try {
                    plan.stream.run();
                } catch (Throwable t) {
                    log.log("SSE", "流中断: " + t.getMessage());
                }
                return;
            }
            HttpResponse resp = plan.response;
            HttpIo.writeResponse(socket, resp.status(), statusText(resp.status()),
                    resp.contentType(), resp.body(), false);
        } catch (BadRequestException e) {
            log.log("HTTP", "bad request: " + e.getMessage());
            HttpResponse r = HttpResponse.errorJson(400, e.getMessage(), "bad_request");
            HttpIo.writeResponse(socket, 400, "Bad Request", r.contentType(), r.body(), false);
        } finally {
            com.aigetway.tattoo7.service.GatewayService.doneWake();
        }
    }

    /** key 解析结果：allowed=null 表示不限制服务商分组。 */
    private static class KeyCtx {
        List<String> allowed;
        String label = "匿名";
    }

    private KeyCtx resolveKey(ParsedRequest req) {
        KeyCtx k = new KeyCtx();
        String raw = req.authorizationBearer();
        ApiKeyStore store = App.get().keys();
        // 1) 先按已保存的分组 key 匹配（决定下发哪些服务商）
        ApiKey found = store.find(raw);
        if (found != null) {
            k.label = found.name;
            k.allowed = found.allowsAll() ? null : new ArrayList<>(found.groups);
            return k;
        }
        // 2) 开启「API Key 校验」：必须带有效 key（分组 key 已在上一步匹配通过）
        Settings s = App.get().settings();
        if (s.keyRequired()) {
            String expected = s.apiKey();
            if (expected != null && !expected.isEmpty() && expected.equals(raw)) {
                k.label = "旧版key";
                return k;
            }
            // 校验已开启但请求没带有效 key（含「开了校验却没配任何 key」）→ 401
            return null;
        }
        // 3) 关闭校验 = 「无 Key 调用」：客户端可不填 key，直接放行
        return k;
    }

    private HttpPlan route(ParsedRequest req, Socket socket) {
        String path = req.path();

        if ("OPTIONS".equalsIgnoreCase(req.method())) {
            return HttpPlan.respond(new HttpResponse(204, "text/plain", new byte[0]));
        }

        // /health 不鉴权，方便探活
        if ("/health".equals(path) || "/".equals(path)) {
            return HttpPlan.respond(health());
        }

        KeyCtx key = resolveKey(req);
        if (key == null) {
            log.log("AUTH", "invalid api key");
            return HttpPlan.respond(HttpResponse.errorJson(401, "invalid api key", "unauthorized"));
        }

        if ("/v1/models".equals(path)) {
            return HttpPlan.respond(modelsResponse(key));
        }

        if ("/v1/chat/completions".equals(path)) {
            return chat(req, socket, key);
        }

        // 常见但**未实现**的 OpenAI 端点：返回 501 + 说明，
        // 而不是 404「Not found」——404 会让客户端以为是地址写错了，
        // 用户根本看不出「这个网关只做文本对话」。
        if (path.startsWith("/v1/audio") || path.startsWith("/v1/embeddings")
                || path.startsWith("/v1/images") || path.startsWith("/v1/moderations")
                || path.startsWith("/v1/responses")) {
            String feature = path.startsWith("/v1/audio") ? "语音转写/语音合成"
                    : path.startsWith("/v1/embeddings") ? "向量嵌入"
                    : path.startsWith("/v1/images") ? "图像生成"
                    : path.startsWith("/v1/moderations") ? "内容审核"
                    : "Responses API";
            return HttpPlan.respond(HttpResponse.errorJson(501,
                    "本地网关未实现" + feature + "，仅支持 /v1/chat/completions 文本对话。"
                            + "请把该模型详情页里的非文本能力关闭后再测试。",
                    "not_implemented"));
        }
        return HttpPlan.respond(HttpResponse.errorJson(404, "Not found: " + path, "not_found"));
    }

    private HttpResponse health() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", true);
            obj.put("service", "tattoo-gateway");
            JSONArray ps = new JSONArray();
            for (ServiceProvider p : App.get().registry().all()) {
                JSONObject o = new JSONObject();
                o.put("id", p.id());
                o.put("name", p.displayName());
                o.put("logged_in", p.loggedIn());
                o.put("models", App.get().catalog().grouped()
                        .getOrDefault(p.id(), java.util.Collections.emptyList()).size());
                ps.put(o);
            }
            obj.put("providers", ps);
            obj.put("keys", App.get().keys().size());
        } catch (JSONException ignored) {
        }
        return HttpResponse.json(200, obj);
    }

    private HttpResponse modelsResponse(KeyCtx key) {
        JSONArray arr;
        try {
            arr = App.get().catalog().toModelsJson(key.allowed);
        } catch (Exception e) {
            return HttpResponse.errorJson(500, "catalog error: " + e.getMessage(), "server_error");
        }
        java.util.Set<String> disabled = App.get().settings().disabledModels();
        if (!disabled.isEmpty()) {
            JSONArray filtered = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                if (disabled.contains(m.optString("id", ""))) continue;
                filtered.put(m);
            }
            arr = filtered;
        }
        logAccess("gateway", "网关", "-", "/v1/models", key.label, 200, 0,
                "下发 " + arr.length() + " 个模型");
        JSONObject root = new JSONObject();
        try {
            root.put("object", "list");
            root.put("data", arr);
        } catch (JSONException ignored) {
        }
        return HttpResponse.json(200, root);
    }

    /** 查目录里该模型的上下文窗口（0 = 未知 → 治理器用兜底值）。 */
    private int catalogWindow(String model, java.util.Collection<String> allowed) {
        try {
            for (java.util.List<com.aigetway.tattoo7.catalog.ModelEntry> l
                    : App.get().catalog().grouped(allowed).values()) {
                for (com.aigetway.tattoo7.catalog.ModelEntry m : l) {
                    if (m.id != null && m.id.equals(model)) return m.contextWindow;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private HttpPlan chat(ParsedRequest req, Socket socket, KeyCtx key) {
        if (!"POST".equals(req.method())) {
            return HttpPlan.respond(HttpResponse.errorJson(405, "Method not allowed", "invalid_request"));
        }
        String bodyStr = new String(req.body(), StandardCharsets.UTF_8);
        JSONObject body;
        try {
            body = new JSONObject(bodyStr);
        } catch (JSONException e) {
            return HttpPlan.respond(HttpResponse.errorJson(400, "invalid JSON body", "invalid_request"));
        }

        String model = body.optString("model", "");
        try {
            body.put("stream", true);
        } catch (JSONException ignored) {
        }

        // 网关侧上下文治理：会话膨胀（估算 token 超模型窗口比例）时折叠中段历史，
        // 避免 space-bunny-free 这类大窗口免密模型"永不压缩直到撑爆"。
        // 窗口取自 /v1/models 下发的同一份目录数据（客户端不消费它，我们只能自己用）。
        // 不依赖客户端行为，只在体积真超标时生效，正常会话零副作用。
        try {
            if (!ContextGovernor.govern(body, model, catalogWindow(model, key.allowed))) {
                // vc125：未折叠也要登记本轮会话（PreparedChat 收尾把上游 usage 回灌到账本）
                ContextGovernor.markTurn(body, model, ContextGovernor.estimateRequestTokens(body));
            }
        } catch (Throwable t) {
            // 治理必须永不阻断主流程：任何异常都忽略，继续透传
            LogSink logSink = App.get().logSink();
            try {
                if (logSink != null) logSink.log("CTX", "govern error: " + t);
            } catch (Throwable ignored) {
            }
        }

        // 工具配对兜底自愈：孤立 role:"tool" 结果转成 role:"user"，
        // 防止 zen 上游 400 invalid request（实测 zen 对孤立 tool 唯一报 400）。
        // 必须在治理之后执行（治理可能折叠掉 assistant.tool_calls）。
        try {
            if (ContextGovernor.selfHeal(body)) {
                LogSink logSink = App.get().logSink();
                try {
                    if (logSink != null) logSink.log("CTX", "selfHeal: orphan tool -> user (model=" + model + ")");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            LogSink logSink = App.get().logSink();
            try {
                if (logSink != null) logSink.log("CTX", "selfHeal error: " + t);
            } catch (Throwable ignored) {
            }
        }

        ServiceProvider sp = App.get().registry()
                .byModel(model, App.get().catalog().grouped(key.allowed), key.allowed);
        if (sp == null) {
            // 404 时把「目录里最相近的模型名」一并报出来 ——
            // 「大小写不一致 / 被 hidden 过滤 / 被禁用」三种情况错误文案完全一样，
            // 没有提示就只能靠猜。
            java.util.Map<String, List<com.aigetway.tattoo7.catalog.ModelEntry>> cat =
                    App.get().catalog().grouped(key.allowed);
            java.util.List<String> sim = App.get().registry().similarModels(model, cat);
            // 说明到底为什么找不到：key 分组限制 vs 目录里真的没有
            StringBuilder why = new StringBuilder();
            if (key.allowed != null && !key.allowed.isEmpty()) {
                why.append("\n该 key 的服务商分组限制: ").append(String.join(", ", key.allowed));
            } else if (key.allowed != null) {
                why.append("\n该 key 未授权任何服务商分组 —— 请在网关「Key」页勾选至少一个分组");
            }
            why.append("\n目录现有分组: ").append(cat.keySet());
            logAccess("?", "网关", model, "/v1/chat/completions", key.label, 404, 0,
                    "模型不存在或该 key 无权访问"
                            + (sim.isEmpty() ? "" : "；相近模型: " + sim)
                            + why);
            String tip = (sim.isEmpty() ? "" :
                    "\n相近的模型: " + String.join(", ", sim)) + why
                    + "\n若名称大小写不一致，请改成上面相近模型里的写法；"
                    + "若分组里没有该模型所属的服务商，请在网关「Key」页补上。";
            return HttpPlan.respond(HttpResponse.errorJson(404,
                    "model not found or not permitted for this key: " + model + tip,
                    "model_not_found"));
        }
        if (!sp.loggedIn()) {
            logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions", key.label, 401, 0,
                    "服务商未登录");
            return HttpPlan.respond(HttpResponse.errorJson(401,
                    "provider not logged in: " + sp.displayName(), "provider_unavailable"));
        }

        long t0 = System.currentTimeMillis();
        log.log("CHAT", "[" + sp.displayName() + "] model=" + model + " key=" + key.label
                + " allowed=" + (key.allowed == null ? "*" : String.join(",", key.allowed)));

        final PreparedChat pc;
        try {
            pc = sp.prepareChat(body);
        } catch (UpstreamHttpException e) {
            return upstreamFailure(socket, sp, body, key, model, e, t0);
        } catch (IOException e) {
            log.log("CHAT", "upstream connect error: " + e.getMessage());
            logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions", key.label,
                    502, System.currentTimeMillis() - t0, String.valueOf(e.getMessage()));
            return HttpPlan.respond(HttpResponse.errorJson(502,
                    "cannot reach upstream: " + e.getMessage(), "upstream_error"));
        }

        // 写响应头**之前**预读一帧：加密端点把业务错误（101/105/112/116…）
        // 放在 HTTP 200 的 SSE 信封里，不预读就只能给客户端一个「200 空流」——
        // 界面上正是「可用 0/6、失效:错误」（用户报障之二）。
        try {
            pc.prelude();
        } catch (UpstreamHttpException e) {
            pc.close();
            return upstreamFailure(socket, sp, body, key, model, e, t0);
        } catch (IOException e) {
            pc.close();
            log.log("CHAT", "upstream prelude error: " + e.getMessage());
            logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions", key.label,
                    502, System.currentTimeMillis() - t0, String.valueOf(e.getMessage()));
            return HttpPlan.respond(HttpResponse.errorJson(502,
                    "cannot read upstream: " + e.getMessage(), "upstream_error"));
        }

        return streamWith(socket, pc, sp, model, key, t0);
    }

    /**
     * 上游业务失败后的统一善后：无权限 → 换候选服务商；401/403 → 续期或轮转；
     * 其余（含上游信封里的 402/403 + 业务码）→ 直接把可读原因回给客户端。
     */
    private HttpPlan upstreamFailure(Socket socket, ServiceProvider sp, JSONObject body,
                                     KeyCtx key, String model, UpstreamHttpException e, long t0) {
        {
            // 11102「模型仅对已授权用户开放」= **选错服务商**了：
            // 同名模型可能同时属于多个账号，其中只有部分账号有权限。
            // 静态路由（按目录顺序或探测结果猜）不可靠 —— 探测范围与时机都可能
            // 不覆盖全部候选。改为**反馈驱动**：标记该组合无权限，立刻换下一个候选重试。
            if (isUnauthorized(e)) {
                com.aigetway.tattoo7.provider.ModelProber.markUnauthorized(sp.id(), model);
                PreparedChat alt = tryNextProvider(sp, body, key, model);
                if (alt != null) {
                    return streamWith(socket, alt, sp, model, key, t0);
                }
                logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions",
                        key.label, e.code, System.currentTimeMillis() - t0,
                        "所有候选服务商均无该模型权限");
                return HttpPlan.respond(HttpResponse.errorJson(e.code,
                        "模型 " + model + " 在当前账号下不可用（已尝试所有候选服务商）。"
                                + "请在网关「模型」页对对应服务商点「探测」，或直接隐藏该模型。",
                        "model_unauthorized"));
            }
            // 账号失效（401/403）：先强制刷新当前账号一次（对齐参考实现，
            // 401 后先续期再决定是否轮转），续成功就用新 token 重发请求；
            // 续不动再轮转到下一个账号。
            if (e.code == 401 || e.code == 403) {
                PreparedChat retry = tryRefreshSameThenRotate(sp, body, key, model);
                if (retry != null) {
                    return streamWith(socket, retry, sp, model, key, t0);
                }
            }
            // 额度/积分/余额耗尽（HTTP 402，或 400 + insufficient_points / 积分余额不足）：
            // 「账号额度用完不切号」的根因修复 —— 额度耗尽说明当前账号确定不可用，
            // 强刷 token 也没用（账号还有效，只是没积分），应直接切到下一个账号重试，
            // 而不是把 400 原样回给客户端。所有账号都试完才回原始错误。
            if (e.quotaExhausted) {
                log.log("CHAT", "[" + sp.displayName() + "] 账号额度耗尽（HTTP "
                        + e.code + "），尝试轮转其它账号");
                PreparedChat retry = tryRotate(sp, body, key, model);
                if (retry != null) {
                    log.log("CHAT", "[" + sp.displayName() + "] 已切换到其它账号，重试成功");
                    return streamWith(socket, retry, sp, model, key, t0);
                }
                logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions",
                        key.label, e.code, System.currentTimeMillis() - t0,
                        "所有账号额度耗尽或不可用");
            }
            log.log("CHAT", "upstream error: " + e.getMessage());
            logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions", key.label,
                    e.code, System.currentTimeMillis() - t0, e.friendly);
            return HttpPlan.respond(HttpResponse.errorJson(e.code, e.friendly, "upstream_error"));
        }
    }


    /** 是否是「模型无权限」类错误（11102 / only available for authorized）。 */
    private static boolean isUnauthorized(UpstreamHttpException e) {
        String b = e.friendly == null ? "" : e.friendly;
        return e.code == 400 && (b.contains("11102")
                || b.contains("only available for authorized"));
    }

    /**
     * 11102 后换下一个候选服务商重试。
     *
     * <p>同名模型可能同时属于多个服务商（deepseek-v4.1-flash 在 Buddy 国内版与
     * WorkBuddy 国际版都有），而每个账号的模型权限不同。这里依次尝试候选列表里
     * 除 current 外的服务商，找到能调通的就用它。
     */
    private PreparedChat tryNextProvider(ServiceProvider current, JSONObject body,
                                         KeyCtx key, String model) {
        java.util.List<ServiceProvider> cands = App.get().registry()
                .candidatesFor(model, App.get().catalog().grouped(key.allowed), key.allowed);
        for (ServiceProvider p : cands) {
            if (p == current || !p.loggedIn()) continue;
            com.aigetway.tattoo7.provider.ModelProber.Result pr =
                    com.aigetway.tattoo7.provider.ModelProber.peek(p.id(), model);
            if (pr != null && pr.status == com.aigetway.tattoo7.provider.ModelProber.UNAUTHORIZED) {
                continue;  // 已探明无权限，跳过
            }
            log.log("CHAT", "[" + current.displayName() + "] 模型无权限，改用 ["
                    + p.displayName() + "] 重试 model=" + model);
            try {
                App.get().logSink().log("CHAT", "模型无权限自动切换: "
                        + current.displayName() + " -> " + p.displayName()
                        + " model=" + model);
            } catch (Throwable ignored) {
            }
            try {
                PreparedChat pc = p.prepareChat(body);
                com.aigetway.tattoo7.provider.ModelProber.markUsable(p.id(), model);
                return pc;
            } catch (Exception e) {
                if (e instanceof UpstreamHttpException
                        && isUnauthorized((UpstreamHttpException) e)) {
                    com.aigetway.tattoo7.provider.ModelProber.markUnauthorized(p.id(), model);
                }
            }
        }
        return null;
    }

    /** 401/403 后：先强制刷新当前账号，失败再轮转。 */
    private PreparedChat tryRefreshSameThenRotate(ServiceProvider sp, JSONObject body, KeyCtx key, String model) {
        if ("raccoon".equals(sp.id())) {
            com.aigetway.tattoo7.provider.RaccoonProvider rac = (com.aigetway.tattoo7.provider.RaccoonProvider) sp;
            com.aigetway.tattoo7.account.AccountPool pool = rac.pool();
            try {
                if (rac.forceRefreshActive()) {
                    log.log("CHAT", "[Raccoon] 401 → 强刷当前账号成功，重发请求");
                    PreparedChat retry = sp.prepareChat(body);
                    if (retry != null) return retry;
                } else {
                    log.log("CHAT", "[Raccoon] 401 → 强刷当前账号失败: " + rac.lastRefreshError());
                }
            } catch (Throwable t) {
                log.log("CHAT", "[Raccoon] 401 → force refresh 异常: " + t);
            }
            // 强刷失败再轮转：遍历所有账号重试（markFailure/换号/错误上报都在 tryRotate 里）
            if (pool.size() > 1) {
                return tryRotate(sp, body, key, model);
            }
            return null;
        }
        return tryRotate(sp, body, key, model);
    }

    /** 失败后轮转账号并重试：遍历池中所有账号，直到成功或全部试完。 */
    private PreparedChat tryRotate(ServiceProvider sp, JSONObject body, KeyCtx key, String model) {
        com.aigetway.tattoo7.account.AccountPool pool =
                com.aigetway.tattoo7.account.AccountPoolHolder.get(sp.id());
        int n = pool.size();
        if (n <= 1) return null;
        pool.markFailure(pool.activeId());
        String lastErr = "";
        for (int i = 0; i < n; i++) {
            if (pool.rotate() == null) break;
            log.log("CHAT", "[" + sp.displayName() + "] 账号失效，已轮转到下一个账号");
            try {
                return sp.prepareChat(body);
            } catch (UpstreamHttpException ue) {
                lastErr = ue.getMessage();
                // 该账号同样不可用（401/403 失效、额度耗尽）→ 记一笔失败继续换号；
                // 其它错误（限流/网络/超时）不该继续消耗账号，停下返回原始错误。
                if (ue.code == 401 || ue.code == 403 || ue.quotaExhausted) {
                    pool.markFailure(pool.activeId());
                    continue;
                }
                break;
            } catch (Exception e) {
                lastErr = String.valueOf(e.getMessage());
                break;
            }
        }
        logAccess(sp.id(), sp.displayName(), model, "/v1/chat/completions", key.label,
                -1, 0, "轮转后仍失败: " + lastErr);
        return null;
    }

    private HttpPlan streamWith(Socket socket, PreparedChat pc, ServiceProvider sp,
                                 String model, KeyCtx key, long t0) {

        final long start = t0;
        final String pid = sp.id();
        final String pname = sp.displayName();
        final String klabel = key.label;
        final String mdl = model;
        return HttpPlan.stream(() -> {
            try {
                pc.streamTo(socket.getOutputStream());
                logAccess(pid, pname, mdl, "/v1/chat/completions", klabel,
                        200, System.currentTimeMillis() - start, "完成");
            } catch (IOException e) {
                log.log("SSE", "stream error: " + e.getMessage());
                logAccess(pid, pname, mdl, "/v1/chat/completions", klabel,
                        -1, System.currentTimeMillis() - start, String.valueOf(e.getMessage()));
            }
        });
    }

    private void logAccess(String pid, String pname, String model, String path,
                           String keyLabel, int status, long latency, String note) {
        App.get().accessLog().add(new AccessLog(System.currentTimeMillis(), pid, pname,
                model, path, keyLabel == null ? "" : keyLabel, status, latency, note));
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 429: return "Too Many Requests";
            case 403: return "Forbidden";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return "Error";
        }
    }
}
