#!/bin/sh
# FMS 从库 standby 容器入口 —— pg_basebackup + 流复制
# 职责：等待主库就绪后 pg_basebackup -R -X stream，再补 primary_conninfo
# 密码后写 standby.signal，再 exec postgres，热备只读运行。
# 幂等：$PGDATA/PG_VERSION 已存在，basebackup 完成过/卷已初始化，则跳过
# basebackup 直接启动，容器/主机重启不重复全量拉取。
# 说明：从库只读，不执行业务 init 脚本与扩展安装，standby 上 CREATE
# EXTENSION 等写入不允许，扩展随 basebackup 从主库带来——主从同用
# 定制镜像 fms-postgres:18，不必为从库单独构建。
# 参数：PRIMARY_HOST 默认 fms-db / PRIMARY_PORT / REPL_USER 默认
# replicator / REPL_PASSWORD 缺省回退 FMS_REPL_PASSWORD /
# PGDATA 官方镜像 /var/lib/postgresql/18/docker。
set -e

PRIMARY_HOST="${PRIMARY_HOST:-fms-db}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPL_USER="${REPL_USER:-replicator}"
REPL_PASSWORD="${REPL_PASSWORD:-${FMS_REPL_PASSWORD:-}}"
PGDATA="${PGDATA:-/var/lib/postgresql/18/docker}"

# sed 替换文本转义，\ 与 & 是替换段特殊字符，| 为分隔符
esc_for_sed() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g'
}

# conninfo 值转义：sed 替换级 \\ & |，libpq 引号级 ' 转 ''，顺序不可反
esc_conninfo() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g' -e "s/'/''/g"
}

# 卷挂载目录可能为空/宿主属主，必须归 postgres 用户，镜像内 uid 999；
# chmod 0700 必要：entrypoint 以 root 执行 mkdir -p 时目录为 0755 root，
# pg_basebackup 不修正顶层权限，postgres 启动校验 0700/0750 直接 FATAL，
# 报 data directory has invalid permissions，幂等修复每次启动执行
mkdir -p "$PGDATA"
chown postgres:postgres /var/lib/postgresql /var/lib/postgresql/18 "$PGDATA" 2>/dev/null || true
chmod 0700 "$PGDATA" 2>/dev/null || true

if [ -s "$PGDATA/PG_VERSION" ]; then
    echo "[replica] $PGDATA/PG_VERSION 已存在，跳过 pg_basebackup（幂等）"
else
    if [ -z "$REPL_PASSWORD" ]; then
        echo "[replica] FATAL: REPL_PASSWORD/FMS_REPL_PASSWORD 未设置，无法认证复制用户" >&2
        exit 1
    fi

    # 上次失败的 basebackup 残留：pg_basebackup 要求目标目录为空，
    # 无 PG_VERSION 的非空目录须清空后才能重试，幂等重试兜底
    if [ -n "$(ls -A "$PGDATA" 2>/dev/null)" ]; then
        echo "[replica] WARN: $PGDATA 非空且无 PG_VERSION（疑似上次 basebackup 失败残留），清空后重试"
        find "$PGDATA" -mindepth 1 -delete
    fi

    echo "[replica] 等待主库 $PRIMARY_HOST:$PRIMARY_PORT 就绪（pg_isready，300s 超时）..."
    waited=0
    while ! pg_isready -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U postgres >/dev/null 2>&1; do
        waited=$((waited + 2))
        if [ "$waited" -ge 300 ]; then
            echo "[replica] FATAL: 主库 300 秒内未就绪，退出（检查 fms-db 健康状态与网络）" >&2
            exit 1
        fi
        sleep 2
    done
    echo "[replica] 主库已就绪，开始 pg_basebackup（-R -X stream -P）..."

    PGPASSWORD="$REPL_PASSWORD" gosu postgres pg_basebackup \
        -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$REPL_USER" \
        -D "$PGDATA" -R -X stream -P
    echo "[replica] pg_basebackup 完成"

    # -R 已写 primary_conninfo，不含密码，standby 连主库拉 WAL 需要密码，
    # 整行重写补入，host/port/user 来自环境变量，值域安全。
    # 注意：password= 值后不能有字面引号——整个 conninfo 已被外层 '...' 包裹，
    # 密码内的单引号经 esc_conninfo 转成 ''，PG 配置单引号字符串内的转义形式，
    # 多余引号会让 PG 解析出裸 token unrecognized near token 8
    if [ -f "$PGDATA/postgresql.auto.conf" ]; then
        ESC=$(esc_conninfo "$REPL_PASSWORD")
        NEW_CONN="primary_conninfo = 'host=${PRIMARY_HOST} port=${PRIMARY_PORT} user=${REPL_USER} password=${ESC} application_name=fms-db-replica'"
        if grep -q '^primary_conninfo' "$PGDATA/postgresql.auto.conf"; then
            sed -i "s|^primary_conninfo = '.*|${NEW_CONN}|" "$PGDATA/postgresql.auto.conf"
        else
            echo "$NEW_CONN" >> "$PGDATA/postgresql.auto.conf"
        fi
    fi
fi

# 标记 standby，热备模式，幂等 touch
touch "$PGDATA/standby.signal"
chown postgres:postgres "$PGDATA/standby.signal"

echo "[replica] 以 standby 模式启动 PostgreSQL（PGDATA=$PGDATA）..."
# 官方镜像以 postgres 用户运行，此处 gosu 切用户，root 直启 postgres 会被拒绝
exec gosu postgres postgres
