-- FMS 财务系统（gl 域补充实现，执行库，fms），
-- gl.fx_rates 期末汇率表（rls 公司范围 + 职责角色授权 + updated_at 触发器 + 审计触发器挂载）

-- gl.fn_fx_adjustment 期末调汇，外币科目期末余额 × 期末汇率 − 按 1:1 计价的账面本币

-- （记账汇率不可得，voucher_lines 仅存 currency 无 rate，折本币差额按「原币余额 ×（期末汇率−1）」简化，设计决策见下文），生成 DRAFT 调汇凭证草稿
-- gl.fn_cron_voucher_number_check，断号发现写 audit.record（voucher_gap），缺口可追溯

-- fa.fn_run_depreciation，凭证生成补 created_by/company_id（NOT NULL 约束）、资产累加累计折旧、

-- 批次回填 voucher_id，与 Java 侧 FaServiceImpl 同口径
-- um.params 种子，fx.gain.account / fx.loss.account（存科目 code，沿用 fa.depr.*.account 约定，um.params 全局无 company_id，存 code 而非 id 才能跨公司生效），

-- um.permissions 种子，gl.period.close / gl.period.reopen / gl.period.fx_adjustment（函数体内已引用，operator 会话调用须经 require_permission）

-- 全部幂等可重跑（CREATE OR REPLACE / DROP ... IF EXISTS / ON CONFLICT DO NOTHING）

SET ROLE fms_admin;

-- gl.fx_rates 期末汇率表


-- 列语义，company_id 公司范围（rls can_access_org 收敛），currency CHAR，3 ISO 币种，
-- period 'YYYY-MM'，rate NUMERIC(18,6) 期末汇率（本币单位=1，如 USD 对 CNY 7.25），
-- updated_by/updated_at 维护审计，deleted_at 软删（统一经 auth.fn_soft_delete 闸口）。

-- 唯一键为部分唯一索引 (company_id, currency, period) WHERE deleted_at IS NULL，
-- 软删行不占键位，upsert 以新行复活，全量约束下软删行仍占键，DO UPDATE 分支对软删行
-- 触发 rls 检查恒报 42501，软删后重新维护汇率会失败，
-- 活跃行仍走 DO UPDATE 更新语义，Mapper 侧 ON CONFLICT 同步带索引谓词 WHERE deleted_at IS NULL。

-- CREATE TABLE IF NOT EXISTS 幂等保留，不 DROP 清空既有汇率数据
CREATE TABLE IF NOT EXISTS gl.fx_rates (
    id          uuid           PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid           NOT NULL,
    currency    char(3)        NOT NULL,
    period      char(7)        NOT NULL,
    rate        numeric(18,6)  NOT NULL CHECK (rate > 0),
    updated_by  uuid,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    created_at  timestamptz    NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);
-- 幂等迁移，删除全量唯一约束（uk_fx_rate）+ 创建部分唯一索引（软删行不占键，upsert 复活路径）
ALTER TABLE gl.fx_rates DROP CONSTRAINT IF EXISTS uk_fx_rate;
-- DROP INDEX 带 schema 限定，本文件 SET ROLE 后 search_path 为 "$user", public，不含 gl，
-- 未限定的 DROP INDEX IF EXISTS 按 search_path 解析找不到 gl schema 中的索引而静默跳过，
-- 而 CREATE UNIQUE INDEX（索引随表入 gl schema）随后必报 'relation
-- uk_fx_rate_active already exists'（rc=3），限定 gl. 后重放幂等。
DROP INDEX IF EXISTS gl.uk_fx_rate_active;
CREATE UNIQUE INDEX uk_fx_rate_active ON gl.fx_rates (company_id, currency, period)
    WHERE deleted_at IS NULL;
COMMENT ON TABLE  gl.fx_rates IS '期末汇率（公司×币种×期间唯一；调汇凭证生成的汇率依据）';
COMMENT ON COLUMN gl.fx_rates.rate IS '期末汇率：1 单位外币折本币（本币 base=1）';

-- updated_at 触发器（复用 gl.fn_set_updated_at，与 gl_base 各表同款）
DROP TRIGGER IF EXISTS trg_fx_rates_updated ON gl.fx_rates;
CREATE TRIGGER trg_fx_rates_updated
    BEFORE UPDATE ON gl.fx_rates
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

