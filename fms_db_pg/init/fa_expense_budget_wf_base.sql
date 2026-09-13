-- FMS 财务系统（init/fa_expense_budget_wf_base），wf 4 表（definitions/instances/tasks/task_events + 领单/办结状态机函数）+ fa 2 表（assets/depreciation_runs）+ expense 2 表（reimbursements/reimbursement_lines）+ budget 2 表（budget_plans/budget_adjustments + 超支阻断触发器）+ rls + 触发器 + cron 任务函数，主键 uuidv7（pg 18 原生），全部 DDL 幂等

-- 职责，budget.budget_adjustments 预算调整单幂等防护，idem_key 列 + uk_adjustment_idem 部分唯一索引（同公司同计划同内容仅一条有效调整单，重复提交 23505 映射 40900，软删行不占键）
-- 职责，wf 流程实例发起链路，uk_wf_inst_business 部分唯一索引，同一业务单据仅一个未删除实例+ wf.fn_start_instance 实例/首任务原子创建函数

-- 预算阻断与执行顺序适配，预算阻断触发器优先读 rpt.budget_actual immv，rpt_log_base.sql 先建则引用安全，若本文件先于 rpt_log_base.sql 执行，immv 尚不存在，函数在运行期用 to_regclass 探测并按同一口径回退读 gl 表内联聚合，两种执行顺序均正确

SET ROLE fms_admin;

-- wf 工作流模块，4 表，流程定义（node_json 节点编排）创建流程实例，业务单据挂接生成任务（assignee/status/due_at 联合索引 + 乐观锁 version），任务事件仅追加
-- 1.1 wf.definitions 流程定义
CREATE TABLE IF NOT EXISTS wf.definitions (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    code        TEXT    NOT NULL,                -- 流程编码（如 REIMBURSEMENT）
    name        TEXT    NOT NULL,                -- 流程名称
    version     INT     NOT NULL DEFAULT 1,
    form_type   TEXT,                            -- 关联表单类型
    node_json   JSONB   NOT NULL DEFAULT '{}'::jsonb,  -- 节点编排数组，APPROVE 含 strategy 与 handlers，START/END 仅 node_code/type
    status      TEXT    NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','ACTIVE','DISABLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uk_wf_def UNIQUE (company_id, code)
);
COMMENT ON TABLE  wf.definitions IS '工作流流程定义（node_json 编排审批节点）';
COMMENT ON COLUMN wf.definitions.node_json IS '节点编排 JSONB 数组，元素含 node_code 与 type；APPROVE 节点含 strategy（approve/countersign/orsign）与 handlers 处理人来源数组（source 为 role/user/applicant）；START/END 不携带处理人';

-- 1.2 wf.instances 流程实例
CREATE TABLE IF NOT EXISTS wf.instances (
    id             uuid    PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid    NOT NULL,
    definition_id  uuid    NOT NULL REFERENCES wf.definitions (id),
    business_type  TEXT    NOT NULL,              -- 业务类型（REIMBURSEMENT 等）
    business_id    uuid    NOT NULL,              -- 业务单据主键
    initiator_id   uuid    NOT NULL REFERENCES um.users (id),
    current_node   TEXT,                          -- 当前节点编码
    status         TEXT    NOT NULL DEFAULT 'RUNNING'
                   CHECK (status IN ('RUNNING','APPROVED','REJECTED','CANCELED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
COMMENT ON TABLE wf.instances IS '工作流实例（业务单据通用挂接：business_type+business_id）';

CREATE INDEX IF NOT EXISTS idx_wf_inst_biz     ON wf.instances (business_type, business_id);
CREATE INDEX IF NOT EXISTS idx_wf_inst_init    ON wf.instances (initiator_id, status);
CREATE INDEX IF NOT EXISTS idx_wf_inst_def     ON wf.instances (definition_id);

-- 1.3 wf.tasks 任务（SKIP LOCKED 并发领取核心表），company_id 由 trg_wf_task_fill_company 从实例自动补全（BEFORE INSERT）
CREATE TABLE IF NOT EXISTS wf.tasks (
    id           uuid   PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid   NOT NULL,                -- 冗余公司范围（rls 用，触发器补全）
    instance_id  uuid   NOT NULL REFERENCES wf.instances (id),
    node_code    TEXT   NOT NULL,
    assignee_id  uuid   NOT NULL REFERENCES um.users (id),
    task_type    TEXT   NOT NULL DEFAULT 'APPROVE'
                 CHECK (task_type IN ('APPROVE','COUNTERSIGN','OR_SIGN')),
    status       TEXT   NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','CLAIMED','APPROVED','REJECTED',
                                   'TRANSFERRED','TIMEOUT')),
    due_at       TIMESTAMPTZ NOT NULL,           -- 截止时间，催办扫描用
    claim_at     TIMESTAMPTZ,
    version      INT    NOT NULL DEFAULT 0,      -- 乐观锁
    remark       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);
COMMENT ON TABLE  wf.tasks IS '工作流任务（并发领取：UPDATE + FOR UPDATE SKIP LOCKED，仅一人成功）';
COMMENT ON COLUMN wf.tasks.version IS '乐观锁版本号（领取/办结 +1）';

-- assignee/status/due_at/instance_id 联合索引，待办列表 + 催办扫描 + 实例推进
CREATE INDEX IF NOT EXISTS idx_wf_task_assignee_status ON wf.tasks (assignee_id, status);
CREATE INDEX IF NOT EXISTS idx_wf_task_status_due      ON wf.tasks (status, due_at);
CREATE INDEX IF NOT EXISTS idx_wf_task_instance_status ON wf.tasks (instance_id, status);

-- 1.4 wf.task_events 任务事件（仅追加，company_id 由触发器从任务补全）
CREATE TABLE IF NOT EXISTS wf.task_events (
    id           uuid   PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid   NOT NULL,                -- 冗余公司范围（rls 用，触发器补全）
    task_id      uuid   NOT NULL REFERENCES wf.tasks (id),
    action       TEXT   NOT NULL
                 CHECK (action IN ('CLAIM','APPROVE','REJECT','TRANSFER','REMIND','TIMEOUT')),
    operator_id  uuid   REFERENCES um.users (id),
    from_node    TEXT,
    to_node      TEXT,
    remark       TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE wf.task_events IS '任务事件流水（仅追加，不挂审计触发器——本身即日志）';

CREATE INDEX IF NOT EXISTS idx_wf_evt_task ON wf.task_events (task_id);
CREATE INDEX IF NOT EXISTS idx_wf_evt_company ON wf.task_events (company_id, occurred_at);

-- fa 固定资产模块，2 表
-- 2.1 fa.assets 资产卡片（net_value 为生成列 = original_value - accumulated_depreciation）
CREATE TABLE IF NOT EXISTS fa.assets (
    id                      uuid           PRIMARY KEY DEFAULT uuidv7(),
    company_id              uuid           NOT NULL,
    asset_code              TEXT           NOT NULL,
    category                TEXT           NOT NULL,     -- 资产类别，如 办公设备/运输工具
    original_value          NUMERIC(18,2)  NOT NULL CHECK (original_value >= 0),
    residual_value          NUMERIC(18,2)  NOT NULL DEFAULT 0 CHECK (residual_value >= 0),
    life_months             INT            NOT NULL CHECK (life_months > 0),
    depreciation_method     TEXT           NOT NULL DEFAULT 'STRAIGHT_LINE'
                            CHECK (depreciation_method IN ('STRAIGHT_LINE','DOUBLE_DECLINING')),
    accumulated_depreciation NUMERIC(18,2) NOT NULL DEFAULT 0
                            CHECK (accumulated_depreciation >= 0),
    net_value               NUMERIC(18,2) GENERATED ALWAYS AS
                            (original_value - accumulated_depreciation) STORED,
    start_date              DATE,                -- 开始计提日期
    status                  TEXT           NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE','DEPRECIATING','DISPOSED','SCRAPPED')),
    disposal_date           DATE,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT uk_asset UNIQUE (company_id, asset_code),
    CONSTRAINT ck_asset_depr_le_orig CHECK (accumulated_depreciation <= original_value)
);
COMMENT ON TABLE  fa.assets IS '固定资产卡片';
COMMENT ON COLUMN fa.assets.net_value IS '净值 = 原值 - 累计折旧（生成列）';

CREATE INDEX IF NOT EXISTS idx_asset_status   ON fa.assets (status);
CREATE INDEX IF NOT EXISTS idx_asset_category ON fa.assets (company_id, category);

-- 2.2 fa.depreciation_runs 折旧批次（period 唯一按公司，CONFIRMED 批次仅一条防重复计提，部分唯一索引）
CREATE TABLE IF NOT EXISTS fa.depreciation_runs (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    period      TEXT          NOT NULL,                 -- 'YYYY-MM'
    run_by      uuid          REFERENCES um.users (id),
    status      TEXT          NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','CONFIRMED')),
    lines       JSONB         NOT NULL DEFAULT '[]'::jsonb,  -- [{asset_id,asset_code,amount,method}]
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);
COMMENT ON TABLE fa.depreciation_runs IS '折旧计提批次（CONFIRMED 后同公司同期间唯一，防重复计提）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_depr_run ON fa.depreciation_runs (company_id, period)
    WHERE status = 'CONFIRMED';
CREATE INDEX IF NOT EXISTS idx_depr_run ON fa.depreciation_runs (company_id, period);

-- expense 费用报销模块，2 表
-- 3.1 expense.reimbursements 报销单（wf_instance_id 挂接审批实例，voucher_id 过账后回填）
CREATE TABLE IF NOT EXISTS expense.reimbursements (
    id             uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id     uuid          NOT NULL,
    applicant_id   uuid          NOT NULL REFERENCES um.users (id),
    dept_id        uuid          NOT NULL REFERENCES org.departments (id),
    title          TEXT          NOT NULL,
    amount         NUMERIC(18,2) NOT NULL CHECK (amount >= 0),
    currency       CHAR(3)       NOT NULL DEFAULT 'CNY',
    status         TEXT          NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED',
                                     'PAID','POSTED','REVERSED')),
    wf_instance_id uuid          REFERENCES wf.instances (id),  -- 审批实例
    voucher_id     uuid          REFERENCES gl.vouchers (id),   -- 过账凭证
    idem_key       varchar(64),                                  -- 创建幂等键（同公司+申请人+内容哈希，同 body 重复提交仅 1 条，uk_reimb_idem 兜底）
    created_by     uuid          REFERENCES um.users (id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);
COMMENT ON TABLE expense.reimbursements IS '费用报销单（审批流状态机由 trg_reimburse_status_check 强制）';

CREATE INDEX IF NOT EXISTS idx_reimb_applicant ON expense.reimbursements (applicant_id, status);
CREATE INDEX IF NOT EXISTS idx_reimb_dept      ON expense.reimbursements (dept_id);
CREATE INDEX IF NOT EXISTS idx_reimb_wf        ON expense.reimbursements (wf_instance_id);
-- 软删行不占键位，删除后同键位可重建，同公司同申请人同内容重复提交仅 1 条
CREATE UNIQUE INDEX IF NOT EXISTS uk_reimb_idem ON expense.reimbursements
    (company_id, applicant_id, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

-- 3.2 expense.reimbursement_lines 报销明细
CREATE TABLE IF NOT EXISTS expense.reimbursement_lines (
    id               uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id       uuid          NOT NULL,
    reimbursement_id uuid          NOT NULL REFERENCES expense.reimbursements (id),
    line_no          INT           NOT NULL,
    type             TEXT          NOT NULL,            -- 明细类型，餐费/差旅/办公等
    amount           NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    trx_date         DATE          NOT NULL,            -- 发生日期
    summary          TEXT          NOT NULL,
    invoice_file_id  TEXT,                              -- 发票附件关联
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);
COMMENT ON TABLE expense.reimbursement_lines IS '报销单明细（行级公司范围经父报销单；有效行内行号唯一——软删行不占键，更新可复用 line_no）';

CREATE INDEX IF NOT EXISTS idx_reimb_line_parent ON expense.reimbursement_lines (reimbursement_id);
-- 软删行不占键位，删除后同键位可重建，更新路径软删旧明细 + 全量重插
CREATE UNIQUE INDEX IF NOT EXISTS uk_reimb_line ON expense.reimbursement_lines
    (reimbursement_id, line_no) WHERE deleted_at IS NULL;

-- budget 预算模块，2 表
-- 4.1 budget.budget_plans 预算计划（period + account + dept + version 唯一，版本化调整）
CREATE TABLE IF NOT EXISTS budget.budget_plans (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id   uuid          NOT NULL,
    period       TEXT          NOT NULL,                -- 'YYYY-MM'
    account_id   uuid          NOT NULL REFERENCES gl.chart_accounts (id),
    dept_id      uuid          NOT NULL REFERENCES org.departments (id),
    amount       NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    version      INT           NOT NULL DEFAULT 0,      -- 版本号，乐观锁调整
    status       TEXT          NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','SUBMITTED','APPROVED')),
    approved_by  uuid          REFERENCES um.users (id),
    approved_at  TIMESTAMPTZ,
    created_by   uuid          REFERENCES um.users (id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);
COMMENT ON TABLE  budget.budget_plans IS '预算计划（期间×科目×部门×版本唯一，软删行不占键位；超支阻断取最新已提交/已批准版本）';
COMMENT ON COLUMN budget.budget_plans.version IS '版本号（预算调整以新版本叠加，乐观锁防并发覆盖）';

-- 软删行不占键位，删除后同键位可重建（uk_plan 部分唯一索引 WHERE deleted_at IS NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan
    ON budget.budget_plans (company_id, period, account_id, dept_id, version)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_plan_dept_period ON budget.budget_plans (dept_id, period);
CREATE INDEX IF NOT EXISTS idx_plan_acct_period ON budget.budget_plans (account_id, period);

-- 4.2 budget.budget_adjustments 预算调整单
CREATE TABLE IF NOT EXISTS budget.budget_adjustments (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    company_id  uuid          NOT NULL,
    plan_id     uuid          NOT NULL REFERENCES budget.budget_plans (id),
    amount      NUMERIC(18,2) NOT NULL CHECK (amount <> 0),   -- 正=追加 负=调减
    reason      TEXT          NOT NULL,
    status      TEXT          NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','APPROVED','REJECTED')),
    adjusted_by uuid          REFERENCES um.users (id),
    adjusted_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);
COMMENT ON TABLE budget.budget_adjustments IS '预算调整单（正负金额，审批后并入预算执行基数）';

CREATE INDEX IF NOT EXISTS idx_adj_plan ON budget.budget_adjustments (plan_id);

-- rls 行级安全（FORCE ROW LEVEL SECURITY + 统一策略函数族 auth.*），全部 SELECT 策略追加 deleted_at IS NULL，INSERT 仅公司范围 WITH CHECK，UPDATE 由状态机/状态前置限制，任何角色不授 DELETE 权限（无 DELETE 策略）
-- 5.1 wf
ALTER TABLE wf.definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE wf.definitions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wf_definitions_select ON wf.definitions;
CREATE POLICY wf_definitions_select ON wf.definitions FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL);
DROP POLICY IF EXISTS wf_definitions_insert ON wf.definitions;
CREATE POLICY wf_definitions_insert ON wf.definitions FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS wf_definitions_update ON wf.definitions;
CREATE POLICY wf_definitions_update ON wf.definitions FOR UPDATE
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id) AND deleted_at IS NULL);

