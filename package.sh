#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 4YCtrl 一键打包脚本
#
#   ./package.sh                # 打全部版本（9 个 yarn 版本 + 26.1/26.2/26.3）
#   ./package.sh 1.21.11 26.3   # 只打指定版本（可写多个；26 或 mc26 = 全部 26.x）
#   ./package.sh --clean        # 先 clean 再打全部
#   ./package.sh --offline      # 离线模式（remapJar 的运行时库没缓存时会失败）
#   ./package.sh --no-build     # 只把已有产物收集到 dist/
#   ./package.sh --list         # 列出所有版本节点
#
# 产物统一收集到 dist/，文件名 fouryctrl-<modver>+<mcver>.jar
# 同时生成 dist/SHA256SUMS 与 dist/README.txt
# 打包后自动运行 tools/verify_mixins.py 校验各版本 mixin 注入目标
# 与 tools/verify_shadow.py 校验 @Shadow 是否都在目标类自身
# （目标类/方法/字段不存在时会红字报出并退出非零，防 1.21.11 那种签名崩溃，
#   也防 @Shadow 打在父类字段上导致「一进世界就崩」）
#
# 两套工程：
#   1.20.1 ~ 1.21.11  -> Stonecutter 版本轴（versions/<mcver>/，yarn 映射）
#   26.1 ~ 26.3       -> mc26/ 独立工程（官方名，无 yarn/intermediary，
#                        内部也是 Stonecutter 轴：mc26/versions/<mcver>/）
#                        注意 26.1 节点同时覆盖 26.1 / 26.1.1 / 26.1.2
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MC26_TARGET="mc26"          # 代表「全部 26.x」的别名（单版本直接写 26.1 / 26.2 / 26.3）
MC26_PREFIX="26."           # mc26 产物版本号前缀
MC26_DIR="mc26"             # 26.x 独立工程目录

