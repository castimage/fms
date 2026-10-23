-- FMS 财务系统（init/um_org_base），auth.* 策略函数族（SECURITY DEFINER 读 fms.* guc，fail-closed）+ um 9 表（含 um.modules）+ org 2 表（ltree）+ 全表 FORCE rls + 授权，主键 uuidv7（pg 18 原生，时间有序）
-- 幂等，schema/表 CREATE IF NOT EXISTS，重放不清空既有数据，um.permissions 建表即含
-- module_id/identifier 等升级列，消除 seed_system 先于 um_perm 的列依赖
-- 部署入口为 deploy/init/fms_init.sh，本文件由白名单调度执行
-- 内容，存量重加密、密钥写入 sec.app_secrets、敏感参数移出 um.params、current_uid、
-- um.users MFA/身份证敏感列、软删除统一出口，auth.is_admin 判定 + admin rls 策略
-- （users/user_role/scope/companies/departments）+ fn_soft_delete（白名单含 um.roles/role_perm、范围放宽 is_admin）

SET ROLE fms_admin;

-- schema（auth 为函数专用 schema，sec 为密钥保护 schema，um/org 重建）
CREATE SCHEMA IF NOT EXISTS auth;
COMMENT ON SCHEMA auth IS 'RLS 统一策略函数族 + 上下文注入（SECURITY DEFINER 读 fms.* GUC）';

