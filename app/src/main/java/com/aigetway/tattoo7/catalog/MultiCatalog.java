package com.aigetway.tattoo7.catalog;

import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.provider.ProviderRegistry;
import com.aigetway.tattoo7.provider.ServiceProvider;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 多服务商模型目录：后台并发拉取各服务商模型 + 能力数据，按服务商分组缓存，
 * 并按 API key 授权的分组过滤后下发给 AG 客户端。
 */
public class MultiCatalog {

    private final ProviderRegistry registry;
    private final Map<String, List<ModelEntry>> byProvider = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private volatile boolean refreshing;
    private volatile long lastRefresh;
    /** 拉模型清单的全局并发上限：5 个服务商同时开火会显著发热，这里限 2 个并发。 */
    private static final Semaphore CATALOG_SEM = new Semaphore(2);

    public MultiCatalog(ProviderRegistry registry) {
        this.registry = registry;
        seedFallback();
    }

    /** 先铺本地兜底表，保证 UI 立即有内容。 */
    private void seedFallback() {
        for (ServiceProvider p : registry.all()) {
            List<ModelEntry> l = byProvider.computeIfAbsent(p.id(), k -> new ArrayList<>());
            l.addAll(p.fallbackModels());
        }
    }

    /** 后台刷新全部服务商。 */
    public void refreshAsync(final Runnable onDone) {
        if (refreshing) return;
        refreshing = true;
        errors.clear();
        final List<Thread> threads = new ArrayList<>();
        for (final ServiceProvider p : registry.all()) {
            Thread t = new Thread(() -> {
                try {
                    try {
                        CATALOG_SEM.acquire();
                    } catch (InterruptedException ie) {
                        return;
                    }
                    List<ModelEntry> live;
                    try {
                        live = p.fetchModels();
                    } finally {
                        CATALOG_SEM.release();
                    }
                    if (live != null && !live.isEmpty()) {
                        synchronized (byProvider) {
                            List<ModelEntry> l = new ArrayList<>();
                            // 保留离线兜底中没有的，避免直播表漏模型
                            for (ModelEntry e : live) ModelCaps.enrich(e);
                            l.addAll(live);
                            // ⚠️ 兜底表里 live 没有的条目要**保住**：Loomy 的在线模型名
                            // 与本地兜底表（glm-5.3-flash 等）并不完全一致，直接 put(live)
                            // 会把兜底模型从目录里抹掉 —— 该分组 key 的请求就找不到归属，
                            // 继而（修复前）被兜底路由透传到别的分组。
                            List<ModelEntry> fb = p.fallbackModels();
                            if (fb != null) {
                                for (ModelEntry f : fb) {
                                    if (f == null || f.id == null) continue;
                                    boolean dup = false;
                                    for (ModelEntry e : l) {
                                        if (e != null && e.id != null && e.id.equalsIgnoreCase(f.id)) {
                                            dup = true;
                                            break;
                                        }
                                    }
                                    if (!dup) {
                                        ModelCaps.enrich(f);
                                        l.add(f);
                                    }
                                }
                            }
                            byProvider.put(p.id(), l);
                        }
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    synchronized (errors) {
                        errors.add(p.displayName() + ": " + msg);
                    }
                    // 同时写进运行日志，「记录」页可见 —— 解析类问题靠报错文案定位，
                    // 不必让用户截图猜。
                    try {
                        App.get().logSink().log("CATALOG",
                                p.displayName() + " 模型拉取失败: " + msg);
                    } catch (Throwable ignored) {
                    }
                }
            }, "cat-" + p.id());
            threads.add(t);
            t.start();
        }
        Thread waiter = new Thread(() -> {
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException ignored) {
                }
            }
            refreshing = false;
            lastRefresh = System.currentTimeMillis();
            if (onDone != null) onDone.run();
        }, "cat-join");
        waiter.start();
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    public long lastRefresh() {
        return lastRefresh;
    }

    public List<String> errors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    /** 全部分组（providerId -> models）。 */
    public Map<String, List<ModelEntry>> grouped() {
        synchronized (byProvider) {
            return new LinkedHashMap<>(byProvider);
        }
    }

    public List<ModelEntry> all() {
        List<ModelEntry> out = new ArrayList<>();
        for (List<ModelEntry> l : grouped().values()) out.addAll(l);
        return out;
    }

    /** 只保留 allowed 服务商分组（null = 不限制）。 */
    public Map<String, List<ModelEntry>> grouped(java.util.Collection<String> allowed) {
        Map<String, List<ModelEntry>> all = grouped();
        if (allowed == null) return all;
        Map<String, List<ModelEntry>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ModelEntry>> e : all.entrySet()) {
            if (allowed.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 生成 /v1/models 响应（含能力字段）。 */
    public JSONArray toModelsJson(java.util.Collection<String> allowed) throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, List<ModelEntry>> e : grouped(allowed).entrySet()) {
            for (ModelEntry m : e.getValue()) arr.put(m.toJson());
        }
        return arr;
    }

    /** 找某 model 属于哪个 providerId。 */
    public String providerOf(String modelId, java.util.Collection<String> allowed) {
        for (Map.Entry<String, List<ModelEntry>> e : grouped(allowed).entrySet()) {
            for (ModelEntry m : e.getValue()) if (m.id.equals(modelId)) return e.getKey();
        }
        return null;
    }
}
