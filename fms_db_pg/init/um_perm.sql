-- FMS 财务系统（init/um_perm），um.modules 模块树，种子 13 根 + 34 子模块+ um.permissions 升级（module_id/identifier/action 等新列 + 权限种子动作矩阵 + 依赖数组 GIN）+ um.user_direct_permissions，特批/临时授权，+ role_perm/user_role 授权留痕与有效期 + auth.user_permissions/has_permission/require_permission 判定函数族（fail-closed）

-- 内容，operator 职责角色（accountant/approver/cashier）补授 wf.* 权限点，auth.assign_role/revoke_role/upsert_role/grant_role_permission/revoke_role_permission/org.fn_upsert_company 补 require_permission 闸口，防自升角色/越权

-- 执行顺序由 deploy/init/fms_init.sh 白名单调度，全部 DDL/种子幂等可重跑

-- 种子常量，ADMIN_UID=00000000-0000-7000-0000-000000000001、COMPANY_UID=00000000-0000-7000-0000-000000000010，模块 id 常量 …500 起、权限生成器 0x60c，1548 十进制起 141 条（seed_data 存量 12 条占 …600-…60b）

SET ROLE fms_admin;

-- um.modules（功能模块树，parent_id 自引用 + identifier 唯一，权限归属域 + 菜单层级建模）
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

-- 模块树种子，前置，三节 um.permissions 存量回填依赖模块行
-- 13 个功能域根（12 业务功能域 + admin 系统支撑，显式 id 常量 …500-…50c）
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

-- um.permissions 升级（兼容现有列，全部 ALTER 幂等），现有结构 id uuid DEFAULT uuidv7 PK / company_id / code UNIQUE / name / 审计列，role_perm.perm_id 引用关系不动
-- 新增列（幂等，DO 块查 information_schema.columns 判存，module_id 先建 um.modules 后加 FK）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'module_id') THEN
        ALTER TABLE um.permissions ADD COLUMN module_id uuid;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'identifier') THEN
        ALTER TABLE um.permissions ADD COLUMN identifier text;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'resource_type') THEN
        ALTER TABLE um.permissions ADD COLUMN resource_type text;   -- 'voucher'/'account'/'customer'/'period'/'api'/'report'...
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'action') THEN
        ALTER TABLE um.permissions ADD COLUMN action text;          -- 'view'/'create'/'update'/'delete'/'approve'/'post'/'reverse'/'close'/'reopen'...
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'permission_type') THEN
        ALTER TABLE um.permissions ADD COLUMN permission_type text; -- 'MENU'/'ACTION'/'api'/'BUTTON'/'DATA'
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'is_enabled') THEN
        ALTER TABLE um.permissions ADD COLUMN is_enabled boolean NOT NULL DEFAULT true;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'is_system') THEN
        ALTER TABLE um.permissions ADD COLUMN is_system boolean NOT NULL DEFAULT false;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'required_permissions') THEN
        ALTER TABLE um.permissions ADD COLUMN required_permissions uuid[] NOT NULL DEFAULT '{}';
    END IF;
END $$;

COMMENT ON COLUMN um.permissions.module_id IS '归属功能模块（um.modules.id）';
COMMENT ON COLUMN um.permissions.identifier IS '权限唯一标识（新体系）：<模块标识>.<action>，如 gl.voucher.post；存量行回填 = code';
COMMENT ON COLUMN um.permissions.resource_type IS '资源类型（voucher/account/customer/period/api/report...）；存量行按 code 拆分回填';
COMMENT ON COLUMN um.permissions.action IS '动作（view/create/update/delete/approve/post/reverse/close/reopen...）；存量行按 code 拆分回填';
COMMENT ON COLUMN um.permissions.permission_type IS '权限形态（MENU/ACTION/API/BUTTON/DATA）';
COMMENT ON COLUMN um.permissions.is_enabled IS '启用开关（false 停用：判定时视为无权限）';
COMMENT ON COLUMN um.permissions.is_system IS '系统内置（不可删）';
COMMENT ON COLUMN um.permissions.required_permissions IS '前置依赖权限 id 数组（uuid[]，GIN 索引）；授予但依赖未满足时 has_permission 判定为 false';

-- 存量行回填，identifier=code，resource_type/action 从 'action:resource:data_scope' 拆分，module_id 按 code 前缀映射到模块树，二节种子已就绪
UPDATE um.permissions p
   SET module_id = m.id,
       identifier = COALESCE(p.identifier, p.code),
       resource_type = COALESCE(p.resource_type, split_part(p.code, ':', 2)),
       action        = COALESCE(p.action,        split_part(p.code, ':', 1)),
       permission_type = COALESCE(p.permission_type, 'ACTION')
  FROM um.modules m
 WHERE p.module_id IS NULL
   AND m.identifier = CASE split_part(p.code, ':', 1)
         WHEN 'voucher' THEN 'gl.voucher'
         WHEN 'budget'  THEN 'budget.plan'
         WHEN 'audit'   THEN 'audit.log'
         WHEN 'ar'      THEN 'ar'
         WHEN 'ap'      THEN 'ap'
         WHEN 'cash'    THEN 'cash'
         WHEN 'fa'      THEN 'fa'
         WHEN 'expense' THEN 'expense'
         WHEN 'report'  THEN 'rpt'
         ELSE 'admin' END;

