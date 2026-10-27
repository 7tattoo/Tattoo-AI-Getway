package com.aigetway.tattoo7.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aigetway.tattoo7.util.QrDiagnostics;

/**
 * AStudio（讯飞 AstronStudio）**内置浏览器登录页**：短信 / 密码 / 微信扫码三条通路。
 *
 * <p>页面本体是 {@code assets/astudio_login.html}（与参考实现 PR 118 的载体页同构：
 * 三个 Tab + 极验 gt.js）。页面由本机的 {@link AStudioCarrierServer} 提供，
 * 登录动作全部在原生侧完成（页面拿不到凭据）。
 *
 * <p>为什么不用外部浏览器：本家没有 OAuth 授权 URL 可跳（Electron 客户端内做微信扫码），
 * 而短信 / 密码两条路要过极验，必须在真实浏览器环境里跑 gt.js —— 所以是「App 内 WebView
 * + 本机载体服务」，既不依赖外部浏览器，也不用把凭据交给网页。
 */
public class AStudioLoginActivity extends Activity {

    /** 本机载体服务端口（与 OAuth 回调服务器 18889 错开）。 */
    private static final int PORT = 18890;

    private WebView webView;
    private TextView status;
    private AStudioCarrierServer server;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("AStudio（讯飞）登录");
        title.setTextSize(17f);
        title.setTextColor(Color.parseColor("#1D2129"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tlp);

        TextView diag = new TextView(this);
        diag.setText("诊断");
        diag.setTextSize(14f);
        diag.setTextColor(Color.parseColor("#86909C"));
        diag.setPadding(dp(12), dp(8), dp(12), dp(8));
        diag.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("AStudio 登录诊断")
                .setMessage(QrDiagnostics.summaryText())
                .setPositiveButton("关闭", null)
                .show());
        bar.addView(diag);

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14f);
        close.setTextColor(Color.parseColor("#165DFF"));
        close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(12f);
        status.setTextColor(Color.parseColor("#86909C"));
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " AStudioLogin/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient());

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(webView, wlp);
        setContentView(root);

        startCarrier();
    }

    private void startCarrier() {
        try {
            server = new AStudioCarrierServer(this);
            server.start(PORT, label -> ui.post(() -> {
                if (finishing) return;
                finishing = true;
                status.setText("登录成功：" + label);
                Intent back = new Intent();
                back.putExtra("login_ok", true);
                back.putExtra("provider", "astudio");
                setResult(RESULT_OK, back);
                ui.postDelayed(this::finish, 800);
            }));
            status.setText("请在下方选择短信 / 密码 / 扫码任一方式登录（新用户验证后自动开户）");
            webView.loadUrl(AStudioCarrierServer.url(PORT));
        } catch (Exception e) {
            // 端口被占：退到随机端口重试一次，失败就把真实原因摆到界面上（不静默白屏）
            try {
                server = new AStudioCarrierServer(this);
                final int[] holder = new int[]{0};
                java.net.ServerSocket probe = new java.net.ServerSocket(0);
                holder[0] = probe.getLocalPort();
                probe.close();
                server.start(holder[0], label -> ui.post(() -> {
                    if (finishing) return;
                    finishing = true;
                    status.setText("登录成功：" + label);
                    setResult(RESULT_OK);
                    ui.postDelayed(this::finish, 800);
                }));
                status.setText("请在下方选择短信 / 密码 / 扫码任一方式登录");
                webView.loadUrl(AStudioCarrierServer.url(holder[0]));
            } catch (Exception e2) {
                status.setText("登录页启动失败：" + e2.getMessage()
                        + "\n----扫码/登录诊断----\n" + QrDiagnostics.summaryText());
            }
        }
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
