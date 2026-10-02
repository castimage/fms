-- FMS 财务系统（init/gl_base），gl 4 表（chart_accounts ltree 科目树/periods/vouchers/voucher_lines）+ 触发器（凭证平衡校验、期间开放校验、过账取号状态机、POSTED/REVERSED 不可变、分录末级科目、分录防篡改、通用 updated_at）+ rls + 授权 + 审计触发器挂载 + gl cron 任务函数

-- 主键 uuidv7（pg 18 原生，时间有序，ORDER BY id 语义不变），表 CREATE IF NOT EXISTS 幂等保留存量数据，重放不清空业务数据，fn_fx_adjustment 判存创建的回退占位（签名含 DEFAULT 'CNY'，与 init/gl_fx_rates.sql 真实现一致，重放时不覆盖真实现），cron 以 NULL 字面量调用（queues_cron.sql）

-- 文件末尾段，均幂等，
--   期间守卫触发器函数，反结账二次确认 + 已结账期间禁建凭证
--   voucher_lines 敏感列 cost_amount + 中文全文检索生成列 summary_tsv
--   凭证创建幂等键 idem_key + uk_voucher_idem / uk_voucher_line 部分唯一索引
--   chart_accounts 操作员写授权 + 写策略（4.3 can_access_org）
--   gl_lines_update 更新策略恢复，软删唯一出口为 auth.fn_soft_delete 闸口

SET ROLE fms_admin;

-- 通用 updated_at 触发器函数（gl/um/org 等多表复用）
CREATE OR REPLACE FUNCTION gl.fn_set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

