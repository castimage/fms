-- FMS 财务系统（Apply 阶段五层授权总闸，执行库，fms），① fa/expense/budget/wf/rpt 五 schema USAGE + 表级授权总闸 ② log.* 对 fms_auditor/fms_operator SELECT 补齐（audit-chain-check 闭环与 admin 会话可读）③ SECURITY DEFINER 业务函数 EXECUTE 精确矩阵（REVOKE PUBLIC 兜底）④ um.params/sec.app_secrets/pgmq 最小授权，密钥收敛⑤ rpt 视图 SELECT 授权（security_invoker=on 由 init/rpt_log_base.sql 设定后生效）⑥ audit/log 管理员授权与 rls（admin 会话 = fms_operator + auth.is_admin 判定）

-- 无 BEGIN/COMMIT，全部 GRANT/REVOKE 幂等，重复执行无副作用，依赖 init/um_org_base.sql-init/queues_cron.sql已部署，表/函数已存在

SET ROLE fms_admin;

-- schema USAGE，权限矩阵，业务 schema operator/viewer/auditor/report 均授，fms_app 仅保留 auth/pgmq/log，沙箱，经 SET ROLE 注入后由职责角色访问，自身零业务 schema

-- 语义，沙箱模型下 fms_app 直连仅 CONNECT + 闸口函数可调，业务数据访问必须经 SET ROLE 注入职责角色，log 对 fms_app 保留 USAGE 供消费函数直连调用，对 fms_auditor 补齐，init/rpt_log_base.sql文件漏授，fa/expense/budget/wf/rpt 从 fms_app 收回，沙箱最小化

-- 授权模型要点，USAGE 是访问第一道闸，无 USAGE 则表级授权不可达，本文件把"五 schema 漏授"系统性补齐为集中总闸，避免各文件内联授权漂移，职责角色的 USAGE 统一授予（operator/viewer/auditor/report 四角色对齐）
GRANT USAGE ON SCHEMA fa, expense, budget, wf
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT USAGE ON SCHEMA rpt
    TO fms_operator, fms_viewer, fms_auditor, fms_report;     -- rpt 报表权限矩阵含 auditor
GRANT USAGE ON SCHEMA log TO fms_auditor, fms_operator;         -- fms_operator，admin 会话读安全中心应用日志/任务监控
GRANT USAGE ON SCHEMA log TO fms_app;                           -- 消费函数 fms_app 直连可调
REVOKE USAGE ON SCHEMA fa, expense, budget, wf, rpt FROM fms_app;   -- 沙箱最小化
GRANT pg_read_all_stats TO fms_app;   -- 系统管理页读 pg_stat_replication 主从状态（PG15+ 非超级用户默认只见自身复制连接，该角色为标准只读监控授权，不授写权限）
-- docs schema，文件存储，授权收敛于 init/docs_base.sql 内联，docs_base 在本文件之后执行，
-- 此处重申会因 schema 未创建而失败，新库 init 实测，docs_base 已含 USAGE/REVOKE 全套

-- 表级授权（fa/expense/budget/wf，operator W / viewer·auditor·report R，init/fa_expense_budget_wf_base.sql文件设计不授 DELETE，无 DELETE 策略，物理删除仅经管理通道）
GRANT SELECT, INSERT, UPDATE ON fa.assets, fa.depreciation_runs TO fms_operator;
GRANT SELECT ON fa.assets, fa.depreciation_runs TO fms_viewer, fms_auditor, fms_report;

GRANT SELECT, INSERT, UPDATE ON expense.reimbursements, expense.reimbursement_lines TO fms_operator;
GRANT SELECT ON expense.reimbursements, expense.reimbursement_lines TO fms_viewer, fms_auditor, fms_report;

GRANT SELECT, INSERT, UPDATE ON budget.budget_plans, budget.budget_adjustments TO fms_operator;
GRANT SELECT ON budget.budget_plans, budget.budget_adjustments TO fms_viewer, fms_auditor, fms_report;

GRANT SELECT, INSERT, UPDATE ON wf.definitions, wf.instances, wf.tasks, wf.task_events TO fms_operator;
GRANT SELECT ON wf.definitions, wf.instances, wf.tasks, wf.task_events TO fms_viewer, fms_auditor, fms_report;

-- docs.documents 表级授权已收敛于 init/docs_base.sql 内联，与 schema USAGE 同理，
-- 此处不重复，docs_base 在本文件之后执行，前置引用会因表不存在而失败

