-- FMS 财务系统（核心函数补齐，执行库，fms），D.0 凭证不可变触发器 v2（POSTED 转 REVERSED 受控放行）+ 防重复冲销唯一索引，D.1 分录行级借贷平衡校验 + 凭证头总额同步，D.2 过账，D.3 红字冲销，D.4 期间结账/反结账，D.5 资金日报，新增表 updated_at 触发器挂载，D.6 EXECUTE 授权矩阵 + 6 角色权限矩阵核对查询

-- 文件末尾段，均幂等重定义，
--   per-company 取号序列（fn_get_voucher_no，与 gl_base 6.1 同函数）
--   fn_post_voucher SoD（审核人≠制单人，与上文 D.2 同函数）
--   fn_fx_adjustment uuid 签名 + 权限闸口（最终实现由 init/gl_fx_rates.sql 覆盖，签名与闸口一致）

-- 全部幂等可重跑（CREATE OR REPLACE / DROP TRIGGER IF EXISTS / 唯一索引 IF NOT EXISTS / DO 块判存）
-- 设计偏差，实现级修正，a) D.3 反向凭证由"状态直接 POSTED"改为同事务内 DRAFT 转 POSTED，D.1 行级平衡触发器逐行执行，POSTED 逐行插分录中间态必然不平衡（RAISE），且总额同步 UPDATE 会被 trg_voucher_immutable 拒绝，DRAFT 中间态事务内原子不可见，最终语义与设计一致。b) D.1 的 DELETE 分支不访问 NEW（DELETE 时 NEW 未赋值），按 TG_OP 分支取值。c) fn_reopen_period 读 fms.force_reopen 加 COALESCE 兜底，guc 缺失时 NULL <> 'on' 恒为 NULL（IF 不触发）则 fail-open，兜底后按默认 off 拒绝

SET ROLE fms_admin;

-- D.0 前置升级，gl.fn_voucher_immutable v2 + 防重复冲销唯一索引
-- v2 放行受控冲销路径 POSTED 转 REVERSED，① 普通角色被 gl_vouchers_update 策略 WITH CHECK 拦截，② 放行仅当存在一个 POSTED 且反向互指本凭证的冲销凭证，该凭证只能经 fn_reverse_voucher，权限闸口创建，③ 触发器对超级用户同样生效，fms_admin 直连也不能绕过
CREATE OR REPLACE FUNCTION gl.fn_voucher_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
       AND NEW.reversed_by_voucher_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM gl.vouchers r
                    WHERE r.id = NEW.reversed_by_voucher_id
                      AND r.status = 'POSTED'
                      AND r.reversal_of_voucher_id = OLD.id
                      AND r.deleted_at IS NULL) THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION '凭证已过账/冲销（status=%），禁止修改或删除（trg_voucher_immutable）', OLD.status;
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;
COMMENT ON FUNCTION gl.fn_voucher_immutable()
    IS '凭证不可变触发器 v2：POSTED/REVERSED 禁止修改，唯一例外为 fn_reverse_voucher 创建的互指冲销路径';

-- 冲销互指列补齐（init/gl_base.sql 建表未含，幂等，供 uk_voucher_reversal / fn_voucher_immutable v2 / fn_reverse_voucher 使用，自引用 FK，uuid 与主键一致）
ALTER TABLE gl.vouchers ADD COLUMN IF NOT EXISTS reversal_of_voucher_id uuid REFERENCES gl.vouchers(id);
ALTER TABLE gl.vouchers ADD COLUMN IF NOT EXISTS reversed_by_voucher_id uuid REFERENCES gl.vouchers(id);
COMMENT ON COLUMN gl.vouchers.reversal_of_voucher_id IS '冲销来源凭证 id（本凭证为某凭证的红字冲销时指向原凭证；NULL=非冲销凭证）';
COMMENT ON COLUMN gl.vouchers.reversed_by_voucher_id IS '被本凭证冲销的原凭证 id（原凭证 REVERSED 时记录冲销凭证；NULL=未冲销）';

-- 防重复冲销，一凭证仅可被冲销一次，部分唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_voucher_reversal
    ON gl.vouchers (company_id, reversal_of_voucher_id)
    WHERE reversal_of_voucher_id IS NOT NULL AND deleted_at IS NULL;

-- D.1 gl.fn_voucher_line_balance，触发器函数，供触发器复用

-- BEFORE INSERT OR UPDATE OR DELETE ON gl.voucher_lines，① 非 DRAFT 凭证强制借贷平衡，跨行校验，CHECK 无法表达，② 同步凭证头 total_debit/total_credit，DELETE 时 NEW 未赋值，v_vid 按 TG_OP 分支取自 OLD/NEW

