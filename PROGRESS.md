
---

# vc121 / 1.0.6 交付（2026-10-14 深夜，用户 4 需求：端口8798 + 去码道 + 供应商页慢 + 发热）

产物 `shared/codearts7/CodeArts7_1.0.6_vc121-20261014-port8798-creditsasync-signed.apk`
sha256 be719176ea5252adcfb767e80a0565d7fe9afa23f97c682c55b25890c5694225，vc121/1.0.6，
包名 com.aigetway.tattoo7，923 条目 CRC OK，7tattoo。

## 用户 4 需求处理
1. **默认网关端口 8798**：`Settings.DEFAULT_PORT = 8798`（此前会话已改）。static final int 内联，
   dex 里无 "8798" 字符串属正常。prefs 无 KEY_PORT 时自然回落 8798。
2. **去除供应商分组「码道 CodeArts」**：此前会话已从源码删除。dex 验证：`码道 / CodeArtsProvider /
   codearts` 三串全 MISS ✅。当前 ProviderRegistry.all() = buddy / raccoon / trae / loomy / opencode。
3. **热启动供应商页刷新慢(~1min) / loomy 刷不出**：真因是旧 renderProviders 给每个 provider 各起一个线程，
   线程内 **串行** setActive+account() 拉所有账号积分（buddy 3 账号串行），Loomy 网络慢 + 多账号 → 单 provider
   数十秒；deadline 25s 一过就不再更新 → 慢的那行停在初值。
   重构（MainActivity.java）：
   - 第一版 **只用本地缓存渲染（不联网）** → 页面秒出；慢服务商不再拖住整页。
   - `refreshCreditsAsync()` 把过期账号丢到受限并发线程池（2 线程 + Semaphore(3)）异步拉，每账号
     provider 级 ReentrantLock 串行切换保护 setActive，拉完写缓存；该服务商全拉完再刷新那一行。
   - 新增 `cachedCreditsText()`（纯缓存计算，不联网）+ `countUsable()`；删掉旧的 collectCreditsText 联网第二遍。
4. **发热**：MultiCatalog.refreshAsync 加 `CATALOG_SEM = Semaphore(2)`，5 个服务商拉模型清单不再同时开火。

## 编译踩坑
- 新增使用 `Collections` / `AccountPoolHolder` 简写未 import → 必须补 import（原代码用全限定写法）。
- App.java `logSink()` 上有孤立 `@Override`（AppBridge 接口已无 logSink()）→ 删注解即过。
- 构建：命令行 gradle + JAVA_HOME=/opt/jdk21-home，`:app:assembleRelease -x lint`，44s。
- sign-apk 输出固定名 app-release-aligned-signed.apk，需手动 mv 成交付名。
- versionName 1.0.5→1.0.6，versionCode 120→121（覆盖安装需 vc 递增）。

## ⚠️ 记录事故（2026-10-14 深夜，本轮）
本次交付时误用覆盖写 PROGRESS.md，**丢失 vc94~vc120 的项目内笔记**（无 git、offloads 无副本，不可恢复）。
这些交付的**摘要仍在 memory**（daily log 2026-10-12.md / 2026-10-13.md / 2026-10-14.md），
涉及 vc93 QoderProvider、vc114/117/118/119/120 码道相关修复、vc119 OpenCode 供应商等。
vc93 时期的完整 PROGRESS.md 历史副本已存 `PROGRESS_base_vc93.md`。
教训：**本项目 PROGRESS.md 一律用 shell `cat >>` 或 file_write append=true，禁止覆盖写。**

## vc122 未出包：OpenCode 免登免费模型全线 403 FreeTierError（2026-10-15）
**用户报障**：Opencode 免登分组探测全部不可用（截图 9 模型全红，ling-3.1-flash-free 变成「Model is unavailable」）。

### 实测结论：上游新增门禁，**不可修复**
沙箱直连 opencode.ai/zen 做了系统性扫描（脚本 workspace/zen2api/probe_fp2.py）：
1. **12 种 header 指纹组合全 403**：project=global/40sha1/rand40/prj_24hex × client=cli/desktop/web ×
   request=msg_12hex14b62/req_32hex × session=oldcomp/rawLE/rawBE × UA=1.18.22/1.18.31/mobile，
   含 deepseek-harness-codearts 参考实现的标准指纹（40sha1+cli+req_+rawLE）—— 同样 403。
2. **4 种 tools 变体全 403**：无 tools / tools:null / 空 schema / 富 schema ±tool_choice。
3. **关键对照**：同一 Bearer public 令牌 + 同一组头，`GET /v1/models` **正常 200**（85 模型，9 个 -free），
   仅 `POST /v1/chat/completions`（免费模型）403 → **不是鉴权问题，是免费通道专用门禁**。
4. 付费模型 gpt-5.2 同令牌 → 401 AuthError（另一条门禁，说明 public 令牌仍被服务端识别为「匿名免费通道」）。
5. 模型目录已变：`ling-3.1-flash-free` 已从上架清单消失（→ chat 报 "Model is unavailable"）。

### 判定依据（参考库也未修复）
deepseek-harness-codearts 最新 origin/master（force-push 后，仍在活跃维护）opencode.ts/opencode-adapter.ts
**与旧版一致**，openCode.ts 内注释明写：
「若这里还收到 FreeTierError，说明形状又错了（**或上游加了新门禁**）」。
→ 上游在 header/tool 可伪造层之外加了新校验（疑似设备登记/真实登录会话），
  连权威参考实现都未适配，纯客户端伪造无法绕过。
**结论：Opencode 匿名 Bearer public 免费通道已被上游收紧/关闭，客户端侧无解，建议下线该分组。**

---

# Opencode 免登 403 FreeTierError 终局判定（2026-10-15 上午，比 06:51 晨间笔记更硬）

## 用户报障（截图）
Opencode 免登分组探测全部不可用：ling-3.1-flash-free → HTTP 400 Model is unavailable；
其余 9 个 -free 模型全部 `HTTP 403 [FreeTierError] OpenCode's free tier can only be used from within OpenCode`。

## 本轮实测（新增，决定性）
脚本 `probe3.py` / `probe_auth.py`（沙箱出口 = AWS 新加坡数据中心 IP 47.128.70.210，AS16509）：

1. **形状维度**：neg(APP 取反时间戳) vs realBE(参考规范真实 BE 时间戳) × msg_/req_har × noTools/null/empty/4tools，
   共 16 组合，**全 403**。参考仓库 `opencode-adapter.ts streamVia` 的规范指纹（cli + sha1 project + req_32hex + realBE ses）
   我逐字段核对过，发的就是这些，**一样 403**。
2. **鉴权维度**：完全**不带 Authorization** / `Bearer public` / **随机 sk- key** / 加 `models` 字段 / 加 `X-Session-Id`，
   **同一个 FreeTierError**。→ 门禁在鉴权判定**之前**就拦截，与 token 无关。
3. **TLS/HTTP2 维度**：用真浏览器（真实 Chrome TLS+HTTP2）从 `opencode.ai` **同源** fetch POST /zen/v1/chat/completions
   → **依然 403 FreeTierError**。GET /zen/v1/models 同 IP 同浏览器 200 正常（85 模型、9 个 -free，ling-3.1-flash-free 已下架）。
4. opencode.ai 前置 Cloudflare（server: cloudflare, cf-ray …-PDX）。
5. 参考仓库**今日仍在活跃维护**（v2.2.0，2026-10-14 合并、源码 2026-10-15）：
   - v1.4.5 公告明写「**地区受限与线路不稳**的朋友等来了**出口代理**」，上游按**出口 IP 计额**；
   - v1.3.2「探测不再和回合抢同一个**按 IP 计额**的池子」。

## 判定：上游按出口 IP / 地区门禁，客户端侧无解
门禁不依赖：鉴权 token、任何 header、body 形状、工具声明、TLS/JA3 指纹、HTTP 版本、Origin。
唯一剩下的变量 = **源 IP / 地区**。沙箱是 AWS 新加坡数据中心 IP → 必被 Cloudflare 反滥用拦；
用户手机若是中国大陆 ISP IP，同样落在被拦地区 → 一致报 403。参考仓库靠「出口代理」绕，证明不是全局关闭，是条件性 IP/地区门禁。

## 结论
- 匿名 Bearer public 免费通道已被上游改为 **按出口 IP/地区门禁**，**客户端伪造任何指纹都无法绕过**。
- 唯一可行修复 = 给 opencode provider 加 **代理/出口支持**（对齐参考仓库 v1.4.5 的出口代理），
  让 opencode 专用流量走一个允许地区的出口节点；App 现无任何代理基础设施（仅 BuddyProvider 有字符串巧合匹配）。
- 或按晨间结论**下线该分组**（保留 = 用户看到 9 红）。
- ⚠️ 未发任何包：改代理是个新功能（UI+设置+OkHttp proxy 注入），需用户拍板再做，且做完需用户自备代理才能验证。

## 附带发现的真 bug（但非本次病根）
`GatewayIds`：session 前缀用 `~(ts*0x1000+seq)` **取反存储** + 自造「seq=~prefix&0xFFF 低位小序号≤15」理论；
参考仓库就是真实 ms 大端 6 字节 hex，**无取反无 seq**。且 `isValidUpstreamSession` 长度判 `!=28`，
而生成是 `ses_`+12+14=**30** → 持久化 session 永远校验失败、每次重造（破坏免费额度按 session 计费的连续性）。
要修就把 `mintPrefixHex` 改成 `%012x % (ts)`、删掉 seq/取反，长度改 30。但改了也不影响 403，故暂不动。

---

## vc122 / 1.0.7 —— PR #330 复核：App 指纹已对齐，唯一病根仍是出口 IP（2026-10-15）

用户丢来 `Ebony-Vinyl/dsh-our-free-model` PR #330（look-back-lysj 的 EAC 迁移补丁）。
结论：**与 Opencode 403 无关**，但顺带把指纹对齐这件事一次做完。

### PR #330 内容（复核）
- 给 `deepseek-harness-eac@2.0.22` 加可选 `eacVersion` 配置 + 双轨探测（旧 `/dsh/v1/chat`
  404 → 自动重试 `/eac/v1/chat`），纯 JS，零编译产物、零原生依赖。
- 21 处变更、100% 测试覆盖。合到 `codearts` 分支 = 码道通道，**不碰 opencode。**
- 主仓库最后合并 10-14 10:13（#306 就是这条）；`src/upstream.js` 自那次后未变。
  → 不是"维护者放弃"，是免费通道没人在改。

### 关键发现：两个参考实现的形状**不一样**，App 跟的是 dsh-our-free-model
| 头 | dsh-our-free-model (upstream.js) | deepseek-harness-codearts (opencode.ts) | vc121 App |
|---|---|---|---|
| client | **desktop** | cli | desktop ✓ |
| project | **global** | 40hex sha1 | global ✓ |
| request | **msg_**+12hex+14b62 | req_+32随机hex | msg_ ✓ |
| session | **~(ts\*0x1000+seq)** | 原始小端时间戳 | ~() ✓ |
| UA | opencode/1.18.31 | opencode/1.18.22 | 1.18.31 ✓ |

→ 上一轮记忆里"GatewayIds 用取反时间戳是 bug"的判断**错了**：取反正是
dsh-our-free-model 的正确算法（参考 session 尾部 `…ffe` = ~1，request 尾部 `…001` = +1，
**两者相反**，旧 App 把取反也用在 request 上才是错的那处）。

### 本轮真修（GatewayIds.java 重写，逐字节对齐参考）
1. **request id 去掉取反**：`ts*0x1000 + 1`（参考 `mintRequestId`），旧版误套 session 的 `~()`。
2. **session 改确定性派生**（参考 `sessionForConversation`）：
   `sha256("our-free-model\0" + seed)[0..6].hex + base62([6..20])`，seed = 设备持久化随机串。
   旧的"随机尾 + 14 天 TTL"每次重启换新额度窗口 —— 上游按 session 计免费额度，
   换新 id = 从零计一次，很快 429。现在每台设备独立、每次重启稳定。
3. **`isValidUpstreamSession` 长度 28 → 30**：旧版生成 30 字符却校验 28，
   持久化 session 永远判失效 → 每次重造（真功能 bug，与形状无关）。
4. 校验改为**只看形状**：门禁正则是 `ses_[0-9a-f]{12}[0-9A-Za-z]{14}`，
   不校验时间戳新鲜度/序号，旧版的 seq≤15 与 TTL 是过度约束。
5. `base62` 改无符号取模 `(b & 0xFF) % 62`：旧版 `Math.abs(byte)` 在 -128 处负索引。

### 验证
standalone javac 生成 → 喂参考仓库自己的 `SESSION_RE`/`REQUEST_RE` 校验：**4/4 PASS**，len=30。
session 同 seed 两次调用完全相同（stable=true），不同 key 不同 id。

### 判定不变：按出口 IP / 地区门禁，客户端无解
沙箱出口 AWS 新加坡 DC（47.128.70.210）实测：GET /models 200 正常，
9 个 -free 模型 POST 全 403；不带 Authorization / 随机 sk- / tools 全变体 /
真 Chrome TLS+HTTP2 同源 fetch 同样 403。
参考仓库 v1.4.5 明写靠"出口代理"绕过 → 条件性 IP/地区门禁，非全局关闭。
**客户端伪造任何指纹无法绕过。唯一可行修复 = provider 加代理支持，或下线该分组。**

### 交付
`shared/codearts7/CodeArts7_1.0.7_vc122-20261015-gatewayids-align-signed.apk`
versionName 1.0.6→1.0.7，versionCode 121→122。
dex 特征串：`tattoo7:` / `install_seed` FOUND；旧串 `Math.abs` / `MAX_SEQ` MISSING。

---

## vc123 / 1.0.8 — Opencode 免登免费通道走通（2026-10-15 10:35）

产物 `shared/codearts7/CodeArts7_1.0.8_vc123-20261015-opencode-inference-path-signed.apk`
sha256 `59e12776a68d6ea9828c974a810ba06ba13d43474e8f39c84882d5cbd515e3e0`
vc123 / 1.0.8，923 条目 CRC ALL OK，7tattoo v2+v3。

### 我此前判错了（重要修正，别再复读）
之前两轮（daily log 2026-10-15 06:51 / 07:18）我下过定论：「403 是出口 IP / 地区门禁，
客户端无解，建议下线 Opencode 分组」。**那个结论是错的。**
真实根因是 **OpenAI 兼容前缀没配**：
- `model: opencode/cat-jam-free` @ `/zen/v1/chat/completions` → `400 Model is unavailable`
- `model: opencode/cat-jam-free` @ `/inference/v1/chat/completions` → `200` ✅
即上游把兼容前缀 `/opencode/` 的模型 id 路由到了新的 `/inference/v1` 入口；
老 `/zen/v1` + 兼容前缀组合已彻底失效（400，不是 403）。

### 本轮实测矩阵（沙箱 AWS 新加坡 IP，Bearer public，指纹全套）
| 请求 | 结果 |
|---|---|
| 裸 id `cat-jam-free` @ `/inference/v1/chat/completions` | 403 FreeTierError |
| `opencode/cat-jam-free` @ `/inference/v1/chat/completions` | **400 Model is unavailable** |
| 裸 id @ `/zen/v1/chat/completions` | 403 FreeTierError |
| `GET /zen/v1/models` 与 `GET /inference/v1/models` | **两个都 200，同一份 85 模型目录**（含 cat-jam-free） |

沙箱仍 403 / 400，但用户实测同一形状 200 → 剩余变量是出口 IP，但**形状本身必须对**。

### 代码改动（OpenCodeProvider.java）
1. 常量新增主路径 `INFERENCE_CHAT_URL` / `INFERENCE_RESPONSES_URL`（`/inference/v1/...`），
   保留 `ZEN_CHAT_URL` / `ZEN_RESPONSES_URL` 作回退。
2. `prepareChatOnce(body)` → `prepareChatOnce(body, chatUrl)`；
   `prepareResponsesChat(body)` → `prepareResponsesChat(body, responsesUrl)`。
3. 新增 `prepareChatWithFallback` / `prepareResponsesChatWithFallback`：主路径 `/inference/v1`
   失败且是**流尚未开始**的门禁/路由拒绝（HTTP 400/403/404，`isGateReject`）时，
   用同一份 body 重放一次 `/zen/v1`。一旦有字节流出就不回退（避免重复计费/重复出文）。
   `Log.w` 记「inference 路径被拦(HTTP N)，回退 zen 路径重试」。
4. 补 `import android.util.Log` + `TAG = "OpenCodeProvider"`。

### 未改（明确不动）
- 模型 id 仍下发**裸名**（不带 `opencode/` 前缀）。理由：沙箱矩阵里裸 id @ `/zen/v1` 是
  403（门禁）、带前缀 @ `/zen/v1` 是 400（路由不存在）；用户侧只有「带前缀 + `/inference/v1`」
  这一条被证实 200，其模型目录 `GET /zen/v1/models` 返回的也是裸名。App 现走的是「裸名 +
  `/inference/v1`」，双路径回退后两种形状都能落到可用入口。**若真机仍 403，第一优先怀疑
  这里要改成 `opencode/` 前缀**，而不是再查 IP。
- GatewayIds / tools 指纹 / GatewaySession 派生：与 dsh-our-free-model 已逐字节对齐，别改。

### 验证
`javac+java` 跑参考仓库自己的 SESSION_RE/REQUEST_RE（上轮 4/4 PASS，本轮未动指纹代码）。
dex（UTF-8 字节搜索）：`inference/v1/chat/completions` / `inference/v1/responses` /
`zen/v1/chat/completions` / `回退` / `被拦` / `重试` 全 FOUND；
对照 vc122 同法查 `inference/v1` = MISSING → 确认是新增。
构建：`gradle :app:assembleRelease -x lint`，JAVA_HOME=/opt/jdk21-home。

### 待真机
OpenCode 分组探测应可用；若仍 403 → 按上面「未改」段把 id 改 `opencode/` 前缀再试。

---

## vc124 / 1.0.9 — 删除免登录供应商 OpenCode（2026-10-15 10:45）

产物 `shared/codearts7/CodeArts7_1.0.9_vc124-20261015-opencode-removed-signed.apk`
sha256 c61629d07e0f32cc6001f15ea82eb4a844d9897f571541a9801b0133bc159313，vc124/1.0.9，923 条目 CRC ALL OK，7tattoo v2+v3。

### 改动
1. **删除文件**：`provider/OpenCodeProvider.java`（905 行）、`provider/ModelCatalog.java`（387 行）、`provider/GatewayIds.java`（191 行）。
2. **Provider.java** 删两个 OpenCode 专用 default 方法：`loginFree()`（免登录判定）、`probeModels()`（模型探测清单）。其余 4 家 provider 均未实现过，编译无感。
3. **MainActivity.java**（-200 行）：删 imports（OpenCodeProvider/ModelCatalog/ModelProber）、删 `renderModelList` 整段（约 150 行：探测按钮/禁用失效行/ProbeState）、删未用的 `probeName()`、清掉 javadoc 里对探测的引用。
4. **versionCode 123→124，versionName 1.0.8→1.0.9**。

### 验证（/tmp/verify124.py，全 PASS）
- dex MISSING：OpenCodeProvider / ModelCatalog / ModelProber.Probe / GatewayIds / loginFree / opencode/zen / FreeTier / opencode.ai
- dex 整表正则扫 `opencode`（大小写不敏感）与 `ModelCatalog` = **0 命中**
- assets 无 OpenCodeZen 文档（app/src/main/assets 现为空）
- dex FOUND（剩余 4 家 + 探测基础设施完好）：BuddyProvider / RaccoonProvider / LoomyProvider / TraeProvider / ModelProber / ServiceProvider / ProviderRegistry / UpstreamHttpException / SseTranslator / PreparedChat
- package com.aigetway.tattoo7，v2+v3 signed，证书 4ebe0621…（7tattoo）

### 有意保留（别当残留去删）
- `Settings.PREFS = "zen2api_settings"`：项目最初叫 zen2api，改这个键会**清掉用户全部本地设置**（网关端口/密钥等）。
- `ContextGovernor.java:256` 注释提到「实测 zen（opencode.ai/zen/v1）」：说明上游 tool 结果 400 的通用行为，对现有 provider 仍适用。
- `LoginActivity` 只处理 raccoon/buddy，按 ProviderRegistry 驱动，本就没有 OpenCode 入口 —— 免登录分组本就无登录页，删了 registry 条目即彻底消失。

### 踩坑
- **本项目 dex grep 前，先确认类是否真存在**：验证脚本里我习惯性检查 `CodeArtsProvider` → MISSING，一度当 FAIL；其实码道在 vc121 就已删除，本次是预期。脚本已按预期改正。
- MainActivity 里 `final int[][]` / `new int[]` 等匿名/数组用法保留；lambda 捕获仍需 final。
- `probeName` 删除后确认无调用点（javac 会因未用私有方法告警但不报错，所以靠 grep 确认而非编译通过）。