-- gl.chart_accounts 会计科目表（ltree 科目树，seed_data 种子 ON CONFLICT (company_id,code)）
-- CREATE TABLE IF NOT EXISTS（存量保留，schema 演进由本文件末尾段的幂等 ALTER 承担）
CREATE TABLE IF NOT EXISTS gl.chart_accounts (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id          uuid        NOT NULL,
    code                varchar(32) NOT NULL,
    name                varchar(128) NOT NULL,
    path                ltree       NOT NULL,     -- 科目树编码，= 科目编码路径
    parent_id           uuid        REFERENCES gl.chart_accounts(id),
    level               int         NOT NULL DEFAULT 1,
    is_leaf             boolean     NOT NULL DEFAULT true,
    account_type        varchar(16) NOT NULL
                        CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    direction           varchar(8)  NOT NULL
                        CHECK (direction IN ('DEBIT','CREDIT')),
    is_foreign_currency boolean     NOT NULL DEFAULT false,
    currency            char(3),
    status              varchar(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','DISABLED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    CONSTRAINT uk_chart_account_code UNIQUE (company_id, code)
);
CREATE INDEX IF NOT EXISTS ix_accounts_path   ON gl.chart_accounts USING GIST (path);   -- <@ 子树
CREATE INDEX IF NOT EXISTS ix_accounts_parent ON gl.chart_accounts (parent_id) WHERE deleted_at IS NULL;

-- gl.periods 会计期间（seed_data 种子 ON CONFLICT (company_id,period)）
CREATE TABLE IF NOT EXISTS gl.periods (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL,
    period      char(7)     NOT NULL,             -- 'YYYY-MM'
    status      varchar(8)  NOT NULL DEFAULT 'OPEN'
                CHECK (status IN ('OPEN','CLOSED')),
    closed_by   uuid,
    closed_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_period UNIQUE (company_id, period)
);

-- gl.vouchers 凭证头，UNIQUE(company_id,period,voucher_no) 过账取号后期间内唯一，连续性兜底，状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、过账 POSTED、冲销 REVERSED，驳回 SUBMITTED 转 REJECTED（退回 REJECTED 转 DRAFT 后可修改重新提交），POSTED/REVERSED 不可变
CREATE TABLE IF NOT EXISTS gl.vouchers (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id    uuid        NOT NULL,
    period        char(7)     NOT NULL,
    voucher_no    integer,                        -- 过账取号（POSTED 时由触发器分配）
    voucher_date  date          NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'DRAFT'
                  CONSTRAINT vouchers_status_check
                  CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','POSTED','REVERSED','REJECTED')),
    source_type   varchar(32)   NOT NULL DEFAULT 'MANUAL',
    source_id     uuid,
    is_private    boolean       NOT NULL DEFAULT false,
    dept_id       uuid,                           -- 部门范围（rls 行级收敛用）
    created_by    uuid        NOT NULL,
    approved_by   uuid,
    approved_at   timestamptz,
    posted_by     uuid,
    posted_at     timestamptz,
    total_debit   numeric(18,2) NOT NULL DEFAULT 0,
    total_credit  numeric(18,2) NOT NULL DEFAULT 0,
    note          text,
    idem_key      varchar(64),                    -- 创建幂等键（同人+公司+期间+内容哈希，同 body 重复提交仅 1 张，uk_voucher_idem 兜底）
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    CONSTRAINT uk_voucher UNIQUE (company_id, period, voucher_no)
);
CREATE INDEX IF NOT EXISTS ix_voucher_period ON gl.vouchers (company_id, period, voucher_date);
-- 创建幂等唯一索引，部分索引，同创建人+公司+期间+内容哈希去重，重试幂等，软删行排除，删后重建允许，
-- 冲销凭证 idem_key 为 NULL 不参与，fn_reverse_voucher 不受影响，调汇/折旧等批次凭证显式置幂等键
-- （fx_adjustment:/fa_depreciation:+period）参与去重
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_idem ON gl.vouchers (company_id, period, created_by, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

-- gl.voucher_lines 凭证分录（UNIQUE(voucher_id,line_no)，单边分录校验）
CREATE TABLE IF NOT EXISTS gl.voucher_lines (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    voucher_id  uuid        NOT NULL REFERENCES gl.vouchers(id),
    line_no     int           NOT NULL,
    account_id  uuid        NOT NULL REFERENCES gl.chart_accounts(id),
    summary     text,
    debit       numeric(18,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit      numeric(18,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    currency    char(3)       NOT NULL DEFAULT 'CNY',
    dept_id     uuid,
    partner_id  uuid,
    company_id  uuid        NOT NULL,
    -- 敏感列，成本/费用金额，不向运行时角色授权（audit.safe_voucher_view 中恒 NULL 占位防旁路）
    cost_amount numeric(18,2) NOT NULL DEFAULT 0 CHECK (cost_amount >= 0),
    -- 摘要全文检索生成列（fms_zh 中文分词，to_tsvector('simple') 表达式索引对中文不生效）
    summary_tsv tsvector GENERATED ALWAYS AS
                (to_tsvector('fms_zh'::regconfig, COALESCE(summary, ''))) STORED,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT ck_line_single_side CHECK (debit = 0 OR credit = 0)
);
-- 分录行号唯一，部分唯一索引，仅有效行，软删+全量重插的更新路径（updateVoucher）复用原 line_no，
-- 全行 UNIQUE 约束会被软删行占键致 23505，更新恒 40900，部分索引排除软删行后语义不变
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_line ON gl.voucher_lines (voucher_id, line_no) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_line_voucher  ON gl.voucher_lines (voucher_id);
CREATE INDEX IF NOT EXISTS ix_line_account  ON gl.voucher_lines (account_id, company_id);
CREATE INDEX IF NOT EXISTS ix_line_summary_tsv ON gl.voucher_lines USING GIN (summary_tsv);
-- 废弃表达式索引清理，to_tsvector('simple') 索引与 fms_zh 生成列索引重复且对中文无效，每行 DML 双份写放大
DROP INDEX IF EXISTS gl.ix_line_summary;

-- 触发器函数
-- 凭证号取号（per-company 序列，nextval 串行取号），序列首值 = 该公司现有最大凭证号 + 1，兼容种子数据，uk_voucher 仍兜底唯一
CREATE OR REPLACE FUNCTION gl.fn_get_voucher_no(p_company_id uuid, p_period text)
RETURNS integer
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_seq text := 'voucher_no_seq_' || replace(p_company_id::text, '-', '');
    v_no  bigint;
BEGIN
    -- 幂等惰性建序列，首值对齐存量 MAX(voucher_no)+1，防与种子凭证号冲突
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'gl' AND c.relname = v_seq) THEN
        EXECUTE format('CREATE SEQUENCE gl.%I START %s',
                       v_seq,
                       (SELECT COALESCE(MAX(voucher_no), 0) + 1
                          FROM gl.vouchers
                         WHERE company_id = p_company_id AND deleted_at IS NULL));
    END IF;
    EXECUTE format('SELECT nextval(%L)', 'gl.' || v_seq) INTO v_no;
    RETURN v_no::integer;
END $$;

-- POSTED/REVERSED 凭证不可变（BEFORE UPDATE OR DELETE，第二层防线，覆盖超级用户绕过 rls 的场景，fn_reverse_voucher 互指冲销路径放行）
CREATE OR REPLACE FUNCTION gl.fn_voucher_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    IF OLD.status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION '凭证已过账/冲销（status=%），禁止修改或删除（trg_voucher_immutable）', OLD.status;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

-- 状态机校验（BEFORE UPDATE OF status / INSERT），提交/审批/过账时校验分录借贷平衡含总额一致性、期间 OPEN、过账取号（POSTED 且未取号时分配 voucher_no）

-- 行为与边界，INSERT 仅 DRAFT 豁免校验，草稿可暂存不平衡，非 DRAFT 直插即校验，UPDATE 仅在状态实际迁移，新旧不同，时校验，同状态字段修改放行

-- 迁移白名单（驳回扩展 SUBMITTED 转 REJECTED、REJECTED 转 DRAFT + 冲销反向凭证 DRAFT 转 POSTED 豁免），DRAFT 提交 SUBMITTED、SUBMITTED 转 APPROVED/REJECTED、APPROVED 过账 POSTED、POSTED 转 REVERSED（fn_reverse_voucher 受控路径）、REJECTED 转 DRAFT，退回，另有 fn_reverse_voucher 同事务 DRAFT 转 POSTED（reversal_of_voucher_id 非空互指豁免），其余跳变/回退一律 RAISE

-- 校验链与错误，分录借贷差非 0 或凭证头总额与分录合计不一致则 RAISE，迁移至 APPROVED/POSTED 时审核人必须与制单人不同（SoD 录入≠审核≠记账）则 RAISE，期间不存在或非 OPEN 则 RAISE（已结账期间禁止提交/过账，含直接 POSTED 的种子路径）
-- 过账取号，NEW.status=POSTED 且未取号时经 gl.fn_get_voucher_no 分配（per-company 序列串行取号，期间内连续 + uk_voucher 唯一约束兜底）并记 posted_at/posted_by
CREATE OR REPLACE FUNCTION gl.fn_voucher_submit_check()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_d             numeric;
    v_c             numeric;
    v_diff          numeric;
    v_period_status text;
BEGIN
    -- INSERT，DRAFT 豁免，草稿可暂存不平衡，非 DRAFT 直插即校验，种子/修复路径
    IF TG_OP = 'INSERT' THEN
        IF NEW.status = 'DRAFT' THEN
            RETURN NEW;
        END IF;
    ELSE
        IF NEW.status = OLD.status THEN
            RETURN NEW;                     -- 非状态迁移的字段修改放行
        END IF;
        -- 状态机迁移白名单（唯一合法迁移集合，驳回 SUBMITTED 转 REJECTED，退回 REJECTED 转 DRAFT，
        -- 冲销 POSTED 转 REVERSED 仅经 fn_reverse_voucher 受控路径，防状态回退/跳变）
        IF NOT (
                (OLD.status = 'DRAFT'     AND NEW.status = 'SUBMITTED')
             OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED'))
             OR (OLD.status = 'APPROVED'  AND NEW.status = 'POSTED')
             OR (OLD.status = 'POSTED'    AND NEW.status = 'REVERSED')
             OR (OLD.status = 'REJECTED'  AND NEW.status = 'DRAFT')
             -- fn_reverse_voucher 同事务内 DRAFT 建反向凭证头后直接置 POSTED，
             -- reversal_of_voucher_id 互指列非空即该受控路径独有特征（该列仅冲销函数写入，
             -- rls WITH CHECK 禁 POSTED + trg_voucher_immutable 互指校验双重把关，普通 api 无法伪造）
             OR (OLD.status = 'DRAFT'     AND NEW.status = 'POSTED'
                 AND NEW.reversal_of_voucher_id IS NOT NULL)
        ) THEN
            RAISE EXCEPTION '凭证状态机非法迁移：% -> %（仅允许 DRAFT->SUBMITTED->APPROVED->POSTED->REVERSED，驳回 SUBMITTED->REJECTED，退回 REJECTED->DRAFT，冲销反向凭证 DRAFT->POSTED 经 fn_reverse_voucher 互指豁免）',
                OLD.status, NEW.status;
        END IF;
        IF NEW.status = 'DRAFT' THEN
            RETURN NEW;                     -- 目标 DRAFT，退回，豁免平衡/期间校验，草稿可暂存不平衡
        END IF;
    END IF;

    -- 借贷平衡，分录合计为准，与凭证头总额一致性
    SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0) INTO v_d, v_c
      FROM gl.voucher_lines
     WHERE voucher_id = NEW.id AND deleted_at IS NULL;
    v_diff := v_d - v_c;
    IF v_diff <> 0 OR NEW.total_debit <> v_d OR NEW.total_credit <> v_c THEN
        RAISE EXCEPTION '凭证借贷不平衡：分录差 %，总借 %/%，总贷 %/%（状态机校验拒绝）',
            v_diff, NEW.total_debit, v_d, NEW.total_credit, v_c;
    END IF;

    -- 审核职责分离（SoD），迁移至 APPROVED（含直接 POSTED 的种子路径）时审核人必须与制单人不同（录入≠审核≠记账）
    IF NEW.status IN ('APPROVED','POSTED')
       AND NEW.approved_by IS NOT DISTINCT FROM NEW.created_by THEN
        RAISE EXCEPTION '凭证审核职责分离校验失败：审核人不得为制单人（approved_by=created_by=%），请由第二人审核（录入≠审核≠记账）',
            NEW.created_by;
    END IF;

    -- 期间开放校验
    SELECT status INTO v_period_status FROM gl.periods
     WHERE company_id = NEW.company_id AND period = NEW.period AND deleted_at IS NULL;
    IF v_period_status IS NULL OR v_period_status <> 'OPEN' THEN
        RAISE EXCEPTION '凭证期间 % 未开放或不存在（status=%），禁止提交/过账',
            NEW.period, COALESCE(v_period_status, '<缺失>');
    END IF;

    -- 过账取号（期间内连续，uk_voucher 兜底唯一性）
    IF NEW.status = 'POSTED' AND NEW.voucher_no IS NULL THEN
        NEW.voucher_no := gl.fn_get_voucher_no(NEW.company_id, NEW.period);
        NEW.posted_at  := now();
        NEW.posted_by  := NULLIF(current_setting('fms.user_id', true), '')::uuid;
    END IF;
    RETURN NEW;
END $$;

-- 期间前向锁定守卫，gl.periods 仅允许 OPEN 转 CLOSED 前向锁定，CLOSED 转 OPEN，反结账必须显式开启 fms.force_reopen 二次确认（fn_reopen_period 同款开关），超级用户直改期间同样被触发器拦截，兜底反结账仅能经函数把关
CREATE OR REPLACE FUNCTION gl.fn_period_guard()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    IF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
        IF COALESCE(NULLIF(current_setting('fms.force_reopen', true), ''), 'off') <> 'on' THEN
            RAISE EXCEPTION '期间 % 已结账，禁止直接反结账（trg_period_guard；请经 fn_reopen_period 并开启 fms.force_reopen 二次确认）',
                OLD.period;
        END IF;
    END IF;
    IF OLD.status = NEW.status AND OLD.period IS NOT DISTINCT FROM NEW.period THEN
        RETURN NEW;                              -- 非期间状态变更的字段修改放行
    END IF;
    RETURN NEW;
END $$;

-- 凭证期间守卫，凭证 INSERT（含 DRAFT）时期间 CLOSED 一律拒绝，已结账期间不得再建任何凭证，含 fms_admin 直插，防止结账后向已锁定期间注入凭证
CREATE OR REPLACE FUNCTION gl.fn_period_voucher_guard()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status FROM gl.periods
     WHERE company_id = NEW.company_id AND period = NEW.period AND deleted_at IS NULL;
    IF v_status = 'CLOSED' THEN
        RAISE EXCEPTION '凭证期间 % 已结账，禁止创建凭证（trg_period_voucher_guard；先反结账或调整期间）',
            NEW.period;
    END IF;
    RETURN NEW;
END $$;

-- 分录末级科目校验
CREATE OR REPLACE FUNCTION gl.fn_voucher_line_leaf_check()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_leaf boolean;
BEGIN
    SELECT is_leaf INTO v_leaf FROM gl.chart_accounts
     WHERE id = NEW.account_id AND deleted_at IS NULL;
    IF v_leaf IS NULL OR NOT v_leaf THEN
        RAISE EXCEPTION '科目 id=% 不存在或非末级科目，禁止录入分录', NEW.account_id;
    END IF;
    RETURN NEW;
END $$;

-- 分录防篡改（父凭证 POSTED/REVERSED 后禁改禁删）
CREATE OR REPLACE FUNCTION gl.fn_voucher_line_posted_freeze()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status FROM gl.vouchers
     WHERE id = OLD.voucher_id AND deleted_at IS NULL;
    IF v_status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION '凭证已过账/冲销（status=%），禁止修改或删除分录', v_status;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

-- 触发器实例（gl 4 表）
DROP TRIGGER IF EXISTS trg_voucher_immutable ON gl.vouchers;
CREATE TRIGGER trg_voucher_immutable
    BEFORE UPDATE OR DELETE ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_immutable();

DROP TRIGGER IF EXISTS trg_voucher_submit_check ON gl.vouchers;
CREATE TRIGGER trg_voucher_submit_check
    BEFORE UPDATE OF status ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_submit_check();

DROP TRIGGER IF EXISTS trg_voucher_submit_check_insert ON gl.vouchers;
CREATE TRIGGER trg_voucher_submit_check_insert
    BEFORE INSERT ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_submit_check();

DROP TRIGGER IF EXISTS trg_period_guard ON gl.periods;
CREATE TRIGGER trg_period_guard
    BEFORE UPDATE ON gl.periods
    FOR EACH ROW EXECUTE FUNCTION gl.fn_period_guard();

DROP TRIGGER IF EXISTS trg_period_voucher_guard ON gl.vouchers;
CREATE TRIGGER trg_period_voucher_guard
    BEFORE INSERT ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_period_voucher_guard();

DROP TRIGGER IF EXISTS trg_voucher_updated ON gl.vouchers;
CREATE TRIGGER trg_voucher_updated
    BEFORE UPDATE ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_voucher_line_leaf ON gl.voucher_lines;
CREATE TRIGGER trg_voucher_line_leaf
    BEFORE INSERT OR UPDATE ON gl.voucher_lines
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_line_leaf_check();

DROP TRIGGER IF EXISTS trg_voucher_line_posted_freeze ON gl.voucher_lines;
CREATE TRIGGER trg_voucher_line_posted_freeze
    BEFORE UPDATE OR DELETE ON gl.voucher_lines
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_line_posted_freeze();

DROP TRIGGER IF EXISTS trg_voucher_line_updated ON gl.voucher_lines;
CREATE TRIGGER trg_voucher_line_updated
    BEFORE UPDATE ON gl.voucher_lines
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_chart_accounts_updated ON gl.chart_accounts;
CREATE TRIGGER trg_chart_accounts_updated
    BEFORE UPDATE ON gl.chart_accounts
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_periods_updated ON gl.periods;
CREATE TRIGGER trg_periods_updated
    BEFORE UPDATE ON gl.periods
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

-- rls（FORCE，策略函数族 fail-closed），策略表达式经 auth.can_access_org(uuid)/can_access_dept/is_self 收敛（维护会话 session_user 短路恒真）
ALTER TABLE gl.chart_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.chart_accounts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS gl_accounts_select ON gl.chart_accounts;
CREATE POLICY gl_accounts_select ON gl.chart_accounts
    FOR SELECT USING (deleted_at IS NULL AND auth.can_access_org(company_id));
-- 写策略（4.3，insert/update WITH CHECK 同 SELECT can_access_org，fms_admin 经
-- auth.can_access_org session_user 短路恒真，维护/直通不受影响）
DROP POLICY IF EXISTS gl_accounts_insert ON gl.chart_accounts;
CREATE POLICY gl_accounts_insert ON gl.chart_accounts
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_accounts_update ON gl.chart_accounts;
CREATE POLICY gl_accounts_update ON gl.chart_accounts
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_accounts_delete ON gl.chart_accounts;
CREATE POLICY gl_accounts_delete ON gl.chart_accounts
    FOR DELETE USING (deleted_at IS NULL AND auth.can_access_org(company_id));

ALTER TABLE gl.periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.periods FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS gl_periods_select ON gl.periods;
CREATE POLICY gl_periods_select ON gl.periods
    FOR SELECT USING (deleted_at IS NULL AND auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_periods_write ON gl.periods;
CREATE POLICY gl_periods_write ON gl.periods
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE gl.vouchers ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.vouchers FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS gl_vouchers_select ON gl.vouchers;
CREATE POLICY gl_vouchers_select ON gl.vouchers
    FOR SELECT USING (deleted_at IS NULL
        AND auth.can_access_org(company_id)
        -- 未分配部门（dept_id IS NULL）的行按公司范围可见，
        -- 注意 INSERT...RETURNING 的行须同时满足本 SELECT 策略
        AND (auth.can_access_dept(dept_id) OR is_private OR dept_id IS NULL)
        AND (auth.is_self(created_by) OR status <> 'DRAFT'));
DROP POLICY IF EXISTS gl_vouchers_insert ON gl.vouchers;
CREATE POLICY gl_vouchers_insert ON gl.vouchers
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_vouchers_update ON gl.vouchers;
CREATE POLICY gl_vouchers_update ON gl.vouchers
    FOR UPDATE USING (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')
        AND deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (status NOT IN ('POSTED','REVERSED') AND auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_vouchers_delete ON gl.vouchers;
CREATE POLICY gl_vouchers_delete ON gl.vouchers
    FOR DELETE USING (status NOT IN ('POSTED','REVERSED')
        AND deleted_at IS NULL AND auth.can_access_org(company_id));

ALTER TABLE gl.voucher_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.voucher_lines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS gl_lines_select ON gl.voucher_lines;
CREATE POLICY gl_lines_select ON gl.voucher_lines
    FOR SELECT USING (deleted_at IS NULL AND auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_lines_insert ON gl.voucher_lines;
CREATE POLICY gl_lines_insert ON gl.voucher_lines
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_lines_update ON gl.voucher_lines;
CREATE POLICY gl_lines_update ON gl.voucher_lines
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_lines_delete ON gl.voucher_lines;
CREATE POLICY gl_lines_delete ON gl.voucher_lines
    FOR DELETE USING (deleted_at IS NULL AND auth.can_access_org(company_id));

-- 授权（fms_operator 执行业务 DML）
GRANT USAGE ON SCHEMA gl TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

GRANT SELECT, INSERT, UPDATE, DELETE ON gl.vouchers, gl.voucher_lines TO fms_operator;
GRANT SELECT ON gl.vouchers, gl.voucher_lines TO fms_viewer, fms_auditor;
GRANT SELECT (id, company_id, period, voucher_no, voucher_date, status, source_type,
              created_by, approved_by, approved_at, total_debit, total_credit,
              note, created_at, updated_at, deleted_at)
    ON gl.vouchers TO fms_report;
-- rpt 视图 security_invoker=on 后视图内以调用者身份访问基表，report 须持 voucher_lines 非敏感列 SELECT，cost_amount 敏感列不授
GRANT SELECT (id, voucher_id, line_no, account_id, summary, debit, credit, currency,
              dept_id, partner_id, company_id, created_at, updated_at, deleted_at)
    ON gl.voucher_lines TO fms_report;
GRANT SELECT ON gl.chart_accounts, gl.periods
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
-- 科目维护为操作员职责（Java requireWriteRole 允许 OPERATOR，仅 SELECT 会致 42501）
GRANT INSERT, UPDATE, DELETE ON gl.chart_accounts TO fms_operator;

-- 审计捕获触发器（audit.fn_audit_log 由 init/audit_base.sql 创建）
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['gl.chart_accounts','gl.periods',
                             'gl.vouchers','gl.voucher_lines']
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

-- gl cron 任务函数（queues_cron.sql 调度段 cron 命令惰性解析，运行时可用）
-- 凭证断号扫描（每日 06:00，queues_cron.sql），期间内 POSTED/REVERSED 凭证号缺口计数，终版另写 audit.record(voucher_gap) 留痕，见 init/gl_fx_rates.sql
CREATE OR REPLACE FUNCTION gl.fn_cron_voucher_number_check()
RETURNS int
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_gaps int := 0;
BEGIN
    SELECT count(*) INTO v_gaps
      FROM (SELECT company_id, period,
                   voucher_no - lag(voucher_no) OVER
                       (PARTITION BY company_id, period ORDER BY voucher_no) AS gap
              FROM gl.vouchers
             WHERE status IN ('POSTED','REVERSED') AND deleted_at IS NULL
               AND voucher_no IS NOT NULL) g
     WHERE g.gap > 1;
    RAISE NOTICE 'fn_cron_voucher_number_check: 断号缺口 % 处', v_gaps;
    RETURN v_gaps;
END $$;

-- 结账检查（每月 1 日 04:00），未过账/未审核计数汇总（p_company_id uuid，cron 以 NULL 字面量调用）
CREATE OR REPLACE FUNCTION gl.fn_close_check(p_company_id uuid, p_period text)
RETURNS int
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_cnt int;
BEGIN
    SELECT count(*) INTO v_cnt
      FROM gl.vouchers
     WHERE company_id = COALESCE(p_company_id, company_id)
       AND period = COALESCE(p_period, period)
       AND status IN ('DRAFT','SUBMITTED','APPROVED') AND deleted_at IS NULL;
    RAISE NOTICE 'fn_close_check: 期间 % 未完成凭证 % 张', COALESCE(p_period, '全部'), v_cnt;
    RETURN v_cnt;
END $$;

-- 期末调汇 cron 回退占位（占位实现，仅当真实现未部署时判存创建，返回 0 保证任务可执行，含 require_permission('gl.period.fx_adjustment') 闸口，cron 以 postgres 身份调用经维护直通放行）

-- 幂等要点，占位定义与真实现签名一致（含 DEFAULT 'CNY'，重放替换不报 'cannot remove
-- parameter defaults from existing function'），仅当函数不存在时创建占位，pg_proc 判存，
-- 不把已部署的真实现降级回占位，重放顺序 gl_base 先于 gl_fx_rates，外层 DO 用
-- $guard$ 标签避免与内层函数体 $$ 嵌套美元引号冲突。
DO $guard$
BEGIN
    -- 判存依据，pronargs=3（上方 DROP 已清 bigint 签名，剩余 3 参签名即 uuid 版，
    -- 不能比较 pg_get_function_identity_arguments 的字面量，其返回含参数名
    -- 'p_company_id uuid, p_period text, p_base_currency text'，实测与 'uuid, text, text'
    -- 不相等导致判存失效重复建函数报 already exists）
    IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'gl' AND p.proname = 'fn_fx_adjustment'
                      AND p.pronargs = 3) THEN
        CREATE FUNCTION gl.fn_fx_adjustment(
            p_company_id    uuid,
            p_period        text,
            p_base_currency text DEFAULT 'CNY')
        RETURNS int
        LANGUAGE plpgsql
        STABLE
        SECURITY DEFINER
        SET search_path = gl, pg_catalog
        AS $$
        BEGIN
            PERFORM auth.require_permission('gl.period.fx_adjustment');
            RAISE NOTICE 'fn_fx_adjustment: 期间 % 调汇检查完成（占位实现）', COALESCE(p_period, '全部');
            RETURN 0;
        END $$;
    END IF;
END $guard$;

-- 期间守卫触发器函数（反结账二次确认 + 已结账期间禁建凭证，
-- 与上文期间前向锁定守卫、凭证期间守卫行为等价，幂等重定义）
CREATE OR REPLACE FUNCTION gl.fn_period_guard()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    IF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
        IF COALESCE(NULLIF(current_setting('fms.force_reopen', true), ''), 'off') <> 'on' THEN
            RAISE EXCEPTION '期间 % 已结账，禁止直接反结账（trg_period_guard；请经 fn_reopen_period 并开启 fms.force_reopen 二次确认）',
                OLD.period;
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION gl.fn_period_voucher_guard()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status FROM gl.periods
     WHERE company_id = NEW.company_id AND period = NEW.period AND deleted_at IS NULL;
    IF v_status = 'CLOSED' THEN
        RAISE EXCEPTION '凭证期间 % 已结账，禁止创建凭证（trg_period_voucher_guard；先反结账或调整期间）',
            NEW.period;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_period_guard ON gl.periods;
CREATE TRIGGER trg_period_guard
    BEFORE UPDATE ON gl.periods
    FOR EACH ROW EXECUTE FUNCTION gl.fn_period_guard();

DROP TRIGGER IF EXISTS trg_period_voucher_guard ON gl.vouchers;
CREATE TRIGGER trg_period_voucher_guard
    BEFORE INSERT ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_period_voucher_guard();

-- voucher_lines 敏感列 cost_amount + 中文全文检索生成列 summary_tsv + GIN 索引，
-- 建表已含同名列/索引，此处为幂等空操作
ALTER TABLE gl.voucher_lines ADD COLUMN IF NOT EXISTS cost_amount numeric(18,2) NOT NULL DEFAULT 0;
ALTER TABLE gl.voucher_lines ADD COLUMN IF NOT EXISTS summary_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('fms_zh'::regconfig, COALESCE(summary, ''))) STORED;
-- DROP INDEX 带 schema 限定，search_path 不含 gl 时未限定名解析不到
-- gl.voucher_lines 上的 ix_line_summary 索引，与 gl_fx_rates.sql uk_fx_rate_active 同问题
DROP INDEX IF EXISTS gl.ix_line_summary;
CREATE INDEX IF NOT EXISTS ix_line_summary_tsv ON gl.voucher_lines USING GIN (summary_tsv);

-- 凭证创建幂等键 idem_key + uk_voucher_idem 部分唯一索引，分录行号唯一为部分唯一索引
-- uk_voucher_line 排除软删行，更新路径可复用 line_no，建表已含，幂等空操作
ALTER TABLE gl.vouchers ADD COLUMN IF NOT EXISTS idem_key varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_idem ON gl.vouchers (company_id, period, created_by, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE gl.voucher_lines DROP CONSTRAINT IF EXISTS uk_voucher_line;
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_line ON gl.voucher_lines (voucher_id, line_no) WHERE deleted_at IS NULL;

-- 自查，幂等重跑后应全 true/1
SELECT 'FIX_GL_VOUCHER_IDEM' AS k,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema='gl' AND table_name='vouchers' AND column_name='idem_key') AS idem_col,
       (SELECT indexdef FROM pg_indexes WHERE schemaname='gl' AND indexname='uk_voucher_idem') LIKE '%deleted_at IS NULL%' AS idem_partial,
       (SELECT indexdef FROM pg_indexes WHERE schemaname='gl' AND indexname='uk_voucher_line') LIKE '%deleted_at IS NULL%' AS line_partial,
       (SELECT count(*) FROM pg_constraint WHERE conname='uk_voucher_line') AS old_constr_left;

-- chart_accounts 操作员写授权 + 写策略（4.3 can_access_org），与上文同名策略幂等重定义
GRANT INSERT, UPDATE, DELETE ON gl.chart_accounts TO fms_operator;

DROP POLICY IF EXISTS gl_accounts_insert ON gl.chart_accounts;
CREATE POLICY gl_accounts_insert ON gl.chart_accounts
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));

DROP POLICY IF EXISTS gl_accounts_update ON gl.chart_accounts;
CREATE POLICY gl_accounts_update ON gl.chart_accounts
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));

