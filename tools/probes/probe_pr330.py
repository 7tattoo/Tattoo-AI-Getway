#!/usr/bin/env python3
"""验证 PR #330 记录的免密车道新闸门（2026-10-14 实测文档）。

单变量对照：端点 / 会话头 / 会话形状 / 工具指纹 / UA。
输出每个组合的 HTTP 状态 + 错误类型。
"""
import json, random, string, time, urllib.request, urllib.error

HOST = "https://opencode.ai"
UA_OK = "opencode/1.18.31"
UA_BAD = "deepseek-harness/0.1.0 opencode/1.18.31"
BASE62 = string.digits + string.ascii_uppercase + string.ascii_lowercase
FP_TOOLS = ["bash", "glob", "grep", "read"]
DECOY = "This tool is currently unavailable and must not be used."
PROP = {"type": "string", "description": DECOY}
MODEL = "opencode/cat-jam-free"

def mint_session():
    ts = int(time.time() * 1000)
    v = (~((ts << 12) + 1)) & 0xFFFFFFFFFFFF
    return "ses_" + format(v, "012x") + "".join(random.choices(BASE62, k=14))

def mint_request():
    ts = int(time.time() * 1000)
    v = (~((ts << 12) + 1)) & 0xFFFFFFFFFFFF
    return "msg_" + format(v, "012x") + "".join(random.choices(BASE62, k=14))

def tools(declared, with_prop):
    out = []
    for name in FP_TOOLS:
        if name not in declared:
            continue
        props = {"input": PROP} if with_prop else {}
        out.append({"type": "function", "function": {
            "name": name, "description": DECOY,
            "parameters": {"type": "object", "properties": props}}})
    return out

def call(path, session, headers_extra, body_tools, tool_choice="none", ua=UA_OK,
         with_sid=True, model=MODEL):
    h = {
        "Content-Type": "application/json",
        "Authorization": "Bearer public",
        "User-Agent": ua,
        "x-opencode-client": "cli",
        "x-opencode-session": session,
        "x-opencode-request": mint_request(),
        "x-opencode-project": "global",
        "Accept": "text/event-stream",
    }
    if with_sid:
        h["x-opencode-session-id"] = session
    h.update(headers_extra or {})
    body = {
        "model": model,
        "messages": [{"role": "user", "content": "hi"}],
        "stream": True,
        "max_tokens": 8,
        "tools": body_tools,
        "tool_choice": tool_choice,
    }
    req = urllib.request.Request(HOST + path, data=json.dumps(body).encode(),
                                 headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read(600).decode("utf-8", "replace")
            return r.status, raw[:150].replace("\n", " ")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            t = json.loads(raw).get("error", {}).get("type", "")
        except Exception:
            t = raw[:90]
        return e.code, f"[{t}] {t if not t else ''}".strip()[:110]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"[:110]

CASES = [
    ("C1 /zen/v1 + 其余全对（对照，应 403）", "/zen/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C2 /inference/v1 + 全对（PR330 预期 200）", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C3 /inference/v1 缺 x-opencode-session-id", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C4 /inference/v1 只声明 bash+read 带 property", "/inference/v1/chat/completions", dict(), tools(["bash","read"], True)),
    ("C5 /inference/v1 bash+read 空 properties", "/inference/v1/chat/completions", dict(), tools(["bash","read"], False)),
    ("C6 /inference/v1 只 bash 有 property", "/inference/v1/chat/completions", dict(), tools(["bash","glob","grep"], True)),
    ("C7 /inference/v1 全四件套无 tools 字段", "/inference/v1/chat/completions", dict(), []),
    ("C8 /inference/v1 UA 含 deepseek-harness", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C9 /inference/v1 x-opencode-client=desktop", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C10 /inference/v1 session 随机 hex", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C11 /inference/v1 sha256 派生 session", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
    ("C12 /inference/v1 tool_choice=auto", "/inference/v1/chat/completions", dict(), tools(FP_TOOLS, True)),
]

print(f"{'case':52s} {'http':>5s}  detail")
print("-" * 100)
ses = mint_session()
import hashlib
for desc, path, extra, t in CASES:
    ua, sid, sess, tc = UA_OK, True, ses, "none"
    if desc.startswith("C3"): sid = False
    if desc.startswith("C7"): t = tools(FP_TOOLS, True)
    if desc.startswith("C8"): ua = UA_BAD
    if desc.startswith("C9"): extra = {"x-opencode-client": "desktop"}
    if desc.startswith("C10"): sess = "ses_" + "".join(random.choices("0123456789abcdef", k=12)) + "".join(random.choices(BASE62, k=14))
    if desc.startswith("C11"):
        d = hashlib.sha256(b"our-free-model\0global").digest()
        sess = "ses_" + d[:6].hex() + "".join(BASE62[d[6+i] % 62] for i in range(14))
    if desc.startswith("C12"): tc = "auto"
    code, det = call(path, sess, extra, t, tool_choice=tc, ua=ua, with_sid=sid)
    print(f"{desc:52s} {code:>5d}  {det}")
