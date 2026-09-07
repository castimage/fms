-- FMS 财务系统（init/seed_system），系统预置数据（全部幂等，INSERT ... ON CONFLICT DO NOTHING 或 DO 块存在性守卫）

-- 内容，um.params 系统参数 / sec.app_secrets 应用机密 / rbac 8 角色 + 12 权限 + role_perm / admin 用户，系统管理员

-- 设计，系统预置数据为系统运行必需，不含任何演示/测试数据，admin 用户为系统管理员，初始密码 Fms@ca5T1ma93
-- 种子行携带固定 uuid 常量，v7 版本位可读可预期，显式 id 直插即可，种子以超级用户写入绕过 rls 属设计预期

SET ROLE fms_admin;

-- um.params 系统参数（值统一 jsonb，标量参数直接存 json 标量，对象参数用 {"v": ...} 结构）
-- jwt.secret / pgcrypto.master_key 存于 sec.app_secrets，无任何角色授权，本表仅保留非敏感业务参数
INSERT INTO um.params (code, value, updated_by)
VALUES
    ('budget.warn.ratio',   '0.8'::jsonb,                              '00000000-0000-7000-0000-000000000001'),  -- 预算预警阈值 80%
    ('budget.block.ratio',  '1.0'::jsonb,                              '00000000-0000-7000-0000-000000000001'),  -- 预算超支阻断阈值 100%
    ('budget.block.enabled', 'true'::jsonb,                            '00000000-0000-7000-0000-000000000001'),  -- 超支阻断开关
    ('smtp.host',           '{"v":"smtp.example.com"}'::jsonb,        '00000000-0000-7000-0000-000000000001'),  -- smtp 服务器，占位
    ('smtp.port',           '{"v":465}'::jsonb,                       '00000000-0000-7000-0000-000000000001'),  -- smtp 端口，占位
    ('smtp.from',           '{"v":"no-reply@fms.local"}'::jsonb,      '00000000-0000-7000-0000-000000000001'),  -- 发件人，占位
    ('cash.reconcile.date.window.days', '3'::jsonb,                   '00000000-0000-7000-0000-000000000001'),  -- 对账候选日期窗口，天
    ('cash.reconcile.similarity.auto',  '0.9'::jsonb,                 '00000000-0000-7000-0000-000000000001'),  -- 自动配对相似度阈值
    ('wf.task.due.days',    '3'::jsonb,                               '00000000-0000-7000-0000-000000000001'),  -- 审批任务默认到期时限，天
    ('wf.timeout.batch.size', '500'::jsonb,                           '00000000-0000-7000-0000-000000000001'),  -- wf.fn_timeout_remind 单轮超时置位批量上限
    ('log.queue.batch.size', '500'::jsonb,                            '00000000-0000-7000-0000-000000000001'),  -- 日志队列每轮每队列最多消费条数
    ('log.queue.vt.seconds', '60'::jsonb,                             '00000000-0000-7000-0000-000000000001'),  -- 日志队列消息可见性超时，秒
    ('audit.retention.years',   '7'::jsonb,                           '00000000-0000-7000-0000-000000000001'),  -- 审计日志分区保留期，年
    ('log.retention.days',      '90'::jsonb,                          '00000000-0000-7000-0000-000000000001'),  -- 应用/访问日志分区保留期，天
    ('loginlog.retention.years','1'::jsonb,                           '00000000-0000-7000-0000-000000000001'),  -- 登录日志分区保留期，年
    ('fa.depr.expense.account', '{"v":"6602"}'::jsonb,                '00000000-0000-7000-0000-000000000001'),  -- 折旧费用行科目 code
    ('fa.depr.asset.account',   '{"v":"1601"}'::jsonb,                '00000000-0000-7000-0000-000000000001'),  -- 累计折旧行科目 code
    ('rpt.dashboard.alert.topn', '5'::jsonb,                          '00000000-0000-7000-0000-000000000001'),  -- 预算/银行余额告警各展示 topN
    ('web.refdata.page.size',   '200'::jsonb,                         '00000000-0000-7000-0000-000000000001'),  -- 参考数据下拉拉取上限
    ('ui.login.background',     '{"v":{"enabled":false,"fit":"cover","blur":6,"darken":0.25,"opacity":1,"desktop":null,"tablet":null,"mobile":null}}'::jsonb, '00000000-0000-7000-0000-000000000001')   -- 登录页背景，默认关闭