-- 兜底，无 module 归属的行挂 admin 根
UPDATE um.permissions SET module_id = (SELECT id FROM um.modules WHERE identifier = 'admin')
 WHERE module_id IS NULL;

-- 收紧约束（幂等，SET NOT NULL 重跑无害，唯一索引用部分索引兼容软删除）
UPDATE um.permissions SET identifier = 'legacy.' || id::text WHERE identifier IS NULL;   -- 理论不可达，兜底
ALTER TABLE um.permissions ALTER COLUMN identifier SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_permissions_identifier
    ON um.permissions (identifier) WHERE deleted_at IS NULL;

-- 外键 + 依赖数组 GIN 索引
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'fk_permissions_module' AND conrelid = 'um.permissions'::regclass) THEN
        ALTER TABLE um.permissions ADD CONSTRAINT fk_permissions_module
            FOREIGN KEY (module_id) REFERENCES um.modules(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_permissions_module ON um.permissions (module_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_permissions_req    ON um.permissions USING GIN (required_permissions);

-- 兼容性结论，现有 12 条种子与 role_perm 全部保留，新增列除 identifier NOT NULL 外均可空/有默认值，uk_permissions_code(code) 保留不删

-- 权限种子（按子模块动作矩阵生成，code=identifier 兼容 uk_permissions_code，全部 ON CONFLICT 幂等）
-- 显式 id 常量 …60c-…698 顺序分配（seed_data 存量 12 条占 …600-…60b，生成器从 0x60c 起 141 条），pg 核心无 uuid 算术运算符，计数器用 to_hex/lpad 文本合成保序常数（十进制 1548 = 0x60c）
DO $$
DECLARE
    m record; a text; v_actions text[];
    v_seq bigint := 1548;   -- 权限 id 末组十六进制计数，0x60c 起，1548 十进制
    v_next uuid;
BEGIN
    FOR m IN SELECT s.identifier, mo.id, mo.name
             FROM (VALUES ('org.company'),('org.department'),('org.position'),('org.employee'),
                          ('org.user'),('org.role'),('gl.voucher'),('gl.chart_account'),('gl.period'),
                          ('ar.customer'),('ar.invoice'),('ar.receipt'),('ap.supplier'),('ap.invoice'),
                          ('ap.payment'),('cash.bank_account'),('cash.transaction'),('cash.reconcile'),
                          ('fa.asset'),('fa.depreciation'),('expense.reimbursement'),('budget.plan'),
                          ('budget.adjustment'),('wf.definition'),('wf.instance'),('rpt.statement'),
                          ('audit.log'),('notify.message'),('admin.user'),('admin.role'),
                          ('admin.permission'),('admin.module'),('admin.api_key'),('admin.param')) AS s(identifier)
             JOIN um.modules mo ON mo.identifier = s.identifier
            WHERE mo.deleted_at IS NULL AND mo.parent_id IS NOT NULL
    LOOP
        v_actions := CASE m.identifier
            WHEN 'gl.voucher'          THEN ARRAY['view','create','update','delete','approve','post','reverse','reject']
            WHEN 'gl.period'           THEN ARRAY['view','close','reopen','fx_adjustment']
            WHEN 'expense.reimbursement' THEN ARRAY['view','create','update','delete','submit','approve','pay']
            WHEN 'cash.reconcile'      THEN ARRAY['view','execute']
            WHEN 'budget.plan'         THEN ARRAY['view','create','update','delete','approve']
            WHEN 'budget.adjustment'   THEN ARRAY['view','create','approve']
            WHEN 'wf.instance'         THEN ARRAY['view','create','approve','reject']
            WHEN 'audit.log'           THEN ARRAY['view','export','chain_check']
            WHEN 'admin.user'          THEN ARRAY['view','create','update','delete','reset_pwd','grant']
            WHEN 'admin.role'          THEN ARRAY['view','create','update','delete','grant']
            WHEN 'admin.permission'    THEN ARRAY['view','create','update','delete','grant']
            WHEN 'admin.module'        THEN ARRAY['view','create','update','delete']
            WHEN 'admin.api_key'       THEN ARRAY['view','create','revoke']
            WHEN 'admin.param'         THEN ARRAY['view','update']
            ELSE ARRAY['view','create','update','delete']
        END CASE;
        FOREACH a IN ARRAY v_actions LOOP
            v_next := ('00000000-0000-7000-0000-' || lpad(to_hex(v_seq), 12, '0'))::uuid;
            v_seq := v_seq + 1;
            INSERT INTO um.permissions (id, company_id, module_id, code, identifier, name,
                                        resource_type, action, permission_type, is_enabled, is_system)
            VALUES (v_next, '00000000-0000-7000-0000-000000000010', m.id, m.identifier || '.' || a, m.identifier || '.' || a,
                    m.name || '：' || a,
                    split_part(m.identifier, '.', 1), a,
                    CASE WHEN a = 'view' THEN 'MENU' ELSE 'ACTION' END,
                    true, false)
            ON CONFLICT (code) DO NOTHING;
        END LOOP;
    END LOOP;
END $$;

-- 依赖示例，过账/冲销/审批/驳回 依赖 查看，required_permissions 演示
UPDATE um.permissions p
   SET required_permissions = ARRAY[(SELECT x.id FROM um.permissions x
                                      WHERE x.identifier = 'gl.voucher.view' AND x.deleted_at IS NULL)]
 WHERE p.identifier IN ('gl.voucher.approve','gl.voucher.post','gl.voucher.reverse','gl.voucher.reject')
   AND p.deleted_at IS NULL AND p.required_permissions = '{}';

-- um.user_direct_permissions（用户级补充授权，特批/临时，reason + valid_until）
CREATE TABLE IF NOT EXISTS um.user_direct_permissions (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    company_id    uuid        NOT NULL,
    user_id       uuid        NOT NULL REFERENCES um.users(id),
    permission_id uuid        NOT NULL REFERENCES um.permissions(id),
    granted_by    uuid        REFERENCES um.users(id),       -- 授权人，特批/临时授权留痕
    granted_at    timestamptz NOT NULL DEFAULT now(),
    reason        text,                                      -- 授权原因，如，特批、代理审批临时授权
    valid_until   timestamptz,                               -- 有效期（NULL=长期，到期自动失效）
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    deleted_at    timestamptz,
    CONSTRAINT uk_user_direct_perm UNIQUE (user_id, permission_id)
);
CREATE INDEX IF NOT EXISTS ix_udp_perm ON um.user_direct_permissions (permission_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE um.user_direct_permissions IS
'用户直接权限（reason 授权原因 + valid_until 有效期）：角色体系之外的补充授权（特批/临时），判定时与角色权限合并';

-- um.role_perm / um.user_role 授权留痕 + 有效期（ALTER 幂等，user_role 的 granted_by/granted_at init/um_org_base.sql 已建，仅补 valid_until）
-- role_perm，granted_by，授权人
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'role_perm'
                      AND column_name = 'granted_by') THEN
        ALTER TABLE um.role_perm ADD COLUMN granted_by uuid REFERENCES um.users(id);
    END IF;
END $$;

-- role_perm，granted_at，授权时间
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'role_perm'
                      AND column_name = 'granted_at') THEN
        ALTER TABLE um.role_perm ADD COLUMN granted_at timestamptz NOT NULL DEFAULT now();
    END IF;
END $$;

-- role_perm，valid_until，有效期
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'role_perm'
                      AND column_name = 'valid_until') THEN
        ALTER TABLE um.role_perm ADD COLUMN valid_until timestamptz;
    END IF;