DROP POLICY IF EXISTS gl_accounts_delete ON gl.chart_accounts;
CREATE POLICY gl_accounts_delete ON gl.chart_accounts
    FOR DELETE USING (deleted_at IS NULL AND auth.can_access_org(company_id));

DROP POLICY IF EXISTS gl_accounts_write ON gl.chart_accounts;

-- 自查
SELECT 'PATCH_SELFCHECK' AS k,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'gl' AND tablename = 'chart_accounts') AS policy_cnt,
       (SELECT bool_and(privilege_type IN ('INSERT','UPDATE','DELETE'))
          FROM information_schema.role_table_grants
         WHERE table_schema = 'gl' AND table_name = 'chart_accounts'
           AND grantee = 'fms_operator') AS operator_write_granted;

-- gl_lines_update 更新策略 USING 含 deleted_at IS NULL，软删唯一出口为 auth.fn_soft_delete 闸口，
-- gl_vouchers_update 现行版本已含 REJECTED 状态，为超集
DROP POLICY IF EXISTS gl_lines_update ON gl.voucher_lines;
CREATE POLICY gl_lines_update ON gl.voucher_lines
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));

-- 文件末尾自查
SELECT 'gl_base' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'gl')                                               AS table_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'gl')                                               AS gl_fn_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'gl')              AS policy_cnt,
       (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'gl' AND NOT t.tgisinternal)                         AS trigger_cnt;

-- 唯一性由部分唯一索引 uk_voucher_line (WHERE deleted_at IS NULL) 承担，全行 UNIQUE 约束
-- 会被软删行占键，更新路径软删+全量重插复用原 line_no 恒 23505，存量库追加的
-- 全行约束幂等清理，seed_data 演示凭证 ON CONFLICT 为带谓词形态，匹配部分唯一索引
ALTER TABLE gl.voucher_lines DROP CONSTRAINT IF EXISTS uk_voucher_line_no;
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_line
    ON gl.voucher_lines (voucher_id, line_no) WHERE deleted_at IS NULL;
