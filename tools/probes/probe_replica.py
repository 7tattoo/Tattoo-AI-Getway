#!/usr/bin/env python3
"""精确复刻 App 的指纹，对比 /inference/v1 vs /zen/v1"""
import json, urllib.request, urllib.error, ssl, time

BASE = "https://opencode.ai"
ctx = ssl.create_default_context()
UA = "opencode/1.18.31"
SESS = "ses_" + format(0x019a1b1f9a6a, "012x") + "C4BZsF5oWko2fq"[:16]


def decoy(name):
    """与 OpenCodeProvider.tool(name, name) 完全一致"""
    return {"type": "function", "function": {
        "name": name, "description": name,
        "parameters": {"type": "object",
                       "properties": {name: {"type": "string"}},
                       "required": []}}}


QUARTET = [decoy(n) for n in ("bash", "glob", "grep", "read")]
DECOY2 = [{"type": "function", "function": {
    "name": n, "description": "This tool is currently unavailable and must not be used.",
    "parameters": {"type": "object", "properties": {}, "required": []}}} for n in ("bash", "glob", "grep", "read")]


def hit(path, label, model="cat-jam-free", tools=QUARTET, tool_choice=None, extra=None, hdr_extra=None):
    body = {"model": model, "stream": True, "max_tokens": 8,
            "messages": [{"role": "user", "content": "hi"}]}
    if tools is not None:
        body["tools"] = tools
    if tool_choice is not None:
        body["tool_choice"] = tool_choice
    if extra:
        body.update(extra)
    h = {"Authorization": "Bearer public", "User-Agent": UA,
         "Content-Type": "application/json", "Accept": "text/event-stream",
         "x-opencode-client": "desktop", "x-opencode-project": "global",
         "x-opencode-session": SESS, "x-opencode-request": "msg_13d5fe53b001Htmux9OxFQMA29"}
    if hdr_extra:
        h.update(hdr_extra)
    try:
        r = urllib.request.urlopen(urllib.request.Request(
            BASE + path, data=json.dumps(body).encode(), headers=h, method="POST"), timeout=20, context=ctx)
        first = ""
        for _ in range(4):
            ln = r.readline().decode("utf-8", "replace").strip()
            if ln:
                first = ln[:100]
                break
        r.close()
        print(f"[200 OK ] {label}")
        print(f"            {first}")
        return 200
    except urllib.error.HTTPError as e:
        b = e.read().decode("utf-8", "replace")
        msg = b
        try:
            j = json.loads(b)
            msg = j.get("error", {}).get("message") or j.get("error", {}).get("type") or b
            if isinstance(msg, dict):
                msg = msg.get("message") or json.dumps(msg)[:120]
        except Exception:
            pass
        print(f"[{e.code}    ] {label}\n            {str(msg)[:150]}")
        return e.code
    except Exception as e:
        print(f"[ERR] {label}\n        {type(e).__name__}: {str(e)[:120]}")
        return -1


INF = "/inference/v1/chat/completions"
ZEN = "/zen/v1/chat/completions"

print("### A. App 精确复刻（4 decoy: description=name, properties={name:string}）")
hit(INF, f"{INF}  裸id+quartet")
hit(INF, f"{INF}  裸id+quartet+tool_choice=none", tool_choice="none")
hit(ZEN, f"{ZEN}  裸id+quartet")
print()
print("### B. 换 decoy 形状（DECOY_DESC / 空 properties）")
hit(INF, f"{INF}  quartet+DECOY_DESC", tools=DECOY2)
hit(INF, f"{INF}  quartet+DECOY_DESC+tool_choice=none", tools=DECOY2, tool_choice="none")
print()
print("### C. 其它 free 模型（同路径，看是否整体 IP 门禁）")
for m in ("ling-3.1-flash-free", "mimo-v2.6-flash-free", "nemotron-3.5-lightning-free",
          "jev-1.13-free", "step-5-preview-free", "space-bunny-free"):
    hit(INF, f"{INF}  {m}", model=m)
