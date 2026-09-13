-- FMS 财务系统初始化（init/bootstrap_base），postgres 库建 8 角色（含 fms_pgaudit_role、fms_public）与 fms 库，\c fms 后装扩展、pgaudit/zhparser 配置、13 业务 schema，无 BEGIN/COMMIT（CREATE DATABASE 不可入事务），全幂等可重跑

-- 执行（按实际部署主机，部署入口为 deploy/init/fms_init.sh，本文件由白名单调度），
--   docker exec fms-db psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f /fms-init/init/bootstrap_base.sql

-- 角色体系，集群级对象，在 postgres 库执行，幂等 DO 块
-- 角色，fms_admin(SUPERUSER NOLOGIN) / fms_app(LOGIN 唯一运行时连接角色) / fms_operator / fms_viewer / fms_auditor / fms_report(NOLOGIN 职责角色) / fms_pgaudit_role(NOLOGIN 对象审计标记角色) / fms_public(NOLOGIN 公开读口角色)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_admin') THEN
        CREATE ROLE fms_admin WITH SUPERUSER NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_app') THEN
        -- CONNECTION LIMIT 100 为部署/容量运维参数期望值（与后端 fms.db.connection-limit 及 Hikari 池上限联动，
        -- 运维按环境调整 yaml 期望值后须重建角色生效）
        CREATE ROLE fms_app WITH LOGIN PASSWORD 'FmsApp_2026_r7Xk#9mQ2vLpZ4sD' CONNECTION LIMIT 100;
    ELSE
        -- fms_app 为唯一运行时连接角色，凭据是沙箱模型信任边界，一律以高强度随机口令重建（SCRAM-SHA-256 存储）
        ALTER ROLE fms_app WITH PASSWORD 'FmsApp_2026_r7Xk#9mQ2vLpZ4sD';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_operator') THEN
        CREATE ROLE fms_operator WITH NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_viewer') THEN
        CREATE ROLE fms_viewer WITH NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_auditor') THEN
        CREATE ROLE fms_auditor WITH NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_report') THEN
        CREATE ROLE fms_report WITH NOLOGIN;
    END IF;
    -- pgaudit 对象审计标记角色（NOLOGIN），对敏感表 GRANT SELECT 给该角色即标记为需读审计
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_pgaudit_role') THEN
        CREATE ROLE fms_pgaudit_role WITH NOLOGIN;
    END IF;
    -- fms_public，公开读口角色（init/role_fms_public.sql 授 EXECUTE fn_get_param_value/fn_public_document_meta + schema USAGE）
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_public') THEN
        CREATE ROLE fms_public WITH NOLOGIN;
    END IF;
END $$;

COMMENT ON ROLE fms_admin    IS 'FMS 初始化/运维超级角色（NOLOGIN，仅建库与授权期 SET ROLE 使用，运行时绝不使用）';
COMMENT ON ROLE fms_app      IS 'FMS 沙箱连接角色（唯一运行时连接角色：仅 CONNECT + 内部职责角色成员，无任何 schema/表权限）';
COMMENT ON ROLE fms_operator IS 'FMS 业务经办职责角色（NOLOGIN，凭证/收付款/报销等写权限，经 SET ROLE 注入）';
COMMENT ON ROLE fms_viewer   IS 'FMS 业务只读职责角色（NOLOGIN，明细级查询）';
COMMENT ON ROLE fms_auditor  IS 'FMS 审计职责角色（NOLOGIN，审计/日志只读）';
COMMENT ON ROLE fms_report   IS 'FMS 报表职责角色（NOLOGIN，汇总只读，无明细敏感列）';

-- 职责角色成员关系仅供 SET ROLE 切换（WITH INHERIT FALSE），默认 INHERIT TRUE 会让 fms_app 直连即继承 operator/auditor 全部权限、沙箱模型被破坏（ALTER ROLE ... NOINHERIT 对已有成员关系不生效）
GRANT fms_operator TO fms_app WITH INHERIT FALSE;
GRANT fms_viewer   TO fms_app WITH INHERIT FALSE;
GRANT fms_auditor  TO fms_app WITH INHERIT FALSE;
GRANT fms_report   TO fms_app WITH INHERIT FALSE;

-- 建库 fms（幂等，CREATE DATABASE 不可入事务/DO 块，故用 \gexec 执行，OWNER=fms_admin 保证库与全库对象属主一致）
SELECT 'CREATE DATABASE fms OWNER fms_admin'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'fms')\gexec

-- 切换到 fms 库并 SET ROLE fms_admin，后续全部 DDL 属主 = fms_admin
\c fms
SET ROLE fms_admin;

