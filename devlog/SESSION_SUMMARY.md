# CodeArts7 会话压缩摘要（截至 2026-10-12 14:15，v92）

## 一句话现状
`shared/codearts7/CodeArts7_1.0.0_vc92-20261012-sandboxprobe-fix-signed.apk` 已出包（sha256 1ae1049a…3c403f，vc92，7tattoo）。
本次会话**唯一产出**是修复 v91 编译失败 + 让沙箱探测 LocalProbe 首次真正编进 APK。

## 本次会话用户原始三问
1. 供应商→智谱GLM 账号到期自动过期且无法刷新凭证。
2. 供应商→Loomy讯飞/智谱GLM/Runable 账号内页底部「添加账号」入口隐藏（功能坏）。
3. 智谱GLM/Runable 模型无法认领本地沙箱。

## 关键定性（用户明确）
- 之前怀疑「并行会话改了同一批文件」是**错的**：只有一条线，是**模型触发循环 bug 没改完**。
- 因此所有未提交改动都归本会话所有，直接修，不必甄别来源。
- 后续出现「别的会话改动」的 diff，先想是不是自己上轮半途而废。

## 本次真因与修法
- 真因：`ChatglmToolBridge.java:203` `split("\s+")` —— Java 里 `\s` 非法转义，javac 报
  「text blocks are not supported in -source 11」，把整棵树卡住 → v91 从未出包。
  该 `\s` 是 python 三引号 heredoc 生成脚本时把 `\\` 解释成 `\` 所致。
- 修：`sed 203s/"\s+"/"\\s+"/` + versionCode 90/91→92；`gradle :app:assembleRelease` 48s SUCCESS。
- 核验：dex grep `LocalProbe`=3 / `sandboxFacts`=2 / `/proc/mounts`=1 / `runable_image_host`=1 / `envNoteCompact`=1 全 FOUND；
  aapt2 versionCode=92，package com.aigetway.tattoo7。
- 重要：`LocalProbe` + `ChatglmToolBridge.envNote()`/`RunableProvider.envNoteExtra()` 注入**源码早已就位**，
  只是被 :203 卡住**从未编进 APK**（v90 dex 缺 sandboxFacts 即证据）。

## 工程约定（本仓库，务必沿用）
- 构建：`cd /var/minis-euleros/workspace/codearts && JAVA_HOME=/opt/jdk21-home gradle :app:assembleRelease --no-daemon --no-configuration-cache -x lint`（增量 20~50s）。
- release 产物名是 `app/build/outputs/apk/release/app-release-unsigned.apk`（**不是** aligned；与 TattooMusic 不同）。
- 签名：`sign-apk app-release-unsigned.apk /tmp/out`（7tattoo，v1/v2/v3，对齐）。
- 交付目录：全局 `/var/minis-euleros/shared/codearts7/`（仓库内还有个同名相对路径，`cp` 会投错，写完必须 ls -la）。
- versionCode 是 **build.gradle 硬编码**（每版 +1），不是 gitCommitCount。
- 交付命名：`CodeArts7_1.0.0_vc<code>-<日期>-<标签>-signed.apk`。

## 判断「改动是否进 APK」的方法
`dex grep 新增字符串 + 源码 mtime < APK 构建时间` 双向确认；别只信 git（工作区可能有未提交改动）。
校验用源码里的**真实子串**，不要凭记忆构造。

## 教训清单
- 别用 python `'''...'''` heredoc 生成含反斜杠转义的 Java 源码（会单斜杠化）。
- 文本协议的工具链默认对方只当文档：要有 schema + 每轮重申协议，首轮声明会被上游自存对话冲掉。
- 图片**必须走官方上传通道**，把 URL/路径当文本发服务端模型 ≠ 送达。
- 图床可用性必须**计时**判断（连上但卡死 ≠ 拒连）；本沙箱对 catbox 域名级拦截、litterbox 403。
- 同一仓库可能有未提交改动/其他会话痕迹，grep 结果自相矛盾时先 `git status/log` 划清 HEAD 边界。

## 前序版本主线（v74→v91，供追溯）
- v74 Runable PROMPT_LIMIT_EXCEEDED → 只发最后一条 user + system 字节截断。
- v75 环境声明 envNote + 图片 data→公网直链三源兜底。
- v76 图床顺序 uguu 秒级 + 自定义直链源 `runable_image_host` + 每源计时日志。
- v77 Minis 发的是**纯文本路径**非 image part → 网关读手机文件换传 + 每轮注入 envNoteCompact。
- v78 换传收尾：clipBytes 保尾截断 + 上传失败原因 + Budget 档位重试。
- v79 Runable 图片走官方附件通道（getAttachmentUploadUrls）+ 顶层 id 用真实 chatId。
- v80 曾误判「Minis 不发图 APK 无解」→ v81 推翻：网关 `/v1/models` 返回 `architecture.input_modalities`。
- v81 Minis 原生识别 input_modalities → 多模态自动启用。
- v82 Runable 工具链脱离设备 → rich 工具块 + 每轮 toolReminder + 图像声明；判据：输出 `[[RUNABLE_TOOL_CALL]]` + 回灌真实工具结果。
- v83 Runable/Raccoon 凭证 refresh_token 刷新 + 每日积分兜底 + 禁用收费分组化 + 供应商页积分缓存（15min）。
- v90 前沙箱探测代码写好但被 :203 卡住未编入。
- v92（本轮）修 :203，LocalProbe 首次进 APK。

## 遗留待办（下一轮）
1. **问题1**：智谱GLM 账号过期无法刷新凭证 —— 需确认智谱 Provider 凭证是否走 vc83 的 refresh_token 链路（vc83 只覆盖 Runable+Raccoon）。参考 APK `gw_1.5.10_vc17-envfix-signed.apk`（用户附件）。
2. **问题2**：Loomy讯飞/智谱GLM/Runable 账号内页底部「添加账号」入口隐藏。
3. **问题3**：智谱GLM/Runable 认领本地沙箱 —— v92 主体已交付，待真机验证（问「你运行在哪/能看到哪些目录」）。
