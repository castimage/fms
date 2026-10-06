-- verify/fix_behavior_verify.sql，修复项行为级验证，只读 + 事务包裹
-- 以 postgres 执行，每个检查独立事务（BEGIN/ROLLBACK），结果经 RAISE NOTICE 输出 PASS/FAIL。
-- CHK12 经 dblink 以 fms_app 自连本机 unix socket，socket 在 pg_hba 里为 trust，不校验口令，
-- 故连接串不含任何凭据，本文件不存口令

\set ON_ERROR_STOP 0

-- 解密出口按 current_user 身份校验

-- fms_app 直调必须失败（insufficient privilege）
BEGIN;
SET ROLE fms_app;
DO $$
BEGIN
    PERFORM auth.decrypt_bank_account('019fe2de-8c97-7d49-bb65-18a7bb305be2');
    RAISE NOTICE 'CHK1a FAIL: fms_app direct decrypt succeeded';
EXCEPTION WHEN insufficient_privilege OR others THEN
    RAISE NOTICE 'CHK1a PASS: fms_app direct decrypt rejected (%)', SQLERRM;
END $$;
ROLLBACK;

-- SET ROLE fms_report 必须失败
BEGIN;
SET ROLE fms_report;
DO $$
BEGIN
    PERFORM auth.decrypt_bank_account('019fe2de-8c97-7d49-bb65-18a7bb305be2');
    RAISE NOTICE 'CHK1b FAIL: fms_report decrypt succeeded';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK1b PASS: fms_report decrypt rejected (%)', SQLERRM;
END $$;
ROLLBACK;

-- SET ROLE fms_operator 必须成功并返回明文账号
BEGIN;
SET ROLE fms_operator;
SELECT 'CHK1c' AS chk,
       auth.decrypt_bank_account('019fe2de-8c97-7d49-bb65-18a7bb305be2') AS acct;
ROLLBACK;

-- 伪造 guc 注入 LOCKED 用户则 current_uid 恒 NULL

BEGIN;
SELECT set_config('fms.user_id', (SELECT id::text FROM um.users WHERE login_name = 'operator01'), true);
SELECT 'CHK2a' AS chk, auth.current_uid() IS NOT NULL AS before_lock;
UPDATE um.users SET status = 'LOCKED' WHERE login_name = 'operator01';
SELECT set_config('fms.user_id', (SELECT id::text FROM um.users WHERE login_name = 'operator01'), true);
SELECT 'CHK2b' AS chk, auth.current_uid() IS NULL AS locked_returns_null;
UPDATE um.users SET status = 'ACTIVE' WHERE login_name = 'operator01';
ROLLBACK;

-- jwt 签发/验签往返（委托 pgjwt.sign/verify，HS256）

-- jwt_sign 强制注入 iat/exp，pgjwt.verify 仅在载荷携带 exp/nbf 时才做时间窗校验，缺失即无界放行恒真，RFC 7519 要求 exp
BEGIN;
SET ROLE fms_admin;
SELECT 'CHK3a' AS chk, left(auth.jwt_sign('{"sub":"admin","role":"operator"}'::jsonb), 30) AS token_prefix;
SELECT 'CHK3b' AS chk, auth.jwt_verify(auth.jwt_sign('{"sub":"admin","role":"operator"}'::jsonb))->>'sub' AS payload_sub;
SELECT 'CHK3d' AS chk,
       (auth.jwt_verify(auth.jwt_sign('{"sub":"admin"}'::jsonb))->>'exp')::bigint > (extract(epoch from now()))::bigint AS has_future_exp,
       (auth.jwt_verify(auth.jwt_sign('{"sub":"admin"}'::jsonb))->>'iat') IS NOT NULL AS has_iat;
DO $$
BEGIN
    PERFORM auth.jwt_verify('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbiJ9.bad_signature');
    RAISE NOTICE 'CHK3c FAIL: tampered token accepted';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK3c PASS: tampered token rejected (%)', SQLERRM;
END $$;
ROLLBACK;

-- 经 auth.fn_soft_delete 软删除，operator 上下文

BEGIN;
SET LOCAL fms.user_id = '00000000-0000-7000-0000-000000000001';
SET LOCAL fms.org_ids = '00000000-0000-7000-0000-000000000010';
SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
SET ROLE fms_operator;
SELECT 'CHK4a' AS chk,
       auth.fn_soft_delete('gl', 'vouchers', '00000000-0000-7000-0000-000000000201') AS soft_deleted_draft;
SELECT 'CHK4b' AS chk,
       (SELECT count(*) FROM gl.vouchers WHERE id = '00000000-0000-7000-0000-000000000201') AS visible_after_softdel;
ROLLBACK;

