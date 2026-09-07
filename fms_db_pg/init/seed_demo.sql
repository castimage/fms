-- FMS 财务系统（init/seed_demo），演示数据（全部幂等，INSERT ... ON CONFLICT DO NOTHING 或 DO 块存在性守卫）

-- 内容，demo 公司与部门树（ltree）/ 4 演示用户（operator01/viewer01/auditor01/report01）/ user_role 与数据范围 / gl.periods（2026-07/2026-08 OPEN）/ 会计科目表，5 大类 21 一级 + 2 二级/ 预算计划 / 2 张演示凭证（POSTED 平衡 + DRAFT 草稿）/ 演示单据（ar 客户、ap 供应商、cash 银行账户加密写、wf 审批流、expense 报销单、wf 审批实例任务）

-- 设计，演示数据为可选加载，用于系统演示和测试，所有用户初始密码 Fms@ca5T1ma93
-- 种子行携带固定 uuid 常量，v7 版本位可读可预期，显式 id 直插即可，种子以超级用户写入绕过 rls 属设计预期

SET ROLE fms_admin;

-- demo 公司（id 固定 COMPANY_UID 常量显式插入）
DO $$
DECLARE
    v_cols text[];
    v_sql  text;
    v_id   uuid;
BEGIN
    SELECT array_agg(column_name) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'org' AND table_name = 'companies';
    v_sql := 'INSERT INTO org.companies (id, code, name, currency, fiscal_year_start, status'
          || CASE WHEN 'company_id' = ANY(v_cols) THEN ', company_id' ELSE '' END
          || ') VALUES (''00000000-0000-7000-0000-000000000010'', $1, $2, $3, $4, $5'
          || CASE WHEN 'company_id' = ANY(v_cols) THEN ', ''00000000-0000-7000-0000-000000000010''' ELSE '' END
          || ') ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status'
          || ' RETURNING id';
    EXECUTE v_sql INTO v_id
        USING 'COM-001', 'FMS演示科技有限公司', 'CNY', DATE '2026-01-01', 'ACTIVE';
    RAISE NOTICE 'seed: demo 公司已就绪 id=% (COM-001)', v_id;
END $$;

-- 部门树（ltree path，总部 1 到财务部 1.1）
INSERT INTO org.departments (id, company_id, code, name, path, parent_id, level)
VALUES ('00000000-0000-7000-0000-000000000020', '00000000-0000-7000-0000-000000000010',
        'HQ', '总部', '1'::ltree, NULL, 1)
ON CONFLICT (company_id, path) DO NOTHING;

INSERT INTO org.departments (id, company_id, code, name, path, parent_id, level)
VALUES ('00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010',
        'FIN', '财务部', '1.1'::ltree,
        (SELECT id FROM org.departments
          WHERE company_id = '00000000-0000-7000-0000-000000000010'
            AND path = '1'::ltree AND deleted_at IS NULL),
        2)
ON CONFLICT (company_id, path) DO NOTHING;

-- 演示用户（4 个，operator01/viewer01/auditor01/report01，密码 Fms@ca5T1ma93）
INSERT INTO um.users
    (login_name, login_name_hash, pass_hash, status, company_id,
     must_change_pwd, failed_login_count)
