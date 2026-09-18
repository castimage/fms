-- FMS 财务系统（补齐缺失对象 + 分区注册，执行库，fms），① 7 个对象（um.login_log 按月分区 / um.api_keys / org.positions / org.employees / gl.voucher_status_history / log.access_logs 按天分区 / audit.safe_voucher_view）② 审计捕获触发器统一挂载 ③ 分区注册，audit.audit_log，按年与 log.app_logs，按天普通表转分区父表，带数据迁移，+ partman.create_parent 幂等注册 4 张表 + retention 保留策略

-- 全部 DDL 幂等，新业务表一律 FORCE rls，分区父表不使用部分索引（pg 不支持 partitioned index with WHERE clause）

-- C.1 数据迁移显式 id 直插（uuidv7 后无 identity 语义，无需 OVERRIDING SYSTEM VALUE），C.1.1 转换后 audit_log SELECT 策略按 init/audit_base.sql 已部署定义重建（has_role('fms_auditor') OR current_user='fms_admin'）并补回 GRANT，补 log/audit schema USAGE 授权，C.2 p_start_partition 取 min(occurred_at) 对齐分区边界防存量数据越界

SET ROLE fms_admin;

-- um.login_log，登录日志，按月分区父表，写入封闭，error_msg 即 fail_reason 列，locked_until 记录登录失败触发的账户锁定到期时间，C.2 分区注册前禁止 INSERT（分区父表无分区时插报 no partition of relation found）
CREATE TABLE IF NOT EXISTS um.login_log (
    id           uuid NOT NULL DEFAULT uuidv7(),
    user_id      uuid REFERENCES um.users(id),      -- 未知用户登录失败为 NULL
    login_name   varchar(64) NOT NULL,
    ip           inet,
    user_agent   text,
    success      boolean     NOT NULL,
    fail_reason  text,                                -- 失败原因（error_msg），密码错误/锁定/验证码等
    locked_until timestamptz,                         -- 锁定到期时间（失败策略写入，NULL=未锁定）
    occurred_at  timestamptz NOT NULL DEFAULT now(),  -- 分区键，月
    CONSTRAINT pk_login_log PRIMARY KEY (id, occurred_at)  -- 分区父表 PK 必须含分区键
) PARTITION BY RANGE (occurred_at);
-- 幂等补列，兼容先行建表未含扩展列的场景
ALTER TABLE um.login_log ADD COLUMN IF NOT EXISTS locked_until timestamptz;
-- 索引（分区父表不支持部分索引，全部不带 WHERE）
CREATE INDEX IF NOT EXISTS ix_login_log_user     ON um.login_log (user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_login_log_occurred ON um.login_log (occurred_at);  -- 分区键索引（partman 前置要求）

COMMENT ON TABLE um.login_log IS
'登录日志（仅追加，按月分区）：登录尝试记录（含失败原因/锁定到期）；RLS 仅本人可见 + fms_auditor 全读；写入经 auth.record_login（SECURITY DEFINER）';
COMMENT ON COLUMN um.login_log.fail_reason IS '登录失败原因（error_msg 口径）：密码错误/账户锁定/验证码错误等';
COMMENT ON COLUMN um.login_log.locked_until IS '登录失败触发的账户锁定到期时间（NULL=未锁定）';

-- 写入入口，登录发生在上下文注入之前，current_uid 恒 NULL 则必须经 SECURITY DEFINER 落库
CREATE OR REPLACE FUNCTION auth.record_login(
    p_user_id     uuid,
    p_login_name  text,
    p_ip          inet,
    p_user_agent  text,
    p_success     boolean,
    p_fail_reason text DEFAULT NULL)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = auth, public
AS $$
    INSERT INTO um.login_log (user_id, login_name, ip, user_agent, success, fail_reason)
    VALUES (p_user_id, p_login_name, p_ip, p_user_agent, p_success, p_fail_reason);
$$;
COMMENT ON FUNCTION auth.record_login(uuid, text, inet, text, boolean, text)
    IS '登录结果落库（SECURITY DEFINER：登录时无沙箱上下文，RLS 不可用）；成功/失败均记录';
-- 本文件在 init/grants_base.sql 之后执行，新建函数默认 PUBLIC EXECUTE 会残留（init/grants_base.sql 的 schema 级
-- REVOKE PUBLIC 早于本文件创建），须自收，与 grants_check 'SECURITY-DEFINER 清零' 断言对齐
REVOKE ALL ON FUNCTION auth.record_login(uuid, text, inet, text, boolean, text) FROM PUBLIC;
-- 自助改密，所有已登录角色，经本函数记 um.login_log（fail_reason='password_changed'），
-- 要求改密事件统一落登录日志，故补发三只读角色 EXECUTE
GRANT EXECUTE ON FUNCTION auth.record_login(uuid, text, inet, text, boolean, text)
    TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- rls，本人看自己，fms_auditor（SET ROLE 后）全读，写无策略，应用无 INSERT 权限 + FORCE rls 双封闭
ALTER TABLE um.login_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.login_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS login_log_select ON um.login_log;
CREATE POLICY login_log_select ON um.login_log
    FOR SELECT USING (auth.is_self(user_id)
                      OR auth.has_role('fms_auditor')
                      OR current_user = 'fms_admin');
GRANT SELECT ON um.login_log TO fms_operator, fms_viewer, fms_auditor;

-- um.api_keys（api 密钥，key_hash 盲索引，库内不存明文）
CREATE TABLE IF NOT EXISTS um.api_keys (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid         NOT NULL,
    user_id      uuid         NOT NULL REFERENCES um.users(id),
    name         varchar(64)  NOT NULL,
    key_prefix   varchar(16)  NOT NULL,               -- 明文前缀（fms_xxxx…）仅展示用
    key_hash     char(64)     NOT NULL,               -- 盲索引，hmac(key, blind_key, 'sha256')，等值查询不泄露明文
    scope        text         NOT NULL DEFAULT 'read',
    expires_at   timestamptz,
    revoked_at   timestamptz,                        -- 吊销时间戳（status=REVOKED 时写入）
    last_used_at timestamptz,
    status       varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','DISABLED','REVOKED')),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    deleted_at   timestamptz,
    CONSTRAINT uk_api_keys_hash UNIQUE (key_hash)
);
ALTER TABLE um.api_keys ADD COLUMN IF NOT EXISTS revoked_at timestamptz;  -- 幂等补列
CREATE INDEX IF NOT EXISTS ix_api_keys_user ON um.api_keys (user_id) WHERE deleted_at IS NULL;
COMMENT ON TABLE um.api_keys IS
'API 密钥（key_hash CHAR(64) 盲索引，库内不存明文）：签发时明文仅一次返回；吊销/禁用经状态位';
COMMENT ON COLUMN um.api_keys.key_prefix IS '明文前缀（fms_ 前 8 位），仅展示用';
COMMENT ON COLUMN um.api_keys.key_hash IS '盲索引：encode(hmac(key, blind_key, sha256), hex)——纯 digest 无防字典攻击能力；存量行明文未存无法迁移，须重新签发';
COMMENT ON COLUMN um.api_keys.revoked_at IS '吊销时间戳（status=REVOKED 时写入；NULL=未吊销）';

-- 签发入口，明文仅此一次返回，仅可为自己签发，维护会话可代签
CREATE OR REPLACE FUNCTION auth.issue_api_key(
    p_user_id    uuid,
    p_name       text,
    p_scope      text DEFAULT 'read',
    p_expires_at timestamptz DEFAULT NULL)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE v_key text;
BEGIN
    IF p_user_id IS NULL OR NULLIF(btrim(p_name), '') IS NULL THEN
        RAISE EXCEPTION 'issue_api_key: user_id 与 name 必填';
    END IF;
    IF NOT auth.is_self(p_user_id) AND session_user NOT IN ('fms_admin','postgres') THEN
        RAISE EXCEPTION 'issue_api_key: 仅可为自己签发';
    END IF;
    v_key := 'fms_' || encode(gen_random_bytes(32), 'hex');
    INSERT INTO um.api_keys (company_id, user_id, name, key_prefix, key_hash, scope, expires_at)
    VALUES ((SELECT company_id FROM um.users WHERE id = p_user_id),
            p_user_id, p_name, left(v_key, 8),
            encode(hmac(v_key,
                        (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'),
                        'sha256'), 'hex'), p_scope, p_expires_at);
    RETURN v_key;
END $$;
COMMENT ON FUNCTION auth.issue_api_key(uuid, text, text, timestamptz)
    IS 'API 密钥签发（SECURITY DEFINER）：随机 32 字节 + fms_ 前缀，库内只存 hmac(盲索引密钥) 盲索引；明文仅本次返回';
REVOKE ALL ON FUNCTION auth.issue_api_key(uuid, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.issue_api_key(uuid, text, text, timestamptz)
    TO fms_operator;

-- rls，本人可见自己密钥，写仅 fms_admin，签发经 SECURITY DEFINER 函数，吊销经管理通道
ALTER TABLE um.api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.api_keys FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS api_keys_select ON um.api_keys;
CREATE POLICY api_keys_select ON um.api_keys
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS api_keys_write ON um.api_keys;
CREATE POLICY api_keys_write ON um.api_keys
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');
GRANT SELECT ON um.api_keys TO fms_operator, fms_viewer;

-- org.positions（岗位定义，公司内 code 唯一，可挂部门/职级/排序）
CREATE TABLE IF NOT EXISTS org.positions (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid         NOT NULL REFERENCES org.companies(id),
    dept_id     uuid         REFERENCES org.departments(id),  -- 所属部门（NULL=全公司通用岗位）
    code        varchar(32)  NOT NULL,
    name        varchar(128) NOT NULL,
    description text,
    level       int          NOT NULL DEFAULT 1,       -- 职级，1 起
    sort_order  int          NOT NULL DEFAULT 0,       -- 同公司排序权重，小在前
    status      varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_positions_code UNIQUE (company_id, code)
);
-- 幂等补列，兼容先行建表未含扩展列的场景
ALTER TABLE org.positions ADD COLUMN IF NOT EXISTS dept_id    uuid REFERENCES org.departments(id);
ALTER TABLE org.positions ADD COLUMN IF NOT EXISTS level      int  NOT NULL DEFAULT 1;
ALTER TABLE org.positions ADD COLUMN IF NOT EXISTS sort_order int  NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_positions_company ON org.positions (company_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_positions_dept    ON org.positions (dept_id) WHERE deleted_at IS NULL;
COMMENT ON TABLE org.positions IS '岗位定义（公司内 code 唯一）：可挂部门（NULL=全公司）/职级/排序权重';

-- rls，公司范围内可读可维护，fms_operator 经沙箱上下文收敛到本公司
ALTER TABLE org.positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.positions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS positions_select ON org.positions;
CREATE POLICY positions_select ON org.positions
    FOR SELECT USING (deleted_at IS NULL AND auth.can_access_org(company_id));
DROP POLICY IF EXISTS positions_manage ON org.positions;
CREATE POLICY positions_manage ON org.positions
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS positions_update ON org.positions;
CREATE POLICY positions_update ON org.positions
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));
GRANT SELECT, INSERT, UPDATE ON org.positions TO fms_operator;
GRANT SELECT ON org.positions TO fms_viewer, fms_auditor, fms_report;

-- org.employees（员工档案，user_id 唯一关联 um.users，base_salary 敏感列不授权）
CREATE TABLE IF NOT EXISTS org.employees (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid         NOT NULL REFERENCES org.companies(id),
    user_id     uuid         NOT NULL REFERENCES um.users(id),
    dept_id     uuid         NOT NULL REFERENCES org.departments(id),
    position_id uuid         REFERENCES org.positions(id),
    emp_no      varchar(32),
    hire_date   date,
    base_salary numeric(18,2) NOT NULL DEFAULT 0,   -- 敏感列，薪资，operator/viewer/report 一律不授
    status      varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','LEAVE','TERMINATED')),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_employees_user UNIQUE (user_id),
    CONSTRAINT uk_employees_emp_no UNIQUE (company_id, emp_no)
);
CREATE INDEX IF NOT EXISTS ix_employees_dept ON org.employees (dept_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_employees_pos  ON org.employees (position_id) WHERE deleted_at IS NULL;
COMMENT ON TABLE org.employees IS
'员工档案（user_id 唯一关联 um.users；emp_no 公司内唯一）：base_salary 为敏感列，运行时角色一律不授';

-- rls，公司到部门/本人双层收敛（fail-closed，未注入上下文返回空集）
ALTER TABLE org.employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.employees FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS employees_select ON org.employees;
CREATE POLICY employees_select ON org.employees
    FOR SELECT USING (deleted_at IS NULL
        AND auth.can_access_org(company_id)
        AND (auth.can_access_dept(dept_id) OR auth.is_self(user_id)));   -- 公司到部门/本人收敛
DROP POLICY IF EXISTS employees_manage ON org.employees;
CREATE POLICY employees_manage ON org.employees
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS employees_update ON org.employees;
CREATE POLICY employees_update ON org.employees
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));

-- 列级授权，base_salary 仅 fms_admin 可见（pg 列级权限下未授权列查询即报错，属防旁路语义，应用须显式列清单，SELECT * 对未授权列报 permission denied）
GRANT SELECT (company_id, user_id, dept_id, position_id, emp_no, hire_date, status,
              created_at, updated_at, deleted_at)
    ON org.employees TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT INSERT (company_id, user_id, dept_id, position_id, emp_no, hire_date, status)
    ON org.employees TO fms_operator;
GRANT UPDATE (dept_id, position_id, emp_no, hire_date, status)
    ON org.employees TO fms_operator;

-- gl.voucher_status_history + 状态机写入触发器 trg_voucher_status_history，changed_by/changed_at 即 action_by/action_at 列，触发器为 SECURITY DEFINER，应用角色无本表 INSERT 权限且 FORCE rls 无写策略，历史写入只能经触发器以 fms_admin 身份完成
CREATE TABLE IF NOT EXISTS gl.voucher_status_history (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    voucher_id  uuid         NOT NULL REFERENCES gl.vouchers(id),
    from_status varchar(16),                          -- 创建时（INSERT）为 NULL
    to_status   varchar(16)  NOT NULL,
    action_by   uuid,                                 -- changed_by，状态变更人（fms.user_id 上下文）
    action_at   timestamptz  NOT NULL DEFAULT now(),  -- changed_at
    remark      text
);
CREATE INDEX IF NOT EXISTS ix_vsh_voucher ON gl.voucher_status_history (voucher_id, action_at DESC);
COMMENT ON TABLE gl.voucher_status_history IS
'凭证状态流转历史（仅追加）：INSERT/status 变化由 trg_voucher_status_history 自动记录；RLS 经凭证公司范围收敛';

CREATE OR REPLACE FUNCTION gl.fn_voucher_status_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, public
AS $$
DECLARE
    v_remark text;
BEGIN
    -- remark 支持事务级 guc fms.voucher_remark 透传（25 驳回，fn_reject_voucher 写原因），
    -- 未设置时 INSERT 记'凭证创建'，迁移记 NULL
    v_remark := NULLIF(current_setting('fms.voucher_remark', true), '');
    IF TG_OP = 'INSERT' THEN
        INSERT INTO gl.voucher_status_history (voucher_id, from_status, to_status, action_by, remark)
        VALUES (NEW.id, NULL, NEW.status, auth.current_uid(), COALESCE(v_remark, '凭证创建'));
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO gl.voucher_status_history (voucher_id, from_status, to_status, action_by, remark)
        VALUES (NEW.id, OLD.status, NEW.status, auth.current_uid(), v_remark);
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

DROP TRIGGER IF EXISTS trg_voucher_status_history ON gl.vouchers;
CREATE TRIGGER trg_voucher_status_history
    AFTER INSERT OR UPDATE OF status ON gl.vouchers
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_status_history();

-- rls，SELECT 经凭证公司范围可见，写仅经 SECURITY DEFINER 触发器，无写策略
ALTER TABLE gl.voucher_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.voucher_status_history FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS vsh_select ON gl.voucher_status_history;
CREATE POLICY vsh_select ON gl.voucher_status_history
    FOR SELECT USING (EXISTS (SELECT 1 FROM gl.vouchers v
                               WHERE v.id = voucher_id AND v.deleted_at IS NULL
                                 AND auth.can_access_org(v.company_id)));
GRANT SELECT ON gl.voucher_status_history TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- log.access_logs，访问日志，按天分区父表，仅追加，分区父表不能建部分索引（cannot create a partitioned index with a WHERE clause），写入仅经 SECURITY DEFINER 消费函数，分区注册见 C.2
CREATE TABLE IF NOT EXISTS log.access_logs (
    id              uuid NOT NULL DEFAULT uuidv7(),
    method          text NOT NULL,
    path            text NOT NULL,
    status_code     int,
    latency_ms      int,
    ip              inet,
    user_agent      text,
    user_id         uuid REFERENCES um.users(id),  -- 会话用户（匿名请求为 NULL）
    trace_id        text,
    request_headers jsonb,                           -- 请求头快照，敏感头脱敏后入库
    occurred_at     timestamptz NOT NULL DEFAULT now(),   -- 分区键，天
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_access_logs PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
-- 幂等补列，兼容先行建表未含扩展列的场景
ALTER TABLE log.access_logs ADD COLUMN IF NOT EXISTS user_id         uuid REFERENCES um.users(id);
ALTER TABLE log.access_logs ADD COLUMN IF NOT EXISTS request_headers jsonb;
CREATE INDEX IF NOT EXISTS ix_access_logs_trace    ON log.access_logs (trace_id);
CREATE INDEX IF NOT EXISTS ix_access_logs_path     ON log.access_logs (path, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_access_logs_occurred ON log.access_logs (occurred_at);  -- 分区键索引（partman 前置要求）
CREATE INDEX IF NOT EXISTS ix_access_logs_user     ON log.access_logs (user_id);
COMMENT ON TABLE log.access_logs IS
'访问日志（仅追加，按天分区）：网关/后端请求审计（trace_id 串联）；写入经 SECURITY DEFINER 消费函数';
COMMENT ON COLUMN log.access_logs.user_id IS '会话用户 id（网关注入；匿名请求为 NULL）';
COMMENT ON COLUMN log.access_logs.request_headers IS '请求头快照 jsonb（敏感头脱敏后入库）';

-- rls，仅已认证会话可读，表级授权仅 fms_auditor（L3，auditor/admin）
ALTER TABLE log.access_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE log.access_logs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS access_logs_select ON log.access_logs;
CREATE POLICY access_logs_select ON log.access_logs
    FOR SELECT USING (auth.current_uid() IS NOT NULL);
GRANT SELECT ON log.access_logs TO fms_auditor, fms_operator;      -- L3，仅 auditor/admin（admin 映射 fms_operator）
-- 补齐，init/rpt_log_base.sql文件未授予 log schema USAGE，无此授权上表 GRANT 无法生效
GRANT USAGE ON SCHEMA log TO fms_auditor;

-- audit.safe_voucher_view，安全屏障视图，对非授权角色屏蔽 cost_amount 等敏感列返回 NULL 而非报错，防旁路探测，security_invoker=on 使 gl.vouchers 行级策略按调用者身份生效，视图属主 fms_admin 为 SUPERUSER，不开启 invoker 将以属主执行绕过 rls 泄漏全量
CREATE OR REPLACE VIEW audit.safe_voucher_view
WITH (security_barrier) AS
SELECT v.id, v.company_id, v.period, v.voucher_no, v.voucher_date, v.status,
       v.source_type, v.source_id, v.is_private, v.dept_id, v.created_by,
       v.approved_by, v.approved_at, v.posted_by, v.posted_at,
       v.total_debit, v.total_credit, v.note, v.created_at, v.updated_at, v.deleted_at,
       NULL::numeric(18,2) AS cost_amount          -- 敏感列占位，恒为 NULL，成本/费用明细防旁路
  FROM gl.vouchers v;
ALTER VIEW audit.safe_voucher_view SET (security_invoker = on);

COMMENT ON VIEW audit.safe_voucher_view IS
'安全屏障视图（audit）：凭证非敏感列 + cost_amount 恒 NULL；security_invoker=on 使 gl.vouchers 行级策略生效';
GRANT SELECT ON audit.safe_voucher_view TO fms_auditor, fms_operator, fms_viewer;
-- 补齐，audit schema USAGE 授 operator/viewer，仅 USAGE，不含 audit_log 等对象的表级权限，无此授权将无法访问本视图
GRANT USAGE ON SCHEMA audit TO fms_operator, fms_viewer;

-- B.8 审计捕获触发器统一挂载（audit.fn_audit_log，init/audit_base.sql已建），挂 modules/user_direct_permissions，init/um_perm.sql已建则生效，/api_keys/positions/employees，不挂，本身即日志/历史，login_log / voucher_status_history / access_logs
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['um.modules','um.user_direct_permissions','um.api_keys',
                             'org.positions','org.employees']
    LOOP
        IF to_regclass(t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_audit ON %s', split_part(t, '.', 2), t);
            EXECUTE format('CREATE TRIGGER trg_%s_audit AFTER INSERT OR UPDATE OR DELETE ON %s '
                           || 'FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log()',
                           split_part(t, '.', 2), t);
        END IF;
    END LOOP;
END $$;

-- C.1 普通表转分区父表（audit.audit_log 按年 / log.app_logs 按天，带数据迁移），init/audit_base.sql/init/rpt_log_base.sql已部署版本为普通表（PK 仅 id），native 分区父表要求全部唯一约束含分区键，必须先转结构，幂等，pg_partitioned_table 判存，DO 块内 DDL+DML 同一事务中途失败整体回滚
-- 数据迁移显式保 id（uuidv7 后 id 为普通 DEFAULT，无 identity 语义），prev_hash/row_hash 原样复制不重算，新表未挂哈希触发器时插入，哈希链不断
-- 9.1 audit.audit_log，按年分区
DO $$
DECLARE
    v_ymin date;
    v_ymax date;
    v_y    date;
    v_part text;
BEGIN
    -- 嵌套 IF，表不存在时绝不执行 'audit.audit_log'::regclass 强转，防报错
    IF to_regclass('audit.audit_log') IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM pg_partitioned_table WHERE partrelid = 'audit.audit_log'::regclass) THEN
        -- 建分区父表（与 init/audit_base.sql 结构一致，PK 含分区键，actor_id/record_id 为 uuid）
        CREATE TABLE audit.audit_log_new (
            id           uuid NOT NULL DEFAULT uuidv7(),
            actor_id     uuid,
            action       text        NOT NULL,
            table_name   text        NOT NULL,
            record_id    uuid,
            old_values   jsonb,
            new_values   jsonb,
            ip           inet,
            request_id   text,
            occurred_at  timestamptz NOT NULL DEFAULT now(),
            prev_hash    char(64),
            row_hash     char(64),
            CONSTRAINT audit_log_new_pkey PRIMARY KEY (id, occurred_at)
        ) PARTITION BY RANGE (occurred_at);
        -- 按数据实际年份动态预建真实分区（命名与 C.2 partman create_parent 一致，audit_log_pYYYY0101，后续 create_parent 会跳过已存在分区）
        -- DEFAULT 分区承接存量行后 DETACH+DROP 会把数据随默认分区一起删除，故先建真实分区再直接迁移，INSERT 按 occurred_at 路由进对应年份分区，空表则建当年分区防 'no partition of relation found'
        SELECT date_trunc('year', min(occurred_at))::date,
               date_trunc('year', max(occurred_at))::date
          INTO v_ymin, v_ymax
          FROM audit.audit_log;
        IF v_ymin IS NULL THEN
            v_ymin := date_trunc('year', now())::date;
            v_ymax := v_ymin;
        END IF;
        v_y := v_ymin;
        WHILE v_y <= v_ymax LOOP
            v_part := 'audit_log_p' || to_char(v_y, 'YYYY') || '0101';
            EXECUTE 'CREATE TABLE audit.' || quote_ident(v_part)
                 || ' PARTITION OF audit.audit_log_new FOR VALUES FROM ('
                 || quote_literal(v_y)
                 || ') TO ('
                 || quote_literal(v_y + interval '1 year') || ')';
            v_y := v_y + interval '1 year';
        END LOOP;
        -- 数据迁移（行原样复制，哈希链不重算，新表此时未挂哈希触发器，显式 id 直接插入）
        INSERT INTO audit.audit_log_new (id, actor_id, action, table_name, record_id,
                                         old_values, new_values, ip, request_id,
                                         occurred_at, prev_hash, row_hash)
        SELECT id, actor_id, action, table_name, record_id, old_values, new_values,
               ip, request_id, occurred_at, prev_hash, row_hash
          FROM audit.audit_log;
        -- 换名（旧表随 DROP 释放约束/索引/触发器/策略名）
        DROP TABLE audit.audit_log;
        ALTER TABLE audit.audit_log_new RENAME TO audit_log;
        -- 重建索引/触发器/rls（策略按 init/audit_base.sql 已部署定义，保持转换前后 L3 语义一致）
        CREATE INDEX idx_audit_time  ON audit.audit_log (occurred_at DESC);
        CREATE INDEX idx_audit_table ON audit.audit_log (table_name, record_id);
        CREATE TRIGGER trg_audit_log_immutable BEFORE UPDATE OR DELETE ON audit.audit_log
            FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log_immutable();
        CREATE TRIGGER trg_audit_log_hash BEFORE INSERT ON audit.audit_log
            FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log_hash();
        ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
        ALTER TABLE audit.audit_log FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS audit_log_select ON audit.audit_log;
        CREATE POLICY audit_log_select ON audit.audit_log
            FOR SELECT USING (auth.has_role('fms_auditor') OR current_user = 'fms_admin');
        -- 授权/注释恢复（DROP 表丢失 GRANT 与 COMMENT）
        GRANT SELECT ON audit.audit_log TO fms_auditor;
        COMMENT ON TABLE audit.audit_log IS
        '审计日志（仅追加，按年分区）：prev_hash/row_hash 构成哈希链，篡改任何一行即断链';
        COMMENT ON COLUMN audit.audit_log.row_hash IS
        'row_hash = sha256(prev_hash || actor_id || action || table_name || record_id || old_values || new_values || occurred_at)';
    END IF;
    END IF;
END $$;

-- 9.2 log.app_logs，按天分区，同模式
DO $$
DECLARE
    v_dmin date;
    v_dmax date;
    v_d    date;
    v_part text;
BEGIN
    -- 嵌套 IF，表不存在时绝不执行 'log.app_logs'::regclass 强转，防报错
    IF to_regclass('log.app_logs') IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM pg_partitioned_table WHERE partrelid = 'log.app_logs'::regclass) THEN
        CREATE TABLE log.app_logs_new (
            id          uuid NOT NULL DEFAULT uuidv7(),
            level       text        NOT NULL DEFAULT 'INFO',
            logger      text        NOT NULL DEFAULT '',
            message     text        NOT NULL,
            context     jsonb,
            occurred_at timestamptz NOT NULL DEFAULT now(),
            created_at  timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT app_logs_new_pkey PRIMARY KEY (id, occurred_at),
            CONSTRAINT ck_app_logs_new_level CHECK (level IN ('DEBUG','INFO','WARN','ERROR'))
        ) PARTITION BY RANGE (occurred_at);
        -- 按数据实际日期动态预建真实分区（命名与 C.2 partman create_parent 一致，app_logs_pYYYYMMDD），DEFAULT 分区承接后 DETACH+DROP 会随分区删除数据，故先建真实分区再直接迁移，空表则建当日分区
        SELECT date_trunc('day', min(occurred_at))::date,
               date_trunc('day', max(occurred_at))::date
          INTO v_dmin, v_dmax
          FROM log.app_logs;
        IF v_dmin IS NULL THEN
            v_dmin := date_trunc('day', now())::date;
            v_dmax := v_dmin;
        END IF;
        v_d := v_dmin;
        WHILE v_d <= v_dmax LOOP
            v_part := 'app_logs_p' || to_char(v_d, 'YYYYMMDD');
            EXECUTE 'CREATE TABLE log.' || quote_ident(v_part)
                 || ' PARTITION OF log.app_logs_new FOR VALUES FROM ('
                 || quote_literal(v_d)
                 || ') TO ('
                 || quote_literal(v_d + 1) || ')';
            v_d := v_d + 1;
        END LOOP;
        INSERT INTO log.app_logs_new (id, level, logger, message, context, occurred_at, created_at)
        SELECT id, level, logger, message, context, occurred_at, created_at FROM log.app_logs;
        DROP TABLE log.app_logs;
        ALTER TABLE log.app_logs_new RENAME TO app_logs;
        CREATE INDEX idx_app_logs_occurred ON log.app_logs (occurred_at);
        ALTER TABLE log.app_logs ENABLE ROW LEVEL SECURITY;
        ALTER TABLE log.app_logs FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS log_app_logs_select ON log.app_logs;
        CREATE POLICY log_app_logs_select ON log.app_logs
            FOR SELECT USING (auth.current_uid() IS NOT NULL);
        COMMENT ON TABLE log.app_logs IS
        '应用日志（结构化，仅追加，按天分区）。写入仅经 SECURITY DEFINER 消费函数/超级用户，FORCE RLS 仅 SELECT 策略';
        -- 授权恢复（DROP 表丢失 GRANT 与 COMMENT，与 9.1 audit_log 同模式），
        -- init/grants_base.sql 在本文件之前执行，其 GRANT SELECT ON log.app_logs 已被上方
        -- DROP TABLE log.app_logs 抹除（relacl 归 NULL），此处必须补回，否则
        -- verify/grants_check.sql 'grants_base-log.* auditor 只读' 判 FAIL
        GRANT SELECT ON log.app_logs TO fms_auditor, fms_operator;   -- fms_operator，admin 的 pg 职责角色，安全中心 app-logs 读
        -- 分区级授权补齐，本块 WHILE 循环内 CREATE TABLE ... PARTITION OF 创建的分区
        -- 不继承父表 GRANT（relacl 全 NULL），且 pg 对分区父表的 GRANT 不向下传播到
        -- 已存在分区，对 log schema 内全部表，含全部既有分区统一补授，幂等可重跑
        GRANT SELECT ON ALL TABLES IN SCHEMA log TO fms_auditor, fms_operator;   -- fms_operator，admin 的 pg 职责角色
    END IF;
    END IF;
END $$;

-- C.2 partman.create_parent 幂等注册（part_config 判存），um.login_log 按月 / audit.audit_log 按年 / log.app_logs 按天，分区自动创建/清理由 init/queues_cron.sql文件已注册的 partman-maintenance cron（每日 init/audit_base.sql:00 CALL run_maintenance_proc）负责，本块只做一次性注册
-- 对含存量数据的表（audit_log/app_logs）p_start_partition 取 min(occurred_at) 并对齐分区边界，月/年首日、日防 partman 对 start 之前的存量数据报错，空表（login_log）保持起点，上月，兼容补录
DO $$
DECLARE
    v_start text;
    v_orphan boolean;
BEGIN
    -- 孤儿配置自愈，part_config 存在但父表不是分区表（或已不存在，如 um.login_log 被整体 DROP 重建）时，run_maintenance 报 'Child table given does not exist' 并跳过该分区集，先删孤儿配置再按正常路径 create_parent 重新注册
    FOREACH v_start IN ARRAY ARRAY['um.login_log','audit.audit_log','log.app_logs','log.access_logs'] LOOP
        IF EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = v_start)
           AND NOT EXISTS (SELECT 1
                             FROM pg_partitioned_table pt
                             JOIN pg_class c ON c.oid = pt.partrelid
                             JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname || '.' || c.relname = v_start) THEN
            DELETE FROM partman.part_config WHERE parent_table = v_start;
            RAISE NOTICE 'fill_missing: 删除孤儿 part_config（父表非分区表）：%', v_start;
        END IF;
    END LOOP;
    -- um.login_log，按月，预建 3 个月，含上月起点，兼容补录
    IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'um.login_log') THEN
        PERFORM partman.create_parent(
            p_parent_table := 'um.login_log',
            p_control      := 'occurred_at',
            p_type         := 'range',
            p_interval     := '1 month',
            p_premake      := 3,
            p_start_partition := to_char(date_trunc('month', now()) - interval '1 month', 'YYYY-MM-DD'));
    END IF;
    -- audit.audit_log，按年，预建 3 年，起点覆盖存量数据的最早年份
    IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'audit.audit_log') THEN
        SELECT to_char(COALESCE(date_trunc('year', (SELECT min(occurred_at) FROM audit.audit_log)),
                                date_trunc('year', now())), 'YYYY-MM-DD')
          INTO v_start;
        PERFORM partman.create_parent(
            p_parent_table := 'audit.audit_log',
            p_control      := 'occurred_at',
            p_type         := 'range',
            p_interval     := '1 year',
            p_premake      := 3,
            p_start_partition := v_start);
    END IF;
    -- log.app_logs，按天，预建 5 天，起点 = 存量最早日期前一天，防存量数据越界报错
    IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'log.app_logs') THEN
        SELECT to_char(date_trunc('day', COALESCE((SELECT min(occurred_at) FROM log.app_logs), now()))
                       - interval '1 day', 'YYYY-MM-DD')
          INTO v_start;
        PERFORM partman.create_parent(
            p_parent_table := 'log.app_logs',
            p_control      := 'occurred_at',
            p_type         := 'range',
            p_interval     := '1 day',
            p_premake      := 5,
            p_start_partition := v_start);
    END IF;
    -- log.access_logs，按天（分区父表无分区时 fn_consume_log_queues 落库必报 no partition of relation found）
    IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'log.access_logs') THEN
        PERFORM partman.create_parent(
            p_parent_table := 'log.access_logs',
            p_control      := 'occurred_at',
            p_type         := 'range',
            p_interval     := '1 day',
            p_premake      := 5,
            p_start_partition := to_char(date_trunc('day', now()) - interval '1 day', 'YYYY-MM-DD'));
    END IF;
