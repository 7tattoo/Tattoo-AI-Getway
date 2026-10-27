#!/usr/bin/env python3
"""从 OfficeAceProvider.java brace-balance 提取辅助方法及其依赖，生成 HuaweiAuthUtils.java"""
import re, io

SRC = "app/src/main/java/com/aigetway/tattoo7/provider/OfficeAceProvider.java"
lines = open(SRC).read().split('\n')

def method_start(name):
    pat = re.compile(r'^\s*(?:public |private |protected )*static\s+(?:final\s+)?[\w<>,\.\[\] ?]+\s+'+name+r'\s*\(')
    for i,l in enumerate(lines):
        if pat.match(l):
            return i
    return -1

def extract(name, out):
    """提取方法 name 到 out，递归提取其调用到的白名单方法。返回 (覆盖行集合)"""
    si = method_start(name)
    if si < 0:
        return
    # 找 { 和配对的 }
    brace_open = -1
    for i in range(si, len(lines)):
        if '{' in lines[i]:
            brace_open = i
            break
    if brace_open < 0:
        return
    depth=0; started=False; brace_close=-1; j=brace_open
    while j<len(lines):
        for ch in lines[j]:
            if ch=='{': depth+=1; started=True
            elif ch=='}': depth-=1
        if started and depth==0:
            brace_close=j; break
        j+=1
    out.append((si, brace_close))

def method_text(si,ci):
    return '\n'.join(lines[si:ci+1])

# 白名单依赖方法
DEPS = ["pkceVerifier","pkceChallenge","generateDpopJwk","importDpopPrivate","ecSpec",
        "dpopJwt","es256Sign","derToP1363","copyLeftPad","randomHex","b64url","fromB64url",
        "leftPad","iso8601Basic","jsEncode","hex","isExpiring","parseIsoMillis"]

# 提取：pkce / 签名辅助（不含 signHuaweiRequest 的常量引用，signHuaweiRequest 保留手写版）
targets = ["pkceVerifier","pkceChallenge","generateDpopJwk","importDpopPrivate","ecSpec",
           "dpopJwt","es256Sign","derToP1363","copyLeftPad","randomHex","b64url","fromB64url",
           "leftPad","iso8601Basic","jsEncode"]

ranges=set()
def collect(name):
    if name in _done: return
    _done.add(name)
    si = method_start(name)
    if si<0: 
        print(f"  [MISS] {name}"); return
    ci = None
    brace_open=-1
    for i in range(si,len(lines)):
        if '{' in lines[i]: brace_open=i; break
    depth=0; started=False; j=brace_open
    while j<len(lines):
        for ch in lines[j]:
            if ch=='{':depth+=1;started=True
            elif ch=='}':depth-=1
        if started and depth==0: ci=j; break
        j+=1
    ranges.add((si,ci))
    body=method_text(si,ci)
    for dep in DEPS:
        if dep!=name and re.search(r'\b'+dep+r'\s*\(', body):
            collect(dep)
_done=set()

print("收集方法体范围：")
for t in targets:
    if method_start(t)>=0:
        collect(t)
for (a,b) in sorted(ranges):
    # 首行方法名
    m=re.search(r'static\s+(?:final\s+)?[\w<>,\.\[\] ?]+\s+(\w+)\s*\(',method_text(a,a))
    name=m.group(1) if m else '?'
    print(f"  {name}: 行{a+1}~{b+1}")

# 输出拼接文本
print("\n=====OUTPUT=====")
for (a,b) in sorted(ranges):
    t=method_text(a,b)
    print(t)
    print()