ALTER TABLE wf.instances ENABLE ROW LEVEL SECURITY;
ALTER TABLE wf.instances FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wf_instances_select ON wf.instances;
CREATE POLICY wf_instances_select ON wf.instances FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL
           AND (auth.is_self(initiator_id) OR status IN ('APPROVED','REJECTED')));
DROP POLICY IF EXISTS wf_instances_insert ON wf.instances;
CREATE POLICY wf_instances_insert ON wf.instances FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS wf_instances_update ON wf.instances;
CREATE POLICY wf_instances_update ON wf.instances FOR UPDATE
    USING (auth.can_access_org(company_id) AND status = 'RUNNING' AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id)
                AND status IN ('RUNNING','APPROVED','REJECTED','CANCELED'));

ALTER TABLE wf.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE wf.tasks FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wf_tasks_select ON wf.tasks;
CREATE POLICY wf_tasks_select ON wf.tasks FOR SELECT
    USING ((auth.is_self(assignee_id) AND deleted_at IS NULL)
           OR auth.can_access_org(company_id));
DROP POLICY IF EXISTS wf_tasks_insert ON wf.tasks;
CREATE POLICY wf_tasks_insert ON wf.tasks FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS wf_tasks_update ON wf.tasks;
CREATE POLICY wf_tasks_update ON wf.tasks FOR UPDATE
    USING (auth.can_access_org(company_id) AND status IN ('PENDING','CLAIMED') AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id)
                AND status IN ('PENDING','CLAIMED','APPROVED','REJECTED',
                               'TRANSFERRED','TIMEOUT'));

ALTER TABLE wf.task_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE wf.task_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS wf_task_events_select ON wf.task_events;
CREATE POLICY wf_task_events_select ON wf.task_events FOR SELECT
    USING (auth.can_access_org(company_id));
DROP POLICY IF EXISTS wf_task_events_insert ON wf.task_events;
CREATE POLICY wf_task_events_insert ON wf.task_events FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));

-- 5.2 fa
ALTER TABLE fa.assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE fa.assets FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fa_assets_select ON fa.assets;
CREATE POLICY fa_assets_select ON fa.assets FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL);
DROP POLICY IF EXISTS fa_assets_insert ON fa.assets;
CREATE POLICY fa_assets_insert ON fa.assets FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS fa_assets_update ON fa.assets;
CREATE POLICY fa_assets_update ON fa.assets FOR UPDATE
    USING (auth.can_access_org(company_id) AND status NOT IN ('DISPOSED','SCRAPPED') AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id) AND status NOT IN ('DISPOSED','SCRAPPED'));

ALTER TABLE fa.depreciation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE fa.depreciation_runs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fa_depreciation_runs_select ON fa.depreciation_runs;
CREATE POLICY fa_depreciation_runs_select ON fa.depreciation_runs FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL);
DROP POLICY IF EXISTS fa_depreciation_runs_insert ON fa.depreciation_runs;
CREATE POLICY fa_depreciation_runs_insert ON fa.depreciation_runs FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS fa_depreciation_runs_update ON fa.depreciation_runs;
CREATE POLICY fa_depreciation_runs_update ON fa.depreciation_runs FOR UPDATE
    USING (status = 'DRAFT' AND deleted_at IS NULL)
    WITH CHECK (status IN ('DRAFT','CONFIRMED') AND deleted_at IS NULL);
    -- WITH CHECK 放行 DRAFT->CONFIRMED 迁移，CONFIRMED 行因 USING (status='DRAFT') 恒被拒，
    -- 确认后禁改禁删语义不变，防重复计提。
    -- 软删统一经 auth.fn_soft_delete（SECURITY DEFINER 绕过 rls），WITH CHECK 的 deleted_at IS NULL
    -- 不影响软删路径。

-- 5.3 expense
ALTER TABLE expense.reimbursements ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense.reimbursements FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS expense_reimbursements_select ON expense.reimbursements;
CREATE POLICY expense_reimbursements_select ON expense.reimbursements FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL
           AND (auth.is_self(applicant_id) OR auth.can_access_dept(dept_id)
                OR status IN ('APPROVED','PAID','POSTED')));
