#!/usr/bin/env python3
"""
从 OfficeAceProvider.java 提取静态工具方法到 HuaweiAuthUtils.java
"""
import re

SRC = "app/src/main/java/com/aigetway/tattoo7/provider/OfficeAceProvider.java"
DST = "app/src/main/java/com/aigetway/tattoo7/provider/HuaweiAuthUtils.java"

with open(SRC, "r") as f:
    lines = f.readlines()

# 第一步：找出所有 static 方法的起止行
# 方法：从方法签名行开始，数大括号，找到匹配的结束 }
methods = {}  # name -> (start_line, end_line, signature_line)

i = 0
while i < len(lines):
    line = lines[i]
    # 匹配 static 方法（非 static class）
    m = re.match(
        r'^    (?:public |private |protected )?static '
        r'(?:final )?(?:[\w<>.,? \[\]]+ )?'
        r'(\w+)\s*\([^)]*\)',
        line
    )
    if m and 'class' not in line.split('static')[1].split('(')[0]:
        name = m.group(1)
        start = i  # 0-indexed
        # 向前找方法体的 {
        brace_line = i
        while brace_line < len(lines) and '{' not in lines[brace_line]:
            brace_line += 1
        if brace_line >= len(lines):
            i += 1
            continue
        # 数大括号找结束
        depth = 0
        j = brace_line
        found_open = False
        while j < len(lines):
            for ch in lines[j]:
                if ch == '{':
                    depth += 1
                    found_open = True
                elif ch == '}':
                    depth -= 1
            if found_open and depth == 0:
                break
            j += 1
        end = j
        # 同一方法名可能有多个重载，用列表存
        if name not in methods:
            methods[name] = []
        methods[name].append((start, end))
    i += 1

print("找到的 static 方法：")
for name in sorted(methods.keys()):
    ranges = methods[name]
    print(f"  {name}: {len(ranges)}个重载，行{ranges[0][0]+1}~{ranges[0][1]+1}")

# 第二步：确定需要提取的方法（从 CodeArtsProvider 实际调用出发，递归收集）
needed = set()

# CodeArtsProvider 直接调用的方法
direct_calls = [
    "pkceVerifier", "pkceChallenge", "generateDpopJwk",
    "jsEncode", "dpopJwt", "isExpiring", "randomHex",
    "signHuaweiRequest"
]

# 递归查找被调用的方法
def find_calls_in_method(start, end):
    """在一个方法体中查找对本类其他 static 方法的调用"""
    calls = set()
    body = ''.join(lines[start:end+1])
    for name in methods:
        # 简单匹配：方法名( 
        if re.search(r'\b' + name + r'\s*\(', body):
            calls.add(name)
    return calls

def collect_needed(name):
    if name in needed or name not in methods:
        return
    needed.add(name)
    for start, end in methods[name]:
        calls = find_calls_in_method(start, end)
        for c in calls:
            if c != name:  # 避免递归
                collect_needed(c)

for call in direct_calls:
    collect_needed(call)

print(f"\n需要提取的方法（{len(needed)}个）：")
for name in sorted(needed):
    print(f"  {name}")

# 第三步：按行号排序提取
# 收集所有需要提取的行范围
extract_ranges = []
for name in needed:
    for start, end in methods[name]:
        extract_ranges.append((start, end))

# 按起始行排序
extract_ranges.sort()

# 第四步：生成 HuaweiAuthUtils.java
# 先获取 import 部分（从文件开头到第一个类定义）
import_end = 0
for i, line in enumerate(lines):
    if line.startswith("public class "):
        import_end = i
        break

import_lines = lines[:import_end]
# 过滤掉 import 本包的类（可能不需要），保留其他
filtered_imports = []
for line in import_lines:
    if line.startswith("package "):
        filtered_imports.append(line)
    elif line.startswith("import "):
        # 保留所有 import，工具方法可能用到
        filtered_imports.append(line)
    elif line.strip() == "":
        filtered_imports.append(line)

# 构建新类
output_lines = []
output_lines.extend(filtered_imports)
if not output_lines[-1].strip() == "":
    output_lines.append("\n")
output_lines.append("/**\n")
output_lines.append(" * 华为云 OAuth / DPoP / 签名通用工具方法。\n")
output_lines.append(" * 从 OfficeAceProvider 抽取，供 CodeArtsProvider 等复用。\n")
output_lines.append(" */\n")
output_lines.append("public class HuaweiAuthUtils {\n")
output_lines.append("\n")

# 把包级可见的改成 public，private 保持 private
for start, end in extract_ranges:
    for j in range(start, end + 1):
        line = lines[j]
        # 把 "    static " 改成 "    public static "（包级可见变公有）
        # 但不要改 private static
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("static ") and not stripped.startswith("static class"):
            line = " " * indent + "public " + stripped
        output_lines.append(line)
    output_lines.append("\n")

output_lines.append("}\n")

with open(DST, "w") as f:
    f.writelines(output_lines)

print(f"\n已生成 {DST}，共 {len(output_lines)} 行")
