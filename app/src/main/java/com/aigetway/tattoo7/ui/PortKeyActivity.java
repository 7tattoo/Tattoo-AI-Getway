package com.aigetway.tattoo7.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
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
import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.core.Settings;
import com.aigetway.tattoo7.key.ApiKey;
import com.aigetway.tattoo7.key.ApiKeyStore;
import com.aigetway.tattoo7.service.GatewayService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「端口与 API Key 管理」页：承接旧 Key 页 + 旧设置卡的**全部**功能。
 * 主界面「我的」页里点「端口与 API Key 管理」进入。
 */
public class PortKeyActivity extends AppCompatActivity {

    private EditText portInput;
    private androidx.appcompat.widget.SwitchCompat apiKeySwitch;
    private androidx.appcompat.widget.SwitchCompat lanSwitch;
    private androidx.appcompat.widget.SwitchCompat bootingSwitch;
    private TextView lanUrlText;
    private TextView newKeyText;
    private LinearLayout keyGroupBox;
    private LinearLayout keyList;
    private LayoutInflater inflater;

    private String draftKey;
    private String editingKey;
    private final LinkedHashSet<String> draftGroups = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_port_key);
        inflater = LayoutInflater.from(this);

        // 「初始 padding + inset」而不是「当前 padding + inset」：
        // listener 重复触发（横竖屏切换）时后者会让 padding 一次次累加。
        final View root = findViewById(R.id.pkRoot);
        final int padTop = root.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), padTop + sys.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        portInput = findViewById(R.id.portInput);
        apiKeySwitch = findViewById(R.id.apiKeySwitch);
        lanSwitch = findViewById(R.id.lanSwitch);
        bootingSwitch = findViewById(R.id.bootingSwitch);
        lanUrlText = findViewById(R.id.lanUrlText);
        newKeyText = findViewById(R.id.newKeyText);
        keyGroupBox = findViewById(R.id.keyGroupBox);
        keyList = findViewById(R.id.keyList);

        findViewById(R.id.pkBack).setOnClickListener(v -> finish());
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveSettings());
        findViewById(R.id.pkKeepAlive).setOnClickListener(v -> ensureKeepAlive());
        lanUrlText.setOnClickListener(v -> copyEndpoint(true));

        Settings s = App.get().settings();
        portInput.setText(String.valueOf(s.port()));
        apiKeySwitch.setChecked(s.keyRequired());
        lanSwitch.setChecked(s.lanEnabled());
        bootingSwitch.setChecked(s.bootOnStart());
        bootingSwitch.setOnCheckedChangeListener((b, checked) -> s.setBootOnStart(checked));

        // 「启用 API Key 校验」即时生效：
        // 开启 = 网关校验 Key（未带有效 Key 的请求 401）；关闭 = 无 Key 调用（客户端可不填 Key）。
        apiKeySwitch.setOnCheckedChangeListener((b, checked) -> {
            s.setKeyRequired(checked);
            if (checked) {
                boolean hasKey = !s.apiKey().isEmpty() || App.get().keys().size() > 0;
                Toast.makeText(this, hasKey
                                ? "已开启 API Key 校验：未带有效 Key 的请求返回 401"
                                : "已开启校验，但尚未配置任何 Key：请填 Key 或先生成分组 Key，否则请求会被拒绝",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已关闭 Key 校验，客户端可不填 Key（无 Key 调用）",
                        Toast.LENGTH_SHORT).show();
            }
        });

        updateLanUrl();
        bindKeyUi();
        renderGroupBox();
        renderKeys();
    }

    // ================= 设置 =================

    private void saveSettings() {
        Settings s = App.get().settings();
        int oldPort = s.port();
        boolean oldLan = s.lanEnabled();
        int newPort;
        try {
            newPort = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        s.setPort(newPort);
        // 手动输入框已移除：key 一律走下方「API Key 分组」生成；
        // 旧版单 key 保留原值不动，只做旧客户端兼容。
        s.setKeyRequired(apiKeySwitch.isChecked());
        s.setLanEnabled(lanSwitch.isChecked());
        updateLanUrl();
        boolean running = GatewayService.isServerRunning();
        boolean changed = newPort != oldPort || lanSwitch.isChecked() != oldLan;
        if (changed && running) {
            Toast.makeText(this, "设置已保存，网关正在重启以应用新配置", Toast.LENGTH_SHORT).show();
            Intent stop = new Intent(this, GatewayService.class);
            stop.setAction(GatewayService.ACTION_STOP);
            ContextCompat.startForegroundService(this, stop);
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                Intent st = new Intent(this, GatewayService.class);
                st.setAction(GatewayService.ACTION_START);
                st.putExtra(GatewayService.EXTRA_PORT, App.get().settings().port());
                ContextCompat.startForegroundService(this, st);
            }, 500);
        } else {
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        }
    }

    /** 刷新局域网接入地址行。 */
    private void updateLanUrl() {
        Settings s = App.get().settings();
        if (!s.lanEnabled()) {
            lanUrlText.setText("局域网接入: 未开启（仅本机 127.0.0.1 可访问）");
            lanUrlText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            return;
        }
        String ip = getLanIp();
        int p = s.port();
        if (ip == null) {
            lanUrlText.setText("局域网接入: 未获取到本机 IP（连上 Wi-Fi 后保存再试）");
            lanUrlText.setTextColor(ContextCompat.getColor(this, R.color.danger));
        } else {
            lanUrlText.setText("局域网接入: http://" + ip + ":" + p + "/v1\n（点击复制）");
            lanUrlText.setTextColor(ContextCompat.getColor(this, R.color.brand));
        }
    }

    /** 取本机局域网 IPv4（跳过 loopback），如 192.168.x.x。 */
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

    /** 点击地址 → 复制完整请求地址（开 Key 时给带 Bearer 的 curl）。 */
    private void copyEndpoint(boolean lan) {
        String base;
        if (!lan) {
            base = "http://127.0.0.1:" + App.get().settings().port() + "/v1";
        } else {
            String ip = getLanIp();
            if (ip == null) {
                Toast.makeText(this, "未获取到本机 IP，连上 Wi-Fi 后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            base = "http://" + ip + ":" + App.get().settings().port() + "/v1";
        }
        if (App.get().settings().keyRequired()) {
            String k = App.get().settings().apiKey();
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
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 后台保活 =================

    /**
     * 网关在后台被系统杀掉后，客户端就访问不到 127.0.0.1:port。
     * 服务已经是前台服务 + WakeLock，但仍可能被厂商策略杀掉，这里主动申请电池优化豁免。
     */
    private void ensureKeepAlive() {
        boolean granted = false;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            granted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
        }
        if (granted) {
            Toast.makeText(this, "已获得后台保活权限", Toast.LENGTH_SHORT).show();
            return;
        }
        int asked = App.get().settings().prefInt("keepalive_asked", 0);
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
        App.get().logSink().log("SERVICE",
                "请允许本应用「忽略电池优化 / 后台运行」，否则切到后台网关会被系统杀掉");
    }

    // ================= API Key =================

    private void bindKeyUi() {
        findViewById(R.id.genKeyBtn).setOnClickListener(v -> {
            draftKey = ApiKeyStore.randomKey();
            editingKey = null;
            draftGroups.clear();
            newKeyText.setText(draftKey);
            Button sb = findViewById(R.id.saveKeyBtn);
            sb.setText("保存该 key");
            renderGroupBox();
            copy(draftKey);
        });
        findViewById(R.id.copyKeyBtn).setOnClickListener(v -> {
            if (draftKey == null) {
                Toast.makeText(this, "请先生成", Toast.LENGTH_SHORT).show();
                return;
            }
            copy(draftKey);
        });
        findViewById(R.id.saveKeyBtn).setOnClickListener(v -> {
            if (draftKey == null) {
                Toast.makeText(this, "请先生成", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> g = new ArrayList<>(draftGroups);
            if (editingKey != null) {
                App.get().keys().setGroups(editingKey, g);
                Toast.makeText(this, "已更新该 key 的分组", Toast.LENGTH_SHORT).show();
                resetDraft();
                renderKeys();
                return;
            }
            ApiKey k = App.get().keys().create("key-" + (App.get().keys().size() + 1), g);
            resetDraft();
            Toast.makeText(this, "已保存：" + k.key, Toast.LENGTH_SHORT).show();
            renderKeys();
        });
    }

    private void resetDraft() {
        draftKey = null;
        editingKey = null;
        Button sb = findViewById(R.id.saveKeyBtn);
        sb.setText("保存该 key");
        newKeyText.setText("尚未生成");
        renderGroupBox();
    }

    /** 该 key 可访问的服务商分组勾选框。 */
    private void renderGroupBox() {
        keyGroupBox.removeAllViews();
        for (com.aigetway.tattoo7.provider.ServiceProvider p : App.get().registry().all()) {
            final String pid = p.id();
            final androidx.appcompat.widget.SwitchCompat sw =
                    new androidx.appcompat.widget.SwitchCompat(this);
            sw.setText(p.displayName());
            sw.setTextSize(14f);
            sw.setChecked(draftGroups.contains(pid));
            sw.setOnCheckedChangeListener((v, checked) -> {
                if (checked) draftGroups.add(pid);
                else draftGroups.remove(pid);
            });
            keyGroupBox.addView(sw);
        }
    }

    private void renderKeys() {
        keyList.removeAllViews();
        for (ApiKey k : App.get().keys().all()) {
            View row = inflater.inflate(R.layout.key_row, keyList, false);
            TextView kt = row.findViewById(R.id.keyText);
            TextView km = row.findViewById(R.id.keyMeta);
            LinearLayout kb = row.findViewById(R.id.keyButtons);
            if (kt != null) kt.setText(k.key);
            if (km != null) {
                km.setText(k.name + " · " + (k.enabled ? "启用" : "停用")
                        + " · 可访问：" + k.groupsText());
            }
            kb.addView(mkBtn("复制", v -> copy(k.key)));
            kb.addView(mkBtn("改分组", v -> {
                draftKey = k.key;
                editingKey = k.key;
                draftGroups.clear();
                draftGroups.addAll(k.groups);
                newKeyText.setText("正在修改：" + k.key);
                Button sb = findViewById(R.id.saveKeyBtn);
                sb.setText("保存该分组");
                renderGroupBox();
            }));
            kb.addView(mkBtn(k.enabled ? "停用" : "启用", v -> {
                App.get().keys().setEnabled(k.key, !k.enabled);
                renderKeys();
            }));
            kb.addView(mkBtn("删除", v -> {
                App.get().keys().remove(k.key);
                renderKeys();
            }));
            keyList.addView(row);
        }
        if (App.get().keys().size() == 0) {
            TextView tv = new TextView(this);
            tv.setText("（还没有 key。生成后客户端用该 key 请求 /v1/models）");
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tv.setPadding(dp(4), dp(12), dp(4), dp(4));
            keyList.addView(tv);
        }
    }

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
}