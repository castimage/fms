-- FMS 财务系统（init/dashboard_alerts），看板-异常告警聚合，预算执行率阈值 + 银行账户余额安全线

-- GET /api/rpt/dashboard 异常告警维度。预算执行率阈值语义与
--   budget.fn_cron_budget_warning 同源（um.params budget.warn.ratio 可配，缺省 0.8），
--   银行账户余额安全线为可配参数 um.params cash.bank.safety.line，缺省 0 =
--   仅透支告警，admin 可经系统参数页调高。

-- 形态，SECURITY DEFINER 函数 rpt.fn_dashboard_alerts(p_company_id)， 函数内
-- 校验 auth.can_access_org，越权公司返回 '[]'，与看板"companyId 仅 ui 提示、

--   越权返回空集"语义一致，不泄露存在性，
-- 读 um.params 阈值，职责角色无 um.params 表级权限，故必须经 definer 函数，

-- 扫描 budget.budget_plans×rpt.budget_actual，执行率≥阈值，ratio 降序 topN，

--      与 cash.bank_accounts×cash.transactions，净额<安全线，余额升序 topN，
--      bank_accounts 密文列不向只读角色授权，亦必须经 definer，N 读 um.params
--      rpt.dashboard.alert.topn，缺省 5。
--   返回 jsonb 数组字符串，
--     [{type:'warning',kind:'budget',period,dept_name,account_name,ratio，0-100,
--       plan_amount,actual_amount,threshold，0-1}, ...]
--     [{type:'error',kind:'bank',bank_name,balance,safety_line}, ...]
--   文案拼装在 Java 侧（RptServiceImpl.parseDashboardAlerts）。

-- 可重复执行（CREATE OR REPLACE / ON CONFLICT DO NOTHING）。

SET ROLE fms_admin;

-- 看板-异常告警聚合函数（SECURITY DEFINER，内部读 um.params 阈值 + 访问 bank_accounts 密文表）