-- 行为，按 TG_OP 重算余额并排除被替换行（INSERT 加 NEW / DELETE 减 OLD / UPDATE 换 OLD 为 NEW），非 DRAFT 凭证借贷不等则 RAISE，DRAFT 豁免，草稿可暂存不平衡，随后同步凭证头 total_debit/total_credit，仅总额列，不触发状态机触发器，updated_at 正常刷新

-- 边界与错误，凭证不存在或已软删则 RAISE，POSTED/REVERSED 凭证的任何分录写入，含绕过 rls 的直插，在总额同步 UPDATE 时被 trg_voucher_immutable 拒绝，已过账凭证防篡改在此闭合

-- 调用约定，本函数仅由触发器 trg_voucher_line_balance 调用，不对外 GRANT EXECUTE，无独立鉴权，权限由触发器的表级权限继承，任何直写 gl.voucher_lines 的路径，含反向凭证 fn_reverse_voucher 的分录复制，都必然经过本校验，平衡防线无旁路
CREATE OR REPLACE FUNCTION gl.fn_voucher_line_balance()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_vid    uuid;
    v_status text;
    v_d      numeric(18,2);
    v_c      numeric(18,2);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_vid := OLD.voucher_id;
    ELSE
        v_vid := NEW.voucher_id;
    END IF;
    SELECT status INTO v_status FROM gl.vouchers WHERE id = v_vid AND deleted_at IS NULL;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'fn_voucher_line_balance: 凭证 % 不存在', v_vid;
    END IF;
    -- 重算余额，排除被替换行（OLD），计入新行（NEW）
    IF TG_OP = 'INSERT' THEN
        SELECT COALESCE(SUM(debit), 0) + NEW.debit,
               COALESCE(SUM(credit), 0) + NEW.credit
          INTO v_d, v_c
          FROM gl.voucher_lines WHERE voucher_id = v_vid AND deleted_at IS NULL;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT COALESCE(SUM(debit), 0) - OLD.debit,
               COALESCE(SUM(credit), 0) - OLD.credit
          INTO v_d, v_c
          FROM gl.voucher_lines WHERE voucher_id = v_vid AND deleted_at IS NULL AND id <> OLD.id;
    ELSE
        SELECT COALESCE(SUM(debit), 0) - OLD.debit + NEW.debit,
               COALESCE(SUM(credit), 0) - OLD.credit + NEW.credit
          INTO v_d, v_c
          FROM gl.voucher_lines WHERE voucher_id = v_vid AND deleted_at IS NULL AND id <> OLD.id;
    END IF;
    IF v_status <> 'DRAFT' AND v_d <> v_c THEN
        RAISE EXCEPTION 'fn_voucher_line_balance: 凭证 % 借贷不平衡（借 % 贷 %），状态 % 非草稿禁止保存',
            v_vid, v_d, v_c, v_status;
    END IF;
    -- 同步凭证头总额（仅总额列，不触发状态机触发器，updated_at 触发器正常刷新）。对 POSTED/REVERSED 凭证本 UPDATE 会被 trg_voucher_immutable 拒绝，已过账凭证的任何分录写入（含绕过 rls 的 INSERT）在此被拦截，防篡改闭合
    UPDATE gl.vouchers SET total_debit = v_d, total_credit = v_c WHERE id = v_vid;
    RETURN COALESCE(NEW, OLD);
END $$;
COMMENT ON FUNCTION gl.fn_voucher_line_balance()
    IS '分录行级平衡校验 + 凭证头总额同步（触发器函数，仅触发器调用，不对外授权）';

DROP TRIGGER IF EXISTS trg_voucher_line_balance ON gl.voucher_lines;
CREATE TRIGGER trg_voucher_line_balance
    BEFORE INSERT OR UPDATE OR DELETE ON gl.voucher_lines
    FOR EACH ROW EXECUTE FUNCTION gl.fn_voucher_line_balance();

-- D.2 gl.fn_post_voucher，过账，状态校验 + 凭证号分配 + 状态历史 + 审计

-- 行为，APPROVED 转 POSTED，凭证号由既有 trg_voucher_submit_check（gl_base.sql）分配（期间内连续 + uk_voucher 兜底），状态历史由 trg_voucher_status_history（gl_voucher_reject.sql）记录，审计双通道（显式 audit.record + 捕获触发器）

-- 鉴权，auth.require_permission('gl.voucher.post')，未注入 fms.user_id 上下文或缺少权限时 RAISE（fail-closed）

-- 参数，p_voucher_id 目标凭证 id，须存在且未软删