DROP POLICY IF EXISTS expense_reimbursements_insert ON expense.reimbursements;
CREATE POLICY expense_reimbursements_insert ON expense.reimbursements FOR INSERT
    WITH CHECK (auth.can_access_org(company_id) AND auth.is_self(applicant_id));
DROP POLICY IF EXISTS expense_reimbursements_update ON expense.reimbursements;
CREATE POLICY expense_reimbursements_update ON expense.reimbursements FOR UPDATE
    USING (auth.can_access_org(company_id)
           AND status IN ('DRAFT','SUBMITTED','APPROVED','PAID','REJECTED') AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id)
                AND status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED',
                               'PAID','POSTED','REVERSED')
                AND NOT (status IN ('APPROVED','PAID') AND auth.is_self(applicant_id)));

ALTER TABLE expense.reimbursement_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense.reimbursement_lines FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS expense_reimbursement_lines_select ON expense.reimbursement_lines;
CREATE POLICY expense_reimbursement_lines_select ON expense.reimbursement_lines FOR SELECT
    USING (auth.can_access_org((SELECT r.company_id FROM expense.reimbursements r
                                WHERE r.id = reimbursement_id AND r.deleted_at IS NULL))
           AND deleted_at IS NULL);
DROP POLICY IF EXISTS expense_reimbursement_lines_insert ON expense.reimbursement_lines;
CREATE POLICY expense_reimbursement_lines_insert ON expense.reimbursement_lines FOR INSERT
    WITH CHECK (auth.can_access_org((SELECT r.company_id FROM expense.reimbursements r
                                    WHERE r.id = reimbursement_id AND r.deleted_at IS NULL)));
DROP POLICY IF EXISTS expense_reimbursement_lines_update ON expense.reimbursement_lines;
CREATE POLICY expense_reimbursement_lines_update ON expense.reimbursement_lines FOR UPDATE
    USING (auth.can_access_org((SELECT r.company_id FROM expense.reimbursements r
                                WHERE r.id = reimbursement_id AND r.deleted_at IS NULL))
           AND deleted_at IS NULL);

-- 5.4 budget
ALTER TABLE budget.budget_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget.budget_plans FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS budget_budget_plans_select ON budget.budget_plans;
CREATE POLICY budget_budget_plans_select ON budget.budget_plans FOR SELECT
    USING (auth.can_access_org(company_id) AND deleted_at IS NULL
           AND (auth.can_access_dept(dept_id) OR auth.is_self(created_by)));
DROP POLICY IF EXISTS budget_budget_plans_insert ON budget.budget_plans;
CREATE POLICY budget_budget_plans_insert ON budget.budget_plans FOR INSERT
    WITH CHECK (auth.can_access_org(company_id));
DROP POLICY IF EXISTS budget_budget_plans_update ON budget.budget_plans;
CREATE POLICY budget_budget_plans_update ON budget.budget_plans FOR UPDATE
    USING (auth.can_access_org(company_id) AND status IN ('DRAFT','SUBMITTED') AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org(company_id)
                AND status IN ('DRAFT','SUBMITTED','APPROVED'));   -- 批准后只读

ALTER TABLE budget.budget_adjustments ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget.budget_adjustments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS budget_budget_adjustments_select ON budget.budget_adjustments;
CREATE POLICY budget_budget_adjustments_select ON budget.budget_adjustments FOR SELECT
    USING (auth.can_access_org((SELECT p.company_id FROM budget.budget_plans p
                                WHERE p.id = plan_id AND p.deleted_at IS NULL))
           AND deleted_at IS NULL);
DROP POLICY IF EXISTS budget_budget_adjustments_insert ON budget.budget_adjustments;
CREATE POLICY budget_budget_adjustments_insert ON budget.budget_adjustments FOR INSERT
    WITH CHECK (auth.can_access_org((SELECT p.company_id FROM budget.budget_plans p
                                    WHERE p.id = plan_id AND p.deleted_at IS NULL)));
DROP POLICY IF EXISTS budget_budget_adjustments_update ON budget.budget_adjustments;
CREATE POLICY budget_budget_adjustments_update ON budget.budget_adjustments FOR UPDATE
    USING (auth.can_access_org((SELECT p.company_id FROM budget.budget_plans p
                               WHERE p.id = plan_id AND p.deleted_at IS NULL))
           AND status = 'DRAFT' AND deleted_at IS NULL)
    WITH CHECK (auth.can_access_org((SELECT p.company_id FROM budget.budget_plans p
                                    WHERE p.id = plan_id AND p.deleted_at IS NULL))
                AND status IN ('DRAFT','APPROVED','REJECTED'));

-- 函数（全部 OR REPLACE 可重复执行，SECURITY DEFINER 属主 fms_admin）
-- 6.1 budget.fn_block_if_exceeded(p_company_id, p_period, p_account_id, p_dept_id, p_amount) 预算超支判定，锁定预算计划行（FOR UPDATE 防并发超支）后比较执行比例
-- 已执行金额优先读 rpt.budget_actual immv（rpt_log_base.sql 建，WITH (automatic) 提交即一致），若本文件先于 rpt_log_base.sql 执行，immv 不存在，运行期 to_regclass 探测并回退为 gl 表内联聚合（口径一致，POSTED/REVERSED 凭证 × 期间×科目×部门），开关/阈值读 um.params（budget.block.enabled / budget.block.ratio，缺省视为启用、阈值 100%）
-- 类型适配，入参 p_company_id/p_account_id/p_dept_id 转 uuid，dept_id 空值匹配用 IS NOT DISTINCT FROM（uuid 无 = 0 比较，COALESCE(...,0) 不可行）
CREATE OR REPLACE FUNCTION budget.fn_block_if_exceeded(
    p_company_id uuid,
    p_period     text,
    p_account_id uuid,
    p_dept_id    uuid,
    p_amount     numeric)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = budget, gl, um, pg_catalog
AS $$
DECLARE
    v_plan_id     uuid;
    v_plan_amount numeric;
    v_actual      numeric;
    v_enabled     boolean := true;
    v_ratio       numeric := 1.0;
    v_param       jsonb;
    v_immv_exists boolean;
BEGIN
    -- 开关参数，缺省，启用，阈值 100%
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'budget.block.enabled' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'boolean' THEN
                v_enabled := v_param::boolean;
            ELSE
                v_enabled := COALESCE(NULLIF(v_param->>'v',''), 'true')::boolean;
            END IF;
        END IF;
        SELECT value INTO v_param FROM um.params
         WHERE code = 'budget.block.ratio' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_ratio := v_param::numeric;
            ELSE
                v_ratio := COALESCE(NULLIF(v_param->>'v',''), '1')::numeric;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_enabled := true; v_ratio := 1.0;      -- 参数表异常时按最严默认放行与否保守处理
    END;

    IF NOT v_enabled THEN
        RETURN false;                            -- 开关关闭，不阻断
    END IF;
    IF p_amount IS NULL OR p_amount <= 0 THEN
        RETURN false;                            -- 贷方行/零金额行不触发
    END IF;

    -- 锁定预算计划行，防并发超支绕过，取该期间×科目×部门最新已提交/已批准版本
    SELECT id, amount INTO v_plan_id, v_plan_amount
      FROM budget.budget_plans
     WHERE company_id = p_company_id AND period = p_period
       AND account_id = p_account_id
       AND dept_id IS NOT DISTINCT FROM p_dept_id
       AND deleted_at IS NULL AND status IN ('SUBMITTED','APPROVED')
     ORDER BY (status = 'APPROVED') DESC, version DESC
     LIMIT 1
       FOR UPDATE;
    IF v_plan_id IS NULL THEN
        RETURN false;                            -- 未配置预算计划，不阻断
    END IF;
    IF v_plan_amount <= 0 THEN
        RETURN true;                             -- 预算额为 0 视为不可支出
    END IF;

    -- 已执行金额，优先 rpt.budget_actual immv（rpt_log_base.sql 建），否则回退 gl 内联聚合
    v_immv_exists := (to_regclass('rpt.budget_actual') IS NOT NULL);
    IF v_immv_exists THEN
        SELECT COALESCE(actual_amount, 0) INTO v_actual
          FROM rpt.budget_actual
         WHERE company_id = p_company_id AND period = p_period
           AND account_id = p_account_id
           AND dept_id IS NOT DISTINCT FROM p_dept_id;
    ELSE
        SELECT COALESCE(SUM(vl.debit - vl.credit), 0) INTO v_actual
          FROM gl.voucher_lines vl
          JOIN gl.vouchers v ON v.id = vl.voucher_id AND v.deleted_at IS NULL
         WHERE vl.account_id = p_account_id
           AND vl.dept_id IS NOT DISTINCT FROM p_dept_id
           AND v.company_id = p_company_id AND v.period = p_period
           AND v.status IN ('POSTED','REVERSED')
           AND vl.deleted_at IS NULL;
    END IF;

    -- 执行比例，含本次新增金额>= 阈值则阻断
    IF (COALESCE(v_actual,0) + p_amount) / v_plan_amount >= v_ratio THEN
        RETURN true;
    END IF;
    RETURN false;
END
$$;
COMMENT ON FUNCTION budget.fn_block_if_exceeded(uuid,text,uuid,uuid,numeric)
    IS '预算超支判定（锁定预算行 + IMMV/内联聚合执行比例，>=阈值返回 true 由调用方 RAISE）';

-- 6.2 budget.fn_voucher_budget_block 触发器函数，挂 gl.voucher_lines BEFORE INSERT/UPDATE，凭证期间×科目×部门执行比例 ≥100%，或配置阈值，时 RAISE 阻断，本文件执行在 gl_base.sql 之后，以 DROP+CREATE 接管 trg_voucher_budget_check，若 gl_base.sql 已挂同名触发器
CREATE OR REPLACE FUNCTION budget.fn_voucher_budget_block()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = budget, gl, pg_catalog
AS $$
DECLARE
    v_company_id uuid;
    v_period     text;
    v_amount     numeric;
