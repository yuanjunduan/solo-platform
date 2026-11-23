#!/usr/bin/env bash
# ============================================================================
#  M7 待实机验收：一次性验证脚本（对应 PROGRESS.md §15.5）
#
#  在**隔离**的 solo-verify 栈上跑，不动线上 solo-platform 栈：
#    1) 起 PostgreSQL + Redis，按文件名顺序补跑 sql/upgrade/*_pg.sql
#       （初始化 SQL 只有遗留基线，缺 audit_log / res_play_record / 导出权限码；
#        每份脚本应用后写入 zz_verify_sql_marker，复跑自动跳过）
#    2) 起 solo + solo-ui，等 /api/health/ready 通过
#    3) 探针：/api/health 与 /api/health/ready 的 200/503 语义 + nginx 反代
#    4) 保留期：塞过期/新鲜行 → 重启 solo 触发首轮 → 断言过期删、新鲜留、日志留痕
#    5) 导出限流：同账号 1 分钟内第 6 次导出必须 429，并落审计 EXPORT_CHANNEL
#    6) 带宽字段 + 备份恢复：summary 带 bandwidth；dump 恢复到临时库并比对行数
#
#  用法
#    bash scripts/verify_m7.sh [all|up|probe|retention|export|bandwidth|backup|logs|down]
#    all 为默认：全流程；退出码 0 = 自动化项全过（SKIP 不影响），1 = 有 FAIL，2 = 中止
#
#  前置
#    · bash + curl + docker compose v2；首次构建镜像需联网（Maven / npm）
#    · 8080(tcp)、5060(tcp+udp)、18081、55432 空闲（后两个可用 UI_PORT / VERIFY_DB_PORT 改）
#    · ZLM / S3 不在编排内：缺失只让带宽与文件相关项降级，不影响本脚本判定
#    · 宿主机同时有 pg_dump / pg_restore 才执行 scripts/backup.sh，否则退化为容器内 dump
#    · 升级脚本里 2026-11-23_res_readonly_grants_pg.sql 需 -v app_user=… 手工执行，自动跳过
#
#  常用开关
#    VERIFY_RESET=1       跑完删卷（下次重建库）；默认保留，便于复跑
#    VERIFY_KEEP=1        跑完不 down，方便进去看
#    VERIFY_DB_PORT=55432 宿主机调试端口（backup.sh 直连用）
#    VERIFY_SKIP_BACKUP=1 跳过备份恢复
#    VERIFY_ALLOW_RUNNING=1  线上栈在跑时仍继续（确认无冲突再用）
#    ADMIN_USER/ADMIN_PASS 默认 admin/admin123（初始化 SQL 里的种子账号）
# ============================================================================
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
DEPLOY_DIR="$ROOT_DIR/deploy"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"

PROJECT="solo-verify"
ACTION="${1:-all}"
UI_PORT="${UI_PORT:-18081}"
DB_HOST_PORT="${VERIFY_DB_PORT:-55432}"
API_PORT=8080
BASE="http://127.0.0.1:$API_PORT"
UI="http://127.0.0.1:$UI_PORT"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin123}"

PG_USER="solo"
PG_DB="solo_db"
PG_PASS="$(printf 'verify-db-%s' "${RANDOM}${RANDOM}")"
JWT_SECRET="$(printf 'verify-jwt-%s-%s' "${RANDOM}${RANDOM}${RANDOM}" "${RANDOM}")"

PASSED=0
FAILED=0
SKIPPED=0
TMP_DIR="$(mktemp -d)"
# env 必须跨轮次保留：POSTGRES_PASSWORD 只在数据卷初始化时生效，
# 每次随机会导致复跑时应用连不上已有库
ENV_FILE="$ROOT_DIR/.verify-m7.env"
OVERRIDE_FILE="$TMP_DIR/override.yml"
TOKEN=""

# ---------- 输出 ----------
if [ -t 1 ]; then C_G=$'\033[32m'; C_R=$'\033[31m'; C_Y=$'\033[33m'; C_B=$'\033[36m'; C_0=$'\033[0m'
else C_G=""; C_R=""; C_Y=""; C_B=""; C_0=""; fi
section() { printf '\n%s== %s ==%s\n' "$C_B" "$*" "$C_0"; }
info()    { printf '  .... %s\n' "$*"; }
ok()      { PASSED=$((PASSED + 1)); printf '  %sPASS%s %s\n' "$C_G" "$C_0" "$*"; }
bad()     { FAILED=$((FAILED + 1)); printf '  %sFAIL%s %s\n' "$C_R" "$C_0" "$*"; }
skip()    { SKIPPED=$((SKIPPED + 1)); printf '  %sSKIP%s %s\n' "$C_Y" "$C_0" "$*"; }
die() {
    printf '\n%s[中止]%s %s\n' "$C_R" "$C_0" "$*" >&2
    # 中止时栈常常还开着（例如探针步骤把库停了）。给一句收拾办法，别让人去猜
    if [ "${DC_READY:-0}" = "1" ] && dc ps -q 2>/dev/null | grep -q .; then
        printf '%s      验证栈仍在运行：logs 看日志，down 停栈（加 VERIFY_RESET=1 连卷一起删）%s\n' \
            "$C_Y" "$C_0" >&2
    fi
    exit 2
}

