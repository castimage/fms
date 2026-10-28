#!/bin/sh
# FMS PgBouncer 容器入口
# 职责：以 $DB_HOST，默认 fms-db，可指向宿主 IP 支持多机直连，sed 替换
# pgbouncer.ini 中的 DB_HOST_PLACEHOLDER；以 FMS_DB_PASSWORD，fms_app
# 用户，与 POSTGRES_PASSWORD，postgres 用户，生成 userlist.txt。
# scram-sha-256 模式下 auth_file 存明文密码即可，pgbouncer 启动期自行
# 计算 SCRAM 摘要，密码经 sed 转义注入模板行；userlist 格式无引号转义
# 机制，.env 密码应避免含双引号；最后 exec 透传 CMD 启动 pgbouncer。
# 懒连接：pgbouncer 启动不依赖 fms-db 可达，事务时才按需建连，
# 无需等待主库就绪。
set -e

CONF=/etc/pgbouncer/pgbouncer.ini
USERLIST=/etc/pgbouncer/userlist.txt

# sed 替换文本转义：\ 与 & 是替换段特殊字符需反斜杠转义，分隔符用 |，管道符
# 一并转义，避免常见密码字符破坏替换
esc_for_sed() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/|/\\|/g'
}

DB_HOST="${DB_HOST:-fms-db}"
sed -i "s|DB_HOST_PLACEHOLDER|$(esc_for_sed "$DB_HOST")|g" "$CONF"
echo "[pgbouncer] DB_HOST=$DB_HOST 已替换入 pgbouncer.ini"

: > "$USERLIST"

# fms_app：后端业务用户，健康检查也用它，必填
if [ -n "${FMS_DB_PASSWORD:-}" ]; then
    printf '"fms_app" "PASSWORD_PLACEHOLDER"\n' \
        | sed "s|PASSWORD_PLACEHOLDER|$(esc_for_sed "$FMS_DB_PASSWORD")|g" >> "$USERLIST"
    echo "[pgbouncer] userlist: fms_app 已写入"
else
    echo "[pgbouncer] WARN: FMS_DB_PASSWORD 为空，fms_app 未写入 userlist（后端将无法认证）"
fi

# postgres：运维/迁移用，pg_dump 等经 6432 以 postgres 身份连接
if [ -n "${POSTGRES_PASSWORD:-}" ]; then
    printf '"postgres" "PASSWORD_PLACEHOLDER"\n' \
        | sed "s|PASSWORD_PLACEHOLDER|$(esc_for_sed "$POSTGRES_PASSWORD")|g" >> "$USERLIST"
    echo "[pgbouncer] userlist: postgres 已写入"
else
    echo "[pgbouncer] WARN: POSTGRES_PASSWORD 为空，postgres 未写入 userlist"
fi

exec "$@"
