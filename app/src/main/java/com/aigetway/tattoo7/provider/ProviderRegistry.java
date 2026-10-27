package com.aigetway.tattoo7.provider;

import com.aigetway.tattoo7.account.AccountSnapshot;
import com.aigetway.tattoo7.catalog.ModelCaps;
import com.aigetway.tattoo7.catalog.ModelEntry;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 全部服务商的注册表：按模型路由 + 按服务商分组。 */
public class ProviderRegistry {

    private final List<ServiceProvider> all = new ArrayList<>();

    public ProviderRegistry(AppBridge bridge) {
        all.add(bridge.buddy());
        all.add(bridge.raccoon());
        all.add(bridge.trae());
        all.add(new LoomyService(bridge));
        // AStudio（讯飞 AstronStudio）：双域协议（推理网关 + 账号控制面），
        // 登录走 App 内置浏览器页（短信 / 密码 / 扫码），见 AStudioProvider 文件头。
        all.add(bridge.astudio());
        // AutoClaw 国内版（自包含，见 AutoClawProvider 文件头）。
        // 上游失效时删除本行 + App 里的构造/bridge 方法即可整块下线。
        all.add(bridge.autoclaw());
    }

    public List<ServiceProvider> all() {
        return all;
    }

    public ServiceProvider byId(String id) {
        for (ServiceProvider p : all) if (p.id().equals(id)) return p;
        return null;
    }

    /** 已登录的服务商。 */
    public List<ServiceProvider> loggedIn() {
        List<ServiceProvider> l = new ArrayList<>();
        for (ServiceProvider p : all) if (p.loggedIn()) l.add(p);
        return l;
    }

    /** 按 model id 找归属服务商；找不到返回 null。 */
    public ServiceProvider byModel(String modelId, Map<String, List<ModelEntry>> catalog) {
        return byModel(modelId, catalog, null);
    }

    /** 按 model id 找归属服务商，并限制在 allowed 分组内（见 candidatesFor 的透传说明）。 */
    public ServiceProvider byModel(String modelId, Map<String, List<ModelEntry>> catalog,
                                   java.util.Collection<String> allowed) {
        if (modelId == null || modelId.isEmpty()) return null;
        List<ServiceProvider> candidates = candidatesFor(modelId, catalog, allowed);
        if (candidates.isEmpty()) return null;
        // ⚠️ 同名模型可能同时属于多个服务商（deepseek-v4.1-flash 在 Buddy 国内版与
        // WorkBuddy 国际版都有；/v3/config 这类全局源还可能混进无权限的条目）。
        // **命中第一个就返回**会把请求路由到账号无权限的那个，调用方直接吃 11102，
        // 表现为「探测说可用、实际调用报无权限」。
        // 这里按「已登录 + 探测通过」优先排序。
        ServiceProvider fallback = null;
        List<ServiceProvider> unknown = new ArrayList<>();
        for (ServiceProvider p : candidates) {
            if (!p.loggedIn()) {
                if (fallback == null) fallback = p;
                continue;
            }
            ModelProber.Result pr = ModelProber.peek(p.id(), modelId);
            if (pr != null && pr.status == ModelProber.OK) return p;
            if (pr != null && pr.status == ModelProber.UNAUTHORIZED) {
                // 运行时已确认该账号无此模型权限，跳过（Router 失败时还会自动换）
                continue;
            }
            unknown.add(p);
        }
        if (unknown.isEmpty()) return fallback;
        // 都没探过时，优先选**可用账号数>1** 的服务商：
        // 多账号通常意味着账号质量更好（能过探测、能轮转），
        // 比 catalog 的注册顺序更有信息量。
        for (ServiceProvider p : unknown) {
            if (availableAccounts(p.id()) > 1) return p;
        }
        return unknown.get(0);
    }

