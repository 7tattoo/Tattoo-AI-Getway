#!/usr/bin/env python3
"""按用户给的配方实测：cat-jam-free 裸 id @ /inference/v1"""
import json, urllib.request, ssl

BASE = "https://opencode.ai"
HDRS = {
    "Authorization": "Bearer public",
    "User-Agent": "opencode/1.18.31",
    "x-opencode-client": "desktop",
    "x-opencode-project": "global",
    "Content-Type": "application/json",
    "Accept": "text/event-stream",
}
ctx = ssl.create_default_context()

TOOLS = [
    {"type": "function", "function": {"name": "bash", "description": "Run shell command",
     "parameters": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}}},
    {"type": "function", "function": {"name": "read", "description": "Read file",
     "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
]


def post(path, model, tools=True, note=""):
    body = {"model": model, "stream": True, "max_tokens": 8,
            "messages": [{"role": "user", "content": "hi"}]}
    if tools:
        body["tools"] = TOOLS
    h = dict(HDRS)
    h["x-opencode-session"] = "ses_019a1b1f9a6aC4BZsF5oWko2fq"
    h["x-opencode-request"] = "msg_13d5fe53b001Htmux9OxFQMA29"
    try:
        req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=h, method="POST")
        r = urllib.request.urlopen(req, timeout=25, context=ctx)
        code = r.status
        chunks = []
        for _ in range(6):
            line = r.readline().decode("utf-8", "replace").strip()
            if not line:
                continue
            chunks.append(line[:120])
            if len(chunks) >= 6:
                break
        r.close()
        print(f"[{code}] {note:<34} {path}\n        model={model!r} tools={'Y' if tools else 'N'}")
        for c in chunks[:4]:
            print(f"          {c}")
        return code
    except urllib.error.HTTPError as e:
        print(f"[{e.code}] {note:<34} {path}\n        model={model!r} tools={'Y' if tools else 'N'}")
        try:
            print("          body:", e.read().decode("utf-8", "replace")[:220])
        except Exception:
            pass
        return e.code
    except Exception as e:
        print(f"[ERR] {note:<34} {path}\n        model={model!r} tools={'Y' if tools else 'N'}")
        print(f"          {type(e).__name__}: {str(e)[:180]}")
        return -1


print("=" * 96)
print("1) 裸 id @ /inference/v1   ← 用户说 200")
post("/inference/v1/chat/completions", "cat-jam-free", True, "裸id+tools")
post("/inference/v1/chat/completions", "cat-jam-free", False, "裸id无tools")
print("-" * 96)
print("2) 带前缀 @ /inference/v1  ← 用户说 400 Model is unavailable")
post("/inference/v1/chat/completions", "opencode/cat-jam-free", True, "带前缀+tools")
print("-" * 96)
print("3) 裸 id @ /zen/v1         ← 用户说 403")
post("/zen/v1/chat/completions", "cat-jam-free", True, "裸id+tools")
post("/zen/v1/chat/completions", "opencode/cat-jam-free", True, "带前缀+tools")
print("=" * 96)
