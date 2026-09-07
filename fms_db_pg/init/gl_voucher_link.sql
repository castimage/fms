-- FMS 财务系统（init/gl_voucher_link），ar/ap/cash 业务单据 ↔ gl 凭证联动（voucher_link）

-- 背景，init/ar_ap_cash_base.sql 为 ar.invoices/ap.invoices/ar.receipts/ap.payments 预留 voucher_id、
-- cash.transactions 预留 voucher_line_id（无 FK 无触发器），Java 侧 ArServiceImpl/
-- ApServiceImpl 生成凭证草稿回填，本文件打通单据到凭证联动，
--   1) 不可变触发器豁免 voucher_id，ar/ap 发票开票（ISSUED）后仅允许 status 列变更，
--      而凭证联动需要在开票后回填 voucher_id，开票时/补生成端点，经
--      CREATE OR REPLACE 重定义两个不可变触发器函数，voucher_id 豁免"仅 status
--      可变更"检查，其余列仍不可变，DELETE/状态机白名单语义原样保留，
--   2) 清算过渡科目种子（cash.clearing_account 指向），现金流水凭证的对端科目，
--      资金流水无业务对端，进出账均以清算过渡科目挂对端（IN 借银行/贷清算，
--      OUT 借清算/贷银行），科目可配可换，
--   3) um.params 科目映射参数种子（公司级，值为该公司 gl.chart_accounts 科目 id，
--      {"v":"<uuid>"} 形态与 fa.depr.* 参数同构），ar.receivable_account /
--      ar.revenue_account / ap.payable_account / ap.expense_account /
--      cash.bank_account / cash.clearing_account，Java 侧经 um.fn_get_param_value
--      SECURITY DEFINER 读口读取（ParamMapper.selectPublicParamValue，无参表权限）。

-- 凭证生成策略，凭证一律 DRAFT，不自动过账，过账由会计在凭证模块完成，符合 SoD
-- 录入≠审核≠记账，生成失败不影响单据主流程，Java 侧 WARN + voucher_id 留空，
-- 可经补生成端点 POST /api/ar/invoices/{id}/link-voucher 与 /api/ap/invoices/{id}/link-voucher 重试。

-- 幂等，全部 INSERT ... ON CONFLICT DO NOTHING / 函数 CREATE OR REPLACE，重复执行无害。

SET ROLE fms_admin;

-- 1. ar 应收发票不可变触发器，豁免 voucher_id，凭证联动回填列，
--    原"仅 status 列可变更"检查在排除 voucher_id 后判断，其余列，金额/日期/客户等，仍不可变，
--    DELETE 禁令与状态机白名单（同状态更新 NEW.status=OLD.status 放行，凭证联动回填
--    ISSUED->ISSUED 场景，仅 status/voucher_id 可变更仍由 v_only_status 终审，
--    ISSUED 转 PARTIAL/PAID/VOID、PARTIAL 转 ISSUED/PAID/VOID）与 ar_ap_cash_base.sql 原实现语义一致。
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
        -- 状态机迁移白名单，ISSUED -> PARTIAL/PAID，核销推进，/ VOID，作废，
        -- PARTIAL -> ISSUED，撤销核销回退，/ PAID，核销结清，/ VOID，作废，
        -- NEW.status = OLD.status，状态不变，放行，凭证联动回填 voucher_id 即此形态
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
        -- 仅 status 列可变更，voucher_id 为凭证联动回填列，豁免，gl_voucher_link，
        -- 其余列，金额/日期/客户/税号等，开票后不可变
        v_only_status := (to_jsonb(NEW) - 'status' - 'voucher_id')
                         IS NOT DISTINCT FROM (to_jsonb(OLD) - 'status' - 'voucher_id');
        IF NOT v_only_status THEN
            RAISE EXCEPTION '应收发票已开票（status=%），禁止修改业务字段（仅可状态迁移与凭证联动回填；trg_ar_invoice_immutable）', OLD.status;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

