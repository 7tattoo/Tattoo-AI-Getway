package com.aigetway.tattoo7.account;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务商账号池：同一服务商下支持多个账号，按需轮转。
 * - 登录成功一次就新增一个账号（而不是覆盖）
 * - 上游报 401/403/额度耗尽时标记失败并冷却，自动切到下一个可用账号
 */
public class AccountPool {

    private static final String PREFS = "account_pools";
    private static final int COOLDOWN_MS = 5 * 60 * 1000;
    private static final int MAX_FAIL = 2;

    private final String providerId;
    private final android.content.SharedPreferences prefs;
    private final List<Account> accounts = new ArrayList<>();
    private String activeId = "";

    public AccountPool(android.content.SharedPreferences prefs, String providerId) {
        this.prefs = prefs;
        this.providerId = providerId;
        load();
    }

    public static class Account {
        public String id = UUID.randomUUID().toString();
        public String label = "";
        public String cred = "{}";
        /** 上游 user_id（uid）。同一 uid 再次登录必须覆盖而不是新增，否则账号列表会自我复制。 */
        public String uid = "";
        public boolean enabled = true;
        public long lastUsedAt;
        public int failCount;
        public long cooldownUntil;
        /** 首次登录时间（epoch ms）。对拍参考实现的 createdAt。 */
        public long createdAt = 0L;
        /** 登录用户身份（如「成员/管理员」），对拍参考实现的 officeRole。 */
        public String officeRole = "";

        public boolean available() {
            if (!enabled) return false;
            long now = System.currentTimeMillis();
            if (now < cooldownUntil) return false;
            return true;
        }

        /** 冷却过期后清零 failCount。对齐 v1.29：v1.29 没冷却，401 就报一次。
         *  旧逻辑不重置 failCount，同一账号会反复 failCount >= MAX_FAIL →
         *  又一轮冷却 → 用户看到"两个账号总有一个自动过期、刷不出来"
         *  （实际是被自己永久冻住）。 */
        public boolean clearCooldownIfExpired() {
            if (cooldownUntil == 0L && failCount == 0) return false;
            if (System.currentTimeMillis() < cooldownUntil) return false;
            if (failCount == 0 && cooldownUntil == 0L) return false;
            failCount = 0;
            cooldownUntil = 0L;
            return true;
        }

        public boolean canRefresh() {
            try {
                String rt = new JSONObject(cred).optString("refreshToken", "");
                return !rt.isEmpty();
            } catch (Exception e) {
                return false;
            }
        }

        public String nickname() {
            try {
                return new JSONObject(cred).optString("nickname", "");
            } catch (Exception e) {
                return "";
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("uid", uid);
            o.put("cred", cred);
            o.put("enabled", enabled);
            o.put("lastUsedAt", lastUsedAt);
            o.put("failCount", failCount);
            o.put("cooldownUntil", cooldownUntil);
            o.put("createdAt", createdAt);
            o.put("officeRole", officeRole);
            return o;
        }

        static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id", UUID.randomUUID().toString());
            a.label = o.optString("label", "");
            a.uid = o.optString("uid", "");
            a.cred = o.optString("cred", "{}");
            a.enabled = o.optBoolean("enabled", true);
            a.lastUsedAt = o.optLong("lastUsedAt", 0L);
            a.failCount = o.optInt("failCount", 0);
            a.cooldownUntil = o.optLong("cooldownUntil", 0L);
            a.createdAt = o.optLong("createdAt", 0L);
            a.officeRole = o.optString("officeRole", "");
            return a;
        }
    }

