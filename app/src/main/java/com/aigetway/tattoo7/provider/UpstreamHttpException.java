package com.aigetway.tattoo7.provider;

import java.io.IOException;

/** 上游返回非 200 时抛出，携带真实 HTTP 状态码与可读消息，供网关原样返回给客户端。 */
public class UpstreamHttpException extends IOException {
    public final int code;
    public final String friendly;

    /**
     * 上游业务码（如 101/103/105/112/10605）。
     * 没有则空串 —— provider 据此区分"重复请求可重发"与"真的鉴权失败"。
     */
    public String bizCode = "";

    /** 上游要求的排队等待毫秒数（0 = 没给）。 */
    public long retryAfterMs = 0L;

    /**
     * 是否「额度/积分/余额耗尽」类错误。
     *
     * <p>这是「账号额度用完不切号」的判定依据：小浣熊/Raccoon 在积分用完后对所有模型
     * 返回 <b>HTTP 400 + error.code=insufficient_points</b>（部分服务商直接用 402），
     * 而网关原本只在 401/403 时才轮转账号 —— 于是额度耗尽的 400 被原样回给客户端，
     * 其它还有额度的账号永远轮不到。标记后 Router 会切到下一个账号重试。
     */
    public boolean quotaExhausted;

    public UpstreamHttpException(int code, String friendly) {
        super("upstream HTTP " + code + (friendly == null || friendly.isEmpty() ? "" : ": " + friendly));
        this.code = code;
        this.friendly = friendly == null ? "" : friendly;
        this.quotaExhausted = looksLikeQuota(code, this.friendly);
    }

    /** HTTP 402 固定判为额度/权益不足；400 等需在报文里识别特征字。 */
    private static boolean looksLikeQuota(int code, String friendly) {
        if (code == 402) return true;
        if (friendly == null || friendly.isEmpty()) return false;
        String s = friendly.toLowerCase();
        return s.contains("insufficient_points") || s.contains("insufficient_point")
                || s.contains("insufficient balance") || s.contains("insufficient_balance")
                || s.contains("quota_exceeded") || s.contains("quota exceeded")
                || s.contains("billing_quota") || s.contains("point balance")
                || s.contains("积分余额不足") || s.contains("积分不足") || s.contains("积分已用完")
                || s.contains("余额不足") || s.contains("余额已用完");
    }
}