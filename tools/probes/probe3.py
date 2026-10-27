#!/usr/bin/env python3
"""Focused probe: does the dsh-free canonical fingerprint pass Zen's free gate now?
Tests: current app shape (negated ts) vs canonical (real BE ts + cli + sha1 + req_32hex),
       with and without tools, and with/without tool_choice.
"""
import hashlib, json, random, time, urllib.request, urllib.error

BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
CHAT = "https://opencode.ai/zen/v1/chat/completions"
MODELS = "https://opencode.ai/zen/v1/models"
UA = "opencode/1.18.31"

def b62(n): return "".join(BASE62[random.randrange(62)] for _ in range(n))

def ses_neg(ts=None, seq=1):      # current app: negated ts
    ts = ts or int(time.time() * 1000)
    v = (~(ts * 0x1000 + seq)) & ((1 << 48) - 1)
    return "ses_" + "%012x" % v + b62(14)

def ses_real():                    # canonical: real BE 48-bit ms
    ts = int(time.time() * 1000)
    return "ses_" + ts.to_bytes(6, "big").hex() + b62(14)

def req_msg(): return "msg_" + "%012x" % int(time.time() * 1000) + b62(14)
def req_har(): return "req_" + random.randbytes(16).hex()

def prj_global(): return "global"
def prj_sha1():
    inner = hashlib.sha256(b"public 0").hexdigest()
    return hashlib.sha1(("git-remote:opencode/" + inner).encode()).hexdigest()

TOOLS4 = [{"type": "function", "function": {"name": n, "description": "x",
           "parameters": {"type": "object", "properties": {}}}} for n in ("bash", "glob", "grep", "read")]

def post(model, session, reqid, project, client, tools="absent", tc="absent", label=""):
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream",
               "Authorization": "Bearer public", "User-Agent": UA,
               "x-opencode-client": client, "x-opencode-project": project,
               "x-opencode-session": session, "x-opencode-request": reqid}
    body = {"model": model, "stream": True, "messages": [{"role": "user", "content": "hi"}]}
    if tools == "null": body["tools"] = None
    elif tools == "empty": body["tools"] = []
    elif tools == "four": body["tools"] = TOOLS4
    if tc == "none": body["tool_choice"] = "none"
    elif tc == "auto": body["tool_choice"] = "auto"
    req = urllib.request.Request(CHAT, data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read(400).decode("utf-8", "replace")
            print(f"  {label:<44} -> 200 {raw[:90]!r}")
            return 200
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        print(f"  {label:<44} -> HTTP {e.code} {raw[:130]}")
        return e.code

print("### models via same headers (canonical)")
h = {"Authorization": "Bearer public", "User-Agent": UA, "x-opencode-client": "cli",
     "x-opencode-session": ses_real(), "x-opencode-request": req_har(),
     "x-opencode-project": prj_sha1()}
try:
    with urllib.request.urlopen(urllib.request.Request(MODELS, headers=h), timeout=20) as r:
        d = json.loads(r.read().decode())
        ids = [m["id"] for m in d.get("data", [])]
        print("  models 200, total=", len(ids), "free=", [i for i in ids if i.endswith("-free")])
except urllib.error.HTTPError as e:
    print("  models HTTP", e.code, e.read().decode()[:120])

MODEL = "space-bunny-free"
print("### probe model:", MODEL)
for sess_fn, sess_lbl in ((ses_neg, "neg(APP)"), (ses_real, "realBE(CANON)")):
    for req_fn, req_lbl in ((req_msg, "msg_"), (req_har, "req_har")):
        for tools, tools_lbl in (("absent", "noTools"), ("null", "nullTools"), ("empty", "emptyTools"), ("four", "4tools")):
            post(MODEL, sess_fn(), req_fn(), prj_sha1(), "cli",
                 tools=tools, tc="none" if tools != "four" else "auto",
                 label=f"{sess_lbl}/{req_lbl}/{tools_lbl}/cli+sha1/tc")
print("### ling flash free (may be gone)")
post("ling-3.1-flash-free", ses_real(), req_har(), prj_sha1(), "cli", tools="four", tc="none", label="CANON/4tools/ling")