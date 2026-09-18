-- init/gl_voucher_reject.sql， 凭证驳回（SUBMITTED 转 REJECTED）

-- 【状态机扩展】在 DRAFT 提交 SUBMITTED、审核 APPROVED、过账 POSTED、冲销 REVERSED 之上增加驳回态，
--   SUBMITTED 转 REJECTED，驳回，原因必填，记 voucher_status_history.remark + 审计
--   REJECTED 转 DRAFT，退回修改后即可重新提交，端点 POST /vouchers/{id}/revert
--                        经 SECURITY DEFINER 闸口 gl.fn_revert_voucher 原子退回，权限 gl.voucher.revert

-- 配套（同步），
--gl.vouchers.status CHECK 加入 'REJECTED'

--gl.fn_voucher_submit_check 采用显式迁移白名单，防状态回退/跳变，驳回/退回/冲销受控放行

--gl.fn_voucher_status_history 支持事务级 guc fms.voucher_remark 透传驳回原因到 remark

--SECURITY DEFINER 闸口 gl.fn_reject_voucher，权限 gl.voucher.reject + SoD 驳回人≠制单人

--gl_vouchers_update rls USING 纳入 REJECTED，REJECTED 转 DRAFT 受控路径，WITH CHECK 不变

--gl.fn_close_period 未完成凭证统计纳入 REJECTED，驳回凭证不允许被结账锁死

--um.permissions 种子 'gl.voucher.reject' + accountant 角色/admin 直授


-- 幂等，全部 CREATE OR REPLACE / IF NOT EXISTS / ON CONFLICT / DROP IF EXISTS，可重复执行

--status CHECK 约束扩容（存量约束名 vouchers_status_check，init/gl_base.sql 同名重建）

ALTER TABLE gl.vouchers DROP CONSTRAINT IF EXISTS vouchers_status_check;
ALTER TABLE gl.vouchers ADD CONSTRAINT vouchers_status_check
    CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','POSTED','REVERSED','REJECTED'));

--状态机校验触发器，显式迁移白名单（唯一合法迁移，驳回 SUBMITTED 转 REJECTED、退回 REJECTED 转 DRAFT、

--    冲销 POSTED 转 REVERSED 经 fn_reverse_voucher 受控路径放行，反向凭证同事务 DRAFT 转 POSTED
--    在 reversal_of_voucher_id 互指非空时豁免，冲销函数独有特征，其余跳变/回退一律 RAISE）
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
            RETURN NEW;                     -- 目标 DRAFT，退回豁免平衡/期间校验，草稿可暂存不平衡
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

--状态历史触发器，remark 支持事务级 guc fms.voucher_remark 透传，驳回原因由

--    fn_reject_voucher 写入，未设置时 INSERT 记'凭证创建'，迁移记 NULL
CREATE OR REPLACE FUNCTION gl.fn_voucher_status_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, public
AS $$
DECLARE
    v_remark text;
BEGIN
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

--驳回闸口（SUBMITTED 转 REJECTED），权限 + SoD + 原因必填 + 状态历史(remark=原因) + 审计，原子完成