-- 校验顺序与错误，不存在则 RAISE，状态非 APPROVED 则 RAISE（仅 APPROVED 可过账），期间缺失或非 OPEN 则 RAISE，已结账期间禁止过账，借贷不平衡则 RAISE，approved_by 为空或等于制单人则 RAISE，SoD 录入≠审核≠记账，过账前最后一关

-- 并发，SELECT ... FOR UPDATE 锁行后，UPDATE ... WHERE status='APPROVED' 未命中则 RAISE 并发冲突，状态已被并发变更，事务回滚，UPDATE 由触发器完成取号/时间戳，v2 不可变触发器对 POSTED 目标无碍

-- 状态机语义，本函数是 APPROVED 转 POSTED 的唯一合法通道，Java 层 pre-check 之后由 SECURITY DEFINER 终审，POSTED 之后凭证冻结（trg_voucher_immutable），唯一出路是 fn_reverse_voucher 冲销，过账即记账生效点，凭证号/状态历史/审计在同一事务内闭环，无中间可见状态
CREATE OR REPLACE FUNCTION gl.fn_post_voucher(p_voucher_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_v gl.vouchers%ROWTYPE;
    v_period_status text;
BEGIN
    PERFORM auth.require_permission('gl.voucher.post');

    SELECT * INTO v_v FROM gl.vouchers
     WHERE id = p_voucher_id AND deleted_at IS NULL FOR UPDATE;
    IF v_v.id IS NULL THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 不存在', p_voucher_id;
    END IF;
    IF v_v.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 状态为 %，仅 APPROVED 可过账', p_voucher_id, v_v.status;
    END IF;
    SELECT status INTO v_period_status FROM gl.periods
     WHERE company_id = v_v.company_id AND period = v_v.period AND deleted_at IS NULL;
    IF v_period_status IS NULL OR v_period_status <> 'OPEN' THEN
        RAISE EXCEPTION 'fn_post_voucher: 期间 % 未开放（%），禁止过账',
            v_v.period, COALESCE(v_period_status, '<缺失>');
    END IF;
    IF v_v.total_debit <> v_v.total_credit THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 借贷不平衡（借 % 贷 %）', p_voucher_id, v_v.total_debit, v_v.total_credit;
    END IF;
    -- 审核职责分离（SoD），过账前审核人必须存在且与制单人不同（录入≠审核≠记账）
    IF v_v.approved_by IS NULL OR v_v.approved_by IS NOT DISTINCT FROM v_v.created_by THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 未经他人审核（approved_by=%），禁止过账',
            p_voucher_id, COALESCE(v_v.approved_by::text, '<NULL>');
    END IF;

    UPDATE gl.vouchers SET status = 'POSTED'
     WHERE id = p_voucher_id AND status = 'APPROVED';   -- 触发器负责取号/时间戳，v2 不可变触发器对 POSTED 目标无碍
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_post_voucher: 并发冲突，凭证 % 状态已被变更', p_voucher_id;
    END IF;

    PERFORM audit.record('voucher_post', 'gl.vouchers', p_voucher_id,
        jsonb_build_object('status', v_v.status), jsonb_build_object('status', 'POSTED'),
        NULL, NULL);
END $$;
COMMENT ON FUNCTION gl.fn_post_voucher(uuid)
    IS '凭证过账：APPROVED->POSTED（期间 OPEN/借贷平衡校验 + 触发器取号 + 状态历史 + 审计）';

-- D.3 gl.fn_reverse_voucher（冲销，红字反向凭证 + 原凭证置 REVERSED）

-- 行为，① 建反向凭证（借贷互换=红字，状态历史记录创建 + DRAFT 转 POSTED）② 复制分录（debit↔credit 互换）③ 反向凭证升 POSTED ④ 原凭证置 REVERSED（fn_voucher_immutable v2 放行 + 互指校验），审计双通道

-- 约束，原期间与冲销期间均须 OPEN（既有 submit_check 校验原期间，已结账期间需先 fn_reopen_period）

-- 设计偏差 (a)，反向凭证不直接以 POSTED 插入，D.1 行级平衡触发器对 POSTED 逐行强制平衡，直接 POSTED 时逐行插入分录的中间态必然借贷不平衡（RAISE），且 D.1 总额同步 UPDATE 会被 trg_voucher_immutable 拒绝，改为同事务内 DRAFT，建头+逐行复制，平衡校验豁免后转 POSTED（submit_check 校验平衡/期间并取号），事务内原子，对外等价于"直接生成已过账反向凭证"

-- 参数，p_voucher_id 原凭证（须 POSTED），p_reverse_date 冲销日期，决定冲销期间，默认当天，p_reason 冲销原因（写入反向凭证 note 与审计 jsonb）

-- 边界与错误，凭证不存在则 RAISE，状态非 POSTED 则 RAISE（仅 POSTED 可冲销），已存在冲销凭证则 RAISE（uk_voucher_reversal 部分唯一索引并发兜底，一凭证仅可冲销一次），冲销期间不存在或非 OPEN 则 RAISE

-- 并发与返回值，四步中任何 UPDATE 未命中则 RAISE 并发冲突，事务整体回滚，不留中间态，返回反向凭证 id，互引闭环 reversal_of_voucher_id 与 reversed_by_voucher_id 互指，v2 不可变触发器据此放行 POSTED 转 REVERSED 受控路径

-- 状态机语义，POSTED 转 REVERSED 的唯一合法通道，反向凭证作为独立凭证（DRAFT 转 POSTED 同事务内完成）继续走完整状态机，可被再次冲销，原凭证保留全部历史行，金额/分录/审计均不动，冲销以"新凭证抵账"而非"改旧凭证"实现，审计可逆性完整
CREATE OR REPLACE FUNCTION gl.fn_reverse_voucher(
    p_voucher_id   uuid,
    p_reverse_date date DEFAULT CURRENT_DATE,
    p_reason       text  DEFAULT '')
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_v gl.vouchers%ROWTYPE;
    v_new_id uuid;
    v_period char(7) := to_char(p_reverse_date, 'YYYY-MM');
    v_period_status text;
BEGIN
    PERFORM auth.require_permission('gl.voucher.reverse');

    SELECT * INTO v_v FROM gl.vouchers
     WHERE id = p_voucher_id AND deleted_at IS NULL FOR UPDATE;
    IF v_v.id IS NULL THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 凭证 % 不存在', p_voucher_id;
    END IF;
    IF v_v.status <> 'POSTED' THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 凭证 % 状态为 %，仅 POSTED 可冲销', p_voucher_id, v_v.status;
    END IF;
    IF EXISTS (SELECT 1 FROM gl.vouchers x
                WHERE x.reversal_of_voucher_id = v_v.id AND x.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 凭证 % 已存在冲销凭证（uk_voucher_reversal 兜底）', p_voucher_id;
    END IF;
    SELECT status INTO v_period_status FROM gl.periods
     WHERE company_id = v_v.company_id AND period = v_period AND deleted_at IS NULL;
    IF v_period_status IS NULL OR v_period_status <> 'OPEN' THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 冲销期间 % 未开放（%）；原期间 % 亦须 OPEN（先反结账）',
            v_period, COALESCE(v_period_status, '<缺失>'), v_v.period;
    END IF;

    -- ① 反向凭证头（先 DRAFT，INSERT 时 submit_check 对 DRAFT 豁免校验，trg_voucher_status_history 记录创建，审计捕获触发器记录 INSERT）
    INSERT INTO gl.vouchers (company_id, period, voucher_no, voucher_date, status, source_type,
                             source_id, is_private, dept_id, created_by,
                             reversal_of_voucher_id, total_debit, total_credit, note)
    VALUES (v_v.company_id, v_period, NULL, p_reverse_date, 'DRAFT', 'REVERSAL',
            v_v.id, v_v.is_private, v_v.dept_id, auth.current_uid(),
            v_v.id, 0, 0, p_reason)
    RETURNING id INTO v_new_id;

    -- ② 分录复制，借贷互换，红字冲销，D.1 行级触发器逐行同步凭证头总额
    INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary, debit, credit,
                                  currency, dept_id, partner_id, company_id)
    SELECT v_new_id, line_no, account_id,
           COALESCE(summary, '') || '（冲销 ' || v_v.voucher_no || '）',
           credit, debit, currency, dept_id, partner_id, company_id
      FROM gl.voucher_lines
     WHERE voucher_id = v_v.id AND deleted_at IS NULL;

    -- ③ 反向凭证升 POSTED，submit_check 校验借贷平衡/期间 OPEN 并分配凭证号，状态历史记录 DRAFT 转 POSTED，审计捕获触发器记录 UPDATE
    -- DRAFT 转 POSTED 直更在状态机白名单中按 reversal_of_voucher_id 互指非空豁免
    --（本函数 INSERT 已写互指列，唯受控路径独有）
    UPDATE gl.vouchers SET status = 'POSTED'
     WHERE id = v_new_id AND status = 'DRAFT';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 反向凭证置 POSTED 失败（并发冲突）';
    END IF;

    -- ④ 原凭证置 REVERSED（v2 不可变触发器校验互指后放行，状态历史记录 POSTED 转 REVERSED）
    UPDATE gl.vouchers
       SET status = 'REVERSED', reversed_by_voucher_id = v_new_id
     WHERE id = v_v.id AND status = 'POSTED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_reverse_voucher: 原凭证置 REVERSED 失败（并发冲突）';
    END IF;

    PERFORM audit.record('voucher_reverse', 'gl.vouchers', v_v.id,
        jsonb_build_object('status', 'POSTED'),
        jsonb_build_object('status', 'REVERSED', 'reversed_by_voucher_id', v_new_id,
                           'reason', p_reason), NULL, NULL);
    RETURN v_new_id;
END $$;
COMMENT ON FUNCTION gl.fn_reverse_voucher(uuid, date, text)
    IS '红字冲销：生成借贷互换的反向凭证（POSTED）并互引，原凭证置 REVERSED；返回反向凭证 id';

-- D.3b gl.fn_reject_voucher（驳回，SUBMITTED 转 REJECTED，原因必填，状态历史 remark + 审计）

-- 行为，状态校验 + 原因必填 + SoD（驳回人≠制单人）+ UPDATE 置 REJECTED（状态历史由
--   trg_voucher_status_history 记录，原因经事务级 guc fms.voucher_remark 透传到 remark）+ audit.record

-- 鉴权，auth.require_permission('gl.voucher.reject')，未注入 fms.user_id 上下文或缺少权限时 RAISE（fail-closed）

-- 参数，p_voucher_id 目标凭证 id（须存在、未软删、SUBMITTED），p_reason 驳回原因（必填，btrim 后非空）

-- 并发，SELECT ... FOR UPDATE 锁行后 UPDATE ... WHERE status='SUBMITTED' 未命中则 RAISE 并发冲突

-- 状态机语义，SUBMITTED 转 REJECTED 唯一合法通道，白名单触发器终审，驳回后凭证进入 REJECTED
--，列表可见/可筛选，REJECTED 转 DRAFT 退回经 fn_revert_voucher 闸口开放
--   （见 init/gl_voucher_reject.sql 4b），db 层白名单与端点一致
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
    -- 审核职责分离（SoD），驳回为审核侧决策，驳回人不得为制单人（与审批同族，录入≠审核）
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

-- D.4 gl.fn_close_period / gl.fn_reopen_period，期间开关

-- 结账，检查（未完成凭证/断号，p_force 可跳过）后置 CLOSED 并审计

-- 参数，p_company_id/p_period 目标公司与期间（'YYYY-MM'），p_force=true 跳过未完成凭证与断号两项检查，仅用于已知历史数据修复，谨慎使用

-- 行为与错误，非 p_force 下存在 DRAFT/SUBMITTED/APPROVED 凭证则 RAISE，POSTED/REVERSED 凭证号存在缺口（窗口函数 lag 检测 gap>1）则 RAISE，期间内编号必须连续，断号拒结，UPDATE 未命中（期间不存在或已 CLOSED）则 RAISE，通过后置 CLOSED 并记录 closed_by/closed_at，audit.record 留痕

-- 结账后由 trg_period_guard/trg_period_voucher_guard 联动锁定，不可再向该期间建凭证、不可直接改回 OPEN，反结账须经 fn_reopen_period + fms.force_reopen 二次确认，超级用户直改同样被拦截

-- 期间状态机语义，OPEN 转 CLOSED 是单向收口，闭账即该期间业务终结，不再接受新凭证、不再过账，唯一反向路径是 fn_reopen_period，最晚期间 + 原因 + 二次确认，p_force 只豁免完整性检查不豁免鉴权，滥用会破坏期间连续性，仅限历史数据修复
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
           AND status IN ('DRAFT','SUBMITTED','APPROVED') AND deleted_at IS NULL;
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
COMMENT ON FUNCTION gl.fn_close_period(uuid, text, boolean)
    IS '期间结账：未完成凭证/断号检查（p_force 可跳过）-> CLOSED -> 审计留痕';

-- 反结账，权限 + 二次确认开关（guc fms.force_reopen，bootstrap_base.sql 已注册默认 off）+ 原因留痕 + 仅可反开最晚期间

-- 修正 (c)，COALESCE 兜底，guc 缺失时 NULL <> 'on' 恒为 NULL（IF 不触发）会 fail-open 放行反结账，兜底后未配置一律按 off 拒绝

-- 参数，p_reason 反结账原因必填（审计留痕，空值则 RAISE）

-- 行为与错误，原因为空 / fms.force_reopen 非 'on'，未开启二次确认则 RAISE，存在晚于 p_period 的已结账期间则 RAISE，只能反开最后一个，防乱序重开，UPDATE 未命中，期间不存在或未结账则 RAISE，通过后置 OPEN 并清 closed_by/closed_at，audit.record 留痕
CREATE OR REPLACE FUNCTION gl.fn_reopen_period(
    p_company_id uuid,
    p_period     text,
    p_reason     text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE v_later int;
BEGIN
    PERFORM auth.require_permission('gl.period.reopen');
    IF NULLIF(btrim(p_reason), '') IS NULL THEN
        RAISE EXCEPTION 'fn_reopen_period: 必须填写反结账原因（审计留痕）';
    END IF;
    IF COALESCE(current_setting('fms.force_reopen', true), 'off') <> 'on' THEN
        RAISE EXCEPTION 'fn_reopen_period: 二次确认开关 fms.force_reopen 未开启，拒绝反结账';
    END IF;
    SELECT count(*) INTO v_later FROM gl.periods
     WHERE company_id = p_company_id AND period > p_period AND status = 'CLOSED';
    IF v_later > 0 THEN
        RAISE EXCEPTION 'fn_reopen_period: 存在晚于 % 的已结账期间（% 个），只能反开最后一个', p_period, v_later;
    END IF;
    UPDATE gl.periods SET status = 'OPEN', closed_by = NULL, closed_at = NULL
     WHERE company_id = p_company_id AND period = p_period AND status = 'CLOSED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_reopen_period: 期间 % 不存在或未结账', p_period;
    END IF;
    PERFORM audit.record('period_reopen', 'gl.periods', NULL, NULL,
        jsonb_build_object('company_id', p_company_id, 'period', p_period, 'reason', p_reason),
        NULL, NULL);
END $$;
COMMENT ON FUNCTION gl.fn_reopen_period(uuid, text, text)
    IS '期间反结账：原因必填 + fms.force_reopen 二次确认 + 仅可反开最晚期间 -> OPEN -> 审计留痕';

-- D.5 cash.fn_daily_report，资金日报聚合

-- 输出，期初/当日收/付/净额/期末 + 分账户明细（jsonb 文档），SECURITY DEFINER 内显式校验公司数据范围，绕过 rls 的代价，维护会话直通
CREATE OR REPLACE FUNCTION cash.fn_daily_report(p_company_id uuid, p_date date)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = cash, public
AS $$
DECLARE
    v_open numeric(18,2);
    v_in   numeric(18,2);
    v_out  numeric(18,2);
    v_rows jsonb;
BEGIN
    IF NOT auth.can_access_org(p_company_id) AND session_user NOT IN ('fms_admin','postgres') THEN
        RAISE EXCEPTION 'fn_daily_report: 无公司 % 数据范围权限', p_company_id;
    END IF;
    SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0) INTO v_open
      FROM cash.transactions
     WHERE company_id = p_company_id AND trx_date < p_date AND deleted_at IS NULL;
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'IN'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'OUT'), 0) INTO v_in, v_out
      FROM cash.transactions
     WHERE company_id = p_company_id AND trx_date = p_date AND deleted_at IS NULL;
    SELECT jsonb_agg(t.row ORDER BY t.row ->> 'bank_account_id') INTO v_rows
      FROM (SELECT jsonb_build_object(
                   'bank_account_id', bank_account_id,
                   'income',  COALESCE(SUM(amount) FILTER (WHERE direction = 'IN'), 0),
                   'expense', COALESCE(SUM(amount) FILTER (WHERE direction = 'OUT'), 0)) AS row
              FROM cash.transactions
             WHERE company_id = p_company_id AND trx_date = p_date AND deleted_at IS NULL
             GROUP BY bank_account_id) t;
    RETURN jsonb_build_object(
        'company_id', p_company_id, 'date', p_date,
        'opening_balance', v_open,
        'income', v_in, 'expense', v_out, 'net', v_in - v_out,
        'closing_balance', v_open + v_in - v_out,
        'by_account', COALESCE(v_rows, '[]'::jsonb));