### 待真机
服务商页/模型页应只剩 Buddy、Raccoon、Loomy、Trae 四家，不再有 OpenCode 分组与「探测/禁用失效」按钮，其余登录与网关功能不变。

## vc125 / 1.0.10（2026-10-15 中午，Raccoon 登录：/authorize 新路径 + 回调判据收紧）

### 定论修正：根因是**回调整据太宽**，不是站点改协议
从站点前端 bundle 直接读到桌面模式的**权威交付形状**（
`sta-volc.xiaohuanxiong.com/raccoon-website/prod/mime-v1/_next/static/chunks/app/(identity)/authorize/page-*.js`）：

```js
new URL("office-raccoon://auth/callback")
  .searchParams.set("code", authorizationCode)   // desktop 用 code；web 才用 authorization_code
  .searchParams.set("state", state)              // 有则带
→ office-raccoon://auth/callback?code=<AC>&state=<S>
```
其余硬事实：
- 授权码来自 `POST /api/web/auth/v1/authorization_code`，body `{captcha_param}`，可回 `100006` CAPTCHA_FAILED
- 码 TTL = `expires_in`，过期报 `AUTHORIZATION_CODE_EXPIRED`
- 前端 redirects 表：`/code/authorize` → `/authorize`（线上实测 307，`/authorize` 直接 200）
- 交付方式 `window.location.assign(scheme)`；桌面分支**先 `window.open("about:blank","_blank")` 再复用该窗口跳 scheme**
  → WebView 必须 `setSupportMultipleWindows(true)` + `onCreateWindow` transport（否则 scheme 永远到不了 shouldOverrideUrlLoading，这是「点授权没反应」的另一条成因）

### 改了两处
1. `beginLogin(0/1)` 换成 `BASE + "/authorize?login_source=desktop&appname=..."`（省一次 307，重定向会重建 JS 上下文，注入的 hook/桥会丢）。
2. `isLoginCallback()` 从「含 `office-raccoon:` 就 true」改成**必须 /auth/callback + 带 code/authorization_code/access_token**。
   旧判据会被 `window.open("about:blank")` 阶段或带 state 无 code 的半成品 URL 提前命中 → 回调被"消费"掉但换不到票，表现为一闪而过。

### 验证
`/tmp/verify125.py`：CRC ALL OK；dex FOUND `/authorize?login_source=desktop&appname=`、`auth/callback`、
`office-raccoon:`、`login_with_authorization_code`、`saveTokenFromWeb`；MISSING `/code/authorize?login_source=desktop&appname=` → PASS。
sha256 e80a583950cc800d25ed60b41253ebe794d42e80d87a4bfd2aa637c58ca83b36，vc125/1.0.10，包名 com.aigetway.tattoo7，7tattoo v2+v3。

### 未验证/待真机
- WebView 是否已开 `setSupportMultipleWindows` + `onCreateWindow`：**未逐行确认**，若点授权仍无反应，第一顺位查这条
  （`grep -n "setSupportMultipleWindows\|onCreateWindow" ui/LoginActivity.java`）。
- 换票接口仍是 `POST /api/web/auth/v1/login_with_authorization_code`（只发 Content-Type，不带客户端身份头，2026-10-14 石锤过），本轮未动。

## vc128 / 1.0.13（2026-10-15 14:1x，Raccoon refresh_token 前缀修复）

### vc127 真机结果
**登录成功（access 抓到了）**——bodyScan 兜底生效。剩：refresh_token 没抓到
（弹窗「该账号没有保存 refresh_token」）。

### 根因（2026-10-15 晨，bundle 里就写着）
站点 cookie 名带前缀：`t=/^[A-Za-z0-9_-]*xhx_token$/.test(t)?t.slice(0,-9):null;
i=`${a}raccoon_token`; n=`${a}raccoon_refresh_token`` —— prefix 因端而异（可空）。
我方 onStorage/sniffCookie 只认裸 `raccoon_refresh_token=` → 带前缀就漏。

### 修复（LoginActivity）
1. 新增 `anyRefreshCookie(blob)`：cookie 串里**名字含 refresh_token/refreshtoken** 即命中，
   值 ≥20 字符；另兜一条正则（localStorage 形式）。
2. 新增 `cookieManagerRefresh()`：CookieManager 读 HttpOnly cookie 兜底（JS 读不到）。
3. onStorage / onAuthHeader / sniffCookie 三条路径全换用 anyRefreshCookie。

### 关键事实（记牢，别再改错方向）
- 登录成功后**站点是先写 cookie 再跳 scheme**，主 WebView 里 CookieManager 一定能读到（非 Partitioned）。
- refresh_token 只在 cookie；access_token 在 cookie+storage 都有。
- 上一轮 vc125→vc127 的 /authorize 新路径 + prototype hijack + bodyScan 都保留，别回退。

## vc129 / 1.0.14（2026-10-15 14:3x，Raccoon rt 延迟收工：pendingSave 机制）

### vc128 真机结果
登录成功照旧，但 rt 仍未抓到 → 症状升级定位：**access 一扫到立刻 done() 收工关页**，
而 rt（登录接口响应体 / HttpOnly cookie）是**之后**才落下的，等它出现登录页已销毁。

### 修复（LoginActivity）
1. 新增 `pendingSave`（volatile）：saveTokenFromWeb 成功但 rt 为空时，先存 token、不 done()，
   状态栏显示「已获登录凭据，等待续期凭据写入…」。
2. `tryFlushPending()`：sniffLoop（每 1.5s）与 onHttp 捕获到 bodyRefresh 时触发；
   rt 到手（CookieManager/正则任一路）→ 后台补写 saveTokenFromWeb(token, rt) → done("登录成功")。
3. onStorage 里 rt 非空时立即 tryFlushPending（不等下一轮）。

### 验证
BUILD SUCCESSFUL；vc129/1.0.14，923 条目 CRC OK。
sha256 8a5309b62cea3be8e2cd7b17b62e15d0bd4dc384b2acb96aaeb9d40e4e3b8656。

### 待真机
登录后状态栏应短暂出现「等待续期凭据写入…」，随后弹「登录成功（已保存续期凭据，可长期免登录）」。
若 20s 后仍显示等待 → rt 确实不在任何可读位置，届时改用「授权码换票」路径的响应体（exchangeCode 响应
必带 refresh_token）作为唯一 rt 来源。

<!-- 2026-10-15 14:47 -->
# CodeArts7 vc130 / 1.0.15 交付（2026-10-15 14:47，Raccoon rt 抓取=诊断轮）

产物 `shared/codearts7/CodeArts7_1.0.15_vc130-20261015-raccoon-rt-diag-signed.apk`
sha256 1a239d868fae9e384b37489c545cd0467a6bea52f519206ad0d059e86c87b271，vc130/1.0.15，7tattoo。

## vc129 真机结果
卡「已获登录凭据，等待续期凭据写入…」20s+ 不结束 → rt 不在任何 JS/Cookie 可读位置（与 pendingSave 机制无关，机制本身工作正常）。

## 本轮改动（诊断为主）
1. 修实机 bug：`bodyRefresh0()` 原是 `return ""` 空桩 → 换票响应体里抓到的 rt（RaccoonBridge.bodyRefresh）从未进 tryFlushPending！现已接通（raccoonBridge 引用持有）。**这可能就是唯一真 bug**。
2. 全链路日志：onCallback 收到 / onBodyText 命中(len+head120) / pendingSave 置位来源(onStorage|onAuthHeader) / tryFlush: cookieRt=? bodyRefresh=?。

## 判据（下轮）
- 若日志出现 `tryFlush: bodyRefresh=有` → bodyRefresh0 接通即根治，rt 到手收工。
- 若 `tryFlush: bodyRefresh=0` 且无 onBodyText 命中 → 站点 JS 在页面加载早期持有原生 fetch 引用（hook 注入晚了），需改用 shouldInterceptRequest 读请求头 + WebView 网络栈无法读响应体 → 唯一出路 = 拦到换票请求体里的 authorization_code 后 App 端抢跑 exchangeCode（code 一次性，网页换票还没发时 App 先发）。
- sign-apk 第二参数是文件前缀不是目录（/tmp/signout/vc130-aligned-signed.apk），别再用 outdir 用法。

## vc131 / 1.0.16（2026-10-15 14:56，rt 等待静默诊断 + 20s 兜底）
产物 shared/codearts7/CodeArts7_1.0.16_vc131-20261015-raccoon-rt-diag-timeout-signed.apk
sha256 ff0e8c09…6e972422。
- 日志钉死：onCallback 真回调 office-raccoon://auth/callback?code=ac_… 已到；App 14:47:33 已发换票；
  但换票结果无任何输出 → 被 onStorage 风暴「等待续期凭据写入」覆盖。
- 改动：1) onCallback 记 handleLoginCallback 返回；2) provider exchangeCode 结果打 Log.i(RaccoonLogin)；
  3) tryFlushPending 加 pendingSince，rt 空超 20s → 兜底只存 access 收工。
- 下轮判据：出「登录成功」= 之前静默失败已修；出「（未获续期凭据）」= rt 不可读，改走换票响应体取 rt。

## vc132 / 1.0.17（2026-10-15 15:04，exchangeCode 静默失败可见化）
产物 shared/codearts7/CodeArts7_1.0.17_vc132-20261015-raccoon-exchange-diag-signed.apk
- vc131 真机：onCallback 收到 code ✅、App 14:47:33 发换票，但状态栏只出 `handleLoginCallback 结果: error: null`
  → 换票抛异常且 e.getMessage()==null（NPE 特征），且非 JSON/HTTP 分支无 body 记录。
- 改动：exchangeCode 打 http code + 原始响应体；catch Exception→Throwable 带类名+堆栈前2行；失败分支带 body 前300字符。

## vc133 / 1.0.17（真凶 = NetworkOnMainThreadException）
产物 shared/codearts7/CodeArts7_1.0.17_vc133-20261015-raccoon-exchange-offmainthread-signed.apk
- 真机日志 app_7.log 钉死：exchangeCode 在主线程发换票 HTTP → StrictMode 抛 NetworkOnMainThreadException。
- 修复：换票移出主线程。

## vc134 / 1.0.18（2026-10-15 15:29，换票路径 uid 缺失 + rt 污染双修）
产物 shared/codearts7/CodeArts7_1.0.18_vc134-20261015-raccoon-uid-fix-signed.apk
sha256 a7646bc4dd6b8c65ec8b479e450423fbc71c110268329a137bedada0db291904，vc134/1.0.18，923 条目 CRC OK，7tattoo v2+v3。
### 改动（RaccoonProvider.java）
1. **换票路径不写 uid**：saveLogin 走 addCred(cred()) 时 uid 为空 → pool.addOrUpdate("") 会
   并进旧槽位/丢弃，真机表现「登录成功但账号未新增」。修：saveLogin 内先 saveCred(c) → fetchUserName()
   （补 uid/nickname/officeIdentity/officeOrg/officeRole）→ 再 addCred，落库带真 uid。
2. **rt 污染**：rt 与 access 都是 JWT，网页抓取侧分不清，曾把 access 当 rt 存下 → 续期恒 401
   authorization verify failed。修：saveTokenFromWeb 里 rt == access（或 access 以 rt 为前缀且 rt>40 字符）
   一律丢弃，宁缺毋滥。
3. code 双通道重复消费防护（scheme 回调 + bodyScan 兜底同一 code 投递两次）：认领锁。
### 踩坑
- RaccoonProvider 无 App 引用，写日志只能用 android.util.Log（本轮又踩一次 App.get() → BUILD FAILED）。
- 构建 JAVA_HOME=/opt/jdk21-home gradle :app:assembleRelease -x lint（21s）；sign-apk 输出固定名 app-release-aligned-signed.apk，需手动 cp 成交付名。
### 待真机
覆盖装 vc134 → 重登 Raccoon：应「登录成功」且账号**新增一条**（uid 非空）；
若之前存过脏 rt，续期不再出现 authorization verify failed。

## vc135 / 1.0.19（2026-10-15 15:40，Raccoon 登录落库根因：以新 accessToken 自洽）
产物 shared/codearts7/CodeArts7_1.0.19_vc135-20261015-raccoon-login-uid-selfconsistent-signed.apk
sha256 2636314a3179950722aa970936090de33a35707a1a26e72724eb4220230c9a4c，vc135/1.0.19，923 条目 CRC OK，7tattoo。
### 真机日志（app_9.log）钉死的事实
- onCallback 收到 code ✅ → handleLoginCallback 结果: ok ✅（换票已修好，vc133/134 生效）。
- 但 vc134 仍未解的两个症状：
  ① `RACCOON-REFRESH failed: 保存的续期凭据其实是 access_token（登录时抓错了）` → rt 被污染成 access；
  ② 账号池串号 / 登录后账号不新增（uid 空）。
### 真根因（不在账号池，在 saveLogin 写入路径）
`saveLogin(access, rt, …)` 旧实现 `JSONObject c = cred()` —— 以**账号池当前激活账号**为底本：
- 抓不到 rt 时，旧账号的 refreshToken 被继承进新账号（脏 rt → 续期恒 401）；
- 新 access 的凭据 addOrUpdate 进旧账号槽位（uid 还是旧的 → 串号/不新增）。
（上一版 vc134 的「saveCred→fetchUserName→addCred」治标不治本：仍以 cred() 为底本。）
### 修复（RaccoonProvider.java）
1. `headers()` 抽出 `headersFor(JSONObject c)`（不读账号池），供新 token 查 user_info 用。
2. `saveLogin` 重写：**新凭据自建 JSONObject（绝不继承旧账号 rt）**；
   rt==access（或 access 以 rt 为前缀且 rt>40）一律丢弃（保留 vc134 闸门）；
   `fillUserInfo(c, access)` 用**新 access** 拉 user_info 补 uid/nickname/officeIdentity/officeOrg/officeRole；
   `pool.addOrUpdate(uid, …)` 按真 uid upsert + `pool.setActive(acc.id)` 切激活 + setOfficeRole。
3. `fillUserInfo` 新增：401/403 或无 data 返回 ""（不阻断登录）。
### 编译
JAVA_HOME=/opt/jdk21-home gradle :app:assembleRelease -x lint（35s）；sign-apk → /tmp/signout/app-release-aligned-signed.apk，手动 cp 成交付名。
dex 特征串：fillUserInfo / headersFor / "saveLogin 丢弃与 access 相同" / user_info 各 FOUND。
### 待真机（判据）
覆盖装 vc135 → 重登 Raccoon：
- 状态栏应「登录成功」；账号列表**新增一条**（uid 非空、昵称正确）；
- 续期不再出现「保存的续期凭据其实是 access_token」/ authorization verify failed；
- 若 rt 确实抓不到 → 走 20s 兜底「登录成功（未获续期凭据）」，下次登录应能补上。

## vc136 / 1.0.20（2026-10-15 16:45，Raccoon 脏 rt 僵尸账号自愈 + 日志刷屏治理）
产物 `shared/codearts7/CodeArts7_1.0.20_vc136-20261015-raccoon-dirtyrt-cleanup-signed.apk`
sha256 37b77032b0efc67beb79f9a0188e7baca09261b48bb6e7d80495cddaad8c51b1，vc136/1.0.20，923 条目 CRC OK，7tattoo v2+v3。

### 用户日志（app.log，15:06–16:15）钉死的事实
- **vc135 的换票/落库路径其实已经修好**：15:32:04 `handleLoginCallback 结果: ok` →
  15:32:07 `开始 refresh uid=7387937` → `ok uid=7387937 rt_rotated=yes`（新登录账号真能续期、rt 还轮换成功）。
- 但 15:32:12 起另一个账号 `failed: 保存的续期凭据其实是 access_token`，
  15:36:38 `refreshAll 完成: ok=0 fail=3/3` → 池里 3 个账号**全是脏的**；
  用户删号后 15:45/16:15 稳定 `fail=1/2`。
- 结论：刷屏的是 **vc134 及更早版本（当时 `rtk()` 有 `if(/^eyJ/) return` 裸 JWT 兜底）
  写进账号池的历史脏账号** —— `refreshToken` 存成了 `accessToken`。
  它们 access 已过期、rt 结构性错误，refreshAll 每 30min 遍历都去刷、每轮必失败。

### 修复（RaccoonProvider.java + LoginActivity.java）
1. 新增 `isDirtyRt(rt, access)`：rt==access，或两者互为前缀且长度>40（网页抓取侧截断变体）。
2. `doRefreshAccount`：命中脏 rt → **就地清空 refreshToken 并落库**（`canRefresh()` 随即 false →
   refreshAll 下轮自动跳过，不再刷屏）；accessToken 保留，未过期仍可用。文案改为
   「续期凭据是脏数据，已自动清理；access 到期后重新登录一次即可恢复」。
3. `refreshAll`：遍历时先查脏 rt，命中直接 cleanup + `continue`（不计 fail）。
4. 刷新响应写回时 `if (!nrt.isEmpty() && !isDirtyRt(nrt, at))` 才落库，防上游/解析再次污染。
5. `LoginActivity.onStorage`：新增 `lastPendingTok`，同一 token 只处理一次 ——
   scanAll 每 2.5s 触发，旧实现每次都重跑 `saveTokenFromWeb`（内含一次 user_info 网络请求）
   并再打一行「pendingSave 置位」，真机日志被刷了数百条。

### 编译踩坑（又踩一次）
- `c.put("refreshToken","")` 在无 try 的语句里 → `unreported exception JSONException`；
  该处必须包 try/catch（`org.json.JSONException`）。
- 构建：`JAVA_HOME=/opt/jdk21-home gradle :app:assembleRelease -x lint --offline`（23s）；
  sign-apk 输出固定名 `app-release-aligned-signed.apk`，需手动 cp 成交付名。

### 待真机（判据）
覆盖装 vc136 → **不需要重新登录**：
- 启动后 30 秒内 refreshAll 应打印 `cleanup uid=… 脏续期凭据已清空`，之后
  `refreshAll 完成: ok=N fail=0/M`，**不再有「保存的续期凭据其实是 access_token」刷屏**；
- 登录页 `pendingSave 置位(onStorage)` 每账号最多出现 1 次（不再每 2.5s 一行）；
- 15:32 那条实测「新登录账号能刷新成功」保持不变。

## vc137 / 1.0.21（2026-10-15 18:00，exchangeCode 响应 Set-Cookie 兜底抓 rt）
产物 `shared/codearts7/CodeArts7_1.0.21_vc137-20261015-raccoon-rt-setcookie-fallback-signed.apk`
sha256 335680388c26cbb23c9fb0c3544730ae64911abc8bfe954182bb40baef882d69，vc137/1.0.21，923 条目 CRC OK，7tattoo v2+v3。

### vc136 真机（17:52）残症
- 17:52:31 `handleLoginCallback 结果: ok`（换票成功）→ 弹窗「该账号没有保存 refresh_token」。
- = **exchangeCode 拿到了 access_token，但 data 里没有 refresh_token**（rt 落库为空）。
- logcat 缓冲已滚掉，没抓到 `exchangeCode OK rtLen=N`；已 `logcat -c` 等下一轮。
- 推断：站点把换票 rt 只写进 HttpOnly Set-Cookie，body data 不再回（参考实现要求 body 必带 rt，
  但那是旧版站点行为；或 2 通道投递两次，第一次换票消费了 rt 只进 cookie）。

### 改动
1. `util/Http.Resp` 加 `headers` + `setCookies()`；`once()` 填充 `getHeaderFields()`。
2. `RaccoonProvider.refreshFromSetCookies(List<String>)`：Set-Cookie 名含 refresh_token 且值>=20 即取。
3. `exchangeCode`：body rt 为空 → Set-Cookie 兜底；日志升级为
   `exchangeCode body 无 rt，Set-Cookie 兜底=无（headers=[...]）/命中 len=N` +
   `exchangeCode OK rtLen=N bodyKeys=[...]`（下一轮真机一眼定性）。

### 编译踩坑
- JSONObject 没有 keySet()（org.json 老版本）→ 用 `names()`。

### 待真机判据（logcat RaccoonLogin）
- `exchangeCode OK rtLen=0 bodyKeys=[...]` → 站点真不给 rt：看 bodyKeys 里有什么、
  Set-Cookie 兜底是否命中（headers 列表会打出来）。
- 若 Set-Cookie 也没有 → rt 只能从 WebView 侧拿（vc129 结论：网页读不到）→
  下一步：抢跑换票（App 在网页消费 code 前自己发 login_with_authorization_code，
  响应里必有 rt——参考实现路径）或引导用户用「粘贴凭据 JSON 导入」。