-- 2. ap 应付发票不可变触发器，同 ar 豁免 voucher_id，与 ar_ap_cash_base.sql 同构
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
        IF NOT (
            (NEW.status = OLD.status) OR
            (OLD.status = 'ISSUED'  AND NEW.status = ANY (v_allowed_new_states)) OR
            (OLD.status = 'PARTIAL' AND (NEW.status = ANY (v_allowed_new_states)
                                         OR NEW.status = ANY (v_allowed_partial_extra)))
        ) THEN
            RAISE EXCEPTION '应付发票已开票（status=%），非法状态迁移 -> %（仅可 PARTIAL/PAID 核销推进、撤销回退 ISSUED 或作废 VOID；trg_ap_invoice_immutable）',
                OLD.status, NEW.status;
        END IF;
        v_only_status := (to_jsonb(NEW) - 'status' - 'voucher_id')
                         IS NOT DISTINCT FROM (to_jsonb(OLD) - 'status' - 'voucher_id');
        IF NOT v_only_status THEN
            RAISE EXCEPTION '应付发票已开票（status=%），禁止修改业务字段（仅可状态迁移与凭证联动回填；trg_ap_invoice_immutable）', OLD.status;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

-- 3. 清算过渡科目种子（demo 公司 00000000-0000-7000-0000-000000000010），
--    现金流水凭证的对端科目（cash.clearing_account 参数指向），末级科目（is_leaf=true）
--    满足 gl.fn_voucher_line_leaf_check 分录末级校验
INSERT INTO gl.chart_accounts
    (id, company_id, code, name, path, parent_id, level, is_leaf,
     account_type, direction, is_foreign_currency, status)
VALUES ('00000000-0000-7000-0000-000000000117', '00000000-0000-7000-0000-000000000010',
        '1231', '银行清算过渡', '1231'::ltree, NULL, 1, true,
        'ASSET', 'DEBIT', false, 'ACTIVE')
ON CONFLICT (company_id, code) DO NOTHING;

-- 4. 科目映射参数种子（um.params 公司级，值为该公司科目 id，{"v":"<uuid>"} 形态，
--    由 gl.chart_accounts 按 (company_id, code) 解析，科目树随客户可调，缺失即跳过
--    参数插入，ON CONFLICT DO NOTHING 幂等，重复执行不覆盖手工调整）
INSERT INTO um.params (code, value, updated_by)
SELECT 'ar.receivable_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '1122' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 应收发票凭证借方科目，应收账款

INSERT INTO um.params (code, value, updated_by)
SELECT 'ar.revenue_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '6001' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 应收发票凭证贷方科目，主营业务收入

INSERT INTO um.params (code, value, updated_by)
SELECT 'ap.payable_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '2202' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 应付发票凭证贷方科目，应付账款

INSERT INTO um.params (code, value, updated_by)
SELECT 'ap.expense_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '6602' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 应付发票凭证借方科目，管理费用

INSERT INTO um.params (code, value, updated_by)
SELECT 'cash.bank_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '100201' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 收款/付款/流水凭证银行科目，银行存款-工行基本户，末级

INSERT INTO um.params (code, value, updated_by)
SELECT 'cash.clearing_account', jsonb_build_object('v', a.id::text),
       '00000000-0000-7000-0000-000000000001'
  FROM gl.chart_accounts a
 WHERE a.company_id = '00000000-0000-7000-0000-000000000010'
   AND a.code = '1231' AND a.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;   -- 现金流水凭证对端科目，银行清算过渡

-- 5. 文件末尾自查
SELECT 'gl_voucher_link' AS file_name,
       (SELECT count(*) FROM um.params
         WHERE code IN ('ar.receivable_account','ar.revenue_account',
                        'ap.payable_account','ap.expense_account',
                        'cash.bank_account','cash.clearing_account')) AS link_param_cnt,
       (SELECT count(*) FROM gl.chart_accounts
         WHERE company_id = '00000000-0000-7000-0000-000000000010'
           AND code = '1231' AND deleted_at IS NULL) AS clearing_account_cnt;