END $$;

-- C.2b 分区自愈兜底，4 个分区父表无任何子分区时重建 part_config 并立即
-- 预建分区，不依赖 partman-maintenance cron（pgmq/pg_cron 缺失环境下每日维护任务未注册，
-- run_maintenance 永不执行，且父表零分区时 run_maintenance 报 'Child table given does not
-- exist' 永久跳过该分区集）。um.login_log 无分区时登录写入必报
-- 'no partition of relation found'（http 500/50000，登录流程整体不可用），本块保证
-- init 完成后分区立即存在，登录/访问日志写入不受 cron 影响，已存在分区则幂等跳过。
DO $$
DECLARE
    v_parent text;
    v_part_cnt int;
BEGIN
    FOREACH v_parent IN ARRAY ARRAY['um.login_log','audit.audit_log','log.app_logs','log.access_logs'] LOOP
        IF to_regclass(v_parent) IS NULL THEN
            CONTINUE;
        END IF;
        SELECT count(*) INTO v_part_cnt
          FROM pg_inherits i
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname || '.' || p.relname = v_parent;
        IF v_part_cnt = 0 THEN
            -- 孤儿 part_config 先清理，含父表为分区表但子分区被清空的场景
            DELETE FROM partman.part_config WHERE parent_table = v_parent;
            IF v_parent = 'um.login_log' THEN
                PERFORM partman.create_parent(
                    p_parent_table := v_parent, p_control := 'occurred_at', p_type := 'range',
                    p_interval := '1 month', p_premake := 3,
                    p_start_partition := to_char(date_trunc('month', now()) - interval '1 month', 'YYYY-MM-DD'));
            ELSIF v_parent = 'audit.audit_log' THEN
                PERFORM partman.create_parent(
                    p_parent_table := v_parent, p_control := 'occurred_at', p_type := 'range',
                    p_interval := '1 year', p_premake := 3,
                    p_start_partition := to_char(COALESCE(date_trunc('year', (SELECT min(occurred_at) FROM audit.audit_log)),
                                                          date_trunc('year', now())), 'YYYY-MM-DD'));
            ELSIF v_parent = 'log.app_logs' THEN
                PERFORM partman.create_parent(
                    p_parent_table := v_parent, p_control := 'occurred_at', p_type := 'range',
                    p_interval := '1 day', p_premake := 5,
                    p_start_partition := to_char(date_trunc('day', COALESCE((SELECT min(occurred_at) FROM log.app_logs), now()))
                                                 - interval '1 day', 'YYYY-MM-DD'));
            ELSE
                PERFORM partman.create_parent(
                    p_parent_table := v_parent, p_control := 'occurred_at', p_type := 'range',
                    p_interval := '1 day', p_premake := 5,
                    p_start_partition := to_char(date_trunc('day', now()) - interval '1 day', 'YYYY-MM-DD'));
            END IF;
            RAISE NOTICE 'C.2b: 分区父表 % 无子分区，已重建 part_config 并预建分区（登录/日志写入不再依赖 cron）', v_parent;
        END IF;
    END LOOP;