END $$;

-- 存量回填，seed_data 种子行无授权人则记为 admin（ADMIN_UID 常量）、时间取 created_at
UPDATE um.role_perm SET granted_by = '00000000-0000-7000-0000-000000000001',
                        granted_at = COALESCE(granted_at, created_at)
 WHERE granted_by IS NULL;

-- user_role，仅补 valid_until
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'user_role'
                      AND column_name = 'valid_until') THEN
        ALTER TABLE um.user_role ADD COLUMN valid_until timestamptz;
    END IF;
END $$;

COMMENT ON COLUMN um.role_perm.granted_by IS '授权人（um.users.id；存量 seed_data 种子行回填 admin=00000000-0000-7000-0000-000000000001）';
COMMENT ON COLUMN um.role_perm.granted_at IS '授权时间（存量回填 created_at）';
COMMENT ON COLUMN um.role_perm.valid_until IS '角色-权限有效期（NULL=长期）；判定时过期即失效';
COMMENT ON COLUMN um.user_role.valid_until IS '用户-角色有效期（NULL=长期）；判定时过期即失效';

-- 授权种子，全部幂等，admin 角色全量授权，补新权限，+ admin 用户直接权限示例，长期特批 + 临时 7 天演示 reason/valid_until
-- granted_by 按 login_name 解析 admin 的子查询，用户不存在时落 NULL（FK 允许），在
-- um.users 为空的库上，seed_data 失败后重放本文件，role_perm_granted_by_fkey 不失败，
-- 既有回填逻辑（granted_by IS NULL 则按 admin）语义不变
INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r CROSS JOIN um.permissions p
 WHERE r.code = 'fms_admin_role' AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

