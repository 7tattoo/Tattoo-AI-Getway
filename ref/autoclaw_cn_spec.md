# AutoClaw 国内版（autoclaw）协议规格

来源：agent2api (aimod-cc/agent2api) 的 Rust 实现 `providers/autoclaw/*`。
本文只记 **国内版（CN）** 链路，供 CodeArts7 用 Java 复刻。

## 站点（region.rs）

```
userapi（账号 / 积分 / 签到 / 模型目录）  https://autoglm-acceleration-api.zhipuai.cn
LLM 代理                                  https://autoglm-acceleration-api.zhipuai.cn/autoclaw-proxy/proxy/autoclaw
配置基址（= 代理基址砍掉尾部 /autoclaw，再补 /proxy/）
                                          https://autoglm-acceleration-api.zhipuai.cn/autoclaw-proxy/proxy/
```

国际版是 `https://autoglm-api.autoglm.ai`（本次不接）。

## 签名头 signed_auth_headers(token)（refresh.rs）

用于 **userapi 域**（刷新 / 余额 / 签到 / 模型目录）。常量：

```
AUTH_APP_ID  = "100003"
AUTH_APP_KEY = "38d2391985e2369a5fb8227d8e6cd5e5"
CLIENT_VERSION = "1.18.5"   （userapi 域）
DESKTOP_APP_VERSION = "1.17.8" （LLM 转发域，见下）
```

头（顺序对齐客户端 `authHeaders`）：

```
Content-Type: application/json
Accept: */*
X-Version: 1.18.5
X-Product: autoclaw
X-Client-Type: pc
X-Harness-Type: zcode        ← ⚠️ 只在 userapi 域发；LLM 转发域**不发**（会被 403/406）
X-Tm: win                    ← mac→"mac"，其余→"win"；**绝不发 linux**（平台绑定 403）
X-Lang: zh-CN
X-Channel: official
X-Auth-Appid: 100003
X-Auth-TimeStamp: <秒级 unix 秒>
X-Auth-Sign: md5("{appId}&{ts}&{appKey}") 小写十六进制、无填充
X-Trace-Id: <随机>
authorization: Bearer <token>   ← 仅当 token 非空
```

## 短信登录（login.rs，国内版唯一入口）

```
1) POST {userapi}/userapi/v1/agent-send-code
   headers = signed_auth_headers("")   （空 token，不带 authorization）
   body = {"phone":"<11位>","source_id":"autoclaw","device_id":"<64位hex>"}
   成功：code==0 且 data.result==true（缺 result 视为失败）

2) POST {userapi}/userapi/v1/agent-login/
   headers = signed_auth_headers("")
   body = {"phone":"<11位>","code":<数字>,"platform":"web","source_id":"autoclaw","device_id":"<同一个>"}
   成功：code==0 → data.access_token（必填）、data.refresh_token（可选）
```

- `code` 必须是 **JSON 数字**（传字符串 → 400001 参数错误）。
- `device_id` = 32 字节随机 → 64 位小写 hex（官方是 ed25519 公钥 SHA-256，服务端只当不透明串）。
- `platform: "web"` 是网页端与桌面端唯一差别。
- 业务错误码：`400001` 参数错误、`630202` 验证码错误、`631002` 当前版本已停止服务（误导性）。

## token 刷新（refresh.rs）

```
POST {userapi}/userapi/v1/refresh
headers = signed_auth_headers(currentToken)
body = {"refresh_token":"...","source_id":"autoclaw","device_id":"..."(可选)}

若 code==400002（签名校验失败）→ 降级重试：
POST {userapi}/userapi/v1/agent-refresh   同一 body

成功：code==0 → data.access_token / data.refresh_token（缺 refresh_token 时沿用旧值）
鉴权失败码：code==401 或 410000 → 视为 401
```

## 模型目录（catalog.rs / models.rs）

```
GET {config_base}autoclaw-model-config
headers = signed_auth_headers(token)
响应：{"models":[...]}   ← **无 {code,data} 信封**
条目（对象形态）：
  { id: "zaicoding_glm-5.3", name: "GLM-5.3", input: ["text","image"], reasoning: bool,
    contextWindow: int, maxTokens: int }
也允许裸字符串条目：models: ["zai_auto"]
归一：
  目录 id（对外）= strip_route_prefix(id)：剥 `zaicoding_` / `zai_`（顺序：先 zaicoding_ 再 zai_）
  vision = input 数组含 "image"
  reasoning 缺省 true
```