-- rls（FORCE + fail-closed，写策略 WITH CHECK 同 can_access_org，镜像 gl.chart_accounts）
ALTER TABLE gl.fx_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE gl.fx_rates FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS gl_fx_rates_select ON gl.fx_rates;
CREATE POLICY gl_fx_rates_select ON gl.fx_rates
    FOR SELECT USING (deleted_at IS NULL AND auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_fx_rates_insert ON gl.fx_rates;
CREATE POLICY gl_fx_rates_insert ON gl.fx_rates
    FOR INSERT WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS gl_fx_rates_update ON gl.fx_rates;
-- 注，upsert 复活依赖的唯一键语义已由部分唯一索引 uk_fx_rate_active 承载，软删行不占键，
-- UPDATE 策略保持原样，活跃行 DO UPDATE 分支命中，软删行对 SELECT/UPDATE 均不可见属预期
CREATE POLICY gl_fx_rates_update ON gl.fx_rates
    FOR UPDATE USING (deleted_at IS NULL AND auth.can_access_org(company_id))
    WITH CHECK (auth.can_access_org(company_id));
-- 汇率仅软删，物理 DELETE 不授权、不建策略
DROP POLICY IF EXISTS gl_fx_rates_delete ON gl.fx_rates;

-- 职责角色授权（gl_base 已授 gl schema USAGE，此处补表级，删除路径经 auth.fn_soft_delete 软删闸口）
GRANT SELECT, INSERT, UPDATE ON gl.fx_rates TO fms_operator;
GRANT SELECT, INSERT, UPDATE, DELETE ON gl.fx_rates TO fms_admin;
GRANT SELECT ON gl.fx_rates TO fms_viewer, fms_auditor, fms_report;

-- 审计捕获触发器挂载（与 gl_base 4 表同款，DML 自动留痕）
DROP TRIGGER IF EXISTS trg_fx_rates_audit ON gl.fx_rates;
CREATE TRIGGER trg_fx_rates_audit
    AFTER INSERT OR UPDATE OR DELETE ON gl.fx_rates
    FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log();

-- um.params / um.permissions 种子

-- 调汇损益科目映射参数（存科目 code，um.params 全局唯一无 company_id 列，存 code 运行时按公司解析，
-- 与 fa.depr.expense.account 同约定，缺省 6603 财务费用，种子科目无独立汇兑损益码，admin 可改）
INSERT INTO um.params (code, value, updated_by)
VALUES ('fx.gain.account', '{"v":"6603"}'::jsonb, '00000000-0000-7000-0000-000000000001'),
       ('fx.loss.account', '{"v":"6603"}'::jsonb, '00000000-0000-7000-0000-000000000001')
ON CONFLICT (code) DO NOTHING;

-- 期间/调汇权限点注册（gl_core_functions.sql fn_close_period/fn_reopen_period、gl_base.sql fn_fx_adjustment 体内
-- auth.require_permission 引用但 um.permissions 无种子行则非维护会话调用必抛"无权限"，
-- 注册后按权限矩阵分配 fms_admin_role 全量 + accountant，总账操作员。
-- 列对齐 init/um_perm.sql 升级后的 permissions 结构（module_id/identifier/resource_type/
-- action/permission_type/is_enabled/is_system 非空约束），module_id 取 um.modules 的 gl 根
INSERT INTO um.permissions (id, company_id, module_id, code, identifier, name,
                            resource_type, action, permission_type, is_enabled, is_system)
SELECT v.id, '00000000-0000-7000-0000-000000000010', m.id, v.code, v.code, v.name,
       'period', v.action, 'ACTION', true, false
  FROM (VALUES
        ('00000000-0000-7000-0000-000000000610'::uuid, 'gl.period.close',        '期间结账',   'close'),
        ('00000000-0000-7000-0000-000000000611'::uuid, 'gl.period.reopen',      '期间反结账', 'reopen'),
        ('00000000-0000-7000-0000-000000000612'::uuid, 'gl.period.fx_adjustment', '期末调汇',  'fx_adjustment')
       ) AS v(id, code, name, action)
  JOIN um.modules m ON m.identifier = 'gl'
ON CONFLICT (code) DO NOTHING;

INSERT INTO um.role_perm (company_id, role_id, perm_id, granted_by, granted_at)
SELECT '00000000-0000-7000-0000-000000000010', r.id, p.id,
       (SELECT id FROM um.users WHERE login_name = 'admin' AND deleted_at IS NULL), now()
  FROM um.roles r JOIN um.permissions p
    ON p.code IN ('gl.period.close','gl.period.reopen','gl.period.fx_adjustment')
 WHERE r.code IN ('fms_admin_role','accountant')
ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();

-- gl.fn_fx_adjustment 期末调汇实现


-- 调汇算法与账务简化设计决策，
-- * 记账汇率不可得（voucher_lines 仅 currency 无 rate 列），账面本币无法精确还原，
-- 采用任务指定简化，期末本币价值 = 原币余额 × 期末汇率，账面按 1:1 计价（base=CNY 时
-- 即原币余额 × 1），差额 = 原币余额 ×（期末汇率 − 1），全部计入汇兑损益科目。
-- * 外币科目期末余额 = 截至期间末全部 POSTED/REVERSED 凭证的累计净额，含期初，按科目
-- direction 定借贷向（DEBIT 科目借−贷、CREDIT 科目贷−借），REVERSED 与反向 POSTED
-- 金额互相抵消，不重复计量。
-- * 单科目调整额 adj = round(余额 × 汇率, 2) − round(余额, 2)，adj>0 按科目 direction
-- 同向入账（DEBIT 科目记借、CREDIT 科目记贷），adj<0 反向入账。
-- * 全部科目调整额合计 T，T>0 则贷方记汇兑收益（fx.gain.account），T<0 则借方记汇兑损失
-- （fx.loss.account），借贷恒等，T=0 不生成凭证。
-- * 无汇率记录的外币科目/公司跳过（NOTICE），同公司同期间已生成过调汇凭证（幂等键
-- idem_key = 'fx_adjustment:'+period）跳过，期间 CLOSED 由 trg_period_voucher_guard
-- 拒绝，调汇应在结账前完成，天然顺序保证。
-- * 凭证为 DRAFT 草稿（触发器 DRAFT 豁免平衡校验但本实现仍恒平衡），source_type
-- 'FX_ADJUSTMENT'，制单人 COALESCE(auth.current_uid, 种子 ADMIN_UID)，cron 以
-- postgres 身份调用无 guc 用户，落种子管理员保证 created_by NOT NULL。

-- 返回生成的调汇凭证数，SECURITY DEFINER + require_permission('gl.period.fx_adjustment')，
-- 声明 VOLATILE，写库函数，STABLE 声明在写库时运行期报错。
-- 参数默认值重放兼容，CREATE OR REPLACE 无法补加参数默认值（'cannot change the default
-- value of the parameter'），按 proargdefaults 判存，无默认值的旧定义先 DROP 后重建，
-- 当前定义已含 DEFAULT 'CNY'，正常路径无副作用，REVOKE/GRANT 段在下方重建授权
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'gl' AND p.proname = 'fn_fx_adjustment'
                  AND pg_get_function_identity_arguments(p.oid) = 'uuid, text, text'
                  AND p.proargdefaults IS NULL) THEN
        DROP FUNCTION gl.fn_fx_adjustment(uuid, text, text);
        RAISE NOTICE 'fn_fx_adjustment: 存量无默认值定义已删除，按新版签名（DEFAULT ''CNY''）重建';
    END IF;
