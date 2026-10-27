package com.aigetway.tattoo7.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.aigetway.tattoo7.R;
import com.aigetway.tattoo7.account.AccountPool;
import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.account.CreditsCache;
import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.provider.BuddyProvider;
import com.aigetway.tattoo7.provider.RaccoonProvider;
import com.aigetway.tattoo7.provider.ServiceProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商详情页：从「供应商」列表点进来。
 * 上半部分是登录入口（浏览器/短信/粘贴凭据），下半部分是已登录账号列表。
 */
public class ProviderDetailActivity extends AppCompatActivity {

    private static final int REQ_LOGIN = 9001;
    private static final int REQ_SMS = 9002;

    private String providerId;
    private ServiceProvider provider;

    private TextView pdTitle;
    private TextView pdSubtitle;
    private TextView pdLoginDesc;
    private TextView pdErrorText;
    private Button pdLoginBtn;
    private Button pdImportBtn;
    private LinearLayout pdSmsBox;
    private LinearLayout pdAccountList;
    private LinearLayout pdProviderActions;
    private EditText pdPhone;
    private EditText pdSmsCode;
    private Button pdSendCodeBtn;
    private Button pdSmsLoginBtn;

    /** reload 代际号：新一轮 reload 会使旧后台刷新线程的 UI 回写作废。 */
    private final java.util.concurrent.atomic.AtomicInteger reloadGen =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 账号页后台刷新专用单线程池（static：Activity 重建也不叠线程；host 闸门本就串行）。 */
    private static final java.util.concurrent.ExecutorService creditExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pd-credits");
                t.setDaemon(true);
                return t;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_provider_detail);

        providerId = getIntent().getStringExtra("provider_id");
        if (providerId == null) providerId = "trae";
        provider = App.get().registry().byId(providerId);

        // 「初始 padding + inset」而不是「当前 padding + inset」：
        // listener 重复触发（横竖屏切换）时后者会让 padding 一次次累加。
        final View root = findViewById(R.id.pdRoot);
        final int padTop = root.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), padTop + sys.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        pdTitle = findViewById(R.id.pdTitle);
        pdSubtitle = findViewById(R.id.pdSubtitle);
        pdLoginDesc = findViewById(R.id.pdLoginDesc);
        pdErrorText = findViewById(R.id.pdErrorText);
        pdLoginBtn = findViewById(R.id.pdLoginBtn);
        pdImportBtn = findViewById(R.id.pdImportBtn);
        pdSmsBox = findViewById(R.id.pdSmsBox);
        pdAccountList = findViewById(R.id.pdAccountList);
        pdProviderActions = findViewById(R.id.pdProviderActions);
        pdPhone = findViewById(R.id.pdPhone);
        pdSmsCode = findViewById(R.id.pdSmsCode);
        pdSendCodeBtn = findViewById(R.id.pdSendCodeBtn);
        pdSmsLoginBtn = findViewById(R.id.pdSmsLoginBtn);

        if (provider == null) {
            pdTitle.setText("未知服务商");
            pdLoginBtn.setEnabled(false);
            pdImportBtn.setEnabled(false);
            return;
        }
        pdTitle.setText(provider.displayName());

        findViewById(R.id.pdBack).setOnClickListener(v -> finish());
        // 右上「刷新」= 强制联网重拉（忽略 15min 缓存）；其余入口的 reload 都是缓存优先
        findViewById(R.id.pdRefreshBtn).setOnClickListener(v -> reload(true));

        boolean isLoomy = "loomy".equals(providerId);
        boolean isAutoClaw = "autoclaw".equals(providerId);
        boolean isAStudio = "astudio".equals(providerId);
        if (isAStudio) {
            // AStudio 是「App 内置浏览器页」登录：短信 / 密码 / 微信扫码三选一，
            // 页面由 assets/astudio_login.html + 本机载体服务提供（见 AStudioLoginActivity）。
            pdLoginDesc.setText("内置浏览器登录：短信 / 密码 / 微信扫码三选一"
                    + "（未注册手机号验证后自动开户）。每登录一次新增一个账号。");
            pdLoginBtn.setText("打开 AStudio 登录页");
            pdLoginBtn.setOnClickListener(v -> startActivityForResult(
                    new Intent(this, AStudioLoginActivity.class), REQ_LOGIN));
        } else if (isLoomy || isAutoClaw) {
            pdLoginDesc.setText(isAutoClaw
                    ? "手机号短信验证码登录（智谱 AutoClaw 国内版），走积分通道。"
                    : "手机号短信验证码登录，走免费积分通道。");
            pdLoginBtn.setText("用手机号短信登录");
            pdSmsBox.setVisibility(View.VISIBLE);
            if (isAutoClaw) {
                // AutoClaw 用本页内联短信框（SmsLoginActivity 只认 loomy），
                // 按钮只负责把焦点移到手机号输入框。
                pdLoginBtn.setOnClickListener(v -> {
                    pdPhone.requestFocus();
                    toast("在下方填写手机号与验证码即可登录");
                });
            } else {
                pdLoginBtn.setOnClickListener(v -> startActivityForResult(
                        new Intent(this, SmsLoginActivity.class), REQ_SMS));
            }
        } else {
            pdLoginDesc.setText("在下方登录窗口完成网页登录，账号就绪后即可使用该服务商的全部模型。");
            pdLoginBtn.setOnClickListener(v -> startLogin());
        }
        pdImportBtn.setOnClickListener(v -> importCredential());
        pdSendCodeBtn.setOnClickListener(v -> sendSmsCode());
        pdSmsLoginBtn.setOnClickListener(v -> submitSmsCode());

        reload();
    }

    private void startLogin() {
        Intent it = new Intent(this, LoginActivity.class);
        it.putExtra(LoginActivity.EXTRA_PROVIDER, providerId);
        // 已登录时走「添加账号」：隔离 Cookie，避免复用上一个账号的登录态
        it.putExtra(LoginActivity.EXTRA_ADD, provider.loggedIn());
        startActivityForResult(it, REQ_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_LOGIN || requestCode == REQ_SMS)
                && resultCode == RESULT_OK) {
            reload();
        }
    }

    // ================= 粘贴凭据 JSON 导入 =================

    private void importCredential() {
        final EditText input = new EditText(this);
        input.setHint("粘贴该服务商的凭据 JSON");
        input.setMinLines(4);
        input.setTextSize(12f);
        // 默认从剪贴板预填，省得用户切出去复制
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (cs != null) input.setText(cs);
            }
        } catch (Exception ignored) {
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("粘贴凭据 JSON 导入")
                .setMessage("从其它工具复制的凭据粘贴到这里，会作为新账号加入账号池。")
                .setView(input)
                .setPositiveButton("导入", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) {
                        toast("请粘贴内容");
                        return;
                    }
                    try {
                        JSONObject o = new JSONObject(raw);
                        String label = o.optString("nickname",
                                o.optString("name", o.optString("phone", "导入账号")));
                        AccountPoolHolder.getOrCreate(providerId).addAccount(raw, label);
                        toast("已导入账号：" + label);
                        reload();
                    } catch (Exception e) {
                        showError("不是合法的 JSON 对象：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 简化取池子（AccountPoolHolder 的 API 见下）。 */
    private static final class AccountPoolHolder {
        static AccountPool getOrCreate(String id) {
            return com.aigetway.tattoo7.account.AccountPoolHolder.get(id);
        }
    }

    // ================= 短信登录（内联，loomy / autoclaw） =================

    /** autoclaw 发码返回的 device_id，登录时必须沿用（上游把设备与登录会话关联）。 */
    private String acDeviceId = "";

    private void sendSmsCode() {
        String phone = pdPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            showError("请输入手机号");
            return;
        }
        pdSmsCode.requestFocus();
        showError("");
        Toast.makeText(this, "正在发送验证码...", Toast.LENGTH_SHORT).show();
        final boolean isAutoClaw = "autoclaw".equals(providerId);
        new Thread(() -> {
            String msg;
            try {
                if (isAutoClaw) {
                    acDeviceId = App.get().autoclawProvider().sendCode(phone);
                    msg = "验证码已发送，请在 5 分钟内填写";
                } else {
                    App.get().loomyProvider().sendSmsCode(phone);
                    msg = "验证码已发送，请在 5 分钟内填写";
                }
            } catch (Exception e) {
                msg = "发送失败: " + e.getMessage();
            }
            final String f = msg;
            runOnUiThread(() -> Toast.makeText(this, f, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void submitSmsCode() {
        String phone = pdPhone.getText().toString().trim();
        String code = pdSmsCode.getText().toString().trim();
        if (phone.isEmpty() || code.isEmpty()) {
            showError("请填写手机号和验证码");
            return;
        }
        Toast.makeText(this, "正在登录...", Toast.LENGTH_SHORT).show();
        final boolean isAutoClaw = "autoclaw".equals(providerId);
        final String dev = acDeviceId;
        new Thread(() -> {
            boolean ok;
            String msg = "";
            try {
                if (isAutoClaw) {
                    ok = App.get().autoclawProvider().loginWithCode(phone, code, dev);
                } else {
                    ok = App.get().loomyProvider().submitSmsCode(phone, code);
                }
                if (!ok) msg = "登录失败";
            } catch (Exception e) {
                ok = false;
                msg = "登录失败: " + e.getMessage();
            }
            final boolean fok = ok;
            final String fmsg = msg;
            runOnUiThread(() -> {
                if (fok) {
                    Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
                    reload();
                } else {
                    showError(fmsg);
                }
            });
        }).start();
    }

    // ================= 刷新 + 渲染 =================
    private void reload() {
        reload(false);
    }

    /**
     * vc152 改造（对齐 vc121 主页模式）：
     * 1. 先用「账号池本地态 + 积分缓存」渲染（秒出，不联网）——不再整页卡在
     *    「正在读取账号与积分...」等 3 个串行请求全跑完；
     * 2. 后台只刷新过期/强制账号，**每完成一个就增量重绘**——慢/挂的账号不再拖住其它账号；
     * 3. 拉/写全程持 {@link com.aigetway.tattoo7.account.AccountPoolHolder#providerLock}，
     *    消除与主页 refreshCreditsAsync 同时切 active 的竞态（此前 account() 会读错账号）；
     * 4. 失败不清缓存：卡片保留上次积分，另起一行「错误: timeout」。
     */
    private void reload(boolean force) {
        if (provider == null) return;
        if (pool().all().isEmpty() && provider.recoverPersistedAccount()) {
            // 「登录成功」弹了 Toast 但账号列表 0 个：把已落库凭据补回池子再渲染。
            // recoverPersistedAccount 内部有幂等/上限守卫，反复进账号页不会重复写入。
            android.util.Log.i("ProviderDetail", "reload: pool empty, recovered persisted account for " + providerId);
        }
        final int gen = reloadGen.incrementAndGet(); // 新一轮开始，旧后台线程作废
        renderAccounts(cachedSnapshots());
        creditExec.execute(() -> refreshAccountsAsync(gen, force));
    }

    /** 本地快照：只读池子与积分缓存，零联网。缺缓存的账号积分先显示「…」。 */
    private Map<String, AccountSnapshot> cachedSnapshots() {
        Map<String, AccountSnapshot> out = new LinkedHashMap<>();
        AccountPool p = pool();
        for (AccountPool.Account a : p.all()) {
            AccountSnapshot s = new AccountSnapshot(providerId, provider.displayName());
            s.supportsCheckin = provider.supportsCheckin();
            // accountName 留空：标题行已显示账号名，缓存首渲不重复；真实值由 account() 回填
            // 乐观：不停用/不冷却就先显示「可用」，真实登录态等后台刷新回填
            s.loggedIn = a.enabled && !(a.cooldownUntil > System.currentTimeMillis());
            CreditsCache.Entry ce = App.get().creditsCache().get(providerId, a.id);
            if (ce != null && !ce.credits.isEmpty()) {
                s.credits = ce.credits;
                s.creditsTotal = ce.creditsTotal;
                // 明细行也从缓存恢复，否则缓存期内（15min）只剩合计、两个池空着
                s.permanentCredits = ce.permanent;
                s.dailyCredits = ce.daily;
            } else {
                s.credits = "…";
            }
            out.put(a.id, s);
        }
        return out;
    }

    private AccountPool.Account accountById(String id) {
        for (AccountPool.Account a : pool().all()) if (a.id.equals(id)) return a;
        return null;
    }

    private AccountPool pool() {
        return com.aigetway.tattoo7.account.AccountPoolHolder.get(providerId);
    }

    /**
     * 后台逐账号刷新（vc152）：把账号池里每个账号依次设为当前再调 account()，查完恢复原激活账号。
     *
     * 为什么要逐个切：所有 provider 的 account() 都只读「当前激活账号」的凭据，
     * 不切就永远只能看到那一个账号的积分。串行 + providerLock（与主页共用）才不会串号。
     * 每完成一个账号就增量重绘一次，慢/挂的账号不再拖住其它账号的展示。
     */
    private void refreshAccountsAsync(int gen, boolean force) {
        AccountPool p = pool();
        List<AccountPool.Account> accts = p.all();
        if (accts.isEmpty()) return;
        String activeId = p.activeId();
        Map<String, AccountSnapshot> snaps = cachedSnapshots();
        for (AccountPool.Account a : accts) {
            if (gen != reloadGen.get()) return; // 用户又点了刷新/切换：本轮剩余账号全部作废
            CreditsCache.Entry ce = App.get().creditsCache().get(providerId, a.id);
            if (!force && ce != null && App.get().creditsCache().fresh(providerId, a.id)) {
                continue; // 缓存期内不联网（与主页同一口径）
            }
            AccountSnapshot snap;
            boolean switched = !a.id.equals(activeId);
            com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).lock();
            try {
                try {
                    if (switched) p.setActive(a.id);
                    snap = provider.account();
                } catch (Throwable e) {
                    snap = new AccountSnapshot(providerId, provider.displayName());
                    snap.error = String.valueOf(e.getMessage());
                } finally {
                    if (switched) p.setActive(activeId);
                }
            } finally {
                com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).unlock();
            }
            if (snap != null && snap.error.isEmpty() && !snap.credits.isEmpty()) {
                App.get().creditsCache().put(providerId, a.id, snap.credits, snap.creditsTotal,
                        snap.permanentCredits, snap.dailyCredits);
            } else if (snap != null && ce != null && !ce.credits.isEmpty()) {
                // 失败保底：保留上次积分，别让「积分 —」看起来像数据丢了
                snap.credits = ce.credits;
                snap.creditsTotal = ce.creditsTotal;
                snap.permanentCredits = ce.permanent;
                snap.dailyCredits = ce.daily;
            }
            snaps.put(a.id, snap);
            final Map<String, AccountSnapshot> done = new LinkedHashMap<>(snaps);
            final int usable = countUsable(done, p);
            runOnUiThread(() -> {
                if (gen != reloadGen.get()) return;
                pdSubtitle.setText(done.size() + " 个账号 · 可用 " + usable);
                renderAccounts(done);
            });
        }
    }

    private int countUsable(Map<String, AccountSnapshot> per, AccountPool p) {
        int u = 0;
        for (Map.Entry<String, AccountSnapshot> en : per.entrySet()) {
            AccountPool.Account a = accountById(en.getKey());
            if (a != null && a.available() && en.getValue() != null && en.getValue().loggedIn) u++;
        }
        return u;
    }

    private void renderAccounts(Map<String, AccountSnapshot> per) {
        pdAccountList.removeAllViews();
        AccountPool p = pool();
        List<AccountPool.Account> accts = p.all();
        if (accts.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("（还没有账号，点上面的登录按钮添加）");
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tv.setBackgroundResource(R.drawable.bg_row_white);
            tv.setPadding(dp(16), dp(20), dp(16), dp(20));
            pdAccountList.addView(tv);
            return;
        }

        for (AccountPool.Account a : accts) {
            AccountSnapshot snap = per.get(a.id);
            boolean cooling = a.cooldownUntil > System.currentTimeMillis();
            boolean isActive = a.id.equals(p.activeId());

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_row_white);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);
            card.setLayoutParams(clp);
            int pad = dp(16);
            card.setPadding(pad, pad, pad, pad);

            // 标题行：账号名 + 状态 + 开关
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);

            LinearLayout nameCol = new LinearLayout(this);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            TextView nameTv = new TextView(this);
            nameTv.setText(a.label + (isActive ? "  · 当前" : ""));
            nameTv.setTextSize(16f);
            nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
            nameTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            TextView stateTv = new TextView(this);
            StringBuilder st = new StringBuilder();
            if (!a.enabled) st.append("已停用");
            else if (cooling) st.append("冷却中");
            else if (snap != null && snap.loggedIn) st.append("可用");
            else st.append("凭据失效");
            stateTv.setText(st.toString());
            stateTv.setTextSize(12f);
            stateTv.setTextColor(ContextCompat.getColor(this,
                    (!a.enabled || cooling) ? R.color.text_secondary : R.color.success));
            nameCol.addView(nameTv);
            nameCol.addView(stateTv);
            head.addView(nameCol, new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            androidx.appcompat.widget.SwitchCompat sw =
                    new androidx.appcompat.widget.SwitchCompat(this);
            // 先设值再挂监听，否则首次 setChecked 会误触发回调
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(a.enabled);
            sw.setOnCheckedChangeListener((v, checked) -> {
                pool().setEnabled(a.id, checked);
                toast((checked ? "已启用 " : "已停用 ") + a.label);
                reload();
            });
            head.addView(sw);
            card.addView(head);

            // 积分与明细
            if (snap != null) {
                StringBuilder info = new StringBuilder();
                info.append("积分 ").append(snap.credits);
                if (!snap.accountName.isEmpty()) info.append("  ·  ").append(snap.accountName);
                if (snap.creditsTotal >= 0 && per.size() > 1) {
                    // 多账号时逐条显示即可，服务商总和在列表页已汇总
                }
                if (!snap.checkinHint.isEmpty()) info.append("  ·  ").append(snap.checkinHint);
                if (!snap.onboardingHint.isEmpty()) info.append("  ·  新手任务 ").append(snap.onboardingHint);
                TextView infoTv = new TextView(this);
                infoTv.setText(info.toString());
                infoTv.setTextSize(13f);
                infoTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                infoTv.setPadding(0, dp(8), 0, 0);
                card.addView(infoTv);

                StringBuilder detail = new StringBuilder();
                // 两个池分开列：合计（上一行的「积分」）看不出「今天有多少会作废」，
                // 也看不出签到到底动了哪个池（见 LoomyProvider.refreshCredits 注释）。
                if (!snap.permanentCredits.isEmpty()) {
                    detail.append("永久 ").append(snap.permanentCredits).append("  ");
                }
                if (!snap.dailyCredits.isEmpty()) {
                    detail.append("每日 ").append(snap.dailyCredits).append("(当日有效)  ");
                }
                if (!snap.expiringCredits.isEmpty()) {
                    detail.append("临时(15天内到期) ").append(snap.expiringCredits).append("  ");
                }
                if (snap.permanentLocked && snap.supportsLock) detail.append("[永久已锁定] ");
                if (!detail.toString().trim().isEmpty()) {
                    TextView d = new TextView(this);
                    d.setText(detail.toString().trim());
                    d.setTextSize(12f);
                    d.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                    card.addView(d);
                }
                if (!snap.error.isEmpty()) {
                    TextView e = new TextView(this);
                    e.setText("错误: " + snap.error);
                    e.setTextSize(12f);
                    e.setTextColor(ContextCompat.getColor(this, R.color.danger));
                    e.setPadding(0, dp(6), 0, 0);
                    card.addView(e);
                }
                // 额度用尽时给出唯一的解药入口，可点击。
                if (!snap.upgradeUrl.isEmpty()) {
                    TextView up = new TextView(this);
                    up.setText("额度已用尽 → 点此升级 / 充值");
                    up.setTextSize(13f);
                    up.setTextColor(ContextCompat.getColor(this, R.color.brand));
                    up.setPadding(0, dp(6), 0, 0);
                    final String url = snap.upgradeUrl;
                    up.setOnClickListener(v -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ex) {
                            showError("打开失败: " + ex.getMessage());
                        }
                    });
                    card.addView(up);
                }
            }

            // 操作按钮：先收集，再按每行最多 3 个整行换行。
            //（直接往横向 LinearLayout 里addView，宽度不够时最后一个按钮会被压扁换行，
            //  表现为「解锁永久」挤成两行还溢出卡片右边。）
            List<Button> btns = new ArrayList<>();
            btns.add(mkBtn(isActive ? "当前账号" : "使用", v -> {
                pool().setActive(a.id);
                toast("已切换到 " + a.label);
                reload();
            }, isActive));
            btns.add(mkBtn("刷新凭证", v -> refreshCredential(a)));
            if (provider.supportsCheckin()) {
                btns.add(mkBtn("签到", v -> checkinAccount(a)));
            }
            if (provider.supportsOnboardingTasks()) {
                btns.add(mkBtn("新手任务", v -> claimOnboardingAccount(a)));
            }
            if (provider.supportsPermanentLock() && provider instanceof BuddyProvider) {
                BuddyProvider bp = (BuddyProvider) provider;
                btns.add(mkBtn(bp.permanentLocked() ? "解锁积分" : "锁定积分", v -> {
                    bp.setPermanentLocked(!bp.permanentLocked());
                    toast(bp.permanentLocked() ? "已锁定永久积分" : "已解锁永久积分");
                    reload();
                }));
            }
            btns.add(mkBtn("删除", v -> {
                pool().remove(a.id);
                toast("已删除 " + a.label);
                reload();
            }));
            card.addView(wrapButtons(btns, 3));

            pdAccountList.addView(card);
        }

        // 服务商级动作
        pdProviderActions.setVisibility(View.VISIBLE);
        // 单账号的「签到/新手任务」在各自的账号卡上（见上方卡片按钮）；
        // 这里才是「该服务商全部账号」的批量入口，刻意与卡片按钮分开，避免误触。
        final int acctCount = pool().all().size();
        if (acctCount > 1 && provider.supportsCheckin()) {
            pdProviderActions.addView(mkBtnWide(
                    "一键签到（全部 " + acctCount + " 个账号）", v -> checkinProvider()));
        }
        if (acctCount > 1 && provider.supportsOnboardingTasks()) {
            if (provider.supportsCheckin()) {
                android.widget.LinearLayout.LayoutParams gap =
                        new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
                pdProviderActions.addView(viewGap(gap));
            }
            pdProviderActions.addView(mkBtnWide(
                    "一键领新手任务（全部 " + acctCount + " 个账号）",
                    v -> claimOnboardingProvider()));
        }
        if (provider.supportsPermanentLock() && provider instanceof BuddyProvider) {
            // ⚠️ 永久锁是**服务商级**开关（存 prefs，不是账号级），
            // 所以只能有一处，不能每张账号卡都渲染一个。
            final BuddyProvider bp = (BuddyProvider) provider;
            pdProviderActions.addView(mkBtnWide(
                    bp.permanentLocked() ? "解锁永久积分" : "锁定永久积分", v -> {
                        bp.setPermanentLocked(!bp.permanentLocked());
                        toast(bp.permanentLocked() ? "已锁定永久积分" : "已解锁永久积分");
                        reload();
                    }));
            android.widget.LinearLayout.LayoutParams sp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
            sp.bottomMargin = dp(10);
            pdProviderActions.addView(viewGap(sp));
        }
        // 「添加账号」入口对 Loomy讯飞 是坏的（点了只会把当前会话踢掉，
        // 并不会真的多出可用账号），按用户要求从内页底部去掉；
        // AutoClaw 国内版同样是短信登录（无网页登录），入口改用上方短信框，
        // 每登录一次即新增一个账号，故也去掉这个网页登录入口。
        if (!"loomy".equals(providerId) && !"autoclaw".equals(providerId)) {
            pdProviderActions.addView(mkBtnWide("添加账号", v -> startLogin()));
        }
        // Raccoon 特有：批量刷新所有账号的续期凭据。
        // 解决"两个账号总有一个自动过期且刷不出来" —— 后台 30 分钟跑一次不保险，
        // 手动按一下能立刻看到每个账号的刷新日志。
        if ("raccoon".equals(providerId)) {
            pdProviderActions.addView(mkBtnWide("刷新全部账号凭证", v -> {
                Toast.makeText(this, "正在刷新所有账号...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    App.get().raccoonProvider().refreshAll();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "已尝试刷新全部账号，见日志详情", Toast.LENGTH_LONG).show();
                        reload();
                    });
                }, "pd-refresh-all").start();
            }));
        }
    }

    /** 纯占位 View（用来给上面的按钮加margin）。 */
    private View viewGap(android.widget.LinearLayout.LayoutParams lp) {
        View v = new View(this);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 按钮横向排版：每行最多 perRow 个，放不下就**整行下移**（而不是让按钮自己压扁）。
     * 用法：父容器设VERTICAL，里面每行一个 HORIZONTAL LinearLayout。
     */
    private LinearLayout wrapButtons(List<Button> btns, int perRow) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        LinearLayout row = null;
        int n = 0;
        for (Button b : btns) {
            if (row == null || n >= perRow) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
                n = 0;
            }
            row.addView(b);
            n++;
        }
        return box;
    }

    private Button mkBtn(String label, View.OnClickListener l, boolean disabled) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        b.setBackgroundResource(R.drawable.bg_chip);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(dp(38));
        // 单行显示：4 个字的「当前账号」跟另外 2 个按钮挤一行时会被压成两行
        b.setMaxLines(1);
        b.setEllipsize(null);
        // 同排按钮大小一致；空间不足时由 wrapButtons 整行下移，而不是压扁
        b.setMinWidth(dp(86));
        b.setEnabled(!disabled);
        b.setOnClickListener(l);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private Button mkBtn(String label, View.OnClickListener l) {
        return mkBtn(label, l, false);
    }

    private Button mkBtnWide(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15f);
        b.setAllCaps(false);
        b.setTextColor(ContextCompat.getColor(this, R.color.surface));
        b.setBackgroundResource(R.drawable.bg_btn);
        b.setOnClickListener(l);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        b.setLayoutParams(lp);
        return b;
    }

    // ================= 操作 =================

    /** 手动刷新凭证：失败时把真实原因显示出来。 */
    private void refreshCredential(AccountPool.Account a) {
        Toast.makeText(this, "正在刷新凭证...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result;
            try {
                // 按行内账号刷新（多账号服务商不能只刷「当前账号」）
                boolean ok = provider.refreshFingerprintForUid(
                        a == null ? "" : (a.uid.isEmpty() ? a.id : a.uid));
                // 走接口拿原因：以前只认 RaccoonProvider，其它服务商
                // 失败原因全被吞掉，界面只剩一句「无法刷新该账号的凭证」。
                String detail = provider.lastRefreshError();
                result = ok ? ("刷新成功，凭证已更新"
                        + (detail.isEmpty() ? "" : "\n（部分账号未续期：" + detail + "）"))
                        : "刷新失败：" + (detail.isEmpty()
                        ? "无法刷新该账号的凭证。" : detail);
            } catch (Exception e) {
                result = "刷新异常：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String msg = result;
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(provider.displayName() + " · 刷新凭证")
                        .setMessage(msg)
                        .setPositiveButton("好", null)
                        .show();
                reload();
            });
        }, "pd-refresh").start();
    }

    /** 单账号签到：只签这张卡对应的账号（一键签到仍遍历所有账号）。 */
    private void checkinAccount(AccountPool.Account acct) {
        Toast.makeText(this, "正在为 " + acct.label + " 签到...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            if (!acct.available()) {
                runOnUiThread(() -> toast(acct.label + " 不可用（已停用或冷却中）"));
                return;
            }
            AccountPool p = pool();
            String activeId = p.activeId();
            boolean switched = !acct.id.equals(activeId);
            String msg;
            com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).lock();
            try {
                if (switched) p.setActive(acct.id);
                msg = provider.checkin();
            } catch (Exception e) {
                msg = "失败: " + e.getMessage();
            } finally {
                if (switched) p.setActive(activeId);
                com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).unlock();
            }
            final String out = acct.label + ": " + msg;
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(provider.displayName() + " 签到")
                        .setMessage(out)
                        .setPositiveButton("好", null)
                        .show();
                reload(true); // 签到改变了积分，缓存已过期，强制重拉
            });
        }, "pd-checkin-acct").start();
    }

    /** 单账号领新手任务：只领这张卡对应的账号（一次性，账号之间互不影响）。 */
    private void claimOnboardingAccount(AccountPool.Account acct) {
        Toast.makeText(this, "正在为 " + acct.label + " 领取新手任务...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            if (!acct.available()) {
                runOnUiThread(() -> toast(acct.label + " 不可用（已停用或冷却中）"));
                return;
            }
            AccountPool p = pool();
            String activeId = p.activeId();
            boolean switched = !acct.id.equals(activeId);
            String msg;
            com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).lock();
            try {
                if (switched) p.setActive(acct.id);
                msg = provider.claimOnboarding();
            } catch (Exception e) {
                msg = "失败: " + e.getMessage();
            } finally {
                if (switched) p.setActive(activeId);
                com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).unlock();
            }
            final String out = acct.label + ": " + msg;
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(provider.displayName() + " 新手任务")
                        .setMessage(out)
                        .setPositiveButton("好", null)
                        .show();
                reload(true); // 领取改变了积分，缓存已过期，强制重拉
            });
        }, "pd-onboarding-acct").start();
    }

    /** 一键签到：该服务商下**所有**账号，逐个切过去签再切回。 */
    private void checkinProvider() {
        Toast.makeText(this, "正在签到...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AccountPool p = pool();
            List<AccountPool.Account> accts = p.all();
            final java.util.List<String> lines = new java.util.ArrayList<>();
            if (accts.isEmpty()) lines.add("无账号");
            String activeId = p.activeId();
            for (AccountPool.Account a : accts) {
                if (!a.available()) {
                    lines.add(a.label + ": 跳过（已停用或冷却中）");
                    continue;
                }
                boolean switched = !a.id.equals(activeId);
                String msg;
                com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).lock();
                try {
                    if (switched) p.setActive(a.id);
                    msg = provider.checkin();
                } catch (Exception e) {
                    msg = "失败: " + e.getMessage();
                } finally {
                    if (switched) p.setActive(activeId);
                    com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).unlock();
                }
                lines.add(a.label + ": " + msg);
            }
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(provider.displayName() + " 签到结果")
                        .setMessage(String.join("\n", lines))
                        .setPositiveButton("好", null)
                        .show();
                reload(true); // 签到改变了积分，缓存已过期，强制重拉
            });
        }, "pd-checkin").start();
    }

    /** 新手任务：该服务商下**所有**账号逐个领（一次性，每个账号独立）。 */
    private void claimOnboardingProvider() {
        Toast.makeText(this, "正在领取新手任务...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AccountPool p = pool();
            List<AccountPool.Account> accts = p.all();
            final java.util.List<String> lines = new java.util.ArrayList<>();
            if (accts.isEmpty()) lines.add("无账号");
            String activeId = p.activeId();
            for (AccountPool.Account a : accts) {
                if (!a.available()) {
                    lines.add(a.label + ": 跳过（已停用或冷却中）");
                    continue;
                }
                boolean switched = !a.id.equals(activeId);
                String msg;
                com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).lock();
                try {
                    if (switched) p.setActive(a.id);
                    msg = provider.claimOnboarding();
                } catch (Exception e) {
                    msg = "失败: " + e.getMessage();
                } finally {
                    if (switched) p.setActive(activeId);
                    com.aigetway.tattoo7.account.AccountPoolHolder.providerLock(providerId).unlock();
                }
                lines.add(a.label + ": " + msg);
            }
            runOnUiThread(() -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(provider.displayName() + " 新手任务")
                        .setMessage(String.join("\n", lines))
                        .setPositiveButton("好", null)
                        .show();
                reload(true); // 领取改变了积分，缓存已过期，强制重拉
            });
        }, "pd-onboarding").start();
    }

    private void showError(String msg) {
        if (msg == null || msg.isEmpty()) {
            pdErrorText.setVisibility(View.GONE);
            return;
        }
        pdErrorText.setText(msg);
        pdErrorText.setVisibility(View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}