-- log.*（fms_auditor 只读，audit-chain-check 每日写 task_runs 的审计闭环可读）
-- 用 ALL TABLES IN SCHEMA log 兜底，幂等，init/fill_missing.sql文件在初始化序列中位于本文件之后，
-- 会把 log.app_logs 普通表 DROP 重建为分区父表，GRANT 随 DROP 丢失，init/fill_missing.sql 已补回授权，
-- 此处以 schema 级统一授权覆盖 init/um_perm.sql先行建表（log.access_logs）等任意时序，防单项列举漂移
GRANT SELECT ON ALL TABLES IN SCHEMA log TO fms_auditor, fms_operator;   -- fms_operator，admin 会话读 /api/admin/app-logs+task-runs（api 层 requireAdminRole 闸口）

-- rpt.*（operator/viewer/auditor/report 只读，视图已 security_invoker=on，行级按调用者 guc 收敛，init/rpt_log_base.sql文件 2.6 节）
-- report 侧 gl 基表列级授权补齐，security_invoker 下视图以调用者身份访问基表，fms_report 须持 gl.vouchers/voucher_lines 非敏感列 SELECT，cost_amount 敏感列不授
GRANT SELECT (id, company_id, period, voucher_no, voucher_date, status, source_type,
              created_by, approved_by, approved_at, total_debit, total_credit,
              note, created_at, updated_at, deleted_at)
    ON gl.vouchers TO fms_report;
GRANT SELECT (id, voucher_id, line_no, account_id, summary, debit, credit, currency,
              dept_id, partner_id, company_id, created_at, updated_at, deleted_at)
    ON gl.voucher_lines TO fms_report;
-- rpt 报表视图 SELECT，矩阵含 auditor，security_invoker=on 下基表授权各文件已齐
GRANT SELECT ON rpt.budget_actual, rpt.ar_aging_paid, rpt.dept_expense, rpt.cash_flow_daily
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT ON rpt.trial_balance, rpt.income_statement, rpt.balance_sheet,
                rpt.cash_flow, rpt.ar_aging
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- SECURITY DEFINER 业务函数 EXECUTE 精确矩阵，全部业务 schema 函数 REVOKE PUBLIC，防任意角色执行无闸口函数，与精确授权矩阵一致，按角色重授（函数体内 require_permission/can_access_org 收敛行级），触发器函数不授，仅触发器以内置身份调用

-- 安全语义，SECURITY DEFINER 函数以属主 fms_admin 身份执行，绕过调用者表级权限，EXECUTE 授权即权限边界，REVOKE PUBLIC 保证未列角色不可调，函数体内显式鉴权（require_permission 权限点 + can_access_org 行级）才是最终裁决，双向防线，授权矩阵管"谁能调"，函数体鉴权管"调了能干什么"，缺一不可

-- 授权面，gl 凭证生命周期函数（post/reverse/reject/revert/close/reopen + 期末调汇 fx_adjustment）授 fms_operator，cash 日报（operator/viewer/report）与自动对账授 operator，fa 折旧 / wf 任务领取办结授 operator，audit 链校验（chain_check/audit_log_chain_check）授 fms_auditor，audit/auth 函数族 PUBLIC 收回后保留 init/um_org_base.sql文件显式角色授权不变
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA gl, ar, ap, cash, fa, expense, budget, wf, rpt, log
    FROM PUBLIC;
-- audit/auth 函数族同样收回 PUBLIC，显式角色授权保留，org/um 补齐（init/auth_org_functions.sql 的 org.fn_upsert_company 与 init/fill_missing.sql 的 auth.fn_soft_delete 均 SECURITY DEFINER，PUBLIC 清）
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA audit FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA auth FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA org FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA um FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.record(text, text, uuid, jsonb, jsonb, text, text) TO fms_admin;
GRANT EXECUTE ON FUNCTION audit.chain_check(date) TO fms_auditor;
GRANT EXECUTE ON FUNCTION audit.audit_log_chain_check() TO fms_auditor;