# --- 环境探测 --------------------------------------------------------------
GRADLE_BIN=""
for g in "$HOME"/.gradle/wrapper/dists/gradle-9.8.1-bin/*/gradle-9.8.1/bin/gradle; do
    [ -x "$g" ] && GRADLE_BIN="$g" && break
done
if [ -z "$GRADLE_BIN" ]; then
    echo "!! 找不到 gradle 9.8.1（fabric-loom 1.19.3 要求 Gradle >= 9.8.0）" >&2
    exit 1
fi

# loom 1.19.3 要求 JVM >= 25
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"(2[5-9]|[3-9][0-9])'; then
    for j in /usr/lib/jvm/jdk-2[5-9]* /usr/lib/jvm/jdk-[3-9][0-9]*; do
        if [ -x "$j/bin/java" ]; then JAVA_HOME="$j"; break; fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    echo "!! 找不到 JVM 25+（fabric-loom 1.19.3 要求 JVM >= 25）" >&2
    exit 1
fi
export JAVA_HOME

# --- 小工具 ----------------------------------------------------------------
# 内部目标名 -> 展示名（26.x 的目标名就是它自己的版本号）
display_name() { echo "$1"; }

# 是不是 26.x 工程的版本节点（26.1 / 26.2 / 26.3）
is_mc26_ver() { [ -d "${MC26_DIR}/versions/$1" ]; }

# 用户写的参数是不是「泛指全部 26.x」（26 / mc26）
is_mc26_alias() {
    case "$1" in
        "$MC26_TARGET"|26) return 0 ;;
        *) return 1 ;;
    esac
}

# mc26 工程支持的版本节点（排除 current）
mc26_versions() {
    ls -1 "${MC26_DIR}/versions" 2>/dev/null | grep -v '^current$' | sort -V
}

# --- 参数解析 --------------------------------------------------------------
CLEAN=0
OFFLINE=0
NO_BUILD=0
WANT=()

for arg in "$@"; do
    case "$arg" in
        --clean)    CLEAN=1 ;;
        --offline)  OFFLINE=1 ;;
        --no-build) NO_BUILD=1 ;;
        --list)
            echo "版本节点："
            ls -1 versions | grep -v '^current$' | sort -V | sed 's/^/  /'
            if [ -f "$MC26_DIR/build.gradle" ]; then
                echo "  -- 26.x（mc26/ 独立工程，官方名）--"
                mc26_versions | sed 's/^/  /'
                echo "  （26 或 mc26 = 以上全部；26.1 节点同时覆盖 26.1.1 / 26.1.2）"
            fi
            exit 0 ;;
        -h|--help)
            sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        -*) echo "!! 未知参数：$arg" >&2; exit 1 ;;
        *)  WANT+=("$arg") ;;
    esac
done

# 版本节点 = 主树 versions/（yarn）+ mc26/versions/（官方名）
ALL_VERS=()
while IFS= read -r v; do ALL_VERS+=("$v"); done < <(ls -1 versions 2>/dev/null | grep -v '^current$' | sort -V)
while IFS= read -r v; do ALL_VERS+=("$v"); done < <(mc26_versions)

if [ ${#WANT[@]} -eq 0 ]; then
    VERS=("${ALL_VERS[@]}")
else
    VERS=()
    for w in "${WANT[@]}"; do
        if is_mc26_alias "$w"; then
            if [ -f "$MC26_DIR/build.gradle" ]; then
                while IFS= read -r mv; do
                    printf '%s\n' "${VERS[@]:-}" | grep -qx "$mv" || VERS+=("$mv")
                done < <(mc26_versions)
            else
                echo "!! 没有 mc26/ 工程，无法打 26.x" >&2
                exit 1
            fi
        elif is_mc26_ver "$w"; then
            VERS+=("$w")
        elif [ -d "versions/$w" ]; then
            VERS+=("$w")
        else
            echo "!! 没有这个版本节点：$w" >&2
            echo "   可用：${ALL_VERS[*]}" >&2
            exit 1
        fi
    done
fi

GRADLE_ARGS=(--console=plain -q --continue)
[ "$OFFLINE" = 1 ] && GRADLE_ARGS+=(--offline)

# 展示用（把 mc26 换成真实版本号）
SHOWN=()
for v in "${VERS[@]}"; do SHOWN+=("$(display_name "$v")"); done

echo "=============================================================="
echo " 4YCtrl 打包"
echo " Gradle : $GRADLE_BIN"
echo " JAVA   : $JAVA_HOME"
echo " 版本   : ${SHOWN[*]}"
[ "$OFFLINE" = 1 ] && echo " 模式   : 离线"
echo "=============================================================="

# --- 可选 clean ------------------------------------------------------------
if [ "$CLEAN" = 1 ]; then
    echo ">> clean"
    YARN_CL=()
    MC26_CL=()
    for v in "${VERS[@]}"; do
        if is_mc26_ver "$v"; then MC26_CL+=(":$v:clean"); else YARN_CL+=("$v"); fi
    done
    if [ ${#YARN_CL[@]} -gt 0 ]; then
        "${GRADLE_BIN}" clean "${GRADLE_ARGS[@]}" >/dev/null 2>&1
    fi
    if [ ${#MC26_CL[@]} -gt 0 ]; then
        ( cd "$MC26_DIR" && "${GRADLE_BIN}" "${MC26_CL[@]}" "${GRADLE_ARGS[@]}" >/dev/null 2>&1 )
    fi
fi

# --- 构建 ------------------------------------------------------------------
FAILED=()
START=$(date +%s)
if [ "$NO_BUILD" = 0 ]; then
    YARN_TARGETS=()
    MC26_TARGETS=()
    for v in "${VERS[@]}"; do
        if is_mc26_ver "$v"; then MC26_TARGETS+=(":$v:build"); else YARN_TARGETS+=(":$v:build"); fi
    done

    YARN_RC=0
    MC26_RC=0

    # --- stonecutter 激活版本陷阱 --------------------------------------------
    # stonecutter 对 versions/current 指向的「激活版本」直接编译根 src/main/java 原始源码
    # （开发模式，跳过条件注释剥离），产物会包含未剥离的双分支 → mixin 签名冲突崩溃。
    # 规避：保证 current 指向 1.21.11（唯一能编译未剥离源码的版本：新旧两套 render 签名都存在），
    # 先构建其余 yarn 版本，再临时把 current 切到 1.20.1 单独构建 1.21.11，最后恢复。
    CURRENT_FILE="versions/current"
    SAVED_CURRENT=""
    [ -f "$CURRENT_FILE" ] && SAVED_CURRENT=$(cat "$CURRENT_FILE")
    if [ "$SAVED_CURRENT" != "1.21.11" ]; then
        echo "1.21.11" > "$CURRENT_FILE"
        echo ">> versions/current 已设为 1.21.11（激活版本构建陷阱的牺牲版本）"
    fi
    # 把激活版本从本轮目标中剔除（它必须走第二遍单独构建）
    PASS1=()
    PASS2=()
    for t in "${YARN_TARGETS[@]}"; do
        case "$t" in
            :1.21.11:build) PASS2+=("$t") ;;
            *) PASS1+=("$t") ;;
        esac
    done

    if [ ${#PASS1[@]} -gt 0 ]; then
        echo ">> 构建 ${#PASS1[@]} 个 yarn 版本（每个都要 remap + 打 jar，首次会下载依赖）"
        set +e
        "${GRADLE_BIN}" "${PASS1[@]}" "${GRADLE_ARGS[@]}" 2>&1 | tee /tmp/4y-build.log | \
            grep -E "(FAILED|Cannot remap|错误|FAILURE|BUILD )" || true
        YARN_RC=${PIPESTATUS[0]}
        set -e
    else
        : > /tmp/4y-build.log
    fi

    # 第二遍：切激活版本单独构建 1.21.11
    if [ ${#PASS2[@]} -gt 0 ]; then
        echo ">> 切换激活版本 → 单独构建 1.21.11（激活版本必须走 stonecutter 预处理）"
        echo "1.20.1" > "$CURRENT_FILE"
        set +e
        "${GRADLE_BIN}" "${PASS2[@]}" "${GRADLE_ARGS[@]}" 2>&1 | tee -a /tmp/4y-build.log | \
            grep -E "(FAILED|Cannot remap|错误|FAILURE|BUILD )" || true
        RC2=${PIPESTATUS[0]}
        [ "$RC2" -ne 0 ] && YARN_RC=$RC2
        set -e
        # 恢复激活版本（保持开发环境一致性）
        echo "1.21.11" > "$CURRENT_FILE"
    fi

    if [ ${#MC26_TARGETS[@]} -gt 0 ]; then
        echo ">> 构建 ${#MC26_TARGETS[@]} 个 26.x 版本（mc26/ 独立工程，官方名）"
        set +e
        ( cd "$MC26_DIR" && "${GRADLE_BIN}" "${MC26_TARGETS[@]}" "${GRADLE_ARGS[@]}" 2>&1 ) \
            | tee /tmp/4y-build-mc26.log | grep -E "(FAILED|错误|FAILURE|BUILD )" || true
        MC26_RC=${PIPESTATUS[0]}
        set -e
        cat /tmp/4y-build-mc26.log >> /tmp/4y-build.log
    fi

    END=$(date +%s)
    echo ">> 构建耗时 $((END-START))s（yarn 日志 /tmp/4y-build.log，26.x 日志 /tmp/4y-build-mc26.log）"
    if [ "$YARN_RC" -ne 0 ] && [ "$MC26_RC" -eq 0 ]; then
        echo "!! yarn 版本有个别失败（退出码 $YARN_RC），逐版本核对产物"
    elif [ "$MC26_RC" -ne 0 ] && [ "$YARN_RC" -eq 0 ]; then
        echo "!! 26.x 构建失败（退出码 $MC26_RC），看 /tmp/4y-build-mc26.log"
    elif [ "$YARN_RC" -ne 0 ] && [ "$MC26_RC" -ne 0 ]; then
        echo "!! 构建失败（yarn $YARN_RC / 26.x $MC26_RC），逐版本核对产物"
    fi
    # 记下确实失败的版本（yarn 任务名形如 :1.20.1:remapJar FAILED）
    while IFS= read -r line; do
        for v in "${VERS[@]}"; do
            case "$line" in *":${v}:"*FAILED*) FAILED+=("$v") ;; esac
        done
    done < <(grep "FAILED" /tmp/4y-build.log || true)
fi

# --- 收集产物 --------------------------------------------------------------
mkdir -p dist
rm -f dist/fouryctrl-*.jar dist/SHA256SUMS dist/README.txt

MODVER=$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d ' \r')
[ -z "$MODVER" ] && MODVER="1.0.0"

ROWS_FILE=$(mktemp)
trap 'rm -f "$ROWS_FILE"' EXIT
OK=0
MISS=0
for v in "${VERS[@]}"; do
    if is_mc26_ver "$v"; then
        SRC="${MC26_DIR}/versions/${v}/build/libs/fouryctrl-${MODVER}+${v}.jar"
        LABEL="$v"
        HINT=":${v}:build 失败？看 /tmp/4y-build-mc26.log"
    else
        SRC="versions/${v}/build/libs/fouryctrl-${MODVER}+${v}.jar"
        LABEL="$v"
        HINT=":${v}:build 失败？看 /tmp/4y-build.log"
    fi

    if [ -n "${SRC:-}" ] && [ -f "$SRC" ]; then
        cp -f "$SRC" dist/
        SZ=$(stat -c%s "$SRC")
        printf "  %-10s %8s B   %s\n" "$LABEL" "$SZ" "${SRC##*/}" >> "$ROWS_FILE"
        OK=$((OK+1))
    else
        printf "  %-10s %8s     —— 缺失（%s）\n" "$LABEL" "—" "$HINT" >> "$ROWS_FILE"
        MISS=$((MISS+1))
    fi
    unset SRC