END $$;
CREATE OR REPLACE FUNCTION gl.fn_fx_adjustment(
    p_company_id    uuid,
    p_period        text,
    p_base_currency text DEFAULT 'CNY')
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = gl, um, auth, audit, pg_catalog
AS $$
DECLARE
    v_period   text := COALESCE(p_period, to_char(now() - interval '1 month', 'YYYY-MM'));
    v_base     text := upper(COALESCE(NULLIF(btrim(p_base_currency), ''), 'CNY'));
    v_comp     uuid;
    v_param    jsonb;
    v_gain_code text := '6603';
    v_loss_code text := '6603';
    v_gain_id  uuid;
    v_loss_id  uuid;
    v_fcy      record;
    v_rate     numeric;
    v_adj      numeric;
    v_total    numeric := 0;
    v_debit    numeric := 0;
    v_credit   numeric := 0;
    v_line     jsonb;
    v_voucher_id uuid;
    v_uid      uuid;
    v_cnt      int := 0;
    v_lines    jsonb := '[]'::jsonb;
BEGIN
    PERFORM auth.require_permission('gl.period.fx_adjustment');
    IF NOT (v_period ~ '^\d{4}-\d{2}$') THEN
        RAISE EXCEPTION 'fn_fx_adjustment: 期间格式非法（%）', v_period;
    END IF;

    -- 汇兑损益科目 code（um.params fx.gain.account / fx.loss.account，缺省 6603 财务费用，
    -- 读取语义与 fa.depr.*.account 同款，裸 json 标量或 {"v":code}）
    BEGIN
        SELECT value INTO v_param FROM um.params WHERE code = 'fx.gain.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_gain_code := COALESCE(NULLIF(v_param #>> '{}', ''), NULLIF(v_param->>'v', ''), v_gain_code);
        END IF;
        SELECT value INTO v_param FROM um.params WHERE code = 'fx.loss.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_loss_code := COALESCE(NULLIF(v_param #>> '{}', ''), NULLIF(v_param->>'v', ''), v_loss_code);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        NULL;                                    -- 参数表异常，维持缺省 6603
    END;

    -- 公司循环，存在外币（≠base）发生额的公司（p_company_id NULL = 全部，cron 月调用口径）
    FOR v_comp IN
        SELECT DISTINCT v.company_id
          FROM gl.vouchers v
          JOIN gl.voucher_lines vl ON vl.voucher_id = v.id AND vl.deleted_at IS NULL
         WHERE v.deleted_at IS NULL
           AND v.status IN ('POSTED','REVERSED')
           AND v.period <= v_period
           AND vl.currency <> v_base
           AND (p_company_id IS NULL OR v.company_id = p_company_id)
         ORDER BY 1
    LOOP
        BEGIN
            -- 幂等，同公司同期间已生成调汇凭证（idem_key 等值 = 'fx_adjustment:'+period，跨 created_by 均命中）则跳过
            IF EXISTS (SELECT 1 FROM gl.vouchers
                        WHERE company_id = v_comp AND period = v_period
                          AND idem_key = 'fx_adjustment:' || v_period
                          AND deleted_at IS NULL) THEN
                RAISE NOTICE 'fn_fx_adjustment: 公司 % 期间 % 已生成调汇凭证，跳过（幂等）', v_comp, v_period;
                CONTINUE;
            END IF;
            -- 该期间无任何汇率记录则无调汇依据，跳过，NOTICE 而非报错，cron 全公司扫描容错
            IF NOT EXISTS (SELECT 1 FROM gl.fx_rates
                            WHERE company_id = v_comp AND period = v_period
                              AND deleted_at IS NULL) THEN
                RAISE NOTICE 'fn_fx_adjustment: 公司 % 期间 % 无期末汇率记录，跳过', v_comp, v_period;
                CONTINUE;
            END IF;

            v_total  := 0;
            v_debit  := 0;
            v_credit := 0;
            v_lines  := '[]'::jsonb;

            -- 外币科目×币种期末余额（截至期间末累计净额，科目须 is_foreign_currency）
            FOR v_fcy IN
                SELECT ca.id AS account_id, ca.code, ca.name, ca.direction, vl.currency,
                       SUM(CASE WHEN ca.direction = 'DEBIT' THEN vl.debit - vl.credit
                                ELSE vl.credit - vl.debit END) AS fcy_balance
                  FROM gl.voucher_lines vl
                  JOIN gl.vouchers v ON v.id = vl.voucher_id AND v.deleted_at IS NULL
                       AND v.status IN ('POSTED','REVERSED') AND v.period <= v_period
                  JOIN gl.chart_accounts ca ON ca.id = vl.account_id AND ca.deleted_at IS NULL
                       AND ca.is_foreign_currency
                 WHERE vl.deleted_at IS NULL AND vl.currency <> v_base
                   AND v.company_id = v_comp
                 GROUP BY ca.id, ca.code, ca.name, ca.direction, vl.currency
                 HAVING SUM(CASE WHEN ca.direction = 'DEBIT' THEN vl.debit - vl.credit
                                 ELSE vl.credit - vl.debit END) <> 0
                 ORDER BY ca.code, vl.currency
            LOOP
                SELECT rate INTO v_rate FROM gl.fx_rates
                 WHERE company_id = v_comp AND currency = v_fcy.currency
                   AND period = v_period AND deleted_at IS NULL;
                IF v_rate IS NULL THEN
                    RAISE NOTICE 'fn_fx_adjustment: 公司 % 币种 % 期间 % 无汇率，科目 % 跳过',
                        v_comp, v_fcy.currency, v_period, v_fcy.code;
                    CONTINUE;
                END IF;
                -- 差额 = 原币余额 ×（期末汇率 − 1）（1 = base 币种按 1:1 记账的简化账面）
                v_adj := round(v_fcy.fcy_balance * v_rate, 2) - round(v_fcy.fcy_balance, 2);
                IF v_adj = 0 THEN
                    CONTINUE;
                END IF;
                v_total := v_total + v_adj;
                v_lines := v_lines || jsonb_build_object(
                    'account_id', v_fcy.account_id,
                    'currency',   v_fcy.currency,
                    'adj',        v_adj);
            END LOOP;

            IF v_total = 0 THEN
                RAISE NOTICE 'fn_fx_adjustment: 公司 % 期间 % 调整额合计为 0，不生成凭证', v_comp, v_period;
                CONTINUE;
            END IF;

            -- 汇兑损益科目按公司解析（末级科目校验由 trg_voucher_line_leaf 终审）
            SELECT id INTO v_gain_id FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_gain_code AND deleted_at IS NULL;
            SELECT id INTO v_loss_id FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_loss_code AND deleted_at IS NULL;
            IF v_gain_id IS NULL OR v_loss_id IS NULL THEN
                RAISE NOTICE 'fn_fx_adjustment: 公司 % 汇兑损益科目 %/% 不存在，跳过', v_comp, v_gain_code, v_loss_code;
                CONTINUE;
            END IF;

            -- 借/贷合计，先记账面调整行，再轧差，调整额按 adj 符号与科目 direction 定向，
            -- adj>0 表示"按期末汇率估值后本币价值上升"，DEBIT 科目记借、CREDIT 科目记贷，
            -- adj<0 反之（DEBIT 记贷、CREDIT 记借），v_line 为单列 FOR 循环变量，直接取
            -- jsonb 元素本身（v_line->>'adj' 而非 v_line.line->>'adj'，无 line 字段）
            FOR v_line IN SELECT e.line FROM jsonb_array_elements(v_lines) AS e(line)
            LOOP
                IF (v_line->>'adj')::numeric > 0 THEN
                    IF EXISTS (SELECT 1 FROM gl.chart_accounts
                                WHERE id = (v_line->>'account_id')::uuid AND direction = 'DEBIT') THEN
                        v_debit := v_debit + (v_line->>'adj')::numeric;
                    ELSE
                        v_credit := v_credit + (v_line->>'adj')::numeric;
                    END IF;
                ELSE
                    IF EXISTS (SELECT 1 FROM gl.chart_accounts
                                WHERE id = (v_line->>'account_id')::uuid AND direction = 'DEBIT') THEN
                        v_credit := v_credit + ABS((v_line->>'adj')::numeric);
                    ELSE
                        v_debit := v_debit + ABS((v_line->>'adj')::numeric);
                    END IF;
                END IF;
            END LOOP;
            -- 轧差入汇兑损益，T>0 则贷收益科目，T<0 则借损失科目，借贷恒等
            IF v_total > 0 THEN
                v_credit := v_credit + v_total;
            ELSE
                v_debit := v_debit + ABS(v_total);
            END IF;

            -- 生成 DRAFT 调汇凭证（idem_key = 'fx_adjustment:'+period，DRAFT 豁免平衡校验但恒平衡，
            -- total_debit/total_credit 与分录合计严格一致，触发器非 DRAFT 才校验，此处自洽双保险）
            v_uid := COALESCE(auth.current_uid(), '00000000-0000-7000-0000-000000000001'::uuid);
            INSERT INTO gl.vouchers (company_id, voucher_no, period, voucher_date, status,
                                     source_type, total_debit, total_credit, created_by,
                                     idem_key, note)
            VALUES (v_comp, NULL, v_period,
                    (to_date(v_period || '-01', 'YYYY-MM-DD') + interval '1 month'
                     - interval '1 day')::date,
                    'DRAFT', 'FX_ADJUSTMENT', v_debit, v_credit,
                    v_uid, 'fx_adjustment:' || v_period, '期末调汇 ' || v_period)
            RETURNING id INTO v_voucher_id;

            -- 分录，外币科目调整行（四象限借/贷映射，adj>0 按 direction 同向，adj<0 反向，
            -- line_no 用 row_number 顺序编号，uk_voucher_line 部分唯一索引 (voucher_id, line_no) 防重复）
            INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary,
                                          debit, credit, currency, company_id)
            SELECT v_voucher_id, (row_number() OVER ())::int, (l.line->>'account_id')::uuid,
                   '期末调汇 ' || v_period || ' ' || (l.line->>'currency'),
                   CASE WHEN (l.line->>'adj')::numeric > 0
                             AND (SELECT direction FROM gl.chart_accounts
                                   WHERE id = (l.line->>'account_id')::uuid) = 'DEBIT'
                        THEN ABS((l.line->>'adj')::numeric)
                        WHEN (l.line->>'adj')::numeric < 0
                             AND (SELECT direction FROM gl.chart_accounts
                                   WHERE id = (l.line->>'account_id')::uuid) = 'CREDIT'
                        THEN ABS((l.line->>'adj')::numeric) ELSE 0 END,
                   CASE WHEN (l.line->>'adj')::numeric > 0
                             AND (SELECT direction FROM gl.chart_accounts
                                   WHERE id = (l.line->>'account_id')::uuid) = 'CREDIT'
                        THEN ABS((l.line->>'adj')::numeric)
                        WHEN (l.line->>'adj')::numeric < 0
                             AND (SELECT direction FROM gl.chart_accounts
                                   WHERE id = (l.line->>'account_id')::uuid) = 'DEBIT'
                        THEN ABS((l.line->>'adj')::numeric) ELSE 0 END,
                   (l.line->>'currency'), v_comp
              FROM jsonb_array_elements(v_lines) AS l(line);
            INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id, summary,
                                          debit, credit, currency, company_id)
            VALUES (v_voucher_id, (SELECT count(*) + 1 FROM gl.voucher_lines
                                    WHERE voucher_id = v_voucher_id),
                    CASE WHEN v_total > 0 THEN v_gain_id ELSE v_loss_id END,
                    CASE WHEN v_total > 0 THEN '汇兑收益 ' || v_period ELSE '汇兑损失 ' || v_period END,
                    CASE WHEN v_total > 0 THEN 0 ELSE ABS(v_total) END,
                    CASE WHEN v_total > 0 THEN v_total ELSE 0 END,
                    v_base, v_comp);

            v_cnt := v_cnt + 1;
            RAISE NOTICE 'fn_fx_adjustment: 公司 % 期间 % 调汇凭证 % 生成（差额 %）',
                v_comp, v_period, v_voucher_id, v_total;
        EXCEPTION WHEN OTHERS THEN
            -- 单公司失败不阻断全扫描，cron 容错，期间 CLOSED/科目禁用等均在此兜底
            RAISE NOTICE 'fn_fx_adjustment: 公司 % 期间 % 调汇失败（跳过）：%', v_comp, v_period, SQLERRM;
        END;
    END LOOP;

    RETURN v_cnt;
