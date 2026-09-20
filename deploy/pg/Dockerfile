# FMS 定制 PostgreSQL 18 镜像，tag 为 fms-postgres:18
# 基于官方 postgres:18，全部 pg 扩展采用下载源码 make install 的方式安装，
# 含 pgaudit 数据库审计，pg_cron 定时任务，pg_partman 分区维护，pgjwt 的 jwt 验签，
# pg_ivm 增量物化视图，zhparser 中文分词，pgmq 消息队列
# 不安装任何 pgdg 二进制扩展包，pgdg apt 源仅保留用于安装构建依赖，
# postgresql-server-dev-18 提供 server 头文件与 pg_config，官方 postgres:18 镜像不含 dev 文件，源码编译 pg 扩展必需
# shared_preload_libraries 基线写入 postgresql.conf.sample，initdb 以该样例生成运行时配置，
# 基线为 pgaudit,pg_cron,pg_partman_bgw，zhparser 与 pgjwt 与 pgmq 无需 preload

FROM postgres:18

# pgdg apt 源，postgres:18 基于 Debian 13，apt-key 已移除，改用 gpg --dearmor 与 signed-by 的现代方式
# 本源仅用于安装构建依赖，扩展一律源码 make install
RUN apt-get update \
    && apt-get install -y curl gnupg lsb-release \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/pgdg.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/pgdg.gpg] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update

# 构建依赖，即源码编译 pg 扩展的工具链，postgresql-server-dev-18 提供 server 头文件与 pg_config，
# build-essential 与 git 与 wget 供各扩展编译下载，
# pkg-config 与 libxml2-dev 为 zhparser 的 scws 依赖编译探测所需，
# libkrb5-dev 提供 gssapi.h，pgaudit 会间接 include server 的 pg-gssapi.h，缺该头即 fatal error
RUN apt-get install -y build-essential postgresql-server-dev-18 git wget pkg-config libxml2-dev libkrb5-dev

# 追加共享库配置，initdb 以 postgresql.conf.sample 为模板生成运行时 postgresql.conf，在此追加即全局生效
# 本行与下方 pg_ivm 段耦合，该段安装成功时以 sed 改写本行追加 pg_ivm
RUN echo "shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw'" >> /usr/share/postgresql/18/postgresql.conf.sample

# 源码安装各扩展，统一模式为 git clone --depth 1 下载源码再 make && make install
# 每段以 || echo WARN 独立容错，失败仅打印 WARN 不中断构建，
# 缺失项由初始化脚本探测扩展可用性后降级处理

# pgaudit 数据库审计，编译产物为 pgaudit.so，已在 preload 基线内，建库即依赖，必需安装
# 固定 release 18.0，该扩展 master 处于 19beta 开发分支，18.0 为支持 pg 18 的稳定版
# Makefile 未定义 USE_PGXS 时走 pg 源码树内构建，独立构建必须显式 USE_PGXS=1 经 pg_config 定位 pgxs
RUN git clone --depth 1 --branch 18.0 https://github.com/pgaudit/pgaudit /tmp/pgaudit \
    && cd /tmp/pgaudit && make USE_PGXS=1 && make USE_PGXS=1 install \
    && echo "OK: pgaudit 源码安装成功" \
    || echo "WARN: pgaudit 源码安装失败（bootstrap_base 将失败）"

# pg_cron 定时任务，编译产物库名为 cron.so，而 preload 基线按 pg_cron 书写，
# 故补建 pg_cron.so 符号链接以兼容该写法，与 pgdg 包行为一致
RUN git clone --depth 1 https://github.com/citusdata/pg_cron /tmp/pg_cron \
    && cd /tmp/pg_cron && make && make install \
    && (test -f /usr/lib/postgresql/18/lib/pg_cron.so || ln -sf /usr/lib/postgresql/18/lib/cron.so /usr/lib/postgresql/18/lib/pg_cron.so) \
    && echo "OK: pg_cron 源码安装成功" \
    || echo "WARN: pg_cron 源码安装失败（bootstrap_base 将失败）"