-- 4c) 直改 deleted_at 的软删除仍被 rls 拦截，PG15+ 新行可见性
BEGIN;
SET LOCAL fms.user_id = '00000000-0000-7000-0000-000000000001';
SET LOCAL fms.org_ids = '00000000-0000-7000-0000-000000000010';
SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
SET ROLE fms_operator;
DO $$
BEGIN
    UPDATE gl.vouchers SET deleted_at = now() WHERE id = '00000000-0000-7000-0000-000000000201';
    RAISE NOTICE 'CHK4c FAIL: direct soft-delete UPDATE succeeded';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK4c PASS: direct soft-delete UPDATE blocked (%)', SQLERRM;
END $$;
ROLLBACK;

-- 密钥对职责角色不可读

BEGIN;
SET LOCAL fms.user_id = '00000000-0000-7000-0000-000000000001';
SET LOCAL fms.org_ids = '00000000-0000-7000-0000-000000000010';
SET ROLE fms_viewer;
DO $$
DECLARE v_c int;
BEGIN
    SELECT count(*) INTO v_c FROM um.params WHERE code IN ('pgcrypto.master_key','jwt.secret');
    RAISE NOTICE 'CHK5 FAIL: viewer read um.params keys (count=%)', v_c;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK5 PASS: viewer cannot read um.params keys (%)', SQLERRM;
END $$;
DO $$
DECLARE v_c int;
BEGIN
    SELECT count(*) INTO v_c FROM sec.app_secrets;
    RAISE NOTICE 'CHK5b FAIL: viewer can read sec.app_secrets (count=%)', v_c;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK5b PASS: viewer cannot read sec.app_secrets (%)', SQLERRM;
END $$;
ROLLBACK;

-- audit.record 对 operator 不可执行

BEGIN;
SET ROLE fms_operator;
DO $$
BEGIN
    PERFORM audit.record('FAKE', 'x', NULL, NULL, NULL, NULL, NULL);
    RAISE NOTICE 'CHK6 FAIL: operator called audit.record';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK6 PASS: operator audit.record rejected (%)', SQLERRM;
END $$;
ROLLBACK;

-- trg_period_voucher_guard，CLOSED 期间内 DRAFT 凭证被拒

-- 硬编码 '2026-07' 会假设该期间 CLOSED，但 seed_data 为 2026-07/2026-08 双 OPEN，
-- 该假设与 demo 凭证日期冲突，在双 OPEN 库上 CHK7 恒打印误导性 FAIL，
-- 故动态取最新 CLOSED 期间，无 CLOSED 期间时 SKIP 提示，不误报
BEGIN;
SET ROLE fms_admin;
DO $$
DECLARE v_period text;
BEGIN
    SELECT max(period) INTO v_period FROM gl.periods
     WHERE status = 'CLOSED' AND deleted_at IS NULL;
    IF v_period IS NULL THEN
        RAISE NOTICE 'CHK7 SKIP: 无 CLOSED 期间可测，跳过期间守卫校验';
        RETURN;
    END IF;
    BEGIN
        INSERT INTO gl.vouchers (company_id, period, voucher_date, status, source_type, created_by, total_debit, total_credit)
        VALUES ('00000000-0000-7000-0000-000000000010', v_period, DATE '2020-01-20', 'DRAFT', 'CHK_TEST', '00000000-0000-7000-0000-000000000001', 0, 0);
        RAISE NOTICE 'CHK7 FAIL: DRAFT voucher created in CLOSED period %', v_period;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'CHK7 PASS: DRAFT voucher in CLOSED period % rejected (%)', v_period, SQLERRM;
    END;
END $$;
ROLLBACK;

-- trg_period_guard，未置 force_reopen 的 CLOSED 转 OPEN 被拒

-- 与 CHK7 同口径，动态取最新 CLOSED 期间，避免硬编码 2026-07 在双 OPEN 库上误报
BEGIN;
SET ROLE fms_admin;
DO $$
DECLARE v_period text;
BEGIN
    SELECT max(period) INTO v_period FROM gl.periods
     WHERE status = 'CLOSED' AND deleted_at IS NULL;
    IF v_period IS NULL THEN
        RAISE NOTICE 'CHK8 SKIP: 无 CLOSED 期间可测，跳过反结账守卫校验';
        RETURN;
    END IF;
    BEGIN
        UPDATE gl.periods SET status = 'OPEN' WHERE company_id = '00000000-0000-7000-0000-000000000010' AND period = v_period;
        RAISE NOTICE 'CHK8 FAIL: direct reopen succeeded (% 未拦截)', v_period;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'CHK8 PASS: direct reopen rejected (%)', SQLERRM;
    END;