-- gl，凭证生命周期函数（init/gl_core_functions.sql 已授 operator，此处总闸兜底重申）
GRANT EXECUTE ON FUNCTION gl.fn_post_voucher(uuid)                     TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reverse_voucher(uuid, date, text)      TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reject_voucher(uuid, text)             TO fms_operator;
-- gl.fn_revert_voucher 定义于 init/gl_voucher_reject.sql，白名单晚于本文件，
-- 全新库在此 GRANT 必报 'function does not exist'，判存守卫，函数创建处已内联
-- 授权，本行为存量库兜底重申，pgmq 段同款模式
DO $$
BEGIN
    IF to_regprocedure('gl.fn_revert_voucher(uuid)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION gl.fn_revert_voucher(uuid) TO fms_operator';
    ELSE
        RAISE NOTICE 'gl.fn_revert_voucher 尚不存在（由 init/gl_voucher_reject.sql 创建并授权），跳过兜底重申';
    END IF;
END $$;
GRANT EXECUTE ON FUNCTION gl.fn_close_period(uuid, text, boolean)      TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reopen_period(uuid, text, text)        TO fms_operator;
-- 期末调汇（gl.fn_fx_adjustment）EXECUTE，与结账/反结账同族纳入总闸兜底重申，
-- 幂等，重复执行无副作用
GRANT EXECUTE ON FUNCTION gl.fn_fx_adjustment(uuid, text, text)        TO fms_operator;

-- cash，资金日报/自动对账
GRANT EXECUTE ON FUNCTION cash.fn_daily_report(uuid, date)
    TO fms_operator, fms_viewer, fms_report;
GRANT EXECUTE ON FUNCTION cash.fn_auto_reconcile(uuid) TO fms_operator;

-- fa，折旧计提（含 require_permission 闸口）
GRANT EXECUTE ON FUNCTION fa.fn_run_depreciation(uuid, text) TO fms_operator;

-- wf，任务领取/办结（fn_claim_task/fn_complete_task 含 assignee/权限校验）
GRANT EXECUTE ON FUNCTION wf.fn_claim_task(uuid, uuid)     TO fms_operator;
GRANT EXECUTE ON FUNCTION wf.claim_task(uuid)              TO fms_operator;
GRANT EXECUTE ON FUNCTION wf.fn_complete_task(uuid, uuid, text, text) TO fms_operator;
GRANT EXECUTE ON FUNCTION wf.complete_task(uuid, text, text)           TO fms_operator;

-- 参数/密钥最小授权，um.params 职责角色一律不授（init/um_org_base.sql 已 REVOKE，此处总闸兜底重申），sec.app_secrets 不向任何角色授权（仅 fms_admin/超级用户）
REVOKE ALL ON um.params FROM fms_operator, fms_viewer, fms_auditor, fms_report, fms_app;
REVOKE ALL ON sec.app_secrets FROM PUBLIC;

-- pgmq 最小授权（init/queues_cron.sql 已细化，此处总闸兜底重申；pgmq 未装即视为无此功能，
-- schema 缺失时 GRANT 会硬失败，故以 pg_extension 记录为准，存在才授权，否则 NOTICE 跳过）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
        EXECUTE 'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA pgmq FROM PUBLIC';
        EXECUTE 'REVOKE ALL ON ALL TABLES  IN SCHEMA pgmq FROM fms_app, fms_operator';
        EXECUTE 'REVOKE ALL ON ALL SEQUENCES IN SCHEMA pgmq FROM fms_app, fms_operator';
        EXECUTE 'GRANT USAGE ON SCHEMA pgmq TO fms_app, fms_operator';
    ELSE
        RAISE NOTICE 'pgmq 扩展未安装（视为无此功能），跳过 pgmq 最小授权';
    END IF;
END $$;
-- 业务函数批量授权（与 init/queues_cron.sql文件三节同一 DO 块模式，管理函数 purge/drop/delete 不授，仅 fms_admin）
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT p.oid, p.proname
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'pgmq'
                AND p.proname NOT IN ('purge_queue','drop_queue','delete')
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION pgmq.%I(%s) TO fms_app, fms_operator',
                       r.proname, pg_get_function_identity_arguments(r.oid));
    END LOOP;
END $$;

-- audit/log 管理员授权与 rls（admin 会话 = fms_operator + auth.is_admin 判定），
--   ① audit schema/表/链校验函数 GRANT 补 fms_operator，admin 会话所需
--   ② log schema USAGE + app_logs/task_runs SELECT 补 fms_operator，admin 模块专属
--   ③ audit.audit_log rls 策略补 OR auth.is_admin，operator 行级仍拒则 api 层 40300
--   ④ log.app_logs rls 策略收紧为 auditor/admin，仅审计/管理员可读系统应用日志
--   ⑤ assign_role/revoke_role 守卫，非本人须 is_admin 放行

-- 配套后端，AuditController 三端点加 RequestContext.requireAuditRole（auditor/admin），
-- 保证 operator 等非审计角色在 api 层即 40300。
SET ROLE fms_admin;

-- ① audit schema/表/函数授权补 fms_operator（admin 会话 = fms_operator，幂等）
GRANT USAGE ON SCHEMA audit TO fms_operator;
GRANT SELECT ON audit.audit_log TO fms_operator;             -- 分区继承（partman 已 inherit_privileges）
GRANT EXECUTE ON FUNCTION audit.audit_log_chain_check() TO fms_operator;
GRANT EXECUTE ON FUNCTION audit.chain_check(date) TO fms_operator;

-- ② log schema USAGE + 表 SELECT 补 fms_operator（/api/admin/app-logs、/api/admin/task-runs）
GRANT USAGE ON SCHEMA log TO fms_operator;
GRANT SELECT ON log.app_logs, log.task_runs TO fms_operator;