usage() {
    # 只打印文件头注释块：与行号解耦，改头部说明不用同步改这里
    awk 'NR>1 { if (!/^#/) exit; print }' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

cleanup() {
    [ -n "${TMP_DIR:-}" ] && rm -rf "$TMP_DIR"
    return 0
}
trap cleanup EXIT

# Git Bash / MSYS 下把 /tmp/xxx 之类的参数交给 docker.exe 时会被改写成宿主机路径，
# compose 就会报"找不到文件"。有 cygpath 就统一换成 Windows 形态；Linux 上原样返回。
hostpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

DC=()
DC_READY=0
# 每次 write_env_files 之后重建：ENV_FILE 可能被换成临时文件（例如 down 时不许覆盖持久 env）
build_dc() {
    DC=(docker compose --project-name "$PROJECT" --project-directory "$(hostpath "$DEPLOY_DIR")"
        --env-file "$(hostpath "$ENV_FILE")" -f "$(hostpath "$COMPOSE_FILE")" -f "$(hostpath "$OVERRIDE_FILE")")
    DC_READY=1
}
dc() { "${DC[@]}" "$@"; }

# ---------- 基础检查 ----------
preflight() {
    section "前置检查"
    command -v docker >/dev/null 2>&1 || die "未找到 docker"
    docker info >/dev/null 2>&1 || die "docker 不可用（未启动或当前用户无权限）"
    docker compose version >/dev/null 2>&1 || die "需要 docker compose v2（docker compose 子命令）"
    command -v curl >/dev/null 2>&1 || die "未找到 curl"
    [ -f "$COMPOSE_FILE" ] || die "找不到 $COMPOSE_FILE"
    ok "docker / compose v2 / curl 就绪"

    # backup.sh 内部要用 pg_restore --list 自校验，缺任一个都别执行它
    if command -v pg_dump >/dev/null 2>&1 && command -v pg_restore >/dev/null 2>&1; then
        ok "宿主机存在 $(pg_dump --version | head -1) 与 pg_restore"
    else
        skip "宿主机缺 pg_dump/pg_restore：备份步骤退化为容器内 dump（不执行 scripts/backup.sh）"
    fi
}

# 只在起栈（up/all）时查：down/logs 时本栈自己就占着这些端口
check_ports() {
    # 复跑 / 上次中断场景：本验证栈自己就可能占着 8080/5060/18081/55432。
    # 用 -a（含已停止容器）判断：只要这套栈存在过，这些端口就归它，不能判成"被别人占用"
    if [ -n "$(dc ps -aq 2>/dev/null)" ]; then
        info "验证栈 ${PROJECT} 已存在：跳过端口占用检查（up 会就地更新容器）"
        return 0
    fi

    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^solo-platform-'; then
        if [ "${VERIFY_ALLOW_RUNNING:-0}" != "1" ]; then
            die "检测到线上 solo-platform 栈在运行：本脚本会 stop postgres，禁止混跑（确认无影响后可 VERIFY_ALLOW_RUNNING=1）"
        fi
        info "线上栈在运行，已按 VERIFY_ALLOW_RUNNING=1 继续（注意端口冲突）"
    fi

    # 端口：只在能查的时候查，查不到就提示
    if command -v ss >/dev/null 2>&1; then
        for spec in "8080:tcp:后端 HTTP" "5060:udp:SIP UDP" "5060:tcp:SIP TCP" \
                    "$UI_PORT:tcp:控制台 nginx" "$DB_HOST_PORT:tcp:验证库调试端口"; do
            local port="${spec%%:*}" rest="${spec#*:}" proto="${rest%%:*}" name="${rest#*:}"
            if [ "$proto" = "udp" ]; then
                ss -lun 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$port\$" \
                    && die "端口 $port/udp 已被占用（$name），请先释放"
            else
                ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$port\$" \
                    && die "端口 $port/tcp 已被占用（$name），请先释放"
            fi
        done
        ok "8080、5060(tcp+udp)、$UI_PORT、$DB_HOST_PORT 空闲"
    else
        skip "未找到 ss，跳过端口占用检查（请自行确认 $API_PORT / 5060 / $UI_PORT / $DB_HOST_PORT 空闲）"
    fi
}

# ---------- 生成 / 复用临时 env 与 override ----------
write_env_files() {
    # 卷已初始化时库口令只在 initdb 那一次生效：env 文件丢了就无从恢复
    if [ ! -f "$ENV_FILE" ] && docker volume ls --format '{{.Name}}' 2>/dev/null \
         | grep -qx "${PROJECT}_pgdata"; then
        case "$ACTION" in
            up|all)
                die "数据卷 ${PROJECT}_pgdata 已存在但 $ENV_FILE 不见了：库口令无从恢复。用 VERIFY_RESET=1 bash scripts/verify_m7.sh down 清库重来，或把旧 POSTGRES_PASSWORD 写回该文件（可用 docker inspect 查旧值）" ;;
            *)
                # 其它动作只需要变量能解析，不需要口令正确（psql 走容器内 socket，是 trust）。
                # 绝不能把新随机口令写进持久 env：下次 up 会拿错口令去连老卷，
                # 卡满 420s 超时后才报错，且报错完全指不到原因。
                ENV_FILE="$TMP_DIR/throwaway.env"
                info "库卷仍在但 env 丢了：本次用临时 env（不覆盖 $ROOT_DIR/.verify-m7.env）；恢复口令前这个栈的状态不可信" ;;
        esac
    fi

    # 复跑时沿用上次的库口令与 JWT 密钥：数据卷初始化只用一次口令
    if [ -f "$ENV_FILE" ]; then
        local old_pg old_jwt
        old_pg=$(sed -n 's/^POSTGRES_PASSWORD=//p' "$ENV_FILE" | head -1)
        old_jwt=$(sed -n 's/^SOLO_JWT_SECRET=//p' "$ENV_FILE" | head -1)
        [ -n "$old_pg" ] && PG_PASS="$old_pg"
        [ -n "$old_jwt" ] && JWT_SECRET="$old_jwt"
        info "复用已有 $ENV_FILE（要清库重来：VERIFY_RESET=1）"
    fi
    cat > "$ENV_FILE" <<EOF
UI_PORT=$UI_PORT
SOLO_HTTP_PORT=8080
SOLO_IP=127.0.0.1
SOLO_SIP_PUBLIC_IP=
SOLO_SIP_PORT=5060
SOLO_SIP_ID=34020000002000000001
SOLO_SIP_DOMAIN=3402000000
SOLO_SIP_PASSWORD=verify-sip-pass
POSTGRES_DB=$PG_DB
POSTGRES_USER=$PG_USER
POSTGRES_PASSWORD=$PG_PASS
SOLO_REDIS_PASSWORD=
SOLO_S3_HOST=127.0.0.1
SOLO_S3_PORT=9000
SOLO_S3_ACCESS_KEY=verify
SOLO_S3_SECRET_KEY=verify-s3-secret
SOLO_S3_BUCKET=solo
SOLO_MEDIA_HOST=127.0.0.1
SOLO_ZLM_SERVER_ID=verify-zlm
SOLO_ZLM_SECRET=verify-zlm-api-secret-0000000000000000
SOLO_ZLM_HOOK_SECRET=verify-hook-secret
SOLO_JWT_SECRET=$JWT_SECRET
OPENAPI_TRUSTED_PROXIES=127.0.0.1,::1
# 保留期缩短到「启动后 15s、每 2 分钟一轮」，否则要等 5 分钟 + 24 小时；
# 时区是镜像里 JAVA_OPTS 的一部分，整串覆盖时必须一起带上，否则 JVM 回到 UTC
JAVA_OPTS=-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -Dretention.startup-delay-ms=15000 -Dretention.interval-ms=120000
EOF
    cat > "$OVERRIDE_FILE" <<EOF