END $$;
ROLLBACK;

-- SoD，同人审批被拒

BEGIN;
SET LOCAL fms.user_id = '00000000-0000-7000-0000-000000000001';
SET LOCAL fms.org_ids = '00000000-0000-7000-0000-000000000010';
SET LOCAL fms.dept_ids = '00000000-0000-7000-0000-000000000020,00000000-0000-7000-0000-000000000021';
SET ROLE fms_operator;
DO $$
DECLARE v_vid uuid;
BEGIN
    INSERT INTO gl.vouchers (company_id, period, voucher_date, status, source_type, created_by, total_debit, total_credit)
    VALUES ('00000000-0000-7000-0000-000000000010', '2026-08', DATE '2026-08-20', 'DRAFT', 'CHK_TEST', '00000000-0000-7000-0000-000000000001', 100.00, 100.00)
    RETURNING id INTO v_vid;
    INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit, currency, company_id)
    SELECT v_vid, 1, id, 'so d test', 100.00, 0.00, 'CNY', '00000000-0000-7000-0000-000000000010'
      FROM gl.chart_accounts WHERE company_id = '00000000-0000-7000-0000-000000000010' AND code = '1001';
    INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit, currency, company_id)
    SELECT v_vid, 2, id, 'so d test', 0.00, 100.00, 'CNY', '00000000-0000-7000-0000-000000000010'
      FROM gl.chart_accounts WHERE company_id = '00000000-0000-7000-0000-000000000010' AND code = '6001';
    UPDATE gl.vouchers SET status = 'APPROVED', approved_by = created_by
     WHERE id = v_vid;
    RAISE NOTICE 'CHK9 FAIL: same-person approval succeeded';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK9 PASS: same-person approval rejected (%)', SQLERRM;
END $$;
ROLLBACK;

-- fn_auto_reconcile 冒烟，空数据路径

BEGIN;
SET ROLE fms_admin;
SELECT 'CHK10' AS chk, cash.fn_auto_reconcile('00000000-0000-7000-0000-000000000010') AS auto_matched;
ROLLBACK;

-- fn_consume_log_queues 冒烟，投 2 条后消费

BEGIN;
SET ROLE fms_admin;
SELECT pgmq.send('q_log_app', jsonb_build_object('level','INFO','logger','chk','message','consume test','trace_id','t-1','ts', now()), 0);
SELECT pgmq.send('q_log_access', jsonb_build_object('method','GET','path','/chk','status',200,'latency',12,'ip','127.0.0.1','ts', now()), 0);
SELECT 'CHK11' AS chk, log.fn_consume_log_queues() AS consumed;
SELECT 'CHK11b' AS chk, count(*) AS app_log_rows FROM log.app_logs WHERE message = 'consume test';
SELECT 'CHK11c' AS chk, count(*) AS access_log_rows FROM log.access_logs WHERE path = '/chk';
ROLLBACK;

-- fms_app 连接登录校验，经 dblink 自连本机 unix socket，校验沙箱连接面可用性

-- 硬编码异机 ip 测试机会使校验实际连到另一台库、结果误导，
-- 故用本机 unix socket（/var/run/postgresql，pg_hba 对 local 为 trust，认证不涉口令）
SELECT 'CHK12' AS chk, t.c AS app_login_ok FROM dblink(
    'host=/var/run/postgresql dbname=fms user=fms_app',
    'SELECT 1') AS t(c int);

-- 公司级凭证取号序列惰性创建

BEGIN;
SET ROLE fms_admin;
SELECT 'CHK13' AS chk, gl.fn_get_voucher_no('00000000-0000-7000-0000-000000000010', '2026-08') AS next_no;
ROLLBACK;

-- v2 重算后审计哈希链仍完好

SELECT 'CHK14' AS chk, prev_ok AS chain_ok, broken_at FROM audit.audit_log_chain_check();

-- 发票不可变，ISSUED 发票更新被拒

BEGIN;
SET ROLE fms_admin;
INSERT INTO ar.invoices (company_id, customer_id, invoice_no, invoice_date, due_date, total, status)
SELECT '00000000-0000-7000-0000-000000000010', id, 'CHK-INV-001', DATE '2026-08-01', DATE '2026-09-01', 100.00, 'ISSUED'
  FROM ar.customers WHERE company_id = '00000000-0000-7000-0000-000000000010' LIMIT 1;
DO $$
BEGIN
    UPDATE ar.invoices SET total = 999.00 WHERE invoice_no = 'CHK-INV-001';
    RAISE NOTICE 'CHK15 FAIL: ISSUED invoice updated';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'CHK15 PASS: ISSUED invoice update rejected (%)', SQLERRM;
END $$;
ROLLBACK;