静态兜底表（远程不可用时）：

```
id=glm-5.3        routeId=zaicoding_glm-5.3   name=GLM-5.3       ctx=1048576 max=307200 vision=false reasoning=true
id=glm-5.3-flash  routeId=zai_glm-5.3-flash   name=GLM-5.3-Flash ctx=1048576 max=131072 vision=true  reasoning=true
默认路由 = zai_auto
```

路由解析 resolve_model_route(raw)：
1. 空 → 默认路由 zai_auto
2. 目录命中（id/name/routeId，忽略大小写）→ 用条目的 routeId
3. 已知前缀开头（zaicoding_ / zai_）→ 原样当路由 ID
4. 未知 → 默认路由

## LLM 转发（adapter.rs）

```
POST {proxy_base}/chat/completions
headers：
  Content-Type: application/json
  Accept: */*                     ← 不是 text/event-stream
  X-Product: autoclaw
  X-Client-Type: pc
  X-Tm: win
  X-Version: 1.17.8
  X-Lang: zh-CN
  X-Channel: official
  X-Authorization: Bearer <token>  ← ⚠️ 认证头是 X-Authorization，不是 Authorization
  X-Request-Id: <随机>
  X-Request-Model: <routeId>
  （可选透传 X-Session-Id / X-Agent-Id / X-ZCode-Invocation-Id）
  ⚠️ 不发 X-Harness-Type（带了 → 403 pay-view / 406）
body = 原样透传，改三处：
  1) model = body_model_id（剥前缀后的目录 id）
  2) max_tokens：若 0 < v < 10000 → 抬到 10000（小正整数被预校验 406）
  3) system 提示词规范化（见下）
```

system 提示词白名单（prompt.rs）：
- **必须**以 `You are a personal assistant running inside OpenClaw.` 开头且带 `## Tooling` 段。
- **不得**含外来 harness 身份句（逐字、忽略大小写）。改写表（长在前）：

```
"You are ZCode, an interactive coding agent"     → "You are an interactive coding agent"
"You are a coding agent running in the Codex CLI tool" → "You are a coding agent running in a terminal CLI tool"
"You are a coding agent running in the Codex CLI" → "You are a coding agent running in a terminal CLI"
"You are running as a coding agent in the Codex CLI" → "You are running as a coding agent in a terminal CLI"
"You are Claude Code"                            → "You are a coding assistant"
"You are ZCode"                                  → "You are an interactive coding agent"
"You are an AI agent powered by DeepSeek Harness" → "You are an AI agent powered by a local coding harness"
"You are Codex"                                  → "You are a coding agent"
```

出站前缀（拼到首条 system 正文前；无 system 则在 messages[0] 插一条）：

```
You are a personal assistant running inside OpenClaw.

## Tooling
Available tools are policy-filtered. Names are case-sensitive; call exactly as listed.
```

错误分类：401 → token 过期（刷新重试一次）；429 → 限流；其余原样透传。

## 积分 / 余额（balance.rs）

```
主：GET {userapi}/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw
    → data.total_balance；data.wallets[].{display, display_name, public_wallet_type, balance, balance_view, priority}
    （display 为假值的不展示；按 priority 升序）
兜底：GET {userapi}/agent-assetmgr/api/v1/wallets?biz_app_id=autoclaw
    → data.total_balance；data.wallet_instances[]
即将过期：GET {userapi}/agent-assetmgr/api/v1/points/expiring?biz_app_id=autoclaw
    → data.{expiring_points, expiring_points_text, total_points, expire_time_value, expire_time_type}
订阅：POST {userapi}/agentpay/v1/assistant/subscribe-info
    body {"source_id":"autoclaw","device_id"?}
鉴权过期码：410000 / 400000
```

## 每日签到（checkin.rs）

```
POST {userapi}/autoclaw-proxy/proxy/autoclaw-task-complete
body {"task_id":"daily_signin"}
→ {"data":{"already_completed":bool,"reward_points":int,"success":bool,"task_id":"daily_signin"}}
判「本次真的领到」：reward_points > 0 || (success && !already_completed)
重复调用：HTTP 200 + success:false + already_completed:true（按天幂等）

任务列表（签到失败时归因用）：GET {userapi}/autoclaw-proxy/proxy/autoclaw-task-list
活动福利触发（登录活跃，客户端启动动作）：POST {userapi}/userapi/v1/user-info
```