-- 职责角色标识（accountant=会计，记账，voucher:create/post/reverse 矩阵 + 种子演示 operator01 审核），
-- voucher:post:dept 等码不参与 require_permission 判定，须补授新标识，否则 fn_post_voucher/fn_reverse_voucher 闸口拒绝
INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r JOIN um.permissions p
    ON p.identifier IN ('gl.voucher.view','gl.voucher.create','gl.voucher.update',
                        'gl.voucher.delete','gl.voucher.approve','gl.voucher.post',
                        'gl.voucher.reverse','gl.voucher.reject')
 WHERE r.code = 'accountant' AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

INSERT INTO um.user_direct_permissions (company_id, user_id, permission_id, granted_by, granted_at, reason, valid_until)
SELECT '00000000-0000-7000-0000-000000000010', u.id, p.id,
       '00000000-0000-7000-0000-000000000001', now(), '超级管理员特批：可执行凭证冲销', NULL
  FROM um.users u CROSS JOIN um.permissions p
 WHERE u.login_name = 'admin' AND p.identifier = 'gl.voucher.reverse'
ON CONFLICT (user_id, permission_id) DO UPDATE SET reason = EXCLUDED.reason, valid_until = EXCLUDED.valid_until, deleted_at = NULL, updated_at = now();

INSERT INTO um.user_direct_permissions (company_id, user_id, permission_id, granted_by, granted_at, reason, valid_until)
SELECT '00000000-0000-7000-0000-000000000010', u.id, p.id,
       '00000000-0000-7000-0000-000000000001', now(), '超级管理员特批：可执行凭证驳回', NULL
  FROM um.users u CROSS JOIN um.permissions p
 WHERE u.login_name = 'admin' AND p.identifier = 'gl.voucher.reject'
ON CONFLICT (user_id, permission_id) DO UPDATE SET reason = EXCLUDED.reason, valid_until = EXCLUDED.valid_until, deleted_at = NULL, updated_at = now();

INSERT INTO um.user_direct_permissions (company_id, user_id, permission_id, granted_by, granted_at, reason, valid_until)
SELECT '00000000-0000-7000-0000-000000000010', u.id, p.id,
       '00000000-0000-7000-0000-000000000001', now(), '代理审批临时授权（示例，7 天有效）', now() + interval '7 days'
  FROM um.users u CROSS JOIN um.permissions p
 WHERE u.login_name = 'admin' AND p.identifier = 'expense.reimbursement.approve'
ON CONFLICT (user_id, permission_id) DO UPDATE SET reason = EXCLUDED.reason, valid_until = EXCLUDED.valid_until, deleted_at = NULL, updated_at = now();