-- ③ audit.audit_log rls，补 is_admin 分支（矩阵 audit:read:all = auditor/admin）
DROP POLICY IF EXISTS audit_log_select ON audit.audit_log;
CREATE POLICY audit_log_select ON audit.audit_log
    FOR SELECT USING (auth.has_role('fms_auditor')
                      OR current_user = 'fms_admin'
                      OR auth.is_admin());

-- ④ log.app_logs rls，auditor/admin 只读
DROP POLICY IF EXISTS log_app_logs_select ON log.app_logs;
CREATE POLICY log_app_logs_select ON log.app_logs
    FOR SELECT USING (auth.has_role('fms_auditor')
                      OR current_user = 'fms_admin'
                      OR auth.is_admin());

-- ⑤ auth.assign_role / auth.revoke_role 守卫，非本人须 is_admin 放行，匿名维护会话放行
CREATE OR REPLACE FUNCTION auth.assign_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_role_id   uuid;
    v_user_comp uuid;
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权为他人分配角色' USING ERRCODE = '42501';
    END IF;
    SELECT id INTO v_role_id FROM um.roles
     WHERE code = btrim(p_role_code) AND deleted_at IS NULL;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION '角色不存在：%', p_role_code USING ERRCODE = 'P0001';
    END IF;
    SELECT company_id INTO v_user_comp FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_user_comp IS NULL THEN
        RAISE EXCEPTION '用户不存在' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO um.user_role (company_id, user_id, role_id, granted_by)
    VALUES (v_user_comp, p_user_id, v_role_id, auth.current_uid())
    ON CONFLICT (user_id, role_id) DO NOTHING;       -- 幂等重授（uk_user_role 兜底）
END
$$;
COMMENT ON FUNCTION auth.assign_role(uuid,text)
    IS '角色分配（um.user_role 写唯一入口）；非本人须系统管理员（is_admin），否则 42501';

CREATE OR REPLACE FUNCTION auth.revoke_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权回收他人角色' USING ERRCODE = '42501';
    END IF;
    UPDATE um.user_role ur
       SET deleted_at = now()
      FROM um.roles r
     WHERE ur.role_id = r.id
       AND r.code = btrim(p_role_code)
       AND ur.user_id = p_user_id
       AND ur.deleted_at IS NULL;
END
$$;
COMMENT ON FUNCTION auth.revoke_role(uuid,text)
    IS '角色回收（软删幂等，未命中静默）；非本人须系统管理员（is_admin），否则 42501';

REVOKE ALL ON FUNCTION auth.assign_role(uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.revoke_role(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.assign_role(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role(uuid,text) TO fms_operator;

RESET ROLE;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'audit_admin_perm_fix' AS file_name,
       (SELECT has_schema_privilege('fms_operator', 'audit', 'USAGE'))   AS audit_usage_op,
       (SELECT has_table_privilege('fms_operator', 'audit.audit_log', 'SELECT'))  AS audit_log_sel_op,
       (SELECT has_function_privilege('fms_operator', 'audit.audit_log_chain_check()', 'EXECUTE')) AS chain_check_exec_op,
       (SELECT has_schema_privilege('fms_operator', 'log', 'USAGE'))     AS log_usage_op,
       (SELECT has_table_privilege('fms_operator', 'log.app_logs', 'SELECT'))  AS app_logs_sel_op,
       (SELECT has_table_privilege('fms_operator', 'log.task_runs', 'SELECT')) AS task_runs_sel_op,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'audit' AND tablename = 'audit_log'
           AND policyname = 'audit_log_select'
           AND qual::text LIKE '%is_admin%')  AS audit_policy_isadmin_cnt,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'log' AND tablename = 'app_logs'
           AND policyname = 'log_app_logs_select'
           AND qual::text LIKE '%is_admin%')  AS applogs_policy_isadmin_cnt,
       (SELECT pg_get_functiondef('auth.assign_role(uuid,text)'::regprocedure)::text LIKE '%is_admin%') AS assign_role_isadmin;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'grants_base' AS file_name,
       (SELECT count(*) FROM pg_namespace
         WHERE nspname IN ('fa','expense','budget','wf','rpt','log')) AS schema_cnt,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname IN ('fa','expense','budget','wf')
           AND c.relkind = 'r'
           AND has_table_privilege('fms_operator', c.oid, 'SELECT'))  AS operator_tbl_cnt,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'log' AND c.relkind = 'r'
           AND has_table_privilege('fms_auditor', c.oid, 'SELECT'))   AS auditor_log_tbl_cnt,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'rpt'
           AND has_table_privilege('fms_report', c.oid, 'SELECT'))    AS report_rpt_obj_cnt;
