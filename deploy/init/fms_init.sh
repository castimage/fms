#!/bin/sh
# FMS 数据库初始化唯一入口，挂载至 /docker-entrypoint-initdb.d/fms_init.sh
# 由官方 postgres entrypoint 以 POSTGRES_USER=postgres 超级用户执行，
# 单会话内 psql 支持脚本内部 \c 切换库，bootstrap_base 在 postgres 库建角色、
# 建库后 \c fms，queues_cron 在 postgres 库注册 pg_cron 后回 fms。
# 文件末段另含主从部署前置，replicator 角色 + pg_hba 复制授权 + wal_level
# 登记，从库 pg_basebackup 前置条件，配套 docker-compose.pg-repl.yml。
# 【目录语义】fms_db_pg/ 按两目录组织，目录级只读挂载至容器 /fms-init，
# init/，新库初始化白名单，见下方 INIT_SCRIPTS，白名单之外的文件一律
#     不执行
# verify/，部署后冒烟校验，只读
# 执行顺序由 INIT_SCRIPTS 显式数组裁决，不依赖文件名排序。
# 演示种子的 API Key 调用方凭据依赖 um.api_keys 建表，故 seed_demo 排在 fill_missing 之后，
# 全新库首轮即有该凭据，不必初始化后再补跑一次。

# 显式顺序数组，白名单
INIT_SCRIPTS="bootstrap_base.sql um_org_base.sql audit_base.sql gl_base.sql gl_core_functions.sql auth_org_functions.sql ar_ap_cash_base.sql fa_expense_budget_wf_base.sql rpt_log_base.sql queues_cron.sql seed_system.sql notify_base.sql grants_base.sql um_perm.sql fill_missing.sql seed_demo.sql um_params_functions.sql um_param_meta.sql auth_api_key_validate.sql backup_codes_persist.sql dashboard_alerts.sql rpt_ap_aging.sql docs_base.sql um_login_log_query.sql gl_voucher_reject.sql um_user_preferences.sql log_access_batch.sql role_fms_public.sql notify_broadcast.sql gl_fx_rates.sql gl_voucher_link.sql"

OK_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
ABORT=0


echo "[fms-init] 开始：FMS 数据库初始化（$(date -u '+%Y-%m-%dT%H:%M:%SZ')）"


# 00 失败诊断，探测 pg_available_extensions 中 zhparser / pg_ivm / pgmq
# 是否缺失，并提示安装途径，deploy/pg/Dockerfile 或手工源码安装
diagnose_00_failure() {
    echo "[fms-init][bootstrap_base] 失败诊断：探测扩展可用性（pg_available_extensions）..."
    MISSING=""
    for ext in zhparser pg_ivm pgmq; do
        if [ "$(psql -U postgres -d postgres -tAc "SELECT 1 FROM pg_available_extensions WHERE name='$ext'")" = "1" ]; then
            echo "[fms-init][bootstrap_base]   扩展 $ext 可用。"
        else
            echo "[fms-init][bootstrap_base]   扩展 $ext 缺失！"
            MISSING="$MISSING $ext"
        fi
    done
    if [ -n "$MISSING" ]; then
        echo "[fms-init][bootstrap_base] 缺失扩展：$MISSING"
        echo "[fms-init][bootstrap_base] 修复途径："
        echo "[fms-init][bootstrap_base]   1) 使用定制镜像 deploy/pg/Dockerfile（全部扩展均源码"
        echo "[fms-init][bootstrap_base]      make install：pgaudit/pg_cron/pg_partman/pgjwt/pg_ivm/"
        echo "[fms-init][bootstrap_base]      zhparser/pgmq），重建后重试；"
        echo "[fms-init][bootstrap_base]   2) 手工源码安装：zhparser（github.com/amutu/zhparser，"
        echo "[fms-init][bootstrap_base]      依赖 scws）、pg_ivm（github.com/sraoss/pg_ivm，"
        echo "[fms-init][bootstrap_base]      make && make install）、pgmq（github.com/pgmq/pgmq，"
        echo "[fms-init][bootstrap_base]      扩展在 pgmq-extension 子目录）。"
    fi
}

