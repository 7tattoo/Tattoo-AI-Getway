package com.aigetway.tattoo7.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.aigetway.tattoo7.R;
import com.aigetway.tattoo7.account.AccountPool;
import com.aigetway.tattoo7.account.AccountPoolHolder;
import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.account.CreditsCache;
import com.aigetway.tattoo7.catalog.ModelEntry;
import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.core.Settings;
import com.aigetway.tattoo7.log.AccessLog;
import com.aigetway.tattoo7.provider.ServiceProvider;
import com.aigetway.tattoo7.service.GatewayService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 主界面：底部 5 项 dock —— 首页 / 供应商 / 模型 / 记录 / 我的。
 * 所有子页面都在同一个 Activity 里靠 visibility 切换；
 * 供应商详情页与端口/Key 管理页是独立 Activity。
 */
public class MainActivity extends AppCompatActivity {

    /** 每个 tab 的标题/副标题。 */
    private static final String[] TAB_TITLES = {"首页", "供应商", "模型", "记录", "我的"};
    private static final String[] TAB_SUBTITLES = {
            "本地模型网关", "账号与签到", "按服务商分组的模型清单", "调用与请求日志", "功能与设置"};

    private TextView headerTitle;
    private TextView headerSubtitle;
    private TextView headerStatusPill;

    private View pageHome, pageProviders, pageModels, pageRecords, pageMine;

    // 首页
    private TextView heroTitle;
    private TextView heroPill;
    private TextView heroLocalUrl;
    private TextView heroLanUrl;
    private View rowLanAddr;
    private Button btnToggle;
    private TextView stepTitle;
    private TextView stepDesc;
    private TextView stepBadge;
    private View stepCard;
    private TextView tokenTitle;
    private TextView tokenDesc;

    // 模型
    private TextView modelStatusText;
    private LinearLayout modelListContainer;
    private Button refreshBtn;

    // 记录
    private TextView segCalls;
    private TextView segLogs;
    private TextView recordsCount;
    private TextView recordsHint;
    private LinearLayout recordsList;
    private LinearLayout logPanel;
    private TextView accessStatsText;
    private TextView logText;

    // 我的
    private TextView mineAbout;

    // 供应商列表
    private LinearLayout providerList;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private LayoutInflater inflater;
    private boolean running;
    private int currentPort = Settings.DEFAULT_PORT;
    private int currentTab = 0;
    private boolean recordsIsLogs = false;
    private String recordFilter = "all";
    /** 模型分组折叠状态：providerId -> 是否展开。默认全部折叠。 */
    private final Map<String, Boolean> modelExpanded = new LinkedHashMap<>();
    private String modelStatusBase = "";
    /** 供应商/模型页是否已渲染过一次（用于懒加载，避免每次切页都刷网络）。 */
    private boolean providersLoaded = false;
    private boolean modelsLoaded = false;
    /** 供应商页面积分缓存：全局唯一实例放在 App 上，账号页也读同一份（懒初始化）。 */
    private CreditsCache creditsCache() {
        return App.get().creditsCache();
    }
    /** 每个服务商的探测器（providerId -> prober），探测按钮/状态按组取用。 */
    private final Map<String, com.aigetway.tattoo7.provider.ModelProber> probers =
            new LinkedHashMap<>();
    /** 探测进度按钮（providerId -> btn），探测中实时更新。 */
    private final Map<String, Button> probeBtns = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        inflater = LayoutInflater.from(this);