END $$;
COMMENT ON FUNCTION gl.fn_fx_adjustment(uuid, text, text)
    IS '期末调汇：外币科目期末余额 × 期末汇率 − 按 1:1 计价账面 -> DRAFT 调汇凭证（幂等键 fx_adjustment:+period；无汇率跳过；期间 CLOSED 由凭证期间守卫拒绝）';
REVOKE ALL ON FUNCTION gl.fn_fx_adjustment(uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION gl.fn_fx_adjustment(uuid, text, text) TO fms_operator;

-- gl.fn_cron_voucher_number_check 断号审计告警

-- 断号发现写 audit.record（action='voucher_gap'，new_values = {gap_count,
-- gaps:[{company_id, period, gap_from, gap_to, gap_count}]}），前端 gapAlert 经审计/断号
-- 在线查询（GET /api/gl/voucher-gaps）展示。
-- 声明 VOLATILE，audit.record 写库，STABLE 函数内 INSERT 会运行期报错。
CREATE OR REPLACE FUNCTION gl.fn_cron_voucher_number_check()
RETURNS int
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = gl, audit, pg_catalog
AS $$
DECLARE
    v_gaps  int := 0;
    v_row   record;
    v_batch jsonb := '[]'::jsonb;
BEGIN
    FOR v_row IN
        SELECT g.company_id, g.period,
               (g.prev_no + 1)::int AS gap_from,
               (g.voucher_no - 1)::int AS gap_to,
               (g.voucher_no - g.prev_no - 1)::int AS gap_count
          FROM (SELECT company_id, period, voucher_no,
                       lag(voucher_no) OVER
                           (PARTITION BY company_id, period ORDER BY voucher_no) AS prev_no
                  FROM gl.vouchers
                 WHERE status IN ('POSTED','REVERSED') AND deleted_at IS NULL
                   AND voucher_no IS NOT NULL) g
         WHERE g.prev_no IS NOT NULL AND g.voucher_no - g.prev_no > 1
         ORDER BY g.company_id, g.period, g.prev_no
    LOOP
        v_batch := v_batch || jsonb_build_object(
            'company_id', v_row.company_id, 'period', v_row.period,
            'gap_from', v_row.gap_from, 'gap_to', v_row.gap_to,
            'gap_count', v_row.gap_count);
        v_gaps := v_gaps + 1;
    END LOOP;
    IF v_gaps > 0 THEN
        PERFORM audit.record('voucher_gap', 'gl.vouchers', NULL, NULL,
            jsonb_build_object('gap_count', v_gaps, 'gaps', v_batch),
            NULL, NULL);
    END IF;
    RAISE NOTICE 'fn_cron_voucher_number_check: 断号缺口 % 处（已写审计 voucher_gap）', v_gaps;
    RETURN v_gaps;
END $$;
COMMENT ON FUNCTION gl.fn_cron_voucher_number_check()
    IS '凭证断号扫描（每日 06:00）：期间内 POSTED/REVERSED 凭证号缺口计数，发现缺口写 audit.record(voucher_gap) 留痕';
REVOKE ALL ON FUNCTION gl.fn_cron_voucher_number_check() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION gl.fn_cron_voucher_number_check() TO fms_operator;

-- 折旧过账双路径统一（fa.fn_run_depreciation 重建）

-- pg 与 Java（FaServiceImpl）双路径同口径，
-- * created_by 落 COALESCE(auth.current_uid, 种子 ADMIN_UID)，cron 无 guc 用户，
-- 分录补 company_id，两列 NOT NULL 无缺省，缺失则凭证生成恒被拦截，
-- * 逐资产累加累计折旧并 ACTIVE 转 DEPRECIATING，与 Java 同算法，
-- * 凭证幂等键 idem_key='fa_depreciation:'+period，双路径互斥，先跑者胜，
-- * 批次回填 voucher_id（fa.depreciation_runs 新增列，展示链路）。
-- Java 侧 FaServiceImpl 同构生成 DRAFT 折旧凭证 + 回填 voucher_id，同文件批次。
ALTER TABLE fa.depreciation_runs
    ADD COLUMN IF NOT EXISTS voucher_id uuid REFERENCES gl.vouchers(id);
COMMENT ON COLUMN fa.depreciation_runs.voucher_id
    IS '折旧凭证草稿 id（fa.fn_run_depreciation / FaServiceImpl 双路径回填，前端可跳转凭证）';

CREATE OR REPLACE FUNCTION fa.fn_run_depreciation(
    p_company_id uuid    DEFAULT NULL,
    p_period     text    DEFAULT NULL)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = fa, gl, um, auth, pg_catalog
AS $$
DECLARE
    v_period      text := COALESCE(p_period, to_char(now(),'YYYY-MM'));
    v_period_end  date;
    v_comp        uuid;
    v_lines       jsonb := '[]'::jsonb;
    v_total       numeric := 0;
    v_cnt         bigint := 0;
    v_asset       record;
    v_monthly     numeric;
    v_cap         numeric;
    v_amount      numeric;
    v_rate        numeric;
    v_dep_code    text := '6602';                -- 折旧费用行科目 code（um.params fa.depr.expense.account 可配）
    v_fa_code     text := '1601';                -- 累计折旧行科目 code（um.params fa.depr.asset.account 可配）
    v_dep_account uuid;
    v_fa_account  uuid;
    v_param       jsonb;
    v_voucher_id  uuid;
    v_run_id      uuid;
    v_uid         uuid;
BEGIN
    -- 权限闸口，SECURITY DEFINER 且无鉴权时任意会话可代跑，require_permission 维护会话直通
    PERFORM auth.require_permission('fa.depreciation.create');
    IF to_regclass('gl.vouchers') IS NULL OR to_regclass('gl.voucher_lines') IS NULL THEN
        RAISE NOTICE 'gl 表不存在，跳过折旧计提';
        RETURN 0;
    END IF;
    v_period_end := (to_date(v_period || '-01','YYYY-MM-DD') + interval '1 month'
                     - interval '1 day')::date;

    -- 折旧凭证科目映射参数，缺省 6602 管理费用 / 1601 固定资产，科目不存在则跳过凭证生成
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'fa.depr.expense.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_dep_code := COALESCE(NULLIF(v_param #>> '{}', ''), NULLIF(v_param->>'v',''), '6602');
        END IF;
        SELECT value INTO v_param FROM um.params
         WHERE code = 'fa.depr.asset.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_fa_code := COALESCE(NULLIF(v_param #>> '{}', ''), NULLIF(v_param->>'v',''), '1601');
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_dep_code := '6602'; v_fa_code := '1601';
    END;

    FOR v_comp IN
        SELECT DISTINCT company_id FROM fa.assets
         WHERE deleted_at IS NULL
           AND (p_company_id IS NULL OR company_id = p_company_id)
           AND status IN ('ACTIVE','DEPRECIATING')
        ORDER BY company_id
    LOOP
        -- 防重复，该期间已存在 CONFIRMED 批次则跳过，DRAFT 旧批次清空重建，幂等
        IF EXISTS (SELECT 1 FROM fa.depreciation_runs
                    WHERE company_id = v_comp AND period = v_period
                      AND status = 'CONFIRMED' AND deleted_at IS NULL) THEN
            CONTINUE;
        END IF;
        DELETE FROM fa.depreciation_runs
         WHERE company_id = v_comp AND period = v_period AND status = 'DRAFT';

        v_lines := '[]'::jsonb;
        v_total := 0;

        FOR v_asset IN
            SELECT * FROM fa.assets
             WHERE company_id = v_comp
               AND status IN ('ACTIVE','DEPRECIATING')
               AND deleted_at IS NULL
               AND start_date IS NOT NULL AND start_date < v_period_end
               AND accumulated_depreciation < (original_value - residual_value)
             ORDER BY id
        LOOP
            IF v_asset.depreciation_method = 'DOUBLE_DECLINING' THEN
                v_rate    := 2.0 / v_asset.life_months;
                v_monthly := round((v_asset.original_value - v_asset.accumulated_depreciation) * v_rate, 2);
            ELSE
                v_monthly := round((v_asset.original_value - v_asset.residual_value)
                                   / v_asset.life_months, 2);
            END IF;
            v_cap    := v_asset.original_value - v_asset.residual_value
                        - v_asset.accumulated_depreciation;      -- 尚可计提上限
            v_amount := LEAST(v_monthly, v_cap);
            IF v_amount > 0 THEN
                v_lines := v_lines || jsonb_build_array(
                    jsonb_build_object('asset_id', v_asset.id,
                                       'asset_code', v_asset.asset_code,
                                       'category',  v_asset.category,
                                       'amount',    v_amount,
                                       'method',    v_asset.depreciation_method));
                v_total := v_total + v_amount;
                v_cnt   := v_cnt + 1;
                -- 统一(c)，计提生效落资产，与 Java FaServiceImpl 同口径，累计折旧累加 +
                -- 首次计提 ACTIVE 转 DEPRECIATING，DRAFT 批次重建时 SELECT 已按最新累计折旧
                -- 重算 v_cap，天然防同期间双计提
                UPDATE fa.assets
                   SET accumulated_depreciation = accumulated_depreciation + v_amount,
                       status = CASE WHEN status = 'ACTIVE' THEN 'DEPRECIATING' ELSE status END
                 WHERE id = v_asset.id;
            END IF;
        END LOOP;

        -- 批次记录（DRAFT，CONFIRM 由业务确认），返回 id 供凭证回填
        INSERT INTO fa.depreciation_runs (company_id, period, run_by, status, lines)
        VALUES (v_comp, v_period, auth.current_uid(), 'DRAFT', v_lines)
        RETURNING id INTO v_run_id;

        -- 折旧凭证草稿（统一(a)(b)，created_by 非空、分录 company_id 补齐，幂等键
        -- 'fa_depreciation:'+period 与 Java 侧互斥，科目缺失/结构不兼容容错跳过仅记批次）
        IF v_total > 0
           AND NOT EXISTS (SELECT 1 FROM gl.vouchers
                            WHERE company_id = v_comp AND period = v_period
                              AND idem_key = 'fa_depreciation:' || v_period
                              AND deleted_at IS NULL) THEN
            SELECT id INTO v_dep_account FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_dep_code AND deleted_at IS NULL;
            SELECT id INTO v_fa_account FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_fa_code AND deleted_at IS NULL;
            IF v_dep_account IS NOT NULL AND v_fa_account IS NOT NULL THEN
                BEGIN
                    v_uid := COALESCE(auth.current_uid(), '00000000-0000-7000-0000-000000000001'::uuid);
                    INSERT INTO gl.vouchers (company_id, voucher_no, period,
                                             voucher_date, status, source_type,
                                             total_debit, total_credit, created_by,
                                             idem_key, note)
                    VALUES (v_comp, NULL, v_period, v_period_end, 'DRAFT',
                            'DEPRECIATION', v_total, v_total, v_uid,
                            'fa_depreciation:' || v_period, '折旧计提 ' || v_period)
                    RETURNING id INTO v_voucher_id;
                    INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id,
                                                  summary, debit, credit, company_id)
                    VALUES (v_voucher_id, 1, v_dep_account, '折旧费用 ' || v_period, v_total, 0, v_comp),
                           (v_voucher_id, 2, v_fa_account,  '累计折旧 ' || v_period, 0, v_total, v_comp);
                    UPDATE fa.depreciation_runs SET voucher_id = v_voucher_id
                     WHERE id = v_run_id;
                EXCEPTION WHEN OTHERS THEN
                    RAISE NOTICE '公司 % 折旧凭证草稿生成失败（忽略，批次已记录）：%',
                                 v_comp, SQLERRM;
                END;
            END IF;
        END IF;
    END LOOP;

    RETURN v_cnt;
