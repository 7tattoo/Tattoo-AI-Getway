package com.aigetway.tattoo7.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 访问日志环形缓冲（内存），供「运行日志」页展示。 */
public class AccessLogStore {

    private static final int MAX = 500;
    private final List<AccessLog> items = new ArrayList<>();
    private final Map<String, int[]> stats = new LinkedHashMap<>(); // providerId+model -> {次数, 失败次数}

    public synchronized void add(AccessLog e) {
        items.add(e);
        while (items.size() > MAX) items.remove(0);
        String k = e.providerId + "|" + e.model;
        int[] v = stats.get(k);
        if (v == null) {
            v = new int[2];
            stats.put(k, v);
        }
        v[0]++;
        if (e.status >= 400 || e.status <= 0) v[1]++;
    }

    public synchronized List<AccessLog> recent(int n) {
        int from = Math.max(0, items.size() - n);
        return new ArrayList<>(items.subList(from, items.size()));
    }

    public synchronized void clear() {
        items.clear();
        stats.clear();
    }

    public synchronized int total() {
        int n = 0;
        for (int[] v : stats.values()) n += v[0];
        return n;
    }

    /** 调用统计：按 服务商/模型 聚合。 */
    public synchronized List<String> statsLines() {
        List<String> out = new ArrayList<>();
        stats.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(en -> {
                    String[] parts = en.getKey().split("\\|", -1);
                    String pid = parts.length > 0 ? parts[0] : "?";
                    String mid = parts.length > 1 ? parts[1] : "?";
                    if (mid.isEmpty()) mid = "-";
                    out.add(pid + " / " + mid + "  调用 " + en.getValue()[0] + " 次，失败 " + en.getValue()[1]);
                });
        return out;
    }
}