END $$;
COMMENT ON FUNCTION cash.fn_daily_report(uuid, date)
    IS '资金日报：期初/当日收付/净额/期末 + 分账户明细（jsonb）；要求注入 fms.org_ids 上下文';

-- 通用 updated_at 触发器挂载（um/org 新增 5 表），gl.fn_set_updated_at 由 init/gl_base.sql 创建，幂等（DROP TRIGGER IF EXISTS）
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['um.modules','um.user_direct_permissions','um.api_keys',
                             'org.positions','org.employees']
    LOOP
        IF to_regclass(t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_updated ON %s', split_part(t, '.', 2), t);
            EXECUTE format('CREATE TRIGGER trg_%s_updated BEFORE UPDATE ON %s '
                           || 'FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at()',
                           split_part(t, '.', 2), t);
        END IF;
    END LOOP;
END $$;

-- D.6 EXECUTE 授权矩阵（沙箱模型，经 SET ROLE 注入职责角色后执行，函数内显式鉴权 auth.require_permission / can_access_org 收敛行级边界），签名随函数参数为 uuid，GRANT 同步
GRANT EXECUTE ON FUNCTION gl.fn_post_voucher(uuid)                     TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reverse_voucher(uuid, date, text)      TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reject_voucher(uuid, text)             TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_close_period(uuid, text, boolean)      TO fms_operator;
GRANT EXECUTE ON FUNCTION gl.fn_reopen_period(uuid, text, text)        TO fms_operator;
GRANT EXECUTE ON FUNCTION cash.fn_daily_report(uuid, date)
    TO fms_operator, fms_viewer, fms_report;
-- 触发器函数不授权，仅触发器调用，fn_voucher_line_balance / fn_voucher_status_history
-- 唯一索引 uk_voucher_reversal 用部分索引，仅对未删除的冲销来源行生效

-- 6 角色权限矩阵核对查询，验证阶段使用，仅查询，不修改任何对象
-- 预期要点，fms_admin 超级用户全 true（NOLOGIN 仅管理会话），fms_app 直连零表级权限（沙箱，仅 CONNECT + schema USAGE + 闸口函数），fms_operator 业务表 DML 与 post/reverse/close/reopen EXECUTE，viewer/auditor/report 仅 SELECT（report 无敏感列），auditor 独有 audit.audit_log / log.access_logs 读取，触发器函数无 EXECUTE
-- 9.1 表级权限矩阵（6 角色 × 关键表，to_regclass 判存兼容基表未创建的场景）
WITH roles AS (
    SELECT rolname FROM pg_roles
     WHERE rolname IN ('fms_admin','fms_app','fms_operator','fms_viewer','fms_auditor','fms_report')
), objs AS (
    SELECT t AS obj FROM (VALUES
        ('um.users'),('um.roles'),('um.permissions'),('um.user_role'),('um.role_perm'),
        ('um.user_org_scope'),('um.user_dept_scope'),('um.params'),('um.modules'),
        ('um.user_direct_permissions'),('um.api_keys'),('um.login_log'),
        ('org.companies'),('org.departments'),('org.positions'),('org.employees'),
        ('gl.chart_accounts'),('gl.periods'),('gl.vouchers'),('gl.voucher_lines'),
        ('gl.voucher_status_history'),
        ('ar.customers'),('ar.invoices'),('ap.suppliers'),('cash.bank_accounts'),
        ('cash.transactions'),
        ('audit.audit_log'),('audit.safe_voucher_view'),('log.app_logs'),('log.access_logs')
    ) AS v(t)
    WHERE to_regclass(t) IS NOT NULL
), privs AS (
    SELECT unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) AS p
)
SELECT r.rolname                                                       AS role,
       o.obj                                                           AS object,
       (SELECT COALESCE(string_agg(priv.p, ',' ORDER BY priv.p), '-')
          FROM privs priv
         WHERE has_table_privilege(r.rolname, o.obj, priv.p))          AS grants
  FROM roles r
 CROSS JOIN objs o
 ORDER BY r.rolname, o.obj;