# 只给验证栈把 PostgreSQL 暴露到宿主机回环，便于 scripts/backup.sh 直连
services:
  postgres:
    ports:
      - "127.0.0.1:$DB_HOST_PORT:5432"
EOF
    # compose 挂的是 ./logs：先建好，root 下尽量交给容器用户（uid 10001），
    # 否则 logback 的文件 appender 会报权限错误（控制台日志仍可用，不影响判定）
    case "$ACTION" in
        up|all)
            mkdir -p "$DEPLOY_DIR/logs" 2>/dev/null || true
            if [ "$(id -u)" = "0" ]; then
                chown 10001:10001 "$DEPLOY_DIR/logs" 2>/dev/null || true
            fi
            ;;
    esac
    # ENV_FILE / OVERRIDE_FILE 到这里才最终确定，DC 数组必须在这之后重建
    build_dc
}

# ---------- DB / HTTP 工具 ----------
# 容器里没有 LANG，psql 会退回 SQL_ASCII：升级脚本里的中文菜单名/注释会被写成乱码。
# 显式指定客户端编码，和基线 SQL 的 SET client_encoding 保持一致。
psql_q() { dc exec -e PGCLIENTENCODING=UTF8 -T postgres psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null; }
psql_run() { dc exec -e PGCLIENTENCODING=UTF8 -T postgres psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -q -c "$1"; }
# 每份升级脚本都必须"整份成功或整份回滚"：psql 默认逐条提交，中途失败会留下
# 半成品且写不了 marker，复跑就等于踩着半成品重放整份（目前只靠脚本自身幂等兜着）。
# BEGIN / CREATE INDEX CONCURRENTLY 不能进显式事务，命中就退回逐条提交。
psql_file() {
    local tx=""
    # 必须匹配"整条 BEGIN;"：光写 BEGIN 会把 begin_time 这种列名也算进来，
    # 一误判就悄悄退化成逐条提交，原子性白给
    grep -qiE '^[[:space:]]*(BEGIN|START[[:space:]]+TRANSACTION)[[:space:]]*;|CONCURRENTLY' "$1" || tx="--single-transaction"
    # shellcheck disable=SC2086  # $tx 为空时必须整体消失，不能留个空参数
    dc exec -e PGCLIENTENCODING=UTF8 -T postgres psql -U "$PG_USER" -d "$PG_DB" \
        -v ON_ERROR_STOP=1 -q $tx -f - < "$1"
}
pg_ready() { dc exec -T postgres pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1; }
# 首次启动时 entrypoint 会先起一个只监听 socket 的临时实例跑初始化 SQL，
# 那段时间 pg_isready 同样会成功；所以要连着三次查到基线表才算真正就绪
pg_stable() {
    local i n
    for i in 1 2 3; do
        pg_ready || return 1
        # 必须真的有数据：基线 SQL 是一个事务，能查到 admin 才说明初始化提交完了
        n=$(psql_q 'select count(*) from sys_user')
        case "$n" in
            ''|*[!0-9]*) return 1 ;;
        esac
        [ "$n" -ge 1 ] || return 1
        sleep 2
    done
    return 0
}
table_exists() { [ "$(psql_q "select to_regclass('public.$1') is not null")" = "t" ]; }
perm_exists() {
    # psql 失败时输出为空，不能当成"已存在"，否则会静默跳过升级脚本
    local cnt
    cnt=$(psql_q "select count(*) from sys_permission where permission_code = '$1'")
    [ -n "$cnt" ] && [ "$cnt" != "0" ]
}
# 权限码存在 ≠ 已授予 admin：菜单脚本的最后一条才是 role grant，
# 它单独失败时导出会直接 403，不在这里点出来就会被误读成"限流或反代坏了"
grant_exists() {
    local cnt
    cnt=$(psql_q "select count(*) from sys_role_permission rp
                  join sys_permission p on p.id = rp.permission_id
                  where rp.role_id = 1 and p.permission_code = '$1'")
    [ -n "$cnt" ] && [ "$cnt" != "0" ]
}

wait_until() {
    local timeout="$1" desc="$2"; shift 2
    local waited=0
    while [ "$waited" -lt "$timeout" ]; do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

http() { # http METHOD URL OUTFILE [curl args...] -> 打印状态码
    local method="$1" url="$2" out="$3"; shift 3
    curl -sS -m 60 -X "$method" -o "$out" -w '%{http_code}' "$@" "$url" 2>/dev/null || echo "000"
}
body_has() { grep -q -- "$1" "$2" 2>/dev/null; }

wait_ready() {
    local timeout="${1:-300}" hint="${2:-}"
    section "等待 /api/health/ready 通过（最长 ${timeout}s${hint:+，$hint}）"
    local waited=0 code
    while [ "$waited" -lt "$timeout" ]; do
        code=$(http GET "$BASE/api/health/ready" "$TMP_DIR/ready.json")
        if [ "$code" = "200" ]; then ok "就绪探针 200"; return 0; fi
        sleep 5
        waited=$((waited + 5))
        if [ $((waited % 60)) -eq 0 ]; then info "已等待 ${waited}s，当前状态码 ${code}"; fi
    done
    dc logs --tail 80 solo 2>/dev/null | tail -40
    bad "就绪探针在 ${timeout}s 内未通过（看上面 solo 日志）"
    return 1
}

# nginx 的 upstream 是启动时解析一次并缓存 IP：solo 被重建（换 IP）后
# 反代就断，导出走 nginx 的那两次会 502。这里检测到断就重启 solo-ui 重新解析。
ensure_ui_up() {
    local code
    code=$(http GET "$UI/api/health" "$TMP_DIR/ui-probe.json")
    if [ "$code" = "200" ]; then return 0; fi
    # 先分清"nginx 还没起来"和"nginx 起了但缓存了旧 IP"：前者一重启反而打断启动，
    # 首次 up 之后紧接着调到这里时正是这种状态
    local waited=0
    while [ "$waited" -lt 60 ] && [ -z "$(dc ps -q solo-ui 2>/dev/null)" ]; do
        sleep 2; waited=$((waited + 2))
    done
    if [ -n "$(dc ps -q solo-ui 2>/dev/null)" ]; then
        info "nginx 反代 /api 不通（HTTP $code），重启 solo-ui 重新解析上游"
        dc restart solo-ui >/dev/null 2>&1 || true
    else
        info "solo-ui 等 ${waited}s 仍未运行，直接 up 起来"
        dc up -d --no-deps solo-ui >/dev/null 2>&1 || true
    fi
    waited=0
    while [ "$waited" -lt 60 ]; do
        sleep 2
        waited=$((waited + 2))
        code=$(http GET "$UI/api/health" "$TMP_DIR/ui-probe.json")
        if [ "$code" = "200" ]; then ok "solo-ui 反代恢复（${waited}s）"; return 0; fi
    done
    bad "solo-ui 反代仍不通（HTTP $code）：后续走 nginx 的断言会失败"
    # nginx 解析不到上游会直接退出，容器状态和日志才是真因，别让人只看 502
    dc ps solo-ui 2>/dev/null | tail -2 | sed 's/^/        /'
    dc logs --tail 20 solo-ui 2>/dev/null | sed 's/^/        /'
    return 1
}

# 保留期步骤靠 -Dretention.* 缩短周期。容器是在 up 时读 env 创建的，
# 若容器来自身份不同的旧 env（例如上一版脚本），这些 -D 根本没进去。
assert_retention_opts() {
    local opts
    opts=$(dc exec -T solo printenv JAVA_OPTS 2>/dev/null || true)
    case "$opts" in
        *-Dretention.startup-delay-ms=*)
            info "容器 JAVA_OPTS 已带保留期覆盖"
            return 0
            ;;
    esac
    info "容器 JAVA_OPTS 未带保留期覆盖，重建 solo 让其生效（会换 IP，随后修 nginx）"
    dc up -d --no-deps --force-recreate solo >/dev/null 2>&1 || { bad "重建 solo 失败"; return 1; }
    wait_ready 240 || return 1
    ensure_ui_up || true
    opts=$(dc exec -T solo printenv JAVA_OPTS 2>/dev/null || true)
    case "$opts" in
        *-Dretention.startup-delay-ms=*)
            ok "重建后 JAVA_OPTS 生效：$(printf '%s' "$opts" | tr ' ' '\n' | grep retention | tr '\n' ' ')"
            ;;
        *)
            bad "JAVA_OPTS 仍无保留期覆盖（容器：$opts）"
            return 1
            ;;
    esac
}