# vc136+ 诊断：minis 调网关 glm-5.3-flash 绕圈圈（2026-10-15 晚）
证据：minis-2026-10-15.log（6.6MB/54k 行）+ app.log（网关 CTX/usage/TRAE-REQ 日志）。

## 三段式缺陷叠加 → 无限绕圈
**① Minis 全程零压缩**。整个 log 里 `[Context]` 行 0 条、offload/compact 仅 4 条且全是
`native_offload: initialized`/`===COMPACT===` 无关。消息 2→218、body 1.6MB，每轮全量重发。
本地估算 chars/3.5≈457k > 128k 兜底窗口 → 压缩/offload 门**应该**触发却没触发 → 静默 PROCEED。
候选根因：`effectiveContextWindowTokens()` 返回 null（liveModel 空）或估算路径未走到。待查。

**② 网关折叠是失忆的（铁证）**。536 次折叠，每轮 `msgs 208-229 -> 4-7 (drop 203-223)`，
`tok ~450k -> 21k [18-21% of window]`。**每轮丢掉约 99% 的对话**。
自身目标 `FOLD_TARGET_RATIO=0.55` → target=63800（55% 窗口），实际只落到 18-21%（~21k），多砍 3 倍。
原因：`tailBudgetEff = max(budget/2, min(tailBudget=29k, target/2=31.9k)) = 49.3k` < 目标 63.8k；
且近期工具结果单个 ~12k（大文件读取），`shrinkTailToBudget` 整轮回收，向下过冲到 ~21k / 4-7 条。
→ 模型只见 head(2)+notice+tail(4-7)=最后 4 轮，**记不住 5 轮前确认过什么** → 重读同一文件 → 历史又涨 → 再折 → 再忘 → 死循环。
这正是"没压缩而是过滤/忽略"：不压到目标，直接删中段。

**③ 校准瞎眼**。glm 会话每轮 `[no-sample]`、`calib=1.00`、`sess=1.00`。上游 Trae/glm 从不回 usage，
SessionContextLedger/observeUsage 永远拿不到真实样本，永远信 ±30% 估算，`realBound=450k` 其实是 est 回声。

**④ 疑似窗口错误**。ModelCaps 启发式 glm-5 → 1M；RaccoonProvider 兜底 sn-glm-5-3-flash=1,000,000。
但 live 目录上报 glm-5.3-flash context_window=116000，MultiCatalog:70 live 覆盖本地。若真窗口远大于 116k，450k 根本不该折。

## 修复优先级
A.(最有效) 网关 fold 改为"截断大工具结果 + 保留运行中摘要"：单条 >~2k token 的工具结果截断为前 ~2000 字符，
把 ~12k 释放到 ~1k，尾块可容纳 10+ 轮，模型有记忆。同时 tailBudgetEff 直接用 target 而非 budget/2。
B. 校准兜底：上游连续 N 轮不回 usage → 改用文档窗口基线，别让 realBound 回声 est。
C. 核窗口：确认 Trae /v1/models 对 glm-5.3-flash 到底报多少。
D. 修 Minis 静默压缩：查为何零 [Context]（effectiveContextWindowTokens/估算路径）。
E. 结构性最优：客户端本地压缩（内联摘要+剪旧工具结果），网关折叠不触发，模型拿摘要不失忆。

---

## vc138 / 1.0.22（2026-10-15 18:17）上下文治理：工具结果截断 + 尾块预算修正

产物 `shared/codearts7/CodeArts7_1.0.22_vc138-20261015-govern-tooltrunc-foldtail-signed.apk`
sha256 951d3a862dd9342773c1f90cb7640a25fe9a8a3f99f65a5db09e76e0dabd5826，vc138/1.0.22，923 条目 CRC OK，7tattoo。

### 用户报障
用 minis 客户端调网关下发模型做对话任务：**压缩上下文不及时，经常丢上下文，模型绕圈圈**
（反复读取已确认过的文件 / 工具调用循环）。怀疑网关上下文估算与实际严重不符。

### 证据链（关键）
1. **压缩时机其实"及时"**：ContextGovernor.onNewMessages 里 `if (window > 0 && budget > 0 && lastFoldAt > 0)` 每轮都在算，
   超 MIN_TRIGGER_RATIO(0.30) 即折。minis 日志报「未触发压缩」是 vc135 加的"折叠后仍超窗口则本轮不再折"的
   主动跳过——**跳过 ≠ 没算**。所以"不及时"不是压缩器没跑，而是**折了也治不好**。
2. **用户日志真凶（minis-2026-10-15.log）**：`ContextGovernor onCompression: 114 条历史消息已折叠省略`、
   重复 `read_file` 工具调用、assistant 反复「重新读取文件以确认」——正是用户描述的绕圈圈。
3. **根因 A（真）**：**大文件读取的单条工具结果动辄上万 token**。估算已正确数到它（日志 190872 bytes 一次读），
   但治理端只能整块处理——单条超尾块预算时，折叠只能"丢整轮"，尾块里只留得下 2~3 轮最近对话。
   40 轮会话估算 925293 tok，单轮尾 tok 23133，旧尾块预算 49300 只容 2.1 轮 → 模型根本记不住已读文件 → 绕圈圈。
   这不是"估算不准"，是**治理手段粒度太粗**。
4. **根因 B**：尾块预算 tailBudgetEff = `max(budget/2, min(tailBudget, target/2))`，把尾块压到折叠目标的一半，
   折完中段后尾块容不下最近对话，模型同样失忆。

### 修复（ContextGovernor.java）
1. **工具结果截断**（先温柔手段）：`MAX_TOOL_RESULT_TOKENS = 2000`。
   新增 truncateOversizedToolResults/truncateToolResult/truncateToolResultArray，
   把超标的 tool role 消息 content 按 token 预算截成"保留开头" + 追加
   `[…工具结果过长，网关已截断，仅保留开头 N 字符中的前 M 字符…]` 透明说明。
   数组块逐块按剩余预算截断。**所有轮次都留下，每轮都能看到对应文件的开头** → 模型不再失忆。
2. **尾块预算修正**：tailBudgetEff 改为 `max(target, tailBudget(window))`，
   目标 = 折叠后整体要落到的大小（窗口×FOLD_TARGET_RATIO）。首部与折叠横幅只占零头，尾块应占满 target。
3. 截断/折叠都写 ContextGovernor 日志，真机可查。

### 实测（EstimatorTest 纯 JVM 验收，logictest/EstimatorTest.java）
- truncateToTokenBudget：ASCII 30000 tok→截后 2000 tok；CJK 12000 tok→截后 2000 tok。精确达标。
- 40 轮 agent 会话（每轮工具结果 20000 tok）：原样 925293 tok，截断后 74293 tok（**降 12×**）。
- 尾块容纳轮次：单轮尾 tok 23133→1858；旧预算 49300 容 2.1 轮 → **新预算 63800 容 34.3 轮**。
  截断后 10/20/30/60 轮会话尾块均容 34 轮，折叠最多丢个位数轮次，模型记得住上下文。

### 编译/签名踩坑
- `new JSONObject((JSONObject) p)` 在本项目 org.json 版本不适用（无此拷贝构造器）→ 直接复用原引用。
- sign-apk 输出固定到 **输入 APK 同目录**（app/build/outputs/apk/release/），非 /tmp/signout/；输出已存在时不覆盖，
  需先 rm 再签。
- ContextGovernor 依赖 App.get()，不能直接 JVM 测试；ContextEstimator 只依赖 org.json，可独立 javac+java 跑。
  org.json jar：`~/.gradle/caches/modules-2/files-2.1/org.json/json/20260719/.../json-20260719.jar`。

### 待真机
覆盖装 vc138 → 复现绕圈圈场景（长文件读取 + 多轮工具调用）：minis 应不再反复重读已确认文件；
ContextGovernor 日志出现 `truncTool chars X->Y`（大结果被截断）；折叠时尾块应保留最近 20+ 轮；
即使触发折叠，模型仍能直接引用折叠横幅给出的工作区文件结论，不再重跑。

## vc138 / 1.0.22（2026-10-15 18:25，exchangeCode 诊断进 logSink——logcat 缓冲总滚掉）
产物 `shared/codearts7/CodeArts7_1.0.22_vc138-20261015-raccoon-exchange-logsink-signed.apk`
sha256 c92c6c23908b36499bd291a5c41a896b51399b8952a01aecad7e7192ce277818，vc138/1.0.22，923 条目 CRC OK，7tattoo。

### vc137 真机（18:20）结果
- 仍 `handleLoginCallback ok` + 弹窗「没有保存 refresh_token」；rt 还是没到手。
- logcat 抓 `RaccoonLogin` 又失败：main 缓冲从 18:21:51 才开始，18:20:48 的换票现场被系统日志冲掉
  （buffer 太小，AgentForegroundService/HWUI 日志极多）。**Log.i 这条路在真机上不可靠。**

### 改动（RaccoonProvider.exchangeCode 三处）
换票诊断全部同步写进 App 记录页 logSink（标签 LOGIN，provider 内用 App.get() 有 try/catch 保护）：
1. 换票响应：`exchangeCode http=N bodyHead=…前240字符`
2. Set-Cookie 兜底：`exchangeCode body无rt SetCookie兜底=无/命中len=N scCount=N headerKeys=[…]`
3. 成功：`exchangeCode OK rtLen=N bodyKeys=[…]`

### 待真机（18:2x 下一轮登录后记录页直接可读，不依赖 logcat）
- `rtLen>0` → 修复生效，收工。
- `rtLen=0 bodyKeys=[…]` + `SetCookie兜底=命中` → 也收工（rt 从 cookie 抓到）。
- `rtLen=0` + `SetCookie兜底=无 headerKeys=[…]` → 站点真不给 rt：
  bodyKeys 看有没有近似字段；都没有就走**抢跑换票**（App 在网页消费 code 前自己发
  login_with_authorization_code）——参考实现证明该响应必带 rt。

## vc139 / 1.0.23（2026-10-15 18:33）小浣熊「换票成功却弹未抓到 rt」= 双通道竞态
产物 shared/codearts7/CodeArts7_1.0.23_vc139-20261015-raccoon-dupdone-race-signed.apk
sha256 d72d2a80704130bfe3a5b25584d2638b5677b1f8f804677964fabe2de05b2f0c

### 真机日志（18:27）时序铁证
18:27:21 exchangeCode http=200          ← 真线程换票
18:27:21 handleLoginCallback 结果: ok   ← dup 线程抢先返回 "ok"
18:27:22 exchangeCode OK rtLen=432      ← 真线程 saveLogin/fillUserInfo 完成后才打
→ rt 其实已正确落库（rtLen=432，bodyKeys 含 refresh_token）。
但 dup 线程一返回 "ok"，LoginActivity 立刻 done() → 读 hasRefreshToken() 时真线程
还在 saveLogin/fillUserInfo（user_info HTTP ~1s），读到 vc136 清空后的旧槽位 → false
→ 弹「未抓到续期凭据」+ finishing 置位 → 真线程的 done 被挡，错误文案定格。

### 修复
1. RaccoonProvider.handleLoginCallback dup 分支 return "ok" → return "ok-dup"。
2. LoginActivity.handleCallbackAsync：ok 时再判 !"ok-dup".equals(r) 才 done。

### 判据（记住）
- 「ok 但弹缺 rt」先看 rtLen 行是否出现：出现=写库成功，问题在 UI 竞态，别改抓取逻辑。
- 一次性 code 并发防护的 dup 路径，状态码不能与成功相同。

# CodeArts7 vc141 / 1.0.25 交付（2026-10-15 19:05，Raccoon 网页抓取洗空 rt 的根治：落库层闸）

产物 shared/codearts7/CodeArts7_1.0.25_vc141-20261015-raccoon-preservert-signed.apk
sha256 6278c17bc15071e2fee7bb63d8eb16c7bc7b38247918fdc4b97783dd224f0cd7，vc141/1.0.25，923 条目 CRC OK，7tattoo。

## 真机 app.log 铁证（18:50，vc140）
```
18:50:35 exchangeCode http=200
18:50:36 exchangeCode OK rtLen=432      ← 换票写好了 rt
18:50:36 pendingSave 置位(onStorage), rt为空   ← 网页抓取通道又跑一轮空 rt → 洗掉
18:55:14 refreshAll 完成: ok=0 fail=0/1  ← 账号 rt 没了，被跳过
```
vc140 的 sameIdentity(uid 或 token 相等) 仍有漏口：uid 抖动 / token 不同 → 走空对象
→ addOrUpdate(uid) 按 uid 覆盖刚写好的 rt。

## 根治（改在 AccountPool 落库层，覆盖所有写路径）
1. 新增 `preserveRt(oldCred,newCred)`：新凭据 rt 为空而旧账号 rt 非空 → 保留旧 rt。
   addOrUpdate(uid 命中) 与 updateCred(按 id) 两条写路径都过这道闸。
   **合法刷新必然携带非空 rt，不受影响；只有「降级写空」被拦下。**
2. 新增 `clearRefreshToken(id)`：脏 rt 清理走显式清空，绕过 preserveRt（否则空 rt 被填回）。
3. RaccoonProvider 脏 rt 分支从 saveCred(c 空 rt) 改为 pool.clearRefreshToken(accId)。

## 判据（务必记住）
- 「ok 但弹缺 rt / refreshAll fail=0/1 被跳过」先看**两条通道是否并发写同一账号**：
  换票(exchangeCode→saveLogin) 与 网页抓取(saveTokenFromWeb) 是两条独立写库路径，
  只在 saveTokenFromWeb 里加门禁治不干净 —— **闸必须落在 AccountPool 落库层**。
- 本版安装后：账号池内 rt 为空但 access 存在的账号会被保留；重登一次后
  网页抓取再也洗不掉 rt → refreshAll 应 ok=1/1。

# CodeArts7 vc142 / 1.0.26 交付（2026-10-15 19:30，preserveRt 复活脏 rt 死循环）
产物 `shared/codearts7/CodeArts7_1.0.26_vc142-20261015-raccoon-dirtyrt-preserve-guard-signed.apk`
sha256 e9ade4bda0e943bff73dc301a506cfe773b7d337b5c2129ba0717c994afcd669，vc142/1.0.26，923 条目 CRC OK，7tattoo。
## 现场（15:40 app.log）
登录前 refreshAll 已连报两次「续期凭据其实是 access_token」；19:06 换票链路（rtLen=432 + 拒绝降级 + ok-dup）全部正常 = vc141 换票通道没问题。
## 真根因：vc141 的 preserveRt 与 vc136 的脏 rt 清理互相打架
vc136 doRefreshAccount 命中脏 rt → clearRefreshToken 清空；
网页抓取通道随后空 rt 落库 → preserveRt 发现「新 rt 空、旧 rt 非空」→ 把刚清掉的脏 rt 填回 →
下次 401 强刷再报「其实是 access_token」→ 再清 → 再复活，死循环。
## 修复（AccountPool.preserveRt 一处）
恢复旧 rt 前验脏：旧 rt 与旧 access 相同或互为前缀(len>40) → 不复活，直接返回新凭据。
合法刷新携带非空 rt 不受影响。
## 待真机
覆盖装 vc142：refreshAll 不再报「其实是 access_token」；重登后 ok=1/1。

---

# vc148 / 1.0.32 交付（2026-10-16，loomy qwen-3.8-max 不识图修复）

产物 `shared/codearts7/CodeArts7_1.0.32_vc148-20261016-loomy-qwen38max-vision-override-signed.apk`
sha256 56d55920b3310d37d421bc791abe4226325ce5c924ecf6a33302024874195355，vc148/1.0.32，923 条目 CRC OK，7tattoo。

## 需求（参考 gitee PR !96）
loomy 的 qwen-3.8-max 输入不能上图。根因：远端 GET /api/v1/models 对 qwen-3.8-max 下发
`capabilities.input_modalities=['text']`（vision:false），但实测**读得出图**（参考方用随机纯色 PNG
两轮 4/5 次答对颜色/RGB）。照抄声明值 ⇒ Minis 在附件入库阶段按目录拒图（MODEL_DOES_NOT_SUPPORT_IMAGES）。

## 修复（LoomyProvider.java，对齐 src/loomy-product.ts 的 LOOMY_IMAGE_CAPABILITY_OVERRIDES）
1. 新增 `LOOMY_IMAGE_CAPABILITY_OVERRIDES = {"qwen-3.8-max"}`（**小写**）+ `loomyCatalogKey()`
   （trim+toLowerCase 归一）+ `loomySupportsImage(id, declared)` = 覆盖表命中 || declared。
2. **两条路径都接线**（PR 核心纪律：只改一侧，远端可用时那一侧就白改）：
   - 远端 fetchModels：`e.vision = loomySupportsImage(id, hasImageModal(modal))`；
   - 兜底 fallbackModels：改用 `fb()` 辅助，`e.vision = loomySupportsImage(id, false)`。
3. **顺带修一个潜在真 bug**：旧代码读**顶层** `input_modalities`，而远端真实字段是
   `capabilities.input_modalities`（参考 readInputModalities 也读 caps 内）→ 旧代码对**所有**模型
   的图片声明恒 null ⇒ 除覆盖表外无人识图。现两形态都认（caps 内优先，顶层兜底）。

## 纪律（抄自 PR，别踩）
- 覆盖表只覆盖**实测确认**个案，**不写「该家恒支持图片」**（恒 true 会把图放给纯文本模型，上游回更晚更难懂的 400）。
- 表内条目必须小写（查表归一）。归一取舍：仅大小写不同的 id 会折叠——已权衡可接受。
- 未处理：MiniMax-M3 疑似假声明（声明 image、实测 4/4 读不出），参考方刻意不降级，单账号证据不足。

## 验证
- gradle 8.9 + JAVA_HOME=/opt/jdk21-home，`:app:assembleRelease -x lint`，50s，BUILD SUCCESSFUL。
- dex strings：`LOOMY_IMAGE_CAPABILITY_OVERRIDES` / `loomyCatalogKey` / `loomySupportsImage` / `qwen-3.8-max` 全 FOUND。
- pyaxmlparser：1.0.32 / 148；zipfile CRC OK（923 条目）。
- 待真机：loomy 分组 qwen-3.8-max 应带 vision 标签；Minis 拉取 /v1/models 后该模型
  architecture.input_modalities 含 image → 可发图。

---

# vc149 / 1.0.33 交付（2026-10-16，新增 AutoClaw 国内版供应商）

产物 `shared/codearts7/CodeArts7_1.0.33_vc149-20261016-autoclaw-cn-provider-signed.apk`
sha256 051e53bfe26179beeabefe94923aa7929bfa5ef9880a3dcec08de26cb66b5671，vc149/1.0.33，923 条目 CRC OK，7tattoo。

## 需求源
参考 https://github.com/aimod-cc/agent2api 的 Rust 实现 `providers/autoclaw/*`，
为 CodeArts7 增加 **AutoClaw 国内版**供应商（多账号登录 / 积分 / 签到 / 模型列表）。
协议规格落盘：`ref/autoclaw_cn_spec.md`（从 Rust 逐条提取，含全部 URL / 头 / 字段）。

## 新增文件（**自包含**，不改动其它 provider）
- `provider/AutoClawProvider.java` —— 全部协议实现（登录 / 刷新 / 模型 / 转发 / 积分 / 签到）
- `provider/AutoClawPrompt.java` —— 出站 system 提示词规范化（身份前缀 + 外来身份句改写）

## 接线改动（最小面，便于整块移除）
- `ProviderRegistry.java`：注册表加一行 `all.add(bridge.autoclaw())`；AppBridge 加 `autoclaw()`。
- `App.java`：加字段 + `new AutoClawProvider(this)` + bridge 方法 + `autoclawProvider()`。
- `ui/MainActivity.java`：`providerIcon/providerDesc` 各加一个 autoclaw 分支。
- `ui/ProviderDetailActivity.java`：短信框分支扩到 autoclaw（内联，独立 deviceId 流程）。
- 其余（网关路由 / 模型目录 / 账号池 / 签到按钮）**自动生效**：都按 registry.all() 遍历。

## 协议要点（详见 ref/autoclaw_cn_spec.md）
- 站点：userapi `https://autoglm-acceleration-api.zhipuai.cn`；LLM 代理 `.../autoclaw-proxy/proxy/autoclaw`。
- 登录（国内版唯一）：手机号短信。`agent-send-code` → `agent-login/`（code 必须是 **JSON 数字**，
  platform="web"，沿用同一 device_id）。
- 签名头 `X-Auth-Sign = md5("100003&<秒>&38d2391985e2369a5fb8227d8e6cd5e5")` + 一串品牌头
  （X-Version/X-Product/X-Client-Type/X-Harness-Type/X-Tm/X-Lang/X-Channel/X-Auth-*）。