for f in $INIT_SCRIPTS; do
    # queues_cron.sql 以 pg_extension 记录判断 pgmq，未装（记录缺失）即视为
    # 无此功能，跳过队列预创建与最小授权；冒烟与 pg_cron 调度按各自可用性执行，
    # 无 pgmq 时整文件不失败。pg_cron 9 个定时任务 partman-maintenance
    # 分区维护 / budget-warning 预算预警 / voucher-number-check 断号巡检 /
    # fx-adjustment 调汇 / fa-depreciation 折旧 / close-check 期末检查 /
    # wf-timeout 流程超时 / audit-chain-check 审计链核验 / log-consumer
    # 日志消费，与 log.* 两个 cron 包装函数均不依赖 pgmq。
    # 缺失 pgmq 的库需用内置 pgmq 的定制镜像，补齐后重跑本文件即补队列与
    # 授权，无需重建容器。

    # 目标库裁决，bootstrap_base 建库，queues_cron 注册 pg_cron，自带 \c
    # 切换、以 postgres 库为锚点，其余 27 个脚本均须在 fms 库执行。
    # 约定，除 bootstrap_base/queues_cron 外全部在 fms 库，psql -f 是
    #   独立会话，\c 不跨脚本生效。
    DBNAME=postgres
    case "$f" in
        bootstrap_base.sql|queues_cron.sql) ;;
        *) DBNAME=fms ;;
    esac

    # 列加密主密钥外部注入，仅 seed_system.sql 之前执行一次，
    # master_key 用于身份证/email/银行账号列加密，必须早于种子数据加密写入，
    # 仅当现值仍为种子默认值时覆盖，存量库已旋转/已注入密钥则 WHERE 不命中跳过，
    # 防止覆盖后既有密文全部不可解密，行不存在则 INSERT，seed_system.sql 的
    # ON CONFLICT DO NOTHING 保留注入值。
    # jwt.secret 无加密列依赖，仍由循环后对齐段统一处理。
    if [ "$f" = "seed_system.sql" ] && [ -n "${FMS_MASTER_KEY:-}" ]; then
        MK_ESCAPED=$(printf '%s' "${FMS_MASTER_KEY}" | sed "s/'/''/g")
        if psql -v ON_ERROR_STOP=1 -U postgres -d fms \
            -c "INSERT INTO sec.app_secrets (code, value, updated_at) VALUES ('pgcrypto.master_key', jsonb_build_object('v', '${MK_ESCAPED}'), now()) ON CONFLICT (code) DO UPDATE SET value = EXCLUDED.value, updated_at = now() WHERE sec.app_secrets.value->>'v' = 'FMS_MK_2026_8d3f9a2c6b7e4f1a9d5c8e2b7f4a6d3c'" \
            > /tmp/fms_init_mk.log 2>&1; then
            echo "[fms-init] PASS: pgcrypto.master_key 已注入外部密钥（FMS_MASTER_KEY）"
        else
            echo "[fms-init] FAIL: master_key 注入失败（详见 /tmp/fms_init_mk.log）"
            FAIL_COUNT=$((FAIL_COUNT+1))
            ABORT=1
        fi
    elif [ "$f" = "seed_system.sql" ]; then
        echo "[fms-init] SKIP: FMS_MASTER_KEY 为空，master_key 使用 init/seed_system.sql 内建值（仅非生产占位）"
    fi

    echo "[fms-init] 执行: $f（库: $DBNAME）"
    if psql -v ON_ERROR_STOP=1 -U postgres -d "$DBNAME" -f "/fms-init/init/$f" > "/tmp/fms_init_$f.log" 2>&1; then
        echo "[fms-init] PASS: $f"
        OK_COUNT=$((OK_COUNT+1))
    else
        echo "[fms-init] FAIL: $f（详见 /tmp/fms_init_$f.log 或 docker compose logs fms-db）"
        FAIL_COUNT=$((FAIL_COUNT+1))
        if [ "$f" = "bootstrap_base.sql" ]; then
            diagnose_00_failure
        fi
        # 核心文件失败则非零退出码中止 init，让容器启动失败暴露问题
        ABORT=1
        break
    fi
done

# 全部脚本执行完毕后，以 .env 注入的 FMS_DB_PASSWORD 对齐 fms_app 角色密码，
# 保证 .env 与 init/bootstrap_base.sql 内建密码保持一致，以 .env 值为最终生效值
if [ -n "${FMS_DB_PASSWORD:-}" ]; then
    # 密码转义，单引号转 ''，sql 标准，杜绝字符串拼接注入，
    # 不用 psql 变量 :'var'，-c 参数中变量插值不生效，原样发给 pg 报语法错
    ESCAPED_PW=$(printf '%s' "${FMS_DB_PASSWORD}" | sed "s/'/''/g")
    if psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
        -c "ALTER ROLE fms_app WITH PASSWORD '${ESCAPED_PW}'" > /tmp/fms_init_alter.log 2>&1; then
        echo "[fms-init] PASS: ALTER ROLE fms_app 密码已对齐 .env 值"
    else
        echo "[fms-init] FAIL: ALTER ROLE fms_app 失败（详见 /tmp/fms_init_alter.log）"
        FAIL_COUNT=$((FAIL_COUNT+1))
        ABORT=1
    fi
else
    echo "[fms-init] WARN: 环境变量 FMS_DB_PASSWORD 为空，跳过 ALTER ROLE fms_app（.env 未配置？）"
fi