# 登录自身有 5 次/分钟限流，且未登录时按 :user:anonymous 计数，
# 同一分钟内多次复跑子命令会撞上 429，所以动手前先清限流键。
clear_rate_limits() {
    dc exec -T redis sh -c 'redis-cli --scan --pattern "rate_limit:*" | while read k; do redis-cli del "$k" >/dev/null; done' >/dev/null 2>&1 || true
}

login() {
    if [ -n "$TOKEN" ]; then return 0; fi
    clear_rate_limits
    local code
    code=$(http POST "$BASE/api/auth/login" "$TMP_DIR/login.json" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
    if [ "$code" != "200" ]; then
        bad "登录失败（HTTP $code），检查种子账号 $ADMIN_USER / $ADMIN_PASS"
        return 1
    fi
    TOKEN=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$TMP_DIR/login.json" | head -1)
    if [ -z "$TOKEN" ]; then
        bad "登录响应里没有 token：$(head -c 300 "$TMP_DIR/login.json")"
        return 1
    fi
    ok "登录成功（$ADMIN_USER），token 长度 ${#TOKEN}"
}

# ---------- 步骤 1：起栈 ----------
step_up() {
    section "1/6 起 PostgreSQL + Redis 并补跑升级脚本"
    # 先让 compose 自己校验一遍变量与覆盖合并，比 up 到一半报错好定位
    local cfg
    if cfg=$(dc config -q 2>&1); then
        ok "compose 配置解析通过（docker-compose.yml + override）"
        [ -n "$cfg" ] && info "compose 提示：$(printf '%s' "$cfg" | tr '\n' ' ')"
    else
        printf '%s\n' "$cfg" >&2
        die "compose 配置有误（多为 .verify-m7.env 里的必填变量缺失）"
    fi
    dc up -d postgres redis || die "postgres/redis 启动失败"
    if ! wait_until 300 "postgres 与基线库就绪" pg_stable; then
        dc logs --tail 60 postgres 2>/dev/null | tail -30
        die "postgres 300s 内未就绪（上面是容器日志；初始化 SQL 报错可 VERIFY_RESET=1 重建卷）"
    fi
    ok "PostgreSQL / Redis 就绪"

    # 初始化 SQL 只是遗留基线：目标架构的表与权限码都在 sql/upgrade 里
    apply_upgrades
    assert_verify_schema

    section "1/6 起 solo + solo-ui（首次会构建镜像，耐心等）"
    dc up -d --build || die "solo/solo-ui 启动失败"
    wait_ready 420 "首次含镜像构建" || return 1
    # solo 被重建会换 IP，nginx 缓存的 upstream 就失效了；顺带确认保留期参数真的进了容器
    assert_retention_opts || die "保留期参数未注入容器：步骤 3 无法在验收窗口内完成（检查 .verify-m7.env 的 JAVA_OPTS，或 VERIFY_RESET=1 重建栈）"
    ensure_ui_up || true
}

# 需要手工带 -v app_user=… 的收口脚本，自动执行只会报语法错
UPGRADE_EXCLUDE="2026-11-23_res_readonly_grants_pg.sql"

# 按文件名顺序补跑全部 *_pg.sql，等价于一次真实升级部署。
# 用 zz_verify_sql_marker 记录已应用的文件：复跑跳过，中断后能从断点续上。
# 单份失败只记 FAIL 不中止——否则一个历史脚本的小问题会把整轮 M7 验收全废掉；
# 真正的门槛交给后面的 assert_verify_schema。
apply_upgrades() {
    info "补跑 sql/upgrade/*_pg.sql（按文件名顺序，已应用过的跳过）"
    [ -d "$ROOT_DIR/sql/upgrade" ] || die "找不到目录 $ROOT_DIR/sql/upgrade"
    psql_run "CREATE TABLE IF NOT EXISTS zz_verify_sql_marker (
                  name       varchar(128) PRIMARY KEY,
                  applied_at timestamp NOT NULL DEFAULT now())" \
        || die "创建升级标记表失败"
    local f name
    while IFS= read -r f; do
        name=$(basename "$f")
        if [ "$name" = "$UPGRADE_EXCLUDE" ]; then
            info "跳过 $name（需 -v app_user=… 手工执行）"
            continue
        fi
        if [ -n "$(psql_q "select 1 from zz_verify_sql_marker where name = '$name'")" ]; then
            info "$name 已应用，跳过"
            continue
        fi
        info "应用 $name"
        if psql_file "$f" 2>"$TMP_DIR/sql.err"; then
            psql_run "INSERT INTO zz_verify_sql_marker(name) VALUES ('$name') ON CONFLICT DO NOTHING" \
                >/dev/null || bad "$name 已执行但写标记失败（复跑会重复执行）"
        else
            bad "升级脚本 $name 执行失败（本份未写标记，修好后复跑会重放它）：$(grep -m1 -E 'ERROR|FATAL' "$TMP_DIR/sql.err" 2>/dev/null | head -c 200)"
        fi
    done < <(find "$ROOT_DIR/sql/upgrade" -maxdepth 1 -name '*_pg.sql' | sort)
}