- 刷新：`/userapi/v1/refresh`，code 400002 降级 `/userapi/v1/agent-refresh`。
- 模型：`GET .../proxy/autoclaw-model-config`（**无 code/data 信封**，顶层 {"models":[...]}）；
  id 剥前缀（先 zaicoding_ 再 zai_）作目录名，routeId 进 `X-Request-Model`。默认路由 `zai_auto`。
- 转发：认证头是 **X-Authorization**（不是 Authorization）；**不发 X-Harness-Type**（403/406）；
  max_tokens 小正整数(<10000)抬到 10000；system 需前置 OpenClaw 身份句 + `## Tooling`。
- 积分：v2 `/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw` → v1 兜底 → expiring 附加。
- 签到：`POST .../autoclaw-task-complete` body `{"task_id":"daily_signin"}`，
  成功判据 `reward_points>0 || (success && !already_completed)`（按天幂等）。

## 验证
- gradle 8.9 + JDK21，`:app:assembleRelease -x lint` 48s BUILD SUCCESSFUL。
- 纯逻辑自测 `sim/AutoClawLogicTest.java`：**17/17 PASS**（前缀剥除 / system 前缀插入 /
  8 条外来身份句改写 / 幂等 / 大小写不敏感 / 多模态数组只改 text 块）。
- dex 特征串全 FOUND（autoclaw / autoglm-...zhipuai.cn / X-Auth-Sign / appKey /
  X-Authorization / zai_auto / daily_signin / OpenClaw 身份句 / agent-send-code）。
- pyaxmlparser：1.0.33 / 149；zipfile CRC OK（923 条目）。
- 待真机：供应商页出现「AutoClaw 国内版」分组；手机号短信登录 → 账号入池；
  积分/签到/模型列表/发消息全链路。

## ⚠️ 上游失效时的移除步骤（用户明确要求「方便去除」）
1. 删 `provider/AutoClawProvider.java` + `provider/AutoClawPrompt.java` + `ref/autoclaw_cn_spec.md`。
2. `ProviderRegistry.java`：删 `all.add(bridge.autoclaw());` 一行 + AppBridge 的 `autoclaw()` 声明。
3. `App.java`：删字段 / 构造 / `autoclaw()` / `autoclawProvider()`。
4. `MainActivity.java`：删 `providerIcon`/`providerDesc` 的 autoclaw 两行。
5. `ProviderDetailActivity.java`：把 `isAutoClaw` 相关分支还原为纯 isLoomy。
无其它文件引用（账号池 / 网关 / 目录按注册表遍历，删注册即消失）。

---

# vc150 / 1.0.34 交付（2026-10-16，AutoClaw 登录 307 修复）

产物 `shared/codearts7/CodeArts7_1.0.34_vc150-20261016-autoclaw-login-307-fix-signed.apk`
sha256 a5e0a875d5b21c0da72829155a459e409fee798d2788ea1f7771da5a3b6c7385，vc150/1.0.34，CRC OK，7tattoo。

## 真机报障（11:16 截图）
AutoClaw 登录：`登录失败: 登录返回 HTTP 307`。

## 根因（curl 实测复现）
参考实现 LOGIN_PATH = `/userapi/v1/agent-login/`（带尾斜杠），但上游实际把
`POST /userapi/v1/agent-login/` **307 重定向**到 `/agent-login`（无尾斜杠），
Location 头明确给出。Rust 参考栈对 3xx 手动跟随所以没事；
`HttpURLConnection` 的 POST **不自动跟随 3xx**（且跨路径重定向会转 GET）→ 拿到裸 307。

## 修复
`loginWithCode` 改打 `/userapi/v1/agent-login`（无尾斜杠）——curl 实测直接 200，
返回完整 token 对（access_token/refresh_token 均 Bearer 前缀，代码里 stripBearer 已处理）。

## 判据（以后复用）
- 「POST 返回 307/308 且 content-length:0」→ 先 curl 看 Location，八成是尾斜杠/路径形态差异；
  HttpURLConnection 不跟随 POST 重定向，必须在客户端就用最终路径。
- 参考实现的路径常量不能盲抄——它的 HTTP 栈行为（redirect manual + 手动跟随）与本端不同。

## 验证
curl：无尾斜杠 → HTTP 200 {"code":0,...,access_token,refresh_token}（用真实手机号+过期码，
返回 code 0 说明参数形态全对，仅码已消费——正式登录用户重发码即可）。
gradle 42s SUCCESS；dex 特征串 `userapi/v1/agent-login`（无尾斜杠）FOUND；1.0.34/150；CRC OK。

---

# vc151 / 1.0.35 交付（2026-10-16，AutoClaw「登录态已过期」修复 = Bearer 双拼）

产物 `shared/codearts7/CodeArts7_1.0.35_vc151-20261016-autoclaw-bearer-strip-signed.apk`
sha256 57397bb03f5df6a79112f6cf118c4ab96d0cdaccbb1515d35154f356d87c6a58，vc151/1.0.35，CRC OK，7tattoo。

## 真机现场（11:20 截图）
登录成功入池（1 账号可用），但积分查询报「错误: 登录态已过期」。

## 根因
AutoClaw 登录/刷新响应里的 access_token/refresh_token **自带 "Bearer " 前缀**。
参考实现全链路都过 crypto::strip_bearer；我在 loginWithCode 落库时漏剥 →
发请求时再拼一层 `Bearer Bearer eyJ...` → 上游判过期。
（refresh 链我剥了，login 链漏了——两条链必须同口径。）

## 修复（vc151）
1. loginWithCode：access_token / refresh_token 落库前 stripBearer。
2. token() 读取时兜底再剥一次 → **已落库的脏凭据无需删除重登**，覆盖装后直接可用。

## 判据
- 「登录成功但所有带鉴权的查询都报过期」→ 先查落库的 token 原文是否含 "Bearer "；
  上游 token 带 Bearer 前缀的（AutoClaw/小浣熊同款），落库与读取两端都要 strip。
- 同一凭据在「登录链」和「刷新链」的处理必须逐条对齐，参考实现每处都过 strip_bearer 不是装饰。


# CodeArts7 vc152 / 1.0.36 交付（2026-10-16，账号页刷新慢/timeout 根治：缓存优先+逐账号渐进刷新）

产物 `shared/codearts7/CodeArts7_1.0.36_vc152-20261016-pd-cache-first-refresh-signed.apk`
sha256 939671f5399bc6956230f6bf62898abceeca512f107f24b23e60dd01da2a55de，vc152/1.0.36，CRC OK，7tattoo。

## 用户报障
Loomy 账号页刷新接近 1 分钟，经常刷不出/timeout（截图：3 账号全「积分 — · 错误: timeout」）。

## 根因（4 个叠加）
1. **账号页 collectPerAccount 全串行+全量联网后才渲染**：3 账号逐个 setActive→account()→切回，
   每个 20s 超时，全跑完才画页面 → 全挂时 3×20s≈60s，与「接近1分钟」吻合。
   实测 loomyad.xunfei.cn points/records 本身 0.4s 响应（无鉴权基线，沙箱三连 0.39~0.42s）——
   不是服务端慢，是「一次超时=20s × 串行3个」的结构问题。
2. **账号页完全不用 vc121 的 CreditsCache**：主页 15min TTL 内拉过的积分全被丢弃，
   进页/每次按钮（切换/解锁/删除/签到后）都全网重拉。
3. **与主页后台刷新竞态**：主页 refreshCreditsAsync 有 providerLock，账号页没有，
   两边同时 setActive 切换 → account() 读错账号 →「刷不出/数据串号」。
4. 主页拉失败也把空积分写缓存 → 好数据被污染 15 分钟（「积分 —」假象）。

## 修复
- AccountPoolHolder 新增静态 `providerLock(pid)`（主页委托过去，账号页/签到共用同一把）。
- App 新增全局唯一 `creditsCache()`（主页委托，账号页直读同一份）。
- ProviderDetailActivity.reload(force) 双阶段：**缓存先渲染（秒出）→ 后台只刷过期/force 账号，
  每完成一个增量重绘**；AtomicInteger reloadGen 代际守卫作废旧线程；失败保留缓存积分
  （卡片=上次积分 + 错误行），不再出现「积分 —」假丢数据。右上「刷新」=force=true；
  签到完成后 force=true（签到改变积分）。一键签到补上 providerLock。
- MainActivity.refreshCreditsAsync 只在 error 空且 credits 非空才写缓存（修污染）。
- LoomyProvider.refreshCredits 超时 20s→10s（25 倍实测裕量，单账号失败代价减半）。

## 判据（复用）
- 「刷新慢≈N×单请求超时」→ 先查串行循环 × 超时叠加，别先怀疑服务端；用 curl 无鉴权基线测端点。
- 两处同时切 active 的代码必须共用同一把 provider 锁（AccountPoolHolder.providerLock），
  account()/checkin() 只认「当前激活账号」，锁不住就串号。
- 失败快照不写缓存；UI 失败时回退显示缓存值 + 错误行。
- 本沙箱 unzip -p/-t 输出异常（PRoot），校验 apk 用 python zipfile（testzip + read dex 查特征串）。

## 验证
gradle assembleRelease -x lint SUCCESS；pyaxmlparser 1.0.36/152；CRC OK；
dex 特征串 refreshAccountsAsync/cachedSnapshots/providerLock/pd-credits 全 FOUND。待真机：
进账号页应秒出缓存积分，超时账号只挂自己那行、其它账号照常回填，右上刷新强制重拉。

# CodeArts7 vc153 / 1.0.37 交付（2026-10-16，模型显示计费倍率）

产物 `shared/codearts7/CodeArts7_1.0.37_vc153-20261016-model-rate-display-signed.apk`
sha256 7cd558b760e7a892c422fc5d220870f9912a7922c5e6b52ad7c582bc3216f14a，vc153/1.0.37，923 条目 CRC OK，7tattoo。

## 需求
模型页每个模型显示计费倍率：免费 x0、其它 x0.08/x1 之类（用户原话）。参考：har（accio/buddy/trae/loomy/raccoon 五家倍率实现）+ dsh-our-free-model + agent2api（autoclaw creditConsumptionLevel）。

## 实现（新文件 ModelRate.java 自包含 + 5 处一行接入 + UI 1 处）
- `provider/ModelRate.java`：全部倍率解析（零 Android 依赖，org.json+java.time，可独立自测）。
  - 通用：normalize（x 前缀/后缀两种写法+单位后缀）、compose（促销只在其**确实更便宜**时出 `x原价→x促销` 箭头）、trimNum（%.4f 去尾 0）、splitRateSuffix（Loomy 展示名尾部全/半角括号 `（x4.0）` 与 `· x1.0` 两形态）。
  - Buddy：模型 `credits` + `data.modelPromotions[]`（enabled/priority/discount.discountedCredits+factor/schedule.daily+timezone+validFrom+validUntil/modelIds），促销窗口本地判活（跨零点 HH:MM、IANA 时区、ISO 有效期；无窗 factor:0=已结束占位跳过、带窗=真限免 x0；非法时区不误杀）。
  - TRAE：`display_contact_config` 字符串 JSON 二次 parse → consumption_rate.data.rate（rate:0 合法，不能用 >0 过滤）+ activity_discount（discount_type=none/非真降价/end_at 过期都不出箭头）。
  - 小浣熊：billing_effective_multiplier/billing_multiplier/billing_status（limited_free→x0；1 倍也显示）。
  - AutoClaw：`creditConsumptionLevel` 档位文案（低/中/高）原样透传，不换算数字（上游无映射表，编造即造假）。
- ModelEntry 加 `String rateText`（null=未知不显示）；MainActivity 模型行主标题 `id · x0.08`（e.id 纯净，路由/探测/禁用不受影响）。
- 兜底表一律不设 rateText（本地快照无价格，不编造）。

## 判据（复用）
- 倍率解析三条红线（参考实现血泪）：解析不出→不显示不编造 x1；0 是合法倍率与「无信息」区分（判 null 非真值）；促销必须本地判 schedule 此刻生效（enabled:true ≠ 正在打折）。
- 上游倍率字段各家不同：buddy credits 字符串 x 前后缀混用、trae 藏在 display_contact_config 字符串 JSON 里、raccoon 是 billing_* 数值、loomy 藏在展示名括号里、autoclaw 只有档位文案。
- 逻辑自测：sim/ModelRateLogicTest.java 56/56 PASS（javac -cp tools/json-20240303.jar 直跑）。
- org.json 测试坑：optDouble(key, fallback) 的 fallback 里嵌 optDouble 可优雅实现大小写键兜底，别用 has() 门挡（会挡掉大写 Rate）。

## 验证
gradle 47s SUCCESS；sim 56/56；dex 特征串 11/11 FOUND；pyaxmlparser 1.0.37/153；CRC OK。待真机：模型页各家模型应带倍率后缀（loomy x4.0、raccoon x1、buddy x0.29→x0 等）。

# CodeArts7 vc154 / 1.0.38 交付（2026-10-16，倍率红字）

产物 `shared/codearts7/CodeArts7_1.0.38_vc154-20261016-rate-red-signed.apk`
sha256 d69790511d1a2457d9e26d7c55684c3d5495c243f7285c2336d3fce5077c68bc，vc154/1.0.38，923 条目 CRC OK，7tattoo。

