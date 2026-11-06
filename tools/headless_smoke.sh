#!/usr/bin/env bash
# 无头回归测试：把 12 个版本各真跑一遍客户端，自动打开模组设置界面 + 推右摇杆，
# 抓截图并对日志做崩溃检查。跑完输出一张表。
#
# 关键：每次跑都会强制加载 KeyboardInput / Mouse（只有进世界才会被加载的类），
# 让它们的 @Shadow / @Inject 在标题界面就完成 mixin transform —— 否则写错的
# @Shadow（打在父类字段上）会让 12 个版本全部假绿，一进世界才崩。
#
#   bash tools/headless_smoke.sh              # 全部 12 个版本
#   bash tools/headless_smoke.sh 1.21.11 26.2 # 只测指定版本
#   bash tools/headless_smoke.sh --list
#
# 依赖：Xvfb / xdotool / import(ImageMagick) / mesa 软件渲染。
# 会自动给工程打 tools/selftest_patch.py 的脚手架，退出时（含 Ctrl-C）自动撤销。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Gradle 9.8 + fabric-loom 1.19.3 要求 JVM 25，环境里默认的 JAVA_HOME 常是 21，
# 所以这里不看继承值，直接按需校验/覆盖（可用 FORCE_JAVA_HOME 指定）。
JDK25="${FORCE_JAVA_HOME:-/usr/lib/jvm/jdk-25.0.2-oracle-x64}"
if [ -x "$JDK25/bin/java" ]; then
    export JAVA_HOME="$JDK25"
fi
_JV=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
if [ "${_JV:-0}" -lt 25 ]; then
    echo "!! 需要 JDK 25，当前 JAVA_HOME=$JAVA_HOME（Java ${_JV:-?}）" >&2
    echo "   设 FORCE_JAVA_HOME=/path/to/jdk25 后重跑" >&2
    exit 1
fi
export LIBGL_ALWAYS_SOFTWARE=1
export FOURY_SELFTEST="${FOURY_SELFTEST:-settings}"
GRADLE="${GRADLE:-$HOME/.gradle/wrapper/dists/gradle-9.8.1-bin/a2l6ltp3k1vth6lwipzm5p2jp/gradle-9.8.1/bin/gradle}"
DISP="${DISP:-:99}"
OUT="${OUT:-/tmp/4y-smoke}"
YARN_VERSIONS=(1.20.1 1.20.2 1.20.4 1.20.6 1.21.1 1.21.4 1.21.5 1.21.8 1.21.11)
MC26_VERSIONS=(26.1 26.2 26.3)

mkdir -p "$OUT"

die() { echo "!! $*" >&2; exit 1; }

if [ "${1:-}" = "--list" ]; then
    printf '%s\n' "${YARN_VERSIONS[@]}" "${MC26_VERSIONS[@]}"
    exit 0
fi

command -v Xvfb >/dev/null || die "缺少 Xvfb（apt install xvfb）"
command -v import >/dev/null || die "缺少 ImageMagick import（apt install imagemagick）"

# ------------------------------------------------------------------ 环境准备
start_x() {
    if DISPLAY=$DISP xdotool getdisplaygeometry >/dev/null 2>&1; then
        echo ">> 复用已有 X 显示 $DISP"
        export DISPLAY="$DISP"
        return
    fi
    ( cd /tmp && setsid Xvfb "$DISP" -screen 0 1280x720x24 -nolisten tcp \
        >/tmp/4y-xvfb.log 2>&1 < /dev/null & )
    for _ in $(seq 1 20); do
        sleep 0.5
        if DISPLAY=$DISP xdotool getdisplaygeometry >/dev/null 2>&1; then
            echo ">> 启动 X $DISP"
            export DISPLAY="$DISP"   # 必须导出：否则 gradle/客户端会用继承来的别的 DISPLAY，
                                      # 截图抓 :99 却抓到空屏（全黑）。踩过这个坑。
            return
        fi
    done
    die "Xvfb 起不来，看 /tmp/4y-xvfb.log"
}

kill_client() {  # 方括号技巧：避免 pgrep 匹配到执行它的 shell 自身
    pgrep -f "[d]evlaunchinjector.Main" 2>/dev/null | while read -r p; do
        kill -9 "$p" 2>/dev/null
    done
    sleep 1
}

CUR_YARN=$(cat versions/current 2>/dev/null || echo "")
CUR_MC26=$(cat mc26/versions/current 2>/dev/null || echo "")
cleanup() {
    kill_client
    python3 tools/selftest_patch.py remove >/dev/null 2>&1
    [ -n "$CUR_YARN" ] && echo "$CUR_YARN" > versions/current
    [ -n "$CUR_MC26" ] && echo "$CUR_MC26" > mc26/versions/current
    echo ">> 已撤销脚手架，versions/current=$CUR_YARN mc26/versions/current=$CUR_MC26"
}
trap cleanup EXIT INT TERM