CREATE OR REPLACE FUNCTION gl.fn_reject_voucher(p_voucher_id uuid, p_reason text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_v gl.vouchers%ROWTYPE;
BEGIN
    PERFORM auth.require_permission('gl.voucher.reject');
    IF NULLIF(btrim(p_reason), '') IS NULL THEN
        RAISE EXCEPTION 'fn_reject_voucher: 驳回原因必填';
    END IF;

    SELECT * INTO v_v FROM gl.vouchers
     WHERE id = p_voucher_id AND deleted_at IS NULL FOR UPDATE;
    IF v_v.id IS NULL THEN
        RAISE EXCEPTION 'fn_reject_voucher: 凭证 % 不存在', p_voucher_id;
    END IF;
    IF v_v.status <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'fn_reject_voucher: 凭证 % 状态为 %，仅 SUBMITTED 可驳回', p_voucher_id, v_v.status;
    END IF;
    -- 审核职责分离（SoD），驳回为审核侧决策，驳回人不得为制单人，与审批同族，录入≠审核
    IF v_v.created_by IS NOT DISTINCT FROM auth.current_uid() THEN
        RAISE EXCEPTION 'fn_reject_voucher: 驳回职责分离（SoD）失败：驳回人不得为制单人';
    END IF;

    -- 驳回原因经事务级 guc 传给状态历史触发器（trg_voucher_status_history 记 remark），
    -- UPDATE 立即清空，防同事务后续状态变更误带原因
    PERFORM set_config('fms.voucher_remark', p_reason, true);
    UPDATE gl.vouchers SET status = 'REJECTED'
     WHERE id = p_voucher_id AND status = 'SUBMITTED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_reject_voucher: 并发冲突，凭证 % 状态已被变更', p_voucher_id;
    END IF;
    PERFORM set_config('fms.voucher_remark', '', true);

    PERFORM audit.record('voucher_reject', 'gl.vouchers', p_voucher_id,
        jsonb_build_object('status', 'SUBMITTED'),
        jsonb_build_object('status', 'REJECTED', 'reason', p_reason),
        NULL, NULL);
END $$;
COMMENT ON FUNCTION gl.fn_reject_voucher(uuid, text)
    IS '凭证驳回：SUBMITTED->REJECTED（原因必填，状态历史 remark + 审计留痕；SoD 驳回人≠制单人）';

-- 4b) 退回闸口（REJECTED 转 DRAFT），权限 gl.voucher.revert + 状态历史 + 审计，原子完成。
-- 退回目标 DRAFT 在 trg_voucher_submit_check 白名单内（REJECTED 转 DRAFT）且豁免平衡/期间校验，
-- 退回后凭证回到可编辑草稿，可修改后重新提交或删除。
CREATE OR REPLACE FUNCTION gl.fn_revert_voucher(p_voucher_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_v gl.vouchers%ROWTYPE;
BEGIN
    PERFORM auth.require_permission('gl.voucher.revert');

    SELECT * INTO v_v FROM gl.vouchers
     WHERE id = p_voucher_id AND deleted_at IS NULL FOR UPDATE;
    IF v_v.id IS NULL THEN
        RAISE EXCEPTION 'fn_revert_voucher: 凭证 % 不存在', p_voucher_id;
    END IF;
    IF v_v.status <> 'REJECTED' THEN
        RAISE EXCEPTION 'fn_revert_voucher: 凭证 % 状态为 %，仅 REJECTED 可退回 DRAFT', p_voucher_id, v_v.status;
    END IF;

    UPDATE gl.vouchers SET status = 'DRAFT'
     WHERE id = p_voucher_id AND status = 'REJECTED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_revert_voucher: 并发冲突，凭证 % 状态已被变更', p_voucher_id;
    END IF;

    PERFORM audit.record('voucher_revert', 'gl.vouchers', p_voucher_id,
        jsonb_build_object('status', 'REJECTED'),
        jsonb_build_object('status', 'DRAFT'),
        NULL, NULL);
END $$;
COMMENT ON FUNCTION gl.fn_revert_voucher(uuid)
    IS '凭证退回：REJECTED->DRAFT（被驳回凭证退回修改后可重新提交/删除；状态历史 + 审计留痕）';

--rls，gl_vouchers_update USING 纳入 REJECTED（REJECTED 转 DRAFT 退回路径可用，WITH CHECK 不变，

--    仍禁止 POSTED/REVERSED，状态机迁移白名单由触发器把关，策略层不额外放行）
DROP POLICY IF EXISTS gl_vouchers_update ON gl.vouchers;
CREATE POLICY gl_vouchers_update ON gl.vouchers
    FOR UPDATE USING (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')
        AND deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (status NOT IN ('POSTED','REVERSED') AND auth.can_access_org(company_id));

--结账检查，REJECTED 属未完成凭证，纳入结账拦截，驳回凭证不允许被结账锁死

CREATE OR REPLACE FUNCTION gl.fn_close_period(
    p_company_id uuid,
    p_period     text,
    p_force      boolean DEFAULT false)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE v_cnt int; v_uid uuid;
BEGIN
    PERFORM auth.require_permission('gl.period.close');
    IF NOT p_force THEN
        SELECT count(*) INTO v_cnt FROM gl.vouchers
         WHERE company_id = p_company_id AND period = p_period
           AND status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED') AND deleted_at IS NULL;
        IF v_cnt > 0 THEN
            RAISE EXCEPTION 'fn_close_period: 期间 % 尚有 % 张未完成凭证，禁止结账', p_period, v_cnt;
        END IF;
        IF EXISTS (
            SELECT 1 FROM (
                SELECT voucher_no - lag(voucher_no) OVER (ORDER BY voucher_no) AS gap
                  FROM gl.vouchers
                 WHERE company_id = p_company_id AND period = p_period
                   AND status IN ('POSTED','REVERSED') AND deleted_at IS NULL) g
             WHERE g.gap > 1) THEN
            RAISE EXCEPTION 'fn_close_period: 期间 % 凭证号存在断号，禁止结账', p_period;
        END IF;
    END IF;
    v_uid := auth.current_uid();
    UPDATE gl.periods SET status = 'CLOSED', closed_by = v_uid, closed_at = now()
     WHERE company_id = p_company_id AND period = p_period AND status = 'OPEN';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_close_period: 期间 % 不存在或已结账', p_period;
    END IF;
    PERFORM audit.record('period_close', 'gl.periods', NULL, NULL,
        jsonb_build_object('company_id', p_company_id, 'period', p_period, 'closed_by', v_uid),
        NULL, NULL);
END $$;

--权限种子，幂等，um.permissions 含 'gl.voucher.reject'/'gl.voucher.revert' +

--    fms_admin_role/accountant 角色 + admin 用户直授，与 gl.voucher.reverse 同族，
--    revert = 被驳回凭证退回 DRAFT 的 api 端点权限
INSERT INTO um.permissions (id, company_id, module_id, code, identifier, name,
                            resource_type, action, permission_type, is_enabled, is_system)
SELECT v.id, '00000000-0000-7000-0000-000000000010',
       m.id, v.code, v.code, v.name,
       'gl', v.action, 'ACTION', true, false
  FROM (VALUES
        ('00000000-0000-7000-0000-000000000700'::uuid, 'gl.voucher.reject', '总账凭证：reject', 'reject'),
        ('00000000-0000-7000-0000-000000000701'::uuid, 'gl.voucher.revert', '总账凭证：revert（驳回退回草稿）', 'revert')
       ) AS v(id, code, name, action)
  JOIN um.modules m ON m.identifier = 'gl.voucher' AND m.deleted_at IS NULL
ON CONFLICT (code) DO NOTHING;

-- granted_by 按 login_name 解析 admin 的子查询，用户不存在时落 NULL（FK 允许），
-- um.users 为空时 role_perm_granted_by_fkey 不失败，seed_data 失败后重放本文件的场景
INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r CROSS JOIN um.permissions p
 WHERE r.code IN ('fms_admin_role','accountant')
   AND p.identifier IN ('gl.voucher.reject','gl.voucher.revert')
   AND p.deleted_at IS NULL AND p.is_enabled
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

INSERT INTO um.user_direct_permissions (company_id, user_id, permission_id, granted_by, granted_at, reason, valid_until)
SELECT '00000000-0000-7000-0000-000000000010', u.id, p.id,
       '00000000-0000-7000-0000-000000000001', now(),
       CASE WHEN p.identifier = 'gl.voucher.revert' THEN '超级管理员特批：可执行凭证退回'
            ELSE '超级管理员特批：可执行凭证驳回' END, NULL
  FROM um.users u CROSS JOIN um.permissions p
 WHERE u.login_name = 'admin'
   AND p.identifier IN ('gl.voucher.reject','gl.voucher.revert')
ON CONFLICT (user_id, permission_id) DO UPDATE SET reason = EXCLUDED.reason, valid_until = EXCLUDED.valid_until, deleted_at = NULL, updated_at = now();

-- 依赖，与 approve/post/reverse 同族，驳回/退回依赖查看
UPDATE um.permissions p
   SET required_permissions = ARRAY[(SELECT x.id FROM um.permissions x
                                      WHERE x.identifier = 'gl.voucher.view' AND x.deleted_at IS NULL)]
 WHERE p.identifier IN ('gl.voucher.reject','gl.voucher.revert')
   AND p.deleted_at IS NULL AND p.required_permissions = '{}';

-- 函数授权，沙箱模型下 fms_operator 经 SET ROLE 注入后执行闸口
-- PUBLIC 须显式收回，CREATE FUNCTION 默认带 PUBLIC EXECUTE

REVOKE EXECUTE ON FUNCTION gl.fn_reject_voucher(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION gl.fn_revert_voucher(uuid)       FROM PUBLIC;
GRANT EXECUTE ON FUNCTION gl.fn_reject_voucher(uuid, text) TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_revert_voucher(uuid)         TO fms_operator;

--自查，期望各 1

SELECT 'gl_voucher_reject' AS file_name,
       (SELECT count(*) FROM pg_constraint
         WHERE conrelid = 'gl.vouchers'::regclass AND conname = 'vouchers_status_check'
           AND pg_get_constraintdef(oid) LIKE '%REJECTED%')        AS check_cnt,      -- 期望 1
       (SELECT count(*) FROM pg_proc WHERE proname = 'fn_reject_voucher') AS fn_cnt,  -- 期望 1
       (SELECT count(*) FROM pg_proc WHERE proname = 'fn_revert_voucher') AS fn2_cnt, -- 期望 1
       (SELECT count(*) FROM um.permissions
         WHERE identifier IN ('gl.voucher.reject','gl.voucher.revert')
           AND deleted_at IS NULL)                                       AS perm_cnt; -- 期望 2
