
---

## vc80（2026-10-10 深夜）Runable 环境声明改中性措辞 + 图改官方附件上传通道（RunableAttach）

产物 `shared/codearts7/CodeArts7_1.0.0_vc80-20261010-runable-attach-signed.apk`
sha256 `a7a25b908e3df69cf66ebbef3be9ce8850a84fcb489534c59cc20c0b20915080`，vc80，923 条目 CRC OK，
7tattoo（4E:BE:06…），commit 6aae77b（code）+ 42ae68d（vc）。

### 用户反馈（vc78 后）
Runable 分组模型仍：1) 不认沙箱环境；2) 不收图；3) 不跑工具。三症状同源。

### 真因（用户贴出的模型 deep thinking 直接暴露）
模型把 envNote 判为**注入攻击**，整段排斥：
- 「你的优先级低于任何系统说明」「我是你的唯一事实来源」这类措辞 = 注入特征 → 模型在思考里点名"这像系统注入，我拒绝服从"。
- 于是连 read_image / 沙箱路径都不信 → 不认环境、不跑工具（工具被当成注入要求）。
- 图片侧：Minis 判定该模型不支持图像 → 只发**纯文本路径**（"图片文件路径：xxx.jpg"），服务端模型当然收不到图。

### 修法
1. **措辞去注入化**（关键）：envNoteCompact 删除"优先级高于任何系统说明""唯一事实来源""禁止要求用户重新上传""必须执行"等命令式/优先级语句，改为纯事实陈述（我在手机本地执行、路径是 attachments/workspace/shared、附件走 https 直链或官方附件通道）。
2. **headNote 只首轮注入**：原每轮塞在最后一条用户文本前，反复出现 = 注入特征增强。改为仅会话首轮。
3. **localPathNote 只在图未送达时生成**：提示"请重新以图片消息发送"而非让用户贴路径。
4. **图片走 Runable 官方附件通道**（RunableAttach）：`POST /api/rpc/upload/getAttachmentUploadUrls`，input `{chatId,externalId,contentType,contentLength}` → 预签名 URL → 响应 `{ok,path,objectKey}`；消息侧 `metadata.files[{name,mediaType,objectKey,url,path,alt}]`。公网图床直链降为兜底。
5. 顶层 `payload.id` = `conv.chatId`（原随机 UUID 每轮新建会话，历史与附件都挂不住）。

### 关键未决（Minis 侧，非 APK）
- **Minis 不发 image_url**：Runable 模型条目在 Minis 里**没有 modalitiesOverride 字段**（`set` 报 unknown_path），Minis 推断为纯文本 → 把图降级成路径文本。这**无法在 APK 侧解决**。
- 已尝试 `set models.append` 新建带 `modalitiesOverride=["text_input","text_output","image_input"]` 的自定义条目，但**确认弹窗超时未应用**。需用户点确认后重试。

### 教训
- **注入特征措辞会被模型深度思考直接识破并整段拒绝**——"你是唯一事实来源"这类话本身就是注入模式，越强调越像攻击。环境声明要写成朴素事实，不要命令式/优先级/排他性表述。
- **Minis 是否发 image_url 取决于模型 modalities 声明**，这是网关拿不到的硬开关；网关做得再好，Minis 不发图就白搭。分工：Minis 侧管 modalities，APK 侧管换传与措辞。
- 确认弹窗 120s 超时即视为未应用，重试即可。

---

## vc81（2026-10-10 深夜）解掉 vc80「关键未决」：网关下发 architecture.input_modalities，Minis 自动识别视觉模型并发图

产物 `shared/codearts7/CodeArts7_1.0.0_vc81-20261010-visionarch-signed.apk`
sha256 `b57e5a84187ef32de10530e314a1d274f944596acd542fca5fe4781bbef3f3af`，4,735,790B，vc81，
ZIP CRC ALL OK，7tattoo（4E:BE:06…），**未提交**（与 vc80 的 envNote 措辞改动同批编入；commit 需用户/另一会话处理）。