-- 扩展安装（全部 IF NOT EXISTS 幂等，ltree/pg_trgm/pgcrypto/pgaudit/dblink/pg_ivm/zhparser 本环境已可装，pg_cron/pg_partman/pgjwt 按下方适配段处理）
CREATE EXTENSION IF NOT EXISTS ltree;       -- 科目树/部门树 path LTREE 类型 + GIST 索引 + <@ 子树查询
CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- similarity + GIN trgm_ops，银行对账模糊匹配/检索
CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- digest('sha256') 审计哈希链 / pgp_sym_encrypt 列加密 + 盲索引 / gen_random_bytes
CREATE EXTENSION IF NOT EXISTS pgaudit;     -- 数据库审计（pgaudit.log = write,ddl,role + 对象审计，配置见本文件 pgaudit 全局审计段）
CREATE EXTENSION IF NOT EXISTS dblink;      -- 跨库核验辅助（cron.job 位于 cron.database_name=postgres 库，跨库核验经 dblink 计数）
CREATE EXTENSION IF NOT EXISTS pg_ivm;      -- 增量物化视图（rpt 4 个 immv，WITH (automatic) 自动维护）
CREATE EXTENSION IF NOT EXISTS zhparser;    -- 中文分词（TEXT SEARCH CONFIGURATION fms_zh，配置见本文件 fms_zh 段）

-- pg_cron 受 cron.database_name 约束只能装在 postgres 库（本环境 cron.database_name=postgres），故 \c postgres 安装后回 fms，init/queues_cron.sql 同样以 \c postgres + schedule_in_database 将任务注册为在 fms 库执行，安装失败仅告警不阻断
\c postgres
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_cron;
    RAISE NOTICE 'pg_cron 已安装于 cron.database_name 库（%）', current_database();
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'pg_cron 安装失败（%）：定时任务将跳过并在最终报告说明', SQLERRM;
END $$;
\c fms
SET ROLE fms_admin;

-- pg_partman 安装到专用 schema partman（init/queues_cron.sql cron 以 CALL partman.run_maintenance_proc 调用，5.x 中 run_maintenance_proc 是 PROCEDURE 必须 CALL）
CREATE SCHEMA IF NOT EXISTS partman AUTHORIZATION fms_admin;
CREATE EXTENSION IF NOT EXISTS pg_partman WITH SCHEMA partman;

-- pgjwt 安装到专用 schema pgjwt（init/auth_org_functions.sql 以 pgjwt.sign / pgjwt.verify 限定名包装 HS256 签发/验签）
CREATE SCHEMA IF NOT EXISTS pgjwt AUTHORIZATION fms_admin;
CREATE EXTENSION IF NOT EXISTS pgjwt WITH SCHEMA pgjwt;

-- pgjwt 0.2.0 安装脚本缺失 pgjwt.hmac 导致 sign/verify 运行时必失败，此处幂等补建包装函数（委托 pgcrypto.hmac，public schema）使扩展恢复可用，输出标准紧凑 base64url HS256 jwt，与 rgw 网关 jsonwebtoken 互操作
CREATE OR REPLACE FUNCTION pgjwt.hmac(message text, key text, algo text)
RETURNS bytea
LANGUAGE sql IMMUTABLE
AS $$
    SELECT public.hmac(message, key, algo)
$$;
GRANT EXECUTE ON FUNCTION pgjwt.hmac(text, text, text) TO PUBLIC;
-- 包装函数不受扩展管理（DROP EXTENSION pgjwt 后残留，升级 pgjwt 时需复查扩展脚本是否覆盖），可选加固，收回 PUBLIC EXECUTE（auth.jwt_sign/verify 为 SECURITY DEFINER，无需 PUBLIC）或改 CREATE EXTENSION pgcrypto WITH SCHEMA pgjwt

-- pgmq 幂等处理，先尝试 CREATE EXTENSION IF NOT EXISTS pgmq，无安装脚本/扩展记录，本测试库现状，pgmq schema 与函数实际可用，则直接探测 schema 与 send 函数，存在即视为可用
DO $$
DECLARE
    v_created  boolean := false;   -- 本会话是否成功执行 CREATE EXTENSION pgmq
    v_ns_cnt   bigint;
    v_send_cnt bigint;
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pgmq;
        v_created := true;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'CREATE EXTENSION pgmq 不可用（%），改为探测 pgmq schema/函数。', SQLERRM;
    END;

    SELECT count(*) INTO v_ns_cnt FROM pg_namespace WHERE nspname = 'pgmq';
    SELECT count(*) INTO v_send_cnt FROM pg_proc
       WHERE proname = 'send'
         AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'pgmq');

    IF v_ns_cnt > 0 AND v_send_cnt > 0 THEN
        RAISE NOTICE 'PGMQ 可用：pgmq schema 存在（%），pgmq.send() 函数=%（本次新装扩展=%）', v_ns_cnt, v_send_cnt, v_created;
    ELSE
        RAISE WARNING 'PGMQ 不可用：pgmq schema=%，pgmq.send()=%，队列功能将不可用', v_ns_cnt, v_send_cnt;
    END IF;
