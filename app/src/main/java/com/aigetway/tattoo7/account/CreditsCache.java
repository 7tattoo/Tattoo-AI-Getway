package com.aigetway.tattoo7.account;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * 供应商页面积分数据缓存。
 * 需求：热启动时先显示缓存（秒出），再只对过期账号做增量更新；平时切页不自动联网。
 * key = providerId + "|" + accountId。
 */
public class CreditsCache {

    /** 缓存有效期：期内不联网，直接复用。 */
    public static final long TTL_MS = 15 * 60 * 1000L;

    private final File file;
    private final Map<String, Entry> map = new LinkedHashMap<>();

    /** 单个账号的积分缓存快照。 */
    public static class Entry {
        public String credits = "";
        public double creditsTotal = -1;
        /**
         * 两个池的明文（永久 / 每日），与 {@link AccountSnapshot} 同名同义。
         * 缓存期内首屏直接复用，否则「永久 / 每日」明细要等联网回来才出现。
         */
        public String permanent = "";
        public String daily = "";
        public long ts;
    }

    public CreditsCache(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "credits_cache.json");
        load();
    }

    public synchronized Entry get(String providerId, String accountId) {
        return map.get(key(providerId, accountId));
    }

    public synchronized boolean fresh(String providerId, String accountId) {
        Entry e = map.get(key(providerId, accountId));
        return e != null && (System.currentTimeMillis() - e.ts) < TTL_MS;
    }

    public synchronized void put(String providerId, String accountId,
                                 String credits, double total) {
        put(providerId, accountId, credits, total, "", "");
    }

    /** 带两池明细的写入（Loomy：永久 / 每日；其他 provider 传空串即可）。 */
    public synchronized void put(String providerId, String accountId,
                                 String credits, double total,
                                 String permanent, String daily) {
        Entry e = new Entry();
        e.credits = credits == null ? "" : credits;
        e.creditsTotal = total;
        e.permanent = permanent == null ? "" : permanent;
        e.daily = daily == null ? "" : daily;
        e.ts = System.currentTimeMillis();
        map.put(key(providerId, accountId), e);
        save();
    }

    private static String key(String p, String a) {
        return p + "|" + a;
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject o = new JSONObject(sb.toString());
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject eo = o.getJSONObject(k);
                Entry e = new Entry();
                e.credits = eo.optString("credits", "");
                e.creditsTotal = eo.optDouble("total", -1);
                e.permanent = eo.optString("permanent", "");
                e.daily = eo.optString("daily", "");
                e.ts = eo.optLong("ts", 0);
                map.put(k, e);
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void save() {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Entry> en : map.entrySet()) {
                JSONObject eo = new JSONObject();
                eo.put("credits", en.getValue().credits);
                eo.put("total", en.getValue().creditsTotal);
                eo.put("permanent", en.getValue().permanent);
                eo.put("daily", en.getValue().daily);
                eo.put("ts", en.getValue().ts);
                o.put(en.getKey(), eo);
            }
            w.write(o.toString());
            w.flush();
        } catch (Throwable ignored) {
        }
    }
}