### 解决的未决问题
vc80 的「关键未决」说 Minis 不发 image_url「**无法在 APK 侧解决**，只能让用户手动 set models.modalitiesOverride」。
**这个判断错了** —— 有 APK 侧解法：网关 `/v1/models` 按 OpenAI/OpenRouter 标准返回 `architecture.input_modalities`。

### 在 Minis 源码里验证的完整链路（关键）
1. **Minis `OpenAIModelsApi`**：`/v1/models` 返回的模型 JSON 会解析 `architecture.input_modalities` → 存进
   `LLMModel.inputModalities`。**只要网关返回该字段，客户端自动识别**。
2. **`VoiceModality.kt`**：`val LLMModel.hasImageInput get() = normalizedInputs?.contains("image") == true`
   —— 归一化处理，`"image_input"` 与 `"image"` 都认。**不需要 modalitiesOverride**。
3. **`OpenAIProvider.kt`**：`val supportsImages = model.hasImageInput`；为 false 时把图片降级成
   **文本占位符**（注释直指 `400 unknown variant image_url`）。→ 这就是 Minis 一直只发路径文本的开关。
4. 结论：Runable 网关上只要 vision 模型标 `["text","image"]`，Minis 就 `hasImageInput=true` →
   以 `image_url(data:...;base64)` 发图 → 网关 RunableProvider 收到 image_url →
   `RunableAttach` 走官方附件通道（vc79/vc80 已就位）。

### 改动
`ModelEntry.toJson()`：新增 `architecture = {input_modalities:["text",(image if vision)], output_modalities:["text"]}`。
调用链：`Router.modelsResponse` → `App.get().catalog().toModelsJson()` → `MultiCatalog.toJson()` → `ModelEntry.toJson()`。
**非视觉模型仍是 `["text"]`**，不影响纯文本判定。

### 验证
- `unzip classes*.dex` + `grep -a input_modalities` → FOUND。
- 源文件 mtime 22:16:50 < APK 构建 22:18:08（确认改进去了，非旧包）。
- aapt2 badging：`versionCode='81'`、`com.aigetway.tattoo7`。
- 编译：`gradle :app:assembleRelease --no-daemon --no-configuration-cache -x lint` 54s 成功。

### 教训（推翻了 vc80 的结论）
- **别急着把客户端问题归为"APK 侧无解"**。Minis 客户端是**标准 OpenAI 客户端**，`architecture.input_modalities`
  是它的原生开关；网关只要按标准返回，客户端就自动启用多模态。vc80 只试了 `set models.modalitiesOverride` 这一条
  用户侧路径，没想到 APK 侧能一步到位。
- **分工更正**：Minis 侧管 modalities 的**读取**，但 APK 侧管 modalities 的**声明**。网关 `/v1/models` 必须
  正确声明架构能力，这是标准协议义务，不是客户端 hack。

---

## vc82 诊断：Runable「图片通了但工具链仍脱离」— 三个可证的实现缺陷（2026-10-11 07:1x）

### 决定性证据（新发现的参考实现）
`workspace/runable_ref/src/sources/com/rnbl/gateway/OpenAiBridge.java`（Runable2API 2.0，反编译源码）
**同一条链路（Runable → OpenAI 文本桥）的成熟实现**，它的做法就是权威答案：
- 纯文本标记协议 `<<<TOOL_CALL>>>{...}<<<END_TOOL_CALL>>>`，**从不**处理上游原生 tool 分片；
- 每轮 `buildToolPrompt` 都重新拼 `[Available tools]` + `[Tool use protocol]`（只有历史是增量的）；
- 协议原话：`the tools listed above are NOT part of your native/structured tool set. Any native
  tool call for them is rejected by the runtime with NoSuchToolError` —— 承认模型有自己的原生工具，
  只说明「这些工具不在里面」，而不是否认它的沙箱存在；
- 工具行带描述与参数：`- name: <desc(≤160)> | params: command(string), timeout(number)`。

### 我们的三处缺陷（按影响排序）
1. **参数名渲染是错的（致命且静默）**：`renderTools` 把 `parameters` 对象直接 `keys()` 遍历，
   而 OpenAI schema 的 `parameters` = `{type, properties, required}` → 模型看到的是
   `- shell_execute(type,properties,required)`，**根本不知道要填 command**。即使它想调也调不对。