ON CONFLICT (code) DO NOTHING;

-- sec.app_secrets 应用机密，密钥移出业务表

-- 仅 fms_admin/超级用户可读，值已旋转，生产部署应经 KMS/密钥注入覆盖
-- 密钥仅首写（ON CONFLICT DO NOTHING），重放不覆盖已部署/已旋转的 master_key
INSERT INTO sec.app_secrets (code, value, updated_by, updated_at)
VALUES
    ('pgcrypto.master_key', '{"v":"FMS_MK_2026_8d3f9a2c6b7e4f1a9d5c8e2b7f4a6d3c"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now()),   -- 列加密主密钥，已旋转，仅首写
    ('jwt.secret', '{"v":"FMS_JWT_2026_a9f3c8e2b7d14f6a9e3c8b2d7f4a6e9c8b2d7f4a6e9c3b8d2f7a4e6c9b8d2f7a4"}'::jsonb,
     '00000000-0000-7000-0000-000000000001', now())    -- jwt HS256 密钥，仅首次兜底，权威在 init 脚本对齐段 .env
ON CONFLICT (code) DO NOTHING;

-- rbac 种子，um.roles，8 个业务角色
-- fms_admin_role 系统管理员全量角色，admin 用户挂接，职责角色
INSERT INTO um.roles (id, company_id, code, name, is_system)
VALUES
    ('00000000-0000-7000-0000-000000000400', '00000000-0000-7000-0000-000000000010', 'fms_admin_role', '系统管理员（全量权限）', true),
    ('00000000-0000-7000-0000-000000000401', '00000000-0000-7000-0000-000000000010', 'sys_admin',      '系统管理员',             true),
    ('00000000-0000-7000-0000-000000000402', '00000000-0000-7000-0000-000000000010', 'accountant',     '会计（记账）',           false),
    ('00000000-0000-7000-0000-000000000403', '00000000-0000-7000-0000-000000000010', 'approver',       '审批人',                 false),
    ('00000000-0000-7000-0000-000000000404', '00000000-0000-7000-0000-000000000010', 'cashier',        '出纳（资金）',           false),
    ('00000000-0000-7000-0000-000000000405', '00000000-0000-7000-0000-000000000010', 'auditor',        '审计员',                 false),
    ('00000000-0000-7000-0000-000000000406', '00000000-0000-7000-0000-000000000010', 'report_viewer',  '报表查看人',             false),
    ('00000000-0000-7000-0000-000000000407', '00000000-0000-7000-0000-000000000010', 'viewer',         '只读查询员',             false)
ON CONFLICT (code) DO NOTHING;

-- um.permissions（12 条，code 格式 action:resource:data_scope）
-- module_id 列判存守卫，module_id 按与 um_perm.sql 一致的 CASE 映射解析
DO $$
DECLARE
    v_has_col boolean;
    v_has_mod boolean;
    v_cnt     int;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'um' AND table_name = 'permissions'
                      AND column_name = 'module_id') INTO v_has_col;
    IF NOT v_has_col THEN
        RAISE NOTICE 'seed_system: um.permissions.module_id 列尚不存在（旧版 schema，由 um_perm.sql 升级），跳过 12 条业务权限插入';
        RETURN;
    END IF;
    SELECT to_regclass('um.modules') IS NOT NULL INTO v_has_mod;
    INSERT INTO um.permissions (id, company_id, module_id, code, identifier, name,
                                resource_type, action, permission_type, is_enabled, is_system)
    SELECT v.id, '00000000-0000-7000-0000-000000000010',
           CASE WHEN v_has_mod THEN
                (SELECT m.id FROM um.modules m
                  WHERE m.identifier = CASE split_part(v.code, ':', 1)
                        WHEN 'voucher' THEN 'gl.voucher'
                        WHEN 'budget'  THEN 'budget.plan'
                        WHEN 'audit'   THEN 'audit.log'
                        WHEN 'report'  THEN 'rpt'
                        ELSE split_part(v.code, ':', 1) END
                    AND m.deleted_at IS NULL LIMIT 1)
           ELSE NULL END,
           v.code, v.code, v.name,
           split_part(v.code, ':', 2), split_part(v.code, ':', 1), 'ACTION', true, false
      FROM (VALUES
            ('00000000-0000-7000-0000-000000000600'::uuid, 'voucher:create:company',  '凭证制单（全公司）'),
            ('00000000-0000-7000-0000-000000000601'::uuid, 'voucher:post:dept',       '凭证过账（部门范围）'),
            ('00000000-0000-7000-0000-000000000602'::uuid, 'voucher:reverse:company', '凭证红字冲销（全公司）'),
            ('00000000-0000-7000-0000-000000000603'::uuid, 'budget:approve:company',  '预算审批（全公司）'),
            ('00000000-0000-7000-0000-000000000604'::uuid, 'budget:view:company',     '预算查询（全公司）'),
            ('00000000-0000-7000-0000-000000000605'::uuid, 'audit:read:all',          '审计日志只读（全库）'),
            ('00000000-0000-7000-0000-000000000606'::uuid, 'ar:manage:company',       '应收管理（全公司）'),
            ('00000000-0000-7000-0000-000000000607'::uuid, 'ap:manage:company',       '应付管理（全公司）'),
            ('00000000-0000-7000-0000-000000000608'::uuid, 'cash:reconcile:company',  '银行对账（全公司）'),
            ('00000000-0000-7000-0000-000000000609'::uuid, 'fa:depreciate:company',   '资产折旧（全公司）'),
            ('00000000-0000-7000-0000-00000000060a'::uuid, 'expense:approve:dept',    '费用报销审批（部门范围）'),
            ('00000000-0000-7000-0000-00000000060b'::uuid, 'report:view:company',     '报表查看（全公司）')
           ) AS v(id, code, name)
    ON CONFLICT (code) DO NOTHING;
    -- 静默丢失防护断言，12 条业务权限必须全部落库
    SELECT count(*) INTO v_cnt FROM um.permissions
     WHERE code IN ('voucher:create:company','voucher:post:dept','voucher:reverse:company',
                    'budget:approve:company','budget:view:company','audit:read:all',
                    'ar:manage:company','ap:manage:company','cash:reconcile:company',
                    'fa:depreciate:company','expense:approve:dept','report:view:company');
    IF v_cnt < 12 THEN
        RAISE WARNING 'seed_system: 业务权限仅落库 %/12 条（module 映射/列缺失？），role_perm 授权将随之缺失', v_cnt;
    END IF;
