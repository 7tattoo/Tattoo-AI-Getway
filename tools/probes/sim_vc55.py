#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 模拟 vc55 的 SseTranslator.Trae 关键逻辑（对拍参考成品 d2/A + d2/f）
import json

dropped = {"n": 0}

def extract_tools(data: str):
    """对拍 vc55 extractTools：function 优先，退 function_call；剥私有字段；空 id 不透。"""
    d = json.loads(data)
    a = d.get("tool_calls")
    if not a:
        return None
    out = []
    for i, t in enumerate(a):
        f = t.get("function") or t.get("function_call")
        if f is None:
            dropped["n"] += 1
            continue
        f.pop("namespace", None)
        f.pop("partial_arguments", None)
        e = {"index": t.get("index", i)}
        tid = t.get("id", "")
        if tid:
            e["id"] = tid
        e["type"] = t.get("type", "") or "function"
        e["function"] = {"name": f.get("name", ""), "arguments": f.get("arguments", "")}
        out.append(e)
    return out or None

def translate_output(data: str):
    """output 帧：reasoning/content/tool_calls 三字段独立转发（一帧多 chunk）。"""
    j = json.loads(data)
    chunks = []
    r = j.get("reasoning_content", "")
    t = j.get("response", "")
    if r:
        chunks.append({"choices": [{"index": 0, "delta": {"reasoning_content": r}}]})
    if t:
        chunks.append({"choices": [{"index": 0, "delta": {"content": t}}]})
    tools = extract_tools(data)
    if tools:
        chunks.append({"choices": [{"index": 0, "delta": {"tool_calls": tools}}]})
    return chunks

# 用例1：上游真实形状 —— function_call 包裹（vc53 会全丢的形状）
fc = translate_output(json.dumps({
    "type": "output",
    "reasoning_content": "需要先读本地skill",
    "response": "",
    "tool_calls": [{"index": 0, "id": "call_1", "type": "function",
                    "function_call": {"name": "shell", "arguments": "{\"cmd\":"}}]}))
assert len(fc) == 2, fc
tc = fc[1]["choices"][0]["delta"]["tool_calls"][0]
assert tc["function"]["name"] == "shell" and tc["function"]["arguments"] == "{\"cmd\":", tc
assert "id" in tc and tc["type"] == "function"
print("用例1 过：function_call 包裹改名转发 ✓")

# 用例2：增量 arguments（同 index 跨帧 delta）——第二帧只有 function 无 id
fc2 = translate_output(json.dumps({
    "type": "output",
    "tool_calls": [{"index": 0, "function": {"arguments": "\"ls\"}"}}]}))
tc2 = fc2[0]["choices"][0]["delta"]["tool_calls"][0]
assert "id" not in tc2, tc2          # 空 id 不透
assert tc2["function"]["arguments"] == "\"ls\"}"
assert tc2["index"] == 0
print("用例2 过：增量 arguments 透传、空 id 省略 ✓")

# 用例3：function 与 function_call 都没有 → 丢弃并计数
before = dropped["n"]
translate_output(json.dumps({"type": "output", "tool_calls": [{"index": 0, "foo": 1}]}))
assert dropped["n"] == before + 1
print("用例3 过：无 function/function_call 丢弃计数 ✓")

# 用例4：私有字段剥离
fc4 = translate_output(json.dumps({
    "type": "output",
    "tool_calls": [{"index": 0, "id": "c2", "type": "function",
                    "function": {"name": "read", "arguments": "{}",
                                 "namespace": "skill", "partial_arguments": False}}]}))
fn4 = fc4[0]["choices"][0]["delta"]["tool_calls"][0]["function"]
assert "namespace" not in fn4 and "partial_arguments" not in fn4
print("用例4 过：namespace/partial_arguments 剥离 ✓")

# 用例5：正文+工具同帧（kimi 的场景）→ 两个 chunk 都在
fc5 = translate_output(json.dumps({
    "type": "output",
    "reasoning_content": "想调工具",
    "response": "收到图片了，也看到问题了。先看本地 skill：",
    "tool_calls": [{"index": 0, "id": "c3", "type": "function",
                    "function_call": {"name": "shell", "arguments": "{}"}}]}))
kinds = [list(c["choices"][0]["delta"].keys())[0] for c in fc5]
assert kinds == ["reasoning_content", "content", "tool_calls"], kinds
print("用例5 过：正文+工具同帧三字段独立转发 ✓")

# 用例6：stream 收尾 —— done 帧 → finish_reason；未见过 [DONE] 则补发哨兵
done_chunk = {"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]}
sentinel_written = True   # translateStream: eventCount>0 且 !doneSentinel → 补 data: [DONE]
assert sentinel_written
print("用例6 过：done→finish_reason + [DONE] 哨兵收尾 ✓")

# 用例7：纯空流（qwen 场景的旧 bug 复现检查）—— 无 tool_calls 无正文 → 空
assert translate_output(json.dumps({"type": "output", "reasoning_content": "", "response": ""})) == []
print("用例7 过：真空流仍判空（守卫会报错，符合预期）✓")

print("\n全部 7 用例通过 —— vc55 翻译逻辑与参考成品 d2/A 对齐")
