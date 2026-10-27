package com.aigetway.tattoo7.provider;

import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelEntry;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/** 统一服务商接口：网关按 model 路由到这里。 */
public interface ServiceProvider {

    String id();

    String displayName();

    boolean loggedIn();

    /** 拉取模型目录（带能力数据）。 */
    List<ModelEntry> fetchModels() throws IOException;

    /** 建连并读状态码，成功返回待透传的 SSE 流。 */
    PreparedChat prepareChat(JSONObject body) throws IOException;

    /** 本地兜底模型表。 */
    List<ModelEntry> fallbackModels();

    /** 账户快照（积分/登录态/签到）。 */
    AccountSnapshot account();

    default boolean supportsCheckin() {
        return false;
    }

    default boolean supportsPermanentLock() {
        return false;
    }

    /**
     * 是否支持「新手任务」：一次性奖励（每号只能领一次），与每日签到语义独立。
     * UI 据此在账号卡片上多渲染一个独立按钮。
     */
    default boolean supportsOnboardingTasks() {
        return false;
    }

    /**
     * 领取新手任务（一次领完所有未完成任务），返回人类可读结果。
     * 只作用于<b>当前账号</b>；多账号服务商由 UI 层逐个切换账号后调用。
     */
    default String claimOnboarding() throws IOException {
        return "该服务商不支持新手任务";
    }

    /** 一键签到，返回人类可读结果。 */
    default String checkin() throws IOException {
        return "该服务商不支持签到";
    }

    /** 刷新登录指纹（重新拉 token）。 */
    default boolean refreshFingerprint() throws IOException {
        return false;
    }

    /**
     * 刷新**指定账号**（accountId 或上游 uid）。默认忽略 uid 只刷当前账号，
     * 支持多账号的服务商（TRAE / 小浣熊）自己覆盖。
     */
    default boolean refreshFingerprintForUid(String uidOrId) throws IOException {
        return refreshFingerprint();
    }

    /**
     * 最近一次刷新的失败原因（供 UI 展示）。
     * 没有它，「刷新凭证」失败时只能显示一句「无法刷新该账号的凭证」，
     * 用户和服务端都拿不到真实原因。
     */
    default String lastRefreshError() {
        return "";
    }

    /**
     * 「登录成功但账号列表是 0 个」时的自愈入口：
     * 把上次成功落库的凭据重新写回账号池。
     * <p>正常路径下登录成功就会立刻 {@code addOrUpdate} 入池；一旦落库线程被中断、
     * 或池子被其它路径清空，账号会在下次进账号页时自己回来。
     *
     * @return true 表示确实补写了一个账号（调用方应当重新读取账号列表）
     */
    default boolean recoverPersistedAccount() {
        return false;
    }
}