# pg_partman 分区维护，编译产物为 pg_partman_bgw.so，已在 preload 基线内，建库必需
RUN git clone --depth 1 https://github.com/pgpartman/pg_partman /tmp/pg_partman \
    && cd /tmp/pg_partman && make && make install \
    && echo "OK: pg_partman 源码安装成功" \
    || echo "WARN: pg_partman 源码安装失败（bootstrap_base 将失败）"

# pgjwt 的 jwt 验签，纯 sql 扩展，建库时以 CREATE EXTENSION pgjwt WITH SCHEMA pgjwt 安装
RUN git clone --depth 1 https://github.com/michelp/pgjwt /tmp/pgjwt \
    && cd /tmp/pgjwt && make && make install \
    && echo "OK: pgjwt 源码安装成功" \
    || echo "WARN: pgjwt 源码安装失败（pgjwt.sign/verify 将不可用，bootstrap_base 失败时 init 诊断会提示）"

# pg_ivm 增量物化视图
# 仅当整条命令成功时才把 pg_ivm 追加进 shared_preload_libraries，即用 sed 改写上文追加的那行，
# 失败仅打印 WARN 不中断构建，缺失时建库失败的诊断会提示安装途径
RUN git clone --depth 1 https://github.com/sraoss/pg_ivm /tmp/pg_ivm \
    && cd /tmp/pg_ivm && make && make install \
    && sed -i "s/shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw'/shared_preload_libraries = 'pgaudit,pg_cron,pg_partman_bgw,pg_ivm'/" /usr/share/postgresql/18/postgresql.conf.sample \
    && echo "OK: pg_ivm 源码编译安装成功并已加入 shared_preload_libraries" \
    || echo "WARN: pg_ivm 源码安装失败（init 脚本对 bootstrap_base 失败的诊断会提示）"

# zhparser 中文分词，依赖 scws 中文分词库与 pkg-config 与 libxml2-dev
# scws 用发布版 tarball 安装，其源码仓库为 autotools 源无 configure
# scws 老代码的 unlink 隐式声明会被新 gcc 默认的 Werror=implicit-function-declaration 判错，须以 CFLAGS 显式关闭
# zhparser 从源码 make，SCWS_HOME=/usr/local 用于定位头文件，make install 后 ldconfig 刷新动态库缓存
RUN wget -q -O - http://www.xunsearch.com/scws/down/scws-1.2.1.tar.bz2 | tar xjf - -C /tmp \
    && cd /tmp/scws-1.2.1 \
    && ./configure CFLAGS="-O2 -Wno-implicit-function-declaration" \
    && make -s && make install && ldconfig \
    && git clone --depth 1 https://github.com/amutu/zhparser /tmp/zhparser \
    && cd /tmp/zhparser && make && make install \
    && echo "OK: zhparser 源码安装成功（依赖 scws）" \
    || echo "WARN: zhparser 源码安装失败（全文检索 fms_zh 将不可用，bootstrap_base 失败时 init 诊断会提示）"

# pgmq 消息队列，扩展位于其仓库的 pgmq-extension 子目录，现为纯 sql 实现，无 C 代码与 preload，
# make install 经 pgxs 装控制文件与 sql 对象
# pg_extension 有记录即视为已安装，建库的 CREATE EXTENSION IF NOT EXISTS pgmq 直接成功，
# 无记录即视为未安装，初始化脚本跳过队列与授权并让访问日志直落库，均不影响建库
RUN git clone --depth 1 https://github.com/pgmq/pgmq /tmp/pgmq \
    && cd /tmp/pgmq/pgmq-extension && make && make install \
    && echo "OK: pgmq 源码安装成功" \
    || echo "WARN: pgmq 源码安装失败（运行期 pg_extension 无记录即视为未安装，NOTICE 跳过队列/授权）"

# 缺失处理
# pgaudit 与 pg_cron 与 pg_partman_bgw 缺失时建库直接失败，属必须安装项
# zhparser 与 pg_ivm 与 pgmq 缺失按未安装处理，pgmq 不再探测实际功能，仅 NOTICE 跳过
# 源码安装均为 git clone --depth 1 取仓库 HEAD，pgaudit 因开发分支关系固定 --branch 18.0，
# 其余扩展如需固定版本可追加 --branch 与该 tag