END
$$;
COMMENT ON FUNCTION fa.fn_run_depreciation(uuid,text)
    IS '折旧计提：直线/双倍余额 -> 批次 + 资产累计折旧累加 + 折旧凭证草稿（幂等键 fa_depreciation:+period；批次回填 voucher_id）';
REVOKE ALL ON FUNCTION fa.fn_run_depreciation(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fa.fn_run_depreciation(uuid, text) TO fms_operator;

-- b gl 侧配套小函数（Java FaServiceImpl 凭证生成共用，避免 Java 直读 um.params，无表级授权

-- 与科目解析重复实现，科目映射参数单一来源仍在 pg），
-- * gl.fn_depr_accounts(p_company_id)，按 um.params fa.depr.*.account 解析该公司折旧凭证
-- 两科目 id（jsonb {depreciation, accumulated}，科目缺失为 null）
-- * gl.fn_idem_voucher_exists(p_company_id, p_period, p_idem_key)，幂等键查重（SECURITY
-- DEFINER 绕过 rls，DRAFT 凭证仅创建人可见，跨路径（cron/不同操作员）查重必须无视
-- rls，只读布尔，不泄露明细）
CREATE OR REPLACE FUNCTION gl.fn_depr_accounts(p_company_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = gl, um, pg_catalog
AS $$
DECLARE
    v_dep_code text := '6602';
    v_fa_code  text := '1601';
    v_param    jsonb;
    v_dep      uuid;
    v_fa       uuid;
BEGIN
    BEGIN
        SELECT value INTO v_param FROM um.params WHERE code = 'fa.depr.expense.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_dep_code := COALESCE(NULLIF(v_param #>> '{}',''), NULLIF(v_param->>'v',''), v_dep_code);
        END IF;
        SELECT value INTO v_param FROM um.params WHERE code = 'fa.depr.asset.account' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            v_fa_code := COALESCE(NULLIF(v_param #>> '{}',''), NULLIF(v_param->>'v',''), v_fa_code);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;
    SELECT id INTO v_dep FROM gl.chart_accounts
     WHERE company_id = p_company_id AND code = v_dep_code AND deleted_at IS NULL;
    SELECT id INTO v_fa FROM gl.chart_accounts
     WHERE company_id = p_company_id AND code = v_fa_code AND deleted_at IS NULL;
    RETURN jsonb_build_object('depreciation', v_dep, 'accumulated', v_fa);
END $$;
COMMENT ON FUNCTION gl.fn_depr_accounts(uuid)
    IS '折旧凭证科目解析：按 um.params fa.depr.*.account 返回该公司 {depreciation, accumulated} 科目 id';
REVOKE ALL ON FUNCTION gl.fn_depr_accounts(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION gl.fn_depr_accounts(uuid) TO fms_operator;

CREATE OR REPLACE FUNCTION gl.fn_idem_voucher_exists(p_company_id uuid, p_period text, p_idem_key text)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = gl, pg_catalog
AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM gl.vouchers
                    WHERE company_id = p_company_id AND period = p_period
                      AND idem_key = p_idem_key AND deleted_at IS NULL);
END $$;
COMMENT ON FUNCTION gl.fn_idem_voucher_exists(uuid, text, text)
    IS '凭证幂等键存在性查重（SECURITY DEFINER 无视 RLS——DRAFT 仅创建人可见，跨路径查重必须全量可见；只读布尔）';
REVOKE ALL ON FUNCTION gl.fn_idem_voucher_exists(uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION gl.fn_idem_voucher_exists(uuid, text, text) TO fms_operator;

-- 文件末尾自查（psql -v ON_ERROR_STOP=1 下确认本文件执行完整）
SELECT 'gl_fx_rates' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname = 'gl' AND tablename = 'fx_rates')                                 AS fx_rates_table,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'gl'
           AND p.proname IN ('fn_fx_adjustment','fn_cron_voucher_number_check',
                             'fn_depr_accounts','fn_idem_voucher_exists'))                    AS gl_fn_cnt,
       (SELECT count(*) FROM um.params
         WHERE code IN ('fx.gain.account','fx.loss.account') AND deleted_at IS NULL)        AS fx_param_cnt,
       (SELECT count(*) FROM um.permissions
         WHERE code LIKE 'gl.period.%')                                                      AS period_perm_cnt,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema = 'fa' AND table_name = 'depreciation_runs'
           AND column_name = 'voucher_id')                                                   AS fa_voucher_id_col;