END $$;

-- pgaudit 全局审计配置（ALTER SYSTEM 需超级用户，本环境连接用户 postgres 具备，无 ALTER SYSTEM 权限可降级为会话级 SET pgaudit.log，生产由管理员配置）

-- 审计面，write,ddl,role（沙箱模型依赖 SET ROLE，角色切换/提权/授权变更必须入审计）+ 绑定参数值入日志（log_parameter，加密列写入的是密文无额外泄露）+ log_statement_once 控量 + log_catalog=off 屏蔽 psql/pgAdmin 目录查询噪声

-- 对象审计，pgaudit.role='fms_pgaudit_role' 标记敏感表（um.users/sec.app_secrets/cash.bank_accounts）读访问入审计，自建 audit.audit_log 只挂 DML 触发器、读路径零审计，对象审计补齐该缺口
ALTER SYSTEM SET pgaudit.log = 'write,ddl,role';
ALTER SYSTEM SET pgaudit.log_parameter = on;
ALTER SYSTEM SET pgaudit.log_statement_once = on;
ALTER SYSTEM SET pgaudit.log_catalog = off;
ALTER SYSTEM SET pgaudit.role = 'fms_pgaudit_role';
SELECT pg_reload_conf();

-- zhparser 中文全文检索配置 fms_zh（TEXT SEARCH CONFIGURATION 无 IF NOT EXISTS，用 DO 块判存，供 gl.voucher_lines.summary_tsv 生成列、对账摘要检索使用）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'fms_zh') THEN
        CREATE TEXT SEARCH CONFIGURATION public.fms_zh (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION public.fms_zh ADD MAPPING FOR n,v,a,i,e,l WITH simple;
        RAISE NOTICE '已创建 zhparser 全文检索配置 public.fms_zh';
    ELSE
        RAISE NOTICE 'zhparser 全文检索配置 public.fms_zh 已存在，跳过';
    END IF;
END $$;

-- 官方分词增强 guc 默认全 off，'基本户/结转/对账'等金融短词在默认单分词模式下不切分、检索召回率低，开启 multi_short/multi_zmain（ALTER DATABASE 全会话生效，summary_tsv 为 STORED 生成列，guc 变更后须 UPDATE 触发生成列重算再 REINDEX）
ALTER DATABASE fms SET zhparser.multi_short = on;
ALTER DATABASE fms SET zhparser.multi_zmain = on;

-- pg_trgm.similarity_threshold 期望值登记，默认 0.3，与后端 yaml fms.db.trgm-similarity-threshold 一致，
-- init/ar_ap_cash_base.sql 对账候选集 % 运算符相似度下界过滤依赖本 guc，yaml 为期望值单一来源，
-- 运维按环境调整后执行 ALTER DATABASE SET 生效，本语句为登记/幂等重跑
ALTER DATABASE fms SET pg_trgm.similarity_threshold = '0.3';

-- guc 默认值（init/auth_org_functions.sql auth.* 策略函数族读取，防伪造校验），fms.user_id 当前用户（空串=未注入则 current_uid 返回 NULL）、fms.org_ids/dept_ids 可见公司/部门集合，逗号分隔、fms.force_reopen 期间强制重开二次确认开关（init/gl_base.sql trg_period_guard 使用）
ALTER DATABASE fms SET fms.user_id = '';
ALTER DATABASE fms SET fms.org_ids = '';
ALTER DATABASE fms SET fms.dept_ids = '';
ALTER DATABASE fms SET fms.force_reopen = 'off';

-- guc SET 权限收敛，防直连伪造身份，fms.* 自定义 guc 仅超级用户可 SET，
-- 直连 fms_app 的会话不能自行 SET fms.user_id/org_ids/dept_ids 伪造 rls 身份，
-- pg 默认任意用户可 SET 自定义参数，应用侧注入经 auth.set_session
-- （SECURITY DEFINER，函数内 SET 按属主权限执行）不受影响，fms.force_reopen
-- 的数据库级默认值继承不受影响，fn_reopen_period 函数内 SET 亦不受限
REVOKE SET ON PARAMETER fms.user_id, fms.org_ids, fms.dept_ids,
    fms.force_reopen, fms.voucher_remark FROM PUBLIC;
GRANT SET ON PARAMETER fms.user_id, fms.org_ids, fms.dept_ids,
    fms.force_reopen, fms.voucher_remark TO fms_admin;

-- 安全加固与数据库权限，沙箱连接模型，fms_app 仅 CONNECT，无任何 schema/表权限，空壳连接角色，全部业务访问由 init/grants_base.sql 按五层矩阵统一下发，PUBLIC 收回数据库级与 public schema 的全部权限，PG15+ 默认亦如此，显式声明
REVOKE ALL ON DATABASE fms FROM PUBLIC;
GRANT CONNECT ON DATABASE fms TO fms_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- 运行时角色补 public USAGE（pg_trgm 的 similarity 函数与 % 运算符装在 public，
-- 无 USAGE 则 search_path 跳过 public，报 "operator does not exist: varchar % text"，
-- 实测教训），仅 USAGE 不授 CREATE，收紧语义不变
GRANT USAGE ON SCHEMA public TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- 13 个业务 schema（全部 AUTHORIZATION fms_admin）
CREATE SCHEMA IF NOT EXISTS um      AUTHORIZATION fms_admin;  -- 用户/rbac/MFA/登录审计
CREATE SCHEMA IF NOT EXISTS org     AUTHORIZATION fms_admin;  -- 公司/部门树(ltree)/岗位/人员
CREATE SCHEMA IF NOT EXISTS gl      AUTHORIZATION fms_admin;  -- 科目树(ltree)/凭证/分录/期间/期末
CREATE SCHEMA IF NOT EXISTS ar      AUTHORIZATION fms_admin;  -- 客户/发票/收款/核销
CREATE SCHEMA IF NOT EXISTS ap      AUTHORIZATION fms_admin;  -- 供应商/发票/付款/核销
CREATE SCHEMA IF NOT EXISTS cash    AUTHORIZATION fms_admin;  -- 银行账户(pgcrypto 加密)/流水/对账
CREATE SCHEMA IF NOT EXISTS fa      AUTHORIZATION fms_admin;  -- 固定资产/折旧
CREATE SCHEMA IF NOT EXISTS expense AUTHORIZATION fms_admin;  -- 报销单/明细
CREATE SCHEMA IF NOT EXISTS budget  AUTHORIZATION fms_admin;  -- 预算计划/调整
CREATE SCHEMA IF NOT EXISTS wf      AUTHORIZATION fms_admin;  -- 流程定义/实例/任务
CREATE SCHEMA IF NOT EXISTS rpt     AUTHORIZATION fms_admin;  -- 报表视图/immv
CREATE SCHEMA IF NOT EXISTS audit   AUTHORIZATION fms_admin;  -- 审计哈希链表/屏障视图
CREATE SCHEMA IF NOT EXISTS log     AUTHORIZATION fms_admin;  -- 应用/访问/任务日志

-- 属主约定，本文件自 \c fms 后即 SET ROLE fms_admin，其后全部扩展/配置/schema 属主均为 fms_admin，init/ 白名单其余各文件开头必须同样执行 SET ROLE fms_admin 保证全库对象属主一致
-- 全部 SECURITY DEFINER 函数属主 fms_admin，函数体以超级用户身份执行、绕过 rls，审计写入/过账/领单等由函数统一闸口，文件末尾不执行 RESET ROLE，psql 进程退出即恢复

-- 文件末尾自查，对象计数（配合 -v ON_ERROR_STOP=1 确认执行完整，pg_cron 受 cron.database_name 约束装在 postgres 库，fms 库扩展计数不含它，预期 9 项 + pgmq 仅探测函数）
SELECT 'bootstrap_base' AS file_name,
       (SELECT count(*) FROM pg_extension
         WHERE extname IN ('ltree','pg_trgm','pgcrypto','pgaudit','dblink','pg_partman',
                           'pg_ivm','pgjwt','zhparser')) AS ext_cnt,
       (SELECT count(*) FROM pg_namespace
         WHERE nspname IN ('um','org','gl','ar','ap','cash','fa','expense',
                           'budget','wf','rpt','audit','log')) AS business_schema_cnt,
       (SELECT count(*) FROM pg_roles
         WHERE rolname IN ('fms_admin','fms_app','fms_operator',
                           'fms_viewer','fms_auditor','fms_report',
                           'fms_pgaudit_role','fms_public')) AS role_cnt;