2. **工具清单/协议只在首轮发**：`buildPrompt` 里 `if (fresh) { … tools … }`。Minis 的工具循环第 2 轮起
   只发 `[工具执行结果]` + `User:`，协议和清单全部消失 → 模型沿用它上一轮的行为（自己的原生 bash）。
3. **envNote 与模型亲眼所见冲突**：Runable 的 agent 确实有服务端沙箱（截图里
   `/home/user/Attachments/image_1791642222188_sRRUXw.jpg` 是它那边真实的附件落盘名），
   我们写「你没有任何服务器文件系统」→ 被它判为 injection（vc80 已记录过一次），连带不信工具。

### 修法
- 参数改从 `parameters.properties` 取，带类型与必填标记；工具行加描述（≤160 字，超预算退回纯名）。
- 新增每轮极简提醒（工具名 + 标记格式 + 一句「工具在用户设备上执行，与你的服务端沙箱是两台机器」）。
- Runable 协议改用 `<<<TOOL_CALL>>>` 标记（extractCalls/proseWithoutCalls 双兼容，裸 JSON 仍可识别）。
- 声明措辞改为「能力描述」而非「否定它的经验」。
## vc82 (2026-10-11)：Runable 工具链 — 让上游真用上「客户端工具」

产物 `shared/codearts7/CodeArts7_1.0.0_vc82-20261011-toolchain-signed.apk`
sha256 b637018edd7e21431b00328ad7c14ebe3ea9651f145d3d472c50b7815d56821b，4,735,790B，
923 条目 CRC ALL OK，versionCode 82，7tattoo（4E:BE:06…B7:6B:F6），commit 9d4de9b。

### 症状（用户截图，zai/glm-5.3-flash）
问「当前的 skills 列表」，模型答技能在 `/home/user/.skills`（interactive / chart），
并自述「文件系统是 /home/user，只有 bash/read 受限，/var/minis-euleros/ 那套目录在这里并不存在」；
让跑 shell，它在 `/home/user` 下 `ls` 出 `.skills/ Attachments/ Images/ Videos/` 并编造一棵目录树。
⇒ 模型完全活在**自己的服务端沙箱**里，工具链与用户设备脱离；那些目录全是幻觉，不是执行结果。

### 三层根因与修法
1. **system 里工具只有名字**（bare backticks），无描述、无参数 schema → 上游不认成可调用对象，
   直接当成「客户端有个同名脚本」。改 `rich=true`：描述 + 真实参数名与类型
   （`typedParams` 输出 `command(string!)` 形式，required 优先序、数组 items 兜底、其余 any）。
2. **工具协议只在首轮发**，而 Runable `/api/chat/text` 自存对话、沿用自己的原生工具做法。
   改：非首轮每轮注入 `toolReminder`（工具名清单 + 标记格式 + 路径作用域三条硬约束）。
3. **图像能力未声明**（vc81 的 `architecture.input_modalities` 一并落地）：Minis 侧
   `VoiceModality.hasImageInput` 读 `/v1/models` 的该字段；未声明则图被降级成文本占位符，
   服务端模型永远收不到 `image_url`。

附带修 bug：工具清单末尾残留 `s}` —— `{tool_definitions}` 占位符替换长度算错（`td+16`），按真实长度替换。

### 验证
离线真实类（RunableBridge + ChatglmToolBridge + SseTranslator + org.json）：
TestRunableToolchain 58 / TestRunableBridge 66 / TestRunableBridgeLocal 39 / TestRunablePromptBudget 34
= **197 全过 0 败**。dex grep `客户端工具提醒` FOUND、`input_modalities` FOUND；aapt2 versionCode=82。
构建 `gradle :app:assembleRelease --no-daemon --no-configuration-cache -x lint` 41s。
签名输入是 `app-release-unsigned.apk`（本项目 release 产物名与 TattooMusic 不同）。

