#!/bin/bash
# 快速语法/编译校验：只编译 app/src/main/java 下全部 java（不跑 gradle、不打 dex）。
# 注意：UI 层依赖 androidx，裸 javac 会报 R/androidx 错——以 provider/service 核心层 0 error 为基线。
set -u
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || { echo "javac not found (JAVA_HOME=$JAVA_HOME)"; exit 1; }
rm -rf /tmp/cc && mkdir -p /tmp/cc
find app/src/main/java -name '*.java' > /tmp/srcs.txt
echo "sources: $(wc -l < /tmp/srcs.txt)"
PLATFORM_JAR="$(ls /opt/android-sdk/platforms/android-*/android.jar | tail -1)"
javac -nowarn -proc:none -encoding UTF-8 -source 17 -target 17 \
  -d /tmp/cc -cp "$PLATFORM_JAR" \
  @/tmp/srcs.txt 2>&1 | grep -v '^Note:' | head -60
echo "javac exit=${PIPESTATUS[0]}"
