-- FMS 财务系统（init/rpt_ap_aging），AP 侧账龄补齐，镜像 rpt.ar_aging_paid / rpt.ar_aging 的
-- 应付版本，rpt immv 仅定义 ar_aging/ar_aging_paid，AP 侧聚合缺失，账龄按
-- 未核销余额分桶 30/60/90/180+，rpt.ap_aging_paid 已核销金额 immv（ap.payment_allocations
-- 单表增量聚合，automatic 模式提交即一致），rpt.ap_aging 普通视图（ap.invoices ISSUED/PARTIAL
-- 余额 = total - 已核销，按供应商×期间展开，security_invoker=on 行级收敛），rpt SELECT 授权
-- 镜像 init/grants_base.sql rpt 段（operator/viewer/auditor/report 四职责角色只读）

-- 幂等，immv 不支持 OR REPLACE，按 relkind 判别 DROP TABLE / DROP MATERIALIZED VIEW IF EXISTS ...
-- CASCADE 前置（兼容 relkind='m' 普通 MV 与 relkind='r' immv 两种存量形态），CASCADE 连带删除依赖
-- rpt.ap_aging_paid 的 rpt.ap_aging，随后重建视图与授权

SET ROLE fms_admin;

-- 幂等前置，DROP 既有对象（immv 为 relkind='r' 普通表，relkind='m' 普通物化视图存量
-- 按实际 relkind 选择 DROP 语句）
DO $$
DECLARE v_r text; v_kind text;
BEGIN
    FOREACH v_r IN ARRAY ARRAY['rpt.ap_aging_paid']
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

-- rpt.ap_aging_paid 已核销金额增量聚合，ap_aging 降级方案，与 ar 侧同构，pg_ivm 1.15

-- 不允许"聚合函数 + 左连接"及"含聚合的子查询"，LEFT JOIN 骨架不可直接物化，改为基于
-- ap.payment_allocations 的单表增量聚合，按发票分组，+ 普通视图 rpt.ap_aging 计算余额
-- CASCADE，rpt.ap_aging 视图依赖本 immv，重跑时需连带删除后再重建
SELECT pgivm.create_immv(
    'rpt.ap_aging_paid',
    'SELECT pa.invoice_id, SUM(pa.amount) AS paid_amount
       FROM ap.payment_allocations pa
      WHERE pa.deleted_at IS NULL
      GROUP BY pa.invoice_id');

COMMENT ON TABLE rpt.ap_aging_paid IS
'应付已核销金额 IMMV：按发票累计核销额；rpt.ap_aging 视图基于本 IMMV 计算未核销余额（降级方案）';

-- rpt.ap_aging 应付账龄，未核销余额，供应商×期间，余额 = 发票总额 - 已核销（immv

-- rpt.ap_aging_paid 实时累计），消费侧按需要再按账龄区间（0-30/31-60/…）对 open_balance 分桶
CREATE OR REPLACE VIEW rpt.ap_aging AS
SELECT i.company_id,
       i.supplier_id,
       date_trunc('month', i.invoice_date)::date AS period,
       i.id                                      AS invoice_id,
       i.invoice_no,
       i.invoice_date,
       i.due_date,
       i.currency,
       i.total,
       COALESCE(a.paid_amount, 0)                 AS paid_amount,
       i.total - COALESCE(a.paid_amount, 0)       AS open_balance
FROM ap.invoices i
LEFT JOIN rpt.v_ap_aging_paid a ON a.invoice_id = i.id
WHERE i.status IN ('ISSUED', 'PARTIAL')
  AND i.deleted_at IS NULL;

COMMENT ON VIEW rpt.ap_aging IS
'应付账龄视图：已开具未付清发票（ISSUED/PARTIAL）按（供应商×期间）计算未核销余额，数据源为 IMMV rpt.ap_aging_paid';

-- 报表视图安全调用者，security_invoker=on，视图内对基表的访问按调用者身份受 rls 收敛，

-- 视图授权按矩阵下发后任意角色只能看到自己 org 范围的汇总行（配合注入 guc），防止视图属主
-- fms_admin（SUPERUSER）绕过 rls 的跨公司泄漏风险，与 rpt.ar_aging 同模式
ALTER VIEW rpt.ap_aging SET (security_invoker = on);

-- rpt SELECT 授权（镜像 init/grants_base.sql rpt 段，immv 与视图均为汇总级，四职责角色只读）

GRANT SELECT ON rpt.v_ap_aging_paid
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT ON rpt.ap_aging
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'rpt_ap_aging' AS file_name,
       to_regclass('rpt.ap_aging_paid') AS ap_aging_paid_obj,
       to_regclass('rpt.ap_aging')      AS ap_aging_view_obj,
       (SELECT has_table_privilege('fms_report', 'rpt.ap_aging', 'SELECT')) AS report_sel,
       (SELECT has_table_privilege('fms_operator', 'rpt.ap_aging_paid', 'SELECT')) AS operator_sel;
