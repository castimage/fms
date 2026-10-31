-- FMS 财务系统（init/rpt_log_base），rpt schema 4 个 pg_ivm 增量物化视图（immv，WITH (automatic) 自动维护，budget_actual/ar_aging_paid/dept_expense/cash_flow_daily）+ 5 个核心报表普通视图（trial_balance/income_statement/balance_sheet/cash_flow/ar_aging，security_invoker=on 行级收敛）+ log schema（app_logs 按天分区声明/task_runs，FORCE rls 仅 SELECT）

-- pg_ivm 1.15 语法与限制，严格按官方文档，CREATE INCREMENTAL MATERIALIZED VIEW 语法在本环境不可用，服务未重启解析钩子未加载，改用官方函数 pgivm.create_immv(immv text, view_definition text) 创建，automatic 模式基表 DML 事务内自动增量维护，提交即一致，免 cron 兜底刷新，不支持窗口/ORDER BY/DISTINCT/UNION/HAVING/GROUPING SETS，仅支持内置聚合 count/sum/avg/min/max，仅支持简单等值连接且连接列须出现在目标列表，聚合与左连接不可混用、含聚合的子查询不可用（故 ar_aging 按降级方案，receipt_allocations 增量聚合 + 普通视图算余额）

-- 状态口径，过账（POSTED）凭证与红字冲销，REVERSED，金额为负的反向凭证，必须同时纳入聚合，冲销后的净额才正确，原凭证 +100 与反向凭证 -100 抵销为 0，故 gl 相关 immv/视图统一 v.status IN ('POSTED','REVERSED')

-- 幂等，immv 不支持 OR REPLACE，按 relkind 判别 DROP TABLE / DROP MATERIALIZED VIEW IF EXISTS ... CASCADE 前置（兼容 relkind='m' 普通 MV 与 relkind='r' immv 两种存量形态），CASCADE 连带删除依赖 rpt.ar_aging_paid 的 rpt.ar_aging，随后 2.5 节重建

-- 职责，rpt 4 个 immv 唯一索引 uk_mv_budget_actual / uk_mv_ar_aging_paid / uk_mv_dept_expense / uk_mv_cash_flow_daily，聚合键唯一约束 + 增量维护效率

SET ROLE fms_admin;

-- 幂等前置，DROP 既有对象（immv 为 relkind='r' 普通表，relkind='m' 普通物化视图存量按实际 relkind 选择 DROP 语句），rpt.budget_actual_immv 测试残留，单表 company+account 聚合，定义与正式版不一致，一并清理
DO $$
DECLARE v_r text; v_kind text;
BEGIN
    FOREACH v_r IN ARRAY ARRAY['rpt.budget_actual','rpt.ar_aging_paid',
                               'rpt.dept_expense','rpt.cash_flow_daily',
                               'rpt.budget_actual_immv']
    LOOP
        IF to_regclass(v_r) IS NOT NULL THEN
            SELECT CASE c.relkind WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END
              INTO v_kind
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = split_part(v_r, '.', 1)
               AND c.relname = split_part(v_r, '.', 2);
            EXECUTE format('DROP %s %s CASCADE', v_kind, v_r);
        END IF;
    END LOOP;
END $$;