        // ⚠️ 顶部避让必须加在 **headerBar** 上：它和 contentRoot 是兄弟节点，
        // contentRoot 在 headerBar 下面，给 contentRoot 加 padding 顶栏照样被状态栏压住。
        // 用「初始 padding + inset」而不是「当前 padding + inset」，
        // 否则 listener 重复触发（比如横竖屏切换）会让 padding 一次次累加。
        final View headerBar = findViewById(R.id.headerBar);
        final int headerPadTop = headerBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(headerBar, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), headerPadTop + sys.top, v.getPaddingRight(), 0);
            return insets;
        });
        // 底部手势条避让：只补bottom，其余三边保持布局里的原值
        final View dock = findViewById(R.id.bottomDock);
        ViewCompat.setOnApplyWindowInsetsListener(dock, (v, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBottom);
            return insets;
        });

        bindViews();
        bindTabs();
        renderStatus();
        refreshModels(null);
        updateMine();
        // 后台保活豁免（原来挂在网关页，现在没有网关页了，挪到启动时）
        handler.postDelayed(this::ensureKeepAlive, 1200);
    }

    private void bindViews() {
        headerTitle = findViewById(R.id.headerTitle);
        headerSubtitle = findViewById(R.id.headerSubtitle);
        headerStatusPill = findViewById(R.id.headerStatusPill);

        pageHome = findViewById(R.id.pageHome);
        pageProviders = findViewById(R.id.pageProviders);
        pageModels = findViewById(R.id.pageModels);
        pageRecords = findViewById(R.id.pageRecords);
        pageMine = findViewById(R.id.pageMine);

        heroTitle = findViewById(R.id.heroTitle);
        heroPill = findViewById(R.id.heroPill);
        heroLocalUrl = findViewById(R.id.heroLocalUrl);
        heroLanUrl = findViewById(R.id.heroLanUrl);
        rowLanAddr = findViewById(R.id.rowLanAddr);
        btnToggle = findViewById(R.id.btnToggle);
        stepCard = findViewById(R.id.stepCard);
        stepBadge = findViewById(R.id.stepBadge);
        stepTitle = findViewById(R.id.stepTitle);
        stepDesc = findViewById(R.id.stepDesc);
        tokenTitle = findViewById(R.id.tokenTitle);
        tokenDesc = findViewById(R.id.tokenDesc);
        updateTokenCard();

        modelStatusText = findViewById(R.id.modelStatusText);
        modelListContainer = findViewById(R.id.modelList);
        refreshBtn = findViewById(R.id.refreshBtn);

        segCalls = findViewById(R.id.segCalls);
        segLogs = findViewById(R.id.segLogs);
        recordsCount = findViewById(R.id.recordsCount);
        recordsHint = findViewById(R.id.recordsHint);
        recordsList = findViewById(R.id.recordsList);
        logPanel = findViewById(R.id.logPanel);
        accessStatsText = findViewById(R.id.accessStatsText);
        logText = findViewById(R.id.logText);

        mineAbout = findViewById(R.id.mineAbout);

        providerList = findViewById(R.id.providerList);

        // 首页动作
        btnToggle.setOnClickListener(v -> toggle());
        findViewById(R.id.btnCopyAddr).setOnClickListener(v -> copyEndpoint(false));
        findViewById(R.id.rowLocalAddr).setOnClickListener(v -> copyEndpoint(false));
        rowLanAddr.setOnClickListener(v -> copyEndpoint(true));
        findViewById(R.id.btnGoProviders).setOnClickListener(v -> switchTab(1));

        // 模型页
        refreshBtn.setOnClickListener(v -> onRefreshClick());

        // 记录页
        segCalls.setOnClickListener(v -> setRecordsMode(false));
        segLogs.setOnClickListener(v -> setRecordsMode(true));
        findViewById(R.id.recordsRefreshBtn).setOnClickListener(v -> renderRecords());
        bindFilterChip(R.id.chipAll, "all");
        bindFilterChip(R.id.chipOk, "ok");
        bindFilterChip(R.id.chipFail, "fail");
        bindFilterChip(R.id.chipAbort, "abort");
        findViewById(R.id.clearBtn).setOnClickListener(v -> {
            App.get().logSink().clear();
            logText.setText("");
            renderRecords();
        });

        // 我的
        findViewById(R.id.rowFuncSign).setOnClickListener(v -> checkinAll());
        findViewById(R.id.rowFuncSettings).setOnClickListener(v ->
                startActivity(new Intent(this, PortKeyActivity.class)));

        // 供应商列表
        findViewById(R.id.providersRefreshBtn).setOnClickListener(v -> renderProviders(true));
        // 全部签到放在「供应商」页：签到是**账号**行为，不是模型行为
        findViewById(R.id.checkinAllBtn).setOnClickListener(v -> checkinAll());
    }

    private void bindFilterChip(int id, String key) {
        findViewById(id).setOnClickListener(v -> {
            recordFilter = key;
            syncFilterChips();
            renderRecords();
        });
    }

    private void syncFilterChips() {
        int[] ids = {R.id.chipAll, R.id.chipOk, R.id.chipFail, R.id.chipAbort};
        String[] keys = {"all", "ok", "fail", "abort"};
        for (int i = 0; i < ids.length; i++) {
            TextView tv = findViewById(ids[i]);
            boolean on = keys[i].equals(recordFilter);
            tv.setSelected(on);
            tv.setTextColor(ContextCompat.getColor(this,
                    on ? R.color.brand : R.color.text_secondary));
        }
    }

    // ================= Dock 导航 =================

    private void bindTabs() {
        findViewById(R.id.tabHome).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.tabProviders).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.tabModels).setOnClickListener(v -> switchTab(2));
        findViewById(R.id.tabRecords).setOnClickListener(v -> switchTab(3));
        findViewById(R.id.tabMine).setOnClickListener(v -> switchTab(4));
    }

    private void switchTab(int idx) {
        currentTab = idx;
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageProviders.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageModels.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        pageRecords.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        pageMine.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);

        headerTitle.setText(TAB_TITLES[idx]);
        headerSubtitle.setText(TAB_SUBTITLES[idx]);

        int[] tabIds = {R.id.tabHome, R.id.tabProviders, R.id.tabModels,
                R.id.tabRecords, R.id.tabMine};
        int[] iconIds = {R.id.tabHomeIcon, R.id.tabProvidersIcon, R.id.tabModelsIcon,
                R.id.tabRecordsIcon, R.id.tabMineIcon};
        int[] labelIds = {R.id.tabHomeLabel, R.id.tabProvidersLabel, R.id.tabModelsLabel,
                R.id.tabRecordsLabel, R.id.tabMineLabel};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_person, R.drawable.ic_layers,
                R.drawable.ic_history, R.drawable.ic_gear};
        int[] iconsSel = {R.drawable.ic_home_blue, R.drawable.ic_person_blue,
                R.drawable.ic_layers_blue, R.drawable.ic_history_blue, R.drawable.ic_gear_blue};
        for (int i = 0; i < tabIds.length; i++) {
            boolean on = i == idx;
            // 用蓝/灰两套 vector：选中蓝、未选灰
            ((ImageView) findViewById(iconIds[i])).setImageResource(on ? iconsSel[i] : icons[i]);
            TextView lb = findViewById(labelIds[i]);
            lb.setTextColor(ContextCompat.getColor(this,
                    on ? R.color.brand : R.color.text_secondary));
            lb.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            findViewById(tabIds[i]).setSelected(on);
        }

        // 供应商/模型页**不主动刷新**：只在首次进入时渲染一次，
        // 之后靠页面上的「刷新」按钮手动更新（否则每次切页都要等网络）。
        if (idx == 1) {
            if (!providersLoaded) {
                providersLoaded = true;
                renderProviders();
            }
        }
        if (idx == 2) {
            if (!modelsLoaded) {
                modelsLoaded = true;
                renderModelList(App.get().catalog().grouped());
            }
        }
        if (idx == 3) renderRecords();
        if (idx == 4) updateMine();
    }

    // ================= 首页 =================

    private void toggle() {
        Intent svc = new Intent(this, GatewayService.class);
        if (running) {
            svc.setAction(GatewayService.ACTION_STOP);
        } else {
            svc.setAction(GatewayService.ACTION_START);
            svc.putExtra(GatewayService.EXTRA_PORT, App.get().settings().port());
        }
        ContextCompat.startForegroundService(this, svc);
    }

    /** 首页「今日消耗」卡片：今日 Tokens（跨所有模型／服务商累计；实测 + 估算，估算会标注）。 */
    private void updateTokenCard() {
        com.aigetway.tattoo7.log.UsageStats u = App.get().usageStats();
        long total = u.totalTokens();
        long estReq = u.estimatedRequestCount();
        tokenTitle.setText(String.format(Locale.US, "今日Tokens消耗: %,d tokens", total));
        if (total > 0) {
            String note = estReq > 0
                    ? "跨所有模型累计，其中 " + estReq + " 次为本地估算（上游未回报 usage）"
                    : "跨所有模型累计，每日零点重置";
            tokenDesc.setText(String.format(Locale.US,
                    "输入 %,d · 输出 %,d · %,d 次调用（%s）",
                    u.promptTokens(), u.completionTokens(), u.requestCount(), note));
        } else {
            tokenDesc.setText("暂无消耗。通过网关的对话按模型累计，每日零点重置。");
        }
    }

    private void renderStatus() {
        Settings s = App.get().settings();
        int p = running ? currentPort : s.port();
        String local = "http://127.0.0.1:" + p + "/v1";
        heroLocalUrl.setText(local);

        // 局域网地址行：开关打开时才出现，与本机行对齐
        boolean lan = s.lanEnabled();
        if (lan) {
            String ip = getLanIp();
            if (ip == null) {
                rowLanAddr.setVisibility(View.VISIBLE);
                heroLanUrl.setText("未获取到本机 IP（连上 Wi-Fi 后重试）");
            } else {
                rowLanAddr.setVisibility(View.VISIBLE);
                heroLanUrl.setText("http://" + ip + ":" + p + "/v1");
            }
        } else {
            rowLanAddr.setVisibility(View.GONE);
        }

        // Hero 状态
        if (running) {
            heroTitle.setText("● 服务运行中");
            heroPill.setText("已启动");
            btnToggle.setText("停止服务");
            headerStatusPill.setText("● 运行中");
            headerStatusPill.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else {
            heroTitle.setText("○ 服务已停止");
            heroPill.setText("未启动");
            btnToggle.setText("启动服务");
            headerStatusPill.setText("○ 已停止");
            headerStatusPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
        updateStep();
    }

    /** 第一步卡：有没有账号。 */
    private void updateStep() {
        int logged = 0;
        for (ServiceProvider p : App.get().registry().all()) {
            if (p.loggedIn()) logged++;
        }
        if (logged > 0) {
            stepBadge.setText("已完成");
            stepTitle.setText("已连接 " + logged + " 个服务商");
            stepDesc.setText("账号就绪，网关可直接使用。点下方按钮管理更多账号。");
        } else {
            stepBadge.setText("第一步");
            stepTitle.setText("还没有连接账号");
            stepDesc.setText("去「供应商」页登录任意服务商；账号就绪后服务会自动启动。");
        }
    }

    // ================= 地址复制 =================

    /**
     * 点击网关地址 → 复制**完整请求地址**（可直接粘进客户端的 Base URL）。
     * 开启 API Key 校验时，改为复制带 Bearer 的完整 curl 命令。
     */
    private void copyEndpoint(boolean lan) {
        Settings s = App.get().settings();
        int p = running ? currentPort : s.port();
        String base;
        if (!lan) {
            base = "http://127.0.0.1:" + p + "/v1";
        } else {
            String ip = getLanIp();
            if (ip == null) {
                toast("未获取到本机 IP，连上 Wi-Fi 后再试");
                return;
            }
            base = "http://" + ip + ":" + p + "/v1";
        }
        if (s.keyRequired()) {
            String k = s.apiKey();
            if (k != null && !k.isEmpty()) {
                copy("curl " + base + "/chat/completions \\\n"
                        + "  -H 'Content-Type: application/json' \\\n"
                        + "  -H 'Authorization: Bearer " + k + "' \\\n"
                        + "  -d '{\"model\":\"<模型id>\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'");
                return;
            }
        }
        copy(base);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
            toast("已复制到剪贴板");
        }
    }

    private static String getLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ================= 供应商列表 =================

    /** 一行一个服务商；显示「账号 N · 可用 M」，点击进入详情页。 */
    private void renderProviders() {
        renderProviders(false);
    }

    /**
     * 积分后台刷新：受限并发的单例线程池，避免多个服务商同时开火打满 CPU/网络导致发热。
     * 每个服务商内部用一把锁串行切换账号（setActive 是共享状态，并发切会互相覆盖）。
     */
    private final ExecutorService creditsExec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "credits-refresh");
        t.setDaemon(true);
        return t;
    });
    /** 全局并发上限：同一时刻最多 3 个积分 HTTP 请求在跑。 */
    private final Semaphore creditsSem = new Semaphore(3);
    private ReentrantLock providerLock(String pid) {
        return com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(pid);
    }

    /** @param force true = 忽略缓存强制联网刷新（手动点「刷新」按钮）。 */
    private void renderProviders(boolean force) {
        providerList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("正在读取各服务商状态...");
        loading.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        providerList.addView(loading);

        final List<ServiceProvider> all = new ArrayList<>(App.get().registry().all());
        final Map<String, Object[]> done =
                Collections.synchronizedMap(new LinkedHashMap<>());
        // 第一版：**只用本地缓存渲染（不联网）**，保证页面秒出、慢服务商不再拖住整页。
        // 缓存里没积分的账号先显示「账号 X · 可用 Y」，积分由后台异步补齐。
        for (ServiceProvider p : all) {
            AccountPool pool0 = AccountPoolHolder.get(p.id());
            List<AccountPool.Account> accts0 = pool0.all();
            done.put(p.id(), new Object[]{p, accts0.size(),
                    countUsable(accts0), cachedCreditsText(p, accts0)});
        }
        runOnUiThread(() -> fillProviders(new ArrayList<>(done.values())));
        // 第二版：后台渐进刷新过期账号。每个服务商独立后台，拉完一个账号就更新自己那行，
        // Loomy 这类慢服务商不再阻塞其它行，也不会因总超时被整行丢弃。
        for (ServiceProvider p : all) {
            refreshCreditsAsync(p, force, done);
        }
    }

    private static int countUsable(List<AccountPool.Account> accts) {
        int u = 0;
        for (AccountPool.Account a : accts) if (a.available()) u++;
        return u;
    }

    /**
     * 把过期账号的积分拉取丢到受限并发线程池异步执行：UI 不阻塞、全局并发受限（降发热）。
     * 每个账号拉完写入缓存；该服务商全部拉完后刷新对应那一行。
     */
    private void refreshCreditsAsync(ServiceProvider p, boolean force,
                                     Map<String, Object[]> done) {
        final AccountPool pool = AccountPoolHolder.get(p.id());
        final List<AccountPool.Account> accts = new ArrayList<>(pool.all());
        if (accts.isEmpty()) return;
        final String activeId = pool.activeId();
        final List<AccountPool.Account> need = new ArrayList<>();
        for (AccountPool.Account a : accts) {
            CreditsCache.Entry ce = creditsCache().get(p.id(), a.id);
            boolean stale = force || ce == null || !creditsCache().fresh(p.id(), a.id);
            if (stale) need.add(a);
        }
        if (need.isEmpty()) return; // 全部命中缓存，无需联网
        creditsExec.execute(() -> {
            for (AccountPool.Account a : need) {
                try {
                    creditsSem.acquire();
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                    ReentrantLock lk = providerLock(p.id());
                    lk.lock();
                    try {
                        AccountSnapshot snap = null;
                        boolean switched = !a.id.equals(activeId);
                        try {
                            if (switched) pool.setActive(a.id);
                            snap = p.account();
                        } catch (Throwable e) {
                            snap = null;
                        } finally {
                            if (switched) pool.setActive(activeId);
                        }
                        if (snap != null && snap.error.isEmpty() && !snap.credits.isEmpty()) {
                            // 失败/超时的快照不写缓存：写进去会把好数据污染成空积分 15 分钟
                            creditsCache().put(p.id(), a.id, snap.credits, snap.creditsTotal,
                                    snap.permanentCredits, snap.dailyCredits);
                        }
                    } finally {
                        lk.unlock();
                    }
                } finally {
                    creditsSem.release();
                }
            }
            final List<AccountPool.Account> accts2 = new ArrayList<>(pool.all());
            final String credits = cachedCreditsText(p, accts2);
            final int usable = countUsable(accts2);
            runOnUiThread(() -> {
                done.put(p.id(), new Object[]{p, accts2.size(), usable, credits});
                fillProviders(new ArrayList<>(done.values()));
            });
        });
    }

    private void fillProviders(List<Object[]> rows) {
        providerList.removeAllViews();
        for (Object[] r : rows) {
            ServiceProvider p = (ServiceProvider) r[0];
            int total = (Integer) r[1];
            int usable = (Integer) r[2];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.bg_row_white);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            card.setLayoutParams(lp);
            card.setPadding(dp(16), dp(18), dp(12), dp(18));
            card.setClickable(true);
            card.setFocusable(true);

            // 图标
            ImageView icon = new ImageView(this);
            icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(40), dp(40)));
            icon.setBackgroundResource(R.drawable.bg_icon_tile);
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            icon.setImageResource(providerIcon(p.id()));
            card.addView(icon);

            // 名称 + 说明 + 账号数
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(14);
            card.addView(col, clp);

            TextView name = new TextView(this);
            name.setText(p.displayName());
            name.setTextSize(17f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            col.addView(name);

            TextView desc = new TextView(this);
            desc.setText(providerDesc(p.id()));
            desc.setTextSize(13f);
            desc.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            desc.setPadding(0, dp(2), 0, 0);
            col.addView(desc);

            TextView count = new TextView(this);
            String credits = (String) r[3];
            count.setText("账号 " + total + " · 可用 " + usable
                    + (credits.isEmpty() ? "" : "  ·  " + credits));
            count.setTextSize(13f);
            count.setTextColor(ContextCompat.getColor(this,
                    total == 0 ? R.color.text_secondary
                            : (usable > 0 ? R.color.success : R.color.text_secondary)));
            col.addView(count);

            ImageView chev = new ImageView(this);
            chev.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(20), dp(20)));
            chev.setImageResource(R.drawable.ic_chevron);
            card.addView(chev);
            card.setOnClickListener(v -> {
                Intent it = new Intent(this, ProviderDetailActivity.class);
                it.putExtra("provider_id", p.id());
                startActivity(it);
            });
            providerList.addView(card);
        }
        if (rows.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("（没有服务商）");
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            providerList.addView(tv);
        }
    }

    /**
     * 只读缓存计算积分文案（不联网）：所有账号积分总和 + 当前账号积分。
     * 联网拉取由 {@link #refreshCreditsAsync} 在后台受限并发完成，本方法保证 UI 秒出。
     */
    private String cachedCreditsText(ServiceProvider p, List<AccountPool.Account> accts) {
        if (accts == null || accts.isEmpty()) return "";
        AccountPool pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(p.id());
        String activeId = pool.activeId();
        double sum = 0;
        boolean any = false;
        String current = "";
        for (AccountPool.Account a : accts) {
            CreditsCache.Entry ce = creditsCache().get(p.id(), a.id);
            if (ce == null) continue;
            if (ce.creditsTotal >= 0) {
                sum += ce.creditsTotal;
                any = true;
            }
            if (a.id.equals(activeId)) current = ce.credits;
        }
        if (!any) return current.isEmpty() ? "" : ("积分 " + current);
        return "积分 " + trimNum(sum)
                + (accts.size() > 1 && !current.isEmpty() ? "（当前 " + current + "）" : "");
    }

    private static String trimNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.2f", v);
    }

    private int providerIcon(String id) {
        if ("trae".equals(id)) return R.drawable.ic_robot;
        if ("loomy".equals(id)) return R.drawable.ic_chat;
        if ("astudio".equals(id)) return R.drawable.ic_target;
        if ("autoclaw".equals(id)) return R.drawable.ic_sparkle;
        if ("buddy".equals(id)) return R.drawable.ic_sparkle;
        if ("raccoon".equals(id)) return R.drawable.ic_shield;
        return R.drawable.ic_person;
    }

    private String providerDesc(String id) {
        if ("trae".equals(id)) return "网页登录导入，走 SOLO 免费通道";
        if ("loomy".equals(id)) return "手机号短信登录，走免费积分通道";
        if ("astudio".equals(id)) return "内置浏览器登录（短信 / 密码 / 扫码），讯飞 AStudio 积分通道";
        if ("autoclaw".equals(id)) return "手机号短信登录，智谱 AutoClaw 积分通道";
        if ("buddy".equals(id)) return "网页登录，走 WorkBuddy（CodeBuddy）积分通道";
        if ("raccoon".equals(id)) return "网页登录，商汤小浣熊积分通道";
        return id;
    }

    // ================= 模型页（分组折叠） =================

    private void onRefreshClick() {
        App.get().settings().clearDisabledModels();
        refreshModels(null);
    }

    /** 拉取全部服务商的模型与能力数据。 */
    private void refreshModels(Runnable onLoaded) {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("刷新中...");
        modelStatusText.setText("正在向各服务商拉取模型清单与能力...");
        App.get().catalog().refreshAsync(() -> runOnUiThread(() -> {
            renderModelList(App.get().catalog().grouped());
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            List<String> errs = App.get().catalog().errors();
            int total = App.get().catalog().all().size();
            String status;
            if (!errs.isEmpty()) {
                status = "共 " + total + " 个模型（部分失败: " + String.join("; ", errs) + "）· "
                        + fmt.format(new Date(App.get().catalog().lastRefresh()));
            } else {
                status = "共 " + total + " 个模型 · "
                        + fmt.format(new Date(App.get().catalog().lastRefresh())) + " 刷新";
            }
            modelStatusBase = status;
            modelsLoaded = true;
            updateProbeStatus();
            refreshBtn.setEnabled(true);
            refreshBtn.setText("刷新模型清单");
            renderRecords();
            if (onLoaded != null) onLoaded.run();
        }));
    }

    /** 按服务商分组渲染；每组默认折叠，点标题展开/收起。 */
    private void renderModelList(Map<String, List<ModelEntry>> grouped) {
        modelListContainer.removeAllViews();
        if (grouped == null || grouped.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("（还没有模型，点下方「刷新模型清单」）");
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            modelListContainer.addView(tv);
            return;
        }
        Set<String> disabled = App.get().settings().disabledModels();

        for (Map.Entry<String, List<ModelEntry>> en : grouped.entrySet()) {
            final String pid = en.getKey();
            ServiceProvider p = App.get().registry().byId(pid);
            // 默认折叠：只有显式点开过才展开
            final boolean expanded = Boolean.TRUE.equals(modelExpanded.get(pid));

            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackgroundResource(R.drawable.bg_row_white);
            android.widget.LinearLayout.LayoutParams glp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.bottomMargin = dp(12);
            group.setLayoutParams(glp);
            group.setPadding(dp(16), dp(14), dp(16), dp(14));

            // 标题行
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(this);
            icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(26), dp(26)));
            icon.setImageResource(providerIcon(pid));
            head.addView(icon);

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams tlp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = dp(12);
            head.addView(titleCol, tlp);

            TextView title = new TextView(this);
            title.setText(p == null ? pid : p.displayName());
            title.setTextSize(16f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            titleCol.addView(title);

            // 无禁用模型时显示总数；有禁用时显示剩余可用数
            int totalModels = en.getValue().size();
            int disabledHere = 0;
            for (ModelEntry me : en.getValue()) if (disabled.contains(me.id)) disabledHere++;
            StringBuilder sub = new StringBuilder();
            if (disabledHere == 0) {
                sub.append(totalModels).append(" 个模型");
            } else {
                sub.append(totalModels - disabledHere).append(" 个模型可用");
            }
            if (p != null && !p.loggedIn()) sub.append(" · 未登录");
            TextView subTv = new TextView(this);
            subTv.setText(sub.toString());
            subTv.setTextSize(12f);
            subTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            titleCol.addView(subTv);

            TextView arrow = new TextView(this);
            arrow.setText(expanded ? "▾" : "▸");
            arrow.setTextSize(18f);
            arrow.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
            head.addView(arrow);
            group.addView(head);

            // 模型列表容器：折叠时为空
            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            group.addView(body);

            // 探测 / 禁用失效：统一走 ModelProber，后者复用各 provider 的
            // prepareChat，所以鉴权头/设备指纹/加密体与真实调用完全一致。
            if (expanded) {
                LinearLayout prow = new LinearLayout(this);
                prow.setOrientation(LinearLayout.HORIZONTAL);
                final String fpid = pid;
                Button pb = mkBtn("探测", v -> startProbe(fpid));
                probeBtns.put(pid, pb);
                prow.addView(pb);
                // 该分组已有被禁模型时按钮变「解禁」，再点恢复 /v1/models 下发（可反复切换）
                prow.addView(mkBtn(disabledHere > 0 ? "解禁(" + disabledHere + ")" : "禁用失效",
                        v -> disableFailedModels(fpid)));
                TextView pt = new TextView(this);
                String ps = probeStatusText(fpid);
                pt.setText(ps);
                pt.setTextSize(11f);
                pt.setTextColor(ContextCompat.getColor(this, ps.contains("失效 ")
                        ? R.color.danger : R.color.text_secondary));
                pt.setPadding(dp(10), 0, 0, 0);
                prow.addView(pt);
                prow.setPadding(0, dp(8), 0, dp(4));
                body.addView(prow);
            }

            if (expanded) {
                for (ModelEntry e : en.getValue()) {
                    View row = inflater.inflate(R.layout.model_row, body, false);
                    TextView idTv = row.findViewById(R.id.modelId);
                    TextView tagsTv = row.findViewById(R.id.modelTags);
                    TextView capsTv = row.findViewById(R.id.modelCaps);
                    TextView availTv = row.findViewById(R.id.modelAvail);
                    if (idTv != null) {
                        // 倍率拼进模型行主标题（对齐参考实现的「倍率写进 name 不写进
                        // description」结论 —— 它是选型时最重要的信号）。e.id 保持纯净，
                        // 纯展示层拼接，探测/禁用/网关路由仍按裸 id 走。
                        // 倍率部分用红字（#DC2626 danger，与失效/禁用同款语义红）——
                        // 用户要求醒目；id 主体保持原色，Spannable 局部上色。
                        if (e.rateText == null) {
                            idTv.setText(e.id);
                        } else {
                            String full = e.id + " · " + e.rateText;
                            android.text.SpannableString ss = new android.text.SpannableString(full);
                            ss.setSpan(new android.text.style.ForegroundColorSpan(
                                            ContextCompat.getColor(this, R.color.danger)),
                                    e.id.length() + 3, full.length(),
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            idTv.setText(ss);
                        }
                    }
                    if (tagsTv != null) {
                        StringBuilder sb = new StringBuilder();
                        if (e.vision) sb.append("vision");
                        if (e.reasoning) {
                            if (sb.length() > 0) sb.append(" · ");
                            sb.append("reasoning");
                        }
                        if (sb.length() == 0) sb.append("chat");
                        tagsTv.setText(sb.toString());
                    }
                    if (capsTv != null) capsTv.setText(e.capTags());
                    if (availTv != null) {
                        if (disabled.contains(e.id)) {
                            availTv.setText("已禁用");
                            availTv.setTextColor(ContextCompat.getColor(this, R.color.danger));
                        } else if (p != null && !p.loggedIn()) {
                            availTv.setText("未登录");
                            availTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                        } else if (probeResultOf(pid, e.id) != null) {
                            com.aigetway.tattoo7.provider.ModelProber.Result r =
                                    probeResultOf(pid, e.id);
                            if (r.status == com.aigetway.tattoo7.provider.ModelProber.OK) {
                                availTv.setText("探测可用");
                                availTv.setTextColor(ContextCompat.getColor(this, R.color.success));
                            } else {
                                // 同上：把探测拿到的真实原因（如「额度/权益不足（HTTP 402）…」）显示出来。
                                // 额度不足用「额度不足」+警示色，区别于真正的模型失效。
                                boolean q = r.status
                                        == com.aigetway.tattoo7.provider.ModelProber.QUOTA;
                                availTv.setText((q ? "额度不足: " : "失效: ")
                                        + (r.reason.isEmpty()
                                        ? com.aigetway.tattoo7.provider.ModelProber.statusName(r.status)
                                        : r.reason));
                                availTv.setTextColor(ContextCompat.getColor(this,
                                        q ? R.color.warn : R.color.danger));
                            }
                        } else if (false) {
                            availTv.setText("未登录");
                            availTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                        } else {
                            availTv.setText(e.live ? "在线" : "兜底");
                            availTv.setTextColor(ContextCompat.getColor(this,
                                    e.live ? R.color.success : R.color.text_secondary));
                        }
                    }
                    body.addView(row);
                }
            }

            // 点击标题折叠/展开
            head.setOnClickListener(v -> {
                modelExpanded.put(pid, !expanded);
                renderModelList(App.get().catalog().grouped());
            });
            group.setOnClickListener(v -> {
                modelExpanded.put(pid, !expanded);
                renderModelList(App.get().catalog().grouped());
            });
            // 展开时不要让子 view 的点击冒泡到标题
            body.setOnClickListener(v -> {
            });

            modelListContainer.addView(group);
        }
    }

    /** 探测状态摘要，追加到模型状态行。 */
    private void updateProbeStatus() {
        int probed = 0, ok = 0, failed = 0, quota = 0;
        for (com.aigetway.tattoo7.provider.ModelProber pr : probers.values()) {
            probed += pr.probedCount();
            ok += pr.probedCount() - pr.failedCount();
            failed += pr.failedCount();
            quota += pr.quotaCount();
        }
        if (probed == 0) {
            modelStatusText.setText(modelStatusBase);
        } else {
            modelStatusText.setText(modelStatusBase + " · 探测: 可用 " + ok + "/" + probed
                    + (failed > 0 ? "，失效 " + (failed - quota)
                    + (quota > 0 ? "，额度不足 " + quota : "") : ""));
        }
    }

    private com.aigetway.tattoo7.provider.ModelProber.Result probeResultOf(String pid, String modelId) {
        com.aigetway.tattoo7.provider.ModelProber pr = probers.get(pid);
        return pr == null ? null : pr.of(modelId);
    }

    /** 取（或建）某服务商的探测器。 */
    private com.aigetway.tattoo7.provider.ModelProber proberOf(String pid) {
        com.aigetway.tattoo7.provider.ModelProber pr = probers.get(pid);
        if (pr == null) {
            pr = new com.aigetway.tattoo7.provider.ModelProber(pid);
            probers.put(pid, pr);
        }
        return pr;
    }

    /** 探测状态摘要（分组内按钮右侧）。 */
    private String probeStatusText(String pid) {
        com.aigetway.tattoo7.provider.ModelProber pr = probers.get(pid);
        if (pr == null || pr.probedCount() == 0) {
            // ⚠️ 这里的禁用数必须是**本分组内**的：disabledModels 是全局集合
            // （只按 model id 存），直接取 size() 会让每个分组都顶同一个总数，
            // 表现为「本组一个都没禁用却写着 6 个已禁用」（与左侧「解禁(N)」
            // 按钮的分组内计数互相矛盾）。
            int disabledHere = 0;
            Map<String, List<ModelEntry>> grouped = App.get().catalog().grouped();
            List<ModelEntry> list = grouped == null ? null : grouped.get(pid);
            if (list != null) {
                Set<String> dis = App.get().settings().disabledModels();
                for (ModelEntry me : list) if (dis.contains(me.id)) disabledHere++;
            }
            return disabledHere > 0
                    ? "未探测（" + disabledHere + " 个已禁用）" : "未探测";
        }
        return (pr.isProbing() ? "探测中 " + pr.probedCount() + " 个"
                : "可用 " + (pr.probedCount() - pr.failedCount()) + "/" + pr.probedCount()
                + quotaSuffix(pr.quotaCount(), pr.failedCount()));
    }

    /** 状态行后缀：把「账号额度不足」和「模型失效」分开报，别让用户以为模型坏了。 */
    private static String quotaSuffix(int quota, int failed) {
        int real = failed - quota;
        if (quota <= 0) return "，失效 " + failed;
        return "，额度不足 " + quota + (real > 0 ? "，失效 " + real : "");
    }

    /** 探测某服务商的模型可用性（对每个模型发一个极小的 hi 请求）。 */
    private void startProbe(String pid) {
        Map<String, List<ModelEntry>> grouped = App.get().catalog().grouped();
        List<ModelEntry> list = grouped == null ? null : grouped.get(pid);
        if (list == null || list.isEmpty()) {
            toast("请先点「刷新模型清单」拉取 " + pid + " 的模型");
            return;
        }
        com.aigetway.tattoo7.provider.ServiceProvider sp = App.get().registry().byId(pid);
        if (sp != null && !sp.loggedIn()) {
            toast("「" + sp.displayName() + "」未登录，无法探测");
            return;
        }
        Button btn = probeBtns.get(pid);
        if (btn != null) {
            btn.setEnabled(false);
            btn.setText("探测中 0/" + list.size());
        }
        toast("正在探测 " + list.size() + " 个模型的可用性，请稍候...");

        com.aigetway.tattoo7.provider.ModelProber pr = proberOf(pid);
        pr.start(list, () -> runOnUiThread(() -> finishProbe(pid, pr)));
        startProberProgress(pid, list.size());
    }

    /** 该模型在**任意**已登录服务商上是否探测可用（决定能不能全局禁用）。 */
    private boolean usableAnywhere(String modelId, String exceptPid) {
        for (com.aigetway.tattoo7.provider.ServiceProvider sp : App.get().registry().all()) {
            if (!sp.loggedIn() || sp.id().equals(exceptPid)) continue;
            com.aigetway.tattoo7.provider.ModelProber.Result r =
                    com.aigetway.tattoo7.provider.ModelProber.peek(sp.id(), modelId);
            if (r != null && r.status == com.aigetway.tattoo7.provider.ModelProber.OK) {
                return true;
            }
        }
        return false;
    }

    private void finishProbe(String pid, Object cat) {
        Button btn = probeBtns.get(pid);
        if (btn != null) {
            btn.setEnabled(true);
            btn.setText("探测");
        }
        int total, bad;
        com.aigetway.tattoo7.provider.ModelProber p =
                (com.aigetway.tattoo7.provider.ModelProber) cat;
        total = p.probedCount();
        bad = p.failedCount();
        String msg = "探测完成：可用 " + (total - bad) + " / " + total + "，失败 " + bad;
        // 无权限(UNAUTHORIZED)的模型在探测阶段就能判定，直接自动隐藏，
        // 免得它在客户端里被选中、调用时才报 11102。
        int noAuth = 0;
        if (cat instanceof com.aigetway.tattoo7.provider.ModelProber) {
            com.aigetway.tattoo7.provider.ModelProber pr =
                    (com.aigetway.tattoo7.provider.ModelProber) cat;
            java.util.Set<String> dis = new HashSet<>(App.get().settings().disabledModels());
            for (com.aigetway.tattoo7.provider.ModelProber.Result r : pr.allResults()) {
                if (r.status != com.aigetway.tattoo7.provider.ModelProber.UNAUTHORIZED) continue;
                // ⚠️ disabledModels 是**全局**的（只按 model id），而同名模型可能属于
                // 多个服务商。只在「所有已登录服务商都无权限」时才禁用，
                // 否则会把别家能用的模型一起干掉（表现为 404 model not found）。
                if (usableAnywhere(r.modelId, pid)) continue;
                dis.add(r.modelId);
                noAuth++;
            }
            if (noAuth > 0) {
                App.get().settings().setDisabledModels(dis);
                msg += "，其中 " + noAuth + " 个无权限已自动隐藏";
            }
        }
        // 失败示例原因：一行字往往就够定位（额度不足 / 限流 / 无权限 ……）
        if (bad > 0 && cat instanceof com.aigetway.tattoo7.provider.ModelProber) {
            for (com.aigetway.tattoo7.provider.ModelProber.Result r
                    : ((com.aigetway.tattoo7.provider.ModelProber) cat).allResults()) {
                if (r.status == com.aigetway.tattoo7.provider.ModelProber.OK) continue;
                String w = r.reason.isEmpty()
                        ? com.aigetway.tattoo7.provider.ModelProber.statusName(r.status) : r.reason;
                if (w.length() > 90) w = w.substring(0, 90) + "…";
                msg += "\n失败示例：" + w;
                break;
            }
        }
        if (bad > 0 && noAuth == 0) {
            // ⚠️ 「额度/权益不足」（HTTP 402）与「限流」都要单独说清：它们不是模型失效，
            // 额度恢复/稍后重试就好了；若跟着邀请用户「禁用失效」，模型会被永久拉黑，
            // 等额度到账后界面上连模型都看不见了（比报红更难排查）。
            int quota = ((com.aigetway.tattoo7.provider.ModelProber) cat).quotaCount();
            if (quota > 0) {
                msg += "；其中 " + quota + " 个是账号额度/权益不足（HTTP 402），"
                        + "不是模型失效，已从「禁用失效」里排除。"
                        + "去对应服务商页点「签到」领每日积分或开通订阅，到账后重新探测即可";
                if (quota < bad) msg += "；其余 " + (bad - quota) + " 个可点「禁用失效」";
            } else {
                msg += "，可点「禁用失效」一键拉黑";
            }
        }
        toast(msg);
        renderModelList(App.get().catalog().grouped());
        updateProbeStatus();
    }

    private void startProberProgress(String pid, int total) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            com.aigetway.tattoo7.provider.ModelProber pr = probers.get(pid);
            if (pr == null || !pr.isProbing()) return;
            Button btn = probeBtns.get(pid);
            if (btn != null) btn.setText("探测中 " + pr.probedCount() + "/" + total);
            handler.postDelayed(() -> startProberProgress(pid, total), 1000);
        });
    }

    /**
     * 一键把探测失败的模型加入禁用列表（不再下发给客户端）；
     * 该分组已有被禁模型时再点一次 = 解禁，恢复下发。可反复切换。
     */
    private void disableFailedModels(String pid) {
        Set<String> dis = new HashSet<>(App.get().settings().disabledModels());
        // —— 解禁分支：本分组当前已有禁用模型 → 移除并恢复下发 ——
        Map<String, List<ModelEntry>> groupedNow = App.get().catalog().grouped();
        List<ModelEntry> groupNow = groupedNow == null ? null : groupedNow.get(pid);
        if (groupNow != null) {
            Set<String> mine = new HashSet<>();
            for (ModelEntry me : groupNow) if (dis.contains(me.id)) mine.add(me.id);
            if (!mine.isEmpty()) {
                dis.removeAll(mine);
                App.get().settings().setDisabledModels(dis);
                toast("已解禁 " + mine.size() + " 个模型，/v1/models 恢复下发");
                renderModelList(App.get().catalog().grouped());
                updateProbeStatus();
                return;
            }
        }
        Set<String> failed;
        com.aigetway.tattoo7.provider.ModelProber pr = probers.get(pid);
        if (pr == null || pr.probedCount() == 0) {
            toast("请先点「探测」获取模型可用性");
            return;
        }
        failed = new HashSet<>(pr.disableWorthyModelIds());
        if (failed.isEmpty()) {
            // 「额度不足 / 限流」被排除后经常落到这里 —— 必须说清「不是没有失败，而是不该拉黑」，
            // 否则用户会以为是按钮坏了。
            boolean anyFail = probers.get(pid) != null && probers.get(pid).failedCount() > 0;
            toast(anyFail
                    ? "探测失败的都是额度不足/限流类问题，不是模型失效，已跳过（恢复后即可用）"
                    : "探测结果全部可用，无需禁用");
            return;
        }
        dis.addAll(failed);
        App.get().settings().setDisabledModels(dis);
        toast("已禁用 " + failed.size() + " 个失效模型，客户端将不再收到");
        renderModelList(App.get().catalog().grouped());
        updateProbeStatus();
    }

    // ================= 记录页 =================

    private void setRecordsMode(boolean logs) {
        recordsIsLogs = logs;
        segCalls.setTextColor(ContextCompat.getColor(this,
                logs ? R.color.text_secondary : R.color.brand));
        segLogs.setTextColor(ContextCompat.getColor(this,
                logs ? R.color.brand : R.color.text_secondary));
        segCalls.setBackgroundResource(logs ? R.drawable.bg_segment : R.drawable.bg_segment_on);
        segLogs.setBackgroundResource(logs ? R.drawable.bg_segment_on : R.drawable.bg_segment);
        recordsList.setVisibility(logs ? View.GONE : View.VISIBLE);
        logPanel.setVisibility(logs ? View.VISIBLE : View.GONE);
        recordsCount.setVisibility(logs ? View.GONE : View.VISIBLE);
        recordsHint.setVisibility(logs ? View.GONE : View.VISIBLE);
        findViewById(R.id.filterBar).setVisibility(logs ? View.GONE : View.VISIBLE);
        findViewById(R.id.recordsRefreshBtn).setVisibility(logs ? View.GONE : View.VISIBLE);
        renderRecords();
    }

    private void renderRecords() {
        if (recordsIsLogs) {
            logText.setText(App.get().logSink().dump());
            List<String> stats = App.get().accessLog().statsLines();
            accessStatsText.setText(stats.isEmpty() ? "暂无调用" : String.join("\n", stats));
            return;
        }
        List<AccessLog> all = App.get().accessLog().recent(200);
        List<AccessLog> items = new ArrayList<>();
        for (AccessLog e : all) {
            if (matchFilter(e)) items.add(e);
        }
        // 最新的记录排在最前面
        java.util.Collections.reverse(items);
        recordsCount.setText("共 " + items.size() + " 条记录");
        recordsList.removeAllViews();
        if (items.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无调用记录\n用任一 API Key 调用本地网关后，记录会出现在这里。");
            tv.setTextSize(16f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            tv.setBackgroundResource(R.drawable.bg_row_white);
            tv.setPadding(dp(16), dp(20), dp(16), dp(20));
            tv.setLineSpacing(dp(4), 1f);
            recordsList.addView(tv);
            return;
        }
        for (AccessLog e : items) {
            recordsList.addView(recordCard(e));
        }
    }

    private boolean matchFilter(AccessLog e) {
        if ("all".equals(recordFilter)) return true;
        if ("ok".equals(recordFilter)) return e.status >= 200 && e.status < 400;
        if ("fail".equals(recordFilter)) return e.status >= 400;
        // 中断：没有明确 HTTP 状态码（客户端断开 / 上游连接异常）
        return e.status <= 0;
    }

    private View recordCard(AccessLog e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_row_white);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText(e.providerName);
        t1.setTextSize(15f);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        t1.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        head.addView(t1, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(this);
        boolean ok = e.status >= 200 && e.status < 400;
        status.setText(e.status > 0 ? String.valueOf(e.status) : "中断");
        status.setTextSize(13f);
        status.setTextColor(ContextCompat.getColor(this,
                ok ? R.color.success : (e.status > 0 ? R.color.danger : R.color.warn)));
        status.setBackgroundResource(R.drawable.bg_badge_pill);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        head.addView(status);
        card.addView(head);

        TextView t2 = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append(e.path);
        sb.append("  ·  ").append(e.model == null || e.model.isEmpty() ? "-" : e.model);
        if (e.latencyMs > 0) sb.append("  ·  ").append(e.latencyMs).append("ms");
        t2.setText(sb.toString());
        t2.setTextSize(12f);
        t2.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        t2.setPadding(0, dp(6), 0, 0);
        card.addView(t2);

        TextView t3 = new TextView(this);
        StringBuilder f = new StringBuilder();
        f.append(e.time());
        if (!e.keyLabel.isEmpty()) f.append("  ·  ").append(e.keyLabel);
        if (!e.note.isEmpty()) f.append("  ·  ").append(e.note);
        t3.setText(f.toString());
        t3.setTextSize(11f);
        t3.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        card.addView(t3);

        card.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(e.providerName + " · " + e.time())
                .setMessage(e.line())
                .setPositiveButton("好", null)
                .show());
        return card;
    }

    // ================= 一键签到（遍历所有服务商的所有账号） =================

    private void checkinAll() {
        final List<ServiceProvider> ps = App.get().registry().all();
        Toast.makeText(this, "正在为所有服务商的所有账号签到...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<String> lines = new ArrayList<>();
            int okCnt = 0;
            for (ServiceProvider p : ps) {
                if (!p.supportsCheckin()) continue;
                AccountPool pool = com.aigetway.tattoo7.account.AccountPoolHolder.get(p.id());
                List<AccountPool.Account> accts = pool.all();
                if (accts.isEmpty()) {
                    lines.add(p.displayName() + ": 无账号");
                    continue;
                }
                String activeId = pool.activeId();
                for (AccountPool.Account a : accts) {
                    if (!a.available()) {
                        lines.add(p.displayName() + " / " + labelOf(a) + ": 跳过（已停用或冷却中）");
                        continue;
                    }
                    boolean switched = !a.id.equals(activeId);
                    if (switched) pool.setActive(a.id);
                    String msg;
                    try {
                        msg = p.checkin();
                    } catch (Exception e) {
                        msg = "失败: " + e.getMessage();
                    }
                    if (switched) pool.setActive(activeId);
                    if (msg != null && msg.startsWith("签到成功")) okCnt++;
                    lines.add(p.displayName() + " / " + labelOf(a) + ": " + msg);
                }
            }
            final int ok = okCnt;
            final List<String> fl = lines;
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("一键签到结果（成功 " + ok + " 项）")
                        .setMessage(String.join("\n", fl))
                        .setPositiveButton("好", null)
                        .show();
            });
        }, "checkin-all").start();
    }

    private String labelOf(AccountPool.Account a) {
        return a.label == null || a.label.isEmpty() ? a.id.substring(0, 8) : a.label;
    }

    // ================= 我的 =================

    private void updateMine() {
        Settings s = App.get().settings();

        StringBuilder ab = new StringBuilder();
        ab.append("版本      ").append(appVersionName()).append("\n\n");
        ab.append("端口      ").append(s.port()).append("\n\n");
        ab.append("Key 校验   ")
                .append(s.keyRequired() ? "已开启（网关校验 Key）" : "已关闭（无 Key 调用）")
                .append("\n\n");
        ab.append("局域网网关  ").append(s.lanEnabled() ? "已开启" : "已关闭").append("\n\n");
        ab.append("开机自启    ").append(s.bootOnStart() ? "已开启" : "已关闭").append("\n\n");
        ab.append("API Key    ")
                .append(App.get().keys().size()).append(" 个\n\n");
        int logged = 0;
        for (ServiceProvider p : App.get().registry().all()) if (p.loggedIn()) logged++;
        ab.append("已登录服务商 ").append(logged).append(" 个");
        mineAbout.setText(ab.toString());
    }

    /** 读取当前安装包的真实版本（PackageManager），不再使用写死或缓存的旧版本号。 */
    private String appVersionName() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi != null) {
                long vc = android.os.Build.VERSION.SDK_INT >= 28
                        ? pi.getLongVersionCode() : pi.versionCode;
                String vn = pi.versionName == null ? "" : pi.versionName.trim();
                if (!vn.isEmpty()) return vn + " (" + vc + ")";
                return "(" + vc + ")";
            }
        } catch (Exception ignored) {
        }
        return "未知";
    }

    // ================= 生命周期与广播 =================

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver,
                new IntentFilter("com.aigetway.tattoo7.STATUS"));
        running = GatewayService.isServerRunning();
        renderStatus();
        updateStep();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.postDelayed(logPoller, 2000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(logPoller);
    }

    /** 停留记录页时每 2s 刷新一次，保证实时可见。 */
    private final Runnable logPoller = new Runnable() {
        @Override
        public void run() {
            if (currentTab == 0) updateTokenCard();
            if (currentTab == 3) renderRecords();
            handler.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            running = intent.getBooleanExtra("running", false);
            int p = intent.getIntExtra("port", -1);
            if (p > 0) currentPort = p;
            renderStatus();
        }
    };

    // ================= 后台保活 =================

    /**
     * 网关在后台被系统杀掉后客户端就访问不到 127.0.0.1:port。
     * 主动申请电池优化豁免，最多提示两次。
     */
    private void ensureKeepAlive() {
        boolean granted = false;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            granted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
        }
        if (granted) return;
        int asked = App.get().settings().prefInt("keepalive_asked", 0);
        if (asked >= 2) {
            App.get().logSink().log("SERVICE",
                    "仍未获得后台保活权限，请在系统设置里允许本应用后台运行");
            return;
        }
        App.get().settings().setPrefInt("keepalive_asked", asked + 1);
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 小工具 =================

    private Button mkBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setMinHeight(dp(38));
        b.setMinWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}