-- 9.2 函数 EXECUTE 矩阵（核心函数 8 个 + auth 权限闸口 3 个）
SELECT r.rolname                                                   AS role,
       n.nspname || '.' || p.proname                               AS function,
       has_function_privilege(r.rolname, p.oid, 'EXECUTE')         AS can_execute
  FROM pg_roles r
 CROSS JOIN pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE r.rolname IN ('fms_admin','fms_app','fms_operator','fms_viewer','fms_auditor','fms_report')
   AND ((n.nspname IN ('gl','cash')
         AND p.proname IN ('fn_post_voucher','fn_reverse_voucher','fn_reject_voucher',
                           'fn_close_period','fn_reopen_period','fn_voucher_line_balance',
                           'fn_daily_report','fn_voucher_status_history'))
     OR (n.nspname = 'auth'
         AND p.proname IN ('require_permission','has_permission','user_permissions')))
 ORDER BY r.rolname, n.nspname, p.proname;

-- 9.3 schema USAGE 矩阵
SELECT r.rolname AS role, s.nspname AS schema,
       has_schema_privilege(r.rolname, s.nspname, 'USAGE') AS can_usage
  FROM pg_roles r
 CROSS JOIN pg_namespace s
 WHERE r.rolname IN ('fms_admin','fms_app','fms_operator','fms_viewer','fms_auditor','fms_report')
   AND s.nspname IN ('um','org','gl','ar','ap','cash','audit','log','auth')
 ORDER BY r.rolname, s.nspname;