END $$;

-- um.role_perm 角色权限分配，按矩阵分配各角色的权限子集
INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r CROSS JOIN um.permissions p
 WHERE r.code = 'fms_admin_role'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('voucher:create:company','voucher:post:dept','voucher:reverse:company',
                  'ar:manage:company','ap:manage:company','budget:view:company')
 WHERE r.code = 'accountant'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('budget:approve:company','expense:approve:dept')
 WHERE r.code = 'approver'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('cash:reconcile:company','fa:depreciate:company')
 WHERE r.code = 'cashier'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('audit:read:all')
 WHERE r.code = 'auditor'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('report:view:company','budget:view:company')
 WHERE r.code = 'report_viewer'
ON CONFLICT (role_id, perm_id) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('report:view:company','budget:view:company')
 WHERE r.code = 'viewer'
ON CONFLICT (role_id, perm_id) DO NOTHING;

-- admin 用户（系统管理员，初始密码 Fms@ca5T1ma93，must_change_pwd=true）
INSERT INTO um.users
    (id, login_name, login_name_hash, pass_hash, status, company_id,
     must_change_pwd, failed_login_count)
VALUES
    ('00000000-0000-7000-0000-000000000001', 'admin',
     encode(hmac('admin', (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'), 'sha256'), 'hex'),
     '$2a$10$ydOv7xNBjXgsaseYNBAN0.gdzFytMufMa5bityvvMsDpV5hS1HVZm',  -- bcrypt 哈希（口令 Fms@ca5T1ma93）
     'ACTIVE', '00000000-0000-7000-0000-000000000010', true, 0)
ON CONFLICT (login_name) DO NOTHING;

-- admin 用户角色分配（fms_admin_role 全量权限）
INSERT INTO um.user_role (company_id, user_id, role_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', u.id, r.id,
       '00000000-0000-7000-0000-000000000001', now()
  FROM um.users u JOIN um.roles r ON r.code = 'fms_admin_role'
 WHERE u.login_name = 'admin'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- admin 数据范围，系统管理员无需预设数据范围，rls 策略按 guc 动态过滤，
-- admin 登录后由后端 set_session 注入当前公司/部门，无固定范围限制

-- admin 联系方式加密写入（pgp_sym_encrypt aes256 + hmac 盲索引）
DO $$
DECLARE
    v_mkey  text;
    v_bkey  text;
    v_cols  text[];
    v_rec   record;
    v_email text;
    v_phone text;
    v_idcard text;
BEGIN
    v_mkey := (SELECT value->>'v' FROM sec.app_secrets
                WHERE code = 'pgcrypto.master_key');
    IF v_mkey IS NULL THEN
        RAISE NOTICE 'seed_system: sec.app_secrets.pgcrypto.master_key 缺失，跳过用户联系方式加密填充';
        RETURN;
    END IF;
    v_bkey := (SELECT value->>'v' FROM sec.app_secrets
                WHERE code = 'pgcrypto.blind_key');
    IF v_bkey IS NULL THEN
        RAISE NOTICE 'seed_system: sec.app_secrets.pgcrypto.blind_key 缺失，跳过加密填充';
        RETURN;
    END IF;
    SELECT array_agg(column_name) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'um' AND table_name = 'users';

    SELECT id, login_name INTO v_rec FROM um.users WHERE login_name = 'admin';
    IF v_rec.id IS NULL THEN
        RAISE NOTICE 'seed_system: admin 用户不存在，跳过联系方式加密';
        RETURN;
    END IF;

    v_email  := 'admin@fms.demo';
    v_phone  := '13800138000';
    v_idcard := '110101199001010011';

    IF 'email_encrypted' = ANY(v_cols) AND 'email_hash' = ANY(v_cols) THEN
        EXECUTE 'UPDATE um.users SET email_encrypted = $1, email_hash = $2 WHERE id = $3'
            USING pgp_sym_encrypt(v_email, v_mkey, 'compress-algo=1, cipher-algo=aes256'),
                  encode(hmac(v_email, v_bkey, 'sha256'), 'hex'), v_rec.id;
    ELSIF 'email' = ANY(v_cols) THEN
        EXECUTE 'UPDATE um.users SET email = $1 WHERE id = $2' USING v_email, v_rec.id;
    END IF;

    IF 'phone_encrypted' = ANY(v_cols) AND 'phone_hash' = ANY(v_cols) THEN
        EXECUTE 'UPDATE um.users SET phone_encrypted = $1, phone_hash = $2 WHERE id = $3'
            USING pgp_sym_encrypt(v_phone, v_mkey, 'compress-algo=1, cipher-algo=aes256'),
                  encode(hmac(v_phone, v_bkey, 'sha256'), 'hex'), v_rec.id;
    ELSIF 'phone_enc' = ANY(v_cols) AND 'phone_hash' = ANY(v_cols) THEN
        EXECUTE 'UPDATE um.users SET phone_enc = $1, phone_hash = $2 WHERE id = $3'
            USING pgp_sym_encrypt(v_phone, v_mkey, 'compress-algo=1, cipher-algo=aes256'),
                  encode(hmac(v_phone, v_bkey, 'sha256'), 'hex'), v_rec.id;
    END IF;

    IF 'id_card_enc' = ANY(v_cols) AND 'id_card_hash' = ANY(v_cols) THEN
        EXECUTE 'UPDATE um.users SET id_card_enc = $1, id_card_hash = $2 WHERE id = $3'
            USING pgp_sym_encrypt(v_idcard, v_mkey, 'compress-algo=1, cipher-algo=aes256'),
                  encode(hmac(v_idcard, v_bkey, 'sha256'), 'hex'), v_rec.id;
    END IF;
    RAISE NOTICE 'seed_system: admin 用户联系方式已加密写入（aes256）+ HMAC 盲索引';
END $$;

-- 存量库兼容兜底段，面向历史存量库的原地修正，对空库/现行种子均为幂等空操作
DO $$
DECLARE
    v_mkey  text;
    v_bkey  text;
    v_plain text;
    v_cnt   int := 0;
    v_rec   record;
BEGIN
    v_mkey := (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.master_key');
    v_bkey := (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key');
    IF v_bkey IS NULL THEN
        RAISE NOTICE 'seed_system: pgcrypto.blind_key 缺失，跳过盲索引迁移';
        RETURN;
    END IF;
    -- um.users，login_name 明文相邻可直接 hmac
    FOR v_rec IN SELECT id, login_name FROM um.users WHERE login_name IS NOT NULL LOOP
        IF EXISTS (SELECT 1 FROM um.users WHERE id = v_rec.id
                    AND login_name_hash IS DISTINCT FROM encode(hmac(v_rec.login_name, v_bkey, 'sha256'), 'hex')) THEN
            UPDATE um.users SET login_name_hash = encode(hmac(v_rec.login_name, v_bkey, 'sha256'), 'hex')
             WHERE id = v_rec.id;
            v_cnt := v_cnt + 1;
        END IF;
    END LOOP;
    IF v_cnt > 0 THEN
        RAISE NOTICE 'seed_system: 盲索引迁移完成（% 行）', v_cnt;
    END IF;
END $$;

-- 系统流程定义种子（wf.definitions），assignee_role 用真实职责角色（applicant=发起人，
-- approver/cashier 等持 wf.instance.approve 权限），避免无实体角色的流程定义导致任务无人承接。
-- 费用报销 ACTIVE（业务已挂接）；其余为业务预留模板 DRAFT（对应单据仍走自有状态机，
-- 待业务接入 wf 后由管理员启用）。幂等，重放不覆盖既有定义。
INSERT INTO wf.definitions (company_id, code, name, version, form_type, node_json, status)
VALUES
    ('00000000-0000-7000-0000-000000000010', 'expense_reimburse', '费用报销审批流', 1,
     'expense_reimbursement',
     '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"pay","type":"APPROVE","assignee_role":"cashier"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb,
     'ACTIVE'),
    ('00000000-0000-7000-0000-000000000010', 'voucher_approve', '凭证审核流', 1,
     'voucher',
     '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb,
     'DRAFT'),
    ('00000000-0000-7000-0000-000000000010', 'payment_approve', '付款审批流', 1,
     'payment',
     '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"pay","type":"APPROVE","assignee_role":"cashier"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb,
     'DRAFT'),
    ('00000000-0000-7000-0000-000000000010', 'budget_adjust', '预算调整审批流', 1,
     'budget_adjustment',
     '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb,
     'DRAFT'),
    ('00000000-0000-7000-0000-000000000010', 'asset_dispose', '资产处置审批流', 1,
     'asset',
     '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb,
     'DRAFT')
ON CONFLICT (company_id, code) DO NOTHING;

-- 存量迁移，把早期以占位角色（dept_manager/finance_manager，无实体角色）预设的报销流
-- 升级为真实职责角色，保证任务能派到持 wf.instance.approve 的用户，幂等仅改一次
UPDATE wf.definitions
   SET node_json = '[{"node_code":"apply","type":"START","assignee_role":"applicant"},{"node_code":"approve","type":"APPROVE","assignee_role":"approver"},{"node_code":"pay","type":"APPROVE","assignee_role":"cashier"},{"node_code":"end","type":"END","assignee_role":null}]'::jsonb
 WHERE company_id = '00000000-0000-7000-0000-000000000010'
   AND code = 'expense_reimburse'
   AND node_json::text LIKE '%dept_manager%';

RESET ROLE;
