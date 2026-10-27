package com.aigetway.tattoo7.log;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * 今日 Tokens 消耗统计：跨服务商/所有模型累计，按自然日（本机时区）重置。
 *
 * 数据源两条：
 * 1. **实测**：上游 usage 帧的真实 prompt_tokens / completion_tokens（PreparedChat 流结束回灌）；
 * 2. **估算**：上游整轮没回报 usage 时，用本地估算兜底记账（输入=治理后估算，
 *    输出=流式正文字符折算）。估算单独计数，UI 上标注「其中 N 次为本地估算」，不冒充实测。
 *
 * 只保留当天一份，文件极小；每次记账即落盘（每流至多一次，开销可忽略）。
 */
public class UsageStats {

    private final File file;
    private String day = "";
    /** 实测累计。 */
    private long prompt;
    private long completion;
    private long requests;
    /** 估算累计（与实测分开存，便于 UI 标注与排障）。 */
    private long estPrompt;
    private long estCompletion;
    private long estRequests;

    public UsageStats(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "usage_stats.json");
        load();
    }

    /** 记一次**上游回报了 usage**的调用（p/c 均 <=0 时忽略）。 */
    public synchronized void add(long p, long c) {
        if (p <= 0 && c <= 0) return;
        rollIfNeeded();
        if (p > 0) prompt += p;
        if (c > 0) completion += c;
        requests++;
        save();
    }

    /** 记一次**上游未回报 usage、按本地估算**的调用（p/c 均 <=0 时忽略）。 */
    public synchronized void addEstimated(long p, long c) {
        if (p <= 0 && c <= 0) return;
        rollIfNeeded();
        if (p > 0) estPrompt += p;
        if (c > 0) estCompletion += c;
        estRequests++;
        save();
    }

    /** 输入 token 合计（实测+估算）。 */
    public synchronized long promptTokens() {
        rollIfNeeded();
        return prompt + estPrompt;
    }

    /** 输出 token 合计（实测+估算）。 */
    public synchronized long completionTokens() {
        rollIfNeeded();
        return completion + estCompletion;
    }

    public synchronized long totalTokens() {
        rollIfNeeded();
        return prompt + estPrompt + completion + estCompletion;
    }

    public synchronized long requestCount() {
        rollIfNeeded();
        return requests + estRequests;
    }

    /** 实测回报 usage 的调用次数。 */
    public synchronized long measuredRequestCount() {
        rollIfNeeded();
        return requests;
    }

    /** 走本地估算的调用次数（>0 时 UI 会标注）。 */
    public synchronized long estimatedRequestCount() {
        rollIfNeeded();
        return estRequests;
    }

    /** 跨零点自动清零（懒滚动：任何读写在访问时校对日期）。 */
    private void rollIfNeeded() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        if (!today.equals(day)) {
            day = today;
            prompt = 0;
            completion = 0;
            requests = 0;
            estPrompt = 0;
            estCompletion = 0;
            estRequests = 0;
            save();
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject o = new JSONObject(sb.toString());
            day = o.optString("day", "");
            prompt = o.optLong("prompt", 0);
            completion = o.optLong("completion", 0);
            requests = o.optLong("requests", 0);
            estPrompt = o.optLong("est_prompt", 0);
            estCompletion = o.optLong("est_completion", 0);
            estRequests = o.optLong("est_requests", 0);
        } catch (Throwable ignored) {
        }
    }

    private synchronized void save() {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            JSONObject o = new JSONObject();
            o.put("day", day);
            o.put("prompt", prompt);
            o.put("completion", completion);
            o.put("requests", requests);
            o.put("est_prompt", estPrompt);
            o.put("est_completion", estCompletion);
            o.put("est_requests", estRequests);
            w.write(o.toString());
            w.flush();
        } catch (Throwable ignored) {
        }
    }
}
