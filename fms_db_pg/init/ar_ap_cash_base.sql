-- FMS 财务系统（init/ar_ap_cash_base），ar 5 表（customers/invoices/receipts/receipt_allocations/bad_debt_provision，核销唯一约束防重复核销）+ ap 6 表（suppliers/invoices/payments/payment_allocations/reconciliations/reconciliation_lines）+ cash 5 表（bank_accounts 列加密+盲索引/transactions/bank_statements/bank_statement_lines/reconcile_runs，对账 GIN trgm 索引）+ 全表 FORCE rls + 授权矩阵 + 审计捕获触发器挂载
-- rpt_log_base.sql immv/视图依赖 ar.receipt_allocations(amount/invoice_id)、cash.transactions(trx_date/direction/amount)、ar.invoices 列契约，seed_data.sql 种子依赖 ar.customers/ap.suppliers UNIQUE(company_id,code)、cash.bank_accounts UNIQUE(account_no_hash)
-- 主键约定，id uuid PRIMARY KEY DEFAULT uuidv7（pg 18 原生，时间有序），全部引用列 uuid，无声明 FK，语义引用，类型与目标主键一致
-- 职责，ar.receipts 收款重复登记幂等防护，uk_ar_receipt_dedup 部分唯一索引（同客户/同金额/同日期/同账户仅一条有效收款单，重复 INSERT 23505 映射 40900，软删行不占键）
-- 职责，uk_ar_customer_code/uk_ar_invoice_no/uk_ap_supplier_code/uk_ap_invoice_no
-- 为部分唯一索引 WHERE deleted_at IS NULL（软删行不占键，编码可复用，与 uk_ar_receipt_dedup 同构），
-- 幂等，ALTER TABLE ... DROP CONSTRAINT IF EXISTS 处理部署库遗留约束，CREATE UNIQUE INDEX IF NOT EXISTS 兜底

SET ROLE fms_admin;