### 判据（模型输出里出现什么算真跑通）
真跑通 ⇒ 模型输出以 `[[RUNABLE_TOOL_CALL]]` 开头，且**工具结果是网关回灌的真实文本**
（真实 skills 列表含 `reverse-engineer-anything`/`prevent-drift` 等手机端独有技能），
引用路径是 `/var/minis-euleros/...`。
仍失败 ⇒ 输出里没有任何 `[[RUNABLE_TOOL_CALL]` 标记，说明它仍在自己沙箱里瞎编。

### 教训
- **文本协议的工具链，默认对方只当「文档」**。要模型真调，必须给足 schema + 每轮重申协议，
  首轮声明会被上游自存对话的历史冲掉。
- **模型自述的目录树 ≠ 执行结果**。`/home/user/Attachments` 里出现本轮附件名，
  说明 Runable 侧确实存在同名镜像目录，它在读那份镜像 —— 更要命。
- 判断改动进 APK：`unzip classes*.dex` + `grep -a` 真实中文串（Java 字符串常量被折叠成一整条 MUTF-8）。
### 真机验证（2026-10-11 08:22 用户截图，zai/glm-5.3-flash）—— 通过
- 发图问「图片能收到吗」→ 正确描述「塑料分格盒里 8 片（4 行 × 2 列）」，说明**图像走 attachment 通道**（vc79/81）已通。
- 让它「检查 skill」→ 输出真实 skills 列表：`ai-gateway-skill`(10-09)、`app-pentest-analyzer`(10-09)、
  `skill-creator`(9-11)、`vivo-car-atomic-lyrics`、`concise-writing`、`edit-code-safely`、`prevent-drift`；
  与手机实测 `ls /var/minis-euleros/skills/*/` 的 8 个目录逐一对应（含手机端独有技能）。
  还主动给出 `file_read` 调用去读 SKILL.md frontmatter，检查 version 字段是否齐备。
⇒ **三层同时成立**：rich 工具块 + 每轮 toolReminder 让它真调工具；工具结果经网关回灌真实文本；
  路径语义是 /var/minis-euleros/ 而非 /home/user。vc75~vc82 这条线（envNote → 附件通道 → input_modalities → 工具链）闭环。

## vc83（2026-10-11）：Runable 凭证刷新 + 每日积分兜底 + 禁用收费分组化 + 供应商页积分缓存
产物 `shared/codearts7/CodeArts7_1.0.0_vc83-20261011-runable-refresh-cache-signed.apk`
sha256 e42e3e756c8f55a3a7fce7f5f112f1c61abae894eab5c3340a6013b2e63ff42c，4,739,886B，vc83，923 条目 CRC ALL OK，7tattoo，commit c171d7b。

用户四项需求：
1. **Runable 账号不能刷新凭证** → refreshFingerprint() 走 better-auth token 端点。
   网页登录时从 Set-Cookie 顺手捞 refresh_token（`extractCookieFromSetCookie` + `stripPrefix`）存进凭证；
   `GET /api/auth/token` + `Bearer <refreshToken>` → 响应/Set-Cookie 取新 session_token（+新 refresh_token）→
   `persistRefreshedCreds` 写回当前账号（保留 email/name）。无 refresh_token 的旧账号回退校验 session 存活
   （`fetchSession`），活=刷新成功；失效则 `lastRefreshError="session 已失效，请重新登录（无 refresh_token 无法自动续期）"`。
   ProviderDetailActivity 展示 `lastRefreshError()`（Runable 与 Raccoon 分支分别取）。

2. **每日积分（每日 1500）不能自动领取** → 用户表述是"自动领取"，但本 APK 无登录奖励领取能力（Raccoon 才有 claimLoginPoints）；
   真因是**积分显示为 0**（看似没领到）。`creditsText` 里 credits.daily_credits 的 breakdown 若无 `reset.interval=="day"` 项
   → daily=0 被显示；修：daily==0 时兜底直接用 `daily_credits.remaining`。

3. **禁用收费 → 只针对 Runable 分组 + 可解除** → `disablePaidModels()` 固定 pid="runable"（全局入口也传 "runable"）；
   新增 `isPaidAllDisabled(pid)` 判断该组收费模型是否全部已禁用 → 按钮文案「禁用收费」/「解除禁用」；
   `disablePaidModels` 变 toggle：`cur.containsAll(paid)` 则 removeAll（解除），否则 addAll（禁用）。
   非 Runable 分组不再显示该按钮。