# 以 .env 的 FMS_JWT_SECRET 对齐 pg 侧 jwt.secret，init/seed_system.sql 内建值仅非生产占位，
# pg auth.jwt_sign 签发、后端校验、网关验签三者必须同值，.env 为唯一密钥源
if [ -n "${FMS_JWT_SECRET:-}" ]; then
    JWT_ESCAPED=$(printf '%s' "${FMS_JWT_SECRET}" | sed "s/'/''/g")
    if psql -v ON_ERROR_STOP=1 -U postgres -d fms \
        -c "INSERT INTO sec.app_secrets (code, value, updated_at) VALUES ('jwt.secret', jsonb_build_object('v', '${JWT_ESCAPED}'), now()) ON CONFLICT (code) DO UPDATE SET value = EXCLUDED.value, updated_at = now()" \
        > /tmp/fms_init_jwt.log 2>&1; then
        echo "[fms-init] PASS: sec.app_secrets.jwt.secret 已对齐 .env 值"
    else
        echo "[fms-init] FAIL: jwt.secret 对齐失败（详见 /tmp/fms_init_jwt.log）"
        FAIL_COUNT=$((FAIL_COUNT+1))
        ABORT=1
    fi
else
    echo "[fms-init] WARN: 环境变量 FMS_JWT_SECRET 为空，跳过 jwt.secret 对齐（PG 侧将用 init/seed_system.sql 内建值）"
fi

# replication 角色与 pg_hba 授权，从库 pg_basebackup 前置条件
# 创建流复制角色 replicator，LOGIN + REPLICATION，密码对齐 .env 的
# FMS_REPL_PASSWORD，在 pg_hba.conf 追加复制授权行并登记 wal_level，
# 为主从部署中从库执行 pg_basebackup 提供前置条件。
# 幂等：角色 IF NOT EXISTS 创建，pg_hba 行 grep 判重后追加，ALTER SYSTEM
# 覆写同名项，重启容器或重复初始化均不报错。
# initdb.d 阶段 pgbouncer 尚不存在，本段只依赖主库自身，pg_hba.conf
# 由主服务器启动时读取，initdb 阶段追加即生效，无需 reload。
# max_wal_senders / max_replication_slots 默认 10 足够单从库场景，
# 多从库时按需增加。

# replicator 角色，幂等，IF NOT EXISTS 创建，heredoc 免引号转义
if psql -v ON_ERROR_STOP=1 -U postgres -d postgres > /tmp/fms_init_repl_role.log 2>&1 <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator LOGIN REPLICATION;
    END IF;
END $$;
SQL
then
    echo "[fms-init] PASS: replicator 角色已就绪"
else
    echo "[fms-init] FAIL: replicator 角色创建失败（详见 /tmp/fms_init_repl_role.log）"
    FAIL_COUNT=$((FAIL_COUNT+1))
    ABORT=1
fi

# 密码对齐 .env 的 FMS_REPL_PASSWORD，转义同 fms_app 段，单引号转 ''
if [ -n "${FMS_REPL_PASSWORD:-}" ]; then
    REPL_ESCAPED=$(printf '%s' "${FMS_REPL_PASSWORD}" | sed "s/'/''/g")
    if psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
        -c "ALTER ROLE replicator WITH PASSWORD '${REPL_ESCAPED}'" > /tmp/fms_init_repl_pw.log 2>&1; then
        echo "[fms-init] PASS: ALTER ROLE replicator 密码已对齐 .env 值"
    else
        echo "[fms-init] FAIL: ALTER ROLE replicator 失败（详见 /tmp/fms_init_repl_pw.log）"
        FAIL_COUNT=$((FAIL_COUNT+1))
        ABORT=1
    fi
else
    echo "[fms-init] WARN: 环境变量 FMS_REPL_PASSWORD 为空，跳过 replicator 密码对齐（不使用主从时忽略）"
fi

# pg_hba.conf 追加复制授权行，grep 判重后追加，$PGDATA 已由官方
# entrypoint 导出，PG18 数据目录 /var/lib/postgresql/18/docker
REPL_HBA_LINE="host replication replicator 0.0.0.0/0 scram-sha-256"
if [ -f "$PGDATA/pg_hba.conf" ] && grep -qF "$REPL_HBA_LINE" "$PGDATA/pg_hba.conf"; then
    echo "[fms-init] SKIP: pg_hba.conf 已存在复制授权行（幂等）"
else
    echo "$REPL_HBA_LINE" >> "$PGDATA/pg_hba.conf"
    echo "[fms-init] PASS: 已追加复制授权行（$PGDATA/pg_hba.conf）"
fi

# wal_level 显式登记，PG18 默认即 replica，ALTER SYSTEM 写
# postgresql.auto.conf，覆写同名项天然幂等
if psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
    -c "ALTER SYSTEM SET wal_level = 'replica'" > /tmp/fms_init_wal.log 2>&1; then
    echo "[fms-init] PASS: wal_level=replica 已登记（PG18 默认值）"
else
    echo "[fms-init] WARN: ALTER SYSTEM wal_level 失败（详见 /tmp/fms_init_wal.log，PG18 默认 replica 可忽略）"
fi


echo "[fms-init] 完成摘要：成功 $OK_COUNT / 失败 $FAIL_COUNT / 跳过 $SKIP_COUNT"

# 任一核心文件失败即非零退出，容器启动失败，问题暴露于 docker compose ps/logs
[ "$ABORT" -eq 1 ] && exit 1
exit 0
