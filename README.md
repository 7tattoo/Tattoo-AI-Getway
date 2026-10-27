# Tattoo-AI-Getway (CodeArts7)

Android 本地 AI 网关（包名 `com.aigetway.tattoo7`）：把多个 AI 供应商（AutoClaw / TRAE / Buddy / Raccoon / Loomy / OpenCode / AStudio 等）聚合成一个 OpenAI 兼容的本地 HTTP 网关，内置账号池、每日签到 / 积分领取、模型目录归一与多账号轮询。

## 构建

```bash
# 需 Android SDK (android-34+) 与 JDK 17+
gradle assembleRelease
```

`compile.sh` 可做 provider 核心层的快速 javac 校验。

## 目录

- `app/src/main/java/com/aigetway/tattoo7/provider/` — 各供应商适配
- `app/src/main/java/com/aigetway/tattoo7/gateway/` — 本地 HTTP 网关
- `sim/` — 逻辑对照模拟测试
- `ref/` — 协议规格笔记
- `PROGRESS.md` — 迭代日志
