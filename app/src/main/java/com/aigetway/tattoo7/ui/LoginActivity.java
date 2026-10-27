package com.aigetway.tattoo7.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import com.aigetway.tattoo7.R;
import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.provider.RaccoonProvider;

import java.util.Map;

/**
 * 统一登录页：按 provider 分派登录方式。
 * - trae：WebView 授权，回调 /authorize?info=（回传 token 而非 code），本机回调服务器收
 * - buddy：先取服务端 authUrl，WebView 打开后后台轮询 auth/token
 * - raccoon：本地生成 32hex，WebView 打开二维码页（手机扫），后台低频轮询 login_with_qrcode_code
 */
public class LoginActivity extends Activity {

    public static final String EXTRA_PROVIDER = "provider";
    /** true = 正在添加新账号：需要隔离 Cookie，避免复用上一个账号的登录态。 */
    public static final String EXTRA_ADD = "add_account";

    private static final int CALLBACK_PORT = 18889;
    private static final long POLL_MS = 5000L;

    private WebView webView;
    private TextView status;
    private TextView title;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String providerId = "trae";
    private boolean finishing;
    private boolean addAccount;
    /** 进入登录页时账户池里已有的令牌。"增加账号"时，只有扫到**不同**的令牌才算本次登录。 */
    private String tokenAtEntry;
    /** OAuth 授权期间临时挂着的 WebView（Google/GitHub 登录页）。 */
    private final java.util.List<android.webkit.WebView> oauthWindows = new java.util.ArrayList<>();

    /** Raccoon 登录页变体（站点对 appname/login_source 敏感，逐个尝试）。 */
    private int raccoonVariant = 0;
    private CallbackServer callbackServer;
    /** 最近一次 WebView 地址，用于判断用户是否离开二维码页。 */
    private volatile String lastUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        providerId = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (providerId == null) providerId = "trae";
        addAccount = getIntent().getBooleanExtra(EXTRA_ADD, false);
        tokenAtEntry = App.get().raccoonProvider().currentToken();