# M7 各项断言真正依赖的表与权限码：升级脚本有失败时，这里会明确点出缺了什么
assert_verify_schema() {
    local missing="" t
    for t in audit_log res_play_record res_channel sync_round; do
        table_exists "$t" || missing="$missing $t"
    done
    for t in res:channel:export res:observability:view; do
        perm_exists "$t" || missing="$missing perm:$t"
        grant_exists "$t" || missing="$missing grant:$t"
    done
    [ -z "$missing" ] || die "验收依赖的表/权限码缺失：$missing（见上面 apply_upgrades 的 FAIL，修好后复跑会自动续上）"
    ok "验收依赖的表与权限码齐备"
}

# ---------- 步骤 2：探针 ----------
step_probe() {
    section "2/6 健康探针语义"
    local code

    code=$(http GET "$BASE/api/health" "$TMP_DIR/h.json")
    if [ "$code" = "200" ] && body_has '"status":"UP"' "$TMP_DIR/h.json"; then
        ok "存活探针 200 且 status=UP"
    else
        bad "存活探针异常：HTTP $code $(head -c 200 "$TMP_DIR/h.json")"
    fi

    code=$(http GET "$BASE/api/health/ready" "$TMP_DIR/r.json")
    if [ "$code" = "200" ] && body_has '"ready":true' "$TMP_DIR/r.json"; then
        ok "就绪探针 200 且 ready=true"
    else
        bad "就绪探针异常：HTTP $code $(head -c 200 "$TMP_DIR/r.json")"
    fi

    code=$(http GET "$UI/api/health" "$TMP_DIR/ui.json")
    if [ "$code" = "200" ] && body_has '"status":"UP"' "$TMP_DIR/ui.json"; then
        ok "nginx 反代 /api 正常（UI:$UI_PORT）"
    else
        bad "nginx 反代 /api 异常：HTTP $code $(head -c 200 "$TMP_DIR/ui.json")"
    fi

    info "停止 postgres，验证 503 语义（就绪结果缓存 5s，等 7s 后再轮询）"
    dc stop postgres >/dev/null 2>&1 || true
    sleep 7
    local waited=0
    code=""
    while [ "$waited" -lt 40 ]; do
        code=$(http GET "$BASE/api/health/ready" "$TMP_DIR/r503.json")
        [ "$code" = "503" ] && break
        sleep 2
        waited=$((waited + 2))
    done
    if [ "$code" = "503" ] && body_has '"name":"db","status":"DOWN"' "$TMP_DIR/r503.json"; then
        ok "DB 不可用时就绪探针 503 且 db=DOWN"
    else
        bad "停止 DB 后预期 503/db=DOWN，实际 HTTP $code $(head -c 240 "$TMP_DIR/r503.json")"
    fi
    code=$(http GET "$BASE/api/health" "$TMP_DIR/h503.json")
    [ "$code" = "200" ] && ok "DB 不可用时存活探针仍 200" || bad "DB 不可用时存活探针应为 200，实际 $code"

    dc start postgres >/dev/null 2>&1 || true
    wait_until 90 "postgres 恢复" pg_ready || die "postgres 未能恢复"
    sleep 2
    waited=0
    code=""
    while [ "$waited" -lt 120 ]; do
        code=$(http GET "$BASE/api/health/ready" "$TMP_DIR/r2.json")
        [ "$code" = "200" ] && break
        sleep 3
        waited=$((waited + 3))
    done
    if [ "$code" = "200" ] && body_has '"ready":true' "$TMP_DIR/r2.json"; then
        ok "DB 恢复后就绪探针回到 200（${waited}s 内）"
    else
        bad "DB 恢复后未回到 200（HTTP $code）"
    fi
}