END $$;

-- retention 保留策略（幂等 UPDATE），part_config.retention 为最大保留期，retention_keep_table=false 时到期分区自动 DROP，run_maintenance_proc 只预建不删历史，无限增长分区将撑爆 catalog/磁盘，log 两表 90 天、login_log 1 年、audit_log 按审计合规期 7 年，由 partman-maintenance cron 每日清理
-- 保留期统一读 um.params（log.retention.days 缺省 90 天 / loginlog.retention.years 缺省 1 年 / audit.retention.years 缺省 7 年，
-- 本文件先于 init/grants_base.sql种子执行，参数缺失时回落缺省与现状等价，admin 调参后重跑本段即可同步 part_config）
-- inherit_privileges=true，partman 将父表（log.app_logs/log.access_logs）的 ACL 复制到模板表，
-- 后续 run_maintenance 新建分区自动继承 fms_auditor SELECT 授权（否则新分区 relacl 为 NULL，
-- grants_check 'log.* auditor 只读' 及审计链读取会因分区级缺权而失败）
UPDATE partman.part_config
   SET retention = make_interval(days => COALESCE(
                       (SELECT COALESCE(value #>> '{}', value->>'v')::int
                          FROM um.params WHERE code = 'log.retention.days' AND deleted_at IS NULL), 90)),
       retention_keep_table = false, retention_keep_index = false,
       inherit_privileges = true
 WHERE parent_table IN ('log.app_logs', 'log.access_logs');
UPDATE partman.part_config
   SET retention = make_interval(years => COALESCE(
                       (SELECT COALESCE(value #>> '{}', value->>'v')::int
                          FROM um.params WHERE code = 'loginlog.retention.years' AND deleted_at IS NULL), 1)),
       retention_keep_table = false, retention_keep_index = false
 WHERE parent_table = 'um.login_log';
UPDATE partman.part_config
   SET retention = make_interval(years => COALESCE(
                       (SELECT COALESCE(value #>> '{}', value->>'v')::int
                          FROM um.params WHERE code = 'audit.retention.years' AND deleted_at IS NULL), 7)),
       retention_keep_table = false, retention_keep_index = false
 WHERE parent_table = 'audit.audit_log';

-- B.9 um.users 敏感列补齐（MFA / 身份证），init/um_org_base.sql文件建表已含，此处幂等补列，兼容 init/um_perm.sql先行/存量库原地升级
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'users'
                      AND column_name = 'mfa_enabled') THEN
        ALTER TABLE um.users ADD COLUMN mfa_enabled boolean NOT NULL DEFAULT false;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'users'
                      AND column_name = 'mfa_secret_enc') THEN
        ALTER TABLE um.users ADD COLUMN mfa_secret_enc bytea;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'users'
                      AND column_name = 'id_card_enc') THEN
        ALTER TABLE um.users ADD COLUMN id_card_enc bytea;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'users'
                      AND column_name = 'id_card_hash') THEN
        ALTER TABLE um.users ADD COLUMN id_card_hash char(64);
    END IF;
