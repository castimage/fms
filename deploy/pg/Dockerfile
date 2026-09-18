# FMS 定制 PostgreSQL 18 镜像（fms-postgres:18）
# 基于官方 postgres:18，全部 PG 扩展采用「下载源码 make install」方式安装：
#   pgaudit（数据库审计）/ pg_cron（定时任务）/ pg_partman（分区维护）/
#   pgjwt（JWT 验签）/ pg_ivm（增量物化视图）/ zhparser（中文分词）/
#   pgmq（消息队列）；不安装任何 PGDG 二进制扩展包（postgresql-18-*）。
# PGDG apt 源仅保留用于构建依赖（postgresql-server-dev-18 提供 server 头文件
# 与 pg_config——官方 postgres:18 镜像不含 dev 文件，源码编译 PG 扩展必需）。
# shared_preload_libraries 基线写入 postgresql.conf.sample（initdb 以该样例
# 生成运行时配置，满足 preload 基线 pgaudit,pg_cron,
# pg_partman_bgw；zhparser/pgjwt/pgmq 无需 preload）。
# 用途：docker-compose.yml / docker-compose.multi.yml 的 fms-db 服务
#       build: ./deploy/pg，image: fms-postgres:18。

FROM postgres:18

# PGDG apt 源（postgres:18 基于 Debian 13，apt-key 已移除，改用
# gpg --dearmor + signed-by 现代方式；国内可换 mirrors.tuna/aliyun 加速）。
# 本源仅用于安装构建依赖，扩展一律源码 make install（见下）。
RUN apt-get update \
    && apt-get install -y curl gnupg lsb-release \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update

# 构建依赖（源码编译 PG 扩展的工具链）：postgresql-server-dev-18 提供
# server 头文件与 pg_config；build-essential/git/wget 供各扩展编译下载；
# pkg-config + libxml2-dev 为 zhparser 的 SCWS 依赖编译探测所需；
# libkrb5-dev 提供 gssapi.h（pgaudit 间接 include server 的 pg-gssapi.h，
# 缺该头会 fatal error）。
RUN apt-get install -y build-essential postgresql-server-dev-18 git wget pkg-config libxml2-dev libkrb5-dev

# 追加共享库配置：initdb 以 postgresql.conf.sample 为模板生成运行时
# postgresql.conf，在此追加即全局生效（zhparser/pgjwt/pgmq 无需 preload）。
# 注意本行必须先于下方 pg_ivm 段执行——pg_ivm 安装成功时用 sed 改写本行。
RUN echo "shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw'" >> /usr/share/postgresql/18/postgresql.conf.sample

# 源码安装扩展（统一模式为 git clone --depth 1 下载源码，再 make && make install；
# 每段独立 || echo WARN 容错：失败仅打印 WARN 不中断构建——缺失项由 init
# 脚本探测 pg_extension / pg_available_extensions 降级处理，见
# deploy/init/fms_init.sh 与文件末降级矩阵）

# pgaudit：数据库审计（github.com/pgaudit/pgaudit，固定 release 18.0——
# master 处于 19beta 开发分支，18.0 为支持 PG 18 的稳定版）。编译产物
# pgaudit.so，已加入 preload 基线；bootstrap_base 建库即依赖，必需安装。
# Makefile 未定义 USE_PGXS 时走 PG 源码树内构建（../../src/
# Makefile.global），独立构建必须显式 USE_PGXS=1（经 pg_config 定位 PGXS）。
RUN git clone --depth 1 --branch 18.0 https://github.com/pgaudit/pgaudit /tmp/pgaudit \
    && cd /tmp/pgaudit && make USE_PGXS=1 && make USE_PGXS=1 install \
    && echo "OK: pgaudit 源码安装成功" \
    || echo "WARN: pgaudit 源码安装失败（bootstrap_base 将失败）"

# pg_cron：定时任务（github.com/citusdata/pg_cron）。编译产物库名 cron.so，
# 而 README 约定 preload 名 'pg_cron'——补建 pg_cron.so 符号链接以兼容该
# 写法（与 PGDG 包行为一致），preload 基线见上。
RUN git clone --depth 1 https://github.com/citusdata/pg_cron /tmp/pg_cron \
    && cd /tmp/pg_cron && make && make install \
    && (test -f /usr/lib/postgresql/18/lib/pg_cron.so || ln -sf /usr/lib/postgresql/18/lib/cron.so /usr/lib/postgresql/18/lib/pg_cron.so) \
    && echo "OK: pg_cron 源码安装成功" \
    || echo "WARN: pg_cron 源码安装失败（bootstrap_base 将失败）"