# ---------- 步骤 3：保留期 ----------
step_retention() {
    section "3/6 保留期回收（audit_log 365d / res_play_record 180d）"
    login || return 1
    # 先把 -Dretention.* 落实（必要时重建容器），否则这一轮要等 5 分钟 + 24 小时
    assert_retention_opts || return 1

    info "写入 2 条过期 + 2 条新鲜样本"
    psql_run "
INSERT INTO audit_log (id, action, target_type, detail, create_time) VALUES
 (9000000000000000001, 'VERIFY_EXPIRED', 'VERIFY', 'expired seed', now() - interval '400 days'),
 (9000000000000000002, 'VERIFY_FRESH',   'VERIFY', 'fresh seed',   now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO res_play_record (id, channel_row_id, channel_code, action, result,
                             begin_time, create_time, update_time) VALUES
 (9000000000000000011, 1, 'verify-ch', 'PLAY', 'SUCCESS',
  now() - interval '200 days', now() - interval '200 days', now() - interval '200 days'),
 (9000000000000000012, 1, 'verify-ch', 'PLAY', 'SUCCESS', now(), now(), now())
ON CONFLICT (id) DO NOTHING;" || { bad "写入保留期样本失败"; return 1; }

    local total
    total=$(psql_q "select count(*) from audit_log where id in (9000000000000000001,9000000000000000002)")
    [ "$total" = "2" ] && ok "样本已写入（audit_log=2）" || bad "样本写入异常（audit_log=$total）"

    info "重启 solo 触发首轮回收（启动延迟 15s）"
    # 容器日志跨重启保留：先记基线，后面只认"新增"的 [保留期] 行，避免老日志造成假 PASS
    local log_base
    log_base=$(dc logs solo 2>/dev/null | grep -c '\[保留期\]' || true)
    dc restart solo >/dev/null 2>&1 || { bad "restart solo 失败"; return 1; }
    wait_ready 180 || return 1

    local expired fresh play_expired play_fresh i=0
    while [ "$i" -lt 120 ]; do
        expired=$(psql_q "select count(*) from audit_log where id = 9000000000000000001")
        play_expired=$(psql_q "select count(*) from res_play_record where id = 9000000000000000011")
        # 查询本身失败（返回空）时别死等满 120s：直接结束轮询，让下面的断言报真因
        [ -n "$expired" ] && [ -n "$play_expired" ] || { bad "回收状态查询失败（psql 无输出，库可能没起来）"; break; }
        if [ "$expired" = "0" ] && [ "$play_expired" = "0" ]; then break; fi
        sleep 5; i=$((i + 5))
        [ $((i % 30)) -eq 0 ] && info "等待回收... ${i}s（audit_log 过期行剩 $expired，播放记录过期行剩 $play_expired）"
    done

    [ "$expired" = "0" ] && ok "过期 audit_log 行已删除" || bad "过期 audit_log 行仍在（${i}s 后）"
    [ "$play_expired" = "0" ] && ok "过期 res_play_record 行已删除" || bad "过期 res_play_record 行仍在"

    fresh=$(psql_q "select count(*) from audit_log where id = 9000000000000000002")
    play_fresh=$(psql_q "select count(*) from res_play_record where id = 9000000000000000012")
    [ "$fresh" = "1" ] && ok "新鲜 audit_log 行保留" || bad "新鲜 audit_log 行被误删"
    [ "$play_fresh" = "1" ] && ok "新鲜 res_play_record 行保留" || bad "新鲜 res_play_record 行被误删"

    local log_now
    log_now=$(dc logs solo 2>/dev/null | grep -c '\[保留期\]' || true)
    if [ "${log_now:-0}" -gt "${log_base:-0}" ]; then
        ok "solo 日志新增 [保留期] 记录（+$((log_now - log_base)) 行）"
        dc logs solo 2>/dev/null | grep '\[保留期\]' | tail -5 | sed 's/^/        /'
    else
        bad "solo 日志没有新增 [保留期] 记录（基线 $log_base 行）"
    fi

    # 收尾：清掉样本，避免影响审计计数
    psql_run "DELETE FROM audit_log WHERE action IN ('VERIFY_EXPIRED','VERIFY_FRESH');
              DELETE FROM res_play_record WHERE channel_code = 'verify-ch';" >/dev/null 2>&1 || true
}

# ---------- 步骤 4：导出限流 + 审计 ----------
step_export() {
    section "4/6 导出限流（5 次/分钟/用户）与审计留痕"
    login || return 1
    wait_ready 120 || return 1
    # 第 3、4 次导出要走 nginx：上一步 restart 过 solo，若换了 IP 而 nginx 还缓存着旧地址，
    # 这两次会拿到 502，看起来像"限流没生效"的假故障，先自愈
    ensure_ui_up || true

    # 清掉上一轮遗留的滑动窗口，保证可重复执行
    clear_rate_limits

    local n code base rl_base
    base=$(psql_q "select count(*) from audit_log where action = 'EXPORT_CHANNEL'")
    [ -n "$base" ] || base=0
    # 日志同样只看新增：容器日志会把上一轮的「触发限流」一起带过来
    rl_base=$(dc logs solo 2>/dev/null | grep -c '触发限流' || true)
    for n in 1 2 3 4 5; do
        if [ "$n" = "3" ] || [ "$n" = "4" ]; then
            # 第 3、4 次走 nginx，顺带验证反代对导出（CSV）放行
            code=$(http GET "$UI/api/res/channel/export" "$TMP_DIR/export.out" \
                -H "Authorization: Bearer $TOKEN")
        else
            code=$(http GET "$BASE/api/res/channel/export" "$TMP_DIR/export.out" \
                -H "Authorization: Bearer $TOKEN")
        fi
        if [ "$code" = "200" ]; then
            ok "第 $n 次导出 200（$(wc -c < "$TMP_DIR/export.out") 字节）"
        else
            bad "第 $n 次导出预期 200，实际 $code：$(head -c 160 "$TMP_DIR/export.out")"
        fi
    done

    code=$(http GET "$BASE/api/res/channel/export" "$TMP_DIR/export6.out" \
        -H "Authorization: Bearer $TOKEN")
    if [ "$code" = "429" ] && body_has '"code":429' "$TMP_DIR/export6.out"; then
        ok "第 6 次导出被限流（HTTP 429）"
    else
        bad "第 6 次导出预期 429，实际 $code：$(head -c 160 "$TMP_DIR/export6.out")"
    fi

    local hit=0 i=0
    while [ "$i" -lt 20 ]; do
        hit=$(psql_q "select count(*) from audit_log where action = 'EXPORT_CHANNEL'")
        [ -n "$hit" ] && [ "$hit" -gt "$base" ] && break
        sleep 1; i=$((i + 1))
    done
    if [ -n "$hit" ] && [ "$hit" -gt "$base" ]; then
        ok "审计新增 EXPORT_CHANNEL（本轮 +$((hit - base)) 条，累计 $hit）"
        psql_q "select detail from audit_log where action = 'EXPORT_CHANNEL' order by create_time desc limit 1" \
            | sed 's/^/        detail: /'
    else
        bad "审计里没有 EXPORT_CHANNEL（旁路异步，可稍后复查）"
    fi

    local rl_now
    rl_now=$(dc logs solo 2>/dev/null | grep -c '触发限流' || true)
    if [ "${rl_now:-0}" -gt "${rl_base:-0}" ]; then
        ok "solo 日志新增「触发限流」（+$((rl_now - rl_base)) 行）"
    else
        bad "solo 日志没有新增「触发限流」（基线 $rl_base 行）"
    fi
}

# ---------- 步骤 5：带宽字段 ----------
step_bandwidth() {
    section "5/6 运行自检的带宽字段（无 ZLM 时只验结构）"
    login || return 1
    local code
    code=$(http GET "$BASE/api/res/observability/summary" "$TMP_DIR/obs.json" \
        -H "Authorization: Bearer $TOKEN")
    if [ "$code" != "200" ]; then
        bad "自检汇总接口 HTTP $code：$(head -c 200 "$TMP_DIR/obs.json")"
        return 1
    fi
    if body_has '"bandwidth"' "$TMP_DIR/obs.json" && body_has '"totalMbps"' "$TMP_DIR/obs.json" \
       && body_has '"totalBytesPerSec"' "$TMP_DIR/obs.json"; then
        ok "summary 含 bandwidth.totalMbps / totalBytesPerSec（无 ZLM 时值为 0 属正常）"
        info "实测：$(sed -n 's/.*"bandwidth":\({[^}]*}\).*/\1/p' "$TMP_DIR/obs.json" | head -c 220)"
    else
        bad "summary 缺少 bandwidth 字段：$(head -c 240 "$TMP_DIR/obs.json")"
    fi
    skip "带宽口径核对（Mbps ≈ ΣbytesSpeed×8/1e6）需真实 ZLM 与多路播放，未自动执行"
    skip "SSE 与 CSV 的 proxy_buffering 行为需真实播放/大导出观察，未自动执行"
}

# ---------- 步骤 6：备份与恢复 ----------
restored_count() {
    dc exec -T postgres psql -U "$PG_USER" -d solo_verify_restore -tAc "select count(*) from $1" 2>/dev/null \
        | tr -d '[:space:]'
}

# $1=表名 $2=恢复后行数 $3=dump 前行数 $4=dump 后行数
check_restored_count() {
    local t="$1" dst="$2" lo="$3" hi="$4" tmp v
    # 三个值必须逐个校验：拼成 "$dst$lo$hi" 再判只会发现"全都没值"，
    # 中间或末位为空时拼出来仍是纯数字，逃过守卫后在 [ -ge ] 处报 integer expression expected
    for v in "$dst" "$lo" "$hi"; do
        case "$v" in
            ''|*[!0-9]*) bad "表 $t 行数读取失败（恢复=$dst，备份前后=$lo..$hi）"; return 0 ;;
        esac
    done
    # 不能假定源库只增不减：保留期 120s 一轮，若上一轮样本没清干净，
    # 备份窗口里正好被回收掉，after 就小于 before，区间倒置后这条断言恒假
    if [ "$lo" -gt "$hi" ]; then tmp="$lo"; lo="$hi"; hi="$tmp"; fi
    if [ "$dst" -ge "$lo" ] && [ "$dst" -le "$hi" ]; then
        ok "表 $t 行数与备份时间点吻合（恢复 $dst，源库 $lo..$hi）"
    else
        bad "表 $t 行数异常：恢复 $dst，源库备份前后 $lo..$hi"
    fi
    return 0
}