END $$;

-- 软删除统一出口 auth.fn_soft_delete 由 init 基础文件定义，本文件不重复定义，避免双维护点，语义，PG15+ 新行可见性检查
-- 封死应用角色直接 UPDATE deleted_at 的路径，SECURITY DEFINER + 白名单 + can_access_org 行级收敛为软删除唯一合法出口

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1 确认本文件执行完整），期望 new_table_cnt=6、part_parent_cnt=4、part_cfg_cnt=4、part_retention_cnt=4、view_cnt=1、vsh_trg_cnt=1
SELECT 'fill_missing' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE (schemaname, tablename) IN
               (('um','login_log'),('um','api_keys'),('org','positions'),
                ('org','employees'),('gl','voucher_status_history'),('log','access_logs'))) AS new_table_cnt,
       (SELECT count(*) FROM pg_partitioned_table pt
         JOIN pg_class c ON c.oid = pt.partrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE (n.nspname, c.relname) IN
              (('um','login_log'),('log','access_logs'),('audit','audit_log'),('log','app_logs'))) AS part_parent_cnt,
       (SELECT count(*) FROM partman.part_config
         WHERE parent_table IN ('um.login_log','audit.audit_log','log.app_logs','log.access_logs')) AS part_cfg_cnt,
       (SELECT count(*) FROM partman.part_config
         WHERE parent_table IN ('um.login_log','audit.audit_log','log.app_logs','log.access_logs')
           AND retention IS NOT NULL AND NOT retention_keep_table) AS part_retention_cnt,
       (SELECT count(*) FROM pg_views WHERE schemaname = 'audit' AND viewname = 'safe_voucher_view') AS view_cnt,
       (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'gl' AND c.relname = 'vouchers' AND t.tgname = 'trg_voucher_status_history') AS vsh_trg_cnt;