BEGIN
    SELECT company_id, period INTO v_company_id, v_period
      FROM gl.vouchers
     WHERE id = NEW.voucher_id AND deleted_at IS NULL;
    IF v_company_id IS NULL THEN
        RETURN NEW;                              -- 凭证不存在/已软删，交给其他约束
    END IF;
    v_amount := COALESCE(NEW.debit, 0) - COALESCE(NEW.credit, 0);
    IF budget.fn_block_if_exceeded(v_company_id, v_period, NEW.account_id,
                                   NEW.dept_id, v_amount) THEN
        RAISE EXCEPTION '预算超支阻断：期间 %，科目 id=%，部门 id=%，执行比例已达配置阈值（默认 100%%），请先提交预算调整（budget.budget_adjustments）或更换预算科目',
            v_period, NEW.account_id, NEW.dept_id
            USING ERRCODE = 'P0001',
                  HINT = '预算计划：budget.budget_plans；阈值参数：um.params.budget.block.ratio';
    END IF;
    RETURN NEW;
END
$$;
COMMENT ON FUNCTION budget.fn_voucher_budget_block()
    IS '凭证分录预算超支阻断触发器函数（BEFORE INSERT/UPDATE ON gl.voucher_lines）';

-- 6.3 wf.fn_claim_task(p_task_id, p_assignee_id) 任务领取，并发安全，UPDATE + CTE 内 FOR UPDATE SKIP LOCKED，并发仅一人领取成功，其余返回 false，成功写入 CLAIM 事件
CREATE OR REPLACE FUNCTION wf.fn_claim_task(p_task_id uuid, p_assignee_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, auth, pg_catalog
AS $$
DECLARE
    v_id           uuid;
    v_company_id   uuid;
    v_instance_id  uuid;
    v_node         text;
BEGIN
    IF p_assignee_id IS NULL THEN
        RETURN false;
    END IF;
    WITH locked AS (
        SELECT id FROM wf.tasks
         WHERE id = p_task_id
           AND status = 'PENDING'
           AND assignee_id = p_assignee_id     -- 只能领取分配给本人的任务
           AND deleted_at IS NULL
           FOR UPDATE SKIP LOCKED             -- 并发领取仅一人拿到行锁
    )
    UPDATE wf.tasks t
       SET status = 'CLAIMED',
           claim_at = now(),
           version = version + 1,
           updated_at = now()
      FROM locked l
     WHERE t.id = l.id
     RETURNING t.id, t.company_id, t.instance_id, t.node_code
       INTO v_id, v_company_id, v_instance_id, v_node;
    IF v_id IS NULL THEN
        RETURN false;                          -- 不存在/非本人/已被领取
    END IF;
    INSERT INTO wf.task_events (company_id, task_id, action, operator_id,
                                from_node, to_node, remark)
    VALUES (v_company_id, v_id, 'CLAIM', p_assignee_id, v_node, v_node, '任务领取');
    RETURN true;
END
$$;
COMMENT ON FUNCTION wf.fn_claim_task(uuid,uuid)
    IS '领取任务（FOR UPDATE SKIP LOCKED 并发安全；仅领取分配给 p_assignee_id 的 PENDING 任务）';

-- wf.claim_task(p_task_id)， 以当前会话用户（auth.current_uid）领取的便捷入口
CREATE OR REPLACE FUNCTION wf.claim_task(p_task_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, auth, pg_catalog
AS $$
DECLARE
    v_uid uuid;
BEGIN
    v_uid := auth.current_uid();
    RETURN wf.fn_claim_task(p_task_id, v_uid);
END
$$;

-- 6.4 节点级推进引擎（多处理人/会签/或签），由 fn_start_instance 与 fn_complete_task 调用
-- 实例推进由 fn_node_done 与 fn_advance_instance 承担
DROP FUNCTION IF EXISTS wf.fn_auto_activate_next(uuid);
-- wf.fn_activate_node(p_instance_id, p_node) 依节点 strategy 与 handlers 展开处理人并生成任务集。
-- APPROVE 含 strategy（approve/countersign/orsign）与 handlers 数组
-- （source 为 applicant/role/user 可混合），缺失 handlers 视为空集。单审 approve 生成
-- 1 条任务，角色源取该公司下最早授权的 ACTIVE 成员，集合空按公司首个 ACTIVE 用户再
-- 发起人兜底；会签 countersign 与或签 orsign 展开全部处理人各 1 条任务，applicant 即
-- 发起人，角色成员按 company_id 过滤且 ACTIVE，同一用户重复命中只派一票，
-- 集合为空则 RAISE P0001。任务到期时限读 um.params wf.task.due.days，缺省 3 天。
CREATE OR REPLACE FUNCTION wf.fn_activate_node(p_instance_id uuid, p_node jsonb)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, um, auth, pg_catalog
AS $$
DECLARE
    v_inst       wf.instances%ROWTYPE;
    v_strategy   text;
    v_handlers   jsonb;
    v_item       jsonb;
    v_assignee   uuid;
    v_assignees  uuid[] := '{}';
    v_uids       uuid[];
    v_member     uuid;
    v_due_days   int := 3;
    v_param      jsonb;
    v_cnt        int := 0;
BEGIN
    SELECT * INTO v_inst FROM wf.instances
     WHERE id = p_instance_id AND deleted_at IS NULL;
    IF v_inst.id IS NULL OR v_inst.status <> 'RUNNING' THEN
        RETURN 0;
    END IF;
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'wf.task.due.days' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_due_days := v_param::int;
            ELSE
                v_due_days := COALESCE(NULLIF(v_param->>'v',''), '3')::int;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_due_days := 3;
    END;

    v_strategy := COALESCE(NULLIF(p_node->>'strategy',''), 'approve');
    IF v_strategy NOT IN ('approve','countersign','orsign') THEN
        v_strategy := 'approve';
    END IF;
    IF jsonb_typeof(p_node->'handlers') = 'array' THEN
        v_handlers := p_node->'handlers';
    ELSE
        v_handlers := '[]'::jsonb;
    END IF;

    IF v_strategy = 'approve' THEN
        FOR v_item IN
            SELECT value FROM jsonb_array_elements(v_handlers) WITH ORDINALITY AS h(value, ord)
            ORDER BY ord
        LOOP
            IF v_item->>'source' = 'applicant' THEN
                v_assignee := v_inst.initiator_id;
                EXIT;
            ELSIF v_item->>'source' = 'user' THEN
                IF EXISTS (SELECT 1 FROM um.users u
                            WHERE u.id = (v_item->>'userId')::uuid
                              AND u.company_id = v_inst.company_id
                              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL) THEN
                    v_assignee := (v_item->>'userId')::uuid;
                    EXIT;
                END IF;
            ELSIF v_item->>'source' = 'role' THEN
                SELECT ur.user_id INTO v_assignee
                  FROM um.user_role ur
                  JOIN um.roles r ON r.id = ur.role_id
                  JOIN um.users u ON u.id = ur.user_id
                 WHERE lower(r.code) = lower(v_item->>'code')
                   AND u.company_id = v_inst.company_id
                   AND u.status = 'ACTIVE'
                   AND r.deleted_at IS NULL AND ur.deleted_at IS NULL AND u.deleted_at IS NULL
                 ORDER BY ur.created_at
                 LIMIT 1;
                IF v_assignee IS NOT NULL THEN
                    EXIT;
                END IF;
            END IF;
        END LOOP;
        IF v_assignee IS NULL THEN
            SELECT id INTO v_assignee FROM um.users
             WHERE company_id = v_inst.company_id
               AND status = 'ACTIVE' AND deleted_at IS NULL
             ORDER BY created_at
             LIMIT 1;
        END IF;
        IF v_assignee IS NULL THEN
            v_assignee := v_inst.initiator_id;
        END IF;
        INSERT INTO wf.tasks (company_id, instance_id, node_code, assignee_id, task_type,
                              status, due_at, version)
        VALUES (v_inst.company_id, p_instance_id, p_node->>'node_code', v_assignee,
                'APPROVE', 'PENDING', now() + make_interval(days => v_due_days), 0);
        RETURN 1;
    END IF;

    FOR v_item IN
        SELECT value FROM jsonb_array_elements(v_handlers) WITH ORDINALITY AS h(value, ord)
        ORDER BY ord
    LOOP
        IF v_item->>'source' = 'applicant' THEN
            IF array_position(v_assignees, v_inst.initiator_id) IS NULL THEN
                v_assignees := array_append(v_assignees, v_inst.initiator_id);
            END IF;
        ELSIF v_item->>'source' = 'user' THEN
            IF EXISTS (SELECT 1 FROM um.users u
                        WHERE u.id = (v_item->>'userId')::uuid
                          AND u.company_id = v_inst.company_id
                          AND u.status = 'ACTIVE' AND u.deleted_at IS NULL)
               AND array_position(v_assignees, (v_item->>'userId')::uuid) IS NULL THEN
                v_assignees := array_append(v_assignees, (v_item->>'userId')::uuid);
            END IF;
        ELSIF v_item->>'source' = 'role' THEN
            SELECT array_agg(ur.user_id) INTO v_uids
              FROM um.user_role ur
              JOIN um.roles r ON r.id = ur.role_id
              JOIN um.users u ON u.id = ur.user_id
             WHERE lower(r.code) = lower(v_item->>'code')
               AND u.company_id = v_inst.company_id
               AND u.status = 'ACTIVE'
               AND r.deleted_at IS NULL AND ur.deleted_at IS NULL AND u.deleted_at IS NULL;
            IF v_uids IS NOT NULL THEN
                FOREACH v_member IN ARRAY v_uids LOOP
                    IF array_position(v_assignees, v_member) IS NULL THEN
                        v_assignees := array_append(v_assignees, v_member);
                    END IF;
                END LOOP;
            END IF;
        END IF;
    END LOOP;
    IF array_length(v_assignees, 1) IS NULL THEN
        RAISE EXCEPTION '流程节点 % 无可用处理人', p_node->>'node_code'
            USING ERRCODE = 'P0001';
    END IF;
    FOREACH v_assignee IN ARRAY v_assignees LOOP
        INSERT INTO wf.tasks (company_id, instance_id, node_code, assignee_id, task_type,
                              status, due_at, version)
        VALUES (v_inst.company_id, p_instance_id, p_node->>'node_code', v_assignee,
                CASE v_strategy WHEN 'countersign' THEN 'COUNTERSIGN' ELSE 'OR_SIGN' END,
                'PENDING', now() + make_interval(days => v_due_days), 0);
        v_cnt := v_cnt + 1;
    END LOOP;
    RETURN v_cnt;
END
$$;

-- wf.fn_node_done(p_instance_id) 判定实例当前节点任务集是否满足完成条件，
-- 会签 countersign 无否决无未决且全部现役票通过（TIMEOUT/TRANSFERRED 视为弃权不计分母，
-- 至少 1 票同意，全弃权不自动过）；单审 approve 与或签 orsign 任一票通过即过，
-- 或签通过时软删其余 PENDING/CLAIMED 候选票
CREATE OR REPLACE FUNCTION wf.fn_node_done(p_instance_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, pg_catalog
AS $$
DECLARE
    v_inst      wf.instances%ROWTYPE;
    v_def       wf.definitions%ROWTYPE;
    v_node      jsonb;
    v_strategy  text;
    v_a int; v_p int; v_r int; v_live int;
BEGIN
    SELECT * INTO v_inst FROM wf.instances
     WHERE id = p_instance_id AND deleted_at IS NULL;
    IF v_inst.id IS NULL OR v_inst.status <> 'RUNNING' OR v_inst.current_node IS NULL THEN
        RETURN false;
    END IF;
    SELECT * INTO v_def FROM wf.definitions
     WHERE id = v_inst.definition_id AND deleted_at IS NULL;
    IF v_def.id IS NULL THEN
        RETURN false;
    END IF;
    SELECT value INTO v_node
      FROM jsonb_array_elements(v_def.node_json) AS e(value)
     WHERE value->>'node_code' = v_inst.current_node
     LIMIT 1;
    IF v_node IS NULL THEN
        RETURN false;
    END IF;
    v_strategy := COALESCE(NULLIF(v_node->>'strategy',''), 'approve');

    SELECT count(*) FILTER (WHERE status = 'APPROVED'),
           count(*) FILTER (WHERE status IN ('PENDING','CLAIMED')),
           count(*) FILTER (WHERE status = 'REJECTED'),
           count(*) FILTER (WHERE status IN ('APPROVED','PENDING','CLAIMED'))
      INTO v_a, v_p, v_r, v_live
      FROM wf.tasks
     WHERE instance_id = p_instance_id
       AND node_code = v_inst.current_node
       AND deleted_at IS NULL;

    IF v_strategy = 'countersign' THEN
        RETURN v_r = 0 AND v_p = 0 AND v_a >= 1 AND v_a = v_live;
    END IF;
    IF v_a >= 1 THEN
        IF v_strategy = 'orsign' THEN
            UPDATE wf.tasks
               SET deleted_at = now(), updated_at = now()
             WHERE instance_id = p_instance_id
               AND node_code = v_inst.current_node
               AND status IN ('PENDING','CLAIMED')
               AND deleted_at IS NULL;
        END IF;
        RETURN true;
    END IF;
    RETURN false;
END
$$;

-- wf.fn_advance_instance(p_instance_id) 当前节点通过后沿 node_json 找下一个 APPROVE 节点
-- 激活（更新 current_node 并生成该节点任务集），向后已无 APPROVE 则实例置 APPROVED
CREATE OR REPLACE FUNCTION wf.fn_advance_instance(p_instance_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, pg_catalog
AS $$
DECLARE
    v_inst     wf.instances%ROWTYPE;
    v_def      wf.definitions%ROWTYPE;
    v_elem     jsonb;
    v_next     jsonb;
    v_found    boolean := false;
BEGIN
    SELECT * INTO v_inst FROM wf.instances
     WHERE id = p_instance_id AND deleted_at IS NULL;
    IF v_inst.id IS NULL OR v_inst.status <> 'RUNNING' THEN
        RETURN;
    END IF;
    SELECT * INTO v_def FROM wf.definitions
     WHERE id = v_inst.definition_id AND deleted_at IS NULL;
    IF v_def.id IS NULL THEN
        RETURN;
    END IF;
    FOR v_elem IN
        SELECT value FROM jsonb_array_elements(v_def.node_json) WITH ORDINALITY AS e(value, ord)
        ORDER BY ord
    LOOP
        IF v_elem->>'type' <> 'APPROVE' THEN
            CONTINUE;
        END IF;
        IF v_found THEN
            v_next := v_elem;
            EXIT;
        END IF;
        IF v_elem->>'node_code' = v_inst.current_node THEN
            v_found := true;
        END IF;
    END LOOP;
    IF v_next IS NULL THEN
        UPDATE wf.instances
           SET status = 'APPROVED', updated_at = now()
         WHERE id = p_instance_id AND status = 'RUNNING' AND deleted_at IS NULL;
        RETURN;
    END IF;
    UPDATE wf.instances
       SET current_node = v_next->>'node_code', updated_at = now()
     WHERE id = p_instance_id AND status = 'RUNNING' AND deleted_at IS NULL;
    PERFORM wf.fn_activate_node(p_instance_id, v_next);
END
$$;

-- 6.5 wf.fn_complete_task(p_task_id, p_assignee_id, p_action, p_remark) 任务办结，推进状态机，
-- APPROVE 则任务置 APPROVED 后经 fn_node_done 判定当前节点完成条件，会签/或签多票按策略判定，
-- 通过则 fn_advance_instance 沿 node_json 激活下一节点直至 END；REJECT 则任务置 REJECTED 且实例
-- 直接置 REJECTED，同节点其余 PENDING/CLAIMED 票一并软删；TRANSFER 则任务置 TRANSFERRED，
-- 若 p_remark 为纯 uuid 即新处理人 id，则同节点新建 PENDING 任务完成转办，否则仅记事件
-- 幂等，任务已办结（非 PENDING/CLAIMED）返回 false，非本人任务 RAISE，办结后向 pgmq q_notify_task 投递通知，队列由 queues_cron.sql 预创建，失败静默
CREATE OR REPLACE FUNCTION wf.fn_complete_task(
    p_task_id    uuid,
    p_assignee_id uuid,
    p_action     text,
    p_remark     text DEFAULT NULL)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, auth, pg_catalog
AS $$
DECLARE
    v_task         wf.tasks%ROWTYPE;
    v_new_assignee uuid;
    v_due_days     int := 3;
    v_param        jsonb;
BEGIN
    -- 转办新建任务到期时限（um.params wf.task.due.days 可配，缺省 3 天，读取语义同 budget 参数）
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'wf.task.due.days' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_due_days := v_param::int;
            ELSE
                v_due_days := COALESCE(NULLIF(v_param->>'v',''), '3')::int;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_due_days := 3;                         -- 参数表异常，回落现状缺省
    END;
    IF p_action NOT IN ('APPROVE','REJECT','TRANSFER') THEN
        RAISE EXCEPTION '非法办结动作：%（仅 APPROVE/REJECT/TRANSFER）', p_action
            USING ERRCODE = 'P0001';
    END IF;
    -- 权限闸口，审批/拒绝须持对应权限点（维护会话 postgres/cron 与 fms_admin 经 require_permission 直通）
    IF p_action IN ('APPROVE','TRANSFER') THEN
        PERFORM auth.require_permission('wf.instance.approve');
    ELSIF p_action = 'REJECT' THEN
        PERFORM auth.require_permission('wf.instance.reject');
    END IF;

    SELECT * INTO v_task FROM wf.tasks
     WHERE id = p_task_id AND deleted_at IS NULL
       FOR UPDATE;
    IF v_task.id IS NULL THEN
        RETURN false;                            -- 任务不存在
    END IF;
    IF v_task.status NOT IN ('PENDING','CLAIMED') THEN
        RETURN false;                            -- 已办结，幂等返回
    END IF;
    IF v_task.assignee_id <> p_assignee_id THEN
        RAISE EXCEPTION '任务 % 不属于当前用户（%），无权办结', p_task_id, p_assignee_id
            USING ERRCODE = '42501';
    END IF;

    --任务置终态

    UPDATE wf.tasks
       SET status = CASE p_action
                        WHEN 'APPROVE'  THEN 'APPROVED'
                        WHEN 'REJECT'   THEN 'REJECTED'
                        ELSE 'TRANSFERRED'
                    END,
           version = version + 1,
           updated_at = now()
     WHERE id = p_task_id;

    --事件留痕

    INSERT INTO wf.task_events (company_id, task_id, action, operator_id,
                                from_node, to_node, remark)
    VALUES (v_task.company_id, p_task_id, p_action, p_assignee_id,
            v_task.node_code, v_task.node_code, COALESCE(p_remark,''));

    --实例状态机推进

    IF p_action = 'REJECT' THEN
        UPDATE wf.instances
           SET status = 'REJECTED', current_node = v_task.node_code,
               updated_at = now()
         WHERE id = v_task.instance_id AND status = 'RUNNING' AND deleted_at IS NULL;
        -- 驳回即整实例终止，软删同节点其余待办票，避免他人仍可在待办中处理
        UPDATE wf.tasks
           SET deleted_at = now(), updated_at = now()
         WHERE instance_id = v_task.instance_id
           AND node_code = v_task.node_code
           AND status IN ('PENDING','CLAIMED')
           AND id <> p_task_id
           AND deleted_at IS NULL;
    ELSIF p_action = 'TRANSFER' THEN
        BEGIN
            v_new_assignee := NULLIF(COALESCE(p_remark,''),'')::uuid;
        EXCEPTION WHEN OTHERS THEN
            v_new_assignee := NULL;              -- remark 非 uuid 文本，仅记事件不转办
        END;
        IF v_new_assignee IS NOT NULL THEN
            INSERT INTO wf.tasks (company_id, instance_id, node_code, assignee_id,
                                  task_type, status, due_at, version, remark)
            VALUES (v_task.company_id, v_task.instance_id, v_task.node_code,
                    v_new_assignee, v_task.task_type, 'PENDING',
                    now() + make_interval(days => v_due_days), 0, '转办自任务 ' || p_task_id);
        END IF;
    ELSE
        IF wf.fn_node_done(v_task.instance_id) THEN
            PERFORM wf.fn_advance_instance(v_task.instance_id);
        END IF;
    END IF;

    --pgmq 通知（queues_cron.sql 预创建 q_notify_task，pgmq 不可用时静默降级）

    BEGIN
        PERFORM pgmq.send('q_notify_task',
            jsonb_build_object('type','wf_task','instance_id',v_task.instance_id,
                               'task_id',p_task_id,'assignee_id',p_assignee_id,
                               'action',p_action,'ts',now()), 0);
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'PGMQ 通知发送失败（忽略）：%', SQLERRM;
    END;

    RETURN true;
END
$$;
COMMENT ON FUNCTION wf.fn_complete_task(uuid,uuid,text,text)
    IS '任务办结并推进实例状态机（APPROVE/REJECT/TRANSFER），幂等，办结事件入 task_events';

-- wf.complete_task(p_task_id, p_action, p_remark)， 以当前会话用户办结的便捷入口
CREATE OR REPLACE FUNCTION wf.complete_task(p_task_id uuid, p_action text,
                                            p_remark text DEFAULT NULL)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, auth, pg_catalog
AS $$
DECLARE
    v_uid uuid;
BEGIN
    v_uid := auth.current_uid();
    RETURN wf.fn_complete_task(p_task_id, v_uid, p_action, p_remark);
END
$$;

-- 6.6 fa.fn_run_depreciation(p_company_id, p_period) 折旧计提，直线法 monthly=，原值-残值/月数，双倍余额递减 monthly=净值*2/月数，单资产计提额封顶，不超过 原值-残值-已计提
-- 产物，fa.depreciation_runs（DRAFT 批次，重复执行先清旧 DRAFT）+ 折旧凭证草稿（科目按 code='1601'/'6602' 查询，科目不存在或凭证结构不兼容时跳过凭证生成仅记录批次，容错设计），供 queues_cron.sql 注册的 pg_cron 任务 fa-depreciation 调用（p_company_id NULL = 全部公司）
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
BEGIN
    -- 权限闸口，折旧计提为 SECURITY DEFINER 且无鉴权时任意会话可代跑，require_permission 在维护会话（postgres/cron）与 fms_admin 下经直通放行
    PERFORM auth.require_permission('fa.depreciation.create');
    IF to_regclass('gl.vouchers') IS NULL OR to_regclass('gl.voucher_lines') IS NULL THEN
        RAISE NOTICE 'gl 表不存在，跳过折旧计提';
        RETURN 0;
    END IF;
    v_period_end := (to_date(v_period || '-01','YYYY-MM-DD') + interval '1 month'
                     - interval '1 day')::date;

    -- 折旧凭证科目映射参数（um.params fa.depr.expense.account / fa.depr.asset.account 可配，
    -- 缺省 6602 管理费用 / 1601 固定资产，科目不存在则跳过凭证生成的兜底逻辑保留不变）
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
            END IF;
        END LOOP;

        -- 批次记录（DRAFT 先落库，CONFIRM 由业务确认，同公司同期间仅一条 CONFIRMED 由 uk_depr_run 保证）
        INSERT INTO fa.depreciation_runs (company_id, period, run_by, status, lines)
        VALUES (v_comp, v_period, auth.current_uid(), 'DRAFT', v_lines);

        -- 折旧凭证草稿（科目缺失/结构不兼容时容错跳过，科目 code 读 um.params fa.depr.*.account）
        IF v_total > 0 THEN
            SELECT id INTO v_dep_account FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_dep_code AND deleted_at IS NULL;
            SELECT id INTO v_fa_account FROM gl.chart_accounts
             WHERE company_id = v_comp AND code = v_fa_code AND deleted_at IS NULL;
            IF v_dep_account IS NOT NULL AND v_fa_account IS NOT NULL THEN
                BEGIN
                    INSERT INTO gl.vouchers (company_id, voucher_no, period,
                                             voucher_date, status, source_type,
                                             total_debit, total_credit, created_by)
                    VALUES (v_comp, NULL, v_period, v_period_end, 'DRAFT',
                            'DEPRECIATION', v_total, v_total, NULL)
                    RETURNING id INTO v_voucher_id;
                    INSERT INTO gl.voucher_lines (voucher_id, line_no, account_id,
                                                  summary, debit, credit)
                    VALUES (v_voucher_id, 1, v_dep_account, '折旧费用 ' || v_period, v_total, 0),
                           (v_voucher_id, 2, v_fa_account,  '累计折旧 ' || v_period, 0, v_total);
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
    IS '折旧计提：直线/双倍余额 -> depreciation_runs 批次 + 折旧凭证草稿（防重复：CONFIRMED 批次存在则跳过）';

-- 6.7 fa.fn_asset_immutable 资产不可变触发器函数，处置/报废（DISPOSED/SCRAPPED）后禁止 UPDATE/DELETE，对全员生效，含 superuser
CREATE OR REPLACE FUNCTION fa.fn_asset_immutable()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = fa, pg_catalog
AS $$
BEGIN
    IF OLD.status IN ('DISPOSED','SCRAPPED') THEN
        RAISE EXCEPTION '资产已处置/报废（status=%），禁止修改或删除', OLD.status
            USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

-- 6.8 expense.fn_reimburse_status_check 报销单状态机触发器函数，仅允许正向流转 DRAFT 提交 SUBMITTED、审核 APPROVED、付款 PAID、过账 POSTED，REJECTED 仅从 SUBMITTED 进入（退回后可再提交 DRAFT），POSTED/REVERSED 后禁改，含非状态字段
CREATE OR REPLACE FUNCTION expense.fn_reimburse_status_check()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = expense, pg_catalog
AS $$
BEGIN
    IF OLD.status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION '报销单已 %，禁止修改', OLD.status
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;                              -- 非状态字段修改放行，状态机之外
    END IF;
    IF NOT (
            (OLD.status = 'DRAFT'     AND NEW.status = 'SUBMITTED')
         OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED','DRAFT'))
         OR (OLD.status = 'APPROVED'  AND NEW.status = 'PAID')
         OR (OLD.status = 'PAID'      AND NEW.status = 'POSTED')
         OR (OLD.status = 'REJECTED'  AND NEW.status = 'DRAFT')
    ) THEN
        RAISE EXCEPTION '非法的报销状态流转：% -> %', OLD.status, NEW.status
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END
$$;

-- 6.9 wf.fn_fill_company 公司范围补全触发器函数（wf.tasks / wf.task_events），BEFORE INSERT 时 NEW.company_id 未提供则按父对象，实例/任务补全，保证 rls insert WITH CHECK 与 NOT NULL 均成立
CREATE OR REPLACE FUNCTION wf.fn_fill_company()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, pg_catalog
AS $$
BEGIN
    IF TG_TABLE_NAME = 'tasks' THEN
        IF NEW.company_id IS NULL THEN
            SELECT company_id INTO NEW.company_id
              FROM wf.instances WHERE id = NEW.instance_id AND deleted_at IS NULL;
        END IF;
    ELSIF TG_TABLE_NAME = 'task_events' THEN
        IF NEW.company_id IS NULL THEN
            SELECT t.company_id INTO NEW.company_id
              FROM wf.tasks t WHERE t.id = NEW.task_id AND t.deleted_at IS NULL;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- 触发器（全部 DROP IF EXISTS 前置 + CREATE，可重复执行），通用 updated_at 触发器引用 gl_base.sql 的 gl.fn_set_updated_at，审计触发器条件挂载，audit.fn_audit_log 由 audit_base.sql 创建，正常执行顺序恒先于本文件存在，本文件是 wf/fa/expense/budget 四模块审计触发器的挂载点，to_regprocedure 探测仅防御乱序执行，缺失时跳过并留 NOTICE，重跑本文件补齐
-- 7.1 专用触发器
-- 7.1.1 fa.assets 不可变，处置/报废后禁改禁删
DROP TRIGGER IF EXISTS trg_asset_immutable ON fa.assets;
CREATE TRIGGER trg_asset_immutable
    BEFORE UPDATE OR DELETE ON fa.assets
    FOR EACH ROW EXECUTE FUNCTION fa.fn_asset_immutable();

-- 7.1.2 expense.reimbursements 状态机（正向推进 + POSTED 后禁改）
DROP TRIGGER IF EXISTS trg_reimburse_status_check ON expense.reimbursements;
CREATE TRIGGER trg_reimburse_status_check
    BEFORE UPDATE ON expense.reimbursements
    FOR EACH ROW EXECUTE FUNCTION expense.fn_reimburse_status_check();

-- 7.1.3 wf.tasks / wf.task_events 公司范围补全（BEFORE INSERT，rls 依赖 company_id）
DROP TRIGGER IF EXISTS trg_wf_task_fill_company ON wf.tasks;
CREATE TRIGGER trg_wf_task_fill_company
    BEFORE INSERT ON wf.tasks
    FOR EACH ROW EXECUTE FUNCTION wf.fn_fill_company();

DROP TRIGGER IF EXISTS trg_wf_task_event_fill_company ON wf.task_events;
CREATE TRIGGER trg_wf_task_event_fill_company
    BEFORE INSERT ON wf.task_events
    FOR EACH ROW EXECUTE FUNCTION wf.fn_fill_company();

-- 7.1.4 预算超支阻断（BEFORE INSERT/UPDATE ON gl.voucher_lines）
DROP TRIGGER IF EXISTS trg_voucher_budget_check ON gl.voucher_lines;
CREATE TRIGGER trg_voucher_budget_check
    BEFORE INSERT OR UPDATE ON gl.voucher_lines
    FOR EACH ROW EXECUTE FUNCTION budget.fn_voucher_budget_block();

-- 7.2 通用 updated_at 触发器，10 张表
DROP TRIGGER IF EXISTS trg_wf_definitions_updated ON wf.definitions;
CREATE TRIGGER trg_wf_definitions_updated
    BEFORE UPDATE ON wf.definitions
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_wf_instances_updated ON wf.instances;
CREATE TRIGGER trg_wf_instances_updated
    BEFORE UPDATE ON wf.instances
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_wf_tasks_updated ON wf.tasks;
CREATE TRIGGER trg_wf_tasks_updated
    BEFORE UPDATE ON wf.tasks
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_wf_task_events_updated ON wf.task_events;
CREATE TRIGGER trg_wf_task_events_updated
    BEFORE UPDATE ON wf.task_events
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_fa_assets_updated ON fa.assets;
CREATE TRIGGER trg_fa_assets_updated
    BEFORE UPDATE ON fa.assets
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_fa_depreciation_runs_updated ON fa.depreciation_runs;
CREATE TRIGGER trg_fa_depreciation_runs_updated
    BEFORE UPDATE ON fa.depreciation_runs
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_expense_reimbursements_updated ON expense.reimbursements;
CREATE TRIGGER trg_expense_reimbursements_updated
    BEFORE UPDATE ON expense.reimbursements
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_expense_reimbursement_lines_updated ON expense.reimbursement_lines;
CREATE TRIGGER trg_expense_reimbursement_lines_updated
    BEFORE UPDATE ON expense.reimbursement_lines
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_budget_budget_plans_updated ON budget.budget_plans;
CREATE TRIGGER trg_budget_budget_plans_updated
    BEFORE UPDATE ON budget.budget_plans
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

DROP TRIGGER IF EXISTS trg_budget_budget_adjustments_updated ON budget.budget_adjustments;
CREATE TRIGGER trg_budget_budget_adjustments_updated
    BEFORE UPDATE ON budget.budget_adjustments
    FOR EACH ROW EXECUTE FUNCTION gl.fn_set_updated_at();

-- 通用审计触发器，条件挂载
DO $$
DECLARE
    t text;
BEGIN
    IF to_regprocedure('audit.fn_audit_log()') IS NOT NULL THEN
        FOREACH t IN ARRAY ARRAY[
            'wf.definitions','wf.instances','wf.tasks','wf.task_events',
            'fa.assets','fa.depreciation_runs',
            'expense.reimbursements','expense.reimbursement_lines',
            'budget.budget_plans','budget.budget_adjustments']
        LOOP
            EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_audit ON %s',
                           split_part(t,'.',2), t);
            EXECUTE format('CREATE TRIGGER trg_%s_audit AFTER INSERT OR UPDATE OR DELETE ON %s '
                           || 'FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log()',
                           split_part(t,'.',2), t);
        END LOOP;
        RAISE NOTICE 'fa_expense_budget_wf_base: 审计触发器已挂载到本文件 10 张表（audit.fn_audit_log 已存在）';
    ELSE
        RAISE NOTICE 'fa_expense_budget_wf_base: audit.fn_audit_log 尚不存在（init/queues_cron.sql 创建），审计触发器由 init/queues_cron.sql 挂载';
    END IF;
END $$;

-- cron 任务函数补充（queues_cron.sql pg_cron 调度段命令惰性解析，运行时需要）
-- 8.1 budget.fn_cron_budget_warning（budget-warning 任务，每 15 分钟），扫描执行比例 ≥80%（um.params budget.warn.ratio 可配）的已批准预算计划则 pgmq.send('q_budget_warning') 预警
CREATE OR REPLACE FUNCTION budget.fn_cron_budget_warning()
RETURNS int
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = budget, gl, pgmq, pg_catalog
AS $$
DECLARE
    v_warn_ratio numeric := 0.8;
    v_cnt        int := 0;
    v_ratio      numeric;
    v_plan       record;
    v_param      jsonb;
BEGIN
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'budget.warn.ratio' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_warn_ratio := v_param::numeric;
            ELSE
                v_warn_ratio := COALESCE(NULLIF(v_param->>'v',''), '0.8')::numeric;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_warn_ratio := 0.8;
    END;

    IF to_regclass('rpt.budget_actual') IS NULL OR to_regclass('pgmq.send(text, jsonb, integer)') IS NULL THEN
        RAISE NOTICE 'fn_cron_budget_warning: rpt.budget_actual IMMV 或 PGMQ 不可用，跳过';
        RETURN 0;
    END IF;

    FOR v_plan IN
        SELECT p.id, p.company_id, p.period, p.account_id, p.dept_id, p.amount,
               COALESCE(a.actual_amount, 0) AS actual
          FROM budget.budget_plans p
          LEFT JOIN rpt.budget_actual a
                 ON a.company_id = p.company_id AND a.period = p.period
                AND a.account_id = p.account_id
                AND a.dept_id IS NOT DISTINCT FROM p.dept_id
         WHERE p.status = 'APPROVED' AND p.deleted_at IS NULL
           AND p.amount > 0
    LOOP
        v_ratio := (v_plan.actual::numeric / v_plan.amount::numeric);
        IF v_ratio >= v_warn_ratio THEN
            PERFORM pgmq.send('q_budget_warning',
                jsonb_build_object('company_id', v_plan.company_id,
                                   'dept_id',    v_plan.dept_id,
                                   'account_id', v_plan.account_id,
                                   'period',     v_plan.period,
                                   'ratio',      round(v_ratio, 4)),
                0);
            v_cnt := v_cnt + 1;
        END IF;
    END LOOP;
    RAISE NOTICE 'fn_cron_budget_warning: 触发预警 % 条（阈值 %）', v_cnt, v_warn_ratio;
    RETURN v_cnt;
END $$;

-- 8.2 wf.fn_timeout_remind（wf-timeout 任务，每分钟），超时待办置 TIMEOUT 并入队催办消息 q_notify_task，队列不存在时降级跳过，
-- 单轮处理批量上限读 um.params wf.timeout.batch.size，缺省 500，任务量增长后可经系统参数页调大
CREATE OR REPLACE FUNCTION wf.fn_timeout_remind()
RETURNS int
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = wf, pgmq, pg_catalog
AS $$
DECLARE
    v_cnt   int := 0;
    v_task  record;
    v_batch int := 500;
    v_param jsonb;
BEGIN
    BEGIN
        SELECT value INTO v_param FROM um.params
         WHERE code = 'wf.timeout.batch.size' AND deleted_at IS NULL;
        IF v_param IS NOT NULL THEN
            IF jsonb_typeof(v_param) = 'number' THEN
                v_batch := v_param::int;
            ELSE
                v_batch := COALESCE(NULLIF(v_param->>'v',''), '500')::int;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_batch := 500;                          -- 参数表异常，回落现状缺省
    END;
    FOR v_task IN
        SELECT id, instance_id, assignee_id, due_at, node_code
          FROM wf.tasks
         WHERE status IN ('PENDING','CLAIMED')
           AND due_at < now()
           AND deleted_at IS NULL
         ORDER BY due_at
         LIMIT v_batch
    LOOP
        UPDATE wf.tasks SET status = 'TIMEOUT', updated_at = now()
         WHERE id = v_task.id AND status IN ('PENDING','CLAIMED');
        IF FOUND THEN
            v_cnt := v_cnt + 1;
            BEGIN
                PERFORM pgmq.send('q_notify_task',
                    jsonb_build_object('type', 'wf_timeout',
                                       'instance_id', v_task.instance_id,
                                       'task_id', v_task.id,
                                       'assignee_id', v_task.assignee_id,
                                       'due_at', v_task.due_at),
                    0);
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'fn_timeout_remind: 任务 % 催办消息入队失败（%），忽略',
                    v_task.id, SQLERRM;
            END;
        END IF;
    END LOOP;
    RAISE NOTICE 'fn_timeout_remind: 超时置位 % 条', v_cnt;
    RETURN v_cnt;
END $$;

-- budget 预算调整单幂等防护，POST /api/budget/adjustments 同 body 重复提交仅生成
-- 1 条 DRAFT 调整单，budget.budget_adjustments 带 idem_key 列（服务层对
-- planId+amount+reason 规范化 SHA-256，同 body 必同键）+ 部分唯一索引
-- uk_adjustment_idem (company_id, plan_id, idem_key)
-- WHERE idem_key IS NOT NULL AND deleted_at IS NULL， 同公司同计划同内容重复提交
-- 服务层先查后插返回既有调整单，并发竞态由唯一索引兜底（23505 不产生
-- 重复数据，与 gl.vouchers uk_voucher_idem / cash.transactions uk_trx_idem 同族
-- 模式）。软删行不占键位，删除后可重建同键位。

--调整单幂等键列 + 部分唯一索引（同 body 重复提交去重）

ALTER TABLE budget.budget_adjustments ADD COLUMN IF NOT EXISTS idem_key varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_adjustment_idem ON budget.budget_adjustments
    (company_id, plan_id, idem_key)
    WHERE idem_key IS NOT NULL AND deleted_at IS NULL;

-- 自查，幂等重跑后应全 true/1
SELECT 'FIX_BUDGET_ADJ_IDEM' AS k,
       (SELECT count(*) FROM information_schema.columns
         WHERE table_schema='budget' AND table_name='budget_adjustments' AND column_name='idem_key') AS idem_col,
       (SELECT indexdef FROM pg_indexes WHERE schemaname='budget' AND indexname='uk_adjustment_idem')
         LIKE '%deleted_at IS NULL%' AS idem_partial;

-- wf 流程实例发起链路（POST /api/wf/instances 后端支撑），
--uk_wf_inst_business 部分唯一索引，同一业务单据仅允许一个未删除实例

--      （发起幂等 + 并发双保险，冲突 23505 映射 40900），
--wf.fn_start_instance SECURITY DEFINER 函数，原子创建 RUNNING 实例并激活

--      首个 APPROVE 节点任务集（处理人解析与策略展开见 fn_activate_node）。
--      SECURITY DEFINER 以属主（fms_admin，本文件 SET ROLE）身份执行，规避
--      um.users / um.user_role 的 rls 空集，fms_operator 对 um.users 仅可见本人。
-- 幂等，CREATE OR REPLACE FUNCTION + CREATE UNIQUE INDEX IF NOT EXISTS + GRANT
-- 重复执行无害。

--同一业务单据仅允许一个未删除审批实例

CREATE UNIQUE INDEX IF NOT EXISTS uk_wf_inst_business
    ON wf.instances (business_type, business_id)
    WHERE deleted_at IS NULL;

--流程发起函数，Java 侧已完成可见性/状态/编排/重复预检，本函数为原子写入 + 首节点激活。

-- 读 ACTIVE 定义 node_json，取首个 APPROVE 节点为当前节点，任务集经 fn_activate_node
-- 依节点策略生成（单审 1 票，会签/或签多票），处理人解析见 fn_activate_node。
-- 发起入口为 5 参，单节点单角色入参重载不保留（DROP 幂等清理）
DROP FUNCTION IF EXISTS wf.fn_start_instance(uuid, uuid, text, uuid, uuid, text, text);
CREATE OR REPLACE FUNCTION wf.fn_start_instance(
    p_company_id    uuid,
    p_definition_id uuid,
    p_business_type text,
    p_business_id   uuid,
    p_initiator_id  uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, auth, pg_catalog
AS $$
DECLARE
    v_def      wf.definitions%ROWTYPE;
    v_first    jsonb;
    v_inst_id  uuid;
BEGIN
    IF p_company_id IS NULL OR p_definition_id IS NULL OR p_business_id IS NULL
       OR p_initiator_id IS NULL THEN
        RAISE EXCEPTION '发起流程参数缺失（公司/定义/业务单号/发起人必填）'
            USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v_def FROM wf.definitions
     WHERE id = p_definition_id
       AND company_id = p_company_id
       AND status = 'ACTIVE'
       AND deleted_at IS NULL;
    IF v_def.id IS NULL THEN
        RAISE EXCEPTION '流程定义不存在或未启用'
            USING ERRCODE = 'P0001';
    END IF;
    SELECT value INTO v_first FROM (
        SELECT value, ord FROM jsonb_array_elements(v_def.node_json) WITH ORDINALITY AS e(value, ord)
         WHERE value->>'type' = 'APPROVE'
         ORDER BY ord
         LIMIT 1) s;
    IF v_first IS NULL THEN
        RAISE EXCEPTION '流程定义 % 未配置审批节点', v_def.code
            USING ERRCODE = 'P0001';
    END IF;

    -- 实例（RUNNING，current_node=首个审批节点）同事务，任务集由 fn_activate_node 生成
    INSERT INTO wf.instances (company_id, definition_id, business_type, business_id,
                              initiator_id, current_node, status)
    VALUES (p_company_id, p_definition_id, p_business_type, p_business_id,
            p_initiator_id, v_first->>'node_code', 'RUNNING')
    RETURNING id INTO v_inst_id;

    PERFORM wf.fn_activate_node(v_inst_id, v_first);

    RETURN v_inst_id;
END
$$;

COMMENT ON FUNCTION wf.fn_start_instance(uuid, uuid, text, uuid, uuid)
    IS '流程发起：创建 RUNNING 实例并激活首个审批节点任务集，节点策略见 fn_activate_node';

--授权，运行时连接角色经 SET ROLE fms_operator 调用

GRANT EXECUTE ON FUNCTION wf.fn_start_instance(uuid, uuid, text, uuid, uuid)
    TO fms_operator;


-- budget_plans UNIQUE(company_id,period,account_id,dept_id,version)，seed_data 的
-- ON CONFLICT 五列规范依赖该约束，幂等判存
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_budget_plans') THEN
        ALTER TABLE budget.budget_plans
            ADD CONSTRAINT uk_budget_plans UNIQUE (company_id, period, account_id, dept_id, version);
    END IF;
END $$;

-- 定义数据形态归一，APPROVE 节点补齐 strategy 与 handlers（source 按 assignee_role 推导为
-- role/applicant），START/END 去除 assignee_role 键，目标形态行不命中，幂等
DO $$
DECLARE
    v_row     record;
    v_node    jsonb;
    v_new     jsonb;
    v_out     jsonb := '[]'::jsonb;
    v_changed boolean := false;
BEGIN
    FOR v_row IN
        SELECT id, node_json FROM wf.definitions
    LOOP
        v_out := '[]'::jsonb;
        v_changed := false;
        FOR v_node IN
            SELECT value FROM jsonb_array_elements(v_row.node_json) AS e(value)
        LOOP
            IF v_node->>'type' = 'APPROVE' THEN
                IF v_node ? 'handlers' THEN
                    v_new := v_node;
                    IF NOT (v_node ? 'strategy') THEN
                        v_new := v_new || jsonb_build_object('strategy', 'approve');
                        v_changed := true;
                    END IF;
                ELSIF v_node ? 'assignee_role' THEN
                    IF lower(v_node->>'assignee_role') = 'applicant' THEN
                        v_new := jsonb_build_object('node_code', v_node->>'node_code', 'type', 'APPROVE',
                                    'strategy', 'approve',
                                    'handlers', jsonb_build_array(jsonb_build_object('source', 'applicant')));
                    ELSE
                        v_new := jsonb_build_object('node_code', v_node->>'node_code', 'type', 'APPROVE',
                                    'strategy', 'approve',
                                    'handlers', jsonb_build_array(
                                        jsonb_build_object('source', 'role', 'code', v_node->>'assignee_role')));
                    END IF;
                    v_changed := true;
                ELSE
                    v_new := v_node;
                END IF;
            ELSIF v_node ? 'assignee_role' THEN
                v_new := v_node - 'assignee_role';
                v_changed := true;
            ELSE
                v_new := v_node;
            END IF;
            v_out := v_out || v_new;
        END LOOP;
        IF v_changed THEN
            UPDATE wf.definitions SET node_json = v_out, updated_at = now()
             WHERE id = v_row.id;
        END IF;
    END LOOP;
END $$;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1，确认执行完整）
SELECT 'fa_expense_budget_wf_base' AS file_name,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname IN ('fa','expense','budget','wf'))              AS table_cnt,
       (SELECT count(*) FROM pg_proc
         WHERE pronamespace IN (SELECT oid FROM pg_namespace
                                 WHERE nspname IN ('fa','expense','budget','wf'))
           AND proname NOT IN ('fn_set_updated_at'))                       AS func_cnt,
       (SELECT count(*) FROM pg_trigger
         WHERE tgrelid::regclass::text LIKE ANY (ARRAY['fa.%','expense.%','budget.%','wf.%'])
           AND NOT tgisinternal)                                           AS trg_cnt,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname IN ('fa','expense','budget','wf'))               AS policy_cnt,
       (SELECT count(*) FROM pg_tables
         WHERE schemaname IN ('fa','expense','budget','wf')
           AND rowsecurity)                                                AS rls_table_cnt;

-- reimbursement_lines UNIQUE(reimbursement_id,line_no)，seed_data 演示报销单段的
-- ON CONFLICT (reimbursement_id,line_no) 依赖该约束
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_reimbursement_line_no') THEN
        ALTER TABLE expense.reimbursement_lines
            ADD CONSTRAINT uk_reimbursement_line_no UNIQUE (reimbursement_id, line_no);
    END IF;
END $$;

-- 流程定义回草稿，DISABLED 转 DRAFT 重新起草，SECURITY DEFINER 豁免 RLS 计数 RUNNING 实例，
-- 前置仅 DISABLED 且无 RUNNING 可回（防改编排打断在途审批），数据范围按 can_access_org
CREATE OR REPLACE FUNCTION wf.fn_revise_definition(p_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = wf, pg_catalog, public
AS $$
DECLARE
    v_company_id uuid;
    v_running    bigint;
BEGIN
    SELECT company_id INTO v_company_id FROM wf.definitions WHERE id = p_id AND deleted_at IS NULL;
    IF v_company_id IS NULL THEN
        RETURN false;
    END IF;
    IF session_user NOT IN ('fms_admin', 'postgres') AND NOT auth.can_access_org(v_company_id) THEN
        RAISE EXCEPTION 'wf.fn_revise_definition: 无公司数据范围权限';
    END IF;
    SELECT count(*) INTO v_running FROM wf.instances
     WHERE definition_id = p_id AND status = 'RUNNING' AND deleted_at IS NULL;
    IF v_running > 0 THEN
        RETURN false;
    END IF;
    UPDATE wf.definitions SET status = 'DRAFT'
     WHERE id = p_id AND status = 'DISABLED' AND deleted_at IS NULL;
    RETURN FOUND;
END $$;

COMMENT ON FUNCTION wf.fn_revise_definition(uuid)
    IS '流程定义回草稿 DISABLED 转 DRAFT，仅无 RUNNING 实例可回，规避 RLS 计数在途';

-- 授权，运行时连接角色经 SET ROLE fms_operator 调用
GRANT EXECUTE ON FUNCTION wf.fn_revise_definition(uuid) TO fms_operator;