-- rpt.budget_actual 预算执行实际数（期间×科目×部门 SUM），基表 gl.vouchers JOIN gl.voucher_lines（凭证行级聚合，JOIN 等值条件 vl.voucher_id = v.id，两表均有主键满足 pg_ivm 约束），与 budget.budget_plans(company_id,period,account_id,dept_id) 直接对键，供预算对比与 budget.fn_block_if_exceeded 读取执行比例
SELECT pgivm.create_immv(
    'rpt.budget_actual',
    'SELECT vl.company_id, v.period, vl.account_id, vl.dept_id,
            SUM(vl.debit - vl.credit) AS actual_amount, count(*) AS line_cnt
       FROM gl.vouchers v
       JOIN gl.voucher_lines vl ON vl.voucher_id = v.id
      WHERE v.status IN (''POSTED'', ''REVERSED'')
        AND v.deleted_at IS NULL
        AND vl.deleted_at IS NULL
      GROUP BY vl.company_id, v.period, vl.account_id, vl.dept_id');

COMMENT ON TABLE rpt.budget_actual IS
'预算执行实际数 IMMV：凭证分录按（公司×期间×科目×部门）SUM(debit-credit)；pg_ivm 自动增量维护，提交即一致';

-- rpt.ar_aging_paid 已核销金额增量聚合，ar_aging 降级方案，pg_ivm 1.15 不允许"聚合函数 + 左连接"及"含聚合的子查询"，LEFT JOIN 骨架不可直接物化，改为基于 ar.receipt_allocations 的单表增量聚合，按发票分组，+ 普通视图 rpt.ar_aging 计算余额
-- CASCADE，rpt.ar_aging 视图依赖本 immv，重跑时需连带删除后再重建
SELECT pgivm.create_immv(
    'rpt.ar_aging_paid',
    'SELECT ra.invoice_id, SUM(ra.amount) AS paid_amount
       FROM ar.receipt_allocations ra
      WHERE ra.deleted_at IS NULL
      GROUP BY ra.invoice_id');

COMMENT ON TABLE rpt.ar_aging_paid IS
'应收已核销金额 IMMV：按发票累计核销额；rpt.ar_aging 视图基于本 IMMV 计算未核销余额（降级方案）';

-- rpt.dept_expense 部门费用（部门×期间×科目 SUM）
SELECT pgivm.create_immv(
    'rpt.dept_expense',
    'SELECT vl.company_id, v.period, vl.dept_id, vl.account_id,
            SUM(vl.debit - vl.credit) AS amount
       FROM gl.vouchers v
       JOIN gl.voucher_lines vl ON vl.voucher_id = v.id
      WHERE v.status IN (''POSTED'', ''REVERSED'')
        AND v.deleted_at IS NULL
        AND vl.deleted_at IS NULL
        AND vl.dept_id IS NOT NULL
      GROUP BY vl.company_id, v.period, vl.dept_id, vl.account_id');

COMMENT ON TABLE rpt.dept_expense IS
'部门费用 IMMV：费用凭证分录按（公司×期间×部门×科目）SUM(debit-credit)，部门费用仪表板数据源';

-- rpt.cash_flow_daily 资金流水按日聚合，收入/支出，SUM(CASE WHEN ...) 为聚合参数内表达式，pg_ivm 支持，非窗口/非子查询
SELECT pgivm.create_immv(
    'rpt.cash_flow_daily',
    'SELECT t.company_id, t.trx_date,
            SUM(CASE WHEN t.direction = ''IN''  THEN t.amount ELSE 0 END) AS income,
            SUM(CASE WHEN t.direction = ''OUT'' THEN t.amount ELSE 0 END) AS expense
       FROM cash.transactions t
      WHERE t.deleted_at IS NULL
      GROUP BY t.company_id, t.trx_date');

COMMENT ON TABLE rpt.cash_flow_daily IS
'资金日报 IMMV：银行流水按（公司×日期）聚合收入/支出，资金日报与趋势图数据源';

-- IMMV 无行级安全，职责角色不直接授 SELECT；以下属主视图按调用方 GUC 收敛公司，
-- 报表查询与账龄视图统一走安全视图，IMMV 仅保留给属主函数与增量维护
CREATE OR REPLACE VIEW rpt.v_budget_actual AS
    SELECT company_id, period, account_id, dept_id, actual_amount, line_cnt
      FROM rpt.budget_actual
     WHERE auth.can_access_org(company_id);
ALTER VIEW rpt.v_budget_actual OWNER TO fms_admin;

CREATE OR REPLACE VIEW rpt.v_dept_expense AS
    SELECT company_id, period, dept_id, account_id, amount
      FROM rpt.dept_expense
     WHERE auth.can_access_org(company_id);
ALTER VIEW rpt.v_dept_expense OWNER TO fms_admin;

CREATE OR REPLACE VIEW rpt.v_cash_flow_daily AS
    SELECT company_id, trx_date, income, expense
      FROM rpt.cash_flow_daily
     WHERE auth.can_access_org(company_id);
ALTER VIEW rpt.v_cash_flow_daily OWNER TO fms_admin;

CREATE OR REPLACE VIEW rpt.v_ar_aging_paid AS
    SELECT pa.invoice_id, pa.paid_amount
      FROM rpt.ar_aging_paid pa
      JOIN ar.invoices i ON i.id = pa.invoice_id
     WHERE i.deleted_at IS NULL AND auth.can_access_org(i.company_id);
ALTER VIEW rpt.v_ar_aging_paid OWNER TO fms_admin;

CREATE OR REPLACE VIEW rpt.v_ap_aging_paid AS
    SELECT pa.invoice_id, pa.paid_amount
      FROM rpt.ap_aging_paid pa
      JOIN ap.invoices i ON i.id = pa.invoice_id
     WHERE i.deleted_at IS NULL AND auth.can_access_org(i.company_id);
ALTER VIEW rpt.v_ap_aging_paid OWNER TO fms_admin;

-- 核心报表普通 sql 视图，非物化，避免维护成本，利润表/资产负债表/试算平衡表用普通视图 + 基表索引，查询计划走基表索引，全部为汇总级（GROUP BY 聚合），不暴露凭证/分录明细，符合 fms_report 角色"汇总只读、无明细敏感列"定位
-- 视图属主 fms_admin（SUPERUSER），视图内部对基表的访问不触发 rls，查询者可看到全部公司汇总数，全部视图 ALTER VIEW SET (security_invoker = on)，使基表行级策略按调用者身份生效，与 audit.safe_voucher_view 同模式，防止补授权后跨公司汇总泄漏
-- rpt.trial_balance 试算平衡表（公司×期间×科目 借/贷/余额）
CREATE OR REPLACE VIEW rpt.trial_balance AS
SELECT v.company_id,
       v.period,
       vl.account_id,
       ca.code,
       ca.name,
       SUM(vl.debit)              AS total_debit,
       SUM(vl.credit)             AS total_credit,
       SUM(vl.debit - vl.credit)  AS balance
FROM gl.vouchers v
JOIN gl.voucher_lines vl ON vl.voucher_id = v.id AND vl.deleted_at IS NULL
JOIN gl.chart_accounts ca ON ca.id = vl.account_id
WHERE v.status IN ('POSTED', 'REVERSED')
  AND v.deleted_at IS NULL
GROUP BY v.company_id, v.period, vl.account_id, ca.code, ca.name;

COMMENT ON VIEW rpt.trial_balance IS
'试算平衡表（汇总视图）：已过账/已冲销凭证分录按（公司×期间×科目）借贷合计与余额';

-- rpt.income_statement 利润表（公司×期间，收入/费用/净利润），REVENUE 类科目贷方余额计收入，EXPENSE 类科目借方余额计费用
CREATE OR REPLACE VIEW rpt.income_statement AS
SELECT v.company_id,
       v.period,
       SUM(CASE WHEN ca.account_type = 'REVENUE' THEN (vl.credit - vl.debit) ELSE 0 END) AS total_revenue,
       SUM(CASE WHEN ca.account_type = 'EXPENSE' THEN (vl.debit - vl.credit) ELSE 0 END)  AS total_expense,
       -- net_profit = 收入 - 费用，EXPENSE 分支符号取反（credit - debit 为负贡献）
       SUM(CASE WHEN ca.account_type = 'REVENUE' THEN (vl.credit - vl.debit)
                WHEN ca.account_type = 'EXPENSE' THEN (vl.credit - vl.debit)
                ELSE 0 END)                                                              AS net_profit
FROM gl.vouchers v
JOIN gl.voucher_lines vl ON vl.voucher_id = v.id AND vl.deleted_at IS NULL
JOIN gl.chart_accounts ca ON ca.id = vl.account_id
WHERE v.status IN ('POSTED', 'REVERSED')
  AND v.deleted_at IS NULL
GROUP BY v.company_id, v.period;

COMMENT ON VIEW rpt.income_statement IS
'利润表（汇总视图）：按（公司×期间）汇总收入、费用与净利润（account_type 判定；net_profit = total_revenue - total_expense）';

-- rpt.balance_sheet 资产负债表（公司×期间，资产/负债/权益，权益含当期未结转损益（REVENUE-EXPENSE 净额），与利润表净利润一致，保证勾稽 资产=负债+权益）
CREATE OR REPLACE VIEW rpt.balance_sheet AS
SELECT v.company_id,
       v.period,
       SUM(CASE WHEN ca.account_type = 'ASSET'     THEN (vl.debit - vl.credit) ELSE 0 END) AS total_asset,
       SUM(CASE WHEN ca.account_type = 'LIABILITY' THEN (vl.credit - vl.debit) ELSE 0 END) AS total_liability,
       -- 权益含当期未结转损益（EQUITY 科目余额 + 本期收入 - 本期费用），保证 资产 = 负债 + 权益 勾稽
       SUM(CASE WHEN ca.account_type IN ('EQUITY', 'REVENUE', 'EXPENSE')
                THEN (vl.credit - vl.debit) ELSE 0 END)                                   AS total_equity
FROM gl.vouchers v
JOIN gl.voucher_lines vl ON vl.voucher_id = v.id AND vl.deleted_at IS NULL
JOIN gl.chart_accounts ca ON ca.id = vl.account_id
WHERE v.status IN ('POSTED', 'REVERSED')
  AND v.deleted_at IS NULL
GROUP BY v.company_id, v.period;

COMMENT ON VIEW rpt.balance_sheet IS
'资产负债表（汇总视图）：按（公司×期间）汇总资产/负债/权益（account_type 判定）；权益包含当期未结转损益（REVENUE 与 EXPENSE 净额，即本期净利润），保证 资产=负债+权益 勾稽';

-- rpt.cash_flow 现金流量（按（公司×月）汇总银行流水收支与净额）
CREATE OR REPLACE VIEW rpt.cash_flow AS
SELECT t.company_id,
       date_trunc('month', t.trx_date)::date AS period,
       SUM(CASE WHEN t.direction = 'IN'  THEN t.amount ELSE 0 END)   AS cash_in,
       SUM(CASE WHEN t.direction = 'OUT' THEN t.amount ELSE 0 END)   AS cash_out,
       SUM(CASE WHEN t.direction = 'IN'  THEN t.amount
                ELSE -t.amount END)                                  AS net_flow
FROM cash.transactions t
WHERE t.deleted_at IS NULL
GROUP BY t.company_id, date_trunc('month', t.trx_date)::date;

COMMENT ON VIEW rpt.cash_flow IS
'现金流量（汇总视图）：按（公司×月）聚合资金流入/流出/净额，月报与趋势图数据源';

-- rpt.ar_aging 应收账龄（未核销余额，客户×期间），余额 = 发票总额 - 已核销（immv rpt.ar_aging_paid 实时累计），消费侧按需要再按账龄区间（0-30/31-60/…）对 open_balance 分桶
CREATE OR REPLACE VIEW rpt.ar_aging AS
SELECT i.company_id,
       i.customer_id,
       date_trunc('month', i.invoice_date)::date AS period,
       i.id                                      AS invoice_id,
       i.invoice_no,
       i.invoice_date,
       i.due_date,
       i.currency,
       i.total,
       COALESCE(a.paid_amount, 0)                 AS paid_amount,
       i.total - COALESCE(a.paid_amount, 0)       AS open_balance
FROM ar.invoices i
LEFT JOIN rpt.v_ar_aging_paid a ON a.invoice_id = i.id
WHERE i.status IN ('ISSUED', 'PARTIAL')
  AND i.deleted_at IS NULL;

COMMENT ON VIEW rpt.ar_aging IS
'应收账龄视图：已开具未付清发票（ISSUED/PARTIAL）按（客户×期间）计算未核销余额，数据源为 IMMV rpt.ar_aging_paid';

-- 报表视图安全调用者，security_invoker=on，视图内对基表的访问按调用者身份受 rls 收敛，视图授权按矩阵下发（init/grants_base.sql）后任意角色只能看到自己 org 范围的汇总行（配合注入 guc），防视图属主绕过 rls 的泄漏
ALTER VIEW rpt.trial_balance      SET (security_invoker = on);
ALTER VIEW rpt.income_statement   SET (security_invoker = on);
ALTER VIEW rpt.balance_sheet      SET (security_invoker = on);
ALTER VIEW rpt.cash_flow          SET (security_invoker = on);
ALTER VIEW rpt.ar_aging           SET (security_invoker = on);
COMMENT ON VIEW rpt.trial_balance IS
'试算平衡表（汇总视图，security_invoker=on）：已过账/已冲销凭证分录按（公司×期间×科目）借贷合计与余额';

-- log schema，log.app_logs / log.task_runs

-- 两表均"仅追加"，FORCE rls + 仅 SELECT 策略，非超级用户无 INSERT/UPDATE/DELETE 能力，写入仅经 SECURITY DEFINER 函数，如消费队列批量入库函数，与超级用户（pg_cron 任务）完成
-- SELECT 策略 USING (auth.current_uid IS NOT NULL)，审计/管理侧注入 fms.user_id 上下文后可读（L3 仅 auditor/admin），log.app_logs 按天分区由 queues_cron.sql 注册的 partman-maintenance cron 配套管理，本文件只建普通表并保持结构兼容分区父表（occurred_at NOT NULL），日志表非业务表，无 company_id
-- log.app_logs 应用日志，按天分区声明
CREATE TABLE IF NOT EXISTS log.app_logs (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    level       TEXT        NOT NULL DEFAULT 'INFO',
    logger      TEXT        NOT NULL DEFAULT '',
    message     TEXT        NOT NULL,
    context     JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_logs_level CHECK (level IN ('DEBUG', 'INFO', 'WARN', 'ERROR'))
);

COMMENT ON TABLE log.app_logs IS
'应用日志（结构化，仅追加）。按天分区：分区注册由 init/queues_cron.sql partman-maintenance 配套管理（pg_partman create_parent 声明式注册在分区管理文件进行），此处建普通表，表结构兼容分区父表（occurred_at NOT NULL 作为分区键）；写入仅经 SECURITY DEFINER 消费函数/超级用户，FORCE RLS 仅 SELECT 策略';

CREATE INDEX IF NOT EXISTS idx_app_logs_occurred ON log.app_logs (occurred_at);

-- log.task_runs 定时任务执行记录，pg_cron 任务结果落库，如 close-check 汇总、audit-chain-check 哈希链校验结果
CREATE TABLE IF NOT EXISTS log.task_runs (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    job_name    TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    detail      JSONB,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_runs_status CHECK (status IN ('OK', 'FAIL'))
);

COMMENT ON TABLE log.task_runs IS
'定时任务执行记录（仅追加）：job_name/status(OK|FAIL)/detail JSONB/started_at/finished_at；pg_cron 任务（init/queues_cron.sql）执行结果统一落此表，供运行监控与审计';

CREATE INDEX IF NOT EXISTS idx_task_runs_job ON log.task_runs (job_name, started_at);

-- log 表 rls，FORCE rls + 仅 SELECT 策略，写入封闭
ALTER TABLE log.app_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE log.app_logs FORCE ROW LEVEL SECURITY;
-- 应用日志含请求上下文，读取面收敛到审计与管理员（与 init/grants_base.sql 同名策略一致，
-- 该文件另有断言核对本策略含 is_admin 分支，三处定义须同文，否则后执行者覆盖先执行者）
DROP POLICY IF EXISTS log_app_logs_select ON log.app_logs;
CREATE POLICY log_app_logs_select ON log.app_logs
    FOR SELECT USING (auth.has_role('fms_auditor')
                      OR current_user = 'fms_admin'
                      OR auth.is_admin());

ALTER TABLE log.task_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE log.task_runs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS log_task_runs_select ON log.task_runs;
CREATE POLICY log_task_runs_select ON log.task_runs
    FOR SELECT USING (auth.current_uid() IS NOT NULL);

SET ROLE fms_admin;

-- rpt 4 个报表对象为 pg_ivm immv（rpt_log_base.sql 建，automatic 模式事务内自动增量维护），无普通物化视图可刷，
-- rpt.fn_cron_refresh_mvs 不重建（对 relkind='r' 的 immv 执行 REFRESH 恒报错，queues_cron.sql 已 DROP）
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_budget_actual
    ON rpt.budget_actual (company_id, period, account_id, dept_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_ar_aging_paid
    ON rpt.ar_aging_paid (invoice_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_dept_expense
    ON rpt.dept_expense (company_id, period, dept_id, account_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mv_cash_flow_daily
    ON rpt.cash_flow_daily (company_id, trx_date);

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1，确认本文件执行完整），期望 rpt_immv_cnt=4，immv 为普通表，按 pgivm.pg_ivm_immv 元数据计数，rpt_view_cnt=5、log_table_cnt=2、log_rls_policy_cnt=2
SELECT 'rpt_log_base' AS file_name,
       (SELECT count(*) FROM pgivm.pg_ivm_immv i
         JOIN pg_class c ON c.oid = i.immvrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'rpt')                                AS rpt_immv_cnt,
       (SELECT count(*) FROM pg_views    WHERE schemaname = 'rpt') AS rpt_view_cnt,
       (SELECT count(*) FROM pg_tables   WHERE schemaname = 'log') AS log_table_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'log') AS log_rls_policy_cnt;
