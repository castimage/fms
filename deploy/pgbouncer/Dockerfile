# FMS PgBouncer 镜像，tag 为 fms-pgbouncer:1.24
# 基于 debian:trixie-slim 安装 pgbouncer 与 postgresql-client，psql 供 compose 健康检查使用
# trixie 仓库的 pgbouncer 为 1.24，1.18 及以上即支持 scram-sha-256 的 auth_file 明文密码
# 与事务池的 DISCARD ALL，功能覆盖本部署件场景
# 镜像 tag 1.24 与仓库版本一致
# 配置 pgbouncer.ini 内含 DB_HOST_PLACEHOLDER，由入口脚本以 $DB_HOST 替换后启动，
# 入口脚本同时以 .env 密码生成 userlist.txt

FROM debian:trixie-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends pgbouncer postgresql-client \
    && rm -rf /var/lib/apt/lists/* \
    # 显式创建 pgbouncer 系统用户，幂等，Debian 包版本间行为不一致，
    # 部分构建不创建同名用户而 chown 会失败，此处兜底保证确定
    && (id -u pgbouncer >/dev/null 2>&1 \
        || (groupadd -r pgbouncer && useradd -r -g pgbouncer -s /usr/sbin/nologin pgbouncer))

# 复制配置与入口，/etc/pgbouncer 归 pgbouncer 用户，
# 入口脚本需在容器启动时以 sed -i 改写 ini 并生成 userlist.txt，须有目录写权限
COPY pgbouncer.ini /etc/pgbouncer/pgbouncer.ini
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
    && chown -R pgbouncer:pgbouncer /etc/pgbouncer

# 运行用户，Debian 包安装时已创建 pgbouncer 系统用户
# 环境变量如 PGPASSWORD 与进程用户无关，健康检查的 psql 可读
USER pgbouncer
EXPOSE 6432
ENTRYPOINT ["/entrypoint.sh"]
CMD ["pgbouncer", "/etc/pgbouncer/pgbouncer.ini"]