# pg_partman：分区维护（github.com/pgpartman/pg_partman）。编译产物
# pg_partman_bgw.so，已加入 preload 基线；bootstrap_base 必需。
RUN git clone --depth 1 https://github.com/pgpartman/pg_partman /tmp/pg_partman \
    && cd /tmp/pg_partman && make && make install \
    && echo "OK: pg_partman 源码安装成功" \
    || echo "WARN: pg_partman 源码安装失败（bootstrap_base 将失败）"

# pgjwt：JWT 验签（纯 SQL 扩展，github.com/michelp/pgjwt）。
# bootstrap_base 以 CREATE EXTENSION pgjwt WITH SCHEMA pgjwt 安装。
RUN git clone --depth 1 https://github.com/michelp/pgjwt /tmp/pgjwt \
    && cd /tmp/pgjwt && make && make install \
    && echo "OK: pgjwt 源码安装成功" \
    || echo "WARN: pgjwt 源码安装失败（pgjwt.sign/verify 将不可用，bootstrap_base 失败时 init 诊断会提示）"

# pg_ivm：增量物化视图（github.com/sraoss/pg_ivm）。仅当整条命令成功时
# 才把 pg_ivm 追加进 shared_preload_libraries（sed 改写上行追加的行）；
# 失败仅打印 WARN 不中断构建——缺失时 init 脚本对 bootstrap_base 失败的
# 诊断会提示安装途径。
RUN git clone --depth 1 https://github.com/sraoss/pg_ivm /tmp/pg_ivm \
    && cd /tmp/pg_ivm && make && make install \
    && sed -i "s/shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw'/shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw,pg_ivm'/" /usr/share/postgresql/18/postgresql.conf.sample \
    && echo "OK: pg_ivm 源码编译安装成功并已加入 shared_preload_libraries" \
    || echo "WARN: pg_ivm 源码安装失败（init 脚本对 bootstrap_base 失败的诊断会提示）"

# zhparser：中文分词（github.com/amutu/zhparser，依赖 SCWS 中文分词库 +
# pkg-config + libxml2-dev）：
# scws 用发布版 tarball（GitHub 仓库是 autotools 源无 configure）：
#     xunsearch.com 官方下载。老代码 unlink 隐式声明会被新 gcc 默认
# Werror=implicit-function-declaration 判错，须以 CFLAGS 显式关闭；
# zhparser 从 GitHub 源码 make（SCWS_HOME=/usr/local 找头文件）；
# make install 后 ldconfig 刷新动态库缓存
RUN wget -q -O - http://www.xunsearch.com/scws/down/scws-1.2.1.tar.bz2 | tar xjf - -C /tmp \
    && cd /tmp/scws-1.2.1 \
    && ./configure CFLAGS="-O2 -Wno-implicit-function-declaration" \
    && make -s && make install && ldconfig \
    && git clone --depth 1 https://github.com/amutu/zhparser /tmp/zhparser \
    && cd /tmp/zhparser && make && make install \
    && echo "OK: zhparser 源码安装成功（依赖 scws）" \
    || echo "WARN: zhparser 源码安装失败（全文检索 fms_zh 将不可用，bootstrap_base 失败时 init 诊断会提示）"

# pgmq：消息队列（官方仓库 github.com/pgmq/pgmq，扩展在 pgmq-extension/
# 子目录，现为纯 SQL 实现，无 C/preload，make install 经 PGXS 装控制文件与
# SQL 对象）。pg_extension 有记录即视为已安装，bootstrap_base 的 CREATE
# EXTENSION IF NOT EXISTS pgmq 直接成功；无记录即视为未安装，queues_cron
# 跳过队列/授权、log_access_batch 直落库，均不影响建库。
RUN git clone --depth 1 https://github.com/pgmq/pgmq /tmp/pgmq \
    && cd /tmp/pgmq/pgmq-extension && make && make install \
    && echo "OK: pgmq 源码安装成功" \
    || echo "WARN: pgmq 源码安装失败（运行期 pg_extension 无记录即视为未安装，NOTICE 跳过队列/授权）"

# 缺失处理（供故障排查参考）：
#   pgaudit / pg_cron / pg_partman_bgw 缺失，bootstrap_base 直接失败（必须安装）
#   zhparser / pg_ivm / pgmq 缺失按未安装处理（pgmq 不再探测实际功能，仅 NOTICE 跳过）
# 源码安装均为 git clone --depth 1（默认取仓库 HEAD）；pgaudit 因 master 处于
# 19beta 开发分支，固定 --branch 18.0（PG 18 稳定版）。其余扩展如需固定
# 版本可加 --branch <tag>（如 pg_cron 1.6.x / pg_partman 5.x）。
