#!/usr/bin/env python3
# 模拟新 SseTranslator.Trae 的逻辑，验证各帧形态的 chunk 产出
import json

KNOWN = {"output", "token_usage", "error", "done", "metadata", "timing_cost", "extra_info"}

def translate(ev, data):
    ev = (ev or "").strip()
    d = (data or "").strip()
    if not d: return []
    if ev not in KNOWN:
        try: t = json.loads(d).get("type", "")
        except Exception: t = None
        if t in ("output", "done", "error"):
            ev = t
        else:
            return [d]  # 原样放行（含 [DONE]）
    if ev in ("token_usage", "metadata", "timing_cost", "extra_info"):
        return []
    if ev == "done":
        try: r = json.loads(d).get("finish_reason", "") or "stop"
        except Exception: r = "stop"
        return [json.dumps({"choices":[{"index":0,"delta":{},"finish_reason":r}]}, ensure_ascii=False)]
    if ev == "error":
        return [json.dumps({"error":{"message":json.loads(d).get("message","")}}, ensure_ascii=False)]
    # output：一帧可能同时带 reasoning/text/tool_calls
    try:
        j = json.loads(d)
        text = j.get("response", "")
        reasoning = j.get("reasoning_content", "")
    except Exception:
        text, reasoning = d, ""
    out = []
    if reasoning: out.append(json.dumps({"choices":[{"index":0,"delta":{"reasoning_content":reasoning}}]}, ensure_ascii=False))
    if text: out.append(json.dumps({"choices":[{"index":0,"delta":{"content":text}}]}, ensure_ascii=False))
    tools = j.get("tool_calls") or []
    if tools:
        out.append(json.dumps({"choices":[{"index":0,"delta":{"tool_calls":tools}}]}, ensure_ascii=False))
    return out

cases = [
    # (描述, event, data, 期望 chunk 数, 期望包含)
    ("纯思考帧", "output", '{"reasoning_content":"让我想想"}', 1, ["reasoning_content"]),
    ("纯正文帧", "output", '{"response":"你好"}', 1, ["content"]),
    ("思考+正文同帧(旧代码丢正文!)", "output", '{"response":"答案","reasoning_content":"思考"}', 2, ["reasoning_content", "content"]),
    ("思考+工具同帧(旧代码丢调用!)", "output", '{"reasoning_content":"我需要读文件","tool_calls":[{"id":"c1","type":"function","function":{"name":"read","arguments":"{}"}}]}', 2, ["reasoning_content", "tool_calls"]),
    ("正文+工具同帧", "output", '{"response":"好的","tool_calls":[{"id":"c2","type":"function","function":{"name":"bash","arguments":"ls"}}]}', 2, ["content", "tool_calls"]),
    ("三字段同帧", "output", '{"response":"a","reasoning_content":"r","tool_calls":[{"id":"c3","type":"function","function":{"name":"x","arguments":"{}"}}]}', 3, ["reasoning_content", "content", "tool_calls"]),
    ("空output帧", "output", '{}', 0, []),
    ("done帧", "done", '{"finish_reason":"tool_calls"}', 1, ["finish_reason"]),
    ("无event行+type字段(output!)", "", '{"type":"output","response":"正文"}', 1, ["content"]),
    ("无event行+type字段(done)", "", '{"type":"done","finish_reason":"stop"}', 1, ["finish_reason"]),
    ("无event行+无type([DONE]放行)", "", '[DONE]', 1, ["[DONE]"]),
    ("usage帧丢弃", "token_usage", '{"input":1,"output":2}', 0, []),
    ("error帧", "error", '{"code":4001,"message":"bad"}', 1, ["error"]),
]

fails = 0
for desc, ev, data, want_n, want_keys in cases:
    got = translate(ev, data)
    ok = len(got) == want_n and all(any(k in g for g in got) for k in want_keys)
    print(("PASS" if ok else "FAIL"), desc, "->", len(got), "chunks")
    if not ok:
        fails += 1
        print("   got:", got)
print("ALL PASS" if fails == 0 else f"{fails} FAILURES")
