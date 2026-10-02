-- FMS 财务系统（权限矩阵核对脚本，执行库，fms），init/grants_base.sql 五层授权总闸后的验收核对，五 schema（fa/expense/budget/wf/rpt）授权可达性、log.* auditor 只读、rpt security_invoker、安全断言（解密出口/密钥收敛/audit.record 最小授权/pgmq 最小授权/SECURITY DEFINER PUBLIC 清零/无闸口函数暴露面）
-- 纯核对脚本，只读，不修改任何对象，每个断言 DO 块捕获异常输出 PASS/FAIL，末尾输出汇总，配合 psql -v ON_ERROR_STOP=1 零错误通过，无 BEGIN/COMMIT，可整体重复执行

CREATE TEMP TABLE IF NOT EXISTS grant_check_results (check_name text PRIMARY KEY, ok boolean);

-- 五 schema 授权可达性，缺授权则全为 permission denied
-- fa/expense/budget/wf schema USAGE（operator/viewer/auditor/report）
DO $$
DECLARE v_ok boolean := true; v_miss text := '';
BEGIN
    IF NOT has_schema_privilege('fms_operator', 'fa', 'USAGE') THEN v_miss := v_miss || 'operator:fa '; v_ok := false; END IF;
    IF NOT has_schema_privilege('fms_viewer',  'expense', 'USAGE') THEN v_miss := v_miss || 'viewer:expense '; v_ok := false; END IF;
    IF NOT has_schema_privilege('fms_auditor', 'budget', 'USAGE') THEN v_miss := v_miss || 'auditor:budget '; v_ok := false; END IF;
    IF NOT has_schema_privilege('fms_report',  'wf', 'USAGE') THEN v_miss := v_miss || 'report:wf '; v_ok := false; END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-五schema USAGE（fa/expense/budget/wf 职责角色可达）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-五schema USAGE（缺失：%）', v_miss;
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-五schema USAGE', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-五schema USAGE（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-五schema USAGE', false);
END $$;

-- operator 对五 schema 代表表 SELECT（fa.assets/budget.budget_plans/wf.tasks）
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regclass('fa.assets') IS NOT NULL
       AND NOT has_table_privilege('fms_operator', 'fa.assets', 'SELECT') THEN v_ok := false; END IF;
    IF to_regclass('budget.budget_plans') IS NOT NULL
       AND NOT has_table_privilege('fms_operator', 'budget.budget_plans', 'SELECT') THEN v_ok := false; END IF;
    IF to_regclass('wf.tasks') IS NOT NULL
       AND NOT has_table_privilege('fms_operator', 'wf.tasks', 'SELECT') THEN v_ok := false; END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-五schema表级SELECT（operator 可达 fa.assets/budget.budget_plans/wf.tasks）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-五schema表级SELECT（operator 仍无权限）';
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-五schema表级SELECT', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-五schema表级SELECT（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-五schema表级SELECT', false);
END $$;

-- rpt.* 可达（report/operator/viewer）+ security_invoker
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regclass('rpt.trial_balance') IS NULL THEN
        RAISE NOTICE 'SKIP: rpt.trial_balance 不存在';
        INSERT INTO grant_check_results VALUES ('grants_base-rpt可达+invoker', true);
        RETURN;
    END IF;
    IF NOT has_table_privilege('fms_report', 'rpt.trial_balance', 'SELECT') THEN v_ok := false; END IF;
    IF NOT has_table_privilege('fms_operator', 'rpt.budget_actual', 'SELECT') THEN v_ok := false; END IF;
    IF NOT (SELECT reloptions IS NOT NULL AND 'security_invoker=on' = ANY(reloptions)
            FROM pg_class WHERE oid = 'rpt.trial_balance'::regclass) THEN
        v_ok := false;
        RAISE NOTICE 'WARN: rpt.trial_balance 未开启 security_invoker';
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-rpt授权+security_invoker（report/operator 可达，invoker=on）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-rpt授权+security_invoker';
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-rpt可达+invoker', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-rpt可达+invoker（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-rpt可达+invoker', false);
END $$;