    /**
     * vc141：落库层最后一道闸 —— 绝不允许「空 rt 覆盖已有非空 rt」。
     * 登录时换票通道(exchangeCode→saveLogin)与网页抓取通道(saveTokenFromWeb)并发写同一
     * 账号：网页抓取常拿不到 rt（HttpOnly / 时序早），且 sameIdentity 判定可能失败
     * （uid 抖动 / token 不同）→ 走空对象 → 按 uid 覆盖，把刚换票写好的 rt 洗成空，
     * 续期永久失败（真机 18:50：rtLen=432 落库 1 秒后被 onStorage 洗空 → refreshAll 跳过）。
     * 合法刷新必然携带非空 rt，不受影响；只有「降级写空」被拦下。
     * 需要显式清空 rt 的场景走 {@link #clearRefreshToken}。
     */
    private static String preserveRt(String oldCred, String newCred) {
        try {
            org.json.JSONObject nc = new org.json.JSONObject(newCred);
            if (!nc.optString("refreshToken", "").isEmpty()) return newCred;
            org.json.JSONObject oc = new org.json.JSONObject(oldCred);
            String ort = oc.optString("refreshToken", "");
            if (ort.isEmpty()) return newCred;
            // vc142：脏 rt 复活闸 —— 旧 rt 若是「结构性脏」（值 = 旧 access，或互为前缀），
            // 说明它正是 vc136 清理逻辑刚清掉的垃圾；preserveRt 绝不能把它填回新凭据，
            // 否则「清空 → 网页抓取空 rt 落库 → preserveRt 复活脏 rt → 401 强刷又报
            // 续期凭据其实是 access_token → 再清空」死循环（15:40:19/15:40:22 连报现场）。
            String oat = oc.optString("accessToken", "");
            if (!oat.isEmpty() && (ort.equals(oat)
                    || (ort.length() > 40 && (oat.startsWith(ort) || ort.startsWith(oat))))) {
                return newCred;
            }
            nc.put("refreshToken", ort);
            return nc.toString();
        } catch (Exception e) {
            return newCred;
        }
    }

    /** 显式清空某账号的 refreshToken（脏 rt 清理用；不受 {@link #preserveRt} 保护）。 */
    public synchronized void clearRefreshToken(String id) {
        for (Account a : accounts) {
            if (!a.id.equals(id)) continue;
            try {
                org.json.JSONObject oc = new org.json.JSONObject(a.cred);
                oc.put("refreshToken", "");
                a.cred = oc.toString();
                persist();
            } catch (Exception ignored) {
            }
            return;
        }
    }

    private String key() {
        return "pool_" + providerId;
    }

