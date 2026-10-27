package com.aigetway.tattoo7.account;

/** 账户快照：登录态、积分、签到、永久锁。 */
public class AccountSnapshot {

    public final String providerId;
    public final String displayName;
    public boolean loggedIn;
    /** 积分文案（已格式化，服务商各自口径不同）。 */
    public String credits = "—";
    /** 永久积分（若能区分）。 */
    public String permanentCredits = "";
    /**
     * 当日刷新池余额（Loomy 的「每日赠送」、Raccoon 的「每日积分」）。
     *
     * <p>与 {@link #permanentCredits} 是两个**分开计算**的池：这个池当日有效、
     * 消耗后不回补，次日重新发放；空串表示不支持或上游未给该字段（不要用 0 冒充）。
     */
    public String dailyCredits = "";
    /** 临时积分（即将到期）。 */
    public String expiringCredits = "";
    /** 积分数值（供上层求和；-1 = 未知/查询失败，不要当作 0 使用）。 */
    public double creditsTotal = -1;
    public String accountName = "";
    /**
     * 额度用尽时的升级/充值入口（服务商返回，可点击打开）。
     *
     * <p>额度用尽后部分服务商对所有模型返回 402，客户端无法自行恢复，
     * 唯一出路就是升级套餐/充值，所以把入口直接摆到账号卡片上。
     */
    public String upgradeUrl = "";
    public boolean checkedInToday;
    public String checkinHint = "";
    /**
     * 新手任务进度提示（如「新手任务 3000/10000」）。空串表示不支持或查询失败。
     * 与 {@link #checkinHint} 语义独立：新手任务一次性、签到每日有收益。
     */
    public String onboardingHint = "";
    public boolean permanentLocked;
    public boolean supportsLock;
    public boolean supportsCheckin;
    public String error = "";

    public AccountSnapshot(String providerId, String displayName) {
        this.providerId = providerId;
        this.displayName = displayName;
    }

    public static AccountSnapshot anon(String providerId, String displayName) {
        AccountSnapshot s = new AccountSnapshot(providerId, displayName);
        s.credits = "未登录";
        return s;
    }

    public boolean isReady() {
        return loggedIn && error.isEmpty();
    }
}