    private static int availableAccounts(String providerId) {
        try {
            int n = 0;
            for (com.aigetway.tattoo7.account.AccountPool.Account a :
                    com.aigetway.tattoo7.account.AccountPoolHolder.get(providerId).all()) {
                if (a.available()) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 列出持有该模型的服务商（目录优先，其次本地兜底表）。 */
    public List<ServiceProvider> candidatesFor(String modelId, Map<String, List<ModelEntry>> catalog) {
        return candidatesFor(modelId, catalog, null);
    }

    /**
     * 列出持有该模型的服务商，**并严格限制在 allowed 分组内**。
     *
     * <p>⚠️ 透传根因修复：以前这里两层扫描（目录 + 兜底表）都不看 key 分组，
     * 兜底表那一层更是遍历全部服务商。当一个 key 被限制到 [loomy] 时，
     * 只要目录里拿不到 loomy 的模型列表（fetch 失败 / 尚未刷新），
     * out 为空 → 兜底遍历 Buddy 等所有服务商 → 同名模型（glm-5.3-flash）
     * 被路由到 Buddy 分组，「Loomy 透传成 Buddy」。
     * allowed=null 表示不限制（老行为）。
     */
    public List<ServiceProvider> candidatesFor(String modelId,
                                               Map<String, List<ModelEntry>> catalog,
                                               java.util.Collection<String> allowed) {
        List<ServiceProvider> out = new ArrayList<>();
        String want = modelId == null ? "" : modelId.trim();
        String lower = want.toLowerCase(java.util.Locale.US);
        if (catalog != null) {
            // 先精确匹配
            for (List<ModelEntry> l : catalog.values()) {
                for (ModelEntry e : l) {
                    if (want.equals(e.id)) {
                        ServiceProvider p = byId(e.providerId);
                        if (p != null && !out.contains(p)) out.add(p);
                    }
                }
            }
            // ⚠️ 再做**大小写不敏感**匹配：客户端（minis 等）可能发小写
            // （gemini-3.5-flash）而目录里是首字母大写（Gemini-3.5-Flash），
            // 严格相等会直接 404 model not found。
            if (out.isEmpty()) {
                for (List<ModelEntry> l : catalog.values()) {
                    for (ModelEntry e : l) {
                        if (e.id != null && e.id.equalsIgnoreCase(want)) {
                            ServiceProvider p = byId(e.providerId);
                            if (p != null && !out.contains(p)) out.add(p);
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            // ⚠️ 兜底表扫描也必须**只在 key 允许的分组内**进行，
            // 否则同名模型会把请求透传到别的分组（Loomy → Buddy）。
            // allowed=null ⇒ 不限分组（旧版单 key）；allowed=[] ⇒ 一个分组都没授权（拒绝）。
            List<ServiceProvider> pool = new ArrayList<>();
            for (ServiceProvider p : all) {
                if (allowed == null || allowed.contains(p.id())) {
                    pool.add(p);
                }
            }
            for (ServiceProvider p : pool) {
                List<ModelEntry> fm;
                try {
                    fm = p.fallbackModels();
                } catch (Throwable t) {
                    continue;
                }
                if (fm == null) continue;
                for (ModelEntry e : fm) {
                    if (e == null) continue;
                    if (want.equals(e.id)
                            || (e.id != null && e.id.equalsIgnoreCase(want))) {
                        if (!out.contains(p)) out.add(p);
                        break;
                    }
                }
            }
        }
        if (allowed != null) {
            // 目录命中也要复核一次：catalog 可能是未按分组过滤的调用方传进来的。
            for (int i = out.size() - 1; i >= 0; i--) {
                if (!allowed.contains(out.get(i).id())) out.remove(i);
            }
        }
        return out;
    }

    /** 目录里与 modelId 最相近的几个模型名（用于 404 时给出提示）。 */
    public java.util.List<String> similarModels(String modelId,
                                                Map<String, List<ModelEntry>> catalog) {
        java.util.List<String> out = new ArrayList<>();
        if (catalog == null || modelId == null) return out;
        String want = modelId.toLowerCase(java.util.Locale.US);
        for (List<ModelEntry> l : catalog.values()) {
            for (ModelEntry e : l) {
                if (e.id == null) continue;
                String a = e.id.toLowerCase(java.util.Locale.US);
                if (a.equals(want) || a.contains(want) || want.contains(a)
                        || Math.abs(a.length() - want.length()) <= 3 && shares(a, want)) {
                    if (!out.contains(e.id)) out.add(e.id);
                }
                if (out.size() >= 5) return out;
            }
        }
        return out;
    }

    private static boolean shares(String a, String b) {
        int n = Math.min(a.length(), b.length());
        if (n == 0) return false;
        int same = 0;
        for (int i = 0; i < n; i++) if (a.charAt(i) == b.charAt(i)) same++;
        return same * 3 >= n * 2;
    }

    /** 构造 App 桥接（避免 core.App 与 provider 循环依赖）。 */
    public interface AppBridge {

        BuddyProvider buddy();

        RaccoonProvider raccoon();

        TraeProvider trae();

        LoomyProvider loomy();

        AStudioProvider astudio();

        AutoClawProvider autoclaw();

    }

    // ---------------- 适配器：把旧的 Provider 接口包成 ServiceProvider ----------------

    static class TraeService implements ServiceProvider {
        private final AppBridge b;

        TraeService(AppBridge b) {
            this.b = b;
        }

        public String id() {
            return "trae";
        }

        public String displayName() {
            return b.trae().displayName();
        }

        public boolean loggedIn() {
            return b.trae().loggedIn();
        }

        public List<ModelEntry> fetchModels() {
            try {
                List<ModelEntry> l = b.trae().fetchModels();
                for (ModelEntry e : l) ModelCaps.enrich(e);
                return l;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        public PreparedChat prepareChat(JSONObject body) throws IOException {
            return b.trae().prepareChat(body);
        }

        public List<ModelEntry> fallbackModels() {
            return b.trae().fallbackModels();
        }

        public AccountSnapshot account() {
            try {
                return b.trae().account();
            } catch (Exception e) {
                return AccountSnapshot.anon(id(), displayName());
            }
        }

        public boolean supportsCheckin() {
            return true;
        }

        public String checkin() throws IOException {
            return b.trae().checkin();
        }

        public boolean refreshFingerprint() throws IOException {
            return b.trae().refreshFingerprint();
        }

        @Override
        public boolean refreshFingerprintForUid(String uidOrId) {
            return b.trae().refreshFingerprintForUid(uidOrId);
        }

        @Override
        public String lastRefreshError() {
            return b.trae().lastRefreshError();
        }
    }

    static class LoomyService implements ServiceProvider {
        private final AppBridge b;

        LoomyService(AppBridge b) {
            this.b = b;
        }

        public String id() {
            return "loomy";
        }

        public String displayName() {
            return b.loomy().displayName();
        }

        public boolean loggedIn() {
            return b.loomy().loggedIn();
        }

        public List<ModelEntry> fetchModels() {
            try {
                List<ModelEntry> l = b.loomy().fetchModels();
                for (ModelEntry e : l) ModelCaps.enrich(e);
                return l;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        public PreparedChat prepareChat(JSONObject body) throws IOException {
            return b.loomy().prepareChat(body);
        }

        public List<ModelEntry> fallbackModels() {
            return b.loomy().fallbackModels();
        }

        public AccountSnapshot account() {
            try {
                return b.loomy().account();
            } catch (Exception e) {
                return AccountSnapshot.anon(id(), displayName());
            }
        }

        public boolean supportsCheckin() {
            return true;
        }

        /**
         * ⚠️ 账号页拿到的 provider 是 ProviderRegistry.LoomyService，不是 LoomyProvider。
         * 能力声明与调用都必须在本类暴露，否则「新手任务」按钮不会出现
         * （vc164 的坑：能力加在 LoomyProvider 上，界面走 default=false）。
         */
        public boolean supportsOnboardingTasks() {
            return true;
        }

        public String claimOnboarding() throws IOException {
            return b.loomy().claimOnboarding();
        }

        public String checkin() throws IOException {
            return b.loomy().checkin();
        }

        public boolean refreshFingerprint() throws IOException {
            return b.loomy().refreshFingerprint();
        }
    }


}