# ------------------------------------------------------- Stonecutter 激活版本陷阱
# 被设成 active 的版本会直接编译根 src/main/java 原始源码（跳过 //? 剥离），
# 那份源码里两套分支同时存在 → 那个版本自己一定编不过或行为错乱。
# 所以测某版本时，active 必须是**另一个**版本：
#   测 1.20.1 → active=1.21.11（原始源码只有它编得过）
#   测其它   → active=1.20.1
set_active_for() {
    local v="$1"
    if [ "$v" = "1.20.1" ]; then
        echo "1.21.11" > versions/current
    else
        echo "1.20.1" > versions/current
    fi
}
restore_active() {
    [ -n "$CUR_YARN" ] && echo "$CUR_YARN" > versions/current
    [ -n "$CUR_MC26" ] && echo "$CUR_MC26" > mc26/versions/current
}

# ------------------------------------------------------------------ 单个版本跑
# run_one <工程相对目录> <版本>
run_one() {
    local proj="$1" v="$2"
    local log="$OUT/$v.log" shot="$OUT/$v.png"
    kill_client
    echo "=== $v ==============================================="
    (
        cd "$ROOT/$proj" || exit 1
        setsid nohup "$GRADLE" ":$v:runClient" -q > "$log" 2>&1 < /dev/null &
    )

    local reached=no crashed=no
    for _ in $(seq 1 90); do   # 最多等 ~270s
        sleep 3
        if grep -q "\[selftest\] tick=40" "$log" 2>/dev/null; then reached=yes; break; fi
        if grep -qE "CrashException|Mixin transformation of|FormattedException|BUILD FAILED" "$log" 2>/dev/null; then
            crashed=yes; break
        fi
    done
    sleep 15   # 留时间让界面画完 + 光标动起来

    DISPLAY=$DISP import -window root "$shot" 2>/dev/null

    local hits cursor visible classload
    hits=$(grep -cE "CrashException|Mixin transformation of|FormattedException|BUILD FAILED|InvalidInjectionException|NoSuchMethodError|NoClassDefFoundError|CLASSLOAD-FAIL" "$log" 2>/dev/null)
    # 只有进世界才会加载的类（KeyboardInput/Mouse）是否成功提前 transform。
    # 这是当初 @Shadow 打在父类字段上却 12 版本全绿的根源：测不到就永远发现不了。
    classload=$(grep -c "classload OK" "$log" 2>/dev/null)
    visible=$(grep -o "cursorVisible=true" "$log" 2>/dev/null | head -1)
    cursor=$(grep -o "cursor=([0-9.-]*,[0-9.-]*)" "$log" 2>/dev/null | tail -1)

    # 截图有效性：纯黑/纯色说明抓错屏幕或客户端没画，截图不算证据。
    local shotok=yes
    if [ -f "$shot" ]; then
        local std
        std=$(convert "$shot" -format "%[fx:standard_deviation]" info: 2>/dev/null || echo 0)
        awk -v s="$std" 'BEGIN{exit !(s+0 < 0.01)}' && shotok=BLACK
    else
        shotok=MISSING
    fi

    kill_client

    local verdict=PASS
    [ "$reached" = "no" ] && verdict=FAIL_未到界面
    [ "$hits" -gt 0 ] && verdict="FAIL_崩溃($hits)"
    [ "${classload:-0}" -lt 2 ] && verdict="$verdict/世界类未加载(${classload:-0}/2)"
    [ "$shotok" != "yes" ] && verdict="$verdict/截图$shotok"
    printf '%-10s %-18s opened=%-3s %-16s %s  shot=%s\n' \
        "$v" "$verdict" "$reached" "${visible:-无光标日志}" "${cursor:-}" "$shot"
    printf '%s\t%s\t%s\t%s\t%s\n' "$v" "$verdict" "$reached" "${visible:-none}" "$shot" >> "$OUT/result.tsv"
}

# ------------------------------------------------------------------ 主流程
targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
    targets=("${YARN_VERSIONS[@]}" "${MC26_VERSIONS[@]}")
fi

start_x
python3 tools/selftest_patch.py remove >/dev/null 2>&1
python3 tools/selftest_patch.py add root >/dev/null || die "打脚手架失败(root)"
python3 tools/selftest_patch.py add mc26 >/dev/null || die "打脚手架失败(mc26)"
: > "$OUT/result.tsv"

echo ">> 场景 FOURY_SELFTEST=$FOURY_SELFTEST，截图输出到 $OUT/"

for v in "${targets[@]}"; do
    case " ${YARN_VERSIONS[*]} " in *" $v "*) set_active_for "$v"; run_one . "$v";; esac
    case " ${MC26_VERSIONS[*]} " in *" $v "*) run_one mc26 "$v";; esac
done

restore_active

echo
echo "================ 汇总 ================"
while IFS=$'\t' read -r v verdict reached vis shot; do
    printf '  %-10s %-16s opened=%-4s %s\n' "$v" "$verdict" "$reached" "$vis"
done < "$OUT/result.tsv"

if grep -qv PASS <(cut -f2 "$OUT/result.tsv"); then
    echo ">> 有版本未通过，看 $OUT/<版本>.log 与 $OUT/<版本>.png"
    exit 1
fi
echo ">> 全部通过 ✅"
