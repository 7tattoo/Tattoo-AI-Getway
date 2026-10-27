package com.aigetway.tattoo7.core;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.aigetway.tattoo7.account.CreditsCache;
import com.aigetway.tattoo7.catalog.MultiCatalog;
import com.aigetway.tattoo7.key.ApiKeyStore;
import com.aigetway.tattoo7.log.AccessLogStore;
import com.aigetway.tattoo7.log.UsageStats;
import com.aigetway.tattoo7.provider.BuddyProvider;
import com.aigetway.tattoo7.provider.ProviderRegistry;
import com.aigetway.tattoo7.provider.RaccoonProvider;
import com.aigetway.tattoo7.provider.TraeProvider;
import com.aigetway.tattoo7.provider.LoomyProvider;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App extends Application implements ProviderRegistry.AppBridge {

    private static App instance;
    private Settings settings;
    private LogSink logSink;
    private BuddyProvider buddyProvider;
    private RaccoonProvider raccoonProvider;
    private TraeProvider traeProvider;
    private LoomyProvider loomyProvider;
    private com.aigetway.tattoo7.provider.AutoClawProvider autoclawProvider;
    private com.aigetway.tattoo7.provider.AStudioProvider astudioProvider;
    private ProviderRegistry registry;
    private MultiCatalog catalog;
    private ApiKeyStore apiKeyStore;
    private AccessLogStore accessLog;
    private CreditsCache creditsCache;
    private UsageStats usageStats;
    private ScheduledExecutorService autoRefreshExec;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        settings = new Settings(this);
        logSink = new LogSink();
        try {
            // 日志落盘：/sdcard/Android/data/<pkg>/files/logs/app.log
            // 排障时可直接读文件看到原始状态码与上游报文。
            File logDir = getExternalFilesDir("logs");
            if (logDir == null) logDir = new File(getFilesDir(), "logs");
            logDir.mkdirs();
            logSink.attachFile(new File(logDir, "app.log"));
        } catch (Throwable ignored) {
        }
        com.aigetway.tattoo7.account.AccountPoolHolder
                .init(getSharedPreferences("account_pools", MODE_PRIVATE));
        buddyProvider = new BuddyProvider(this);
        raccoonProvider = new RaccoonProvider(this);
        traeProvider = new TraeProvider(this);
        loomyProvider = new LoomyProvider(this);
        autoclawProvider = new com.aigetway.tattoo7.provider.AutoClawProvider(this);
        astudioProvider = new com.aigetway.tattoo7.provider.AStudioProvider();
        registry = new ProviderRegistry(this);
        catalog = new MultiCatalog(registry);
        apiKeyStore = new ApiKeyStore(this);
        accessLog = new AccessLogStore();
        createChannels();
        startAutoRefresh();
    }

    /**
     * 对拍参考实现：启动时排一个 30 分钟一次的后台任务，把所有 enabled 且有 refresh_token
     * 的账号全部续一遍。**不**只在 active 账号过期时才续期 —— 这就是"两个账号总有一个过期且
     * 刷不出来"的关键修复。
     */
    private void startAutoRefresh() {
        try {
            autoRefreshExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tattoo7-auto-refresh");
                t.setDaemon(true);
                return t;
            });
            // 5 分钟后跑第一次（给前台登录留时间），之后每 30 分钟一次
            autoRefreshExec.scheduleAtFixedRate(this::runAutoRefresh,
                    5, 30, TimeUnit.MINUTES);
        } catch (Throwable t) {
            // 后台调度非核心能力，初始化失败不能阻止 App 启动
            android.util.Log.w("App", "auto-refresh scheduler init failed: " + t);
        }
    }

    private void runAutoRefresh() {
        try {
            if (raccoonProvider != null) {
                // vc143 诊断：refreshAll 前把池子快照打出来（账号数/uid/rt长度），
                // 直接对照「登录落库回读」看 rt 是在哪一步消失的。
                try {
                    java.util.List<com.aigetway.tattoo7.account.AccountPool.Account> all
                            = raccoonProvider.pool().all();
                    StringBuilder sb = new StringBuilder("refreshAll 前快照: accounts=" + all.size());
                    for (com.aigetway.tattoo7.account.AccountPool.Account a : all) {
                        String rt = "";
                        try {
                            org.json.JSONObject cc = raccoonProvider.pool().credOf(a.id);
                            if (cc != null) rt = cc.optString("refreshToken", "");
                        } catch (Exception ignored) {
                        }
                        sb.append(" | acc=").append(a.id.substring(0, Math.min(8, a.id.length())))
                          .append(" uid=").append(a.uid == null ? "" : a.uid)
                          .append(" rtLen=").append(rt.length())
                          .append(" enabled=").append(a.enabled);
                    }
                    com.aigetway.tattoo7.core.App.get().logSink().log("RACCOON-REFRESH", sb.toString());
                } catch (Exception ignored) {
                }
                raccoonProvider.refreshAll();
                // 对齐 v1.29 runOnce 第二步：凭证刷完后顺带刷新模型目录。
                // 长期不打开 UI 时 raccoon 模型清单也能保持最新（v1.29 每 30min
                // 用第一个 enabled 账号拉 /models 并 apply；refreshAsync 内部有
                // refreshing 去重锁 + 失败只记 CATALOG 日志，不影响凭证刷新）。
                if (catalog != null) catalog.refreshAsync(null);
            }
        } catch (Throwable t) {
            android.util.Log.w("App", "auto-refresh run failed: " + t);
        }
        // 第三步：Loomy 的后台续期。refreshFingerprint 内部自带守卫：Loomy 是
        // 14 天长会话直接返回 loggedIn()，所以 30 分钟一轮不会产生多余请求。
        keepLive("loomy");
    }

    /** 后台续期单个服务商，失败只记日志，不影响其它服务商。 */
    private void keepLive(String pid) {
        try {
            if ("loomy".equals(pid) && loomyProvider != null && loomyProvider.loggedIn()) {
                loomyProvider.refreshFingerprint();
            }
        } catch (Throwable t) {
            android.util.Log.i("App", pid + " auto-refresh 跳过: " + t);
        }
    }

    /** 立刻触发一次刷新（供 UI 「刷新账号」按钮调用）。 */
    public void runAutoRefreshNow() {
        if (autoRefreshExec == null) {
            runAutoRefresh();
        } else {
            autoRefreshExec.execute(this::runAutoRefresh);
        }
    }

    public Settings settings() {
        return settings;
    }

    public MultiCatalog catalog() {
        return catalog;
    }

    public BuddyProvider buddyProvider() {
        return buddyProvider;
    }

    public RaccoonProvider raccoonProvider() {
        return raccoonProvider;
    }

    public TraeProvider traeProvider() {
        return traeProvider;
    }

    public LoomyProvider loomyProvider() {
        return loomyProvider;
    }

    public ProviderRegistry registry() {
        return registry;
    }

    public ApiKeyStore keys() {
        return apiKeyStore;
    }

    public AccessLogStore accessLog() {
        return accessLog;
    }

    /** 全局唯一积分缓存：主页与账号页共用（懒初始化）。 */
    public synchronized CreditsCache creditsCache() {
        if (creditsCache == null) creditsCache = new CreditsCache(this);
        return creditsCache;
    }

    /** 今日 Tokens 消耗统计（懒初始化）。 */
    public synchronized UsageStats usageStats() {
        if (usageStats == null) usageStats = new UsageStats(this);
        return usageStats;
    }

    // ---- ProviderRegistry.AppBridge ----

    @Override
    public BuddyProvider buddy() {
        return buddyProvider;
    }

    @Override
    public RaccoonProvider raccoon() {
        return raccoonProvider;
    }

    @Override
    public TraeProvider trae() {
        return traeProvider;
    }

    @Override
    public LoomyProvider loomy() {
        return loomyProvider;
    }

    @Override
    public com.aigetway.tattoo7.provider.AutoClawProvider autoclaw() {
        return autoclawProvider;
    }

    /** 供 UI 直接取（与 loomyProvider() 同款）。 */
    public com.aigetway.tattoo7.provider.AutoClawProvider autoclawProvider() {
        return autoclawProvider;
    }

    @Override
    public com.aigetway.tattoo7.provider.AStudioProvider astudio() {
        return astudioProvider;
    }

    /** 供 UI 直接取（AStudio 登录页要往里写账号）。 */
    public com.aigetway.tattoo7.provider.AStudioProvider astudioProvider() {
        return astudioProvider;
    }

    public LogSink logSink() {
        return logSink;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Settings.CHANNEL_GATEWAY,
                    "本地网关",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("本地 AI 网关运行通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