CREATE OR REPLACE FUNCTION rpt.fn_dashboard_alerts(p_company_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = rpt, budget, cash, public
AS $$
DECLARE
    v_warn_ratio numeric := 0.8;  -- 预算预警阈值（um.params budget.warn.ratio 可配，与 fn_cron_budget_warning 同源）
    v_safety     numeric := 0;    -- 银行账户余额安全线（um.params cash.bank.safety.line 可配，0=仅透支告警）
    v_topn       int := 5;        -- 每类告警展示上限（um.params rpt.dashboard.alert.topn 可配，缺省 5）
    v_param      jsonb;
    v_rows       jsonb;
BEGIN
    -- 越权公司返回空集，与看板其余维度 rls 空集语义一致，session_user 判定保留管理会话直查通道
    IF NOT auth.can_access_org(p_company_id) AND session_user NOT IN ('fms_admin', 'postgres') THEN
        RETURN '[]'::jsonb;
    END IF;

    -- 告警每类展示上限，同一读取语义，参数缺失时维持缺省 5
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'rpt.dashboard.alert.topn' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_topn := v_param::int;
            ELSE
                v_topn := COALESCE(NULLIF(v_param->>'v',''), '5')::int;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_topn := 5;
    END;

    -- 预算预警阈值，复用 fn_cron_budget_warning 的 um.params 读取语义，jsonb 数字或 {"v":n}
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'budget.warn.ratio' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_warn_ratio := v_param::numeric;
            ELSE
                v_warn_ratio := COALESCE(NULLIF(v_param->>'v', ''), '0.8')::numeric;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_warn_ratio := 0.8;
    END;

    -- 银行账户余额安全线，同一读取语义，参数缺失时维持缺省 0
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'cash.bank.safety.line' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_safety := v_param::numeric;
            ELSE
                v_safety := COALESCE(NULLIF(v_param->>'v', ''), '0')::numeric;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_safety := 0;
    END;

    SELECT jsonb_agg(t.row ORDER BY t.sort) INTO v_rows
      FROM (
        -- 预算执行率告警，APPROVED 计划执行率 >= 阈值（对键 IS NOT DISTINCT FROM 防 immv 空 dept 漏配）
        (SELECT 0 AS sort,
                jsonb_build_object('type', 'warning', 'kind', 'budget',
                                   'period', p.period,
                                   'dept_name', COALESCE(dep.name, ''),
                                   'account_name', COALESCE(ca.name, ''),
                                   'ratio', round((COALESCE(a.actual_amount, 0)::numeric / p.amount) * 100, 2),
                                   'plan_amount', p.amount,
                                   'actual_amount', COALESCE(a.actual_amount, 0),
                                   'threshold', v_warn_ratio) AS row
           FROM budget.budget_plans p
           LEFT JOIN rpt.budget_actual a
                  ON a.company_id = p.company_id AND a.period = p.period
                 AND a.account_id = p.account_id
                 AND a.dept_id IS NOT DISTINCT FROM p.dept_id
           LEFT JOIN org.departments dep ON dep.id = p.dept_id AND dep.deleted_at IS NULL
           LEFT JOIN gl.chart_accounts ca ON ca.id = p.account_id
          WHERE p.company_id = p_company_id
            AND p.status = 'APPROVED' AND p.deleted_at IS NULL AND p.amount > 0
            AND (COALESCE(a.actual_amount, 0)::numeric / p.amount) >= v_warn_ratio
          ORDER BY (COALESCE(a.actual_amount, 0)::numeric / p.amount) DESC
          LIMIT v_topn)
        UNION ALL
        -- 银行账户余额安全线告警，活跃账户净额（IN-OUT 累计）低于安全线
        (SELECT 1,
                jsonb_build_object('type', 'error', 'kind', 'bank',
                                   'bank_name', b.bank_name,
                                   'balance',
                                     COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0)::numeric(18,2),
                                   'safety_line', v_safety) AS row
           FROM cash.bank_accounts b
           LEFT JOIN cash.transactions t ON t.bank_account_id = b.id AND t.deleted_at IS NULL
          WHERE b.company_id = p_company_id
            AND b.deleted_at IS NULL AND b.is_active AND b.status = 'ACTIVE'
          GROUP BY b.id, b.bank_name
         HAVING COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0) < v_safety
          ORDER BY COALESCE(SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END), 0)
          LIMIT v_topn)
      ) t;
    RETURN COALESCE(v_rows, '[]'::jsonb);
END $$;
COMMENT ON FUNCTION rpt.fn_dashboard_alerts(uuid)
    IS '看板异常告警：预算执行率超 budget.warn.ratio 阈值（同 fn_cron_budget_warning 语义）+ 银行账户余额低于 cash.bank.safety.line 安全线（jsonb 数组；越权公司返回空集）';

-- 执行授权，4 个读写职责角色
-- PUBLIC 须显式收回，CREATE FUNCTION 默认带 PUBLIC EXECUTE，rpt schema 的 USAGE 收回只挡住
-- 跨 schema 调用，函数 ACL 自身仍是 PUBLIC 可执行

REVOKE EXECUTE ON FUNCTION rpt.fn_dashboard_alerts(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION rpt.fn_dashboard_alerts(uuid)
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- 银行账户余额安全线参数种子，幂等，0 = 仅透支告警，admin 可经系统参数调高

INSERT INTO um.params (code, value, updated_by)
VALUES ('cash.bank.safety.line', '0'::jsonb, '00000000-0000-7000-0000-000000000001')
ON CONFLICT (code) DO NOTHING;

RESET ROLE;

-- 文件末尾自查（纯逻辑命名约定，`SELECT '<名>' AS file_name` 与文件名一致，配合 psql -v ON_ERROR_STOP=1 确认本文件执行完整）
SELECT 'dashboard_alerts' AS file_name;