        webView = findViewById(R.id.loginWebView);
        status = findViewById(R.id.loginStatusText);
        title = findViewById(R.id.loginTitleText);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // ⚠️ 必须开多窗口支持：OAuth 登录（Google/GitHub）靠 window.open / target=_blank
        // 跳到授权页，默认 false 时新窗口被**直接丢弃**——用户点完按钮「毫无反应」。
        s.setSupportMultipleWindows(true);
        // 第三方 Cookie（Google 登录必须）——Lollipop 之后的默认值是拦的
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        // 站点按 User-Agent 区分客户端：默认 Android WebView UA 会被判定为非桌面端，
        // 登录提交被服务端静默拒绝（页面刷一下又回登录页）。这里统一伪装成桌面浏览器。
        // Raccoon 站点按 UA 判定客户端类型：伪装成桌面浏览器会让它在授权后
        // 给出「进入网页版首页」而不是回调 office-raccoon://。这里用真实桌面端 UA。
        if ("raccoon".equals(providerId)) {
            s.setUserAgentString("Raccoon-Work-260817-A");
        } else if ("buddy".equals(providerId)) {
            // ⚠️ 关键：Google 会**按 UA 判断是否为内嵌浏览器**，
            // 用 WorkBuddy/5.5.4 这种 App UA 会被判 disallowed_useragent。
            // base.apk（实测可用）用的是**真实移动 Chrome UA**。
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36");
        } else {
            s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
        // Raccoon：每次都从**干净的网页会话**开始。
        // 还原旧 Cookie 会让页面启动时抛 "Error restoring user info: 200003"
        // （200003 = token 无效），SPA 状态坏掉，点登录毫无反应。
        if (addAccount || "raccoon".equals(providerId)) {
            cm.removeAllCookies(null);
            cm.flush();
            try {
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {
            }
            try {
                webView.clearCache(true);
                webView.clearHistory();
            } catch (Exception ignored) {
            }
        } else {
            restoreCookies(providerId);
        }


        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            // 2026-10-15 最后一路：直接在网络层截获换票/登录接口的响应体。
            // refresh_token 只出现在 POST /api/web/auth/v1/login_with_authorization_code 与
            // /api/web/auth/v1/refresh 的响应里（HttpOnly Set-Cookie + body），JS 与
            // CookieManager 都读不到（vc129 真机 20s 等不到已实证）。WebView 网络栈里
            // shouldInterceptRequest 拿不到响应 body（只能拿请求头），所以这里改为：
            // 拦到这两个 URL 的**请求**后，App 端自己重放一遍该请求 —— 响应体里的
            // refresh_token 就到手了。授权码一次性，重放会 409/200035，但**我们的目标
            // 不是重放成功**，而是读 body 里 Set-Cookie 前的 JSON —— 换票成功路径下
            // 站点网页自己已经消费过 code，App 端再打同一接口只会拿 error。
            // 因此真正的做法是：截到换票请求体里的 authorization_code，App 端用它走
            // RaccoonProvider.exchangeCode —— 与网页共享同一个 code 不可能，所以改为
            // 抢跑：**在网页发换票请求之前** App 端拿不到 code。
            // 结论：这条由「授权码 bodyScan → App 端 exchangeCode」链路覆盖
            // （onBodyText 已实现），这里只需要把 onBodyText 的 rt 来源补上：
            // exchangeCode 的响应里带 refresh_token —— onHttp 已扫 BODY_RT 存入
            // bodyRefresh，tryFlushPending 里优先用它补写，已完成。此注释仅记录结论。

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                lastUrl = url;
                // 登录链路诊断：看清 OAuth 到底停在哪一跳
                App.get().logSink().log("LOGIN", "主页面: " + url);
                if (!finishing && providerId.equals("raccoon")
                        && com.aigetway.tattoo7.provider.RaccoonProvider.isLoginCallback(url)) {
                    view.stopLoading();
                    handleCallbackAsync(url);
                    return;
                }
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastUrl = url;
                saveCookies(providerId);
                if (providerId.equals("raccoon")) {
                    injectHook();
                    ui.postDelayed(LoginActivity.this::sniffToken, 1500);
                    // 2026-10-15：授权页（"授权 Raccoon-Work / 想要访问您的账号"）在不同版本里
                    // 按钮文案与勾选项都变过，且新版按钮可能因未勾选条款而 disabled —— 只靠
                    // JS 定时器按词表点，一旦词表没覆盖就表现为「点登录一闪而过、永远抓不到 token」。
                    // 这里补两层：① 让页面把可见按钮/勾选框文本上报，状态栏直接看到问题；
                    // ② 轮询 console 里的 JWT（部分版本把登录态写进前端日志）。
                    ui.postDelayed(LoginActivity.this::reportPageControls, 1200);
                    ui.postDelayed(LoginActivity.this::sniffConsoleToken, 2500);
                }
            }
        });

        if ("buddy".equals(providerId)) {
            // OAuth 登录（Google / GitHub）会开新窗口跳到授权页。
            // 之前 setSupportMultipleWindows 默认 false，新窗口被**直接丢弃**，
            // 用户点完按钮毫无反应，页面停在「等待授权中」直到 state 过期。
            //
            // 授权页放在独立 WebView 里正常走完即可：token 由后台 pollLogin 轮询拿到，
            // 不依赖网页回调，所以这里不需要把授权页「交接」回主 WebView
            // （早期版本在 onPageFinished 里 destroy 掉，反而把授权页弄坏了）。
            webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onCreateWindow(android.webkit.WebView view, boolean isDialog,
                                             boolean isUserGesture, android.os.Message resultMsg) {
                    try {
                        android.webkit.WebView newWeb = new android.webkit.WebView(view.getContext());
                        android.webkit.WebSettings ns = newWeb.getSettings();
                        ns.setJavaScriptEnabled(true);
                        ns.setDomStorageEnabled(true);
                        ns.setDatabaseEnabled(true);
                        ns.setJavaScriptCanOpenWindowsAutomatically(true);
                        ns.setSupportMultipleWindows(true);
                        ns.setUserAgentString(view.getSettings().getUserAgentString());
                        // 授权页会写 Cookie（Google 登录必需）
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(newWeb, true);
                        // 新窗口也要装 WebChromeClient：授权页自己可能再开窗，
                        // 没有它这一层仍会被丢弃（Google 的多跳授权就属于这种）。
                        // hint 页上的「用系统浏览器继续」由这个 bridge 接
                        newWeb.addJavascriptInterface(new GoogleFallbackBridge(), "AndroidBridge");
                        newWeb.setWebChromeClient(new android.webkit.WebChromeClient() {
                            @Override
                            public boolean onCreateWindow(WebView v, boolean isDialog,
                                                         boolean isUserGesture,
                                                         android.os.Message msg2) {
                                try {
                                    android.webkit.WebView inner = new android.webkit.WebView(v.getContext());
                                    android.webkit.WebSettings is = inner.getSettings();
                                    is.setJavaScriptEnabled(true);
                                    is.setDomStorageEnabled(true);
                                    is.setSupportMultipleWindows(true);
                                    is.setUserAgentString(v.getSettings().getUserAgentString());
                                    android.webkit.CookieManager.getInstance()
                                            .setAcceptThirdPartyCookies(inner, true);
                                    inner.setWebViewClient(new android.webkit.WebViewClient());
                                    oauthWindows.add(inner);
                                    ((android.webkit.WebView.WebViewTransport) msg2.obj).setWebView(inner);
                                    msg2.sendToTarget();
                                    return true;
                                } catch (Throwable t) {
                                    setStatus("打开嵌套授权窗口失败: " + t);
                                    return false;
                                }
                            }
                        });
                        newWeb.setWebViewClient(new android.webkit.WebViewClient() {
                            @Override
                            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                                super.onPageStarted(v, url, f);
                                App.get().logSink().log("LOGIN", "授权页跳转: " + url);
                                // UA 已伪装成真实移动 Chrome（见上面 setUserAgentString），
                                // Google 不再判定为内嵌，因此这里**不做拦截**，
                                // 正常让授权流程跑完。
                                if (false && isGoogleBlocked(url)) {
                                    // ⚠️ 不能用 data: URL —— 那里 JS/JSBridge 都不生效，
                                    // 按钮点了没反应。改成停在当前页并重写 DOM。
                                    App.get().logSink().log("LOGIN",
                                            "Google 拒绝内嵌窗口，改为提示用系统浏览器");
                                    showGoogleFallback(v);
                                    return;
                                }
                            }

                            @Override
                            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                                return handlePopupUrl(v, req == null ? null : req.getUrl().toString());
                            }

                            @Override
                            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                                return handlePopupUrl(v, url);
                            }
                        });
                        // ⚠️ **不把新窗口挂进视图树**：它只用于承接 window.open 的那一次跳转，
                        // 随后的每一跳都由 handlePopupUrl 转交给**主 WebView** 展示。
                        // （base.apk 的做法：新 WebView 只做 transport，不显示。）
                        oauthWindows.add(newWeb);
                        setStatus("授权窗口已打开，请在新窗口完成登录...");
                        ((android.webkit.WebView.WebViewTransport) resultMsg.obj).setWebView(newWeb);
                        resultMsg.sendToTarget();
                        return true;
                    } catch (Throwable e) {
                        // ⚠️ 绝不静默：上一次就是这里吞掉异常，用户只看到「点了没反应」
                        setStatus("打开授权窗口失败: " + e);
                        return false;
                    }
                }

                @Override
                public void onCloseWindow(android.webkit.WebView window) {
                    // 用户在授权页点了关闭：收走窗口，回到主界面（后台轮询仍在跑）
                    try {
                        ((android.view.ViewGroup) webView.getParent()).removeView(window);
                    } catch (Throwable ignored) {
                    }
                    oauthWindows.remove(window);
                    try {
                        window.destroy();
                    } catch (Throwable ignored) {
                    }
                    setStatus("授权窗口已关闭，如已完成登录请点「检测登录状态」");
                }
            });
        }

        if ("raccoon".equals(providerId)) {
            raccoonBridge = new RaccoonBridge();
            webView.addJavascriptInterface(raccoonBridge, "AndroidBridge");
            // 授权后站点可能用 window.open 弹新窗口；window.open 的参数已被 JS hook
            // 里的 rep() 拦下上报，这里只需禁止系统弹窗（否则会跳出空白窗口盖住 App）
            webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onCreateWindow(android.webkit.WebView view, boolean isDialog,
                                             boolean isUserGesture, android.os.Message resultMsg) {
                    return false;
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                    // 控制台里的 scheme 线索也一并上报（排查自定义跳转用）
                    try {
                        String msg = String.valueOf(m.message());
                        String cb = com.aigetway.tattoo7.provider.RaccoonProvider.extractCallback(msg);
                        if (cb != null) new RaccoonBridge().onCallback(cb);
                        else setStatus("console " + m.message() + " @" + m.lineNumber());
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            });
        }
        // 系统按 office-raccoon:// 把回调直接送进来（manifest intent-filter 命中）
        handleSchemeIntent(getIntent());
        if ("raccoon".equals(providerId) && tokenAtEntry != null && !tokenAtEntry.isEmpty()) {
            setStatus("正在添加新账号：请在网页中登录（本次登录必须与当前账号不同）");
        }
        if ("raccoon".equals(providerId)) {
            Button sw = findViewById(R.id.loginSwitchBtn);
            sw.setVisibility(View.VISIBLE);
            sw.setOnClickListener(v -> {
                raccoonVariant = (raccoonVariant + 1) % 4;
                sw.setText("换登录页(" + (raccoonVariant + 1) + "/4)");
                startRaccoon();
            });
        }
        Button check = findViewById(R.id.loginCheckBtn);
        check.setOnClickListener(v -> {
            injectHook();
            setStatus("正在检测登录状态...");
            ui.postDelayed(LoginActivity.this::sniffToken, 300);
            ui.postDelayed(LoginActivity.this::sniffCookie, 900);
        });

        startLogin();
    }

    /**
     * 网页 JS 桥：Raccoon 登录成功后可能用 JS（window.open / location 赋值）跳转到
     * office-raccoon://auth/callback，这类跳转**不会**触发
     * shouldOverrideUrlLoading / onPageStarted，必须由注入脚本主动上报。
     */
    private class RaccoonBridge {
        @android.webkit.JavascriptInterface
        public void onCallback(String url) {
            if (url == null || finishing) return;
            App.get().logSink().log("LOGIN", "onCallback 收到: "
                    + (url.length() > 120 ? url.substring(0, 120) + "…" : url));
            final String raw = url;
            // vc133：复用统一异步入口（换票请求必须离开主线程）
            handleCallbackAsync(raw);
        }

        /**
         * 登录/刷新响应体里扫到的 refresh_token。
         *
         * <p>站点常把 rt 只写在 POST /login 的响应 JSON 里，cookie 与 storage 未必落盘。
         * 旧实现 onHttp 把 body 显示到状态栏就丢掉，于是新账号存不到 rt —— 之后刷新
         * 只能沿用别的账号留下的值（已修）或干脆失败。这里把它接住。
         */
        private volatile String bodyRefresh = "";
        private static final java.util.regex.Pattern BODY_RT = java.util.regex.Pattern
                .compile("\"(?:refresh_token|refreshToken|refresh-token)\"\\s*:\\s*\"([^\"]{20,})\"");

        /** 网络兜底：任何 POST 响应体/页面文本里出现 office-raccoon 回调即消费。 */
        @android.webkit.JavascriptInterface
        public void onBodyText(String text) {
            if (text == null || text.length() < 20 || finishing) return;
            App.get().logSink().log("LOGIN", "onBodyText 命中, len=" + text.length()
                    + " head=" + text.substring(0, Math.min(120, text.length())).replace('\n', ' '));
            String cb = com.aigetway.tattoo7.provider.RaccoonProvider.extractCallback(text);
            if (cb == null) {
                // 响应体里没有 scheme 形态时，退一步：直接抠授权码
                // （/api/web/auth/v1/authorization_code 返回 {"authorization_code":"…","expires_in":N}，
                //  body 里从不出现 office-raccoon:，只认 scheme 会漏）。
                String code = authorizationCodeFromBody(text);
                if (code != null) {
                    cb = "office-raccoon://auth/callback?code=" + code;
                } else {
                    return;
                }
            }
            if (cb.equals(lastBodyCb)) return; // 同一段 body 反复触发时只消费一次
            lastBodyCb = cb;
            new RaccoonBridge().onCallback(cb);
        }
        private volatile String lastBodyCb = "";

        /** 从 JSON / 文本里抠 authorization_code 值（含 expires_in 佐证时更可信）。 */
        private static String authorizationCodeFromBody(String text) {
            if (text == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(?:authorization_code|authorizationCode|auth_code)\"\\s*:\\s*\"([A-Za-z0-9_\\-]{16,128})\"")
                    .matcher(text);
            if (m.find()) return m.group(1);
            return null;
        }

        /** 诊断：网页里的网络请求（全量上报，不只失败）。 */
        @android.webkit.JavascriptInterface
        public void onHttp(String url, int status, String body) {
            if (url == null) return;
            try {
                if (body != null && body.length() > 20) {
                    java.util.regex.Matcher mm = BODY_RT.matcher(body);
                    if (mm.find()) bodyRefresh = mm.group(1);
                ui.post(LoginActivity.this::tryFlushPending);
                }
            } catch (Exception ignored) {
            }
            ui.post(() -> {
                // 只显示本站业务请求；验证码/心跳等第三方请求会把状态栏刷爆，看不到关键信息
                String u = String.valueOf(url);
                if (u.indexOf("xiaohuanxiong") < 0) return;
                setStatus("请求 " + status + " " + u
                        + "\n" + (body == null || body.isEmpty() ? "" : body));
            });
        }

        /** 穷举 localStorage/sessionStorage/cookie/内联脚本，扫出任何像 JWT 的值。 */
        @android.webkit.JavascriptInterface
        public void onStorage(String blob) {
            if (blob == null || blob.isEmpty() || finishing) return;
            // refresh_token 与 access 同批上报：缺了就退化成长期要重新登录。
            // 2026-10-15：站点 cookie 名带前缀（`${prefix}raccoon_refresh_token`，prefix 因端而异），
            // 只认裸名会漏 → 改成「名字含 refresh_token 就取」+ CookieManager 兜底（HttpOnly）。
            String rt = anyRefreshCookie(blob);
            if (rt.isEmpty()) rt = bodyRefresh;
            if (rt.isEmpty()) rt = cookieManagerRefresh();
            final String refresh = rt;
            // vc140：换票通道已成功（loginDone）后，网页抓取通道一律停止落库，
            // 否则 scanAll 残留一轮会用空 rt 洗掉刚写好的凭据。
            if (loginDone) return;
            if (!refresh.isEmpty()) ui.post(LoginActivity.this::tryFlushPending);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
                    .matcher(blob);
            if (!m.find()) {
                ui.post(() -> {
                    if (!finishing) setStatus("已扫描网页存储 " + blob.length() + " 字节，未发现 JWT");
                });
                return;
            }
            m.reset();
            while (m.find()) {
                String tok = m.group();
                if (isStaleToken(tok)) {
                    ui.post(() -> {
                        if (!finishing) setStatus("扫到的是当前账号的旧令牌，请换一个新账号登录");
                    });
                    continue;
                }
                // vc136：scanAll 每 2.5s 触发一次，同一 token 会被反复扫到。
                // 旧实现每次都重跑 saveTokenFromWeb（内含一次 user_info 网络请求）并再打一行
                // 「pendingSave 置位」→ 真机日志被同一行刷屏数百条，既费流量又淹没关键信息。
                // 同一 token 只处理一次。
                if (tok.equals(lastPendingTok)) return;
                if (tok.indexOf('.') > 20
                        && App.get().raccoonProvider().saveTokenFromWeb(tok, refresh)) {
                    lastPendingTok = tok;
                    if (refresh.isEmpty()) {
                        // rt 尚未到手：等它出现（onHttp/onStorage/CookieManager 会再触发本类逻辑）
                        pendingSave = tok;
                        ui.post(() -> setStatus("已获登录凭据，等待续期凭据写入…"));
                        App.get().logSink().log("LOGIN", "pendingSave 置位(onStorage), rt为空");
                    } else {
                        ui.post(() -> done("登录成功"));
                    }
                    return;
                }
            }
            ui.post(() -> {
                if (!finishing) setStatus("扫到疑似令牌但校验未通过");
            });
        }

        /** 网页已在用 Authorization 头调私有接口 —— 直接把令牌收走（最可靠）。 */
        @android.webkit.JavascriptInterface
        public void onAuthHeader(String token, String refresh) {
            if (token == null || token.isEmpty() || finishing || loginDone) return;
            // 请求头里只有 access，rt 常在响应体 —— 抓不到 rt 就取 onHttp 存下的那个
            String r = refresh == null ? "" : refresh.trim();
            if (r.isEmpty()) r = bodyRefresh;
            final String rt = r;
            ui.post(() -> {
                if (isStaleToken(token)) return;
                if (App.get().raccoonProvider().saveTokenFromWeb(token, rt)) {
                    if (rt.isEmpty()) {
                        pendingSave = token;
                        setStatus("已获登录凭据，等待续期凭据写入…");
                        App.get().logSink().log("LOGIN", "pendingSave 置位(onAuthHeader), rt为空");
                    } else {
                        done("登录成功");
                    }
                }
            });
        }

        /** 诊断：页面 JS 异常 —— 很多"点了没反应"其实是前端 JS 报错。 */
        @android.webkit.JavascriptInterface
        public void onJsError(String msg) {
            if (msg == null) return;
            ui.post(() -> setStatus("页面报错: " + msg));
        }

        @android.webkit.JavascriptInterface
        public void onLoggedIn() {
            ui.post(() -> {
                if (!finishing) sniffToken();
            });
        }
    }

    /** 清空 WebView 的 Cookie 与本地存储（保证干净登录态）。 */
    private void clearWebSession() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception ignored) {
        }
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {
        }
        try {
            webView.clearCache(true);
            webView.clearHistory();
        } catch (Exception ignored) {
        }
    }

    private static final String GOOGLE_HINT_HTML =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>body{font-family:-apple-system,sans-serif;background:#fff;color:#16181d;"
            + "padding:26px 22px;line-height:1.75}h1{font-size:19px;margin:0 0 12px}"
            + "p{font-size:14px;color:#6b7280;margin:0 0 14px}"
            + "a{display:block;text-align:center;background:#2E7DF7;color:#fff;text-decoration:none;"
            + "padding:14px;border-radius:26px;font-size:16px;font-weight:600;margin:6px 0}"
            + ".s{background:#F1F3F7;color:#16181d}</style></head><body>"
            + "<h1>Google 登录需要用系统浏览器</h1>"
            + "<p>Google <b>禁止</b>在 App 内嵌的浏览器窗口里完成登录（就是刚才那个"
            + "「此浏览器或应用可能不安全」），这是它的安全策略，任何 App 都绕不过。</p>"
            + "<p><b>可以照常登录：</b>下面的按钮会用你手机上的<b>系统浏览器</b>打开授权页，"
            + "在那里完成 Google 授权后回到本 App 即可自动登录。</p>"
            + "<a href=\"t7openbrowser\">用系统浏览器继续登录</a>"
            + "<a class=\"s\" href=\"t7close\">返回（改用 GitHub / 邮箱登录）</a>"
            + "<p style=\"margin-top:16px;font-size:13px\">如果不想用 Google，"
            + "关掉这个窗口后直接在登录页选 <b>GitHub</b> 或 <b>邮箱</b> 登录即可。</p>"
            + "</body></html>";


    /**
     * Google 在 WebView 里的 OAuth：只要进了它的授权路径就**必然**被拒
     * （Google 禁止 App 内嵌浏览器窗口登录），两种表现：
     *   · accounts.google.com/o/oauth2/... 直接 400 disallowed_useragent
     *   · 跳到 /signin 并在页面上提示浏览器不受支持
     * 所以不只匹配错误文案，而是**认路径**。
     */
    private static boolean isGoogleBlocked(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.US);
        boolean google = u.contains("accounts.google.com") || u.contains("accounts.google.co.");
        if (!google) return false;
        return u.contains("/o/oauth2/") || u.contains("/signin")
                || u.contains("disallowed_useragent") || u.contains("not secure");
    }

    /**
     * 新窗口里的导航处理：**把每一跳都转交给主 WebView**。
     *
     * <p>这是 base.apk（workbuddy2api，实测可用）的做法：onCreateWindow 里 new 出来的
     * WebView 并没有挂进视图树，用户在它里面点Google/GitHub 后页面是不可见的，
     * 于是「授权做了但什么都没发生」。转交主窗口后流程可见、可继续。
     */
    private boolean handlePopupUrl(android.webkit.WebView popup, String url) {
        if (url == null || url.isEmpty()) return false;
        App.get().logSink().log("LOGIN", "授权窗跳转->主窗: " + url);
        // 收起授权窗，后续全部在主 WebView 里继续
        closeOauthWindow();
        try {
            webView.loadUrl(url);
        } catch (Throwable e) {
            setStatus("跳转失败: " + e.getMessage());
        }
        return true;
    }

    /** 把 Google 的拒绝页换成「用系统浏览器继续」的说明页（DOM 注入，保证 JSBridge 可用）。 */
    private void showGoogleFallback(android.webkit.WebView v) {
        if (v == null) return;
        try {
            String html = GOOGLE_HINT_HTML.replace("\"", "\\\"");
            String js = "(function(){try{"
                    + "document.open();document.write(\"" + html + "\");document.close();"
                    + "function bind(){"
                    + "  var a=document.querySelector('a[href=\\\"t7openbrowser\\\"]');"
                    + "  if(a){a.onclick=function(e){e.preventDefault();"
                    + "    window.AndroidBridge.openInSystemBrowser('1');return false;};}"
                    + "  var b=document.querySelector('a[href=\\\"t7close\\\"]');"
                    + "  if(b){b.onclick=function(e){e.preventDefault();"
                    + "    window.AndroidBridge.closeHint('1');return false;};}"
                    + "}"
                    + "if(document.readyState==='complete')bind();"
                    + "else document.addEventListener('DOMContentLoaded',bind);"
                    + "setTimeout(bind,300);setTimeout(bind,900);"
                    + "}catch(e){}})();";
            v.evaluateJavascript(js, null);
        } catch (Throwable e) {
            setStatus("无法显示提示页: " + e);
        }
    }

    /**
     * hint 页的按钮桥：把授权页交给系统浏览器打开。
     *
     * <p>为什么这样能绕开 Google 的限制：本登录是**服务端 state 轮询**
     * （{@code /v2/plugin/auth/token?state=xxx}），token 由 App 后台拿，
     * **不依赖网页回调**。所以在真浏览器（Chrome）里完成 Google 授权后，
     * App 轮询依然能取到 token。
     */
    private class GoogleFallbackBridge {
        @android.webkit.JavascriptInterface
        public void openInSystemBrowser(String dummy) {
            ui.post(() -> openAuthInSystemBrowser());
        }

        @android.webkit.JavascriptInterface
        public void closeHint(String dummy) {
            ui.post(() -> closeOauthWindow());
        }
    }

    /** 用系统默认浏览器打开当前授权页。 */
    private void openAuthInSystemBrowser() {
        String url = App.get().buddyProvider().pendingAuthUrl();
        if (url == null || url.isEmpty()) {
            setStatus("授权地址已失效，请返回重新开始登录");
            return;
        }
        // 授权窗先收起来，避免与浏览器争抢前台
        closeOauthWindow();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            setStatus("请在浏览器中完成登录，完成后会自动获取凭据...");
            // 后台继续轮询
            ui.postDelayed(() -> pollBuddy(), POLL_MS);
        } catch (Throwable e) {
            setStatus("无法打开浏览器: " + e.getMessage());
        }
    }

    /** 收走所有授权窗口。 */
    private void closeOauthWindow() {
        for (android.webkit.WebView w : new java.util.ArrayList<>(oauthWindows)) {
            try {
                android.view.ViewGroup p = (android.view.ViewGroup) w.getParent();
                if (p != null) p.removeView(w);
            } catch (Throwable ignored) {
            }
            try {
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
        oauthWindows.clear();
    }

    /** 该令牌是否为"进入登录页时就已存在"的旧令牌（增加账号时应忽略）。 */
    private boolean isStaleToken(String tok) {
        if (tok == null || tok.isEmpty()) return true;
        String entry = tokenAtEntry;
        if (entry == null || entry.isEmpty()) return false;
        if (tok.equals(entry)) return true;
        // JWT 相同账号不同实例时 payload 可能一致，用长度+前缀再比一次更稳
        return tok.length() == entry.length() && tok.endsWith(entry.substring(
                Math.max(0, entry.length() - 16)));
    }

        /**
     * cookie 串里找 refresh_token：名字**含** refresh_token 即命中（站点带前缀）。
     * 值太短的（<20 字符，多半是占位/删除标记）跳过。
     */
    private static String anyRefreshCookie(String blob) {
        if (blob == null) return "";
        for (String kv : blob.split(";")) {
            String p = kv.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).toLowerCase();
            if (!name.contains("refresh_token") && !name.contains("refreshtoken")) continue;
            String v = p.substring(eq + 1).trim();
            try { v = java.net.URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }
            if (v.length() >= 20) return v;
        }
        // localStorage 形式 key=value 里 key 含 refresh
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)[A-Za-z0-9_]*refresh[_]?token[A-Za-z0-9_]*[=:]{1,2}\\s*\"?([A-Za-z0-9_\\-\\.]{20,})\"?")
                .matcher(blob);
        if (m.find()) {
            // vc134：rt 与 access 都是 JWT，无法靠 eyJ 前缀区分。旧实现用
            // `!eyJ || len>60` 放行 → 把 access 当 rt 存了（续期必报 verify failed）。
            // 这里只负责取值，由调用方按「不得等于当前 access」过滤。
            return m.group(1);
        }
        return "";
    }

    /** CookieManager 兜底：HttpOnly cookie JS 读不到，这里能读到。 */
    private String cookieManagerRefresh() {
        try {
            String c = android.webkit.CookieManager.getInstance()
                    .getCookie("https://xiaohuanxiong.com");
            return anyRefreshCookie(c);
        } catch (Exception e) {
            return "";
        }
    }
    /**
     * 从 cookie 串里取js-cookie 写入的值。
     * 站点把 access/refresh 分别存成 {@code raccoon_token} / {@code raccoon_refresh_token}
     * （domain=.xiaohuanxiong.com），**refresh_token 只在这里**——
     * 之前只存了 access，3 小时后必然要重新登录。
     */
    


    /**
     * Google 在 WebView 里的 OAuth：只要进了它的授权路径就**必然**被拒
     * （Google 禁止 App 内嵌浏览器窗口登录），两种表现：
     *   · accounts.google.com/o/oauth2/... 直接 400 disallowed_useragent
     *   · 跳到 /signin 并在页面上提示浏览器不受支持
     * 所以不只匹配错误文案，而是**认路径**。
     */
    
    /**
     * 新窗口里的导航处理：**把每一跳都转交给主 WebView**。
     *
     * <p>这是 base.apk（workbuddy2api，实测可用）的做法：onCreateWindow 里 new 出来的
     * WebView 并没有挂进视图树，用户在它里面点Google/GitHub 后页面是不可见的，
     * 于是「授权做了但什么都没发生」。转交主窗口后流程可见、可继续。
     */
    
    /** 把 Google 的拒绝页换成「用系统浏览器继续」的说明页（DOM 注入，保证 JSBridge 可用）。 */
    
    /**
     * hint 页的按钮桥：把授权页交给系统浏览器打开。
     *
     * <p>为什么这样能绕开 Google 的限制：本登录是**服务端 state 轮询**
     * （{@code /v2/plugin/auth/token?state=xxx}），token 由 App 后台拿，
     * **不依赖网页回调**。所以在真浏览器（Chrome）里完成 Google 授权后，
     * App 轮询依然能取到 token。
     */
    
    /** 用系统默认浏览器打开当前授权页。 */
    
    /** 收走所有授权窗口。 */
    
    /** 该令牌是否为"进入登录页时就已存在"的旧令牌（增加账号时应忽略）。 */
    
        /**
     * cookie 串里找 refresh_token：名字**含** refresh_token 即命中（站点带前缀）。
     * 值太短的（<20 字符，多半是占位/删除标记）跳过。
     */
    
    /** CookieManager 兜底：HttpOnly cookie JS 读不到，这里能读到。 */
    
    /**
     * 已验证的 access 待续期凭据到手后再落库收工。
     * 登录接口的 refresh_token 比网页可见的登录态晚一拍（onHttp 响应体 / HttpOnly cookie），
     * 旧逻辑 access 一扫到立刻 done()，登录页随即销毁 —— rt 永远抓不到，3 小时必重登。
     */
    private volatile String pendingSave = "";
    private volatile long pendingSince = 0;
    /** vc136：scanAll 每 2.5s 触发，同一 token 只允许处理一次（消刷屏 + 省一次 user_info 请求）。 */
    private volatile String lastPendingTok = "";
    /** vc134：换票成功后置位，网页抓取侧的 pendingSave / 兜底落库一律停止。 */
    private volatile boolean loginDone = false;
    private RaccoonBridge raccoonBridge;

    /** rt 到手后：补写 refreshToken、收工。给 rt 最多 ~20s（8×2.5s）。 */
    private void tryFlushPending() {
        if (finishing || loginDone || pendingSave.isEmpty()) return;
        String rt = cookieManagerRefresh();
        if (rt.isEmpty()) rt = bodyRefresh0();
        App.get().logSink().log("LOGIN", "tryFlush: cookieRt=" + (rt.isEmpty() ? "空" : "有(" + rt.length() + ")")
                + " bodyRefresh=" + (raccoonBridge == null ? "?" : String.valueOf(raccoonBridge.bodyRefresh.length())));
        if (rt.isEmpty()) {
            // 等 rt 超过 ~20s 仍空：rt 大概率不在任何可读位置。兜底直接存 access 收工
            // （access 到期前可用；到期后用户需重登，好过永久卡在「等待续期凭据写入」）。
            if (pendingSince == 0) pendingSince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - pendingSince > 20_000L) {
                final String tok = pendingSave;
                pendingSave = "";
                App.get().logSink().log("LOGIN", "rt 等待超 20s，兜底只存 access 收工");
                new Thread(() -> {
                    boolean ok = App.get().raccoonProvider().saveTokenFromWeb(tok, null);
                    ui.post(() -> {
                        if (ok) done("登录成功（未获续期凭据，到期需重登）");
                        else setStatus("登录凭据落库失败，请重试");
                    });
                }).start();
            }
            return;
        }
        final String tok = pendingSave;
        pendingSave = "";
        final String frt = rt;
        new Thread(() -> {
            boolean ok = App.get().raccoonProvider().saveTokenFromWeb(tok, frt);
            ui.post(() -> {
                if (ok) done("登录成功");
                else setStatus("续期凭据已捕获但落库失败，请重试登录");
            });
        }).start();
    }

    private String bodyRefresh0() { return raccoonBridge == null ? "" : raccoonBridge.bodyRefresh; }

    /** 从 CookieManager 侧嗅探 token（JS 读不到 HttpOnly cookie 时才有用）。 */
    private void sniffCookie() {
        if (finishing || !"raccoon".equals(providerId)) return;
        try {
            String c = CookieManager.getInstance().getCookie("https://xiaohuanxiong.com");
            if (finishing || loginDone) return;
            String tok = findJwt(c);
            if (tok != null && !isStaleToken(tok)
                    && App.get().raccoonProvider().saveTokenFromWeb(tok,
                    anyRefreshCookie(c))) {
                done("登录成功");
            }
        } catch (Exception ignored) {
        }
    }

    /** 注入脚本：拦截自定义 scheme 跳转 + 登录成功后主动上报。 */
    private static final String RACCOON_HOOK =
            "(function(){if(window.__t7hook)return;window.__t7hook=1;"
            + "function rep(u){try{u=String(u);if(u.indexOf('office-raccoon:')===0)"
            + "{window.AndroidBridge.onCallback(u);return true;}}catch(e){}return false;}"
            + "var o=window.open;window.open=function(u){if(rep(u))return null;"
            + "return o&&o.apply(this,arguments);};"
            + "var _la=window.location.assign;try{window.location.assign=function(u){if(rep(u))return;return _la&&_la.call(this,u);};}catch(e){}"
            + "var _lr=window.location.replace;try{window.location.replace=function(u){if(rep(u))return;return _lr&&_lr.call(this,u);};}catch(e){}"
            + "try{var _h=history.pushState;history.pushState=function(){var r=rep(arguments[2]);return _h.apply(this,arguments);};}catch(e){}"
            + "setInterval(function(){try{"
            + "  if(document.body&&document.body.innerHTML.indexOf('\u767b\u5f55\u6210\u529f')>=0){window.AndroidBridge.onLoggedIn();}"
            // 深度扫描所有存储位置，任何像 JWT 的值都上报
            + "function scanAll(){try{var out=[];"
            + " try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "  out.push(k+'='+localStorage.getItem(k));}}catch(e){}"
            + " try{for(var j=0;j<sessionStorage.length;j++){var k2=sessionStorage.key(j);"
            + "  out.push(k2+'='+sessionStorage.getItem(k2));}}catch(e){}"
            + " out.push(document.cookie);"
            + " var st=document.querySelectorAll('script');"
            + " for(var s2=0;s2<st.length&&s2<40;s2++){out.push(st[s2].textContent.substring(0,3000));}"
            + " window.AndroidBridge.onStorage(out.join('\\n').substring(0,60000));"
            + "}catch(e){}}"
            + " setInterval(function(){try{"
            + "  if(document.body&&document.body.innerHTML.indexOf('\u767b\u5f55\u6210\u529f')>=0){window.AndroidBridge.onLoggedIn();}"
            + "}catch(e){}},2000);"
            + " setInterval(scanAll,2500);"
            + " try{document.addEventListener('DOMContentLoaded',scanAll);}catch(e){}"
            + " setTimeout(scanAll,1200);"
            + "}catch(e){}},2000);"
            // location.href = '...' 赋值不经过 assign/replace/setter，必须单独劫持
            + "try{var _d=Object.getOwnPropertyDescriptor(Location.prototype,'href');"
            + "if(_d&&_d.set){Object.defineProperty(window.location,'href',{configurable:true,"
            + "get:function(){return _d.get.call(window.location);},"
            + "set:function(v){if(!rep(v))_d.set.call(window.location,v);}});}}catch(e){}"
            // 捕获 <a href=office-raccoon:> 点击、<form> 提交
            + "document.addEventListener('click',function(ev){try{var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;"
            + "if(a&&rep(a.getAttribute('href'))){try{ev.preventDefault();ev.stopPropagation();}catch(e){}}}catch(e){}},true);"
            + "document.addEventListener('submit',function(ev){try{if(ev.target&&ev.target.action){rep(ev.target.action);}}catch(e){}},true);"
            // 扫描 iframe src（授权成功后站点常用隐藏 iframe 触发 scheme 跳转）
            + "setInterval(function(){try{var fs=document.querySelectorAll('iframe[src]');"
            + "for(var i=0;i<fs.length;i++){rep(fs[i].getAttribute('src'));}}catch(e){}},1000);"
            + "})();"
            // 登录成功后站点还会弹一层 OAuth 授权确认页（\"授权 Raccoon-Work / 想要访问您的账号\"），
            // 必须再点一次\"授权\"才会回调 office-raccoon://。这里自动点掉。
            + "(function(){if(window.__t7net)return;window.__t7net=1;"
            // 独立的 scheme 上报函数：第二个 IIFE 里的 rep(m,u,st,b) 是网络上报（4 参），
            // 不能复用。v1.26 曾把 scheme 传给它 → 垃圾 onHttp + 跳转照常放行（no-op）。
            + " function repScheme(u){try{u=String(u);"
            + "  if(u.indexOf('office-raccoon:')===0){window.AndroidBridge.onCallback(u);return true;}"
            + " }catch(e){}return false;}"
            // 2026-10-15 补：实例级覆盖 window.location.assign/replace 在部分 WebView
            // 内核上静默失败（Location 实例属性不可配置），scheme 跳转照常发生但拦截不到
            // → 授权成功页都出来了 code 却一直收不到。改为在 **Location.prototype** 上
            // 劫持（原型方法一定可配置），实例调用会沿原型链命中。
            + " try{var _pa=Location.prototype.assign;"
            + " Location.prototype.assign=function(u){if(repScheme(u))return;return _pa.call(this,u);};}catch(e){}"
            + " try{var _pr=Location.prototype.replace;"
            + " Location.prototype.replace=function(u){if(repScheme(u))return;return _pr.call(this,u);};}catch(e){}"
            + " window.onerror=function(m,u,l){try{window.AndroidBridge.onJsError(m+' @'+u+':'+l);}catch(e){}return false;};"
            + " var _oe=console.error;try{console.error=function(){try{"
            + "  window.AndroidBridge.onJsError(Array.prototype.join.call(arguments,' ').substring(0,180));}catch(e){}"
            + "  return _oe.apply(console,arguments);};}catch(e){}"
            + " function rep(m,u,st,b){try{window.AndroidBridge.onHttp(m+' '+u,st,String(b||'').substring(0,200));}catch(e){}}"
            // 网络兜底：响应体里出现 office-raccoon://auth/callback?code=… 就直接消费，
            // 不再依赖 scheme 跳转是否被 WebView 拦到
            + " function bodyScan(b){try{if(!b)return;var s=String(b);"
            + "  if(s.indexOf('office-raccoon')>=0||s.indexOf('authorization_code')>=0"
            + "   ||s.indexOf('\"expires_in\"')>=0)"
            + "  window.AndroidBridge.onBodyText(s.substring(0,60000));}catch(e){}}"
            // refresh_token 抓取：站点用 js-cookie 存 raccoon_refresh_token，但不同版本
            // 也见过 refresh_token / refreshToken / refresh-token，且部分构建把它写进
            // localStorage/sessionStorage 而不是 cookie。只认一个名字 → 新登录抓到空 rt，
            // 3 小时后必然要重新登录，或（旧逻辑）继承上一个账号的 rt 报
            // authorization verify failed。这里尽量抓全。
            + " function rtk(){try{"
            + "  var m=document.cookie.match(/(?:^|;\\s*)(raccoon_refresh_token|raccon_refresh_token|refresh_token|refreshToken|refresh-token)=([^;]*)/i);"
            + "  if(m&&m[2])return decodeURIComponent(m[2]);"
            + "  var st=[window.localStorage,window.sessionStorage];"
            + "  for(var s=0;s<2;s++){try{var L=st[s];if(!L)continue;"
            + "    for(var i=0;i<L.length;i++){var k=L.key(i);"
            + "      if(!k||!/refresh/i.test(k))continue;var v=L.getItem(k);if(!v||v.length<20)continue;"
            // 值可能是一整段 JSON（里面同时有 access_token）。裸扫 eyJ 会把 access_token
            // 当成 refresh_token 存下来 → 拿 access 去续期，服务端回
            // 「authorization verify failed」。先按 refresh 键取，取不到才认裸 JWT。
            + "      var js=String(v).match(/\"(?:refresh_token|refreshToken|refresh-token)\":\\s*\"([^\"]{20,})\"/);"
            + "      if(js)return js[1];"
            + "      if(/^eyJ/.test(String(v).trim())){var mm=String(v).match(/eyJ[A-Za-z0-9._\\-]{10,}/);if(mm)return mm[0];}}"
            + "  }catch(e){}}"
            + "  return '';}catch(e){return '';}}"
            // 网页拿到登录态后，所有私有接口请求都会带 Authorization —— 从请求头直接取令牌
            + " function auth(o){try{if(!o||!o.headers)return;var h=o.headers;"
            + " var g=function(k){try{return h.get?h.get(k):h[k];}catch(e){return null;}};"
            + " var t=g('Authorization')||g('authorization')||g('X-Access-Token')||g('x-access-token');"
            + " if(t){t=String(t);if(t.toLowerCase().indexOf('bearer ')===0)t=t.substring(7);"
            + " if(t.indexOf('.')>20){window.AndroidBridge.onAuthHeader(t,rtk());}}}catch(e){}}"
            + " try{var _hset=window.Headers&&window.Headers.prototype.set;"
            + " if(_hset){window.Headers.prototype.set=function(k,v){try{auth(this);}catch(e){}"
            + "  return _hset.call(this,k,v);};}}catch(e){}"
            + " var _f=window.fetch;try{window.fetch=function(i,o){var m=(o&&o.method)||'GET';"
            + "  var u=(typeof i==='string')?i:(i&&i.url)||'';try{auth(o);}catch(e){}"
            + "  return _f.apply(this,arguments).then(function(r){"
            + "   try{r.clone().text().then(function(t){bodyScan(t);"
            + "    if(m==='POST'){rep(m,u,r.status,t);}});}catch(e){}return r;});}}catch(e){}"
            + " var _o=XMLHttpRequest.prototype.open,_s=XMLHttpRequest.prototype.send;"
            + " try{XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return _o.apply(this,arguments);};}catch(e){}"
            + " try{var _srh=XMLHttpRequest.prototype.setRequestHeader;"
            + " XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{"
            + "  if(String(k).toLowerCase().indexOf('authorization')>=0&&v){var t=String(v);"
            + "   if(t.toLowerCase().indexOf('bearer ')===0)t=t.substring(7);"
            + "   if(t.indexOf('.')>20)window.AndroidBridge.onAuthHeader(t,rtk());}}catch(e){}"
            + "  return _srh.apply(this,arguments);};}catch(e){}"
            + " try{XMLHttpRequest.prototype.send=function(b){var x=this;"
            + "  x.addEventListener('load',function(){try{bodyScan(x.responseText);"
            + "   if(x.__m==='POST'){rep(x.__m,x.__u,x.status,x.responseText);}}catch(e){}});"
            + "  return _s.apply(this,arguments);};}catch(e){}"
            + "})();"
            + "(function(){if(window.__t7consent)return;window.__t7consent=1;"
            + " setInterval(function(){try{"
            + "  if(location.href.indexOf('xiaohuanxiong')<0)return;"
            + "  var els=document.querySelectorAll('button,a,div[class*=btn],span');"
            + "  for(var i=0;i<els.length;i++){"
            + "   var el=els[i];"
            + "   if(!el||el.offsetParent===null)continue;"
            + "   var t=(el.innerText||el.textContent||'').trim();"
            // 2026-10-15 修正：新版授权页按钮是「确认授权」（旧版才是「授权」），
            // 且下方多了一个条款勾选框「我已阅读并同意」——不勾选时「确认授权」是
            // disabled，点了没反应，表现为"登录页一闪而过却一直不出 token"。
            // 这里：先尽量把「我已阅读并同意」勾上，再点确认类按钮。
            // 只按精确词表匹配，避免误点「取消」「退出登录」等。
            + "   if(t.indexOf('\u6211\u5df2\u9605\u8bfb\u5e76\u540c\u610f')>=0){"
            + "     try{if(el.click){el.click();}else if(el.checked===false){el.checked=true;"
            + "       el.dispatchEvent(new Event('input',{bubbles:true}));"
            + "       el.dispatchEvent(new Event('change',{bubbles:true}));}}catch(e){}"
            + "     continue;"
            + "   }"
            + "   if(t==='\u6388\u6743'||t==='\u5141\u8bb8'||t==='\u786e\u8ba4\u6388\u6743'"
            + "      ||t==='\u540c\u610f\u6388\u6743'||t==='\u7ee7\u7eed\u6388\u6743'){"
            + "     try{window.AndroidBridge.onLoggedIn();}catch(e){}"
            + "     el.click(); return;"
            + "   }"
            + "  }"
            + " }catch(e){}},1500);"
            + "})();";

    private void injectHook() {
        try {
            webView.evaluateJavascript(RACCOON_HOOK, null);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 本机回调服务器 ----------------

    private void startCallbackServer() {
        try {
            callbackServer = new CallbackServer();
            callbackServer.start(CALLBACK_PORT, this::onServerCallback);
        } catch (Exception e) {
            setStatus("无法监听本地回调端口 " + CALLBACK_PORT + "：" + e.getMessage());
        }
    }

    /** 真正的本地回调（GET 302 或 POST 表单都走这里）。 */
    private String onServerCallback(Map<String, String> params) {
        if (providerId.equals("trae")) {
            String r = App.get().traeProvider().handleCallbackParams(params);
            if ("ok".equals(r)) {
                flushCookies();
                ui.post(() -> done("登录成功"));
                return page("登录成功", "正在返回 App…");
            }
            return page("TRAE 登录失败", r);
        }
        return page("OK", "");
    }

    private static String page(String t, String msg) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{font-family:sans-serif;background:#111;color:#eee;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + "div{text-align:center;padding:24px}h1{font-size:20px}p{color:#aaa;font-size:13px}</style>"
                + "</head><body><div><h1>" + esc(t) + "</h1><p>" + esc(msg) + "</p>"
                + "<p>可以关闭此页面返回 App。</p></div></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- URL 拦截（自定义 scheme + 兜底） ----------------

    /**
     * 统一的回调处理入口：handleLoginCallback 内部发换票 HTTP 请求，
     * 必须在后台线程执行（vc132 真机：主线程直调抛 NetworkOnMainThreadException）。
     */
    private void handleCallbackAsync(String rawUrl) {
        final com.aigetway.tattoo7.provider.RaccoonProvider rp =
                App.get().raccoonProvider();
        final String real = rp.extractCallback(rawUrl);
        if (real == null || !rp.isLoginCallback(real)) return;
        new Thread(() -> {
            final String r = rp.handleLoginCallback(real);
            ui.post(() -> {
                App.get().logSink().log("LOGIN", "handleLoginCallback 结果: "
                        + (r.length() > 200 ? r.substring(0, 200) : r));
                if ("ok".equals(r)) {
                    // vc134：换票成功即视为登录完成，取消网页抓取那条 pendingSave
                    // 与 20s 兜底 —— 否则它可能在 done() 之后又把网页抓来的
                    // （可能已被误判为 rt 的）令牌写进账号池。
                    // vc139："ok-dup"（重复投递同一 code）不触发 done —— 真线程
                    // 还在 saveLogin，此刻读 hasRefreshToken() 会误弹「未抓到续期凭据」。
                    if (!"ok-dup".equals(r)) {
                        loginDone = true;
                        pendingSave = "";
                        flushCookies();
                        // vc145：换票接口不给独立 rt 时（body rt==access、Set-Cookie 无），
                        // 最后从 WebView 的 HttpOnly cookie 收割 raccoon_refresh_token 补写。
                        // 必须在 flushCookies() 之后（确保 cookie 已就绪）。
                        boolean harvested = App.get().raccoonProvider().harvestWebviewRt();
                        done(harvested ? "登录成功（已从网页 Cookie 补获续期凭据）" : "登录成功");
                    }
                } else {
                    setStatus(r);
                }
            });
        }, "raccoon-exchange").start();
    }


    /** 处理系统按 scheme 送进来的回调 Intent（冷启动/单任务复用都走这里）。 */
    private void handleSchemeIntent(android.content.Intent it) {
        if (it == null || !"raccoon".equals(providerId)) return;
        android.net.Uri u = it.getData();
        if (u == null) return;
        String s = u.toString();
        if (com.aigetway.tattoo7.provider.RaccoonProvider.isLoginCallback(s)) {
            setStatus("收到系统回调，正在登录...");
            handleCallbackAsync(s);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleSchemeIntent(it);
    }

    private boolean handleUrl(String url) {
        String scheme = schemeOf(url);
        // 自定义 scheme（未注册时点了没反应，必须自己拦）
        if (scheme != null && !scheme.equals("http") && !scheme.equals("https")
                && !scheme.equals("about") && !scheme.equals("data") && !scheme.equals("javascript")) {
            // Raccoon 走 office-raccoon://auth/callback 授权码回调
            if (providerId.equals("raccoon") && com.aigetway.tattoo7.provider.RaccoonProvider.isLoginCallback(url)) {
                handleCallbackAsync(url);
                return true;
            }
        }
        // 兜底：本机回调被本地服务器收不到时（如端口被占用），仍尝试从 URL 取
        java.net.URI u;
        try {
            u = java.net.URI.create(url);
        } catch (Exception e) {
            return false;
        }
        String host = u.getHost();
        boolean local = host != null && (host.equals("127.0.0.1") || host.equals("localhost"));
        if (!local) return false;
        String path = u.getPath() == null ? "" : u.getPath();
        Map<String, String> params = com.aigetway.tattoo7.provider.TraeProvider.parseParams(url);
        if (providerId.equals("trae") && path.startsWith("/authorize")) {
            String r = App.get().traeProvider().handleCallbackParams(params);
            if ("ok".equals(r)) {
                flushCookies();
                done("登录成功");
            } else {
                setStatus(r);
            }
            return true;
        }
        return false;
    }

    /** 极简 query 解析（避免额外依赖）。 */
    private static class LinkedHashMapShim {
        java.util.Map<String, String> parse(String url) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            int q = url.indexOf('?');
            if (q < 0) return m;
            for (String kv : url.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                try {
                    m.put(java.net.URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
            return m;
        }
    }

    private static String schemeOf(String url) {
        int i = url.indexOf(':');
        if (i <= 0) return null;
        return url.substring(0, i).toLowerCase(java.util.Locale.US);
    }

    private void flushCookies() {
        try {
            saveCookies(providerId);
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    // ---------------- Cookie 持久化 ----------------

    private void saveCookies(String pid) {
        try {
            String cookie = CookieManager.getInstance().getCookie(currentHost());
            if (cookie != null && !cookie.isEmpty()) App.get().settings().setProviderCookie(pid, cookie);
        } catch (Exception ignored) {
        }
    }

    private void restoreCookies(String pid) {
        String c = App.get().settings().providerCookie(pid);
        if (c == null || c.isEmpty()) return;
        for (String p : c.split(";")) {
            String kv = p.trim();
            if (kv.indexOf('=') <= 0) continue;
            try {
                CookieManager.getInstance().setCookie(currentHost(), kv);
            } catch (Exception ignored) {
            }
        }
        CookieManager.getInstance().flush();
    }

    private String currentHost() {
        switch (providerId) {
            case "buddy": return "https://copilot.tencent.com";
            case "raccoon": return "https://xiaohuanxiong.com";
            case "trae": return "https://www.trae.cn";
            default: return "https://www.trae.cn";
        }
    }

    // ---------------- 启动各服务商登录 ----------------

    private void startLogin() {
        switch (providerId) {
            case "buddy":
                title.setText("登录 Buddy / CodeBuddy");
                startBuddy();
                break;
            case "raccoon":
                title.setText("登录 Raccoon / 小浣熊");
                startRaccoon();
                break;
            case "trae":
                title.setText("登录 TRAE / 字节");
                startCallbackServer();
                startTrae();
                break;
            default:
                setStatus("未知服务商: " + providerId);
                break;
        }
    }

    // ---- TRAE ----

    private void startTrae() {
        setStatus("请在下方完成 TRAE 账号登录并授权");
        try {
            webView.loadUrl(App.get().traeProvider().beginLogin(CALLBACK_PORT));
        } catch (Exception e) {
            setStatus("发起登录失败: " + e.getMessage());
        }
    }

    // ---- Buddy ----

    private void startBuddy() {
        // base.apk 在加载授权页前会先清空 Cookie/存储（clear_session=true）：
        // 否则上一个账号的登录态会让授权页直接跳过登录、或拿到错误账号。
        clearWebSession();
        setStatus("正在获取登录地址...");
        new Thread(() -> {
            try {
                String url = App.get().buddyProvider().beginLogin();
                ui.post(() -> {
                    setStatus("请在下方完成 CodeBuddy 账号登录");
                    webView.loadUrl(url);
                    pollBuddy();
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("获取登录地址失败: " + e.getMessage()));
            }
        }).start();
    }

    /** state 过期类错误（含「页面已过期」「state invalid/expired」）。 */
    private static boolean isStateExpired(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(java.util.Locale.US);
        return m.contains("过期") || m.contains("expired") || m.contains("invalid")
                || m.contains("11218") || m.contains("11219");
    }

    private void pollBuddy() {
        if (finishing) return;
        new Thread(() -> {
            String r = App.get().buddyProvider().pollLogin();
            ui.post(() -> {
                if ("ok".equals(r)) {
                    done("登录成功");
                } else if (r.startsWith("error")) {
                    setStatus(r);
                    // state 过期后不要让用户对着「页面已过期」发呆：
                    // 状态栏点一下就能重开一轮登录（重新申请 state）。
                    if (isStateExpired(r)) {
                        setStatus(r + " —— 点这里重新开始登录");
                        View st = findViewById(R.id.loginStatusText);
                        st.setOnClickListener(v -> {
                            st.setOnClickListener(null);
                            startBuddy();
                        });
                    }
                } else {
                    setStatus("等待授权中...");
                    ui.postDelayed(this::pollBuddy, POLL_MS);
                }
            });
        }).start();
    }

    // ---- Raccoon ----

    /**
     * Raccoon：打开桌面授权页 /code/authorize，登录成功后页面会跳到
     * office-raccoon://auth/callback —— 这是**授权码流程**，不是扫码轮询。
     * 另外保留网页 token 嗅探作为兜底（覆盖跳转异常的情况）。
     */
    private void startRaccoon() {
        setStatus("正在打开登录页...");
        new Thread(() -> {
            try {
                String url = App.get().raccoonProvider().beginLogin(raccoonVariant);
                ui.post(() -> {
                    setStatus("请登录；登录成功后会弹出授权确认页，点「授权」即可");
                    // 加载前再清一次：确保页面启动时读不到任何过期 token
                    // （否则会抛 "Error restoring user info: 200003"）
                    clearWebSession();
                    webView.loadUrl(url);
                    ui.postDelayed(this::sniffLoop, 3000);
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("打开登录页失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 低频轮询扫码结果。
     * 注意：用户在网页里用「验证码登录/密码登录」时，高频轮询会被服务端当成重复登录尝试，
     * 导致点登录没反应、二次报验证码错误。这里改成 5 秒一次，且用户一旦离开二维码页就停止轮询。
     */
    /** 循环嗅探网页 token，直到登录成功或页面关闭。 */
    private void sniffLoop() {
        if (finishing) return;
        if (!pendingSave.isEmpty()) tryFlushPending();
        sniffToken();
        // 授权成功后网页立刻发带 Authorization 的私有接口请求，
        // 每 1.5s 重注一次 hook 让 setRequestHeader 拦截器持续生效。
        injectHook();
        ui.postDelayed(this::sniffLoop, 1500);
    }


    /**
     * 从网页里嗅探 token（localStorage + cookie）。
     * 覆盖验证码/密码/微信/二维码全部登录方式：用户在页面里登录成功后，
     * 前端会把 JWT 落到 localStorage 或 cookie，这里取出来直接复用。
     * 不依赖扫码轮询，因此不会再和验证码流程互相干扰。
     */
    private void sniffToken() {
        if (finishing || !"raccoon".equals(providerId)) return;
        // 关键修复：账户池里已有旧令牌时，"增加账号"绝不能立刻判定成功返回。
        // 旧逻辑这里直接 done()，导致打开登录页 1~2 秒就弹回网关。
        // 现在一律继续往下扫描，只有扫到**与进入时不同**的令牌才算本次新登录。
        if (App.get().raccoonProvider().loggedIn() && !addAccount) {
            done("登录成功");
            return;
        }
        final String js = "(function(){try{var a=[];"
                + "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
                + "a.push(localStorage.getItem(k));}"
                + "for(var j=0;j<sessionStorage.length;j++){var k2=sessionStorage.key(j);"
                + "a.push(sessionStorage.getItem(k2));}"
                + "a.push(document.cookie);"
                + "return a.join('\\n');}catch(e){return '';}})()";
        try {
            webView.evaluateJavascript(js, value -> {
                if (finishing || value == null) return;
                String raw;
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    raw = value.substring(1, value.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
                } else {
                    raw = value;
                }
                String tok = findJwt(raw);
                if (tok == null) return;
                if (isStaleToken(tok)) return;
                RaccoonProvider rp = App.get().raccoonProvider();
                if (rp.saveTokenFromWeb(tok)) done("登录成功");
            });
        } catch (Exception ignored) {
        }
    }

    /** 从任意文本里找出第一个 JWT（a.b.c 三段、每段 base64url）。 */
    private static String findJwt(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,})")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 让授权页把「可见按钮文本 + 勾选框状态」上报回来。
     *
     * <p>背景：Raccoon 授权页各版本的按钮文案与结构改过多次（"授权" / "确认授权" /
     * 带条款勾选框的新版）。一旦自动点击没命中，用户看到的现象是"点了登录一闪而过、
     * 却一直抓不到 token"，无从判断。这里把页面上真实存在什么打出来，
     * 状态栏直接可读，不用再靠猜。
     */
    private void reportPageControls() {
        if (finishing || !"raccoon".equals(providerId)) return;
        final String js = "(function(){try{var out=[];"
                + "var els=document.querySelectorAll('button,a,[role=button],div[class*=btn],span,label');"
                + "for(var i=0;i<els.length&&out.length<14;i++){var el=els[i];"
                + " if(!el||el.offsetParent===null)continue;"
                + " var t=(el.innerText||el.textContent||'').trim();"
                + " if(!t||t.length>18)continue;"
                + " if(t.indexOf('\\n')>=0)continue;"
                + " var tag=el.tagName.toLowerCase();"
                + " var dis=(el.disabled===true)?'[disabled]':'';"
                + " var chk=(el.type==='checkbox'||el.type==='radio')?('['+(el.checked?'✓':'✗')+']'):'';"
                + " var s=tag+chk+dis+':'+t;"
                + " if(out.indexOf(s)<0)out.push(s);}"
                + "return '页面控件 '+location.pathname+' | '+out.join(' / ');"
                + "}catch(e){return '';}})()";
        try {
            webView.evaluateJavascript(js, value -> {
                if (finishing || value == null || value.length() < 10) return;
                String s = value;
                if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                s = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/");
                if (s.startsWith("页面控件")) setStatus(s);
                App.get().logSink().log("LOGIN", s);
            });
        } catch (Exception ignored) {
        }
    }

    /** 从页面 console 输出里捞 JWT（部分版本只在日志里打印登录态）。 */
    private void sniffConsoleToken() {
        if (finishing || !"raccoon".equals(providerId)) return;
        final String js = "(function(){try{var o=[];"
                + "if(window.__t7logs)o=window.__t7logs;"
                + "return o.join('\\n')+'\\n'+document.cookie;"
                + "}catch(e){return '';}})()";
        try {
            webView.evaluateJavascript(js, value -> {
                if (finishing || value == null) return;
                String raw = value;
                if (raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
                raw = raw.replace("\\n", "\n").replace("\\\"", "\"");
                if (raw.indexOf("eyJ") < 0) return;
                String tok = findJwt(raw);
                if (tok == null || isStaleToken(tok)) return;
                if (App.get().raccoonProvider().saveTokenFromWeb(tok, bridgeRefresh())) done("登录成功");
            });
        } catch (Exception ignored) {
        }
    }

    /** 网页里抓到的 refresh_token（可能在 localStorage/页面上，拿不到就传空）。 */
    private String bridgeRefresh() {
        return "";
    }

    // ---------------- 通用 ----------------

    private void setStatus(String t) {
        if (status != null) status.setText(t);
    }

    private void done(String msg) {
        if (finishing) return;
        finishing = true;
        flushCookies();
        if ("raccoon".equals(providerId)) {
            // 明确告知是否拿到了 refresh_token —— 拿不到的话 3 小时后必然要重登
            boolean rt = App.get().raccoonProvider().hasRefreshToken();
            msg = msg + (rt ? "（已保存续期凭据，可长期免登录）" : "（未抓到续期凭据，约 3 小时后需重新登录）");
        }
        setStatus(msg + "，正在返回...");
        ui.postDelayed(() -> {
            Intent back = new Intent();
            back.putExtra("login_ok", true);
            back.putExtra("provider", providerId);
            setResult(RESULT_OK, back);
            finish();
        }, 700);
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (callbackServer != null) callbackServer.stop();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
        }
        super.onDestroy();
    }
}