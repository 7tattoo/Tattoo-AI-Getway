package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 客户端登录奖励每日去重。
 *
 * <p>对齐参考实现：登录后自动调用 points/grant 时必须按「账号 uid × yyyy-MM-dd」去重，
 * 否则会每天重复刷同一个登录奖励（服务端也按天去重，但我们走对端是为了少发一次请求 + 日志可读）。
 *
 * <p>存储位置：{@code filesDir/raccoon_checkin.json}，结构 {@code {uid: "yyyy-MM-dd", ...}}。
 * 异常一律降级为「未签到」—— 不能因为本地文件读不出来就卡住流程。
 */
public final class RaccoonCheckin {

    private static final String FILE = "raccoon_checkin.json";
    private static final int MAX_ENTRIES = 200;

    private final java.io.File file;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public RaccoonCheckin(java.io.File filesDir) {
        this.file = new File(filesDir, FILE);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public boolean isCheckedInToday(String uid) {
        if (uid == null || uid.isEmpty()) return false;
        lock.lock();
        try {
            JSONObject root = read();
            String d = root.optString(uid, "");
            return today().equals(d);
        } finally {
            lock.unlock();
        }
    }

    public void markCheckedIn(String uid) {
        if (uid == null || uid.isEmpty()) return;
        lock.lock();
        try {
            JSONObject root = read();
            try {
                root.put(uid, today());
            } catch (org.json.JSONException ignored) {
                return;
            }
            // 防止账号列表无限增长，保留最近 MAX_ENTRIES 条
            if (root.length() > MAX_ENTRIES) {
                java.util.List<String> keys = new java.util.ArrayList<>();
                Iterator<String> it = root.keys();
                while (it.hasNext()) keys.add(it.next());
                for (int i = 0; i + MAX_ENTRIES < keys.size(); i++) root.remove(keys.get(i));
            }
            write(root);
        } finally {
            lock.unlock();
        }
    }

    private JSONObject read() {
        try {
            if (!file.exists()) return new JSONObject();
            String s = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void write(JSONObject o) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(),
                    o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