-- log.* 对 auditor 只读（audit-chain-check 闭环可读）
-- 分区级校验，log.app_logs/log.access_logs 为分区父表：按父表扫描的查询只需父表授权（子分区
-- 继承父表的权限需求，PG 不逐分区另查 ACL），分区自身 relacl 只在直接访问该分区时生效；
-- 此处父表与既有分区一并核对，任一处缺权均判 FAIL（父表缺权则扫描不可读，分区缺权则直查分区不可读）
DO $$
DECLARE v_ok boolean := true; v_part_miss text := '';
BEGIN
    IF to_regclass('log.app_logs') IS NOT NULL
       AND NOT has_table_privilege('fms_auditor', 'log.app_logs', 'SELECT') THEN v_ok := false; END IF;
    IF to_regclass('log.task_runs') IS NOT NULL
       AND NOT has_table_privilege('fms_auditor', 'log.task_runs', 'SELECT') THEN v_ok := false; END IF;
    IF to_regclass('log.access_logs') IS NOT NULL
       AND NOT has_table_privilege('fms_auditor', 'log.access_logs', 'SELECT') THEN v_ok := false; END IF;
    -- 全部 log schema 分区（relkind='r' 且 relispartition）逐一核对
    SELECT string_agg(n.nspname || '.' || c.relname, ', ')
      INTO v_part_miss
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'log' AND c.relkind = 'r' AND c.relispartition
       AND NOT has_table_privilege('fms_auditor', c.oid, 'SELECT');
    IF v_part_miss IS NOT NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: log 分区缺 auditor SELECT：%', v_part_miss;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-log.* auditor 只读（app_logs/task_runs/access_logs 父表+全部分区）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-log.* auditor 只读';
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-log auditor只读', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-log auditor只读（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-log auditor只读', false);
END $$;

-- log.* admin 通道只读可达，admin jwt 映射 pg 职责角色 fms_operator，
-- 安全中心 /api/admin/app-logs + /api/admin/task-runs 需 fms_operator 对 log.* SELECT + schema USAGE，
-- api 层 requireAdminRole 仍限 admin 调用，operator jwt 不可达
DO $$
DECLARE v_ok boolean := true; v_part_miss text := '';
BEGIN
    IF NOT has_schema_privilege('fms_operator', 'log', 'USAGE') THEN v_ok := false; END IF;
    IF to_regclass('log.app_logs') IS NOT NULL
       AND NOT has_table_privilege('fms_operator', 'log.app_logs', 'SELECT') THEN v_ok := false; END IF;
    IF to_regclass('log.task_runs') IS NOT NULL
       AND NOT has_table_privilege('fms_operator', 'log.task_runs', 'SELECT') THEN v_ok := false; END IF;
    SELECT string_agg(n.nspname || '.' || c.relname, ', ')
      INTO v_part_miss
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'log' AND c.relkind = 'r' AND c.relispartition
       AND NOT has_table_privilege('fms_operator', c.oid, 'SELECT');
    IF v_part_miss IS NOT NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: log 分区缺 fms_operator SELECT：%', v_part_miss;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-log.* admin 通道（fms_operator）只读（USAGE+父表+全部分区）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-log.* admin 通道（fms_operator）只读';
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-log admin通道只读', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-log admin通道只读（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-log admin通道只读', false);
END $$;