4. **供应商页热启动加载慢 → 积分缓存 + 仅热启动增量更新** → 新增 `CreditsCache`（TTL 15min，`files/credits_cache.json`，
   key=`providerId|accountId`，存 credits/creditsTotal/ts）。
   `renderProviders(force)`：默认 false；`collectCreditsText(p,n,force)` 分两遍——第一遍命中 TTL 缓存的直接累计，
   只把过期账号收进 need；第二遍仅对 need 联网拉取，成功后 `put`，失败沿用旧缓存不清空。
   页面切到供应商 tab 仍只渲染一次（providersLoaded），普通点击不联网；只有「刷新」按钮 `renderProviders(true)` 强制联网。
   热重启 Activity 重建 → providersLoaded=false → 首次进入走增量更新。

### 踩坑
- ProviderDetailActivity 用 `RunableProvider` 却没 import → 4 处 `cannot find symbol`（javac 报错定位明确）。
- versionCode 是 build.gradle 硬编码（82→83），不是 git commit count（TattooMusic 才是）。
- 同批多项需求由并行会话落地，`git diff` 是别人的改动；编译报错才是我的活（补 import 后即通）。

### 判据/验证
dex grep 新字符串全命中：credits_cache.json(1)、refresh_token(11)、getCustomer(1)、解除禁用(2)、禁用收费(1)、session_token(6)。
aapt2 versionCode=83；构建 52s；CRC ALL OK 923 条目。

## vc90 已交付 / vc91 受阻（2026-10-12 14:5x）
- **vc90**：`shared/codearts7/CodeArts7_1.0.0_vc90-20261012-glmrefresh-signed.apk`
  sha256 ecea8d7e4f74e542607bdee630be50ff22446c117d816f3e87d4ea8ff4c205c9，4743982B，CRC OK。
  内容 = **智谱 GLM 刷新端点修正**（并行会话的 keepAlive/调度/自动刷新 + 我加的 user-api 通道）。
- **问题1 的实证根因**：`GET /api/auth/token` 在 agentmore.chatglm.cn 上返回的是 **SPA 的 HTML**（不是 JSON），
  所以 12:35→12:44 过期后刷新必然失败，报"刷新接口返回的不是 JSON"。
  用户附的参考包 `gw_1.5.10_vc17-envfix-signed.apk` 里翻出真端点：
  `POST /chatglm/user-api/user/refresh` + cookie `chatglm_refresh_token=<rt>` + body `{"refreshToken":rt,"remember":true}`
  （响应 set-cookie: chatglm_access_token / chatglm_uid / chatglm_phone）。
  假 token 实测：该端点 **400 bad request(40001)** = 端点存在并校验凭证；而 /api/auth/ 系列全是 **404 SPA HTML**。
  实现：`refreshViaUserApi()` 成功写回 cookie_token/session_token/chatglm_uid(+uid)，失败再退 /api/auth/token；
  两条都失败才置 lastRefreshError，且**不降级成"需要重新登录"**（保留凭证，符合用户规矩）。
- **问题2**：并行会话已整体删掉账号页底部「添加账号」栏（`provider_add_account`/`pd_addAccount`/`pd_bottomBar` 全部无引用），
  顶部按 provider 显示 网页登录 / 粘贴凭据 JSON 导入 / 短信登录。已核验，无需再改。
- **问题3/4（模型不认本地沙箱）：vc90 里还没有**。设备实测探针（/proc/mounts + ls /var/minis-euleros + skills +
  uploads 真实文件名 + 可用空间）代码已写，但 **被并行会话的后续编辑覆盖**，dex grep `probeSandbox` MISSING。
- **vc91 编译当前受阻**：`ChatglmToolBridge.java:203 error: text blocks are not supported in -source 11`
  —— 该 `"""` 文本块不是我写的（14:12 我构建 vc90 时树还是好的），需并行会话改成字符串拼接或把
  compileOptions source/target 抬到 14+。**别两边同时改这个文件**。