-- auth.* 权限判定函数族（SECURITY DEFINER，读 guc fms.user_id，fail-closed），不依赖 current_user，DEFINER 内恒为属主，未注入上下文则空集/false/异常，fms_admin（NOLOGIN）与 postgres，引导连接用户，维护直通
-- 合并权限集，角色权限（含 user_role/role_perm 有效期过滤）+ 用户直接权限（valid_until 过滤）+ 权限/模块启用状态过滤，返回权限明细与依赖缺口
CREATE OR REPLACE FUNCTION auth.user_permissions()
RETURNS TABLE (
    permission_id        uuid,
    identifier           text,
    code                 text,
    source               text,            -- 'ROLE' | 'DIRECT'
    valid_until          timestamptz,
    required_permissions uuid[],
    missing_required     boolean          -- 前置依赖未全部满足
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
    WITH uid AS (SELECT auth.current_uid() AS id),
    held AS (
        SELECT rp.perm_id, 'ROLE'::text AS source, rp.valid_until
          FROM um.user_role ur
          JOIN um.role_perm rp ON rp.role_id = ur.role_id AND rp.deleted_at IS NULL
          JOIN um.roles r      ON r.id = rp.role_id AND r.deleted_at IS NULL
         WHERE ur.user_id = (SELECT id FROM uid) AND ur.deleted_at IS NULL
           AND (ur.valid_until IS NULL OR ur.valid_until > now())
           AND (rp.valid_until IS NULL OR rp.valid_until > now())
        UNION ALL
        SELECT dp.permission_id, 'DIRECT'::text, dp.valid_until
          FROM um.user_direct_permissions dp
         WHERE dp.user_id = (SELECT id FROM uid) AND dp.deleted_at IS NULL
           AND (dp.valid_until IS NULL OR dp.valid_until > now())
    )
    SELECT DISTINCT ON (p.id)
           p.id, p.identifier, p.code, h.source, h.valid_until, p.required_permissions,
           EXISTS (SELECT 1 FROM unnest(p.required_permissions) req
                    WHERE NOT EXISTS (SELECT 1 FROM held h2 WHERE h2.perm_id = req))
      FROM held h
      JOIN um.permissions p ON p.id = h.perm_id
      LEFT JOIN um.modules m ON m.id = p.module_id
     WHERE p.deleted_at IS NULL AND p.is_enabled
       AND (m.id IS NULL OR (m.deleted_at IS NULL AND m.is_enabled))
     ORDER BY p.id;
$$;
COMMENT ON FUNCTION auth.user_permissions()
    IS '合并权限集：角色权限（user_role/role_perm 有效期过滤）+ 直接权限（valid_until 过滤）+ 启用状态过滤；含前置依赖缺口标记';

-- 权限判定，identifier 命中且前置依赖全部满足
CREATE OR REPLACE FUNCTION auth.has_permission(p_identifier text)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
    SELECT EXISTS (SELECT 1 FROM auth.user_permissions() up
                    WHERE up.identifier = p_identifier AND NOT up.missing_required);
$$;
COMMENT ON FUNCTION auth.has_permission(text)
    IS '权限判定：合并权限集中命中 identifier 且前置依赖全部满足（依赖缺口->false）';

-- 闸口，供 SECURITY DEFINER 业务函数函数体内显式鉴权，绕过 rls 的代价
CREATE OR REPLACE FUNCTION auth.require_permission(p_identifier text)
RETURNS void
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, public
AS $$
BEGIN
    -- 维护直通，fms_admin（NOLOGIN 真实会话）与 postgres，bootstrap_base.sql 引导连接用户
    IF session_user NOT IN ('fms_admin', 'postgres') THEN
        IF auth.current_uid() IS NULL OR NOT auth.has_permission(p_identifier) THEN
            RAISE EXCEPTION 'require_permission: 无权限 %（user_id=%）', p_identifier, auth.current_uid();
        END IF;
    END IF;
END $$;
COMMENT ON FUNCTION auth.require_permission(text)
    IS '权限闸口：SECURITY DEFINER 业务函数函数体内显式鉴权；未注入上下文或权限不足抛异常';

-- rls（新表 FORCE ROW LEVEL SECURITY，策略 fail-closed）与 GRANT，um.permissions/role_perm/user_role 升级列不改变既有策略，无需变更
-- um.modules，任何已认证用户可读菜单树，写仅 fms_admin
ALTER TABLE um.modules ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.modules FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS modules_select ON um.modules;
CREATE POLICY modules_select ON um.modules
    FOR SELECT USING (deleted_at IS NULL AND auth.current_uid() IS NOT NULL);
DROP POLICY IF EXISTS modules_write ON um.modules;
CREATE POLICY modules_write ON um.modules
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

-- um.user_direct_permissions，本人可见自己授权，写仅 fms_admin，特批经管理通道
ALTER TABLE um.user_direct_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.user_direct_permissions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS udp_select ON um.user_direct_permissions;
CREATE POLICY udp_select ON um.user_direct_permissions
    FOR SELECT USING (deleted_at IS NULL AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
DROP POLICY IF EXISTS udp_write ON um.user_direct_permissions;
CREATE POLICY udp_write ON um.user_direct_permissions
    FOR ALL USING (current_user = 'fms_admin') WITH CHECK (current_user = 'fms_admin');

-- 表级授权（沙箱模型，fms_app 仅 CONNECT，业务访问经 SET ROLE 注入职责角色，L3 表级 + rls 行级收敛，admin 全量授权走 um.role_perm，不授 um.role_perm 写权限）
GRANT SELECT ON um.modules TO fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT ON um.user_direct_permissions TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- auth.* 权限判定函数族 EXECUTE
-- 先 REVOKE PUBLIC，本文件在 init/grants_base.sql 之后执行，bootstrap_base.sql 的
-- schema 级 REVOKE PUBLIC 早于本文件创建函数，新建函数默认 PUBLIC EXECUTE 会残留，
-- 须在此自收，与 grants_check 'SECURITY-DEFINER 函数 PUBLIC EXECUTE 清零' 断言对齐
REVOKE ALL ON FUNCTION auth.user_permissions() FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.has_permission(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.require_permission(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.user_permissions() TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.has_permission(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION auth.require_permission(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- operator 权限面（职责角色 accountant/approver/cashier），
-- 补授 wf.* 权限点，wf.fn_complete_task 的 require_permission

--    ('wf.instance.approve'/'wf.instance.reject') 闸口放行，期望矩阵，WF 任务
--    approve/reject/transfer = operator 业务操作

-- 闸口收敛，纵深防御，Java requireAdminRole 之后 sql 侧双闸，

-- auth.assign_role / auth.revoke_role，admin.user.grant 闸口，防自助分配/自升
-- auth.upsert_role，admin.role.create 闸口，角色创建/更新
-- auth.grant_role_permission / auth.revoke_role_permission，admin.role.grant 闸口
-- org.fn_upsert_company，org.company.create 闸口，公司创建/更新
--    函数体追加 PERFORM auth.require_permission，... 首行闸口
-- 全部幂等可重跑，psql -v ON_ERROR_STOP=1 执行

-- auth.update_user / auth.set_password / auth.create_user 不设闸，登录/找回/首登
-- 流程复用，且 Java 侧已按矩阵收敛，仅 admin 可经 /api/org/users 创建与改密

SET ROLE fms_admin;

-- operator 业务角色补授 WF 权限点（fms_admin_role 已有全量，此处仅补职责角色）

INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r
  JOIN um.permissions p
    ON p.identifier IN ('wf.instance.view','wf.instance.create',
                        'wf.instance.approve','wf.instance.reject',
                        'wf.definition.view')
 WHERE r.code IN ('accountant','approver','cashier')
   AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

-- 闸口收敛（sql 侧纵深防御）


-- auth.assign_role，仅持 admin.user.grant 者可分配角色，防自助分配/自升
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
    PERFORM auth.require_permission('admin.user.grant');
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
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
    ON CONFLICT (user_id, role_id) DO UPDATE SET deleted_at = NULL, updated_at = now();       -- 幂等重授（uk_user_role 兜底）
END
$$;

-- auth.revoke_role，仅持 admin.user.grant 者可回收角色
CREATE OR REPLACE FUNCTION auth.revoke_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    PERFORM auth.require_permission('admin.user.grant');
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
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

-- auth.upsert_role，仅持 admin.role.create 者可创建/更新角色，公司取调用者公司，兜底最早公司
CREATE OR REPLACE FUNCTION auth.upsert_role(p_code text, p_name text, p_is_system boolean)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, org, public, pg_catalog
AS $$
DECLARE
    v_id   uuid;
    v_comp uuid;
BEGIN
    PERFORM auth.require_permission('admin.role.create');
    IF btrim(p_code) = '' OR btrim(p_name) = '' THEN
        RAISE EXCEPTION '角色编码/名称不能为空' USING ERRCODE = 'P0001';
    END IF;
    v_comp := (SELECT company_id FROM um.users WHERE id = auth.current_uid() AND deleted_at IS NULL);
    IF v_comp IS NULL THEN
        v_comp := (SELECT id FROM org.companies WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1);
    END IF;
    INSERT INTO um.roles (company_id, code, name, is_system)
    VALUES (v_comp, btrim(p_code), btrim(p_name), COALESCE(p_is_system, false))
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, is_system = EXCLUDED.is_system
    RETURNING id INTO v_id;
    RETURN v_id;
END
$$;

-- auth.grant_role_permission，仅持 admin.role.grant 者可绑定角色-权限，权限点/角色存在性校验+幂等
CREATE OR REPLACE FUNCTION auth.grant_role_permission(p_role_id uuid, p_perm_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_perm_id   uuid;
    v_role_comp uuid;
BEGIN
    PERFORM auth.require_permission('admin.role.grant');
    SELECT id INTO v_perm_id FROM um.permissions
     WHERE code = btrim(p_perm_code) AND deleted_at IS NULL;
    IF v_perm_id IS NULL THEN
        RAISE EXCEPTION '权限点不存在：%', p_perm_code USING ERRCODE = 'P0001';
    END IF;
    SELECT company_id INTO v_role_comp FROM um.roles
     WHERE id = p_role_id AND deleted_at IS NULL;
    IF v_role_comp IS NULL THEN
        RAISE EXCEPTION '角色不存在' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO um.role_perm (company_id, role_id, perm_id)
    VALUES (v_role_comp, p_role_id, v_perm_id)
    ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();       -- 幂等（uk_role_perm 兜底）
END
$$;

-- auth.revoke_role_permission，仅持 admin.role.grant 者可解绑角色-权限，软删幂等
CREATE OR REPLACE FUNCTION auth.revoke_role_permission(p_role_id uuid, p_perm_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    PERFORM auth.require_permission('admin.role.grant');
    UPDATE um.role_perm rp
       SET deleted_at = now()
      FROM um.permissions p
     WHERE rp.perm_id = p.id
       AND p.code = btrim(p_perm_code)
       AND rp.role_id = p_role_id
       AND rp.deleted_at IS NULL;
END
$$;

-- org.fn_upsert_company，仅持 org.company.create 者可创建/更新公司，幂等 upsert + company_id 自引用回填
CREATE OR REPLACE FUNCTION org.fn_upsert_company(
    p_code text, p_name text, p_currency text,
    p_fiscal_year_start date, p_status text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = org, public, pg_catalog
AS $$
DECLARE
    v_id uuid;
BEGIN
    PERFORM auth.require_permission('org.company.create');
    IF btrim(p_code) = '' OR btrim(p_name) = '' THEN
        RAISE EXCEPTION '公司编码/名称不能为空' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO org.companies (code, name, currency, fiscal_year_start, status)
    VALUES (btrim(p_code), btrim(p_name),
            COALESCE(upper(btrim(p_currency)), 'CNY'),
            p_fiscal_year_start, COALESCE(p_status, 'ACTIVE'))
    ON CONFLICT (code) DO UPDATE
        SET name = EXCLUDED.name,
            currency = EXCLUDED.currency,
            fiscal_year_start = EXCLUDED.fiscal_year_start,
            status = EXCLUDED.status
    RETURNING id INTO v_id;
    -- 自引用回填，新建公司无任何 scope，can_access_org(company_id) 需 company_id=self.id 才可见
    UPDATE org.companies SET company_id = v_id
     WHERE id = v_id AND company_id IS NULL;
    RETURN v_id;
END
$$;

-- 保持函数授权不漂移，与授权矩阵一致
GRANT EXECUTE ON FUNCTION auth.assign_role(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.upsert_role(text,text,boolean) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.grant_role_permission(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role_permission(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION org.fn_upsert_company(text,text,text,date,text)
    TO fms_operator;

-- 文件末尾自查，wf 权限点对职责角色授权落库 + admin 权限点存在性
SELECT 'um_perm' AS file_name,
       (SELECT count(*) FROM um.role_perm rp
         JOIN um.roles r ON r.id = rp.role_id
         JOIN um.permissions p ON p.id = rp.perm_id
        WHERE r.code IN ('accountant','approver','cashier')
          AND p.identifier LIKE 'wf.%' AND rp.deleted_at IS NULL) AS wf_grants_cnt,
       (SELECT count(*) FROM um.permissions
         WHERE identifier IN ('admin.user.grant','admin.role.create','admin.role.grant','org.company.create')
           AND deleted_at IS NULL) AS admin_perm_cnt;

-- 数据范围配置，um.user_org_scope / um.user_dept_scope 的应用层写入通道。两表的 rls 写策略
-- 只允许数据库属主（uos_write 的 current_user = 'fms_admin'），应用连接无从直接写入，故收口为
-- 下列闸口函数，函数体内校验权限点并留审计。

-- 权限点 admin.user.scope，挂 admin.user 模块，与 admin.user.grant 同族
INSERT INTO um.permissions (company_id, module_id, code, identifier, name,
                            resource_type, action, permission_type, is_enabled, is_system)
SELECT '00000000-0000-7000-0000-000000000010', m.id, 'admin.user.scope', 'admin.user.scope',
       '用户管理：数据范围配置', 'admin', 'scope', 'ACTION', true, false
  FROM um.modules m
 WHERE m.identifier = 'admin.user' AND m.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r CROSS JOIN um.permissions p
 WHERE r.code IN ('fms_admin_role', 'sys_admin')
   AND p.identifier = 'admin.user.scope'
   AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

-- 权限点 um.user.mfa_reset，挂 admin.user 模块，管理员吊销他人双因子闸口 auth.admin_reset_mfa 的判定标识，
-- 与 admin.user.grant 同族，授予系统管理员角色，仅授予吊销能力，不含任何读密钥或代绑能力
INSERT INTO um.permissions (company_id, module_id, code, identifier, name,
                            resource_type, action, permission_type, is_enabled, is_system)
SELECT '00000000-0000-7000-0000-000000000010', m.id, 'um.user.mfa_reset', 'um.user.mfa_reset',
       '用户管理：重置双因子', 'admin', 'mfa_reset', 'ACTION', true, false
  FROM um.modules m
 WHERE m.identifier = 'admin.user' AND m.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r CROSS JOIN um.permissions p
 WHERE r.code IN ('fms_admin_role', 'sys_admin')
   AND p.identifier = 'um.user.mfa_reset'
   AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

-- 数据范围读取，返回 {orgIds:[...], depts:[{deptId, includeSubtree}]}，供配置界面回显
CREATE OR REPLACE FUNCTION auth.list_user_scopes(p_user_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, auth, public
AS $$
DECLARE
    v_orgs  jsonb;
    v_depts jsonb;
BEGIN
    PERFORM auth.require_permission('admin.user.scope');
    SELECT COALESCE(jsonb_agg(s.org_id ORDER BY s.org_id), '[]'::jsonb) INTO v_orgs
      FROM um.user_org_scope s
     WHERE s.user_id = p_user_id AND s.deleted_at IS NULL;
    SELECT COALESCE(jsonb_agg(jsonb_build_object('deptId', s.dept_id,
                                                 'includeSubtree', s.include_subtree)
                              ORDER BY s.dept_id), '[]'::jsonb) INTO v_depts
      FROM um.user_dept_scope s
     WHERE s.user_id = p_user_id AND s.deleted_at IS NULL;
    RETURN jsonb_build_object('orgIds', v_orgs, 'depts', v_depts);
END $$;
COMMENT ON FUNCTION auth.list_user_scopes(uuid)
    IS '数据范围读取：返回用户的公司范围与部门范围（含子树开关），供配置界面回显；仅 admin.user.scope 持有者与维护会话可调';
REVOKE ALL ON FUNCTION auth.list_user_scopes(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.list_user_scopes(uuid) TO fms_operator;

-- 公司范围写入，全量替换语义：先软删不在名单内的既有行，再按名单补齐。唯一约束 uk_user_org_scope
-- 建在 (user_id, org_id) 上且不随软删释放，故重新授予同一公司走 ON CONFLICT DO UPDATE 复活，
-- 而不是插入新行
CREATE OR REPLACE FUNCTION auth.set_user_org_scope(p_user_id uuid, p_org_ids uuid[])
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, auth, public
AS $$
DECLARE
    v_comp uuid;
    v_ids  uuid[] := COALESCE(p_org_ids, ARRAY[]::uuid[]);
BEGIN
    PERFORM auth.require_permission('admin.user.scope');
    SELECT company_id INTO v_comp FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_comp IS NULL THEN
        RAISE EXCEPTION '用户不存在' USING ERRCODE = 'P0001';
    END IF;
    UPDATE um.user_org_scope
       SET deleted_at = now(), updated_at = now()
     WHERE user_id = p_user_id AND deleted_at IS NULL
       AND NOT (org_id = ANY (v_ids));
    INSERT INTO um.user_org_scope (company_id, user_id, org_id, scope_type)
    SELECT v_comp, p_user_id, o, 'GRANTED'
      FROM unnest(v_ids) AS o
    ON CONFLICT (user_id, org_id) DO UPDATE
       SET deleted_at = NULL, updated_at = now();
    PERFORM audit.record('user_org_scope_set', 'um.user_org_scope', p_user_id,
        NULL, jsonb_build_object('org_ids', to_jsonb(v_ids)), NULL, NULL);
END $$;
COMMENT ON FUNCTION auth.set_user_org_scope(uuid, uuid[])
    IS '公司数据范围写入：全量替换，名单外既有行软删、名单内缺行补齐，公司 id 取目标用户所属公司；留审计';
REVOKE ALL ON FUNCTION auth.set_user_org_scope(uuid, uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.set_user_org_scope(uuid, uuid[]) TO fms_operator;

-- 部门范围写入，语义同公司范围，include_subtree 统一施加于名单内的全部部门
CREATE OR REPLACE FUNCTION auth.set_user_dept_scope(p_user_id uuid, p_dept_ids uuid[],
                                                    p_include_subtree boolean DEFAULT true)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, auth, public
AS $$
DECLARE
    v_comp uuid;
    v_ids  uuid[] := COALESCE(p_dept_ids, ARRAY[]::uuid[]);
    v_sub  boolean := COALESCE(p_include_subtree, true);
BEGIN
    PERFORM auth.require_permission('admin.user.scope');
    SELECT company_id INTO v_comp FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_comp IS NULL THEN
        RAISE EXCEPTION '用户不存在' USING ERRCODE = 'P0001';
    END IF;
    UPDATE um.user_dept_scope
       SET deleted_at = now(), updated_at = now()
     WHERE user_id = p_user_id AND deleted_at IS NULL
       AND NOT (dept_id = ANY (v_ids));
    INSERT INTO um.user_dept_scope (company_id, user_id, dept_id, scope_type, include_subtree)
    SELECT v_comp, p_user_id, d, 'GRANTED', v_sub
      FROM unnest(v_ids) AS d
    ON CONFLICT (user_id, dept_id) DO UPDATE
       SET deleted_at = NULL, include_subtree = v_sub, updated_at = now();
    PERFORM audit.record('user_dept_scope_set', 'um.user_dept_scope', p_user_id,
        NULL, jsonb_build_object('dept_ids', to_jsonb(v_ids), 'include_subtree', v_sub),
        NULL, NULL);
END $$;
COMMENT ON FUNCTION auth.set_user_dept_scope(uuid, uuid[], boolean)
    IS '部门数据范围写入：全量替换，名单外软删、名单内补齐，include_subtree 统一施加；留审计';
REVOKE ALL ON FUNCTION auth.set_user_dept_scope(uuid, uuid[], boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.set_user_dept_scope(uuid, uuid[], boolean) TO fms_operator;

-- 文件末尾自查
SELECT 'um_perm' AS file_name,
       (SELECT count(*) FROM um.modules)                    AS module_cnt,
       (SELECT count(*) FROM um.permissions)                AS perm_cnt,
       (SELECT count(*) FROM um.role_perm)                  AS role_perm_cnt,
       (SELECT count(*) FROM um.user_direct_permissions)    AS udp_cnt,
       (SELECT count(*) FROM um.permissions
         WHERE required_permissions <> '{}')                AS req_dep_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth')                          AS auth_fn_cnt,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'um'
           AND tablename IN ('modules','user_direct_permissions')) AS new_policy_cnt;