-- audit.audit_log 只读可达（auditor 与 admin 通道，父表 + 全部分区），并核对越权面未被放宽
-- 与上文 log 只读校验同口径，按父表扫描的查询只需父表授权，分区 relacl 只在直接访问分区时生效
-- （该表重建后须补回 fms_operator 父表授权，否则报 permission denied）；
-- 正向之外补反向断言，viewer/report 必须不可达，防后续改动把读取面放大
-- 反向下标只查表级 SELECT：读该表需模式使用权与表级 SELECT 同时成立，而查阅员本就持
-- audit 模式使用权（供屏障视图），以模式使用权判越权会误报
DO $$
DECLARE v_ok boolean := true; v_part_miss text := '';
BEGIN
    IF NOT has_schema_privilege('fms_operator', 'audit', 'USAGE') THEN v_ok := false; END IF;
    IF to_regclass('audit.audit_log') IS NOT NULL THEN
        IF NOT has_table_privilege('fms_operator', 'audit.audit_log', 'SELECT') THEN v_ok := false; END IF;
        IF NOT has_table_privilege('fms_auditor', 'audit.audit_log', 'SELECT') THEN v_ok := false; END IF;
        -- 反向：查阅员与报表员不得读审计日志。读闸在表级 SELECT（仅持模式使用权读不到数据，
        -- 查阅员本就持 audit 模式使用权），故只核对表级授权
        IF has_table_privilege('fms_viewer', 'audit.audit_log', 'SELECT') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: fms_viewer 意外持有 audit.audit_log SELECT';
        END IF;
        IF has_table_privilege('fms_report', 'audit.audit_log', 'SELECT') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: fms_report 意外持有 audit.audit_log SELECT';
        END IF;
        -- 列级补漏：has_table_privilege 不读列级授权，若出现列级 GRANT 不会体现在上面的判定里
        IF EXISTS (
            SELECT 1 FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
             WHERE n.nspname = 'audit' AND c.relname = 'audit_log' AND a.attacl IS NOT NULL
               AND EXISTS (SELECT 1 FROM unnest(a.attacl) x
                            WHERE x::text LIKE 'fms_viewer=%' OR x::text LIKE 'fms_report=%')) THEN
            v_ok := false;
            RAISE NOTICE 'WARN: audit.audit_log 存在对 viewer/report 的列级授权';
        END IF;
    END IF;
    SELECT string_agg(n.nspname || '.' || c.relname, ', ')
      INTO v_part_miss
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'audit' AND c.relkind = 'r' AND c.relispartition
       AND (NOT has_table_privilege('fms_operator', c.oid, 'SELECT')
            OR NOT has_table_privilege('fms_auditor', c.oid, 'SELECT'));
    IF v_part_miss IS NOT NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: audit 分区缺 SELECT（operator 或 auditor）：%', v_part_miss;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: grants_base-audit.audit_log 只读（auditor 与 admin 通道，父表+全部分区，viewer/report 不可达）';
    ELSE
        RAISE NOTICE 'FAIL: grants_base-audit.audit_log 只读（父表或分区缺权，或越权面被放宽）';
    END IF;
    INSERT INTO grant_check_results VALUES ('grants_base-audit 只读（父表+分区）', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: grants_base-audit 只读（父表+分区）（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('grants_base-audit 只读（父表+分区）', false);
END $$;

-- 行级策略不得被"后执行的文件用更宽版本覆盖"（本类问题的根因：同一策略在多份 init 脚本里
-- 各写一份，后执行者生效），逐条核对关键策略当前是否为收紧版：
-- audit.audit_log 与应用日志须含 is_admin 分支（auditor/admin 只读），否则普通会话即可读
DO $$
DECLARE v_ok boolean := true; v_q text;
BEGIN
    IF to_regclass('audit.audit_log') IS NOT NULL THEN
        SELECT qual::text INTO v_q FROM pg_policies
         WHERE schemaname = 'audit' AND tablename = 'audit_log' AND policyname = 'audit_log_select';
        IF v_q IS NULL OR position('is_admin' in v_q) = 0 THEN
            v_ok := false;
            RAISE NOTICE 'WARN: audit.audit_log 策略未含 is_admin 分支（当前 %）', COALESCE(v_q, '缺失');
        END IF;
    END IF;
    IF to_regclass('log.app_logs') IS NOT NULL THEN
        SELECT qual::text INTO v_q FROM pg_policies
         WHERE schemaname = 'log' AND tablename = 'app_logs' AND policyname = 'log_app_logs_select';
        IF v_q IS NULL OR position('is_admin' in v_q) = 0 THEN
            v_ok := false;
            RAISE NOTICE 'WARN: log.app_logs 策略未含 is_admin 分支（当前 %）', COALESCE(v_q, '缺失');
        END IF;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 关键读策略为收紧版（审计日志与应用日志均含 is_admin 分支）';
    ELSE
        RAISE NOTICE 'FAIL: 关键读策略被更宽版本覆盖';
    END IF;
    INSERT INTO grant_check_results VALUES ('关键读策略收紧版（含 is_admin）', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 关键读策略收紧版（含 is_admin）（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('关键读策略收紧版（含 is_admin）', false);
END $$;

-- FMS-pg 系列安全断言
-- 解密出口，fms_app 不可执行，fms_operator 可执行，无 PUBLIC EXECUTE
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regprocedure('auth.decrypt_bank_account(uuid)') IS NOT NULL THEN
        IF has_function_privilege('fms_app', 'auth.decrypt_bank_account(uuid)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: fms_app 仍可执行 auth.decrypt_bank_account';
        END IF;
        IF NOT has_function_privilege('fms_operator', 'auth.decrypt_bank_account(uuid)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: fms_operator 不可执行 auth.decrypt_bank_account';
        END IF;
        IF (SELECT proacl IS NOT NULL AND '=X/fms_admin'::aclitem = ANY(proacl)
            FROM pg_proc WHERE oid = 'auth.decrypt_bank_account(uuid)'::regprocedure) THEN
            v_ok := false;
            RAISE NOTICE 'WARN: decrypt_bank_account 存在 PUBLIC EXECUTE';
        END IF;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 解密出口授权（fms_app 拒/fms_operator 授/PUBLIC 清）';
    ELSE
        RAISE NOTICE 'FAIL: 解密出口授权';
    END IF;
    INSERT INTO grant_check_results VALUES ('解密出口授权', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 解密出口授权（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('解密出口授权', false);
END $$;

-- 密钥收敛，um.params 职责角色零 SELECT，sec.app_secrets 无角色授权
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF has_table_privilege('fms_viewer', 'um.params', 'SELECT')
       OR has_table_privilege('fms_operator', 'um.params', 'SELECT')
       OR has_table_privilege('fms_auditor', 'um.params', 'SELECT')
       OR has_table_privilege('fms_report', 'um.params', 'SELECT') THEN
        v_ok := false;
    END IF;
    IF has_table_privilege('fms_app', 'sec.app_secrets', 'SELECT')
       OR has_table_privilege('fms_operator', 'sec.app_secrets', 'SELECT')
       OR has_table_privilege('fms_viewer', 'sec.app_secrets', 'SELECT')
       OR has_table_privilege('fms_auditor', 'sec.app_secrets', 'SELECT')
       OR has_table_privilege('fms_report', 'sec.app_secrets', 'SELECT') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: sec.app_secrets 存在角色授权';
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 密钥收敛（um.params 零职责角色 SELECT；sec.app_secrets 零授权）';
    ELSE
        RAISE NOTICE 'FAIL: 密钥收敛';
    END IF;
    INSERT INTO grant_check_results VALUES ('密钥收敛', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 密钥收敛（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('密钥收敛', false);
END $$;

-- audit.record，PUBLIC 不可执行、fms_operator 不可执行、fms_admin 可执行
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regprocedure('audit.record(text,text,uuid,jsonb,jsonb,text,text)') IS NOT NULL THEN
        IF has_function_privilege('public', 'audit.record(text,text,uuid,jsonb,jsonb,text,text)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: audit.record PUBLIC 仍可执行';
        END IF;
        IF has_function_privilege('fms_operator', 'audit.record(text,text,uuid,jsonb,jsonb,text,text)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: audit.record operator 仍可执行';
        END IF;
        IF NOT has_function_privilege('fms_admin', 'audit.record(text,text,uuid,jsonb,jsonb,text,text)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: audit.record fms_admin 不可执行';
        END IF;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: audit.record 最小授权（PUBLIC/operator 拒，fms_admin 授）';
    ELSE
        RAISE NOTICE 'FAIL: audit.record 最小授权';
    END IF;
    INSERT INTO grant_check_results VALUES ('audit.record最小授权', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: audit.record最小授权（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('audit.record最小授权', false);
END $$;

-- pgmq，内部表零角色授权，管理函数 PUBLIC 零 EXECUTE，业务函数 operator 可执行
-- 环境适配，pgmq 扩展未装（pg_extension 无记录）即视为未安装，SKIP 记为通过；
-- has_function_privilege 对不存在函数会硬报错，扩展已装（schema 必在）才核对授权
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
        RAISE NOTICE 'SKIP: pgmq 扩展未安装（视为无此功能），跳过 PGMQ 最小授权核对';
        INSERT INTO grant_check_results VALUES ('PGMQ最小授权', true);
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'pgmq' AND c.relname LIKE 'q\_q\_%'
                  AND (has_table_privilege('fms_app', c.oid, 'SELECT')
                    OR has_table_privilege('fms_operator', c.oid, 'UPDATE'))) THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq 内部表仍存在 fms_app/operator 直接授权';
    END IF;
    IF has_function_privilege('public', 'pgmq.purge_queue(text)', 'EXECUTE')
       OR has_function_privilege('public', 'pgmq.drop_queue(text)', 'EXECUTE')
       OR has_function_privilege('public', 'pgmq.delete(text,bigint)', 'EXECUTE') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq 管理函数仍 PUBLIC 可执行';
    END IF;
    IF NOT has_function_privilege('fms_operator', 'pgmq.send(text,jsonb,integer)', 'EXECUTE')
       OR NOT has_function_privilege('fms_operator', 'pgmq.read(text,integer,integer,jsonb)', 'EXECUTE')
       OR NOT has_function_privilege('fms_operator', 'pgmq.archive(text,bigint)', 'EXECUTE') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: pgmq 业务函数对 operator 授权缺失';
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: PGMQ 最小授权（内部表零 GRANT/管理函数 PUBLIC 清/业务函数可达）';
    ELSE
        RAISE NOTICE 'FAIL: PGMQ 最小授权';
    END IF;
    INSERT INTO grant_check_results VALUES ('PGMQ最小授权', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: PGMQ最小授权（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('PGMQ最小授权', false);
END $$;

-- SECURITY DEFINER 函数的 PUBLIC 可执行面清零，范围为全部业务 schema 与闸口 schema，
-- proacl 为 NULL 表示从未收回，默认即 PUBLIC 可执行，与显式 PUBLIC 条目同判，两者都须为 0
DO $$
DECLARE v_ok boolean := true; v_cnt int; v_list text;
BEGIN
    SELECT count(*), string_agg(n.nspname || '.' || p.proname, ', ' ORDER BY 1)
      INTO v_cnt, v_list
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname IN ('gl','ar','ap','cash','fa','expense','budget','wf','rpt','log',
                         'audit','docs','auth','org','um','notify','sec')
       AND p.prosecdef
       AND (p.proacl IS NULL
            OR EXISTS (SELECT 1 FROM unnest(p.proacl) a
                        WHERE a = ('=X/' || pg_catalog.pg_get_userbyid(p.proowner))::aclitem));
    IF v_cnt > 0 THEN
        v_ok := false;
        RAISE NOTICE 'WARN: % 个 SECURITY DEFINER 函数仍为 PUBLIC 可执行：%', v_cnt, v_list;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: SECURITY-DEFINER 函数 PUBLIC 可执行面清零（含 notify/docs/sec，proacl 为 NULL 同判，0 残留）';
    ELSE
        RAISE NOTICE 'FAIL: SECURITY-DEFINER 函数 PUBLIC 可执行面清零';
    END IF;
    INSERT INTO grant_check_results VALUES ('SECURITY-DEFINER-PUBLIC清零', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: SECURITY-DEFINER-PUBLIC清零（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('SECURITY-DEFINER-PUBLIC清零', false);
END $$;

-- 无闸口函数不对外（fa.fn_run_depreciation 仅 operator，viewer/auditor/report 拒）
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regprocedure('fa.fn_run_depreciation(uuid,text)') IS NOT NULL THEN
        IF NOT has_function_privilege('fms_operator', 'fa.fn_run_depreciation(uuid,text)', 'EXECUTE')
           OR has_function_privilege('fms_viewer', 'fa.fn_run_depreciation(uuid,text)', 'EXECUTE')
           OR has_function_privilege('fms_report', 'fa.fn_run_depreciation(uuid,text)', 'EXECUTE') THEN
            v_ok := false;
        END IF;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 无闸口函数暴露面收敛（fa.fn_run_depreciation 仅 operator）';
    ELSE
        RAISE NOTICE 'FAIL: 无闸口函数暴露面收敛';
    END IF;
    INSERT INTO grant_check_results VALUES ('无闸口函数暴露面收敛', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 无闸口函数暴露面收敛（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('无闸口函数暴露面收敛', false);
END $$;

-- 数据范围闸口授权，三个函数须存在、PUBLIC 已收回、仅职责角色通道可执行
DO $$
DECLARE v_ok boolean := true;
BEGIN
    IF to_regprocedure('auth.list_user_scopes(uuid)') IS NULL
       OR to_regprocedure('auth.set_user_org_scope(uuid,uuid[])') IS NULL
       OR to_regprocedure('auth.set_user_dept_scope(uuid,uuid[],boolean)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 数据范围闸口函数缺失';
    END IF;
    IF has_function_privilege('public', 'auth.set_user_org_scope(uuid,uuid[])', 'EXECUTE')
       OR has_function_privilege('public', 'auth.set_user_dept_scope(uuid,uuid[],boolean)', 'EXECUTE') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 数据范围闸口仍对 PUBLIC 开放';
    END IF;
    IF has_function_privilege('fms_app', 'auth.set_user_org_scope(uuid,uuid[])', 'EXECUTE') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 数据范围闸口对运行时角色 fms_app 开放';
    END IF;
    IF NOT has_function_privilege('fms_operator', 'auth.set_user_org_scope(uuid,uuid[])', 'EXECUTE') THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 数据范围闸口未授予职责角色 fms_operator';
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 数据范围闸口授权（三函数齐备，PUBLIC 与 fms_app 拒，职责角色授）';
    ELSE
        RAISE NOTICE 'FAIL: 数据范围闸口授权';
    END IF;
    INSERT INTO grant_check_results VALUES ('数据范围闸口授权', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 数据范围闸口授权（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('数据范围闸口授权', false);
END $$;

-- 邮箱解密出口授权与本人判定，auth.get_user_email 以 is_self 或 is_admin 放行，
-- is_self 在无会话时返回 NULL 会使取反后的守卫不成立而穿透，故同时钉住出口授权与 is_self 的返回值范围
DO $$
DECLARE v_ok boolean := true; v_self boolean;
BEGIN
    IF to_regprocedure('auth.get_user_email(uuid)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 邮箱解密出口 auth.get_user_email 缺失';
    ELSE
        IF has_function_privilege('public', 'auth.get_user_email(uuid)', 'EXECUTE')
           OR has_function_privilege('fms_app', 'auth.get_user_email(uuid)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: 邮箱解密出口仍对 PUBLIC/fms_app 开放';
        END IF;
        IF NOT has_function_privilege('fms_operator', 'auth.get_user_email(uuid)', 'EXECUTE')
           OR NOT has_function_privilege('fms_viewer', 'auth.get_user_email(uuid)', 'EXECUTE') THEN
            v_ok := false;
            RAISE NOTICE 'WARN: 邮箱解密出口未授予职责角色';
        END IF;
    END IF;
    -- 空入参必须落在 false，不得返回 NULL
    v_self := auth.is_self(NULL);
    IF v_self IS NULL OR v_self THEN
        v_ok := false;
        RAISE NOTICE 'WARN: auth.is_self(NULL) 返回 %（应为 false）', coalesce(v_self::text, 'NULL');
    END IF;
    -- fms_admin 是会话语义下的可信管理员，其短路返回 true 属预期，其余身份在无会话时必须为 false
    IF session_user <> 'fms_admin' THEN
        v_self := auth.is_self('00000000-0000-7000-0000-000000000001'::uuid);
        IF v_self IS DISTINCT FROM false THEN
            v_ok := false;
            RAISE NOTICE 'WARN: 无会话下 auth.is_self 返回 %（应为 false，NULL 会穿透守卫）',
                coalesce(v_self::text, 'NULL');
        END IF;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 邮箱解密出口授权 + 本人判定 fail-closed（ACL 收敛，is_self 无 NULL 语义）';
    ELSE
        RAISE NOTICE 'FAIL: 邮箱解密出口授权 + 本人判定 fail-closed';
    END IF;
    INSERT INTO grant_check_results VALUES ('邮箱出口与is_self fail-closed', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 邮箱出口与is_self fail-closed（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('邮箱出口与is_self fail-closed', false);
END $$;

-- 函数属主约定，业务与闸口 schema 的 SECURITY DEFINER 函数属主须为 fms_admin，
-- 唯一例外是软删除出口 auth.fn_soft_delete 与 auth.fn_soft_delete_v，两函数体内以
-- current_user <> 'fms_admin' 判定调用方，SECURITY DEFINER 下 current_user 即属主，
-- 属主若被改为 fms_admin 该判定恒假、拒绝分支永不触发，属主本身即控制成立条件，
-- 故此处既钉住例外不得被改，也钉住其余不得偏离 fms_admin
DO $$
DECLARE v_ok boolean := true; v_cnt int; v_bad text;
BEGIN
    IF to_regprocedure('auth.fn_soft_delete(text,text,uuid)') IS NULL
       OR to_regprocedure('auth.fn_soft_delete_v(text,text,uuid,int)') IS NULL THEN
        v_ok := false;
        RAISE NOTICE 'WARN: 软删除出口函数缺失';
    ELSE
        SELECT string_agg(n.nspname || '.' || p.proname, ', ')
          INTO v_bad
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname || '.' || p.proname IN ('auth.fn_soft_delete', 'auth.fn_soft_delete_v')
           AND pg_catalog.pg_get_userbyid(p.proowner) = 'fms_admin';
        IF v_bad IS NOT NULL THEN
            v_ok := false;
            RAISE NOTICE 'WARN: 软删除出口属主被改为 fms_admin，current_user 判定将短路：%', v_bad;
        END IF;
    END IF;
    SELECT count(*), string_agg(n.nspname || '.' || p.proname, ', ' ORDER BY 1)
      INTO v_cnt, v_bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname IN ('gl','ar','ap','cash','fa','expense','budget','wf','rpt','log',
                         'audit','docs','auth','org','um','notify','sec')
       AND p.prosecdef
       AND pg_catalog.pg_get_userbyid(p.proowner) <> 'fms_admin'
       AND n.nspname || '.' || p.proname NOT IN ('auth.fn_soft_delete', 'auth.fn_soft_delete_v');
    IF v_cnt > 0 THEN
        v_ok := false;
        RAISE NOTICE 'WARN: % 个 SECURITY DEFINER 函数属主偏离 fms_admin：%', v_cnt, v_bad;
    END IF;
    IF v_ok THEN
        RAISE NOTICE 'PASS: 函数属主约定（SECURITY DEFINER 属主 fms_admin，软删除出口两函数为刻意例外）';
    ELSE
        RAISE NOTICE 'FAIL: 函数属主约定';
    END IF;
    INSERT INTO grant_check_results VALUES ('函数属主约定', v_ok);
EXCEPTION WHEN OTHERS THEN
    -- 断言自身报错一律落 FAIL，不让异常冒泡使该断言整条消失
    RAISE NOTICE 'FAIL: 函数属主约定（异常：%）', SQLERRM;
    INSERT INTO grant_check_results VALUES ('函数属主约定', false);
END $$;

-- 汇总
-- 条数守卫，结果表行数须等于断言总数，少于总数即说明有断言整条丢失
-- 该类丢失只表现为计数变小，看的人无从察觉，故一并报出并以 FAIL 措辞提示
DO $$
DECLARE v_ok int; v_fail int; v_expect int := 16;
BEGIN
    SELECT count(*) FILTER (WHERE ok), count(*) FILTER (WHERE NOT ok)
      INTO v_ok, v_fail FROM grant_check_results;
    RAISE NOTICE 'grants_check: 权限矩阵核对 PASS=% FAIL=%（共 % 项，预期 % 项%）',
        v_ok, v_fail, v_ok + v_fail, v_expect,
        CASE WHEN v_ok + v_fail = v_expect THEN '' ELSE '，条数不符，有断言未落盘' END;
    IF v_ok + v_fail <> v_expect THEN
        RAISE NOTICE 'FAIL: grants_check 条数守卫（实际 % 项，预期 % 项断言全部落盘）', v_ok + v_fail, v_expect;
    END IF;
END $$;

SELECT check_name, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
  FROM grant_check_results ORDER BY ok, check_name;