-- 密钥保护 schema，列加密主密钥/jwt 密钥等机密收敛到无任何角色授权的 sec.app_secrets，仅 fms_admin，超级用户与 SECURITY DEFINER 函数可读
CREATE SCHEMA IF NOT EXISTS sec AUTHORIZATION fms_admin;
COMMENT ON SCHEMA sec IS '机密参数保护 schema：列加密主密钥/JWT 密钥（仅 fms_admin 可读，无任何角色授权）';
CREATE TABLE IF NOT EXISTS sec.app_secrets (
    code       text        PRIMARY KEY,
    value      jsonb       NOT NULL,
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE sec.app_secrets IS
'应用机密（code 唯一）：pgcrypto.master_key（列加密主密钥）/ jwt.secret（JWT HMAC 密钥）/ pgcrypto.blind_key（盲索引 HMAC 密钥）；不对任何角色 GRANT（含 fms_app），仅 SECURITY DEFINER 函数以 fms_admin 身份读取；生产可替换为 KMS/外部密钥服务，本表为数据库内收敛的落地形态';
-- 显式声明，不向任何角色授权，含 PUBLIC 默认无权限，防止未来授权遗漏
REVOKE ALL ON TABLE sec.app_secrets FROM PUBLIC;

-- 盲索引用带密钥 hmac（encode(hmac(value, blind_key,'sha256'),'hex')），纯 digest 无防字典攻击能力，低熵卡面值可被持库只读者秒级离线爆破，密钥与密文同库存放属已知设计约束，生产可换外部 KMS 注入
-- 本段 blind_key 仅首写（DO NOTHING），重放不覆盖已部署密钥，覆盖后旧密文
-- （api_keys key_hash / id_card_hash / bank_accounts 等）全部失配不可解密，
-- 与 seed_system 对 jwt.secret 的防覆盖语义一致。
-- 注，防覆盖仅限本首写段，的 master_key/blind_key 写入为 DO UPDATE（重放收敛回
-- 本文件声明常量、仅 jwt.secret 被 WHERE 排除）
INSERT INTO sec.app_secrets (code, value, updated_by, updated_at) VALUES
    ('pgcrypto.blind_key', '{"v":"FMS_BLIND_2026_9c4e2f7a1b6d4c3e8f5a2b7d9c4e6f8a1b3d5c7e9f"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now())
ON CONFLICT (code) DO NOTHING;

-- CREATE SCHEMA IF NOT EXISTS + 建表 IF NOT EXISTS，重放不清空既有数据
-- （schema 演进由文件内幂等 ALTER 段与 um_perm.sql 补齐）
CREATE SCHEMA IF NOT EXISTS um AUTHORIZATION fms_admin;
COMMENT ON SCHEMA um IS '用户、角色、权限、上下文预载数据源、系统参数（9 表）';

CREATE SCHEMA IF NOT EXISTS org AUTHORIZATION fms_admin;
COMMENT ON SCHEMA org IS '公司、部门树（ltree）（2 表）';

-- um.modules 模块树（本文件建表/种子，先于 seed_system 执行，seed_system 的权限 INSERT
-- 经 um.modules JOIN 解析 module_id，um_perm.sql 内同名建表/种子为幂等空操作
-- （IF NOT EXISTS / ON CONFLICT DO NOTHING），存量库升级路径不受影响）
CREATE TABLE IF NOT EXISTS um.modules (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    identifier  varchar(64)  NOT NULL,             -- 唯一标识，'gl' / 'gl.voucher' / 'gl.voucher.approve' 风格
    name        varchar(128) NOT NULL,             -- 模块名，菜单名
    description text,                              -- 说明
    parent_id   uuid         REFERENCES um.modules(id),   -- 自引用，模块树/菜单层级
    is_enabled  boolean      NOT NULL DEFAULT true,
    is_system   boolean      NOT NULL DEFAULT false,      -- 系统内置，不可删
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_modules_identifier UNIQUE (identifier)
);  -- 模块树父行先落库再插子模块
CREATE INDEX IF NOT EXISTS ix_modules_parent ON um.modules (parent_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE um.modules IS
'功能模块树（parent_id 自引用、identifier 唯一）：权限归属域 + 菜单层级建模';

-- 模块树种子（13 个功能域根，12 业务功能域 + admin 系统支撑，显式 id 常量 …500-…50c）
INSERT INTO um.modules (id, identifier, name, description, parent_id, is_enabled, is_system) VALUES
    ('00000000-0000-7000-0000-000000000500', 'org',     '组织与权限', '公司/部门/岗位/员工/RBAC/数据范围', NULL, true, true),
    ('00000000-0000-7000-0000-000000000501', 'gl',      '总账',       '科目树/凭证/期间/期末',           NULL, true, true),
    ('00000000-0000-7000-0000-000000000502', 'ar',      '应收',       '客户/发票/收款/核销',             NULL, true, true),
    ('00000000-0000-7000-0000-000000000503', 'ap',      '应付',       '供应商/发票/付款/核销',           NULL, true, true),
    ('00000000-0000-7000-0000-000000000504', 'cash',    '资金',       '银行账户/流水/对账',              NULL, true, true),
    ('00000000-0000-7000-0000-000000000505', 'fa',      '固定资产',   '资产卡片/折旧',                   NULL, true, true),
    ('00000000-0000-7000-0000-000000000506', 'expense', '费用报销',   '报销单/审批/付款',                NULL, true, true),
    ('00000000-0000-7000-0000-000000000507', 'budget',  '预算',       '预算编制/调整/预警',              NULL, true, true),
    ('00000000-0000-7000-0000-000000000508', 'wf',      '审批流',     '流程定义/实例/任务',              NULL, true, true),
    ('00000000-0000-7000-0000-000000000509', 'rpt',     '报表',       '财务报表/多维透视/导出',          NULL, true, true),
    ('00000000-0000-7000-0000-00000000050a', 'audit',   '审计',       '操作审计/哈希链/登录审计',        NULL, true, true),
    ('00000000-0000-7000-0000-00000000050b', 'notify',  '通知',       '站内信/邮件/WebSocket',           NULL, true, true),
    ('00000000-0000-7000-0000-00000000050c', 'admin',   '系统管理',   '用户/角色/权限/模块/API 密钥/参数', NULL, true, true)
ON CONFLICT (identifier) DO NOTHING;

-- 2 级子模块（完整集 34 个，parent_id 按 identifier 前缀解析，显式 id 常量 …50d-…52e 按 VALUES 顺序）
INSERT INTO um.modules (id, identifier, name, description, parent_id, is_enabled, is_system)
SELECT s.id::uuid, s.identifier, s.name, s.description, mp.id, true, true
  FROM (VALUES
    ('00000000-0000-7000-0000-00000000050d','org.company','公司管理','多公司/多账套'),
    ('00000000-0000-7000-0000-00000000050e','org.department','部门管理','部门树 ltree'),
    ('00000000-0000-7000-0000-00000000050f','org.position','岗位管理','岗位定义'),
    ('00000000-0000-7000-0000-000000000510','org.employee','员工管理','员工档案（含薪资敏感列）'),
    ('00000000-0000-7000-0000-000000000511','org.user','用户管理','登录账号/状态/盲索引'),
    ('00000000-0000-7000-0000-000000000512','org.role','角色管理','RBAC 角色'),
    ('00000000-0000-7000-0000-000000000513','gl.voucher','记账凭证','制单/审核/过账/冲销'),
    ('00000000-0000-7000-0000-000000000514','gl.chart_account','会计科目','科目树 ltree'),
    ('00000000-0000-7000-0000-000000000515','gl.period','会计期间','期间开关/期末处理'),
    ('00000000-0000-7000-0000-000000000516','ar.customer','客户档案','信用/账龄'),
    ('00000000-0000-7000-0000-000000000517','ar.invoice','销售发票','开票/核销'),
    ('00000000-0000-7000-0000-000000000518','ar.receipt','收款登记','收款/核销'),
    ('00000000-0000-7000-0000-000000000519','ap.supplier','供应商档案','对账'),
    ('00000000-0000-7000-0000-00000000051a','ap.invoice','采购发票','付款计划'),
    ('00000000-0000-7000-0000-00000000051b','ap.payment','付款执行','付款申请/执行'),
    ('00000000-0000-7000-0000-00000000051c','cash.bank_account','银行账户','列加密/盲索引'),
    ('00000000-0000-7000-0000-00000000051d','cash.transaction','资金流水','收付款'),
    ('00000000-0000-7000-0000-00000000051e','cash.reconcile','银行对账','自动/人工对账'),
    ('00000000-0000-7000-0000-00000000051f','fa.asset','资产卡片','原值/折旧方法'),
    ('00000000-0000-7000-0000-000000000520','fa.depreciation','折旧计提','月末批量'),
    ('00000000-0000-7000-0000-000000000521','expense.reimbursement','费用报销','多明细/发票'),
    ('00000000-0000-7000-0000-000000000522','budget.plan','预算编制','科目×部门×期间'),
    ('00000000-0000-7000-0000-000000000523','budget.adjustment','预算调整','审批'),
    ('00000000-0000-7000-0000-000000000524','wf.definition','流程定义','节点/会签/或签'),
    ('00000000-0000-7000-0000-000000000525','wf.instance','流程实例','待办/催办'),
    ('00000000-0000-7000-0000-000000000526','rpt.statement','财务报表','三大报表/往来分析'),
    ('00000000-0000-7000-0000-000000000527','audit.log','审计日志','查询/导出/链校验'),
    ('00000000-0000-7000-0000-000000000528','notify.message','通知消息','站内信/邮件/WS'),
    ('00000000-0000-7000-0000-000000000529','admin.user','系统用户','账号管理'),
    ('00000000-0000-7000-0000-00000000052a','admin.role','系统角色','角色授权'),
    ('00000000-0000-7000-0000-00000000052b','admin.permission','权限点','权限定义/依赖'),
    ('00000000-0000-7000-0000-00000000052c','admin.module','功能模块','模块树维护'),
    ('00000000-0000-7000-0000-00000000052d','admin.api_key','API 密钥','签发/吊销'),
    ('00000000-0000-7000-0000-00000000052e','admin.param','系统参数','参数维护')
  ) AS s(id, identifier, name, description)
  JOIN um.modules mp ON mp.identifier = split_part(s.identifier, '.', 1)
 WHERE NOT EXISTS (SELECT 1 FROM um.modules x WHERE x.identifier = s.identifier)
ON CONFLICT (identifier) DO NOTHING;

-- auth.* 策略函数族（SECURITY DEFINER 属主 fms_admin，GRANT 见本文件授权段（L683 起））
-- 全部 fail-closed，guc 缺失/非法则 NULL/false，绝不 RAISE 阻断 SELECT，fms.user_id/org_ids/dept_ids 均为 canonical 小写 uuid 文本，经 auth.set_session 或验证脚本字面量注入
-- 当前会话用户 id（安全版，上下文缺失/非法/用户不存在返回 NULL）
-- 全库 rls 策略的身份来源，读 fms.user_id guc（后端每请求经 auth.set_session 注入，验证脚本以字面量注入），函数无参数、STABLE，可被任何策略表达式直接调用而无需额外上下文
-- 行为，guc 缺失/为空/非 canonical uuid，8-4-4-4-12 十六进制则 NULL，fail-closed 不抛错，策略视行不可见，格式合法但用户已软删或状态非 ACTIVE 则 NULL，LOCKED/DISABLED 账户经伪造 guc 在 rls 层即失效，与 set_session 的 ACTIVE 语义一致
-- fms_admin 会话未注入 guc 同样返回 NULL，管理放行一律经 session_user/current_user 短路判定而非本函数
-- 调用约定，所有 rls 策略（含 is_self/can_access_dept 的内部 EXISTS）均以本函数为身份唯一来源，禁止直接读 guc，格式与 ACTIVE 双校验集中在此一处，防策略表达式各自解析造成判定口径漂移
CREATE OR REPLACE FUNCTION auth.current_uid()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_text text;
    v_uid  uuid;
BEGIN
    v_text := NULLIF(current_setting('fms.user_id', true), '');
    IF v_text IS NULL OR v_text !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        RETURN NULL;                              -- fail-closed，不抛错
    END IF;
    v_uid := v_text::uuid;
    -- 二次校验 ACTIVE，LOCKED/DISABLED 账户经伪造 guc 复活即在 rls 层失效（与 auth.set_session 语义一致）
    IF NOT EXISTS (SELECT 1 FROM um.users
                    WHERE id = v_uid AND deleted_at IS NULL AND status = 'ACTIVE') THEN
        RETURN NULL;
    END IF;
    RETURN v_uid;
END $$;

-- 公司数据范围（读 fms.org_ids 逗号分隔集合，fms_admin 短路放行）
-- rls 行级收敛核心判定，gl/ar/ap/cash/org 等业务表的公司范围策略一律经此函数
-- 参数，p_org_id 目标行所属公司 id，策略表达式以行值传入
-- 行为，p_org_id NULL 或 guc 缺失则 false，未注入上下文不可见，fail-closed，否则按 p_org_id::text 精确匹配逗号分隔集合，canonical 小写 uuid 文本，与 set_session 的 uuid::text 输出恒一致
-- 短路判定必须用 session_user 而非 current_user，SECURITY DEFINER 内 current_user 恒为属主 fms_admin，用它短路会把 SET ROLE fms_operator 的普通会话也恒真放行，fms_admin（维护/物化刷新/cron）直通
-- 语义，精确匹配，非前缀/包含，集合由 set_session 以 uuid 文本写入，行值同样转 uuid 文本比较，天然规范化无大小写/格式歧义，未授权公司则 false 行不可见
CREATE OR REPLACE FUNCTION auth.can_access_org(p_org_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_ids text;
BEGIN
    -- 短路判定必须用 session_user，SECURITY DEFINER 内 current_user 恒为属主 fms_admin，恒真短路会把 SET ROLE fms_operator 的普通会话也放行
    IF session_user = 'fms_admin' THEN
        RETURN true;                              -- 维护/物化刷新/cron 短路
    END IF;
    IF p_org_id IS NULL THEN
        RETURN false;
    END IF;
    v_ids := NULLIF(current_setting('fms.org_ids', true), '');
    IF v_ids IS NULL THEN
        RETURN false;                             -- 未注入上下文，不可见
    END IF;
    RETURN p_org_id::text = ANY (string_to_array(v_ids, ','));
END $$;

-- 部门数据范围（读 fms.dept_ids），直接成员命中，或 include_subtree=true 的授予部门存在后代部门 d（d.path <@ g.path，GIST 索引加速），部门管理员看其子树的能力，SECURITY DEFINER 绕过 rls，两处查询均走既有索引，失败静默
-- 参数，p_dept_id 目标行所属部门 id，策略表达式以行值传入
-- 行为，fms.dept_ids 直接包含 p_dept_id 则 true，否则查 um.user_dept_scope 中当前用户 include_subtree=true 且未软删的授予部门（g 与目标部门 d 均须未软删），目标部门是任一授予部门的 ltree 子树节点（d.path <@ g.path）则 true
-- p_dept_id NULL 则 false，未注入身份时 auth.current_uid 为 NULL，EXISTS 恒 false（fail-closed），与 can_access_org 组合使用（vouchers 等表按 org 与 dept 双收敛）
-- 语义，两级判定叠加，直接授予（fms.dept_ids 快照，set_session 预载）覆盖普通成员，scope 表的 include_subtree 授予覆盖部门管理员（子树含后代，ltree <@ 语义），快照与 scope 表任一命中即放行，均未命中则行不可见
CREATE OR REPLACE FUNCTION auth.can_access_dept(p_dept_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_ids text;
BEGIN
    IF session_user = 'fms_admin' THEN
        RETURN true;
    END IF;
    IF p_dept_id IS NULL THEN
        RETURN false;
    END IF;
    v_ids := NULLIF(current_setting('fms.dept_ids', true), '');
    IF v_ids IS NOT NULL AND p_dept_id::text = ANY (string_to_array(v_ids, ',')) THEN
        RETURN true;                              -- 直接授予部门
    END IF;
    RETURN EXISTS (
        SELECT 1
          FROM um.user_dept_scope s
          JOIN org.departments g ON g.id = s.dept_id AND g.deleted_at IS NULL
          JOIN org.departments d ON d.id = p_dept_id AND d.deleted_at IS NULL
         WHERE s.user_id = auth.current_uid()
           AND s.deleted_at IS NULL
           AND s.include_subtree
           AND d.path <@ g.path);                 -- 目标部门是授予部门的子树节点
END $$;

-- 本人数据判定，恒返回 true 或 false，不产生 NULL
-- 会话标识缺失时直接比较将得到 NULL，而调用方普遍写作取反后拒绝，NULL 使该条件不成立即放行，
-- 守卫被穿透，故此处显式判空并落在 false，缺失即判非本人，调用点随之收敛为拒绝
CREATE OR REPLACE FUNCTION auth.is_self(p_owner_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
BEGIN
    IF session_user = 'fms_admin' THEN
        RETURN true;
    END IF;
    IF p_owner_id IS NULL OR auth.current_uid() IS NULL THEN
        RETURN false;
    END IF;
    RETURN p_owner_id = auth.current_uid();
END $$;

-- 角色判定（audit 等表的 SELECT 策略使用），普通函数（非 DEFINER）+ current_user + 'MEMBER'，沙箱模型下 INHERIT FALSE 使 'USAGE' 恒为 false，而 SET ROLE fms_auditor 后 current_user=fms_auditor 则 MEMBER 命中放行，未 SET ROLE 的 fms_app 直连由表级权限拦截，职责隔离精确
CREATE OR REPLACE FUNCTION auth.has_role(p_role text)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SET search_path = auth, public
AS $$
BEGIN
    IF current_user = 'fms_admin' THEN
        RETURN true;
    END IF;
    RETURN p_role IS NOT NULL AND pg_has_role(current_user, p_role, 'MEMBER');
END $$;

-- 沙箱上下文注入，后端每请求调用，校验用户存在性与 ACTIVE 状态后 SET LOCAL 三个 fms.* guc，org/dept 数组缺省时从授权表预载，uuid::text 输出 canonical 小写，与 p_org_id::text 比较恒一致
-- 参数，p_user_id 必填（NULL 则 RAISE），p_org_ids/p_dept_ids 缺省 NULL 时从 um.user_org_scope/um.user_dept_scope 预载（仅未软删授权行，COALESCE 兜底空数组）
-- 行为，用户不存在或已软删则 RAISE，状态非 ACTIVE 则 RAISE 拒绝注入，LOCKED/DISABLED 不可建立会话上下文，通过后 SET LOCAL，事务级，提交/回滚即失效，写入三个 guc，仅本事务内 rls 生效
-- 边界与错误，空授权数组写入空串，策略 ANY 恒 false，fail-closed，无授权则全库不可见，三个 RAISE 分支（user_id 为空/用户不存在或已删除/状态非法）由调用方，后端登录/切换会话捕获转业务错误
-- 调用约定，后端每语句经 SandboxSessionInterceptor 幂等调用，事务内，与网关签名采信的身份严格一致，显式传入 org/dept 数组可覆盖 scope 表预载，如跨公司查询场景，传 NULL 即"按授权表全量预载"
CREATE OR REPLACE FUNCTION auth.set_session(
    p_user_id  uuid,
    p_org_ids  uuid[] DEFAULT NULL,
    p_dept_ids uuid[] DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_status text;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'set_session: user_id 不能为空';
    END IF;
    SELECT status INTO v_status FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'set_session: 用户 % 不存在或已删除', p_user_id;
    END IF;
    IF v_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'set_session: 用户 % 状态为 %，禁止注入上下文', p_user_id, v_status;
    END IF;
    IF p_org_ids IS NULL THEN
        SELECT COALESCE(array_agg(org_id), ARRAY[]::uuid[]) INTO p_org_ids
          FROM um.user_org_scope
         WHERE user_id = p_user_id AND deleted_at IS NULL;
    END IF;
    IF p_dept_ids IS NULL THEN
        SELECT COALESCE(array_agg(dept_id), ARRAY[]::uuid[]) INTO p_dept_ids
          FROM um.user_dept_scope
         WHERE user_id = p_user_id AND deleted_at IS NULL;
    END IF;
    PERFORM set_config('fms.user_id',  p_user_id::text, true);
    PERFORM set_config('fms.org_ids',  array_to_string(p_org_ids, ','), true);
    PERFORM set_config('fms.dept_ids', array_to_string(p_dept_ids, ','), true);
END $$;
COMMENT ON FUNCTION auth.set_session(uuid, uuid[], uuid[])
    IS '沙箱上下文注入：校验用户后 SET LOCAL 注入 fms.user_id/org_ids/dept_ids（uuid 文本，事务级）';

-- jwt 签发/校验（HS256，委托 pgjwt 扩展，密钥读 sec.app_secrets）
-- 签发强制注入 iat/exp，默认 24h，调用方已提供则尊重其值，pgjwt.verify 仅对携带 exp/nbf 的载荷做时间窗校验，无 exp 令牌永不过期，RFC 7519 要求 exp，pgjwt 无时钟偏移容差，网关与 db 时钟须同步
-- 能力边界，pgjwt 0.2.0 仅支持 hmac 系，FMS 硬编码 HS256 且签发/验签同密钥，无算法混淆面，HS256 共享密钥须在所有验签方（rgw 网关 + db）间安全分发
-- 密钥存续约束，jwt.secret/pgcrypto.master_key 以明文 jsonb 存于库内 sec.app_secrets，库被攻破即密文+密钥同失，官方 pgcrypto 文档明示若无法信任 DBA 则加密应放客户端，沙箱环境接受该约束，生产化路径为外部 KMS/密钥服务注入
-- 参数，p_payload 为 jwt 载荷 jsonb，调用方提供业务 claims，如 sub/role/type，p_secret 缺省 NULL 时读 sec.app_secrets 的 jwt.secret，参数优先，测试可注入独立密钥
-- 行为，载荷缺 iat/exp 时强制注入（iat=当前时刻、exp=+24h，已提供则尊重原值），jsonb::text 序列化在键值分隔处带空格（'": ' 与 ', "' 仅出现在分隔处，值内字符串不含该引号邻接，替换安全），先紧凑化再交 pgjwt.sign，输出标准紧凑 jwt，与网关 jsonwebtoken 严格互操作
-- 错误，密钥未配置（参数与 sec.app_secrets 均缺失）则 RAISE，算法固定 HS256（pgjwt 0.2.0 仅 hmac 系，无算法混淆面），签发/验签同密钥，须在所有验签方（rgw 网关 + db）间安全分发
-- 安全语义，强制注入 iat/exp 是签发侧的防呆约束，pgjwt.verify 仅对携带 exp/nbf 的载荷做时间窗校验，无 exp 令牌永不过期，RFC 7519 要求 exp，签发时不补 exp 等于发了一张永不过期的通行证
CREATE OR REPLACE FUNCTION auth.jwt_sign(p_payload jsonb, p_secret text DEFAULT NULL)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_secret text := COALESCE(p_secret,
                   (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'jwt.secret'));
    v_compact text;
BEGIN
    IF v_secret IS NULL OR NULLIF(btrim(v_secret), '') IS NULL THEN
        RAISE EXCEPTION 'jwt_sign: 密钥未配置（参数或 sec.app_secrets.jwt.secret）';
    END IF;
    IF NOT (p_payload ? 'iat') THEN
        p_payload := p_payload || jsonb_build_object('iat', extract(epoch from now())::bigint);
    END IF;
    IF NOT (p_payload ? 'exp') THEN
        -- 兜底缺省 24h（调用方未带 exp 时），期望值由后端 yaml fms.jwt.expiry（默认 24h）登记，与 fms.jwt.access-ttl 联动评估，
        -- 非协议值，issuer/audience/算法不动（铁律仅涉有效期），后端签发恒显式注入 exp，本兜底缺省按配置值保留
        p_payload := p_payload || jsonb_build_object('exp',
            extract(epoch from now() + interval '24 hours')::bigint);
    END IF;
    -- jsonb::text 序列化在键值分隔处带空格，先紧凑化（'": ' 与 ', "' 仅出现在分隔处，值内字符串不含该引号邻接，替换安全）再传 pgjwt.sign，输出标准紧凑 jwt，与网关 jsonwebtoken 严格互操作
    v_compact := replace(replace(p_payload::text, '": ', '":'), ', "', ',"');
    RETURN pgjwt.sign(v_compact::json, v_secret, 'HS256');
END $$;

-- jwt 验签（HS256，委托 pgjwt 扩展，密钥读 sec.app_secrets，与 jwt_sign 对称）
-- 参数，p_token 待验紧凑 jwt，三段式，p_secret 缺省 NULL 时读 sec.app_secrets 的 jwt.secret，须与签发侧同密钥
-- 行为，委托 pgjwt.verify 做签名验证 + 时间窗校验，仅对携带 exp/nbf 的载荷生效，签发侧已强制注入 exp，无 exp 令牌永不过期问题被签发约束封堵，校验通过返回 payload jsonb
-- 边界与错误，签名失败/令牌被篡改/密钥不匹配则 RAISE，保持错误语义，调用方不可继续处理，密钥未配置则 RAISE，pgjwt 无时钟偏移容差，网关与 db 时钟须同步
-- 调用约定，后端 refresh 路径经本函数验签（AuthServiceImpl.verifyRefreshToken），与网关 auth.verify_jwt 同密钥同算法，令牌跨网关/后端双端互验，任一端签发的令牌另一端可验证
CREATE OR REPLACE FUNCTION auth.jwt_verify(p_token text, p_secret text DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_secret text := COALESCE(p_secret,
                   (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'jwt.secret'));
    v_result record;
BEGIN
    IF v_secret IS NULL OR NULLIF(btrim(v_secret), '') IS NULL THEN
        RAISE EXCEPTION 'jwt_verify: 密钥未配置（参数或 sec.app_secrets.jwt.secret）';
    END IF;
    -- 委托 pgjwt.verify，签名失败/篡改则 valid=false，RAISE，保持错误语义
    SELECT * INTO v_result FROM pgjwt.verify(p_token, v_secret, 'HS256');
    IF NOT v_result.valid THEN
        RAISE EXCEPTION 'jwt_verify: 签名校验失败（令牌被篡改或密钥不匹配）';
    END IF;
    RETURN v_result.payload::jsonb;
END $$;

-- 银行账号解密（唯一出口，SECURITY DEFINER，仅 fms_admin 与资金职责角色）
-- 参数，p_id 目标银行账户 id（cash.bank_accounts 主键）
-- 行为，读 sec.app_secrets 的 pgcrypto.master_key，库内密钥收敛点，后 pgp_sym_decrypt 解 cash.bank_accounts.account_no_enc 密文，返回明文账号，显式 cipher-algo=aes256，默认 aes128，OpenPGP 数据包自带算法标识，存量 aes128 密文照常可解
-- 守卫用 current_user（SET ROLE 后的职责身份）而非 session_user，fms_app 恰为职责角色成员，session_user 判定对 fms_app 直连恒真会误放行，fms_operator 为 NOLOGIN，须经 SET ROLE 注入后才可执行，fms_app 直连必被拒
-- 错误，角色不符 / 主密钥未配置 / 账户不存在或已软删则分别 RAISE，不返回 NULL 混淆"无此账户"，GRANT 仅 fms_operator，fms_app 直连不可达
-- 设计约束，明文账号仅在本函数内出现，应用侧任何 SELECT 都只拿到密文（account_no_enc 列对非 DEFINER 上下文不可读），解密能力收敛到唯一出口，审计/权限管控单点化
CREATE OR REPLACE FUNCTION auth.decrypt_bank_account(p_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_key text;
    v_no  text;
BEGIN
    -- 守卫用 current_user（SET ROLE 后的职责身份），session_user 判定对任何 fms_app 会话恒真（fms_app 恰为职责角色成员），fms_operator 为 NOLOGIN，fms_app 直连时 current_user=fms_app 必被拒
    IF current_user NOT IN ('fms_admin', 'fms_operator') THEN
        RAISE EXCEPTION 'decrypt_bank_account: 无权限（仅 fms_admin/资金职责角色）';
    END IF;
    -- 主密钥收敛到 sec.app_secrets（um.params 全角色可读，须收敛）
    SELECT value->>'v' INTO v_key FROM sec.app_secrets
     WHERE code = 'pgcrypto.master_key';
    IF v_key IS NULL THEN
        RAISE EXCEPTION 'decrypt_bank_account: pgcrypto.master_key 未配置（sec.app_secrets）';
    END IF;
    -- 显式 cipher-algo=aes256，默认 aes128，OpenPGP 数据包自带算法标识，解密选项仅是提示，存量 aes128 密文照常可解
    SELECT pgp_sym_decrypt(account_no_enc, v_key, 'cipher-algo=aes256') INTO v_no
      FROM cash.bank_accounts
     WHERE id = p_id AND deleted_at IS NULL;
    IF v_no IS NULL THEN
        RAISE EXCEPTION 'decrypt_bank_account: 银行账户 % 不存在', p_id;
    END IF;
    RETURN v_no;
END $$;

-- um.params 系统参数（seed_system.sql 种子，ON CONFLICT (code)）
CREATE TABLE IF NOT EXISTS um.params (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    code        text        NOT NULL,
    value       jsonb       NOT NULL,
    updated_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_params_code UNIQUE (code)
);
COMMENT ON TABLE um.params IS '系统参数（JSONB 值；预算开关/阈值、SMTP、JWT、列加密主密钥）';

-- um.users（seed_system.sql 种子契约，login_name 唯一、login_name_hash 盲索引、列加密 email/phone + 盲索引，id=ADMIN_UID 常量显式注入）
CREATE TABLE IF NOT EXISTS um.users (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id         uuid        NOT NULL,
    login_name         varchar(64) NOT NULL,
    login_name_hash    char(64)    NOT NULL,      -- 盲索引，hmac(login_name, pgcrypto.blind_key)，兼容列，详见下方列注释
    pass_hash          varchar(100) NOT NULL,     -- bcrypt（种子为正式演示哈希，口令 Fms@ca5T1ma93，生产由应用改密流程覆盖）
    display_name       varchar(128),
    email_encrypted    bytea,                     -- pgp_sym_encrypt 列加密
    email_hash         char(64),                  -- 盲索引，等值查询
    phone_encrypted    bytea,
    phone_hash         char(64),
    status             varchar(16) NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
    must_change_pwd    boolean     NOT NULL DEFAULT false,
    failed_login_count integer     NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    mfa_enabled        boolean     NOT NULL DEFAULT false,   -- MFA 启用开关
    mfa_secret_enc     bytea,                                -- MFA 密钥（totp secret，pgp_sym_encrypt）
    id_card_enc        bytea,                                -- 身份证号（pgp_sym_encrypt 静态加密）
    id_card_hash       char(64),                             -- 身份证盲索引，hmac(id_card, pgcrypto.blind_key)
    last_login_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz,
    CONSTRAINT uk_users_login_name UNIQUE (login_name)
);
CREATE INDEX IF NOT EXISTS ix_users_login_hash ON um.users (login_name_hash) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_users_company    ON um.users (company_id) WHERE deleted_at IS NULL;
-- 盲索引一律 hmac(value, blind_key)，纯 digest 无防字典攻击能力，低熵卡面值，电话/身份证/银行卡/邮箱可被持库只读者离线秒破
COMMENT ON COLUMN um.users.login_name_hash IS '兼容列：login_name 明文相邻存储 + UNIQUE 约束，本列不承担安全职责；接口兼容保留，等值查询走 uk_users_login_name';
COMMENT ON COLUMN um.users.email_encrypted IS 'pgp_sym_encrypt 加密（cipher-algo=aes256）；等值查询走 email_hash 盲索引';
COMMENT ON COLUMN um.users.mfa_enabled IS 'MFA 启用开关；开启后登录需 TOTP 二次验证';
COMMENT ON COLUMN um.users.mfa_secret_enc IS 'MFA TOTP 种子（pgp_sym_encrypt cipher-algo=aes256，主密钥 sec.app_secrets.pgcrypto.master_key）';
COMMENT ON COLUMN um.users.id_card_enc IS '身份证号 pgp_sym_encrypt 静态加密存储（敏感列，运行时角色不授）';
COMMENT ON COLUMN um.users.id_card_hash IS '身份证盲索引：encode(hmac(id_card, pgcrypto.blind_key, sha256), hex)，等值查询不泄露明文';
COMMENT ON COLUMN um.users.email_hash IS '邮箱盲索引：encode(hmac(email, pgcrypto.blind_key, sha256), hex)';
COMMENT ON COLUMN um.users.phone_hash IS '电话盲索引：encode(hmac(phone, pgcrypto.blind_key, sha256), hex)';

-- rbac，um.roles / um.permissions / um.user_role / um.role_perm
CREATE TABLE IF NOT EXISTS um.roles (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL,
    code        varchar(32) NOT NULL,
    name        varchar(64) NOT NULL,
    is_system   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

-- 权限表建表即含 module_id/identifier 等升级列，seed_system 先于 um_perm.sql 执行，
-- 其权限 INSERT 引用 module_id 不报 'column module_id does not exist'，um_perm.sql 的
-- ADD COLUMN 判存块为幂等空操作，module_id FK 由 um_perm.sql 补加（um.modules 已在本文件建表）
CREATE TABLE IF NOT EXISTS um.permissions (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id          uuid        NOT NULL,
    code                varchar(64) NOT NULL,             -- 'action:resource:data_scope'
    name                varchar(128) NOT NULL,
    module_id           uuid,                              -- 归属功能模块（um.modules.id，FK 由 um_perm.sql 补）
    identifier          text,                              -- 权限唯一标识，新体系，<模块标识>.<action>，存量行回填 = code
    resource_type       text,                              -- 资源类型（voucher/account/customer/period/api/report...）
    action              text,                              -- 动作（view/create/update/delete/approve/post/reverse/close/reopen...）
    permission_type     text,                              -- 权限形态（MENU/ACTION/api/BUTTON/DATA）
    is_enabled          boolean     NOT NULL DEFAULT true, -- 启用开关（false 停用，判定时视为无权限）
    is_system           boolean     NOT NULL DEFAULT false,-- 系统内置，不可删
    required_permissions uuid[]     NOT NULL DEFAULT '{}', -- 前置依赖权限 id 数组（uuid[]，GIN 索引）
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS um.user_role (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES um.users(id),
    role_id     uuid        NOT NULL REFERENCES um.roles(id),
    granted_by  uuid        REFERENCES um.users(id),
    granted_at  timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);
CREATE INDEX IF NOT EXISTS ix_user_role_role ON um.user_role (role_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS um.role_perm (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL,
    role_id     uuid        NOT NULL REFERENCES um.roles(id),
    perm_id     uuid        NOT NULL REFERENCES um.permissions(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_role_perm UNIQUE (role_id, perm_id)
);
CREATE INDEX IF NOT EXISTS ix_role_perm_perm ON um.role_perm (perm_id) WHERE deleted_at IS NULL;

-- org.companies / org.departments
CREATE TABLE IF NOT EXISTS org.companies (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id        uuid,                       -- 自引用（seed_demo.sql 种子列存在性自适应）
    code              varchar(32)  NOT NULL,
    name              varchar(128) NOT NULL,
    currency          char(3)      NOT NULL DEFAULT 'CNY',
    fiscal_year_start date         NOT NULL DEFAULT '0001-01-01',
    status            varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    CONSTRAINT uk_companies_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS org.departments (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL REFERENCES org.companies(id),
    parent_id   uuid        REFERENCES org.departments(id),
    code        varchar(32) NOT NULL,
    name        varchar(128) NOT NULL,
    path        ltree       NOT NULL,             -- 部门树，根='1'，子='父path.子编号'，仅字母数字与下划线
    level       int         NOT NULL DEFAULT 1,
    manager_id  uuid        REFERENCES um.users(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_dept_path UNIQUE (company_id, path)
);
CREATE INDEX IF NOT EXISTS ix_depts_company ON org.departments (company_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_depts_parent  ON org.departments (parent_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_depts_path    ON org.departments USING GIST (path);   -- <@ 子树查询
COMMENT ON COLUMN org.departments.path IS '部门树 LTREE：GIST 索引 + <@ 算子子树查询（auth.can_access_dept 子树可见性）；ltree label 只能是字母数字与下划线（最大 1000 字节），uuid 含 ''-'' 必报 invalid ltree syntax，若用 uuid 须去横线或映射 numeric 序号';

-- 上下文预载数据源（auth.set_session 缺省参数时装载，依赖 org 表先行）
CREATE TABLE IF NOT EXISTS um.user_org_scope (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES um.users(id),
    org_id      uuid        NOT NULL REFERENCES org.companies(id),
    scope_type  varchar(16) NOT NULL DEFAULT 'GRANTED'
                CHECK (scope_type IN ('GRANTED','MANAGER','DEFAULT')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CONSTRAINT uk_user_org_scope UNIQUE (user_id, org_id)
);

CREATE TABLE IF NOT EXISTS um.user_dept_scope (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id      uuid        NOT NULL,
    user_id         uuid        NOT NULL REFERENCES um.users(id),
    dept_id         uuid        NOT NULL REFERENCES org.departments(id),
    scope_type      varchar(16) NOT NULL DEFAULT 'GRANTED'
                    CHECK (scope_type IN ('GRANTED','MANAGER','DEFAULT')),
    include_subtree boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    CONSTRAINT uk_user_dept_scope UNIQUE (user_id, dept_id)
);

-- rls（um/org 全表 FORCE ROW LEVEL SECURITY），策略语义 fail-closed，未注入上下文（fms.* guc 缺失）则空集
ALTER TABLE um.params ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.params FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS params_select ON um.params;
CREATE POLICY params_select ON um.params
    FOR SELECT USING (deleted_at IS NULL AND (auth.current_uid() IS NOT NULL OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS params_write ON um.params;
CREATE POLICY params_write ON um.params
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.users FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS users_select ON um.users;
CREATE POLICY users_select ON um.users
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS users_update ON um.users;
CREATE POLICY users_update ON um.users
    FOR UPDATE USING (auth.is_self(id) OR current_user = 'fms_admin')
    WITH CHECK (auth.is_self(id) OR current_user = 'fms_admin');
DROP POLICY IF EXISTS users_insert ON um.users;
CREATE POLICY users_insert ON um.users
    FOR INSERT WITH CHECK (current_user = 'fms_admin');
DROP POLICY IF EXISTS users_delete ON um.users;
CREATE POLICY users_delete ON um.users
    FOR DELETE USING (current_user = 'fms_admin');

ALTER TABLE um.roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.roles FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS roles_select ON um.roles;
CREATE POLICY roles_select ON um.roles
    FOR SELECT USING (deleted_at IS NULL AND auth.current_uid() IS NOT NULL);
DROP POLICY IF EXISTS roles_write ON um.roles;
CREATE POLICY roles_write ON um.roles
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.permissions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS perms_select ON um.permissions;
CREATE POLICY perms_select ON um.permissions
    FOR SELECT USING (deleted_at IS NULL AND auth.current_uid() IS NOT NULL);
DROP POLICY IF EXISTS perms_write ON um.permissions;
CREATE POLICY perms_write ON um.permissions
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.user_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.user_role FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS user_role_select ON um.user_role;
CREATE POLICY user_role_select ON um.user_role
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS user_role_write ON um.user_role;
CREATE POLICY user_role_write ON um.user_role
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.role_perm ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.role_perm FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS role_perm_select ON um.role_perm;
CREATE POLICY role_perm_select ON um.role_perm
    FOR SELECT USING (deleted_at IS NULL AND auth.current_uid() IS NOT NULL);
DROP POLICY IF EXISTS role_perm_write ON um.role_perm;
CREATE POLICY role_perm_write ON um.role_perm
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.user_org_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.user_org_scope FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS uos_select ON um.user_org_scope;
CREATE POLICY uos_select ON um.user_org_scope
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS uos_write ON um.user_org_scope;
CREATE POLICY uos_write ON um.user_org_scope
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE um.user_dept_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.user_dept_scope FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS uds_select ON um.user_dept_scope;
CREATE POLICY uds_select ON um.user_dept_scope
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS uds_write ON um.user_dept_scope;
CREATE POLICY uds_write ON um.user_dept_scope
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE org.companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.companies FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS companies_select ON org.companies;
CREATE POLICY companies_select ON org.companies
    FOR SELECT USING (deleted_at IS NULL AND (auth.can_access_org(id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS companies_write ON org.companies;
CREATE POLICY companies_write ON org.companies
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

ALTER TABLE org.departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE org.departments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS depts_select ON org.departments;
CREATE POLICY depts_select ON org.departments
    FOR SELECT USING (deleted_at IS NULL AND (auth.can_access_org(company_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS depts_write ON org.departments;
CREATE POLICY depts_write ON org.departments
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

-- 授权（沙箱模型，fms_app 仅 CONNECT，业务访问经 SET ROLE 注入职责角色，L2 schema USAGE + L3 表级，敏感列不授予运行时角色）
GRANT USAGE ON SCHEMA um, org, auth TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- auth.* SECURITY DEFINER 函数族 EXECUTE（签名随 uuidv7 改造同步）
GRANT EXECUTE ON FUNCTION auth.current_uid() TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.can_access_org(uuid) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.can_access_dept(uuid) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.is_self(uuid) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.has_role(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.set_session(uuid, uuid[], uuid[]) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.jwt_sign(jsonb, text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.jwt_verify(text, text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
-- 解密出口最小授权，仅资金职责角色可执行，fms_app 直连不可达
GRANT EXECUTE ON FUNCTION auth.decrypt_bank_account(uuid) TO fms_operator;

-- 表级权限（职责角色，rls 行级收敛由策略负责），um.users 敏感列列级收紧，
-- mfa_secret_enc/id_card_enc/id_card_hash/login_name_hash 不授予运行时角色（2fa/身份证读写仅经
-- auth.* SECURITY DEFINER 闸口），pass_hash 仅 fms_operator 可 SELECT（totp 启用/禁用、改密等
-- 场景经 UserMapper 读哈希做 bcrypt 校验，rls is_self 限本人行），UPDATE 仍禁，
-- operator 直接 UPDATE 仅保留自助非敏感列（display_name）
REVOKE ALL ON um.users FROM fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT (id, company_id, login_name, display_name, status, must_change_pwd,
              failed_login_count, locked_until, mfa_enabled, pass_hash, email_encrypted,
              email_hash, phone_encrypted, phone_hash, last_login_at,
              created_at, updated_at, deleted_at) ON um.users TO fms_operator;
GRANT UPDATE (display_name) ON um.users TO fms_operator;
GRANT SELECT (id, company_id, login_name, display_name, status, must_change_pwd,
              failed_login_count, locked_until, mfa_enabled, email_encrypted,
              email_hash, phone_encrypted, phone_hash, last_login_at,
              created_at, updated_at, deleted_at)
    ON um.users TO fms_viewer, fms_auditor, fms_report;
-- um.users 的 rls 仅允许本人或管理员可见，业务列表（报销申请人、凭证制单人、流程处理人）
-- 需要展示他人姓名，故以 fms_admin 属主的视图暴露安全列，视图按属主身份执行，
-- 策略中的 current_user = fms_admin 分支放行；敏感列不进入视图
CREATE OR REPLACE VIEW um.v_user_names AS
    SELECT id, company_id, login_name, display_name
      FROM um.users
     WHERE deleted_at IS NULL;
ALTER VIEW um.v_user_names OWNER TO fms_admin;
GRANT SELECT ON um.v_user_names TO fms_operator, fms_viewer, fms_auditor, fms_report;
-- 参数表最小授权，密钥已移入 sec.app_secrets，其余参数仅供 SECURITY DEFINER 函数（fms_admin 身份）读取，职责角色一律不授 SELECT
REVOKE ALL ON um.params FROM fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT ON um.roles, um.permissions, um.user_role, um.role_perm
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT ON um.user_org_scope, um.user_dept_scope
    TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT, INSERT, UPDATE ON org.companies, org.departments TO fms_operator;
GRANT SELECT ON org.companies, org.departments TO fms_viewer, fms_auditor, fms_report;

-- pgaudit 对象审计标记，对目标对象 GRANT SELECT 给 pgaudit.role 即记录对应读操作，敏感表 um.users（email/phone/id_card 密文与盲索引）与 sec.app_secrets，密钥表的读访问入 pgaudit 审计日志，fms_pgaudit_role 为 NOLOGIN 标记角色，不改变任何业务授权面
GRANT SELECT ON um.users, sec.app_secrets TO fms_pgaudit_role;

-- 安全加固与组织管理员授权（存量重加密、密钥收敛、current_uid、敏感列、admin rls 与软删除出口）

-- 存量重加密，旧密钥读 um.params，新密钥写回前先迁移，防旋转后密文不可解
DO $$
DECLARE
    v_old_key text;
    v_new_key text := 'FMS_MK_2026_8d3f9a2c6b7e4f1a9d5c8e2b7f4a6d3c';
    v_plain   text;
    v_cnt     int := 0;
BEGIN
    SELECT value->>'v' INTO v_old_key FROM um.params
     WHERE code = 'pgcrypto.master_key' AND deleted_at IS NULL;
    IF v_old_key IS NULL OR v_old_key = v_new_key THEN
        RAISE NOTICE 'security_hardening_fix: 旧主密钥缺失或已旋转，跳过银行账户重加密';
        RETURN;
    END IF;
    FOR v_plain IN
        SELECT pgp_sym_decrypt(account_no_enc, v_old_key, 'cipher-algo=aes256')::text
          FROM cash.bank_accounts WHERE deleted_at IS NULL
    LOOP
        -- 新密文显式 aes256
        UPDATE cash.bank_accounts
           SET account_no_enc = pgp_sym_encrypt(v_plain, v_new_key, 'compress-algo=1, cipher-algo=aes256')
         WHERE account_no_hash = encode(digest(v_plain, 'sha256'), 'hex');
        v_cnt := v_cnt + 1;
    END LOOP;
    RAISE NOTICE 'security_hardening_fix: 银行账户已按新主密钥重加密 % 行', v_cnt;
END $$;

-- 写入旋转后的密钥（幂等，master_key/blind_key 重放时经 DO UPDATE 收敛回本文件声明常量，本段为
-- 密钥权威写入点，覆盖上文 L32 首写，jwt.secret 被 WHERE 排除仅首次落库，防重放覆盖已旋转值），
-- pgcrypto.blind_key（盲索引 hmac 密钥，本文件上文 L32 已首写同值），盲索引必须带密钥，
-- 纯 digest 可被持库只读者对低熵值离线字典爆破
INSERT INTO sec.app_secrets (code, value, updated_by, updated_at) VALUES
    ('pgcrypto.master_key', '{"v":"FMS_MK_2026_8d3f9a2c6b7e4f1a9d5c8e2b7f4a6d3c"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now()),
    ('jwt.secret', '{"v":"FMS_JWT_2026_a9f3c8e2b7d14f6a9e3c8b2d7f4a6e9c8b2d7f4a6e9c3b8d2f7a4e6c9b8d2f7a4"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now()),   -- jwt.secret 仅首次兜底（权威 .env 对齐段）
    ('pgcrypto.blind_key', '{"v":"FMS_BLIND_2026_9c4e2f7a1b6d4c3e8f5a2b7d9c4e6f8a1b3d5c7e9f"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now())
ON CONFLICT (code) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
  WHERE sec.app_secrets.code <> 'jwt.secret';

-- 敏感参数移出 um.params（密钥收敛，职责角色已由 grants_base 收表权限）
DELETE FROM um.params WHERE code IN ('jwt.secret', 'pgcrypto.master_key');

-- auth.* 函数族
-- auth.current_uid，LOCKED/DISABLED 账户在 rls 层即失效（二次校验 status='ACTIVE'）
CREATE OR REPLACE FUNCTION auth.current_uid()
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, pg_catalog, public
AS $$
DECLARE
    v_text text;
    v_uid  uuid;
BEGIN
    v_text := NULLIF(current_setting('fms.user_id', true), '');
    IF v_text IS NULL OR v_text !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        RETURN NULL;
    END IF;
    v_uid := v_text::uuid;
    IF NOT EXISTS (SELECT 1 FROM um.users
                    WHERE id = v_uid AND deleted_at IS NULL AND status = 'ACTIVE') THEN
        RETURN NULL;
    END IF;
    RETURN v_uid;
END $$;

-- um.users 敏感列（MFA/身份证）+ 软删除出口
ALTER TABLE um.users ADD COLUMN IF NOT EXISTS mfa_enabled     boolean     NOT NULL DEFAULT false;
ALTER TABLE um.users ADD COLUMN IF NOT EXISTS mfa_secret_enc  bytea;
ALTER TABLE um.users ADD COLUMN IF NOT EXISTS id_card_enc     bytea;
ALTER TABLE um.users ADD COLUMN IF NOT EXISTS id_card_hash    char(64);

-- auth.is_admin，当前用户（fms.user_id guc）是否挂接系统管理业务角色

SET ROLE fms_admin;

CREATE OR REPLACE FUNCTION auth.is_admin()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM um.user_role ur
          JOIN um.roles r ON r.id = ur.role_id AND r.deleted_at IS NULL
          JOIN um.users u ON u.id = ur.user_id AND u.deleted_at IS NULL
         WHERE u.id = auth.current_uid()
           AND r.code IN ('fms_admin_role', 'sys_admin')
           AND ur.deleted_at IS NULL
    );
$$;
COMMENT ON FUNCTION auth.is_admin()
    IS '系统管理员判定（业务角色 fms_admin_role/sys_admin，SECURITY DEFINER 读 GUC fms.user_id）：RLS 策略与权限闸口共用，管理员可管理他人用户/公司/部门';
REVOKE EXECUTE ON FUNCTION auth.is_admin() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.is_admin() TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- rls 策略，管理员可见/可写全部用户/公司/部门，其余语义不变，fail-closed


-- um.users，管理员可查/可改他人行，预检 selectById / 列表 / 详情 / 更新回读
ALTER TABLE um.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.users FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS users_select ON um.users;
CREATE POLICY users_select ON um.users
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.is_self(id) OR auth.is_admin() OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS users_update ON um.users;
CREATE POLICY users_update ON um.users
    FOR UPDATE USING (auth.is_self(id) OR auth.is_admin() OR current_user = 'fms_admin')
    WITH CHECK (auth.is_self(id) OR auth.is_admin() OR current_user = 'fms_admin');

-- um.user_role，管理员可查他人角色分配，assign/revoke 走权限闸口，写不依赖本策略
DROP POLICY IF EXISTS user_role_select ON um.user_role;
CREATE POLICY user_role_select ON um.user_role
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.is_self(user_id) OR auth.is_admin() OR current_user = 'fms_admin'));

-- um.user_org_scope / um.user_dept_scope，管理员可查他人数据范围
DROP POLICY IF EXISTS uos_select ON um.user_org_scope;
CREATE POLICY uos_select ON um.user_org_scope
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.is_self(user_id) OR auth.is_admin() OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS uds_select ON um.user_dept_scope;
CREATE POLICY uds_select ON um.user_dept_scope
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.is_self(user_id) OR auth.is_admin() OR current_user = 'fms_admin'));

-- org.companies，管理员全量可见，新建公司不在 scope 亦立即可见/可查/可更新
DROP POLICY IF EXISTS companies_select ON org.companies;
CREATE POLICY companies_select ON org.companies
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.can_access_org(id) OR auth.is_admin() OR current_user = 'fms_admin'));

-- org.departments，管理员全量可见，写策略放开为数据范围（应用角色直写经
-- can_access_org 收敛，管理员 is_admin 放行，current_user='fms_admin' 对应用会话恒拒）
DROP POLICY IF EXISTS depts_select ON org.departments;
CREATE POLICY depts_select ON org.departments
    FOR SELECT USING (deleted_at IS NULL
        AND (auth.can_access_org(company_id) OR auth.is_admin() OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS depts_write ON org.departments;
CREATE POLICY depts_write ON org.departments
    FOR ALL USING (auth.can_access_org(company_id) OR auth.is_admin() OR current_user = 'fms_admin')
    WITH CHECK (auth.can_access_org(company_id) OR auth.is_admin() OR current_user = 'fms_admin');

-- auth.fn_soft_delete 保持属主 postgres（属主若为 fms_admin 会令函数内
-- current_user='fms_admin' 恒真短路、数据范围检查整体失效），故以下软删除出口各节
-- 均以连接用户（postgres）身份执行（RESET ROLE 恢复文件起始 SET ROLE fms_admin 之前
-- 的状态，auth.* 其余函数族属主仍为 fms_admin 不受影响）
RESET ROLE;

-- 软删除统一出口，白名单表按公司范围收敛
CREATE OR REPLACE FUNCTION auth.fn_soft_delete(p_schema text, p_table text, p_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, pg_catalog, public
AS $$
DECLARE
    v_company_id uuid;
    v_rc         bigint;
    v_whitelist  text[] := ARRAY[
        'gl.vouchers','gl.voucher_lines','gl.chart_accounts','gl.fx_rates',
        'ar.customers','ar.invoices','ar.receipts','ar.receipt_allocations','ar.bad_debt_provision',
        'ap.suppliers','ap.invoices','ap.payments','ap.payment_allocations',
        'ap.reconciliations','ap.reconciliation_lines',
        'cash.bank_accounts','cash.transactions','cash.bank_statements',
        'cash.bank_statement_lines','cash.reconcile_runs',
        'um.users','um.api_keys','org.companies','org.departments','org.positions','org.employees',
        'fa.assets','fa.depreciation_runs',
        'expense.reimbursements','expense.reimbursement_lines',
        'budget.budget_plans','budget.budget_adjustments',
        'wf.definitions','wf.instances','wf.tasks'];
BEGIN
    IF (p_schema || '.' || p_table) <> ALL (v_whitelist) THEN
        RAISE EXCEPTION 'fn_soft_delete: 表 %s.%s 不在软删除白名单', p_schema, p_table;
    END IF;
    IF auth.current_uid() IS NULL AND current_user <> 'fms_admin' THEN
        RETURN false;
    END IF;
    EXECUTE format('SELECT company_id FROM %I.%I WHERE id = $1 AND deleted_at IS NULL',
                   p_schema, p_table)
        INTO v_company_id USING p_id;
    IF v_company_id IS NULL THEN
        RETURN false;
    END IF;
    IF current_user <> 'fms_admin' AND NOT auth.can_access_org(v_company_id) THEN
        RAISE EXCEPTION 'fn_soft_delete: 无公司 % 数据范围权限', v_company_id;
    END IF;
    EXECUTE format('UPDATE %I.%I SET deleted_at = now() WHERE id = $1 AND deleted_at IS NULL',
                   p_schema, p_table)
        USING p_id;
    GET DIAGNOSTICS v_rc = ROW_COUNT;
    RETURN v_rc > 0;
END $$;
REVOKE EXECUTE ON FUNCTION auth.fn_soft_delete(text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.fn_soft_delete(text, text, uuid) TO fms_operator;
COMMENT ON FUNCTION auth.fn_soft_delete(text, text, uuid)
    IS '软删除统一出口（SECURITY DEFINER）：白名单表按公司范围收敛后置 deleted_at=now()；应用角色软删除经本闸口（PG15+ 新行可见性检查下直 UPDATE 不可达）';

-- 版本化变体，核销撤销/冲销乐观锁，返回受影响行数，0 = 版本冲突/不存在/已删除，
--     ar/ap 核销明细撤销经此出口（Mapper softDeleteWithVersion 转交 auth.fn_soft_delete_v）
CREATE OR REPLACE FUNCTION auth.fn_soft_delete_v(p_schema text, p_table text, p_id uuid, p_old_version int)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, pg_catalog, public
AS $$
DECLARE
    v_company_id uuid;
    v_rc         bigint;
    v_whitelist  text[] := ARRAY[
        'ar.receipt_allocations','ap.payment_allocations'];
BEGIN
    IF (p_schema || '.' || p_table) <> ALL (v_whitelist) THEN
        RAISE EXCEPTION 'fn_soft_delete_v: 表 %s.%s 不在白名单', p_schema, p_table;
    END IF;
    IF auth.current_uid() IS NULL AND current_user <> 'fms_admin' THEN
        RETURN 0;
    END IF;
    EXECUTE format('SELECT company_id FROM %I.%I WHERE id = $1 AND deleted_at IS NULL',
                   p_schema, p_table)
        INTO v_company_id USING p_id;
    IF v_company_id IS NULL THEN
        RETURN 0;
    END IF;
    IF current_user <> 'fms_admin' AND NOT auth.can_access_org(v_company_id) THEN
        RAISE EXCEPTION 'fn_soft_delete_v: 无公司 % 数据范围权限', v_company_id;
    END IF;
    EXECUTE format('UPDATE %I.%I SET deleted_at = now(), version = version + 1, updated_at = now()
                     WHERE id = $1 AND version = $2 AND deleted_at IS NULL',
                   p_schema, p_table)
        USING p_id, p_old_version;
    GET DIAGNOSTICS v_rc = ROW_COUNT;
    RETURN v_rc;
END $$;
REVOKE EXECUTE ON FUNCTION auth.fn_soft_delete_v(text, text, uuid, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.fn_soft_delete_v(text, text, uuid, int) TO fms_operator;
COMMENT ON FUNCTION auth.fn_soft_delete_v(text, text, uuid, int)
    IS '版本化软删除出口（SECURITY DEFINER）：核销明细撤销专用，乐观锁 version 条件后置 deleted_at=now()';

-- auth.fn_soft_delete（保持属主 postgres，以连接用户 postgres 重建，勿 SET ROLE），

--    白名单含 um.roles/um.role_perm，数据范围放宽 is_admin
CREATE OR REPLACE FUNCTION auth.fn_soft_delete(p_schema text, p_table text, p_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, pg_catalog, public
AS $$
DECLARE
    v_company_id uuid;
    v_rc         bigint;
    v_whitelist  text[] := ARRAY[
        'gl.vouchers','gl.voucher_lines','gl.chart_accounts','gl.fx_rates',
        'ar.customers','ar.invoices','ar.receipts','ar.receipt_allocations','ar.bad_debt_provision',
        'ap.suppliers','ap.invoices','ap.payments','ap.payment_allocations',
        'ap.reconciliations','ap.reconciliation_lines',
        'cash.bank_accounts','cash.transactions','cash.bank_statements',
        'cash.bank_statement_lines','cash.reconcile_runs',
        'um.users','um.api_keys','um.roles','um.role_perm',
        'org.companies','org.departments','org.positions','org.employees',
        'fa.assets','fa.depreciation_runs',
        'expense.reimbursements','expense.reimbursement_lines',
        'budget.budget_plans','budget.budget_adjustments',
        'wf.definitions','wf.instances','wf.tasks'];
BEGIN
    IF (p_schema || '.' || p_table) <> ALL (v_whitelist) THEN
        RAISE EXCEPTION 'fn_soft_delete: 表 %s.%s 不在软删除白名单', p_schema, p_table;
    END IF;
    IF auth.current_uid() IS NULL AND current_user <> 'fms_admin' THEN
        RETURN false;
    END IF;
    EXECUTE format('SELECT company_id FROM %I.%I WHERE id = $1 AND deleted_at IS NULL',
                   p_schema, p_table)
        INTO v_company_id USING p_id;
    IF v_company_id IS NULL THEN
        RETURN false;
    END IF;
    -- 数据范围，公司范围命中或系统管理员（is_admin）放行，越权 RAISE
    IF NOT auth.can_access_org(v_company_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION 'fn_soft_delete: 无公司 % 数据范围权限', v_company_id;
    END IF;
    EXECUTE format('UPDATE %I.%I SET deleted_at = now() WHERE id = $1 AND deleted_at IS NULL',
                   p_schema, p_table)
        USING p_id;
    GET DIAGNOSTICS v_rc = ROW_COUNT;
    RETURN v_rc > 0;
END $$;
REVOKE EXECUTE ON FUNCTION auth.fn_soft_delete(text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.fn_soft_delete(text, text, uuid) TO fms_operator;
COMMENT ON FUNCTION auth.fn_soft_delete(text, text, uuid)
    IS '软删除统一出口（SECURITY DEFINER，属主 postgres）：白名单表按公司范围收敛后置 deleted_at=now()；系统管理员（is_admin）可删除新建/范围外公司数据';

-- 文件末尾自查
SELECT 'um_org_base' AS file_name,
       (SELECT count(*) FROM pg_tables WHERE schemaname IN ('um','org'))       AS table_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth')                                            AS auth_fn_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname IN ('um','org'))    AS policy_cnt;
