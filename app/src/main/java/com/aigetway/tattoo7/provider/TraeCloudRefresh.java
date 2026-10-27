package com.aigetway.tattoo7.provider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TRAE「云端刷新」：拿 WebView 里保存的 www.trae.cn 登录态（cookie）静默重跑一次
 * OAuth 授权，从 /authorize 回调里取回**新的 Token + RefreshToken**。
 *
 * <p>为什么必须有它：Trae 的 ExchangeToken 每次都会**轮换** refreshToken，
 * 本地那条 rt 是一次性的。只要有一次刷新后没把新 rt 写回、或登录时压根没抓到 rt，
 * 之后拿旧 rt 去换永远是 401 —— 这时唯一能自救的路子是重新走一次云端授权，
 * 而不是逼用户重新扫码登录。
 *
 * <p>实现要点：
 * <ul>
 * <li>回调地址 {@code http://127.0.0.1:PORT/authorize?...} 由 WebViewClient 直接拦下，
 * 从不真的建监听（与 LoginActivity 的拦截路径一致，端口只是占位）。</li>
 * <li>授权页可能需要点「授权」按钮，页面加载完后按文案自动点几次。</li>
 * <li>WebView 只能在主线程创建/销毁：主线程只负责建视图和加载，
 * 等待发生在调用方工作线程，超时后回主线程销毁 —— 绝不在主线程 await（会 ANR）。</li>
 * </ul>
 */
public final class TraeCloudRefresh {

    /** 回调 URL 占位端口：只用于匹配重定向，不监听。 */
    public static final int CALLBACK_PORT = 19881;

    private static final long TIMEOUT_MS = 45_000L;

    /** 网页登录态失效时通常会停留的页面特征。 */
    private static final java.util.regex.Pattern STUCK_HINT =
            java.util.regex.Pattern.compile("login|signin|sign-in|passport|sso|ticket|captcha|verify|qrcode", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 现场诊断：最后一次停留的 URL / 页面标题 / 主框架加载错误。 */
    private static volatile String lastUrl = "";
    private static volatile String lastTitle = "";
    private static volatile String lastError = "";
    private static volatile boolean stuckOnLogin = false;

    /** 结果：ok + 回调参数（成功时）或失败原因。 */
    public static final class Result {
        public final boolean ok;
        public final Map<String, String> params;
        public final String message;

        private Result(boolean ok, Map<String, String> params, String message) {
            this.ok = ok;
            this.params = params;
            this.message = message;
        }

        static Result ok(Map<String, String> p) {
            return new Result(true, p, "");
        }

        static Result fail(String msg) {
            return new Result(false, null, msg);
        }
    }

    private TraeCloudRefresh() {
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    /**
     * 同步执行一次静默重授权。必须在**非主线程**调用。
     *
     * @param authUrl {@link TraeProvider#beginLogin(int)} 生成的授权地址
     */
    public static Result run(Context app, String authUrl) {
        if (app == null || authUrl == null || authUrl.isEmpty()) {
            return Result.fail("云端刷新缺少参数");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.fail("云端刷新不能在界面主线程执行");
        }
        // 把内存里的 cookie 落盘，确保 WebView 用的是最新登录态
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }

        final CountDownLatch done = new CountDownLatch(1);
        final Result[] box = new Result[1];
        final WebView[] holder = new WebView[1];
        final Handler main = new Handler(Looper.getMainLooper());
        final String callbackPrefix = "http://127.0.0.1:" + CALLBACK_PORT + "/authorize";

        main.post(() -> {
            try {
                WebView wv = new WebView(app.getApplicationContext());
                holder[0] = wv;
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setDatabaseEnabled(true);
                wv.setWebViewClient(new WebViewClient() {

                    private void capture(String url) {
                        if (box[0] != null) return;
                        Map<String, String> p = TraeProvider.parseParams(url);
                        box[0] = p == null || p.isEmpty()
                                ? Result.fail("云端回调没有携带任何参数") : Result.ok(p);
                        done.countDown();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        if (url != null && url.startsWith(callbackPrefix)) {
                            capture(url);
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        String url = req == null || req.getUrl() == null ? null : req.getUrl().toString();
                        if (url != null && url.startsWith(callbackPrefix)) {
                            capture(url);
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (url != null) lastUrl = url;
                        try {
                            view.evaluateJavascript(
                                    "document.title||''", v -> {
                                        if (v != null && v.length() > 1) {
                                            lastTitle = v.replaceAll("^\"|\"$", "");
                                        }
                                    });
                        } catch (Exception ignored) {
                        }
                        if (box[0] == null) {
                            // 落在登录/扫码页说明网页登录态已失效，等满 45 秒也没意义
                            // /authorize、/authorization 是授权确认页（正常一环，等自动点授权），
                            // 绝不能按登录页判死 —— 否则刚登录完的用户也被误杀
                            if (url != null && url.contains("authoriz")) {
                                autoAccept(view, 0);
                                return;
                            }
                            if (url != null && STUCK_HINT.matcher(url).find()) {
                                // 首次命中不立即判死：SSO/passport 域名是授权链路的正常一环，
                                // 可能只是跳转中转。等 5 秒复查，仍在同一登录页且没拿到回调才算失效。
                                final WebView fv = view;
                                final String furl = url;
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (box[0] != null || holder[0] == null) return;
                                    String now = fv.getUrl();
                                    if (now != null && STUCK_HINT.matcher(now).find()
                                            && now.equals(furl)) {
                                        stuckOnLogin = true;
                                        done.countDown();
                                    }
                                }, 5000L);
                                return;
                            }                            autoAccept(view, 0);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest req,
                                                WebResourceError err) {
                        if (req == null || !req.isForMainFrame()) return;
                        android.net.Uri u = req.getUrl();
                        if (u != null && u.toString().startsWith(callbackPrefix)) return;
                        lastError = err != null && err.getDescription() != null
                                ? err.getDescription().toString() : "unknown";
                    }
                });
                wv.loadUrl(authUrl);
            } catch (Throwable e) {
                box[0] = Result.fail("云端刷新异常：" + e.getClass().getSimpleName()
                        + " " + (e.getMessage() == null ? "" : e.getMessage()));
                done.countDown();
            }
        });

        boolean finished;
        try {
            finished = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (box[0] == null) {
            if (stuckOnLogin) {
                box[0] = Result.fail("网页登录态已失效（停在登录页"
                        + (lastTitle.isEmpty() ? "" : "「" + lastTitle + "」")
                        + (lastUrl.isEmpty() ? "" : "，页面：" + shortUrl(lastUrl))
                        + "），请点「登录」重新完成一次 TRAE 网页登录");
            } else {
                StringBuilder sb = new StringBuilder("云端刷新超时（" + (TIMEOUT_MS / 1000) + " 秒没拿到回调）");
                if (!lastUrl.isEmpty()) sb.append("；最后停留：").append(shortUrl(lastUrl));
                if (!lastTitle.isEmpty()) sb.append("（").append(lastTitle).append("）");
                if (!lastError.isEmpty()) sb.append("；页面加载错误：").append(lastError);
                sb.append("。若页面一直未授权，请点「登录」重新走一次");
                box[0] = Result.fail(sb.toString());
            }
        }
        final Result captured = box[0];
        // 回调到手后立刻让页面停止跳转，避免网页把「登录失败」提示覆盖到前台
        main.post(() -> {
            WebView wv = holder[0];
            if (wv != null) {
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.destroy();
                } catch (Exception ignored) {
                }
                holder[0] = null;
            }
        });
        return captured;
    }

    /**
     * 自动点「授权/同意」按钮。页面异步渲染，所以最多试 6 次、每次隔 1.2 秒。
     * 找不到按钮不算失败——已登录时页面通常自己就跳了。
     */
    private static void autoAccept(WebView wv, int attempt) {
        if (attempt >= 6) return;
        String js = "(function(){try{"
                + "var re=/(授权|同意|确认|继续|允许|Authorize|Allow|Agree|Confirm)/i;"
                + "var els=document.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit],div,span');"
                + "for(var i=0;i<els.length;i++){var e=els[i];"
                + " var t=(e.innerText||e.textContent||e.value||'').trim();"
                + " if(!t||t.length>14)continue;"
                + " if(!re.test(t))continue;"
                + " var r=e.getBoundingClientRect();"
                + " if(r.width<=0||r.height<=0)continue;"
                + " e.click();return 'clicked';}"
                + "return 'none';}catch(err){return 'err';}})()";
        try {
            wv.evaluateJavascript(js, value -> {
                if (value != null && value.contains("clicked")) return;
                new Handler(Looper.getMainLooper())
                        .postDelayed(() -> autoAccept(wv, attempt + 1), 1200);
            });
        } catch (Exception ignored) {
        }
    }
}