step_backup() {
    section "6/6 备份与恢复演练"
    if [ "${VERIFY_SKIP_BACKUP:-0}" = "1" ]; then skip "VERIFY_SKIP_BACKUP=1"; return 0; fi

    local work="$TMP_DIR/backup"
    mkdir -p "$work"
    local dump="" used_script=0
    # 源库在备份期间仍会有写入（登录、导出都会落审计），所以"恢复后的行数"只能要求
    # 落在 [dump 前, dump 后] 区间内；直接和"此刻的源库"相比会偶发假 FAIL
    local before_user before_chan before_audit
    before_user=$(psql_q "select count(*) from sys_user")
    before_chan=$(psql_q "select count(*) from res_channel")
    before_audit=$(psql_q "select count(*) from audit_log")

    if command -v pg_dump >/dev/null 2>&1 && command -v pg_restore >/dev/null 2>&1; then
        info "执行 scripts/backup.sh（宿主机 pg_dump → 127.0.0.1:$DB_HOST_PORT）"
        if PGPASSWORD="$PG_PASS" DB_KIND=pg DB_HOST=127.0.0.1 DB_PORT="$DB_HOST_PORT" \
           DB_NAME="$PG_DB" DB_USER="$PG_USER" BACKUP_DIR="$work" KEEP_DAYS=14 \
           bash "$ROOT_DIR/scripts/backup.sh" >"$TMP_DIR/backup.log" 2>&1; then
            dump=$(find "$work" -name '*.dump' | head -1)
            used_script=1
            # backup.sh 是"dump 成功且 pg_restore --list 通过"才返回 0，
            # 但产物名由它的 BACKUP_DIR/DB_NAME 拼，真的没落盘时不能算 PASS
            if [ -n "$dump" ] && [ -s "$dump" ]; then
                ok "backup.sh 成功，产物 $(basename "$dump") $(du -h "$dump" 2>/dev/null | cut -f1)"
            else
                bad "backup.sh 返回成功但 $work 下没有 dump 产物"
            fi
        elif grep -q 'server version mismatch' "$TMP_DIR/backup.log"; then
            # Ubuntu 22.04 这类机器宿主 pg_dump 常是 14/15，连不上 16 的服务端；
            # 这是预期内的降级，不该记为失败
            skip "宿主机 pg_dump 版本低于服务端 16（server version mismatch），改用容器内 dump"
        else
            bad "backup.sh 失败：$(tail -3 "$TMP_DIR/backup.log" | tr '\n' ' ')"
        fi
    else
        skip "宿主机缺 pg_dump/pg_restore，跳过 scripts/backup.sh"
    fi

    if [ -z "$dump" ] || [ ! -s "$dump" ]; then
        info "退化：容器内 pg_dump 生成等价产物"
        if dc exec -T postgres pg_dump -U "$PG_USER" -d "$PG_DB" -Fc --no-owner --no-privileges \
             > "$work/fallback.dump" 2>"$TMP_DIR/dump.err" && [ -s "$work/fallback.dump" ]; then
            dump="$work/fallback.dump"
            ok "容器内 pg_dump 产物 $(du -h "$dump" | cut -f1)"
        else
            bad "容器内 pg_dump 也失败：$(tail -2 "$TMP_DIR/dump.err" | tr '\n' ' ')"
            return 1
        fi
    else
        if command -v pg_restore >/dev/null 2>&1 && pg_restore --list "$dump" >/dev/null 2>&1; then
            ok "产物自校验通过（pg_restore --list）"
        else
            skip "宿主机无可用 pg_restore，产物自校验交给下面的恢复步骤"
        fi
    fi

    # 恢复到临时库后比对行数
    # WITH (FORCE)：上次中断可能留下连接，普通 DROP 会失败；失败后 CREATE 又撞"已存在"，
    # 报出来的却是"建临时库失败"，完全指不到真因
    dc exec -T postgres psql -U "$PG_USER" -d postgres -q \
        -c 'DROP DATABASE IF EXISTS solo_verify_restore WITH (FORCE)' >/dev/null 2>&1
    dc exec -T postgres psql -U "$PG_USER" -d postgres -q -c 'CREATE DATABASE solo_verify_restore' >/dev/null 2>&1 \
        || { bad "建临时库失败（残留连接或权限问题）"; return 1; }
    # 用 stdin 送进容器：docker cp 在 Windows Git Bash 下会把 /tmp 改写成宿主机路径
    dc exec -T postgres sh -c 'cat > /tmp/verify.dump' < "$dump" \
        || { bad "复制备份进容器失败"; return 1; }

    local restored=0
    if dc exec -T postgres sh -c "pg_restore -U $PG_USER -d solo_verify_restore --no-owner --no-privileges /tmp/verify.dump" \
         >"$TMP_DIR/restore.log" 2>&1; then
        ok "pg_restore 到临时库成功"
        restored=1
    else
        bad "pg_restore 失败：$(tail -4 "$TMP_DIR/restore.log" | tr '\n' ' ')"
    fi

    if [ "$restored" = "1" ]; then
        local after_user after_chan after_audit
        after_user=$(psql_q "select count(*) from sys_user")
        after_chan=$(psql_q "select count(*) from res_channel")
        after_audit=$(psql_q "select count(*) from audit_log")
        check_restored_count sys_user      "$(restored_count sys_user)"      "$before_user"  "$after_user"
        check_restored_count res_channel   "$(restored_count res_channel)"   "$before_chan"  "$after_chan"
        check_restored_count audit_log     "$(restored_count audit_log)"     "$before_audit" "$after_audit"
    else
        skip "恢复失败，跳过行数比对（避免连带 FAIL 掩盖真因）"
    fi

    dc exec -T postgres psql -U "$PG_USER" -d postgres -q \
        -c 'DROP DATABASE IF EXISTS solo_verify_restore WITH (FORCE)' >/dev/null 2>&1
    dc exec -T postgres sh -c 'rm -f /tmp/verify.dump' >/dev/null 2>&1 || true
    info "临时库已清理；产物保留在 $dump（脚本退出即随临时目录删除）"
    [ "$used_script" = "1" ] && ok "验证的是 scripts/backup.sh 的真实产物" || skip "backup.sh 未执行，仅验证了等价 dump"
}