- 复核结论（14:5x）：vc90 dex 里 `sandboxFacts` / `挂载来源` / `实测：共` **全部 MISSING**，
  `chatglm/user-api/user/refresh` FOUND ⇒ v90 = 只带刷新修复，未带沙箱实测。
  原因：`ChatglmToolBridge.java` 在我 14:07 写入后被并行会话整体覆盖重写，新版含 `"""` 文本块，
  而 `app/build.gradle` 是 `sourceCompatibility JavaVersion.VERSION_11` → 14:47 起整棵树编译失败。
  **规矩：该文件与 RunableBridge.java 同一时间只能有一个会话在改。**

---

## v92 沙箱探测注链 修复（2026-10-12 14:15）
产物 `shared/codearts7/CodeArts7_1.0.0_vc92-20261012-sandboxprobe-fix-signed.apk`
sha256 1ae1049a85395aed0cd4e4674dcfaf8678978f59df894592c479df11cbbc403f，vc92，7tattoo。

### 真因（一句话）
v91 编译失败：`ChatglmToolBridge.java:203` 是 `split("\s+")` —— **java 里 `\s` 是非法转义**，
javac 报「text blocks are not supported in -source 11」，把整棵树卡住，v91 没出包。
这个 `\s` 是我用 python 三引号 heredoc 写临时脚本时把 `\\s` 转义掉的（python 会解释 `\\`→`\`），
**别再用 python 三引号生成含反斜杠转义的 Java 源码，会单斜杠化**。

### 修法
- `sed 203s/"\s+"/"\\s+"/`（正则里要匹配空白就得写双斜杠）。
- `sed versionCode 90/91 -> 92`。`gradle :app:assembleRelease` 48s BUILD SUCCESSFUL。
- release 产物名是 `app/build/outputs/apk/release/app-release-unsigned.apk`（不是 aligned，本仓库与 TattooMusic 不同）。

### 关键：树其实比我记忆里更完整（用户确认 B：同一会话，之前"并行"是同一个模型触发循环 bug 没改完）
- `LocalProbe.java`（provider/）**已存在**：/proc/mounts 里过滤 `minis` + rootfs/workspace/euleros 的挂载点（最多 3 个），
  返回 `挂载点(挂载选项)`，读不到返回 null。这就是认领本地沙箱的实测证据。
- `ChatglmToolBridge.envNote()` 末尾已接 `LocalProbe.note()`（智谱 agent-api 通道），
  `RunableProvider.envNoteExtra()` 里也调 `LocalProbe.note()`（Runable 通道）。
  v92 之前这些代码在源码里但**从未编进 APK**（v90 dex grep 缺 `sandboxFacts` 即证据）——被 :203 卡住了。
- dex 核验 v92：`LocalProbe`=3 / `sandboxFacts`=2 / `/proc/mounts`=1 / `runable_image_host`=1 / `envNoteCompact`=1 全在。
- 待办仍未做：Runable 图床直链注入（问题3）、智谱/Runable 凭证刷新（问题1）、"添加账号"入口隐藏（问题2）。
  vc83 的 refresh_token 链路只覆盖 Runable+Raccoon 凭证，智谱账号过期仍需确认是否复用。

### 交付命名
`CodeArts7_1.0.0_vc92-20261012-sandboxprobe-fix-signed.apk`

--- 2026-10-12 16:35  v93  QoderProvider 完整落盘 ---

## CodeArts7 vc93：QoderProvider 全链路交付（2026-10-12 16:35）

产物 `shared/codearts7/CodeArts7_1.0.0_vc93-20261012-qoder-provider-signed.apk`
sha256 ebe75df7e8da873ff52fb4fb203528f93f3f680b765bae72fad751e5c276dc4a，4,748,078B（4.53MiB），
vc93，923 条目 CRC OK，7tattoo（v2+v3），构建 34s。

### 完整路由链（v90→v93 收尾）
1. `App.java`：`qoder()` 静态访问器注册 + `providers.put("qoder", qoder())`。
2. `App.java`：`MODELS.put("qoder", MODEL_GROUP_QODER)`（新增 `MODEL_GROUP_QODER`）。
3. `ProviderDetailActivity.java`：`MODEL_GROUPS` 加 "qoder"（含 QoderPinned / QoderQuota / 两个按钮方法）。
4. `QoderProvider.java`：参考 RaccoonProvider 完整实现（chat/chatStream/embeddings/refreshFingerprint/snapshot 全部委派 QwenOAuthClient）。
5. `QwenOAuthClient.java`：114 行 Java 17 → **完整 1:1 翻译后无任何外部依赖**（无 BearerTokenSource / ZhipuAuthGate / RunableHttpRetry 引用），纯 java.net / org.json。
6. `App.java` 补 import 3 处（MODELS / providers / `qoder()` 方法）。
7. `QoderProvider.java` `AccountPoolHolder` 跨包 → 全限定 `com.aigetway.tattoo7.account.AccountPoolHolder`。
8. `ProviderDetailActivity:549` 调 `q.addKey(String)` → 新增 `addKey` 重载（用 `pool().size()` 而非 `all().size()`，因 AccountPool 直接有 size()）。
9. `<Object>` cast 技巧：Java 17 `if (obj instanceof Provider p)` 后写进 `Provider[]` 需先 cast。

### 三个参考仓库的取舍
- gitee.com/iJetLi/deepseek-harness-codearts：原始 Java 实现（doom-dll 风格），命名/分类全锁死，且不是我仓库的某一次 commit。
- gitcode.com/gh_mirrors/ds/dsh-our-free-model：另一独立项目，HTML/JS 前端。
- 二者**都不直接合入**，只取"qoder 是阿里系、OpenAI 兼容、需 OAuth"的认知 + "Provider 命名按厂商名 qoder"的命名约定。

### 关键认知更新
- **`<Object>` cast 技巧**：Java 17 `if (obj instanceof Provider p)` 写进 `Provider[] arr` 数组必须先 cast。
- **`AccountPool` 有 `size()`** 直接用，不要 `all().size()`。
- **`AccountPoolHolder` 在 `account` 包**，不在 `provider` 包，provider 包内调用要全限定。
- **构建 34s**，38 条任务 up-to-date。
- **dex 验证**：`QoderProvider` 类 + `HttpLong` 内部类 + `https://api.qoder.ai` 3 个端点字符串全 FOUND。