-- ar 应收，5 表
-- CREATE TABLE IF NOT EXISTS 幂等保留存量数据，不 DROP 清空既有业务数据
CREATE TABLE IF NOT EXISTS ar.customers (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid          NOT NULL,
    code         varchar(32)   NOT NULL,
    name         varchar(128)  NOT NULL,
    tax_no       varchar(32),
    credit_limit numeric(18,2) NOT NULL DEFAULT 0,
    status       varchar(16)   NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','INACTIVE','BLOCKED')),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);
-- 客户编码唯一（软删行不占键，与 uk_ar_receipt_dedup 同构），
-- 部分唯一索引（deleted_at 过滤），DROP CONSTRAINT IF EXISTS 处理部署库遗留约束，
-- CREATE UNIQUE INDEX IF NOT EXISTS 兜底幂等
ALTER TABLE ar.customers DROP CONSTRAINT IF EXISTS uk_ar_customer_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_customer_code ON ar.customers (company_id, code)
    WHERE deleted_at IS NULL;
-- 客户名模糊检索走真实 trgm 索引，similarity/%/<-> 只能被 gin_trgm_ops（或 gist_trgm_ops）加速，to_tsvector 表达式索引对其不可用且不做中文分词
CREATE INDEX IF NOT EXISTS ix_ar_customer_name ON ar.customers USING GIN (name gin_trgm_ops);

CREATE TABLE IF NOT EXISTS ar.invoices (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid          NOT NULL,
    customer_id  uuid          NOT NULL,
    invoice_no   varchar(32)   NOT NULL,
    invoice_date date          NOT NULL,
    due_date     date,
    currency     char(3)       NOT NULL DEFAULT 'CNY',
    total        numeric(18,2) NOT NULL DEFAULT 0,
    tax          numeric(18,2) NOT NULL DEFAULT 0,
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','ISSUED','PARTIAL','PAID','VOID')),
    voucher_id   uuid,
    version      int           NOT NULL DEFAULT 0,   -- 乐观锁
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);
-- 发票号唯一（软删行不占键，与 uk_ar_receipt_dedup 同构），部分唯一索引
-- （deleted_at 过滤），DROP CONSTRAINT IF EXISTS 处理部署库遗留约束，幂等
ALTER TABLE ar.invoices DROP CONSTRAINT IF EXISTS uk_ar_invoice_no;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_invoice_no ON ar.invoices (company_id, invoice_no)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_ar_inv_customer ON ar.invoices (customer_id, invoice_date);

CREATE TABLE IF NOT EXISTS ar.receipts (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id      uuid          NOT NULL,
    customer_id     uuid          NOT NULL,
    amount          numeric(18,2) NOT NULL CHECK (amount > 0),
    bank_account_id uuid,
    receipt_date    date          NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'RECEIVED'
                    CHECK (status IN ('RECEIVED','ALLOCATED','REVERSED')),
    voucher_id      uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);
CREATE INDEX IF NOT EXISTS ix_ar_rcpt_customer ON ar.receipts (customer_id, receipt_date);

CREATE TABLE IF NOT EXISTS ar.receipt_allocations (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    receipt_id  uuid          NOT NULL,
    invoice_id  uuid          NOT NULL,
    amount      numeric(18,2) NOT NULL CHECK (amount > 0),
    version     int           NOT NULL DEFAULT 0,    -- 乐观锁（UPDATE ... WHERE version=?）
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
-- 防重复核销，部分唯一索引，仅对有效行去重，撤销核销/冲销为软删（deleted_at 置位），
-- 全行 UNIQUE 约束会占住 (receipt_id, invoice_id) 键导致同一对无法再次核销
CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_alloc ON ar.receipt_allocations (receipt_id, invoice_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_ar_alloc_invoice ON ar.receipt_allocations (invoice_id);

CREATE TABLE IF NOT EXISTS ar.bad_debt_provision (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    invoice_id  uuid          NOT NULL,
    period      char(7)       NOT NULL,
    amount      numeric(18,2) NOT NULL CHECK (amount >= 0),
    status      varchar(16)   NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','CONFIRMED','REVERSED')),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
CREATE INDEX IF NOT EXISTS ix_ar_bdp_period ON ar.bad_debt_provision (period);

-- ap 应付，6 表
CREATE TABLE IF NOT EXISTS ap.suppliers (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid          NOT NULL,
    code         varchar(32)   NOT NULL,
    name         varchar(128)  NOT NULL,
    tax_no       varchar(32),
    credit_limit numeric(18,2) NOT NULL DEFAULT 0,
    status       varchar(16)   NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','INACTIVE','BLOCKED')),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);
-- 供应商编码唯一（软删行不占键，与 uk_ar_receipt_dedup 同构），部分唯一索引
-- （deleted_at 过滤），DROP CONSTRAINT IF EXISTS 处理部署库遗留约束，幂等
ALTER TABLE ap.suppliers DROP CONSTRAINT IF EXISTS uk_ap_supplier_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ap_supplier_code ON ap.suppliers (company_id, code)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ap.invoices (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid          NOT NULL,
    supplier_id  uuid          NOT NULL,
    invoice_no   varchar(32)   NOT NULL,
    invoice_date date          NOT NULL,
    due_date     date,
    currency     char(3)       NOT NULL DEFAULT 'CNY',
    total        numeric(18,2) NOT NULL DEFAULT 0,
    tax          numeric(18,2) NOT NULL DEFAULT 0,
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','ISSUED','PARTIAL','PAID','VOID')),
    voucher_id   uuid,
    version      int           NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);
-- 发票号唯一（软删行不占键，与 uk_ar_receipt_dedup 同构），部分唯一索引
-- （deleted_at 过滤），DROP CONSTRAINT IF EXISTS 处理部署库遗留约束，幂等
ALTER TABLE ap.invoices DROP CONSTRAINT IF EXISTS uk_ap_invoice_no;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ap_invoice_no ON ap.invoices (company_id, invoice_no)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ap.payments (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id      uuid          NOT NULL,
    supplier_id     uuid          NOT NULL,
    amount          numeric(18,2) NOT NULL CHECK (amount > 0),
    bank_account_id uuid,
    payment_date    date          NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'PAID'
                    CHECK (status IN ('PAID','ALLOCATED','REVERSED')),
    voucher_id      uuid,
    dup_key         uuid,          -- 幂等键，公司/供应商/账户/金额/付款日期 派生，防重复付款登记
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);
-- 防重复付款登记，部分唯一索引，仅有效行去重，软删后同键可重新登记
CREATE UNIQUE INDEX IF NOT EXISTS uk_ap_payments_dup ON ap.payments (company_id, dup_key)
    WHERE dup_key IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ap.payment_allocations (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    payment_id  uuid          NOT NULL,
    invoice_id  uuid          NOT NULL,
    amount      numeric(18,2) NOT NULL CHECK (amount > 0),
    version     int           NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
-- 防重复核销，部分唯一索引，仅对有效行去重，撤销核销/冲销为软删（deleted_at 置位），
-- 全行 UNIQUE 约束会占住 (payment_id, invoice_id) 键导致同一对无法再次核销
CREATE UNIQUE INDEX IF NOT EXISTS uk_ap_alloc ON ap.payment_allocations (payment_id, invoice_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ap.reconciliations (
    id             uuid         PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid         NOT NULL,
    supplier_id    uuid         NOT NULL,
    statement_date date         NOT NULL,
    status         varchar(16)  NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT','CONFIRMED','VOID')),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);

CREATE TABLE IF NOT EXISTS ap.reconciliation_lines (
    id                  uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id          uuid          NOT NULL,
    reconciliation_id   uuid          NOT NULL,
    trx_date            date          NOT NULL,
    amount              numeric(18,2) NOT NULL,
    summary             text,
    match_status        varchar(16)   NOT NULL DEFAULT 'UNMATCHED'
                        CHECK (match_status IN ('UNMATCHED','MATCHED','MANUAL')),
    matched_invoice_id  uuid,
    similarity_score    numeric(6,4),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    deleted_at          timestamptz
);
CREATE INDEX IF NOT EXISTS ix_ap_reconcil_line ON ap.reconciliation_lines (reconciliation_id);

-- cash 资金，5 表
CREATE TABLE IF NOT EXISTS cash.bank_accounts (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid        NOT NULL,
    bank_name      varchar(128) NOT NULL,
    account_no_enc bytea       NOT NULL,           -- pgp_sym_encrypt 列加密
    account_no_hash char(64)   NOT NULL,           -- sha256 盲索引，等值查询
    account_type   varchar(16) NOT NULL DEFAULT 'CORPORATE'
                   CHECK (account_type IN ('CORPORATE','PERSONAL')),
    currency       char(3)     NOT NULL DEFAULT 'CNY',
    is_active      boolean     NOT NULL DEFAULT true,
    status         varchar(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,
    CONSTRAINT uk_bank_account_hash UNIQUE (account_no_hash)
);
COMMENT ON COLUMN cash.bank_accounts.account_no_enc IS 'pgp_sym_encrypt 加密，密钥经 sec.app_secrets.pgcrypto.master_key；解密唯一出口 auth.decrypt_bank_account';

CREATE TABLE IF NOT EXISTS cash.transactions (
    id               uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id       uuid          NOT NULL,
    bank_account_id  uuid          NOT NULL,
    direction        varchar(8)    NOT NULL CHECK (direction IN ('IN','OUT')),
    amount           numeric(18,2) NOT NULL CHECK (amount > 0),
    partner          varchar(128),
    summary          text,
    trx_date         date          NOT NULL,
    idem_key         varchar(64),                -- 创建幂等键（同公司+账户+内容哈希，同 body 重复提交仅 1 条，uk_trx_idem 兜底）
    voucher_line_id  uuid,
    match_status     varchar(16)   NOT NULL DEFAULT 'UNMATCHED'
                     CHECK (match_status IN ('UNMATCHED','MATCHED','MANUAL')),
    matched_line_id  uuid,
    similarity_score numeric(6,4),
    reconciled_at    timestamptz,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);
CREATE INDEX IF NOT EXISTS ix_trx_bank      ON cash.transactions (bank_account_id, trx_date);
-- 对账模糊匹配走真 trgm GIN 索引（fn_auto_reconcile 的 similarity/% 可加速）
CREATE INDEX IF NOT EXISTS ix_trx_summary   ON cash.transactions USING GIN (summary gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_trx_match     ON cash.transactions (match_status) WHERE deleted_at IS NULL;
-- 创建幂等，同公司+账户+内容哈希仅 1 条有效流水，重复提交返回既有流水，并发竞态 23505 兜底
CREATE UNIQUE INDEX IF NOT EXISTS uk_trx_idem ON cash.transactions (company_id, bank_account_id, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS cash.bank_statements (
    id             uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid          NOT NULL,
    bank_account_id uuid         NOT NULL,
    filename       varchar(255)  NOT NULL,
    idem_key       varchar(64),                 -- 导入幂等键（filename+全部行内容哈希，重复导入仅 1 份，uk_bank_stmt_idem 兜底）
    import_date    date          NOT NULL DEFAULT CURRENT_DATE,
    status         varchar(16)   NOT NULL DEFAULT 'IMPORTED'
                   CHECK (status IN ('IMPORTED','RECONCILED','VOID')),
    total_lines    int           NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);
-- 导入幂等，同公司+账户+filename+内容哈希仅 1 份有效对账单，重复导入返回既有对账单，并发竞态 23505 兜底
CREATE UNIQUE INDEX IF NOT EXISTS uk_bank_stmt_idem ON cash.bank_statements (company_id, bank_account_id, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS cash.bank_statement_lines (
    id               uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id       uuid          NOT NULL,
    statement_id     uuid          NOT NULL,
    trx_date         date          NOT NULL,
    amount           numeric(18,2) NOT NULL,
    summary          text,
    direction        varchar(8)     CHECK (direction IN ('IN','OUT')),
    match_status     varchar(16)   NOT NULL DEFAULT 'UNMATCHED'
                     CHECK (match_status IN ('UNMATCHED','MATCHED','MANUAL')),
    matched_trx_id   uuid,
    similarity_score numeric(6,4),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);
CREATE INDEX IF NOT EXISTS ix_bsl_statement ON cash.bank_statement_lines (statement_id);
-- 银行对账单摘要 trgm GIN 索引（fn_auto_reconcile 候选扫描走此索引）
CREATE INDEX IF NOT EXISTS ix_bsl_summary   ON cash.bank_statement_lines USING GIN (summary gin_trgm_ops);

CREATE TABLE IF NOT EXISTS cash.reconcile_runs (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid        NOT NULL,
    period         char(7)     NOT NULL,
    run_at         timestamptz NOT NULL DEFAULT now(),
    auto_matched   int         NOT NULL DEFAULT 0,
    manual_matched int         NOT NULL DEFAULT 0,
    diff_count     int         NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz
);

-- rls（全表 FORCE，can_access_org 行级收敛，策略函数族 fail-closed）
DO $$
DECLARE
    t   text;
    tbl text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ar.customers','ar.invoices','ar.receipts','ar.receipt_allocations',
        'ar.bad_debt_provision',
        'ap.suppliers','ap.invoices','ap.payments','ap.payment_allocations',
        'ap.reconciliations','ap.reconciliation_lines',
        'cash.bank_accounts','cash.transactions','cash.bank_statements',
        'cash.bank_statement_lines','cash.reconcile_runs']
    LOOP
        tbl := split_part(t, '.', 2);
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', tbl || '_select', t);
        EXECUTE format('CREATE POLICY %I ON %s FOR SELECT USING '
                       || '(deleted_at IS NULL AND auth.can_access_org(company_id))',
                       tbl || '_select', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', tbl || '_insert', t);
        EXECUTE format('CREATE POLICY %I ON %s FOR INSERT WITH CHECK '
                       || '(auth.can_access_org(company_id))',
                       tbl || '_insert', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', tbl || '_update', t);
        EXECUTE format('CREATE POLICY %I ON %s FOR UPDATE USING '
                       || '(deleted_at IS NULL AND auth.can_access_org(company_id)) '
                       || 'WITH CHECK (auth.can_access_org(company_id))',
                       tbl || '_update', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', tbl || '_delete', t);
        EXECUTE format('CREATE POLICY %I ON %s FOR DELETE USING '
                       || '(deleted_at IS NULL AND auth.can_access_org(company_id))',
                       tbl || '_delete', t);
    END LOOP;
END $$;

-- 授权（矩阵，operator W / viewer、auditor R / report R，汇总裁剪，bank_accounts 敏感列 account_no_enc/account_no_hash 不授予任何角色）
GRANT USAGE ON SCHEMA ar, ap, cash TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ar TO fms_operator;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ap TO fms_operator;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cash TO fms_operator;
GRANT SELECT ON ALL TABLES IN SCHEMA ar, ap TO fms_viewer, fms_auditor;
GRANT SELECT ON ALL TABLES IN SCHEMA ar, ap TO fms_report;
-- cash 只读角色按表授，bank_accounts 密文账号列不授予任何角色（与 auth_org_functions.sql 的 fn_create_bank_account/decrypt_bank_account 对偶，
-- 全表 GRANT 会绕过列级收紧，故 bank_accounts 单独 REVOKE 兜底）
GRANT SELECT ON ALL TABLES IN SCHEMA cash TO fms_viewer, fms_auditor, fms_report;
REVOKE SELECT ON cash.bank_accounts FROM fms_viewer, fms_auditor, fms_report;

-- pgaudit 对象审计标记，cash.bank_accounts 密文账号的 SELECT 入 pgaudit 审计日志，fms_pgaudit_role 为 NOLOGIN 标记角色，不改变业务授权面，GRANT ALL TABLES 已含对职责角色的 SELECT，本授权仅作用于 pgaudit 标记
GRANT SELECT ON cash.bank_accounts TO fms_pgaudit_role;

-- 发票不可变触发器，开票（ISSUED）后发票进入资金/核算闭环，防 operator 直改已开票
-- 发票的金额/税额/日期/供应商等业务字段，非 status/voucher_id 列一律不可变，对全员生效含 superuser，
-- 且 PAID/VOID 终态禁改禁删，DELETE 仅限 DRAFT（经 auth.fn_soft_delete 闸口）。

-- 状态迁移例外，业务生命周期，Java 核销/撤销核销/作废走此通道，终审仍在此触发器，
--   ISSUED 转 PARTIAL/PAID 为核销推进、ISSUED 转 VOID 为作废，
--   PARTIAL 转 PAID 为核销完毕、PARTIAL 转 ISSUED 为撤销核销回退、PARTIAL 转 VOID 为作废。
-- 同状态更新（NEW.status = OLD.status，ISSUED->ISSUED）放行，凭证联动回填 voucher_id
-- 即此形态（gl_voucher_link 联动凭证开票时/补生成端点回填）。
-- voucher_id 为凭证联动回填列，豁免"仅 status 可变更"检查（与 init/gl_voucher_link.sql
-- 内重定义保持同构，两文件定义必须一致，否则执行顺序会互相覆盖）。
-- 除 status/voucher_id 列变化外任何 UPDATE 一律拒绝，PAID/VOID 起任何状态迁移一律拒绝。
CREATE OR REPLACE FUNCTION ar.fn_ar_invoice_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ar, pg_catalog
AS $$
DECLARE
    v_allowed_new_states text[] := ARRAY['PARTIAL','PAID','VOID'];
    v_allowed_partial_extra text[] := ARRAY['ISSUED'];
    v_only_status boolean;
BEGIN
    IF TG_OP = 'DELETE' AND OLD.status IN ('ISSUED','PARTIAL','PAID','VOID') THEN
        RAISE EXCEPTION '应收发票已开票/核销/作废（status=%），禁止删除（trg_ar_invoice_immutable）', OLD.status;
    END IF;
    IF OLD.status IN ('PAID','VOID') THEN
        RAISE EXCEPTION '应收发票已结清/作废（status=%），禁止修改（trg_ar_invoice_immutable）', OLD.status;
    END IF;
    IF OLD.status IN ('ISSUED','PARTIAL') THEN
        -- 状态机迁移白名单，ISSUED -> PARTIAL/PAID，核销推进/ VOID，作废，
        -- PARTIAL -> ISSUED，撤销核销回退/ PAID，核销结清/ VOID，作废，
        -- NEW.status = OLD.status，状态不变放行，凭证联动回填 voucher_id 即此形态
        -- （ISSUED->ISSUED），仅 status/voucher_id 可变更由下方 v_only_status 终审兜底
        IF NOT (
            (NEW.status = OLD.status) OR
            (OLD.status = 'ISSUED'  AND NEW.status = ANY (v_allowed_new_states)) OR
            (OLD.status = 'PARTIAL' AND (NEW.status = ANY (v_allowed_new_states)
                                         OR NEW.status = ANY (v_allowed_partial_extra)))
        ) THEN
            RAISE EXCEPTION '应收发票已开票（status=%），非法状态迁移 -> %（仅可 PARTIAL/PAID 核销推进、撤销回退 ISSUED 或作废 VOID；trg_ar_invoice_immutable）',
                OLD.status, NEW.status;
        END IF;
        -- 仅 status 列可变更（voucher_id 为凭证联动回填列，豁免，gl_voucher_link），
        -- 其余列，金额/日期/客户/税号等开票后不可变
        v_only_status := (to_jsonb(NEW) - 'status' - 'voucher_id')
                         IS NOT DISTINCT FROM (to_jsonb(OLD) - 'status' - 'voucher_id');
        IF NOT v_only_status THEN
            RAISE EXCEPTION '应收发票已开票（status=%），禁止修改业务字段（仅可状态迁移与凭证联动回填；trg_ar_invoice_immutable）', OLD.status;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE OR REPLACE FUNCTION ap.fn_ap_invoice_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ap, pg_catalog
AS $$
DECLARE
    v_allowed_new_states text[] := ARRAY['PARTIAL','PAID','VOID'];
    v_allowed_partial_extra text[] := ARRAY['ISSUED'];
    v_only_status boolean;
BEGIN
    IF TG_OP = 'DELETE' AND OLD.status IN ('ISSUED','PARTIAL','PAID','VOID') THEN
        RAISE EXCEPTION '应付发票已开票/核销/作废（status=%），禁止删除（trg_ap_invoice_immutable）', OLD.status;
    END IF;
    IF OLD.status IN ('PAID','VOID') THEN
        RAISE EXCEPTION '应付发票已结清/作废（status=%），禁止修改（trg_ap_invoice_immutable）', OLD.status;
    END IF;
    IF OLD.status IN ('ISSUED','PARTIAL') THEN
        -- 状态机迁移白名单，ISSUED -> PARTIAL/PAID，核销推进/ VOID，作废，
        -- PARTIAL -> ISSUED，撤销核销回退/ PAID，核销结清/ VOID，作废，
        -- NEW.status = OLD.status，状态不变放行，凭证联动回填 voucher_id 即此形态
        -- （ISSUED->ISSUED），仅 status/voucher_id 可变更由下方 v_only_status 终审兜底
        IF NOT (
            (NEW.status = OLD.status) OR
            (OLD.status = 'ISSUED'  AND NEW.status = ANY (v_allowed_new_states)) OR
            (OLD.status = 'PARTIAL' AND (NEW.status = ANY (v_allowed_new_states)
                                         OR NEW.status = ANY (v_allowed_partial_extra)))
        ) THEN
            RAISE EXCEPTION '应付发票已开票（status=%），非法状态迁移 -> %（仅可 PARTIAL/PAID 核销推进、撤销回退 ISSUED 或作废 VOID；trg_ap_invoice_immutable）',
                OLD.status, NEW.status;
        END IF;
        -- 仅 status 列可变更（voucher_id 为凭证联动回填列，豁免，gl_voucher_link），
        -- 其余列，金额/日期/供应商/税额等开票后不可变
        v_only_status := (to_jsonb(NEW) - 'status' - 'voucher_id')
                         IS NOT DISTINCT FROM (to_jsonb(OLD) - 'status' - 'voucher_id');
        IF NOT v_only_status THEN
            RAISE EXCEPTION '应付发票已开票（status=%），禁止修改业务字段（仅可状态迁移与凭证联动回填；trg_ap_invoice_immutable）', OLD.status;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

DROP TRIGGER IF EXISTS trg_ar_invoice_immutable ON ar.invoices;
CREATE TRIGGER trg_ar_invoice_immutable
    BEFORE UPDATE OR DELETE ON ar.invoices
    FOR EACH ROW EXECUTE FUNCTION ar.fn_ar_invoice_immutable();

DROP TRIGGER IF EXISTS trg_ap_invoice_immutable ON ap.invoices;
CREATE TRIGGER trg_ap_invoice_immutable
    BEFORE UPDATE OR DELETE ON ap.invoices
    FOR EACH ROW EXECUTE FUNCTION ap.fn_ap_invoice_immutable();

-- cash.fn_auto_reconcile，银行对账自动匹配，同一银行账户内 金额相等 + 日期差≤N 天 的未匹配流水对，按 similarity，摘要 降序贪心配对，相似度 ≥N 自动 MATCHED（写 matched 互指、similarity_score、reconciled_at），<N 不自动配对，留待人工/模糊匹配，结果写 cash.reconcile_runs
-- 对账规则参数读 um.params（候选日期窗口 cash.reconcile.date.window.days 缺省 3 天 / 自动配对相似度阈值 cash.reconcile.similarity.auto 缺省 0.9，参数缺失/异常回落缺省，行为与现状一致）
-- 入口权限，SECURITY DEFINER + auth.require_permission('cash.reconcile.execute')（cron/维护会话 postgres 经维护直通）
CREATE OR REPLACE FUNCTION cash.fn_auto_reconcile(p_company_id uuid DEFAULT NULL)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = cash, pg_catalog, public
AS $$
DECLARE
    v_auto        int := 0;
    v_trx         record;
    v_best        record;
    v_stmt        bigint;
    v_comp        bigint;
    v_comp2       uuid;
    v_date_window int := 3;
    v_sim_auto    numeric := 0.9;
    v_param       jsonb;
BEGIN
    PERFORM auth.require_permission('cash.reconcile.execute');
    -- 对账规则参数（业务规则可配，读取语义与 budget.fn_block_if_exceeded 一致，jsonb 数字或 {"v":n} 双形态）
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'cash.reconcile.date.window.days' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_date_window := v_param::int;
            ELSE
                v_date_window := COALESCE(NULLIF(v_param->>'v',''), '3')::int;
            END IF;
        END IF;
        SELECT value INTO v_param FROM um.params
         WHERE code = 'cash.reconcile.similarity.auto' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_sim_auto := v_param::numeric;
            ELSE
                v_sim_auto := COALESCE(NULLIF(v_param->>'v',''), '0.9')::numeric;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_date_window := 3; v_sim_auto := 0.9;     -- 参数表异常，回落现状缺省
    END;
    IF to_regclass('cash.transactions') IS NULL OR to_regclass('cash.bank_statement_lines') IS NULL THEN
        RAISE NOTICE 'fn_auto_reconcile: 资金表不存在，跳过';
        RETURN 0;
    END IF;

    FOR v_trx IN
        SELECT t.id, t.company_id, t.bank_account_id, t.trx_date, t.amount, t.summary
          FROM cash.transactions t
         WHERE t.match_status = 'UNMATCHED' AND t.deleted_at IS NULL
           AND (p_company_id IS NULL OR t.company_id = p_company_id)
         ORDER BY t.trx_date, t.id
    LOOP
        -- 候选，同账户、金额相等、日期差≤N 天（um.params cash.reconcile.date.window.days）、未匹配，按 similarity 降序取最优
        -- 加 % 下界过滤（默认 pg_trgm.similarity_threshold=0.3，走 ix_bsl_summary GIN trgm 索引），消除数据量增大后的逐笔 O(N) 全量候选扫描，相似度 <0.3 的候选与后续 ≥0.9 自动配对判定矛盾（0.9>0.3），语义无损，NULL 摘要双方 % 求值为 NULL 则不进候选，与原 similarity(NULL) 不配对行为一致
        -- 阈值登记联动，期望值由后端 yaml fms.db.trgm-similarity-threshold，默认 0.3单一来源登记，init/bootstrap_base.sql ALTER DATABASE SET 生效，调整仅影响候选集规模与索引利用，不改变自动配对 ≥0.9 判定
        SELECT bsl.id, bsl.statement_id,
               similarity(COALESCE(v_trx.summary,''), COALESCE(bsl.summary,'')) AS sim
          INTO v_best
          FROM cash.bank_statement_lines bsl
          JOIN cash.bank_statements bs ON bs.id = bsl.statement_id AND bs.deleted_at IS NULL
         WHERE bsl.match_status = 'UNMATCHED' AND bsl.deleted_at IS NULL
           AND bs.bank_account_id = v_trx.bank_account_id
           AND bs.company_id = v_trx.company_id
           AND bsl.amount = v_trx.amount
           AND abs(bsl.trx_date - v_trx.trx_date) <= v_date_window
           AND bsl.summary % v_trx.summary
         ORDER BY similarity(COALESCE(v_trx.summary,''), COALESCE(bsl.summary,'')) DESC,
                  abs(bsl.trx_date - v_trx.trx_date), bsl.id
         LIMIT 1;
        IF v_best.id IS NULL OR v_best.sim < v_sim_auto THEN
            CONTINUE;                              -- 无候选或相似度不足，不自动配对
        END IF;
        -- 原子配对，仅当双方仍为 UNMATCHED 时生效，防并发双配
        UPDATE cash.transactions t
           SET match_status = 'MATCHED',
               matched_line_id = v_best.id,
               similarity_score = round(v_best.sim::numeric, 4),
               reconciled_at = now(),
               updated_at = now()
         WHERE t.id = v_trx.id AND t.match_status = 'UNMATCHED';
        IF FOUND THEN
            UPDATE cash.bank_statement_lines bsl
               SET match_status = 'MATCHED',
                   matched_trx_id = v_trx.id,
                   similarity_score = round(v_best.sim::numeric, 4),
                   updated_at = now()
             WHERE bsl.id = v_best.id AND bsl.match_status = 'UNMATCHED';
            IF NOT FOUND THEN
                -- 行已被并发消费，回滚流水侧配对，保持双方一致
                UPDATE cash.transactions SET match_status = 'UNMATCHED',
                       matched_line_id = NULL, similarity_score = NULL,
                       reconciled_at = NULL, updated_at = now()
                 WHERE id = v_trx.id;
                CONTINUE;
            END IF;
            v_auto := v_auto + 1;
        END IF;
    END LOOP;

    -- 结账本次运行统计，含未匹配计数
    SELECT COALESCE(count(*), 0) INTO v_stmt
      FROM cash.bank_statement_lines bsl
      JOIN cash.bank_statements bs ON bs.id = bsl.statement_id
     WHERE bsl.match_status = 'UNMATCHED' AND bsl.deleted_at IS NULL
       AND bs.deleted_at IS NULL
       AND (p_company_id IS NULL OR bs.company_id = p_company_id);
    SELECT COALESCE(count(*), 0) INTO v_comp
      FROM cash.transactions
     WHERE match_status = 'UNMATCHED' AND deleted_at IS NULL
       AND (p_company_id IS NULL OR company_id = p_company_id);

    -- 运行统计归属公司，显式入参优先，否则取流水/对账单现存公司
    v_comp2 := COALESCE(p_company_id,
                        (SELECT company_id FROM cash.transactions
                          WHERE (p_company_id IS NULL OR company_id = p_company_id)
                          ORDER BY company_id LIMIT 1),
                        (SELECT company_id FROM cash.bank_statements bs
                          WHERE (p_company_id IS NULL OR bs.company_id = p_company_id)
                          ORDER BY company_id LIMIT 1));
    IF v_comp2 IS NOT NULL THEN
        INSERT INTO cash.reconcile_runs (company_id, period, run_at, auto_matched, manual_matched, diff_count)
        VALUES (v_comp2, to_char(now(), 'YYYY-MM'), now(), v_auto, 0, v_stmt + v_comp);
    END IF;

    RAISE NOTICE 'fn_auto_reconcile: 自动配对 % 对（剩余未匹配：流水 %，对账单 %）', v_auto, v_comp, v_stmt;
    RETURN v_auto;
END $$;
COMMENT ON FUNCTION cash.fn_auto_reconcile(uuid)
    IS '银行对账自动匹配：金额相等+日期差≤N 天（um.params cash.reconcile.date.window.days）+similarity 降序配对，sim≥N（um.params cash.reconcile.similarity.auto）自动 MATCHED，结果写 reconcile_runs';

-- 审计捕获触发器（audit.fn_audit_log 由 audit_base.sql 创建）
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ar.customers','ar.invoices','ar.receipts','ar.receipt_allocations',
        'ar.bad_debt_provision',
        'ap.suppliers','ap.invoices','ap.payments','ap.payment_allocations',
        'ap.reconciliations','ap.reconciliation_lines',
        'cash.bank_accounts','cash.transactions','cash.bank_statements',
        'cash.bank_statement_lines','cash.reconcile_runs']
    LOOP
        IF to_regclass(t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_audit ON %s',
                           split_part(t, '.', 2), t);
            EXECUTE format('CREATE TRIGGER trg_%s_audit AFTER INSERT OR UPDATE OR DELETE ON %s '
                           || 'FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log()',
                           split_part(t, '.', 2), t);
        END IF;
    END LOOP;
END $$;

-- ar.receipts 收款重复登记幂等防护，业务键部分唯一索引 uk_ar_receipt_dedup
-- （company_id, customer_id, amount, receipt_date, COALESCE(bank_account_id, ZERO)）
--， 同客户/同金额/同日期/同账户的收款单仅允许一张有效（deleted_at IS NULL），
--    重复登记 INSERT 触发 23505，GlobalExceptionHandler 全局映射 40900，
--    软删后的同键收款单不阻挡重新登记（部分索引 WHERE deleted_at IS NULL）。
-- 核销重复生效由 Service 层防护（同对同金额重复请求 40900 + 收款单剩余
-- 可核销额预检 selectAllocatedSumByReceipt），此处无相关 DDL。
-- 可重复执行（IF NOT EXISTS）。

CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_receipt_dedup ON ar.receipts
    (company_id, customer_id, amount, receipt_date,
     COALESCE(bank_account_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE deleted_at IS NULL;

-- 自检，索引存在性
SELECT indexname, indexdef
  FROM pg_indexes
 WHERE schemaname = 'ar' AND indexname = 'uk_ar_receipt_dedup';

-- 文件末尾自查
SELECT 'ar_ap_cash_base' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname IN ('ar','ap','cash'))        AS table_cnt,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname IN ('ar','ap','cash'))        AS policy_cnt,
       (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('ar','ap','cash') AND NOT t.tgisinternal) AS trigger_cnt;
