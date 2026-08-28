#!/usr/bin/env bash
#
# 探针跑法 —— 🔴 不走 Maven，因为本工程 `mvn test` 的绿是假的。
#
# 背景与理由见 ../docs/BUILD-VERIFICATION.md：surefire 的 fork 在沙箱里会被打崩，
# 而表现是「用例很少但都过」；增量编译还会拿旧产物顶替没重编的文件。
# 这些探针带 main、断言失败即非零退出，中间没有任何层能把失败吞成绿色。
#
# 用法：
#   cd Echo/echo-server && ./probe/run.sh            # 全部
#   cd Echo/echo-server && ./probe/run.sh CardProbe  # 只跑一个（子串匹配）
#
set -uo pipefail
cd "$(dirname "$0")/.."

# ⚠️ 必须先有 target/classes。没有就先按 BUILD-VERIFICATION §2 编一次全量。
if [ ! -d target/classes ]; then
    echo "🔴 target/classes 不存在。先跑一次全量编译（见 docs/BUILD-VERIFICATION.md §2）" >&2
    exit 1
fi

# ─────────────────────────────────────────────────────────────────────────────
# JDK 版本兜底
#
# 🔴 本工程按 release 26 编译，而多数机器 PATH 上的 javac 是别的版本（实测 17）。
#    版本不对时 javac 报的是「class file has wrong version 70.0, should be 61.0」
#    然后跟着 170 多个 "cannot find symbol" —— 看上去像探针写错了，
#    ⚠️ 而真正的原因在第一行、且被后面的噪声顶出屏幕。
#    这里在编译之前就把它撞出来，并说清缺什么。
# ─────────────────────────────────────────────────────────────────────────────
REQUIRED_JDK=26

javac_major() {
    # "javac 26.0.1" → 26；取不到时回空串
    "$1" -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p'
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVAC="$(command -v javac || true)"
    JAVA_BIN="$(command -v java || true)"
fi

if [ -z "$JAVAC" ]; then
    echo "🔴 找不到 javac。本工程需要 JDK $REQUIRED_JDK。" >&2
    exit 1
fi

if [ "$(javac_major "$JAVAC")" != "$REQUIRED_JDK" ]; then
    # 先自动找：maven 用的那个 JDK 就是对的那个（pom 里 release=26 由它编出 target/classes）
    FOUND=""
    MVN_HOME="$(mvn -o -v 2>/dev/null | sed -nE 's/^Java version:.*runtime: (.*)$/\1/p')"
    for cand in "$MVN_HOME" \
                /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home \
                /Library/Java/JavaVirtualMachines/*/Contents/Home \
                /usr/lib/jvm/*; do
        [ -x "$cand/bin/javac" ] || continue
        if [ "$(javac_major "$cand/bin/javac")" = "$REQUIRED_JDK" ]; then
            FOUND="$cand"
            break
        fi
    done
    if [ -n "$FOUND" ]; then
        echo "-- PATH 上的 javac 是 $(javac_major "$JAVAC")，自动切到 JDK $REQUIRED_JDK: $FOUND"
        export JAVA_HOME="$FOUND"
        JAVAC="$JAVA_HOME/bin/javac"
        JAVA_BIN="$JAVA_HOME/bin/java"
    else
        # 🔴 找不到就明确报版本不对并退非零，不要让它以一堆编译错误的形式表现
        echo "🔴 JDK 版本不对：需要 $REQUIRED_JDK，当前 javac 是 $(javac_major "$JAVAC")（$JAVAC）。" >&2
        echo "   target/classes 是按 release $REQUIRED_JDK 编的，低版本 javac 读不了它的 class 文件。" >&2
        echo "   装一个 JDK $REQUIRED_JDK 后，或者：export JAVA_HOME=<JDK$REQUIRED_JDK 路径>，再重跑本脚本。" >&2
        exit 1
    fi
fi

# 依赖 classpath 缓存；缺了就让 Maven 现算一次（-o 离线，不加会卡在等网）
CPFILE=target/probe-cp.txt
if [ ! -s "$CPFILE" ]; then
    echo "-- 生成依赖 classpath（一次性）"
    mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CPFILE" || {
        echo "🔴 classpath 生成失败" >&2
        exit 1
    }
fi

CP="target/classes:$(cat "$CPFILE")"
OUT=target/probe-classes
rm -rf "$OUT" && mkdir -p "$OUT"

# ⚠️ 不用 mapfile：macOS 自带 bash 是 3.2，没有这个内建
SRCS=$(find probe -name '*.java' | sort)
"$JAVAC" -nowarn -cp "$CP" -d "$OUT" $SRCS || {
    echo "🔴 探针编译失败" >&2
    exit 1
}

FILTER="${1:-}"
failed=0
for src in $SRCS; do
    cls=$(echo "$src" | sed 's|^probe/||; s|\.java$||; s|/|.|g')
    [ -n "$FILTER" ] && [[ "$cls" != *"$FILTER"* ]] && continue
    echo ""
    echo "===== $cls"
    # 只滤掉 logback 的启动噪声（|-WARN / |-INFO / SLF4J / 时间戳开头的日志行）。
    # 🔴 不要改成「只留 ^ok|^FAIL」那种白名单式过滤：探针打的失败明细是缩进的，
    #    白名单会把它一起吃掉，于是看到 "FAIL - 命中 11 处" 却看不到是哪 11 处。
    "$JAVA_BIN" -cp "$CP:$OUT" "$cls" 2>&1 \
        | grep -vE "^[0-9]{2}:[0-9]{2}:[0-9]{2}|\|-(WARN|INFO|ERROR)|^SLF4J"
    # grep 吃掉了退出码，单独再取一次
    "$JAVA_BIN" -cp "$CP:$OUT" "$cls" >/dev/null 2>&1 || failed=1
done

echo ""
if [ "$failed" -ne 0 ]; then
    echo "🔴 有探针失败"
    exit 1
fi
echo "✅ 探针全部通过"