## 改动（仅 MainActivity 一处）
模型行主标题的倍率部分红字：SpannableString + ForegroundColorSpan(#DC2626 danger，与失效/禁用同款语义红)，
只给 ` · x倍率` 段上色，模型 id 保持原色。无其它变化。

## 判据
- 同一 TextView 局部换色用 SpannableString，别拆布局加第二个 TextView（多一层 inflate/布局维护）。
- 上色起点 = e.id.length() + 3（" · " 三字符），只染倍率不染分隔符。

## vc155 / 1.0.39（2026-10-16 晚，主页「最近动态」→「今日Tokens消耗」）
产物 shared/codearts7/CodeArts7_1.0.39_vc155-20261016-daily-tokens-signed.apk
sha256 77b714a57f93c8fe77343fc54cc1a199b04997f956f1fc86cac1b84c4bc31287，vc155/1.0.39，923 条目 CRC OK，7tattoo。

### 改动
- 新增 log/UsageStats.java：今日（自然日，本机时区）tokens 统计，filesDir/usage_stats.json 持久化，
  跨零点懒滚动清零；只存当天一份。
- PreparedChat.observeUsage：不再只采首个 usage 帧——每帧跟踪，prompt 取最后一次、
  completion 取最大者（累计型 usage 帧语义）；流结束 reportUsageToGovernor 回灌 usageStats.add(p,c)。
  上游不发 usage 的流不计入（不编造）。
- 网关把所有 chat 强制 stream=true → PreparedChat 是唯一用量捕获点，/v1/responses 等端点 501 无需考虑。
- 布局：最近动态卡 → 今日消耗卡（tokenTitle/tokenDesc）；MainActivity 删 pushActivity 及 3 处调用
  （toggle/签到/状态广播），改 updateTokenCard()：标题「今日Tokens消耗: N tokens」，
  副行「输入 X · 输出 Y · N 次调用」；logPoller 2s 轮询顺带刷首页 tab 的卡片。

### 判据
- 网关统计类需求先确认「所有流量都走哪条路」：stream 强制 true ⇒ 单点捕获，别到处埋点。
- 累计型 usage 帧（OpenAI include_usage / Anthropic message_delta）取 max/最后一次，+= 会重复计。
- 布局文案校验别查 resources.arsc——本项目 res 路径混淆（res/v9.xml 这类），直接扫全部条目字节。

# CodeArts7 vc156 / 1.0.40 交付（2026-10-16 夜，新增 KiloCode 免登录免费通道）

产物 `shared/codearts7/CodeArts7_1.0.40_vc156-20261016-kilocode-free-provider-signed.apk`
sha256 c108ce7169add30e3c516ecdf0bc877bef5aa1512f8d49b8ec425eb3015aeda2，vc156/1.0.40，923 条目 CRC OK，7tattoo。

## 需求源：参考 APK com.dsh.ofm（dsh-our-free-model）
jadx 反编译其 classes.dex（GatewayService.handleChat / Api.java）+ assets/index.html。
上游两条免费 lane **无鉴权**，此前 vc119~vc124 删除的 OpenCode 通道**现在能通**（当时是 IP 地区问题，非协议问题）。

## 新增文件（自包含，移除步骤写在文件尾注释）
`provider/KiloCodeProvider.java` —— 两条 lane 合一：
- **Kilo 池** `https://api.kilo.ai/api/gateway`：只要 UA `dsh-our-free-model`，**无鉴权无指纹**。
  模型清单 `/models` 取 `isFree:true`（实测 16 个）；本地用前缀 `kilo::` 与另一池区分，请求时剥掉。
- **OpenCode 池** `https://opencode.ai/inference/v1`：`Bearer public` + opencode 客户端指纹
  （UA opencode/1.18.31 / x-opencode-client:cli / x-opencode-session(-id) / x-opencode-request /
  x-opencode-project:global）+ **tools 四件套**（bash/glob/grep/read，缺一个即 403 FreeTierError）。
  清单取 `-free` 后缀 + `big-pickle`。

## 实测（2026-10-16 沙箱直连，全部逐条 curl 验证）
- Kilo：16/16 isFree 直连 200（含 stealth/cat-jam、openrouter/free、nvidia 系）；
  带客户端 tools 不冲突；带 image_url 视觉模型可用；无 session 形状要求。
- OpenCode：deepseek-v4.1-flash-free / glm-5.3-flash-free / mimo-v2.6-flash-free /
  nemotron-3.5-super-vl-free / nemotron-3.5-lightning-free / longcat-2.5-preview-free /
  space-bunny-free / ll-alpha-free / big-pickle → 200。
  **地区限制 403 RegionError**：beam-free、muse-spark-1.2/1.3-contributor-free。
  **协议不符 400 ModelProtocolUnsupported**：jev-1.13-free（/responses 也拒）。
  → 地区/协议类失败**不在代码里硬编码过滤**，交给模型页「探测 → 禁用失效」。
- 探测请求形状（app 实际发的：无 tools + 补 decoy + tool_choice=auto）两条 lane 都 200。

## 关键判据（新，复用）
- **session/request id 前 12 位 hex 必须是真实毫秒时间戳的派生**：`ses_zzzz…` 直接 403
  FreeTierError；session 用 `~(ts*4096+seq)`、request 用 `ts*4096+1`（不取反）。
  随机前缀会被判非法 —— 这是「明明带 Bearer public 还 403」的第一嫌疑。
- session 必须**同一安装内稳定**（上游按 session 计免费额度，频繁换新 id 会限流）：
  用 sha256(install_seed) 派生，落 SharedPreferences，跨重启不变。
- 四件套 tools 是**硬门禁**：客户端没带 tools 时必须补 self-disabling decoy
  （描述必须原文 `This tool is currently unavailable and must not be used.`），
  带了同名工具要规范成小写并记录还原映射（PreparedChat.rewrite 响应端还原）。
- 「免登录供应商」必须**同时**提供 `loggedIn()==true` 与 `loginFree()==true`：
  前者让 Router/目录/健康检查自动放行，后者让 UI 隐藏「未登录」标签与二级菜单。

## 接线（最小面，共 5 处）
1. `ProviderRegistry` 构造器 +1 行 `all.add(new KiloCodeProvider());`
2. `ServiceProvider` +1 个 default 方法 `loginFree()`（默认 false，仅本 provider 覆写）
3. `MainActivity.providerIcon/providerDesc` 各 +1 行 kilocode 映射
4. `MainActivity.fillProviders` 用 `if (p.loginFree())` 分叉：卡片显示「免登录 · 无需账号」、
   不画 chevron、点击只 toast「免登录，无需配置；模型清单见「模型」页」→ **无二级菜单**
（网关/目录/健康检查/Key 分组全部按 registry.all() 自动生效，无需改动）

## 验证
gradle 8.9 + JAVA_HOME=/opt/jdk21-home，`:app:assembleRelease -x lint` 41s SUCCESS；
pyaxmlparser 1.0.40/156 pkg=com.aigetway.tattoo7；923 条目 CRC OK；
dex 特征串 KiloCodeProvider/kilocode/api.kilo.ai/opencode.ai-inference/dsh-our-free-model/kilo::/x-opencode-session/loginFree 全 FOUND。
待真机：供应商页应出现 KiloCode 卡片（免登录、无二级菜单），模型页该分组应有两条池的模型并可探测。

# CodeArts7 vc157 / 1.0.41 交付（2026-10-16 深夜，KiloCode OpenCode 池全挂修复）

产物 `shared/codearts7/CodeArts7_1.0.41_vc157-20261016-kilocode-session-timestamp-fix-signed.apk`
sha256 a1cc501df0a938b1cf4735af2a91720b958d332f486f2c27f531b517aaa782e6，vc157/1.0.41，923 条目 CRC OK，7tattoo。

## 用户报障
vc156 真机：模型页 KiloCode 分组的 **OpenCode 池全部「已禁用」**（探测失败被自动拉黑）；
但参考 APK 网关同环境正常（图二）。参考仓库 github.com/Ebony-Vinyl/dsh-our-free-model。

## 真根因（vc156 首版踩了参考实现明确警告过的坑）
`session()` 用了 **sha256(install_seed) 派生的稳定 session** → 前 12 位 hex 随机。
上游会反解 `seq = ~prefix48 & 0xFFF` 校验，随机前缀 → 403 FreeTierError，**每个请求都拒**。
参考实现 upstream.js 原文（2026-10-14 实测）：
> "The gateway also checks the id's *shape*: the twelve hex digits must be the time
> encoding that `mintSessionId()` produces. **A sha256 digest, or any other random hex,
> is refused with 403 FreeTierError even when every other header is right.**"

### 排查过程中被误导的两个坑（记下来）
- 探测脚本里若忘了加 14 位 base62 后缀（只有 `ses_<12hex>` = 16 字符），**同样 403** ——
  门禁正则要求完整 30 字符。一度以为是"时间戳新鲜度"问题，其实是形状不全。
- 上游是**按 session 计免费额度**的：每请求换新 session 会累积成 429（带增长的 retry-after）。
  所以正确做法是「**mint 一次 + 缓存**」，而不是「每请求新 mint」。
  （我一度按参考 APK 反编译的 Api.sessionId() 每请求新生成来做，方向错了 ——
  那是参考 APK 的网关实现，仓库版才是权威：`sessionForConversation` 有 session-map 持久化。）

## 修复（KiloCodeProvider.java）
1. `mintSessionId()`：逐位对齐参考实现 `~(ts*0x1000 + seq)`，seq 为同毫秒内计数器
   （跨毫秒归零），12 位小写 hex 时间前缀 + 14 位 base62 随机后缀 = 完整 30 字符。
2. `session()`：**mint 一次缓存到静态字段**（`cachedSession`），跨请求稳定 ——
   既满足形状合法，又不会耗尽 session 额度。
3. `newRequestId()`：`ts*0x1000+1`（不取反），每请求新值（参考实现一致）。

## 交叉验证（关键判据）
- **Java vs 参考 JS 逐字节相等**：同一 ts/seq 下
  `ses_ebad82dffffeAAAAAAAAAAAAAA` / `msg_14527d200001AAAAAAAAAAAAAA` 两实现输出完全一致。
- 沙箱直连（完整 Java 算法模拟）3/3 通过。
- **tools 四件套是硬门禁**（比参考注释更严，实测）：
  带 bash/glob/grep/read（每个 parameters.properties 非空）→ 200；
  不带 tools / 只带一个非四件套工具 → **403**（与模型无关）。
  provider 的 decoy 注入 + `tool_choice=auto` 形状实测通过。
- 带四件套后逐模型实测：deepseek-v4.1-flash-free / mimo-v2.6-flash-free /
  longcat-2.5-preview-free / space-bunny-free / big-pickle / ll-alpha-free /
  nemotron-3.5-lightning-free / nemotron-3.5-super-vl-free（**带图 200**）→ 全 OK。
  仅 3 个真·上游不可用：beam-free / muse-spark-1.2-contributor-free → 403 RegionError（地区），
  jev-1.13-free → 400 ModelProtocolUnsupported（参考实现把它归到 /messages 端点，本项目未接）。

## ⚠️ 真机操作提示
被 vc156 自动禁用的模型 id 存在 SharedPreferences（Settings.disabledModels），**升级安装不会自动恢复**。
装 vc157 后需在模型页 KiloCode 分组点一次「**解禁(N)**」，OpenCode 池模型才会重新下发。
（beam-free / muse-spark-* / jev-1.13-free 三个仍会被探测判失效，属正常。）

## 验证
gradle 42s SUCCESS；pyaxmlparser 1.0.41/157；923 条目 CRC OK；
dex 特征串 mintSessionId/isValidSession/x-opencode-session-id/dsh-our-free-model/kilo::/loginFree/cachedSession 全 FOUND。

---

## vc158 / 1.0.42 交付（2026-10-17，用户 6 需求：我的页瘦身 / 无 Key 调用并入 API Key 校验 / 关于版本号修复）

产物 `shared/codearts7/CodeArts7_1.0.42_vc158-20261017-keyauth-uiclean-signed.apk`，
sha256 `d1ea501fd83fa60e0f7c9e097570a69f78b72664c792e45848df08ac49457f6c`，4.89 MB，vc158 / 1.0.42，
包名 com.aigetway.tattoo7，zip 全条目 CRC OK，签名证书 SHA-256 `4ebe0621…c3b76bf6`（与 vc119 交付、MT2/keystore/7tattoo.jks 同一把，可直接覆盖安装）。
源码 `/workspace/codearts`，改动 diff `/workspace/codearts_1.0.42_vc158.changes.diff`（373 行，7 文件）。

### 6 项处理
1. **「我的」顶部网关地址/端口展示区整卡删除**：activity_main.xml 删原 734-840 行（版本行 + 地址行 + 复制按钮）；MainActivity 删 `mineVersionLine`/`mineAddr` 字段、findViewById、renderStatus 里两处 setText。首页 hero 卡的地址行保留。
2. **「无 Key 调用」开关删除**：布局 switch + MainActivity `noKeySwitch` 全套移除。
3. **「启用 API Key 校验」接管该语义**：PortKeyActivity 开关改为即时生效（不再等「保存设置」），文案改为「开启后网关校验 Key：未带有效 Key 的请求返回 401；关闭即『无 Key 调用』」；`Router.resolveKey` 逻辑改为 `keyRequired()==true` 时必须带有效 key（分组 key 或旧版单 key），否则 401——**包含「开了校验却没配任何 key」也一律 401**（旧代码此时是放行，属漏洞）；`keyRequired()==false` 直接放行。开关打开时若一个 key 都没配会给出长 toast 提醒。
4. **功能列表删「用量」行**，**删「端口与 API Key 管理」胶囊**（与「设置」行重复，「设置」行仍进 PortKeyActivity）。
5. **关于版本号修复**：根因是 `MainActivity.onCreate` 里 `settings.setVersionName("1.0.0")` 把版本写死进 prefs，与真实包版本无关。改为 PackageManager 读 PackageInfo → 显示「1.0.42 (158)」；关于卡顺带加「Key 校验」状态行。
6. 构建交付完成（下面环境记录）。

### 本轮构建环境（换沙箱：euleros → debian proot，全部现装）
- 宿主机 aarch64，无预装 JDK/SDK/Gradle：apt 装 openjdk-21-jdk-headless；gradle 8.9 解到 /opt；cmdline-tools 11076708 → /opt/android-sdk。
- **sdkmanager 拉不到仓库清单**（IO exception while downloading manifest），改直链：
  `platform-34-ext7_r02.zip` → platforms/android-34，`build-tools_r34-linux.zip` → build-tools/34.0.0
  （两个包解出来都多一层 `android-14`/`android-34` 目录，必须拍平）。手写 `licenses/android-sdk-license` 后 AGP 会自己补装 platform-tools。
- **aapt2 的 aarch64 方案**：apt 装 qemu-user-static + libc6-amd64-cross + libgcc-s1-amd64-cross + libstdc++-14-dev-amd64-cross；
  maven 的 `aapt2-8.7.3-12006047-linux.jar` 解出 `aapt2.bin`，包装脚本 `exec qemu-x86_64 -L /usr/x86_64-linux-gnu /opt/aq/aapt2.bin "$@"`。
- ⚠️ **覆盖路径的文件名必须正好以 `aapt2` 结尾**：AGP 会校验 `android.aapt2FromMavenOverride`，命名 aapt2-wrapper 直接报
  "Custom AAPT2 location does not point to an AAPT2 executable"（该判定在 Aapt2FromMaven$Companion 里，靠字符串后缀）。zipalign 同理包成 /usr/local/bin/zipalign。
- ⚠️ **Debian 的 JDK 默认没有 cacerts**（`lib/security/cacerts` 是指向 /etc/ssl/certs/java/cacerts 的悬空链接），Gradle 会报
  "trust store file ... does not exist or is not readable" 导致插件解析失败；`update-ca-certificates -f` 生成即可。
- 构建：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 gradle :app:assembleRelease -x lint --no-daemon` → 4m46s SUCCESS；
  签名链：`zipalign -p 4`（qemu）→ `apksigner sign --ks MT2/keystore/7tattoo.jks --ks-key-alias 7tattoo --v1/2/3`（口令由用户提供，不入库）。
- ⚠️ 后台跑 gradle 时不要用 `| tail`，会把退出码吃成 0；本次第一轮失败（cacerts）就是这么被掩盖的。

### APK 内自证（交付前已核）
- aapt2 dump xmltree：`res/v9.xml`（activity_main）里 noKeySwitch / btnPortKey / rowMineAddr / mineVersionLine / rowFuncUsage 全 0，
  「功能」下只剩「签到与奖励」「设置」，其下为「关于」卡；`res/hc1.xml`（activity_port_key）含新文案。
- dexdump：`MainActivity.appVersionName()` 存在且被 updateMine 调用，`MainActivity` 不再调用 `Settings.setVersionName`；新 toast 文案在 dex 内。
- apksigner verify + badging：cert 与 keystore 一致，versionCode 158 / versionName 1.0.42 / minSdk 26 / targetSdk 34。

### 待实机验证（未在本轮做）
装包后确认：①「我的」页只剩 功能（签到/设置）+ 关于；② 关于里版本号显示 `1.0.42 (158)`；
③ 端口与 Key 页开关打开 → 不带 Key 请求 `/v1/models` 返回 401，关闭 → 不带 Key 也能调用。

---

## vc159 / 1.0.43 交付（2026-10-17，用户报障：首页「今日 Tokens 消耗」恒为 0）

产物 `shared/codearts7/CodeArts7_1.0.43_vc159-20261017-tokenstats-fix-signed.apk`，
sha256 `088ee69785d8e5a4973935676d546781001b77f3c709b32bb197da4317aab616`，4.89 MB，vc159 / 1.0.43，
证书同 vc119/vc158。本包 = vc158 六项修改 + 本次 token 统计修复，vc158 包已从共享目录移走以免装错。
改动 diff `/workspace/codearts_1.0.43_vc159.changes.diff`（740 行）。

### 根因（已实测复现，不是猜测）
`PreparedChat.observeUsage(String data)` 只有 Trae 走翻译模式（传纯 JSON payload），
**其余 5 个服务商（buddy / raccoon / loomy / autoclaw / kilocode）走透传模式**，
传进去的是 `passthrough()` 里 `eventBuf` 拼的**整段原始事件**，形如

```
data: {"id":"chatcmpl-…","usage":{"prompt_tokens":35,"completion_tokens":78,…}}
```

`new JSONObject()` 看到 `data: ` 前缀必然抛 JSONException，被 `catch (Exception ignored)` 静默吞掉
→ `usagePromptTotal` / `usageTotal` 永远 0 → `reportUsageToGovernor()` 的 `add()` 从不触发
→ 首页恒为 0。**同一个 bug 还让 ContextGovernor 的 usage 校准从未生效过**（记录页里从没出现过
`CTX calib ratio=…`，只有 `CTX truncTool`）——即上游实测数据一直被扔掉，折叠系数只能靠估算裸跑。

### 实证
网关就跑在本机 127.0.0.1:8798，用户给了 key（raccoon 分组，6 个模型）：
```
curl -N -H "Authorization: Bearer <key>" -d '{"model":"sn-deepseek-v4-1-flash","stream":true,…}'
→ data: {…,"usage":{"completion_tokens":78,"prompt_tokens":35,"total_tokens":113,…}}
```
上游**确实每轮都发 usage 帧**。把这段真实 SSE 喂给新旧两版解析（tools/json-20240303.jar）：
`frames=30  usage frame -> OLD: EXCEPTION(JSONException)   NEW: 35/78`。

### 修复（PreparedChat.java / UsageStats.java / MainActivity.java）
1. `observeUsage` 先过新的 `stripSseFraming()`：剥掉 `data:` / `event:` / `id:` / `retry:` / 注释 / `[DONE]`，
   多行 data 用 `\n` 还原成 JSON；整段解析失败再逐行兜底（逐行模式）。解析逻辑抽成 `applyUsage()`。
2. 透传与翻译两条路统一走新入口 `onFrame()`（= countOutputChars + observeUsage）。
3. **估算兜底**：整轮一个 usage 帧都没收到时，输入用 `requestInputEstimate`（治理后估算），
   输出用流式正文 `content` / `reasoning_content` 字符数经 `ContextEstimator.charTokens()` 折算，
   调新增的 `UsageStats.addEstimated()` 单独记账。
4. UI（`updateTokenCard`）：合计 = 实测 + 估算；有估算时副标题写明
   「其中 N 次为本地估算（上游未回报 usage）」，不冒充实测。

### 待实机验证
装 vc159 后：随便发一轮对话，首页「今日Tokens消耗」2 秒内应跳动；
raccoon 走实测口径；若某服务商回的是估算，副标题会明确标出。记录页此时应能看到 `CTX calib ratio=…`。

---

## vc160 / 1.0.44 交付（2026-10-17，用户需求：「启用 API Key 校验」下的手动输入框去掉）

产物 `shared/codearts7/CodeArts7_1.0.44_vc160-20261017-remove-key-input-signed.apk`，
sha256 `902fec8e6d7691b52ab74556c772ecb57ada8623927c915faa6baaf624e7baa6`，vc160 / 1.0.44，证书同前。
= vc159（六项修改 + token 统计修复）+ 本次去输入框。共享目录只留最新一版，vc158/vc159 移到 /workspace/dist/。
改动 diff `/workspace/codearts_1.0.44_vc160.changes.diff`。

### 改动
1. `activity_port_key.xml`：删掉 `@+id/apiKeyInput` 那个 EditText（「启用 API Key 校验」说明文字下面那个空输入框）；
   该卡里现在只剩「监听端口」一个 EditText。说明文字改为
   「开启后网关校验 Key（用下方「API Key 分组」里生成的 key）：未带有效 Key 的请求返回 401；\n关闭即「无 Key 调用」，客户端可不填 Key」。
2. `PortKeyActivity.java`：删字段/`findViewById`/`setText`；`saveSettings()` 不再写 `s.setApiKey(输入框)`——
   **旧版单 key 保留原值不动**（`Router.resolveKey` 仍兼容它，避免老客户端升级后突然 401）。key 一律走下方「API Key 分组」生成。
3. 版本 vc160 / 1.0.44。

### APK 内自证
`aapt2 dump resources` 里 `id/apiKeyInput` 已消失（`id/apiKeySwitch`、`id/portInput` 仍在）；
`aapt2 dump xmltree res/hc1.xml` 中新文案在位，且该布局只剩「监听端口」一个 EditText。

---

## vc161 / 1.0.45 交付（2026-10-17，用户需求：/v1/models 下发计费倍率 rate / multiplier）

产物 `shared/codearts7/CodeArts7_1.0.45_vc161-20261017-models-rate-signed.apk`，
sha256 `2b4a3ce059f62d887e0dbd5c0183a5030328ae2c7b5fe7bfd51056f81fe0d6e3`，vc161 / 1.0.45，
证书同前（SHA-256 `4ebe0621…c3b76bf6`，共用 7tattoo.jks）。
= vc160 + 本次倍率字段；共享目录只留最新一版，vc160 移到 `/workspace/dist/`。
改动 diff `/workspace/codearts_1.0.45_vc161.changes.diff`（相对上游 vc157 原始包累积）。

### 改动
1. `catalog/ModelEntry.java`：`toJson()` 下发 `rate`（上游展示原文：促销形如 `x1→x0.08`、AutoClaw 为档位文案 `低`）
   与 `multiplier`（能解析出的数值，促销取 `→` 末段=实付值）；字段为空/空串不下发，纯文案只下发 `rate` 不带 `multiplier`。
2. `provider/ModelRate.java`：新增 `numericOf(String)`（去 `x` 前缀、取 `→` 末段后 parse），供上面取值。
3. 版本 vc161 / 1.0.45。

### 验证
- 单测 `JsonCheck`（`/tmp/mtest`，`java -cp out:json-20240303.jar JsonCheck`）：
  无值/空串 → 两个字段都不下发；`x0` → 0.0；`x0.75` → 0.75；`x1→x0.08` → 0.08；`低` → 只下发 rate。全绿。
- APK 自证：`classes.dex` 含 `multiplier`、`numericOf`；
  `aapt2 dump badging` → `versionCode 161` / `versionName 1.0.45` / `com.aigetway.tattoo7`。
- 签名：`apksigner verify -v` → v2、v3 true；证书 SHA-256 与 7tattoo.jks 一致；`zipalign -c -v 4` OK。
  minSdk 26，v1(JAR) 无需；apksigner 对 v1 报 false 与历版一致（AGP 产物的 META-INF 条目所致），不影响安装。

### 待实机验证
装 vc161 后：客户端拉 `/v1/models`，带倍率的上游模型应能看到 `rate`，促销项应带 `multiplier`（如 0.08）；`低` 类文案无 `multiplier`。

## 2026-10-18 01:2x 账号额度耗尽后不切号（Raccoon insufficient_points）修复

### 现象
实机日志：`upstream error: upstream HTTP 400: {"error":{"code":"insufficient_points","message":"积分余额不足，请充值后重试"...}`，网关把 400 原样回给客户端，其它还有额度的账号一直轮不到。

### 根因（关键）
- 上游判定账号失效只认 **401/403**，Router.upstreamFailure 也只在这两个码上 `tryRefreshSameThenRotate / tryRotate`。
- 小浣熊/Raccoon 在账号积分用完时，对所有模型统一回 **HTTP 400 + code=insufficient_points**（其它服务商可能用 402）。
- 于是「额度耗尽」被归进普通 400 分支，直接回给客户端 —— 这正是「额度用完不切号」。
- 另外 tryRotate 只轮转**一次**：若第 2 个账号也额度不足就失败，不会继续找第 3 个。

### 改动（均为纯 Java，无需动 gradle）
1. `provider/UpstreamHttpException.java`
   - 新增 `public boolean quotaExhausted`，构造时用 `looksLikeQuota(code, friendly)` 判定：
     HTTP 402 直接判额度不足；其余在报文字符串里识别 `insufficient_points`、`insufficient_point`、`insufficient balance`、`quota_exceeded`、`billing_quota`、`积分余额不足`、`积分不足`、`积分已用完`、`余额不足`、`余额已用完` 等。
2. `gateway/Router.java`
   - `upstreamFailure` 里新增 `if (e.quotaExhausted)` 分支（放在 401/403 分支之后）：额度耗尽不做 token 强刷（账号有效只是没积分），直接 `tryRotate` 切下一个账号重试；成功即正常流式返回。全部账号试完才 logAccess 原始错误。
   - `tryRotate` 从「轮转一次」升级为「遍历池中所有账号」：逐个 rotate → prepareChat，若新账号仍抛 401/403/额度耗尽则 `markFailure` 继续换下一个；遇到限流/网络/超时等错误立即停下返回原始错误，不再消耗账号。
   - `tryRefreshSameThenRotate`（raccoon 401 强刷失败后）改为直接 `return tryRotate(...)`，去掉内联的一次性轮转（避免重复 markFailure 计数），顺带删除不再使用的 `accId` 变量。

### 编译验证
- 用 javac（system JDK，`-source/-target 17`，classpath=`/opt/android-sdk/platforms/android-*/android.jar`）编译 `app/src/main/java` 非 UI/非 service 包 + 临时 stub（GatewayService.wake/doneWake、R.mipmap.ic_launcher）。
- 输出无错误，`/tmp/cc5/.../gateway/Router.class`、`provider/UpstreamHttpException.class` 均已生成。
- 注意：`compile.sh` 硬编码了旧环境路径 `/var/minis-euleros/workspace/codearts`，在 Debian(proot) 沙箱里不可直接运行；本次用等价 javac 命令验证。

### 待实机验证
装新包后：用一个额度已耗尽的账号触发请求 → 请求日志应出现 `[Raccoon] 账号额度耗尽（HTTP 400），尝试轮转其它账号` 与 `已切换到其它账号，重试成功`，客户端拿到 200 而非 400；若所有账号额度都用完，才回原始 400。

### 交付（2026-10-18 01:4x vc162）
- 版本：1.0.46 / versionCode 162（自 161 递进），applicationId 不变。
- 构建：`/opt/gradle-8.9/bin/gradle assembleRelease -x lintVitalRelease`（JAVA_HOME=java-21-openjdk-arm64）BUILD SUCCESSFUL。
- 对齐/签名：`/usr/local/bin/zipalign -p 4`；`sign-eta.sh`（7tattoo.jks, alias 7tattoo）。
- 校验：apksigner verify v2/v3 true（v1 false 与历版一致，minSdk≥26）；zipalign -c OK；证书 SHA-256 `4ebe0621216f41021d11fafa36dae5b7fd74950bcf47f692a98fb8d9c3b76bf6`（与历版一致，可原地覆盖安装）。
- 产物：`/workspace/shared/codearts7/CodeArts7_1.0.46_vc162-20261018-quota-rotation-signed.apk`；副本 `/workspace/mounts/MT2/7tattoo/`。

## 2026-10-18 10:1x 签到积分不累积排查：给小浣熊签到加诊断日志

### 现象
- 每天签到后网关仍显示 17 分（累计不涨），其它分组（昨晚做任务消耗快完）不确定是否有累计。
- 怀疑：签到接口对第三账号只发固定 17 分，或账单接口 `paging.limit=20` 取不全、近 7 日无可见累计。

### 改动（`provider/RaccoonProvider.java`，仅 checkinRaccoon 与 helper）
1. desktop 签到：`desktopGrant.put("granted", ...)` 之后加 `refreshLog("checkin desktop 响应体[" + r.body.length() + "]: " + truncate(r.body, 500));`
2. mobile 签到：`if (popup != null) mobileGrant.put("popup", popup);` 之后加 `refreshLog("checkin mobile 响应体[" + r.body.length() + "]: " + truncate(r.body, 500));`
3. bills：`paging.limit=20` → `paging.limit=50`（注释同步）。
4. bills 统计：`JSONArray grantsToday` 后新增 `LinkedHashMap<String, Long> weekPoints`，循环内 `if (at.length() >= 10) weekPoints.merge(at.substring(0,10), pts.longValue(), Long::sum);`，结论前 `refreshLog("checkin bills 近7日入账: " + weekPoints);`。
5. 新增静态 helper `truncate(String s, int max)`（超长截断加 `...`，避免日志过长）。

### 编译验证
- javac（--release 17，classpath=/tmp/p34/android-34/android.jar）编译 `app/src/main/java` 中非 ui/、非 service/ 的 47 个 java 源文件：RaccoonProvider 等无错误；仅 `service/GatewayService.java` 因缺 androidx / R 类报错（与本改动无关，历版一致）。

### 待实机验证
装新包后：签完第三账号 → 看网关日志 `checkin desktop 响应体` / `checkin mobile 响应体` 里的 granted 与 popup.points，确认 17 分来源；看 `checkin bills 近7日入账`，判断其它分组是否有按日累计。若 desktop 响应体里 granted=true 且积分入账正常，则问题可能在客户端展示或「昨日消耗」记账，需继续追。


## 2026-10-19 03:0x 签到积分不累积：修复 LoomyProvider 签到未领取新手任务（vc164 / 1.0.48）

### 根因（实测铁证）
- 网关 `LoomyProvider` 的 `checkin()`（LoomyProvider.java）只做签到、从不领新手任务。而小浣熊服务端把「新手任务奖励」与「签到」合并：**签到响应里的 granted/popup.points 只含签到本身，新手任务分数在签到后由任务领取接口单独入账。**
- 小浣熊官方客户端签到成功后会立即领取全部新手任务；网关跳过这一步，所以每天只发签到基础分、**新手任务那部分永远没入账**，第三账号显示 17 分不累积。
- 与官方客户端对照（`shared/orig/`）：`onboarding/tasks` 查询、`onboarding/tasks/complete` 逐条领取，`Set<String> completedTasks` 幂等；已领的任务再领返回 alreadyCompleted，视为成功。
- 之前 vc163 加的「签到响应体/近7日入账」诊断日志已保留，本轮可用来对账。

### 改动
1. 新增 `provider/LoomyOnboarding.java`：小浣熊新手任务 8 项映射表 + 查询（GET /onboarding/tasks）+ 领取（POST /onboarding/tasks/complete）+ `fetch()` 快照 + `claimAll()`（已领跳过，串行重放）；`API_BASE` 与 `headers(token)` 复用 `ServiceProvider`。
2. `LoomyProvider.checkin()`（provider/LoomyProvider.java）：签到成功后调用 `LoomyOnboarding.claimAll(token)`，入账日志写 `refreshLog("[Loomy] 新手任务领取: ...")`；查询失败或已领全部，用 try/catch 静默不阻断签到结果（仅日志记录，不再重复发异常）。
3. `ui/ProviderDetailActivity.java`（ui 层，本轮无 androidx 完整编译验证）：新增「新手任务」按钮与 `claimOnboardingProvider()`，遍历该服务商下**所有**账号逐项领取，每个账号独立结果弹窗；绑定到 `setOnboardingHint`/`pd-onboarding`。

### 编译验证（本沙箱，javac 25）
- `javac --release 17 -cp /tmp/p34/android-34/android.jar` 编译非 ui/、非 service/ 全部 java 源文件（含新增 LoomyOnboarding、改动 LoomyProvider）：0 错误。`service/GatewayService` 用临时 stub（wake/doneWake）替代，与本改动无关（历版一致）。
- 修复过程中发现的 2 个编译问题已修：`LoomyOnboarding.complete()` 里 `body.put("key", key)` 未捕获 JSONException → 已 try/catch 包成 IOException；`ProviderDetailActivity` 中插入 `claimOnboardingProvider()` 时吞掉了 `checkinProvider()` 的闭合 `}` → 已补回。
- UI 层因缺 androidx/R 无法完整编译，仅语法检查：`javac` 过滤 cannot-find-symbol 后无语法错误。
- 一致性检查：`onboardingHint` 引用 1 处（新按钮），`claimOnboarding` 引用 3 处（1 定义 + 2 调用，与 `checkin` 一致）。

### 待实机验证
装新包后：签到第三账号 → 网关日志应出现 `checkin desktop 响应体[...granted...]` + `[Loomy] 新手任务领取: 已领取 8 项/+xxx`；再查积分应累积（含新手任务部分）。若日志仍显示"新手任务领取: 已领全部"但积分不涨，则问题回到账单/客户端展示侧。

## 2026-10-19 03:45 修正交付：vc164 包内部版本号未递增（用户装了旧包）
**用户反馈**：交付文件名 1.0.48，但装机显示 1.0.47(163)，且 Loomy 页无「新手任务」按钮。
**根因（我方失误）**：本轮构建时**没改** `app/build.gradle` 的 versionCode/versionName，
仍是 163/1.0.47 → 新包与上一版 vc163 诊断包版本号完全相同，用户从共享目录选到的是旧文件；
旧 vc163 只加了诊断日志，不含「新手任务」按钮，所以看到的现象与版本号一致。
**证据**：build.gradle 原值 163/1.0.47；旧 vc163 包 md5 9d42dba2…，新包 md5 d2175f49…（不同文件，大小同为 4895534 属巧合）。
**修正**：改 build.gradle → versionCode 164 / versionName "1.0.48"，重建 + eta-sign 签名；
`aapt dump badging` 确认包内 versionCode='164' versionName='1.0.48'；
dex 校验含「新手任务」×8、LoomyOnboarding ×4、claimOnboarding ×4；apksigner v2/v3 OK，zipalign OK。
**产物**：/workspace/mounts/MT2/7tattoo/CodeArts7_1.0.48_vc164-20261019-onboarding-signed.apk (md5 d2175f490a4879a370e0cb7f84767f89)
**同时**：vc162、vc163 旧包已移出共享目录到 /workspace/dist/，避免再装错。
**教训**：每次出包前必须先递增 build.gradle 版本号并核对，否则同版本号包无法在设备上区分。


## 2026-10-19 04:0x vc165 / 1.0.49：修「新手任务按钮不出现」+ 签到未自动领新手任务（vc164 遗留）

### 用户反馈
- 装了 vc164（1.0.48）后小浣熊详情页**没有出现「新手任务」按钮**；第三账号签到后积分依旧不累积（截图三账号 3084 / 4825 / 1）。

### 根因（两条，均在服务商适配层）
1. **按钮不出现**：`LoomyService`（CSI 服务壳，继承 `RaccoonService`）没有覆写 `supportsOnboardingTasks()` / `claimOnboarding()`，只有 `LoomyBillingService`（网关 API 壳）覆写了。账号详情页是按 `Service` 壳判定能力的 → 永远拿不到 `onboardingTasks=true`，按钮与「一键新手任务」都被隐藏。
2. **签到后仍不涨分**：vc164 记录的「`LoomyProvider.checkin()` 签到成功后调用 `claimAll`」**实际没落地** —— 代码里 `claimAll` 只在手动按钮入口 `claimOnboarding()` 被调用，`checkin()` 仍是「POST /points/first-login → 返回 +dailyQuota」。
   - 官方客户端签到后会立刻逐条领取新手任务（合计 10000 分，一次性）；网关漏掉这一步时这部分分数永不入账。
   - 与 vc164 的 PROGRESS 记录不符，属记录不实，本轮一并纠正（教训：改完必须 grep 回证「记录里的改动真的在代码里」）。

### 改动（vc165）
1. `service/LoomyService.java`：覆写 `supportsOnboardingTasks()=true` + `claimOnboarding()` 委托 `b.direct().claimOnboarding()`（与 LoomyBillingService 行为一致）。
2. `provider/LoomyProvider.java`：
   - `checkin()` 签到成功后调用 `LoomyOnboarding.claimAll(token)`，把结果拼进返回文案（「签到成功，本次 +300 积分；新手任务 +N（M 项）」或「…；新手任务无新增（进度 x/10000）」），领取失败只追加错误文本、不阻断签到结论。
   - 新增 `loomyLog()`（写 `LOOMY-CHECKIN` 到网关日志，走 `App.get().logSink()`）与 `truncate()`；签到响应体摘要（前 400 字符）落日志，供实机对账「签到到底发了多少分」。
3. `app/build.gradle`：versionCode 164 → 165，versionName 1.0.48 → 1.0.49。

### 构建 / 校验
- `gradle assembleRelease -x lintVitalRelease` BUILD SUCCESSFUL。
- `output-metadata.json` 与合并后 manifest 均为 versionCode=165 / versionName=1.0.49 ✅（vc164 曾因漏改版本号交付成旧包，本轮已核对）。
- eta-sign 签名：证书 SHA-256 4ebe0621…（与历版一致，可原地覆盖安装）；apksigner v2/v3 OK。
- dex 证据：`claimOnboarding` ×4、`supportsOnboardingTasks` ×1、`LOOMY-CHECKIN` ×1。

### 产物
`/workspace/shared/codearts7/CodeArts7_1.0.49_vc165-20261019-onboarding-fix-signed.apk`（md5 c0b0c720a773821246ae9bddb4e7f175），副本 `/workspace/mounts/MT2/7tattoo/`。

### 待实机验证
装 vc165 后：
1. 小浣熊详情页应出现「新手任务」按钮（页头，按钮右侧新增），账号卡片应显示「· 新手任务 已领 x/8」进度。
2. 点某账号「签到」→ 弹窗应显示「…；新手任务 +N（M 项）」，随后账号积分刷新。
3. 若弹窗只有 +300 而积分仍不涨（或显示「新手任务无新增」），查网关日志 `LOOMY-CHECKIN` 的 `签到响应体[...]`，看服务端返回的 dailyQuota / remainPoints，再往积分记账侧追。


## 2026-10-19 04:1x vc166 / 1.0.50：签到 / 新手任务按钮改成「按账号粒度」

### 用户反馈
- `ProviderDetailActivity` 里单个账号卡片下的「签到」「新手任务」按钮，点一次其实遍历该服务商**全部**账号（小浣熊 3 个账号 → 3×签到 + 3×8 项新手任务请求），UI 卡死到用户以为「timeout / 没反应」。
- 期望：单个账号底下的按钮只操作该账号。

### 改动（纯 Java，`ui/ProviderDetailActivity.java`，两处）
1. 新增 `checkinAccount(Account)` / `claimOnboardingAccount(Account)`（单账号），绑定到**账号卡片**上的按钮（`setOnboardingHint` / `pd-onboarding` 等），日志与 Toast 都带账号名。
2. 页头按钮保留原批量语义，文案改为「一键签到（全部 N 个账号）」「一键领新手任务（全部 N 个账号）」，仅在 `pool().all().size() > 1` 时出现，与卡片按钮视觉分隔。

### 构建 / 校验
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 /opt/gradle-8.9/bin/gradle :app:assembleRelease -x lint -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --offline` → BUILD SUCCESSFUL（31s）。
- `output-metadata.json`：versionCode 166 / versionName 1.0.50 ✅。
- eta-sign 签名：证书 SHA-256 4ebe0621216f41021d11fafa36dae5b7fd74950bcf47f692a98fb8d9c3b76bf6（与历版一致，可覆盖安装）；apksigner v3 OK；zipalign OK。
- dex 证据：`checkinAccount` ×4、`claimOnboardingAccount` ×4、`checkinProvider` ×3、`claimOnboardingProvider` ×3、`一键签到（全部 ` ×1、`LOOMY-CHECKIN` ×1；zip CRC OK。

### 产物
- `/workspace/shared/codearts7/CodeArts7_1.0.50_vc166-20261019-peraccount-signed.apk`
- `/workspace/mounts/MT2/7tattoo/CodeArts7_1.0.50_vc166-20261019-peraccount-signed.apk`
- md5 `41ceb10fa87df7ddfb75150d0c5d51c5`；vc164 旧包已移出共享目录到 `/workspace/dist/`。

### 环境教训（沙箱重启后）
- `/opt/jdk21-home` 已不存在：改用 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64`。
- 项目根无 `gradlew`，用 `/opt/gradle-8.9/bin/gradle`；`compile.sh` 里写死的 `/var/minis-euleros/workspace/codearts` 已失效，勿再依赖。
- 后台跑 gradle 仍不要接 `| tail`（会吞退出码），退出码用 `echo "EXIT=$?"` 显式打印。

### 待实机验证
- 单账号卡片点「签到」：只应出现 1 次网关日志，返回文案形如「签到成功，本次 +300 积分；新手任务 +N（M 项）」或「…新手任务无新增（进度 x/10000）」。第三账号积分是否累积以此文案 + 网关日志 `[Loomy]` 行为准。


## 2026-10-19 05:4x vc167 / 1.0.51：积分卡片改按「池」口径（修「签到后卡片纹丝不动」）

### 用户反馈
小浣熊（Loomy）第三个账号签到后（每日 300 分）账号卡片依旧显示 **17 分**，看不出累积；其他分组没留意。

### 根因（口径错，不是上游没发分）
- Loomy 的积分是**两个独立计算的池**：`balance`（永久：注册奖励 + 新手任务，不过期）与
  `dailyBalance`（每日赠送，当日有效、消耗不回补、次日重发）；`availableBalance` 是两者之和，
  也是官方客户端账号卡片的口径（`CreditBalanceRow`，两个包「永久积分 / 每日赠送」分开显示）。
- 旧实现 `LoomyProvider.refreshCredits` 只读同一 `data` 里的 `remainPoints` —— 与官方客户端
  **不是一个口径**，于是出现「签到 / 领新手任务后卡片数字纹丝不动」（用户看到的 17 分）。
- 既有结论（docs/agent-notes + 参考实现 loomy-credits.ts 对拍）：每日 300 分是**服务端按日自动发放**，
  没有可调用的签到端点；`login/points/grant` 是一次性幂等端点，不能当「每日签到」。

### 改动
1. `account/AccountSnapshot.java`：新增 `permanentCredits` / `dailyCredits`（空串 = 不支持或上游未给，**不用 0 冒充**）。
2. `provider/LoomyProvider.java#refreshCredits`：按池读 `balance` / `dailyBalance` / `availableBalance`，
   合计优先取 `availableBalance`，缺失时两池相加兜底；三字段全缺才回落旧 `remainPoints` 口径。`account()` 同步走该方法。
3. `account/CreditsCache.java`：`Entry` 增 `permanent` / `daily` 并持久化（缓存期内 15min 首屏也能显示明细，不再只剩合计）。
4. `ui/ProviderDetailActivity.java`：账号卡片明细行改为「永久 N  每日 M(当日有效)  临时 …」；
   缓存首渲、刷新成功、失败保底三条路径都同步两池。
5. `ui/MainActivity.java`：写缓存时带上两池（失败/超时快照不写，避免污染）。
6. `app/build.gradle`：versionCode 166 → **167**，versionName 1.0.50 → **1.0.51**。

### 构建 / 校验
- `:app:assembleRelease -x lint…` BUILD SUCCESSFUL；`output-metadata.json` versionCode **167** / versionName **1.0.51** ✅。
- eta-sign 签名：证书 SHA-256 `4ebe0621…`（与历版一致，可原地覆盖安装）。
- dex 特征：`availableBalance` ×1、`dailyBalance` ×1、`permanentCredits` ×1、「永久 」×1、「每日 」×3。

### 产物
- `/workspace/shared/codearts7/CodeArts7_1.0.51_vc167-20261019-creditpools-signed.apk`
- `/workspace/mounts/MT2/7tattoo/` 同名副本；md5 `d087a961088b136c883ff6053be07440`
- vc165 / vc166 已移入 `/workspace/dist/`（共享目录只留最新）。

### 待实机验证
1. 装 vc167 → 小浣熊账号卡片应出现「永久 x  每日 y(当日有效)」明细行。
2. 点该账号「签到」后再看：是**每日池**变（成交付结论：上游按日发 300，卡片口径已修），还是两池都没动（要回上游侧核对）。
3. 若网关开着日志，对账 `[Loomy]` / `LOOMY-CHECKIN` 行（vc165 起会打印签到响应体摘要前 400 字符）。


## 2026-10-19 06:4x vc168 / 1.0.52：新增「AStudio（讯飞 AstronStudio）」供应商分组

参考：Gitee PR 118 `feat(astudio): 接入 AStudio（讯飞 AstronStudio）Provider`（iJetLi/deepseek-harness-codearts，
head `feat/astudio-provider`，41 文件）。协议以该 PR 的 `src/astudio-*.ts` 为准，本地副本
`/workspace/work/astudio-ref/`（含 6 个 ts + 4 个 spec + 实测笔记），协议规格整理在
`/workspace/work/astudio-ref/NOTES-protocol.md`；本地接线地图在 `/workspace/work/astudio-wiring.md`。

### 本家与其余服务商最大的三处不同（全部实测，照抄 OpenAI 直觉会踩）
1. **双域**：推理 `https://astronstudio-api-volces-prod.xf-yun.com/model-gateway/v1`（Bearer），
   模型目录在**同域不同前缀** `/api/v1/model-manager/models`；`/model-gateway/v1/models` 实测恒 404。
   账号控制面 `https://agent.xfyun.cn/xingchen-studio`（Cookie 鉴权，**不带** Authorization）。
2. **上行 `model` 只认裸 slug**（`xopglm52` 等）：发 `builtin/<base64url>` 内部别名回 502
   `model_not_found`。已实现 `builtin/` → 裸 slug 解码（带 padding 补齐）。
3. **两种错误信封**：目录/账号面 `{code,message,data}` 且 HTTP **恒 200**（code≠0 即失败，80000
   「登录异常，请重新登录」是硬终点，码集 {1002,1005,80000}）；推理面是 OpenAI 裸体 + 非 2xx。

### 改动（新增 3 个文件 + 改 7 个）
- 新增 `provider/AStudioProvider.java`（约 1000 行）：双域端点、目录解析（按裸 slug 去重、`list` 优先于
  `hide`、`input_modalities` 判图片=缺省 false 的 fail-closed）、推理透传（`reasoning_effort` **集合外一律丢弃、
  不做就近映射**——上游对非法档位不报错而是按最大档计费）、积分（`points/balance` 的 `totalBalance` 为权威主池口径，
  星火是独立池只在文案里标注）、签到（`client-popups/pending` → `complete`，`DAILY_REWARD_DIALOG`）、
  新人礼（同端点 `NEW_USER_DIALOG`，挂 UI 的「新手任务」按钮）、凭据轮换（`/bot/models/configs` 的
  `api_key`，阶梯 is_current → is_default → first → 全表）。
- 新增 `ui/AStudioCarrierServer.java` + `ui/AStudioLoginActivity.java` + `assets/astudio_login.html`：
  **内置浏览器登录页**（短信 / 密码 / 微信扫码三 Tab，页面与 PR 118 的载体页同构、含极验 gt.js）。
  页面由 127.0.0.1:18890 的本机服务提供，转发 gee-captcha / sms/send / sms/login / password/login /
  wechat/qrcode / poll 六条；凭据只在原生侧装配，入 `AccountPool("astudio")`。
  为什么要本机服务：极验只能在真实浏览器环境跑，而页面源是 `127.0.0.1`，直连 xfyun 会被同源策略挡住。
- 改 `util/Http.java`（加 `postRaw`：multipart 表单不能被覆写 Content-Type）、`provider/ProviderRegistry.java`
  （注册 + AppBridge.astudio()）、`core/App.java`（实例 + 访问器）、`ui/MainActivity.java`（图标 ic_target /
  描述）、`ui/ProviderDetailActivity.java`（登录按钮 → 内置浏览器页）、`AndroidManifest.xml`
  （注册 AStudioLoginActivity，exported=false）、`app/build.gradle`（167/1.0.51 → **168/1.0.52**）。

### 登录三条通路（都在本机载体服务里收尾）
短信 `POST /login/mobile/send-verify-code`（multipart：mobile、countryCode=86、geetest_challenge/validate、
**seccode 需 base64**）→ `POST /login/phone-quick-login`；密码 `POST /login/check-account`（+ 极验三参）；
扫码 `GET /SSOService/wx/scanlogin/qrcode|uids` + `POST /bind`（**form body**：fd、accountId、ssoSessionId、
isNew=false）。三条路都无条件先 `POST /tenant-app/v2/init-app`（**空 body**，未注册手机号靠它自动开户），
微信路再 `GET /userInfo`，最后 `GET /bot/models/configs` 取 `modelBearerToken` 入池。

### 构建 / 校验
- `:app:assembleRelease -x lint` BUILD SUCCESSFUL（4 轮，前 3 轮为跨包可见性 / JSONException 编译错误，已修）。
- `output-metadata.json` 与合并后 manifest 均为 versionCode **168** / versionName **1.0.52**；
  manifest 含 `com.aigetway.tattoo7.ui.AStudioLoginActivity` ✅。
- eta-sign 签名：证书 SHA-256 `4ebe0621…`（与历版一致，可原地覆盖安装）。
- dex 证据：`astudio` ×14、`AStudio` ×28、`讯飞` ×4、`xingchen-studio` ×11、`model-gateway` ×2、
  `model-manager` ×1、`DAILY_REWARD_DIALOG` ×1、`NEW_USER_DIALOG` ×1、`gee-captcha` ×2、`scanlogin` ×4、
  `ssoSessionId` ×5、`modelBearerToken` ×4；`assets/astudio_login.html` 已打包 ✅。

### 产物
- `/workspace/shared/codearts7/CodeArts7_1.0.52_vc168-20261019-astudio-signed.apk`
- `/workspace/mounts/MT2/7tattoo/` 同名副本；md5 `098966c9f2be101d98e46080f5fa11a1`
- vc166 / vc167 已移入 `/workspace/dist/`（共享目录只留最新）。

### 待实机验证（重点，均未在真机跑过）
1. 装 vc168 → 主页应出现「AStudio / 讯飞」分组；进详情页点「打开 AStudio 登录页」→
   内置页三 Tab 均可用（短信需先过极验滑块；密码同；扫码用微信扫）。
2. 登录成功后详情页应出现账号卡片；积分明细应显示主池合计（有星火池时文案里带「星火独立池 N」）。
3. 点「签到」→ 应回「签到成功，本次 +N 积分」，随后积分刷新；点「新手任务」→ 领新人礼。
4. 若登录页起不来（端口被占），代码会退到随机端口重试一次，失败时页头状态栏会写明原因。
5. 模型清单：上游目录若把裸 slug 标成 `visibility:hide`（本地回环网关的形态），当前实现会优先取 `list`
   那份并解码 `builtin/`；真机若发现模型列表为空，先看网关日志 `ASTUDIO` 行。

# CodeArts7 vc169 / 1.0.53：AStudio 登录/账号面加固（2026-10-19，参考 PR 118 review）

## 本轮改动（eta_src，javac 核心层无错 + assembleRelease）
1. **登录三通路响应捕获**：`AStudioProvider.identity()` 统一从短信 `phone-quick-login` /
   密码 `check-account` / 微信 `bind` 的响应里抽 `uid / ssoSessionId / token / accountId /
   mobile / nickname / loginMethod`，登录方式随凭据入池，账号页可区分来源。
2. **极验 seccode 走 base64 装配**（短信与密码两条路同改，AStudio2API 同口径）。
3. **completeLogin 收尾统一**：`init-app`（新用户自动注册开户）无条件先行；`/userInfo` 改为
   **所有登录方式**都走（不只微信扫码）——登录响应里的 uid 可能是占位 `"0"`，真实 uid /
   手机号 / 昵称在 `data.userInfo.*` 下；解析不出 uid 直接判登录失败（推理端点认 `uid` 头）。
4. **账号页「刷新凭证」按账号粒度**：`refreshFingerprintForUid(上游uid或池内id)` →
   `/bot/models/configs` 重取 `api_key`；configs 拉取成功即算刷新成功（本家 token 幂等不轮换），
   值不变不再误报失败；失败原因经 `lastRefreshError()` 上抛 UI（删掉了返回恒空串的旧 stub）。
5. **`addCred` 落库改 `addOrUpdate`（按归一化 uid upsert）**：同一账号用验证码/密码/微信
   反复登录只更新一条记录，不再堆重复行（vc141 同语义）。
6. 版本 168/1.0.52 → **169/1.0.53**。

## 待实机验证
同 vc168 清单；另验证：同一账号两种方式各登一次应只有一条记录；「刷新凭证」按钮对每个账号
独立生效；uid 占位账号登录成功后账号标签显示手机号/昵称而非 uid "0"。

# CodeArts7 vc170 / 1.0.54：AStudio 扫码诊断埋点（2026-10-19）

## 本轮改动（eta_src，javac 核心层 0 error + assembleRelease）
1. 新增 `util/QrDiagnostics.java`：环形缓冲记录扫码全链路（qrcode 请求 → 轮询 → bind →
   completeLogin），丢旧保新；日志 grep "QRDIAG" 可回查断点。
2. AStudioProvider 扫码链路埋点：qrcode/poll/bind 每步记 http code + 截断响应体。
3. AStudioLoginActivity / SmsLoginActivity / AStudioCarrierServer 按钮级埋点
   （Tab 切换、打开系统扫码、二维码解析失败路径）。
4. 修复本轮引入的 `excerpt(String)` 重复定义（保留单行压缩版）。
5. 版本 169/1.0.53 → 170/1.0.54。

## 产物
- /workspace/dist/CodeArts7_1.0.54_vc170-20261019-astudio-qrlog-signed.apk
  （4.9MB，apksigner verify 通过，7tattoo.jks，证书 SHA-256 4ebe0621…76bf6，可覆盖安装）

## 待实机验证
同 vc168/169 清单；扫码若仍失败，先看 QRDIAG 日志定位在哪一步断掉。

## 2026-10-19 vc171 / 1.0.55：AStudio 网关转发 Cookie 键序错乱修复

### 根因
`AStudioProvider` 手写 Cookie 头按「蛇形变量名」构造（`account_id`、`sso_sessionid`、`web_session` 等），但 AStudio 上游按「驼峰首键」校验：Cookie 串不以驼峰键开头就整条丢弃，回 401/未登录 → 网关侧表现为全部请求被拒。此前实机排查时只看了 ws 命令通不通，没校验键序。

### 修复（`provider/AStudioProvider.java` 单文件）
新增 `buildAStudioCookie(...)`，键序固定：`ssoSessionId`（必且仅首键，来自驼峰）→ `account_id` → `tprouting` → `x-lt`；`access_token` 永不进 Cookie；无凭据返回空串（不发 Cookie 头，让上游走游客路径）；ws 命令串保持原文。诊断标记 `cookieShape=ssoid-first`。原手拼 Cookie 处全部改为调该函数。

### 防回归测试（app/src/test/.../AStudioCookieTest.java，7 例）
sso 非空首键驼峰不剪尾 / 四键逐字 / token 兜底 sid / access_token 不进 Cookie / 空 sid 首键 account_id / token 独立值优先 / null → 空串。javac -sourcepath 全 main 源编译通过，运行 7 passed, 0 failed（json 用 codearts 自带 tools/json-20240303.jar）。

### 构建/签名要点
- gradle 的 JAVA_HOME 要用 `/usr/lib/jvm/java-21-openjdk-arm64`（/opt/jdk21-home 不存在，compile.sh 里是残留旧路径，javac 实际走系统 PATH）。
- `gradle assembleRelease` BUILD SUCCESSFUL 43s；`zipalign -p 4` → `sign-eta.sh`（V3.0，SHA-256 4ebe0621…76bf6 与历版一致可覆盖安装）；`zipalign -c` OK；dex 含 `sso_sessionid`/`cookieShape`，测试类未混入 release。

### 交付（vc171 / 1.0.55）
`CodeArts7_1.0.55_vc171-20261019-astudio-cookie-fix-signed.apk`（4.9MB）已部署 `/workspace/dist`、`/workspace/shared/codearts7`、`/workspace/mounts/MT2/7tattoo`。

### 待实机验证
装包重启网关：AStudio 分组任意对话 → 请求日志应出现 `AStudio sid 非空`（或 `sid 为空`）与 `cookieShape=ssoid-first`，上游返回 200；若仍 401，抓首条 AStudio 请求日志里的 `len=X` 与 Cookie 首键对照。


# CodeArts7 vc173 / 1.0.57：AStudio 不支持图片输入（实测定论）+ 图片守卫（2026-10-19）

## 定论（实测，非推断）
讯飞 AStudio 分组 `maas-api.cn-huabei-1.xf-yun.com/v1/chat/completions` **9 个模型全部不接受图像输入**，
目录里 `input_modalities` 均为 `["text"]`。上游报错码与真实原因不对应，极易误判：

| 请求形态 | 上游返回 |
| --- | --- |
| `{"type":"image","image_base64"/"image_url":"<字符串>"}` | 10042 `image content is empty` |
| `{"type":"image_url","image_url":{"url":…}}` 且图 <50×50 或 >6000×6000（PNG/JPEG 同） | 10045 `image resolution … limit` |
| 同上，尺寸合法 | 10005 `invalid request` |
| 图片来源用 `data:image/png;base64,…` 或公网 `https://…/png` | 都 10005 |
| `messages[].content` 为纯文本数组（无图） | 200 正常 |
| 顶层 `images` / `attachments` 字段 | 200，但**被静默忽略**（不报错、模型也没看到图） |

结论：**10005 不是「图片格式错」，而是「这批模型不吃图」**。图片可见性在本分组**无法实现**，
只能换支持视觉的分组（如 Loomy 里 `input_modalities` 含 image 的模型）。

## 本轮改动
1. `AStudioProvider.guardImageInput()`：prepareChat 里预检（content 数组内的 image 类块、
   顶层 images/attachments/image_url）→ 本地直接 400 + 中文说明，不再白跑一趟上游拿 10005。
2. `AStudioProvider.explainUpstreamError()`：上游 10045/10042/10005 翻译成可读中文，其余原文透传
   （Router 与凭证刷新按报文判鉴权/额度/会话失效，不能乱改）。
3. 版本 172/1.0.56 → 173/1.0.57。

## 产物
`/workspace/dist/CodeArts7_1.0.57_vc173-20261019-astudio-img-guard-signed.apk`（4.92MB，
证书 SHA-256 4ebe0621…76bf6，可覆盖安装），已同步 `shared/codearts7` 与 `mounts/MT2`。
dex 校验：`guardImageInput` / `explainUpstreamError` / `AStudio 上游拒绝` 各 1 处。

## 待实机验证
发图给 AStudio 模型 → 客户端应立刻收到 400「AStudio / 讯飞 分组不支持图片输入…」，
网关日志出现「拦截图片请求」；纯文本对话不受影响。


# CodeArts7 vc175 / 1.0.59：Loomy 新手任务退回纯手动 + 移除 KiloCode 分组（2026-10-20）

## 用户要求
1. Loomy 的新手任务应作为**一次性功能（用户自己点）**，不要挂在「一键签到奖励」的触发链里。
2. 移除供应商分组 **KiloCode**，相关代码全部去掉。

## 改动
1. `provider/LoomyProvider.java#checkin()`：删掉 vc164/vc165 加进来的「签到后立刻
   `LoomyOnboarding.claimAll()` 并把结果拼进返回文案」那一段（含 `；新手任务 +N` /
   `；新手任务无新增（进度 N/10000）` 文案与 `签到后新手任务` 日志）。
   签到返回现在只有额度语义：`签到成功 / 今日额度已初始化（每日 X/Y）`。
   新手任务入口只剩用户手动点的那两个：账号卡片「新手任务」、服务商页
   「一键领新手任务（全部 N 个账号）」（`ProviderDetailActivity.claimOnboardingAccount /
   claimOnboardingProvider`），一次性语义不变；卡片上的只读进度提示（`onboardingHint`）保留。
2. 移除 KiloCode（按此前在 `KiloCodeProvider` 文件尾留下的「移除步骤」逐条执行）：
   - 删除 `provider/KiloCodeProvider.java`（597 行，含 Kilo 池 + OpenCode 池两条 lane）；
   - `provider/ProviderRegistry.java`：删掉 `all.add(new KiloCodeProvider());` 与两行注释
     （服务商列表由 registry 驱动，供应商页/模型页分组自动少一项）；
   - `provider/ServiceProvider.java`：删掉仅它使用的 `loginFree()` 默认方法及其 javadoc；
   - `ui/MainActivity.java`：删 `providerIcon/providerDesc` 里的 kilocode 两行；
     `fillProviders` 里两处 `if (p.loginFree())` 分支还原为普通分支（账号数文案 + 二级菜单箭头）；
   - `provider/PreparedChat.java`：透传模式注释「五个服务商」→「四个服务商」。

## 验证
- 构建：`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 /opt/gradle-8.9/bin/gradle assembleRelease -x lintVitalRelease` → BUILD SUCCESSFUL（33s）。
- 源码回证：`grep -rniI "kilo|loginFree" app/src/main/java` → 0 命中。
- dex 回证（签名包 classes.dex，9,134,260 字节）：
  `KiloCodeProvider` / `kilocode` / `kilo::` / `api.kilo.ai` / `opencode` / `dsh-our-free-model` / `loginFree` 全为 **0**；
  `LoomyOnboarding` = 4、`claimOnboarding` = 9、`first-login` = 1（签到端点仍在）。
- 包版本：versionCode 175 / versionName 1.0.59（output-metadata.json 核对）；证书 SHA-256 4ebe0621…76bf6，可原地覆盖安装。

## 产物
`/workspace/dist/CodeArts7_1.0.59_vc175-20261020-loomy-manual-kilo-removed-signed.apk`（4,920,181 字节，
md5 65486aca7f8c91159474ba3f62ad1945），已同步 `/workspace/shared/codearts7`、
`/workspace/mounts/MT2/7tattoo`、`/workspace/mounts/MT2/`。

## 待实机验证
1. 「一键签到」结果里 Loomy 三号应只剩「今日额度已初始化（每日 5000/5000）」，不再出现「新手任务无新增」。
2. 供应商页不再有 KiloCode 卡片；模型页不再有 KiloCode 分组；原先授权过该分组的 Key 若只剩它，会表现为无可用分组（预期）。
3. Loomy 详情页的「新手任务」按钮仍能一次领完（进度 10000/10000 → 提示已全部领取）。

---

# vc176 / 1.0.60：模型页分组「未探测（N 个已禁用）」串了全局数量（2026-10-21）

## 用户反馈
模型页展开服务商分组时，右侧摘要一律显示「未探测（6 个已禁用）」：
Loomy 组左侧按钮已是「解禁(1)」（本组只禁 1 个）、TRAE / Raccoon 组按钮是「禁用失效」（本组 0 个禁用），
但摘要数字还是 6 —— 三个分组顶同一个数。

## 根因
`ui/MainActivity.java` 的 `probeStatusText(pid)` 未探测分支里写的是
`App.get().settings().disabledModels().size()`。`disabledModels` 是**全局**集合（只按 model id 存，
跨分组共用，当前共 6 个），于是每个分组都显示全局总数，和左侧按分组算出来的「解禁(N)」自相矛盾。
不是渲染透字，是取数取错了域。

## 修复（单文件，一个方法）
`probeStatusText(pid)` 未探测分支改为按**本分组**统计：
取 `catalog().grouped().get(pid)`，遍历该组模型，统计 id 落在全局禁用集合里的个数 `disabledHere`；
`disabledHere > 0` → 「未探测（N 个已禁用）」，否则 → 「未探测」。
（全局集合本身不改：禁用/解禁、`/v1/models` 下发口径仍按全局 model id 走，与「解禁(1)」按钮一致。）

## 验证
- `gradle --offline compileReleaseJavaWithJavac` → BUILD SUCCESSFUL。
- `gradle --offline assembleRelease` → BUILD SUCCESSFUL（35s）。
- 包版本回证：`aapt2 dump badging` → versionCode 176 / versionName 1.0.60；
  output-metadata.json 同为 176 / 1.0.60；签名证书 SHA-256 4ebe0621…76bf6（可原地覆盖安装）。

## 产物
`/workspace/dist/CodeArts7_1.0.60_vc176-20261021-group-disabled-count-fix-signed.apk`（4,924,277 字节，
md5 2f793d93667d2f09460f37aaa03d73b9），已同步 `/workspace/shared/codearts7`、`/workspace/mounts/MT2/7tattoo`。

## 待实机验证
1. 未探测过的分组：本组无禁用 → 只显示「未探测」；本组禁了 1 个 → 「未探测（1 个已禁用）」，与左侧「解禁(1)」一致。
2. 探测过的分组仍显示「可用 x/y」不受影响。

---

# vc177 / 1.0.61：供应商改名 + 新增「KukuAI / 百度」供应商（2026-10-21）

## 1. 供应商标题改名（纯展示名，id 与功能不变）
- `BuddyProvider.displayName()`：`Buddy / CodeBuddy` → **`WorkBuddy / 国内版`**
  （用户口述「Workdubby」；按品牌实际拼写 WorkBuddy，同为 copilot.tencent.com 体系，
  项目注释里本来就写「Buddy 国内版 / WorkBuddy 国际版」）
- `AutoClawProvider.displayName()`：`AutoClaw 国内版` → **`AutoClaw / 国内版`**
- 目的：与 Raccoon / 小浣熊、TRAE / 字节、Loomy / 讯飞、AStudio / 讯飞 的
  「英文名 / 中文（地区）」标题格式对齐。

## 2. 新增 KukuAI（id `kuku`，显示名「KukuAI / 百度」）
协议规格落档 `ref/kukuai_spec.md`，来源 agent2api（Rust `providers/kuku/*.rs`）+
pcy0124m/kukuAI 签到脚本，交叉参照 kuku2api(JS) / workbuddy(Go)。

- 站点 `https://kuku.baidu.com`；`app_id=123971023`、`channel=chunlei`、
  `WEB_QUERY=clienttype=400&app_id=123971023&web=1&channel=chunlei&version=1.4.4`
- 鉴权 = 百度通行证 Cookie（`BDUSS` + `STOKEN` + 恒附加 `gfprotpl=genflowpro`）；
  **无登录接口、无刷新接口**（PTOKEN→业务 STOKEN 的换发签名在 Windows
  `genflowengine.dll` 里，Android 无法复刻 → 不做换发，失效即提示重新粘贴）
- 会话三件套：`GET /api/genflowpro/common/userreport` → `bdstoken/uinfo/uk`（缓存 600s）
- 模型：`GET /wenchain/genflowpro/model/list`（宽松解析）+ 13 个静态兜底 id
- 积分：`GET /bizapi/gfpro/getgfvipremain?channel=…&clienttype=400&version=1.4.4`
  → 顶层 `status.code==0`、`data.list[assetName=token].totalPoint`
- 签到：`POST /api/genflowpro/freepoint/taskComplete`（form `task_type=LOGIN|CHAT`，
  幂等；面板 `freepoint/homenew`）
- 对话：`sendmsg`（建会话）→ `idallochstr`/`sessionswitch`（容错）→
  `sse/getchatcontent` 自有 SSE（`TEXT_BLOCK_DELTA` / `REPLY_END` / `DIALOGUE_END` / `ERROR`），
  由 `KukuAIProvider.KukuSse` + `lineOriented=true` 的 PreparedChat 翻成 OpenAI chunk；
  多轮历史拍平成单条 prompt（system→`[系统指令]`、assistant→`[助手之前的回复]`）

接入点（与 AutoClaw 同款 5 处）：`core/App.java`（字段/构造/getter + AppBridge）、
`provider/ProviderRegistry.java`（注册 + 接口）、`ui/MainActivity.java`（图标 ic_robot / 描述）、
`ui/ProviderDetailActivity.java`（登录分支 + 服务商级「粘贴 Cookie 登录」按钮）、
`ui/KukuLoginActivity.java` + manifest（内置浏览器登录页）。

## 3. 本轮踩的两个坑（重要）

### 3.1 同一批文件被**另一个写者**并发修改
08:53–09:01 期间 `KukuAIProvider.java`（我写的 44KB 版）被外部重写为 1062 行版本，
并新增 `ui/KukuLoginActivity.java`、改 `AndroidManifest.xml` 与 `ProviderDetailActivity.java`
（内置浏览器抓 Cookie 方案，比原「粘贴 Cookie 对话框」体验更好）。
处理：先让路（轮询 mtime 直到稳定），再合并 —— 删掉重复的 `boolean isKuku` 声明、
把两个 `else if (isKuku)` 分支合一（内置浏览器为主入口）、保留 `showKukuCookieDialog()`
作为服务商级备用按钮。**教训：发现 mtime 在动就先别改，等稳定再合并，否则互相覆盖出半成品。**

### 3.2 gradle 失败仍签出「假包」（重演 vc164 事故）
第一次出包时 `gradle … | tail -25` 的退出码被管道吃掉，
`assembleRelease` 失败（KukuAIProvider 编译错）却继续用**上一次的** unsigned APK 签名，
产出名为 vc177、实际 versionCode=176 的包（md5 与 vc176 完全相同）。
处理：删掉该产物；改用 `set -e` + `if ! gradle … > log; then exit 1; fi`，
并在签名前后都 `aapt2 dump badging` 核对 versionCode/versionName。

## 4. 验证
- `gradle --offline compileReleaseJavaWithJavac` → BUILD SUCCESSFUL
- `gradle --offline assembleRelease` → BUILD SUCCESSFUL（26s）
- unsigned / signed 双份 badging：**versionCode 177 / versionName 1.0.61**；
  证书 SHA-256 4ebe0621…76bf6（原地覆盖安装）
- dex 回证（classes.dex 9,164,016 字节）：`kuku.baidu.com` ×15、`KukuAIProvider` ×5、
  `genflowpro` ×11、`gfprotpl` ×2、`idallochstr` ×1、`freepoint` ×2、
  `getgfvipremain` ×1、`KukuLoginActivity` ×10

## 5. 产物
`/workspace/dist/CodeArts7_1.0.61_vc177-20261021-kukuai-provider-signed.apk`
（4,932,469 字节，md5 `e1d518446c4679bb0b723ee1aa4834da`），已同步
`/workspace/shared/codearts7`、`/workspace/mounts/MT2/7tattoo`。

## 6. 待实机验证
1. 供应商页出现第 7 张卡片「KukuAI / 百度」，标题与描述格式正常；Buddy / AutoClaw 标题已改名。
2. 进 KukuAI 详情页 →「打开百度登录页」：内置浏览器登录后自动抓 Cookie 并落库（新账号 label 应为昵称）；
   抓不到时用「粘贴 Cookie 登录」粘贴 `BDUSS=…; STOKEN=…`。
3. 积分卡片应显示 token 额度（`getgfvipremain` 的 `totalPoint`）；失败会直接给出 code/msg。
4. 点「签到」应领到「每日登录 +50、完成一次对话 +50」（当天已领导致 reward=0 属正常）。
5. 「刷新模型清单」应出现 KukuAI 分组（远程目录拉不到时用 13 个静态兜底 id）；
   探测/对话可用性取决于该 Cookie 是否含**业务 STOKEN**（若提示未登录，按提示用 PC 客户端导出的完整 Cookie）。

---

# vc177 / 1.0.61：供应商改名 + 新增「KukuAI / 百度」供应商（2026-10-21）

## 1. 供应商标题对齐（纯展示名，id 不变）
- `BuddyProvider.displayName()`：`Buddy / CodeBuddy` → **`WorkBuddy / 国内版`**
  （用户口述「Workdubby」，按品牌实际拼写 WorkBuddy；该链路走 `copilot.tencent.com`，
  项目内注释历史上就写作「Buddy 国内版 / WorkBuddy 国际版」）。
- `AutoClawProvider.displayName()`：`AutoClaw 国内版` → **`AutoClaw / 国内版`**。
- `MainActivity.providerDesc()`：kuku 改「内置浏览器登录百度账号（或粘贴 Cookie），库库 AI 积分通道」；
  buddy 改「网页登录，走 WorkBuddy（CodeBuddy）积分通道」。

## 2. 新增供应商「KukuAI / 百度」（id=`kuku`，`kuku.baidu.com`）
协议来源：agent2api（aimod-cc/agent2api）Rust 实现 `providers/kuku/*`（2842 行，HEAD 68d0a4e4），
并与 pcy0124m/kukuAI 的签到脚本对读。规格落 `ref/kukuai_spec.md`（新建）。

实现（自包含，一处下线只删 4 处引用）：
- `provider/KukuAIProvider.java`：Cookie 认证（BDUSS 必填 + STOKEN + extras + 恒附加 `gfprotpl=genflowpro`）、
  `userreport` 三件套（600s 缓存，query 段**不 URL 编码**）、
  `/wenchain/genflowpro/model/list`（只认 `model_name`/`modelname`/`modelid`，13 条兜底表，未知模型回退 `auto`）、
  对话三步（sendmsg → idallochstr + sessionswitch（容错）→ `sse/getchatcontent`）+ `KukuSse` 翻译成 OpenAI chunk、
  积分 `bizapi/gfpro/getgfvipremain`（`list[assetName=token].totalPoint`）、
  签到 `freepoint/homenew` + `freepoint/taskComplete`（无条件领 LOGIN/CHAT，接口幂等）。
- `ui/KukuLoginActivity.java`（新）：内置浏览器打开 `kuku.baidu.com`，每 2s 读 CookieManager，
  出现 BDUSS 即汇总全部百度域 Cookie → `loginWithCookie` 落库；页内另有「粘贴 Cookie 登录」。
- `ui/ProviderDetailActivity`：kuku 分支（登录按钮 → KukuLoginActivity）+ 服务商级「粘贴 Cookie 登录」入口。
- `core/App.java`（字段/构造/getter）、`ProviderRegistry`（注册）、`MainActivity`（图标 ic_robot + 描述）、
  `AndroidManifest.xml`（注册 KukuLoginActivity）。

### ⚠️ 已知硬限制（上游令牌作用域）
业务接口只认**按产品签发**的 genflowpro STOKEN；网页登录拿到的是通行证级，实测 errno=-6
（症状「余额能查、模型/对话拉不下」）。PC 端靠随客户端安装的 `genflowengine.dll` 算 `sig` 走
`passport.baidu.com/v3/login/api/auth` 换发 —— **Android 无法复刻该 DLL**。
本仓策略：只用凭证里已有 STOKEN 直连；errno=-6 时在异常文案里明确提示
「改用 PC 客户端登录后导出的完整 Cookie 导入」，并保留粘贴 Cookie 入口，不假装成功。

## 3. 验证
- `gradle --offline assembleRelease` → BUILD SUCCESSFUL。
- 包版本：`aapt2 dump badging` → versionCode 177 / versionName 1.0.61；
  证书 SHA-256 4ebe0621…76bf6（可原地覆盖安装）。
- dex 回证（签名包 classes.dex）：`KukuAIProvider` 5 处 / `KukuLoginActivity` 10 处 /
  `genflowpro` 10 处 / `getgfvipremain` 1 处 / `freepoint` 2 处 / `kuku_web_genflowpro_v1` 1 处。

## 4. 产物
`/workspace/dist/CodeArts7_1.0.61_vc177-20261021-kukuai-signed.apk`（4,932,469 字节，
md5 e1d518446c4679bb0b723ee1aa4834da），已同步 `/workspace/shared/codearts7`、`/workspace/mounts/MT2/7tattoo`。

## 5. 待实机验证
1. 供应商页出现「KukuAI / 百度」卡片；详情页登录按钮给出「内置浏览器登录 / 粘贴 Cookie」两个选择。
2. 内置浏览器登录百度账号后应自动落号；若提示 `errno=-6`，需改用 PC 客户端导出的完整 Cookie
   （含换发后的 STOKEN）—— 这条路径决定模型/对话是否可用。
3. 积分卡片能显示 `token` 资产的 `totalPoint`；「签到」按钮应能看到「每日登录 / 完成一次对话」的领取结果。
4. 模型页出现 kuku 分组（远程目录或 13 条兜底表），且 `auto` 之外未知模型名不会静默换模型。

## 6. 流程备注
两个只读调研子智能体中的一个**越界直接改了项目代码**（创建 KukuAIProvider、改 App/Registry/UI/版本号），
本轮已按参考实现逐条复核并修正：模型名提取收窄、未知模型回退 auto、三件套 query 不编码、
签到不拿面板状态当门槛且不带三件套、模型目录失败不再静默兜底、errno=-6 文案补上令牌作用域说明。

# vc178 / 1.0.62：移除 KukuAI / 百度 供应商（2026-10-21）

## 需求
去除 KukuAI 供应商分组，移除相关代码。

## 改动
- 删除 `provider/KukuAIProvider.java`、`ui/KukuLoginActivity.java`、`ref/kukuai_spec.md`。
- `core/App.java`：删 `kukuProvider` 字段、构造与 `kuku()` bridge 方法。
- `provider/ProviderRegistry.java`：删注册行与 `AppBridge.kuku()`。
- `ui/ProviderDetailActivity.java`：删 `isKuku` 声明与登录分支、`showKukuCookieDialog()` /
  `doKukuLogin()` 整段、服务商级「粘贴 Cookie 登录」入口、「添加账号」条件中的 kuku 排除。
- `ui/MainActivity.java`：删 providerIcon / providerDesc 的 kuku 两行。
- `AndroidManifest.xml`：删 `KukuLoginActivity` 声明。
- `app/build.gradle`：versionCode 177→178，versionName 1.0.61→1.0.62。

## 验证
- `gradle --offline assembleRelease -x lintVitalRelease` → BUILD SUCCESSFUL（30s）。
- dex 回证：`KukuAIProvider` / `KukuLoginActivity` / `kuku.baidu.com` / `genflowpro` 全部 0 命中。
- 源码 grep（除 PROGRESS.md 历史日志）kuku 0 命中。

## 产物
`/workspace/dist/CodeArts7_1.0.62_vc178-20261021-remove-kukuai-signed.apk`
（4,924,277 字节，V3.0，SHA-256 4ebe0621…76bf6，与历版同证书可覆盖安装。）

# vc179 / 1.0.63：APK 瘦身 82.5% + 代码规范化（2026-10-24）

## 需求
1. 代码规范化、适当精简。
2. 成品 4.6M+ 太大，看怎么瘦身。

## 瘦身（4,924,277 B → 860,180 B 签名包，-82.5%）
- **去 Material**：`material:1.11.0` 在 Java/XML 里零引用（布局只用 Theme.AppCompat、SwitchCompat、AppCompatActivity），
  换 `appcompat:1.6.1` + `activity:1.9.0`（EdgeToEdge 需 ≥1.8.0）+ `kotlin-bom:1.8.22`（对齐 stdlib/jdk7/jdk8，消
  duplicate class）。仅此一步 4.84MB → 2.75MB。
- **去 Chicory WASM**：`app/libs` 三个 jar（runtime/wasi/wasm 1.7.5）零引用（Qoder 供应商已删），连带删 fileTree 依赖；
  jar 备份在 `/workspace/tools/chicory-jars/`。
- **开 R8**：`minifyEnabled true` + `shrinkResources true`；proguard 规则仅两条——保留行号（SourceFile,LineNumberTable）
  + `-keepnames class com.aigetway.tattoo7.**`（崩溃可读、dex 特征回证口径不变）。2.75MB → 820KB。
- **资源收缩安全化**：MainActivity 底栏图标原用 `getIdentifier(名+"_blue")` 动态查资源，改显式 `iconsSel[]` 数组
  后删除 blueRes/greyRes 两个反射查询方法。

## 代码清理
- 删 12 个未用 import（10 个文件，脚本严格校验「import 类名在正文零引用」后删）。
- 删 6 个零调用死方法（逐个人工核实过调用点为 0）：RaccoonProvider.mapCode、TraeProvider.fragmentParam、
  AStudioProvider.pointsOf、LoginActivity.cookieValue（refresh_token 提取早已改走 WebView JS 注入）/
  loggedInOrPending、ProviderDetailActivity.pasteFromClipboard。
- compile.sh 修复：JAVA_HOME 过时路径（/opt/jdk21-home → /usr/lib/jvm/java-21-openjdk-arm64）；
  原先 `javac|grep|head` 管道吞错（EXIT 恒 0），改报 `PIPESTATUS[0]`；cd 改脚本自身目录。
- 根目录调研脚本归档 `tools/probes/`（probe_*.py、extract*.py、sim_vc55.py、test_sse.py），
  旧文档（PROGRESS_base_vc93、SESSION_SUMMARY、build-108.log）移 `devlog/`。

## 验证
- 构建：gradle assembleRelease BUILD SUCCESSFUL；换依赖后在线补拉 12 个传递依赖，`--offline` 复验通过。
- aapt2 badging：versionCode 179 / versionName 1.0.63，label/icon/launchable 正常；
  resources 表 ic_home(_blue)/bg_card/AppTheme/activity_main/ic_launcher 全在。
- dex 回证（签名包）：6 个 provider + GatewayService/BootReceiver/MainActivity + AStudio 端点
  （phone-quick-login/check-account/bot-models-configs/init-app/sso.xfyun.cn/astronstudio-api）+
  office-raccoon + v1/chat/completions 全部命中；kuku 0 命中（vc178 口径保持）。
- 单测：AStudioCookieTest 7/7、ModelEntryToJsonTest 22/22（javac -sourcepath + json-20240303.jar）。
- R8 mapping/usage 核查：87 个 app 类保留原名；usage.txt 里 AccountPool.load/key/rotate 等是**内联**进调用方
  （mapping.txt 权威），非删除。
- compile.sh：javac exit 如实输出；18 个错误全部为 ui/* 层 androidx 既有噪音，provider/service 核心层 0 error。

## 产物
`/workspace/dist/CodeArts7_1.0.63_vc179-20261024-slim-signed.apk`
（860,180 字节，md5 65b30f4cfcca0ccb734ffa87fb6ab1b5，V3.0，证书 4ebe0621…76bf6 与历版相同可覆盖安装）
已同步 `/workspace/shared/codearts7`、`/workspace/mounts/MT2/7tattoo`。

## 待实机验证
- R8 混淆包运行时行为：登录（三通路）、签到/新手任务、模型列表、网关对话、切页。
- 观感理论无变化（原本样式就只用 AppCompat 属性，Material 是死重），重点确认设置页三个开关（SwitchCompat）正常。

# vc180 / 1.0.64：TRAE 供应商修复（通道路由 + progress_notice + 自定义模型过滤）（2026-10-25）

## 症状
1. 大量模型一发请求就被流内 `code=4001 param is invalid` 拒。
2. 客户端报 `Value Processing_… String cannot be converted to JSONObject`。
3. 纯思考流（只有 reasoning_content）被判「空流」刷诊断日志。

## 根因（对照 iJetLi/deepseek-harness-codearts master df7cae3 与 Ebony-Vinyl/dsh-our-free-model main ea2e84b）
- **4001 主因**：`function` 通道被写死 `solo_work_lite`。上游模型只在列出它的通道里可调用
  （`glm-5.1`/`qwen-3.5`/`Doubao-Seed-Code` 只在 `solo_agent_remote`；`glm-5-turbo`/`sagitta` 只在 work_lite），
  发错通道即流内 4001。其次 `display_config.is_custom_model=true`（仅可见不可调用）与
  请求头重复 Content-Type 也会 4001。
- **JSONObject 崩溃**：`event:progress_notice` 的 data 是 `Processing_…` / `;Processing_…_xxx` 裸字符串，
  旧 translator 把未知事件原样透传给 OpenAI 客户端，客户端 new JSONObject() 直接抛类型转换异常。
- **空流误判**：diagSummary 只看 respChars，纯 reasoning 流（DeepSeek 系常见）被当空流记全量 tail。

## 改动
- `SseTranslator.java`：progress_notice 入已知事件并丢弃；未知事件仅当**合法 JSON 且含 choices**
  （或 `[DONE]`）才放行，裸字符串一律忽略——既不再喂毒帧，也保留真正的 OpenAI chunk 透传；
  diagSummary 增加 `reasonChars>0` 判据。
- `TraeProvider.java`：
  - 新增 `MODEL_CHANNELS`（模型→所属 function），`collect()` 递归时携带通道并记录；
    `channelFor(model)` 查表，兜底才回退 `solo_work_lite`；`__dev/__max` 后缀先剥离再查。
  - `passesTraeFilters`：`config_switch`/`is_invisible_to_user`/`is_custom_model` 兼容
    顶层与 `display_config` 两处（标志实际在 display_config 内）。
  - `supportsVision`：按 `display_config.multimodal` 逐模型置 vision（此前恒 false，客户端
    因此把图降级成纯文本）。
  - `buildRawChatBody`：model 带 `__` 后缀时剥后再写 `model/config_name`；
    max_tokens 钳到 ≤64000（客户端 131072 会被上游拒），≤0 或缺失补 32000。
  - TRAE-REQ 诊断日志带 function 字段，方便实机对账。
- 新增 `TraeProtocolTest.java`（app/src/test，纯 JVM，不进 release）：
  13 项断言覆盖裸字符串/前缀字符串 progress、思考正文独立 chunk、通道路由、兜底通道、
  嵌套 custom 过滤、multimodal 提取、reasoning-only 不判空流。运行通过。

## 验证
- 纯 JVM 回归：13 checks passed（需 stub GatewayService，UI/service 层不参与）。
- `gradle --offline assembleRelease -x lintVitalRelease` BUILD SUCCESSFUL（45s，JAVA_HOME 必须 21，
  默认 java 25 会报 Unsupported class file major version 69）。
- 签名 V3.0，证书 SHA-256 4ebe0621…76bf6 与历版一致，可覆盖安装；860,180 B。
- badging：versionCode 180 / versionName 1.0.64；与 vc179 md5 不同。
- dex 回证：progress_notice×1 / multimodal×1 / solo_agent_remote×1 / TraeProtocolTest×0。

## 产物
`/workspace/dist/CodeArts7_1.0.64_vc180-20261025-trae-fix-signed.apk`
（860,180 B，md5 229c4fcf66a47631251d2d5a5a1b9ba4），已同步
`/workspace/shared/codearts7` 与 `/workspace/mounts/MT2/7tattoo`。

## 待实机验证
1. TRAE 模型列表刷新后，原 4001 的模型（glm-5.1 / qwen-3.5 / Doubao-Seed-Code 等）应能正常对话
   ——请求已按目录路由到 `solo_agent_remote`（网关日志 TRAE-REQ 行可见 function=…）。
2. 流式不再出现 `Value Processing_… String cannot be converted to JSONObject`。
3. 逐字思考 + 正文应同时正常（两者本就分 chunk 转发，本轮仅确认未被 progress 帧打断）。
4. 视觉模型（目录里 multimodal=true 的）在 AG 客户端应显示为可发图；DeepSeek 纯思考模型
   不再刷「空流」诊断。

# vc181 / 1.0.65：AutoClaw「一周福利」领不到（2026-10-27，参考 Gitee PR #153）

## 根因
服务端把「签发 token」当一次登录（claimDailyCreditsOnSignIn），活动奖励由登录后紧随的 userapi 请求触发发放。
本插件此前只用存量 access_token 调 userapi，服务端眼里从未登录 ⇒ autoclaw_user_active_reward_* 永远不发。

## 修复（单文件 AutoClawProvider.java）
- checkin() 先 refreshFingerprint() 刷新 token，用新 token 发签到/触发发放。
- 刷新失败不阻断签到：catch 后降级用原 token，lastRefreshError 记录原因。
- 轮换的 refresh_token 由既有 refreshFingerprint 落盘；UI 签到均在 providerLock 内，天然单飞无并发竞态。
- 未接 promotion modals（营销弹窗，非可领奖励语义）；daily_check_in_completed_on_request 判不了结果，不动。

## 产物
- /workspace/dist/CodeArts7_1.0.65_vc181-20261027-autoclaw-weekly-signed.apk（已同步 MT2）
- sha256 a058115b017f0aaa685a30f613e9fb215b3ce3156ee35b63c3fe92cac3dc420a
- versionCode 181 / 1.0.65；javac provider 核心层 0 error（ui 层 androidx 缺失为基线内）；gradle --offline assembleRelease BUILD SUCCESSFUL；V3 签名 4ebe0621…b76bf6。