# ---------- 收尾 ----------
step_logs() {
    section "solo 最近日志（含 M7 关键字）"
    dc logs --tail 200 solo 2>/dev/null | grep -E '\[健康检查\]|\[慢SQL\]|\[保留期\]|\[审计\]|触发限流|降级放行' | tail -30 \
        || info "暂无 M7 关键字日志"
}

step_down() {
    section "停止验证栈"
    if [ "${VERIFY_RESET:-0}" = "1" ]; then
        dc down -v >/dev/null 2>&1 && ok "已 down 并删除卷"
    else
        dc down >/dev/null 2>&1 && ok "已 down（卷保留，便于复跑；VERIFY_RESET=1 可清库）"
    fi
}

summary() {
    printf '\n%s== 汇总 ==%s\n' "$C_B" "$C_0"
    printf '  PASS %d / FAIL %d / SKIP %d\n' "$PASSED" "$FAILED" "$SKIPPED"
    printf '  验证栈项目名：%s；UI %s；API %s；DB 127.0.0.1:%s\n' "$PROJECT" "$UI" "$BASE" "$DB_HOST_PORT"
    if [ "$FAILED" -gt 0 ]; then
        printf '  %s存在失败项：按上面 FAIL 行逐条处理，或用 logs 子命令看服务端日志%s\n' "$C_R" "$C_0"
        return 1
    fi
    printf '  %s自动化项全部通过；剩余人工项见 step_bandwidth 的 SKIP 行%s\n' "$C_G" "$C_0"
    return 0
}

# ---------- 主流程 ----------
case "$ACTION" in
    -h|--help|help) usage; exit 0 ;;
esac

preflight
write_env_files

# 端口与"线上栈在跑"只在起栈时判断：down/logs 时本栈自己就占着这些端口
case "$ACTION" in
    up|all) check_ports ;;
esac

case "$ACTION" in
    up)
        step_up
        ;;
    probe|retention|export|bandwidth|backup)
        [ -n "$(dc ps -q solo 2>/dev/null)" ] || die "验证栈未启动，先跑：bash scripts/verify_m7.sh up"
        wait_ready 300
        # 上游 solo 若被手工重建过，nginx 缓存的 IP 会失效，先自愈再断言
        ensure_ui_up || true
        "step_$ACTION"
        ;;
    all)
        step_up || die "起栈失败，后续步骤无意义"
        step_probe
        step_retention
        step_export
        step_bandwidth
        step_backup
        step_logs
        ;;
    logs) step_logs ;;
    down) step_down ;;
    *) usage; die "未知子命令：$ACTION" ;;
esac

if [ "$ACTION" = "all" ]; then
    if [ "${VERIFY_KEEP:-0}" = "1" ]; then
        info "VERIFY_KEEP=1：栈保持运行，稍后可 bash scripts/verify_m7.sh down"
    elif [ "$FAILED" -gt 0 ]; then
        # 失败时保留现场：容器、卷、日志都还在，才能 logs / 进库复查
        printf '%s  有失败项，栈保持运行以便排查（确认不用了再 bash scripts/verify_m7.sh down）%s\n' \
            "$C_Y" "$C_0"
    else
        step_down
    fi
fi

summary