SELECT u.login_name,
       encode(hmac(u.login_name, (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'), 'sha256'), 'hex'),
       crypt(u.pwd, gen_salt('bf', 10)), 'ACTIVE', '00000000-0000-7000-0000-000000000010', false, 0
  FROM (VALUES ('operator01', 'Fms@ca5T1ma93'), ('viewer01', 'Fms@ca5T1ma93'),
               ('auditor01', 'Fms@ca5T1ma93'), ('report01', 'Fms@ca5T1ma93')) AS u(login_name, pwd)
ON CONFLICT (login_name) DO NOTHING;

-- 演示用户联系方式加密写入（pgp_sym_encrypt aes256 + hmac 盲索引）
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
        RAISE NOTICE 'seed_demo: sec.app_secrets.pgcrypto.master_key 缺失，跳过用户联系方式加密填充';
        RETURN;
    END IF;
    v_bkey := (SELECT value->>'v' FROM sec.app_secrets
                WHERE code = 'pgcrypto.blind_key');
    IF v_bkey IS NULL THEN
        RAISE NOTICE 'seed_demo: sec.app_secrets.pgcrypto.blind_key 缺失，跳过加密填充';
        RETURN;
    END IF;
    SELECT array_agg(column_name) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'um' AND table_name = 'users';

    FOR v_rec IN
        SELECT id, login_name FROM um.users
         WHERE login_name IN ('operator01','viewer01','auditor01','report01')
    LOOP
        v_email  := CASE v_rec.login_name WHEN 'operator01' THEN 'operator01@fms.demo'
                     WHEN 'viewer01'   THEN 'viewer01@fms.demo'
                     WHEN 'auditor01'  THEN 'auditor01@fms.demo'
                     ELSE 'report01@fms.demo' END;
        v_phone  := CASE v_rec.login_name WHEN 'operator01' THEN '13800138001'
                     WHEN 'viewer01'   THEN '13800138002'
                     WHEN 'auditor01'  THEN '13800138003'
                     ELSE '13800138004' END;
        v_idcard := CASE v_rec.login_name WHEN 'operator01' THEN '110101199001010021'
                     WHEN 'viewer01'   THEN '110101199001010031'
                     WHEN 'auditor01'  THEN '110101199001010041'
                     ELSE '110101199001010051' END;

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
    END LOOP;
    RAISE NOTICE 'seed_demo: 演示用户 email/phone/id_card 已加密写入（aes256）+ HMAC 盲索引';
END $$;

-- 演示用户角色分配与数据范围
INSERT INTO um.user_role (company_id, user_id, role_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', u.id, r.id,
       '00000000-0000-7000-0000-000000000001', now()
  FROM um.users u JOIN um.roles r
    ON (u.login_name = 'operator01' AND r.code = 'accountant')
    OR (u.login_name = 'viewer01'   AND r.code = 'viewer')
    OR (u.login_name = 'auditor01'  AND r.code = 'auditor')
    OR (u.login_name = 'report01'   AND r.code = 'report_viewer')
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO um.user_org_scope (company_id, user_id, org_id)
SELECT '00000000-0000-7000-0000-000000000010', u.id,
       '00000000-0000-7000-0000-000000000010' FROM um.users u
 WHERE u.login_name IN ('operator01','viewer01','auditor01','report01')
ON CONFLICT (user_id, org_id) DO NOTHING;

INSERT INTO um.user_dept_scope (company_id, user_id, dept_id)
SELECT '00000000-0000-7000-0000-000000000010', u.id, d.id
  FROM um.users u CROSS JOIN org.departments d
 WHERE u.login_name IN ('operator01','viewer01','auditor01','report01')
   AND d.company_id = '00000000-0000-7000-0000-000000000010' AND d.deleted_at IS NULL
ON CONFLICT (user_id, dept_id) DO NOTHING;

-- gl.periods 会计期间（2026-07/2026-08 均 OPEN）
INSERT INTO gl.periods (company_id, period, status, closed_by, closed_at)
VALUES ('00000000-0000-7000-0000-000000000010', '2026-07', 'OPEN',    NULL, NULL),
       ('00000000-0000-7000-0000-000000000010', '2026-08', 'OPEN',   NULL, NULL)
ON CONFLICT (company_id, period) DO NOTHING;

-- 会计科目表，5 大类 21 一级 + 2 二级
INSERT INTO gl.chart_accounts
    (id, company_id, code, name, path, parent_id, level, is_leaf,
     account_type, direction, is_foreign_currency, status)
VALUES
    ('00000000-0000-7000-0000-000000000100','00000000-0000-7000-0000-000000000010','1001','库存现金',      '1001'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000101','00000000-0000-7000-0000-000000000010','1002','银行存款',      '1002'::ltree,    NULL, 1, false, 'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000102','00000000-0000-7000-0000-000000000010','1012','其他货币资金',  '1012'::ltree,    NULL, 1, true,  'ASSET','DEBIT',true, 'ACTIVE'),
    ('00000000-0000-7000-0000-000000000103','00000000-0000-7000-0000-000000000010','1122','应收账款',      '1122'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000104','00000000-0000-7000-0000-000000000010','1123','预付账款',      '1123'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000105','00000000-0000-7000-0000-000000000010','1221','其他应收款',    '1221'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000106','00000000-0000-7000-0000-000000000010','1405','库存商品',      '1405'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000107','00000000-0000-7000-0000-000000000010','1601','固定资产',      '1601'::ltree,    NULL, 1, true,  'ASSET','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000108','00000000-0000-7000-0000-000000000010','2202','应付账款',      '2202'::ltree,    NULL, 1, true,  'LIABILITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000109','00000000-0000-7000-0000-000000000010','2203','预收账款',      '2203'::ltree,    NULL, 1, true,  'LIABILITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010a','00000000-0000-7000-0000-000000000010','2211','应付职工薪酬',  '2211'::ltree,    NULL, 1, true,  'LIABILITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010b','00000000-0000-7000-0000-000000000010','2221','应交税费',      '2221'::ltree,    NULL, 1, true,  'LIABILITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010c','00000000-0000-7000-0000-000000000010','4101','实收资本',      '4101'::ltree,    NULL, 1, true,  'EQUITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010d','00000000-0000-7000-0000-000000000010','4104','利润分配',      '4104'::ltree,    NULL, 1, true,  'EQUITY','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010e','00000000-0000-7000-0000-000000000010','6001','主营业务收入',  '6001'::ltree,    NULL, 1, true,  'REVENUE','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-00000000010f','00000000-0000-7000-0000-000000000010','6051','其他业务收入',  '6051'::ltree,    NULL, 1, true,  'REVENUE','CREDIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000110','00000000-0000-7000-0000-000000000010','6401','主营业务成本',  '6401'::ltree,    NULL, 1, true,  'EXPENSE','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000111','00000000-0000-7000-0000-000000000010','6402','其他业务成本',  '6402'::ltree,    NULL, 1, true,  'EXPENSE','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000112','00000000-0000-7000-0000-000000000010','6601','销售费用',      '6601'::ltree,    NULL, 1, true,  'EXPENSE','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000113','00000000-0000-7000-0000-000000000010','6602','管理费用',      '6602'::ltree,    NULL, 1, true,  'EXPENSE','DEBIT',false,'ACTIVE'),
    ('00000000-0000-7000-0000-000000000114','00000000-0000-7000-0000-000000000010','6603','财务费用',      '6603'::ltree,    NULL, 1, true,  'EXPENSE','DEBIT',false,'ACTIVE')
ON CONFLICT (company_id, code) DO NOTHING;

-- 二级科目，1002 银行存款下设 1002.01/1002.02
INSERT INTO gl.chart_accounts
    (id, company_id, code, name, path, parent_id, level, is_leaf,
     account_type, direction, is_foreign_currency, status)
SELECT '00000000-0000-7000-0000-000000000115', '00000000-0000-7000-0000-000000000010',
       '100201', '银行存款-中国工商银行基本户', '1002.01'::ltree, ca.id, 2, true,
       'ASSET','DEBIT',false,'ACTIVE'
  FROM gl.chart_accounts ca
 WHERE ca.company_id = '00000000-0000-7000-0000-000000000010'
   AND ca.code = '1002' AND ca.deleted_at IS NULL
ON CONFLICT (company_id, code) DO NOTHING;

INSERT INTO gl.chart_accounts
    (id, company_id, code, name, path, parent_id, level, is_leaf,
     account_type, direction, is_foreign_currency, status)
SELECT '00000000-0000-7000-0000-000000000116', '00000000-0000-7000-0000-000000000010',
       '100202', '银行存款-中国建设银行一般户', '1002.02'::ltree, ca.id, 2, true,
       'ASSET','DEBIT',false,'ACTIVE'
  FROM gl.chart_accounts ca
 WHERE ca.company_id = '00000000-0000-7000-0000-000000000010'
   AND ca.code = '1002' AND ca.deleted_at IS NULL
ON CONFLICT (company_id, code) DO NOTHING;

-- 预算计划（2026-08 财务部×管理费用 6602×1,000,000，APPROVED）
INSERT INTO budget.budget_plans
    (company_id, period, account_id, dept_id, amount, version, status,
     approved_by, approved_at, created_by)
SELECT '00000000-0000-7000-0000-000000000010', '2026-08', ca.id, d.id, 1000000.00,
       1, 'APPROVED', u.id, now(), u.id
  FROM gl.chart_accounts ca
  CROSS JOIN org.departments d
  CROSS JOIN um.users u
 WHERE ca.company_id = '00000000-0000-7000-0000-000000000010' AND ca.code = '6602'
   AND d.company_id = '00000000-0000-7000-0000-000000000010'
   AND d.path = '1.1'::ltree AND d.deleted_at IS NULL
   AND u.login_name = 'admin'
ON CONFLICT (company_id, period, account_id, dept_id, version) DO NOTHING;

-- 演示凭证（2 张，POSTED 平衡 + DRAFT 草稿）

-- 演示凭证 1，POSTED 平衡凭证（借 1002.01 银行存款 10,000 / 贷 6001 主营业务收入 10,000）
DO $$
DECLARE
    v_vid  uuid;
    v_cols text[];
    v_sql  text;
    v_acc1 uuid;   -- 1002.01 银行存款
    v_acc2 uuid;   -- 6001 主营业务收入
BEGIN
    IF EXISTS (SELECT 1 FROM gl.vouchers
                WHERE company_id = '00000000-0000-7000-0000-000000000010'
                  AND period = '2026-08' AND status = 'POSTED'
                  AND source_type = 'SEED_DEMO' AND deleted_at IS NULL) THEN
        RAISE NOTICE 'seed_demo: 演示过账凭证（SEED_DEMO/POSTED/2026-08）已存在，跳过创建';
        RETURN;
    END IF;

    SELECT array_agg(column_name) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'gl' AND table_name = 'vouchers';

    v_sql := 'INSERT INTO gl.vouchers
                  (id, company_id, period, voucher_date, status, source_type, created_by,
                   total_debit, total_credit'
          || CASE WHEN 'dept_id' = ANY(v_cols) THEN ', dept_id' ELSE '' END
          || CASE WHEN 'is_private' = ANY(v_cols) THEN ', is_private' ELSE '' END
          || ') VALUES (''00000000-0000-7000-0000-000000000200'',
                        ''00000000-0000-7000-0000-000000000010'',
                        ''2026-08'', DATE ''2026-08-03'', ''DRAFT'', ''SEED_DEMO'',
                        ''00000000-0000-7000-0000-000000000001'', 10000.00, 10000.00'
          || CASE WHEN 'dept_id' = ANY(v_cols) THEN ', ''00000000-0000-7000-0000-000000000021''' ELSE '' END
          || CASE WHEN 'is_private' = ANY(v_cols) THEN ', false' ELSE '' END
          || ') RETURNING id';
    EXECUTE v_sql INTO v_vid;

    SELECT id INTO v_acc1 FROM gl.chart_accounts
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = '100201' AND deleted_at IS NULL;
    SELECT id INTO v_acc2 FROM gl.chart_accounts
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = '6001' AND deleted_at IS NULL;

    INSERT INTO gl.voucher_lines
        (id, voucher_id, line_no, account_id, summary, debit, credit, currency, dept_id, company_id)
    VALUES
        ('00000000-0000-7000-0000-000000000300', v_vid, 1, v_acc1,
         '演示：收到客户货款，银行存款增加（已过账凭证）', 10000.00, 0.00, 'CNY',
         '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010'),
        ('00000000-0000-7000-0000-000000000301', v_vid, 2, v_acc2,
         '演示：确认主营业务收入（已过账凭证）',           0.00, 10000.00, 'CNY',
         '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010')
    ON CONFLICT (voucher_id, line_no) WHERE deleted_at IS NULL DO NOTHING;

    -- 状态机流转（SoD，制单人 admin 与审核人 operator01 分离）
    UPDATE gl.vouchers SET status = 'SUBMITTED'
     WHERE id = v_vid AND status = 'DRAFT';
    UPDATE gl.vouchers SET status = 'APPROVED',
           approved_by = (SELECT id FROM um.users WHERE login_name = 'operator01'),
           approved_at = now()
     WHERE id = v_vid AND status = 'SUBMITTED';
    UPDATE gl.vouchers SET status = 'POSTED'
     WHERE id = v_vid AND status = 'APPROVED';

    RAISE NOTICE 'seed_demo: 演示过账凭证创建完成 id=%（2026-08，借银行/贷收入 10,000.00，制单 admin/审核 operator01，状态 POSTED）', v_vid;
END $$;

-- 演示凭证 2，DRAFT 草稿凭证（借 6602 管理费用 2,500 / 贷 1001 库存现金 2,500）
DO $$
DECLARE
    v_vid  uuid;
    v_cols text[];
    v_sql  text;
    v_acc1 uuid;   -- 6602 管理费用
    v_acc2 uuid;   -- 1001 库存现金
BEGIN
    IF EXISTS (SELECT 1 FROM gl.vouchers
                WHERE company_id = '00000000-0000-7000-0000-000000000010'
                  AND period = '2026-08' AND status = 'DRAFT'
                  AND source_type = 'SEED_DEMO' AND deleted_at IS NULL) THEN
        RAISE NOTICE 'seed_demo: 演示草稿凭证（SEED_DEMO/DRAFT/2026-08）已存在，跳过创建';
        RETURN;
    END IF;

    SELECT array_agg(column_name) INTO v_cols
      FROM information_schema.columns
     WHERE table_schema = 'gl' AND table_name = 'vouchers';

    v_sql := 'INSERT INTO gl.vouchers
                  (id, company_id, period, voucher_date, status, source_type, created_by,
                   total_debit, total_credit'
          || CASE WHEN 'dept_id' = ANY(v_cols) THEN ', dept_id' ELSE '' END
          || CASE WHEN 'is_private' = ANY(v_cols) THEN ', is_private' ELSE '' END
          || ') VALUES (''00000000-0000-7000-0000-000000000201'',
                        ''00000000-0000-7000-0000-000000000010'',
                        ''2026-08'', DATE ''2026-08-05'', ''DRAFT'', ''SEED_DEMO'',
                        ''00000000-0000-7000-0000-000000000001'', 2500.00, 2500.00'
          || CASE WHEN 'dept_id' = ANY(v_cols) THEN ', ''00000000-0000-7000-0000-000000000021''' ELSE '' END
          || CASE WHEN 'is_private' = ANY(v_cols) THEN ', false' ELSE '' END
          || ') RETURNING id';
    EXECUTE v_sql INTO v_vid;

    SELECT id INTO v_acc1 FROM gl.chart_accounts
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = '6602' AND deleted_at IS NULL;
    SELECT id INTO v_acc2 FROM gl.chart_accounts
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = '1001' AND deleted_at IS NULL;

    INSERT INTO gl.voucher_lines
        (id, voucher_id, line_no, account_id, summary, debit, credit, currency, dept_id, company_id)
    VALUES
        ('00000000-0000-7000-0000-000000000302', v_vid, 1, v_acc1,
         '演示：差旅费报销-管理费用（草稿凭证）', 2500.00, 0.00, 'CNY',
         '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010'),
        ('00000000-0000-7000-0000-000000000303', v_vid, 2, v_acc2,
         '演示：差旅费报销-库存现金（草稿凭证）', 0.00, 2500.00, 'CNY',
         '00000000-0000-7000-0000-000000000021', '00000000-0000-7000-0000-000000000010')
    ON CONFLICT (voucher_id, line_no) WHERE deleted_at IS NULL DO NOTHING;

    RAISE NOTICE 'seed_demo: 演示草稿凭证创建完成 id=%（2026-08，借管理费用/贷库存现金 2,500.00，状态 DRAFT）', v_vid;
END $$;

-- 演示单据（ar 客户、ap 供应商、cash 银行账户、wf 审批流、expense 报销单）

-- ar 客户
INSERT INTO ar.customers (company_id, code, name, tax_no, credit_limit, status)
VALUES ('00000000-0000-7000-0000-000000000010', 'CUS-001', 'FMS演示客户科技有限公司',
        '91310000MA1FL1C23X', 500000.00, 'ACTIVE')
ON CONFLICT (company_id, code) WHERE deleted_at IS NULL DO NOTHING;

-- ap 供应商
INSERT INTO ap.suppliers (company_id, code, name, tax_no, credit_limit, status)
VALUES ('00000000-0000-7000-0000-000000000010', 'SUP-001', 'FMS演示供应商有限公司',
        '91310000MA1FL1D45Y', 300000.00, 'ACTIVE')
ON CONFLICT (company_id, code) WHERE deleted_at IS NULL DO NOTHING;

-- cash 银行账户（pgcrypto 加密写 + hmac 盲索引）
INSERT INTO cash.bank_accounts
    (company_id, bank_name, account_no_enc, account_no_hash, currency, account_type, status)
SELECT '00000000-0000-7000-0000-000000000010', '中国工商银行北京中关村支行',
       pgp_sym_encrypt('6222020200012345678',
                       (SELECT value->>'v' FROM sec.app_secrets
                         WHERE code = 'pgcrypto.master_key')::text,
                       'compress-algo=1, cipher-algo=aes256'),
       encode(hmac('6222020200012345678',
                   (SELECT value->>'v' FROM sec.app_secrets
                     WHERE code = 'pgcrypto.blind_key')::text, 'sha256'), 'hex'),
       'CNY', 'CORPORATE', 'ACTIVE'
ON CONFLICT (account_no_hash) DO NOTHING;

-- 费用报销审批流定义承载于系统种子 init/seed_system.sql（assignee_role 用真实职责角色），
-- 本文件仅保留报销单据与审批实例的演示数据，不重复插定义

-- expense 报销单（DRAFT）+ 明细
DO $$
DECLARE v_rid uuid;
BEGIN
    SELECT id INTO v_rid FROM expense.reimbursements
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND title = '演示：差旅费报销（草稿）' AND deleted_at IS NULL
     LIMIT 1;
    IF v_rid IS NOT NULL THEN
        RAISE NOTICE 'seed_demo: 演示报销单已存在（id=%），跳过创建', v_rid;
        RETURN;
    END IF;
    INSERT INTO expense.reimbursements
        (company_id, applicant_id, dept_id, title, amount, currency, status, created_by)
    SELECT '00000000-0000-7000-0000-000000000010', u.id, d.id,
           '演示：差旅费报销（草稿）', 2500.00, 'CNY', 'DRAFT', u.id
      FROM um.users u
      CROSS JOIN org.departments d
     WHERE u.login_name = 'admin'
       AND d.company_id = '00000000-0000-7000-0000-000000000010'
       AND d.path = '1.1'::ltree AND d.deleted_at IS NULL
    RETURNING id INTO v_rid;
    INSERT INTO expense.reimbursement_lines
        (company_id, reimbursement_id, line_no, type, amount, trx_date, summary)
    VALUES
        ('00000000-0000-7000-0000-000000000010', v_rid, 1, 'TRAVEL', 1500.00, DATE '2026-08-04', '高铁票'),
        ('00000000-0000-7000-0000-000000000010', v_rid, 2, 'TRAVEL', 1000.00, DATE '2026-08-04', '住宿费')
    ON CONFLICT (reimbursement_id, line_no) WHERE deleted_at IS NULL DO NOTHING;
    RAISE NOTICE 'seed_demo: 演示报销单创建完成 id=%（DRAFT，金额 2,500.00）', v_rid;
END $$;

-- wf 审批实例/任务种子
DO $$
DECLARE v_inst uuid; v_def uuid; v_reimb uuid;
BEGIN
    SELECT id INTO v_def FROM wf.definitions
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND code = 'expense_reimburse' AND deleted_at IS NULL LIMIT 1;
    IF v_def IS NULL THEN
        RAISE NOTICE 'seed_demo: wf.definitions 缺失，跳过审批实例/任务种子';
        RETURN;
    END IF;
    SELECT id INTO v_reimb FROM expense.reimbursements
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND title = '演示：差旅费报销（草稿）' AND deleted_at IS NULL LIMIT 1;
    SELECT id INTO v_inst FROM wf.instances
     WHERE company_id = '00000000-0000-7000-0000-000000000010'
       AND business_type = 'REIMBURSEMENT' AND business_id = v_reimb LIMIT 1;
    IF v_inst IS NULL AND v_reimb IS NOT NULL THEN
        INSERT INTO wf.instances (company_id, definition_id, business_type, business_id,
                                  initiator_id, current_node, status)
        VALUES ('00000000-0000-7000-0000-000000000010', v_def, 'REIMBURSEMENT', v_reimb,
                (SELECT id FROM um.users WHERE login_name = 'admin'),
                'dept_approve', 'RUNNING')
        RETURNING id INTO v_inst;
        INSERT INTO wf.tasks (company_id, instance_id, node_code, assignee_id, task_type,
                              status, due_at)
        VALUES ('00000000-0000-7000-0000-000000000010', v_inst, 'dept_approve',
                (SELECT id FROM um.users WHERE login_name = 'admin'),
                'APPROVE', 'PENDING',
                now() + make_interval(days => COALESCE(
                    (SELECT COALESCE(value #>> '{}', value->>'v')::int
                       FROM um.params WHERE code = 'wf.task.due.days' AND deleted_at IS NULL),
                    3)));
        RAISE NOTICE 'seed_demo: 审批实例/任务种子创建完成（instance_id=%）', v_inst;
    ELSE
        RAISE NOTICE 'seed_demo: 审批实例/任务种子已存在，跳过（v_inst=%）', v_inst;
    END IF;
END $$;

-- rpt 报表物化对象刷新
DO $$
DECLARE
    mv text;
    v_is_immv boolean;
BEGIN
    FOREACH mv IN ARRAY ARRAY['rpt.budget_actual','rpt.ar_aging_paid',
                              'rpt.dept_expense','rpt.cash_flow_daily']
    LOOP
        IF to_regclass(mv) IS NOT NULL THEN
            SELECT EXISTS (
                     SELECT 1 FROM pgivm.pg_ivm_immv i
                       JOIN pg_class c ON c.oid = i.immvrelid
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = split_part(mv, '.', 1)
                        AND c.relname = split_part(mv, '.', 2))
              INTO v_is_immv;
            IF v_is_immv THEN
                PERFORM pgivm.refresh_immv(mv, true);
            ELSE
                EXECUTE format('REFRESH MATERIALIZED VIEW %s', mv);
            END IF;
            RAISE NOTICE 'seed_demo: rpt 报表物化对象 % 已刷新（种子数据落库后）', mv;
        END IF;
    END LOOP;
END $$;

RESET ROLE;