-- per-company 取号序列（nextval 串行取号，替代 MAX+1 无行锁并发同号，
-- 与 gl_base 6.1 同函数幂等重定义，序列首值 = 该公司现有最大凭证号 + 1）
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

-- fn_post_voucher SoD（过账前审核人必须存在且与制单人不同，
-- 与上文 D.2 同函数幂等重定义，逻辑一致）
CREATE OR REPLACE FUNCTION gl.fn_post_voucher(p_voucher_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
DECLARE
    v_v gl.vouchers%ROWTYPE;
    v_period_status text;
BEGIN
    PERFORM auth.require_permission('gl.voucher.post');
    SELECT * INTO v_v FROM gl.vouchers
     WHERE id = p_voucher_id AND deleted_at IS NULL FOR UPDATE;
    IF v_v.id IS NULL THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 不存在', p_voucher_id;
    END IF;
    IF v_v.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 状态为 %，仅 APPROVED 可过账', p_voucher_id, v_v.status;
    END IF;
    SELECT status INTO v_period_status FROM gl.periods
     WHERE company_id = v_v.company_id AND period = v_v.period AND deleted_at IS NULL;
    IF v_period_status IS NULL OR v_period_status <> 'OPEN' THEN
        RAISE EXCEPTION 'fn_post_voucher: 期间 % 未开放（%），禁止过账',
            v_v.period, COALESCE(v_period_status, '<缺失>');
    END IF;
    IF v_v.total_debit <> v_v.total_credit THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 借贷不平衡（借 % 贷 %）', p_voucher_id, v_v.total_debit, v_v.total_credit;
    END IF;
    IF v_v.approved_by IS NULL OR v_v.approved_by IS NOT DISTINCT FROM v_v.created_by THEN
        RAISE EXCEPTION 'fn_post_voucher: 凭证 % 未经他人审核（approved_by=%），禁止过账',
            p_voucher_id, COALESCE(v_v.approved_by::text, '<NULL>');
    END IF;
    UPDATE gl.vouchers SET status = 'POSTED'
     WHERE id = p_voucher_id AND status = 'APPROVED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fn_post_voucher: 并发冲突，凭证 % 状态已被变更', p_voucher_id;
    END IF;
    PERFORM audit.record('voucher_post', 'gl.vouchers', p_voucher_id,
        jsonb_build_object('status', v_v.status), jsonb_build_object('status', 'POSTED'),
        NULL, NULL);
END $$;

-- fn_fx_adjustment uuid 签名 + 权限闸口（先删 bigint 旧签名，CREATE OR REPLACE 不会合并
-- 不同签名，新库无 bigint 签名，DROP 为幂等空操作，最终实现由 init/gl_fx_rates.sql
-- 覆盖为真实调汇实现，uuid 签名与 require_permission 闸口一致）
-- 占位定义与真实现签名一致（含 DEFAULT 'CNY'），且仅当函数不存在时创建（pg_proc 判存），
-- 重放顺序 gl_core_functions 先于 gl_fx_rates，判存守卫保证不把已部署的真实现降级回占位。
DROP FUNCTION IF EXISTS gl.fn_fx_adjustment(bigint, text, text);
-- 外层 DO 用 $guard$ 标签避免与内层函数体 $$ 嵌套美元引号冲突（原 DO $$ 内嵌 AS $$
-- 在解析期截断外层字符串，报 'syntax error at or near BEGIN'），
-- 判存依据 pronargs=3，上方 DROP 已清 bigint 旧签名，剩余 3 参签名即 uuid 版
-- （不能比较 pg_get_function_identity_arguments 字面量，其返回含参数名
-- 'p_company_id uuid, p_period text, p_base_currency text'，实测与 'uuid, text, text'
-- 不相等导致判存失效重复建函数报 already exists）
DO $guard$
BEGIN
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
REVOKE EXECUTE ON FUNCTION gl.fn_fx_adjustment(uuid, text, text) FROM PUBLIC;

-- 文件末尾自查（期望 fn_cnt=8，其中 fn_voucher_status_history 由 init/gl_voucher_reject.sql 创建/升级，其余 7 个由本文件创建/升级）
SELECT 'gl_core_functions' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname IN ('gl','cash')
           AND p.proname IN ('fn_post_voucher','fn_reverse_voucher','fn_reject_voucher',
                             'fn_close_period','fn_reopen_period','fn_voucher_line_balance',
                             'fn_daily_report','fn_voucher_status_history'))        AS fn_cnt,       -- 期望 8
       (SELECT count(*) FROM pg_indexes
         WHERE schemaname = 'gl' AND indexname = 'uk_voucher_reversal')            AS uk_reversal_cnt, -- 期望 1
       (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
         JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'gl' AND t.tgname = 'trg_voucher_line_balance')          AS trg_balance_cnt; -- 期望 1