done

if [ "$OK" -gt 0 ]; then
    ( cd dist && sha256sum fouryctrl-*.jar > SHA256SUMS )
fi

{
    echo "4YCtrl ${MODVER} —— $(date '+%Y-%m-%d %H:%M:%S') 构建"
    echo
    echo "每个 jar 只对应一个 Minecraft 版本区间（见 fabric.mod.json 的 depends.minecraft），"
    echo "按你的游戏版本装对应的那一个，不要同时放多个。"
    echo
    echo "  1.20.1 ~ 1.21.11 : yarn 映射（Stonecutter 版本轴）"
    echo "  26.1 ~ 26.3      : 官方名（mc26/ 独立工程，需 Java 25+）"
    echo "                     26.1 的 jar 同时支持 26.1 / 26.1.1 / 26.1.2"
    echo
    cat "$ROWS_FILE"
} > dist/README.txt

echo
echo "=============================================================="
cat "$ROWS_FILE"
echo "--------------------------------------------------------------"
echo " 产物目录 : $ROOT/dist"
echo " 成功 $OK 个，缺失 $MISS 个"
[ "$MISS" -gt 0 ] && echo " 缺失版本：${FAILED[*]:-未知}"
echo "=============================================================="

# --- mixin 注入目标自动校验（打包后自动跑，防 1.21.11 那种签名崩溃） -------
if [ "$OK" -gt 0 ] && command -v python3 >/dev/null; then
    echo
    echo ">> 校验各版本 mixin 注入目标..."
    if ! python3 tools/verify_mixins.py; then
        echo "!! mixin 校验失败：上述红字版本装进游戏会崩溃，先修复再分发" >&2
        exit 1
    fi
    echo ">> mixin 校验通过"

    # @Shadow 单独再验一遍：它只在目标类**自身**查，不沿继承链找，
    # 打在父类字段上会导致「标题界面全正常、一进世界就崩」。
    echo
    echo ">> 校验 @Shadow 是否都落在目标类自身..."
    if ! python3 tools/verify_shadow.py; then
        echo "!! @Shadow 校验失败：字段/方法不在目标类自身（多半在父类），一进世界就崩" >&2
        exit 1
    fi
    echo ">> @Shadow 校验通过"
fi

[ "$MISS" -eq 0 ]
