package com.aigetway.tattoo7.account;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** 账号池仓库：同一 providerId 始终返回同一个 AccountPool 实例（避免多实例互相覆盖）。 */
public final class AccountPoolHolder {

    private static final Map<String, AccountPool> POOLS = new HashMap<>();
    /** 每个 provider 一把锁：谁要切 setActive 逐账号查积分/签到，谁就先拿锁，
     *  否则主页后台刷新与账号页 reload 同时切换 active，account() 会读错账号。 */
    private static final Map<String, ReentrantLock> PROVIDER_LOCKS = new HashMap<>();
    private static android.content.SharedPreferences prefs;

    private AccountPoolHolder() {}

    public static synchronized void init(android.content.SharedPreferences p) {
        prefs = p;
    }

    public static synchronized AccountPool get(String providerId) {
        if (prefs == null) {
            throw new IllegalStateException("AccountPoolHolder 未初始化");
        }
        AccountPool p = POOLS.get(providerId);
        if (p == null) {
            p = new AccountPool(prefs, providerId);
            POOLS.put(providerId, p);
        }
        return p;
    }

    public static synchronized void clear(String providerId) {
        get(providerId).clear();
    }

    /** 该 provider 的串行锁：所有「切 active 拉账号数据」的调用方共用同一把。 */
    public static ReentrantLock providerLock(String providerId) {
        synchronized (PROVIDER_LOCKS) {
            ReentrantLock l = PROVIDER_LOCKS.get(providerId);
            if (l == null) {
                l = new ReentrantLock();
                PROVIDER_LOCKS.put(providerId, l);
            }
            return l;
        }
    }
}