    private void load() {
        synchronized (this) {
            accounts.clear();
            try {
                String s = prefs.getString(key(), null);
                if (s == null) return;
                JSONObject root = new JSONObject(s);
                activeId = root.optString("active", "");
                JSONArray arr = root.optJSONArray("accounts");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    accounts.add(Account.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : accounts) arr.put(a.toJson());
            JSONObject root = new JSONObject();
            root.put("active", activeId);
            root.put("accounts", arr);
            prefs.edit().putString(key(), root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<Account> all() {
        return new ArrayList<>(accounts);
    }

    public synchronized int size() {
        return accounts.size();
    }

    public synchronized boolean isEmpty() {
        return accounts.isEmpty();
    }

    public synchronized String activeId() {
        return activeId;
    }

    /** 新增一个账号（登录成功时调用）。 */
    public synchronized Account addAccount(String cred, String label) {
        Account a = new Account();
        a.cred = cred;
        a.label = label == null || label.isEmpty() ? ("账号" + (accounts.size() + 1)) : label;
        a.createdAt = System.currentTimeMillis();
        accounts.add(a);
        activeId = a.id;
        persist();
        return a;
    }

    public synchronized void updateCred(String id, String cred) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.cred = preserveRt(a.cred, cred);
                persist();
                return;
            }
        }
    }

    /**
     * CAS 写凭据：只有当账号当前的 accessToken 仍等于 {@code prevAccess} 时才写入。
     *
     * <p>这是「两个账号总有一个自动过期且刷不出新凭证」的根因修复。
     * 旧实现是「读当前账号 → 发网络请求 → 无条件写回 activeId」，
     * 中间只要发生一次账号轮转（{@link #rotate()}，网关在 401 时就会调），
     * 新凭证就会被写到<em>另一个</em>账号头上，而真正被刷的那个账号仍拿着
     * 已被服务端作废的 refresh_token —— 表现就是「有一个号永远过期、永远刷不回来」。
     *
     * <p>CAS 让刷新变成幂等且账号绑定：刷 A 只会写 A，且 A 若已被别的线程刷新过就放弃写入。
     *
     * @return true=写入成功；false=账号已不存在或已被他人刷新（调用方应放弃本次结果）
     */
    public synchronized boolean updateCredIfCurrent(String id, String prevAccess, String cred) {
        for (Account a : accounts) {
            if (!a.id.equals(id)) continue;
            try {
                String cur = new JSONObject(a.cred).optString("accessToken", "");
                if (!cur.equals(prevAccess)) return false;   // 已被其它线程刷新过 → 丢弃
            } catch (Exception e) {
                return false;
            }
            a.cred = cred;
            a.failCount = 0;
            a.cooldownUntil = 0L;
            persist();
            return true;
        }
        return false;
    }

    /**
     * 按上游 uid 覆盖或新增账号（对齐参考实现的 upsert 语义）。
     *
     * <p>旧实现是 {@link #addAccount} 无条件新增：同一账号每登录一次（验证码/密码/微信/扫码
     * 任意一种）就多出一条记录。记录越多，{@link #rotate()} 命中的概率越低，
     * 冷却中的坏号会把可用号挤掉。
     *
     * @param uid 上游 user_id；为空则退化为无条件新增
     * @return 命中的（新建或被覆盖的）账号
     */
    public synchronized Account addOrUpdate(String uid, String cred, String label) {
        if (uid != null && !uid.isEmpty()) {
            for (Account a : accounts) {
                if (uid.equals(a.uid)) {
                    // vc141：落库层最后一道闸 —— 绝不允许「空 rt 覆盖已有非空 rt」。
                    // 见 preserveRt 注释（真机 18:50：换票 rt=432 被网页抓取洗空）。
                    a.cred = preserveRt(a.cred, cred);
                    if (label != null && !label.isEmpty()) a.label = label;
                    a.failCount = 0;
                    a.cooldownUntil = 0L;
                    a.lastUsedAt = System.currentTimeMillis();
                    persist();
                    return a;
                }
            }
        }
        Account created = addAccount(cred, label);
        if (uid != null && !uid.isEmpty()) {
            created.uid = uid;
            persist();
        }
        return created;
    }

    public synchronized void setOfficeRole(String id, String role) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.officeRole = role == null ? "" : role;
                persist();
                return;
            }
        }
    }

    /** 取指定账号的凭据 JSON（不存在返回 null）。 */
    public synchronized JSONObject credOf(String id) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                try {
                    return new JSONObject(a.cred);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    public synchronized void rename(String id, String label) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.label = label;
                persist();
                return;
            }
        }
    }

    public synchronized void remove(String id) {
        accounts.removeIf(a -> a.id.equals(id));
        if (id.equals(activeId)) activeId = accounts.isEmpty() ? "" : accounts.get(0).id;
        persist();
    }

    public synchronized void setEnabled(String id, boolean v) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.enabled = v;
                if (v) a.cooldownUntil = 0L;
                persist();
                return;
            }
        }
    }

    public synchronized void setActive(String id) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                activeId = id;
                a.cooldownUntil = 0L;
                a.failCount = 0;
                persist();
                return;
            }
        }
    }

    /** 当前账号（凭据 JSON）。没有则返回 null。 */
    public synchronized Account current() {
        for (Account a : accounts) if (a.id.equals(activeId)) return a;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    /** 轮转：切到下一个可用账号（跳过冷却中/停用的）。没有则返回 null。 */
    public synchronized Account rotate() {
        int n = accounts.size();
        if (n == 0) return null;
        // 入口先扫一遍：冷却过期的账号清零 failCount，避免「永久冻住」。
        // 这是「两个账号总有一个自动过期、刷不出来」的根因修复。
        boolean changed = false;
        for (Account a : accounts) {
            if (a.clearCooldownIfExpired()) changed = true;
        }
        int start = 0;
        for (int i = 0; i < n; i++) {
            if (accounts.get(i).id.equals(activeId)) {
                start = i;
                break;
            }
        }
        for (int k = 1; k <= n; k++) {
            Account a = accounts.get((start + k) % n);
            if (a.available()) {
                activeId = a.id;
                if (changed) persist();
                return a;
            }
        }
        if (changed) persist();
        return null;
    }

    /** 重试用：从当前账号之后的下一个可用账号（不含当前）。 */
    public synchronized Account next() {
        return rotate();
    }

    public synchronized void markSuccess(String id) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.failCount = 0;
                a.cooldownUntil = 0L;
                a.lastUsedAt = System.currentTimeMillis();
                persist();
                return;
            }
        }
    }

    /** 失败：计数并冷却，避免一直打坏账号。 */
    public synchronized void markFailure(String id) {
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                a.failCount++;
                if (a.failCount >= MAX_FAIL) a.cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
                persist();
                return;
            }
        }
    }

    public synchronized void clear() {
        accounts.clear();
        activeId = "";
        persist();
    }
}