### 下一轮（用户原始三问）
1. 智谱GLM 账号到期无法刷新凭证（未动）
2. Loomy讯飞/智谱GLM/Runable 账号内页「添加账号」入口隐藏（未动）
3. ✅ 本轮 QoderProvider 完整落地，主体已交付。

---

## 2026-10-13 换源码包重建（vc93 第二次，内容变更）

- 用 `/var/minis-euleros/mounts/MT2/7tattoo/CodeArts7_src_20261013.tar.gz` 整包替换 workspace 源码。
- 新树：移除 Runable / 智谱GLM(Chatglm) / WorkBuddyIntl 三个 Provider 及配套（RunableBridge/ChatglmToolBridge/LocalProbe/ZhipuProvider/网页登录两个 Activity/runableImageHost）；**Qoder 重写为真 OAuth 设备码授权流**（dex 含 `deviceToken`/`grant_type`/`qoder.sh`）；LoginActivity/MainActivity/ProviderDetailActivity/activity_main.xml 均更新。
- **新树自带一处编译错误**：LoginActivity 里两处裸 `buddyProvider()` 漏写 `App.get().` 前缀，已补全（593、1008 行）。
- 构建 `:app:assembleRelease` BUILD SUCCESSFUL，versionCode 仍 93（沿新树未改），versionName 1.0.0。
- 交付：`shared/codearts7/CodeArts7_1.0.0_vc93-20261013-src20261013-qoderoauth-signed.apk`
  sha256 363515063ba08c2e7ae31110b58190a38278546c87556712ebe4503a1809677a，7tattoo(v2+v3)，923 条目 CRC OK。
- 本地旧源码备份：`shared/codearts7/backup-codearts-local-before-src-20261013.tar.gz`
