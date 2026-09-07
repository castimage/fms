-- FMS 财务系统（init/um_param_meta），系统参数元数据（um.param_meta），Web 系统参数页
-- 分组表单化渲染的权威来源，答辩改进项，kv 列表转元数据驱动表单。

-- 设计，um.params 存值（kv + jsonb 形态不变），本表描述每个参数的展示方式（分组/标签/
-- 控件类型/单位/范围/枚举/说明/敏感标记），新增参数 = 插一条 params + 一条 param_meta，
-- 前端按 meta 自动渲染控件，零前端改动。

-- 表级最小授权同 um.params，零职责角色 SELECT，读走 SECURITY DEFINER 函数 fn_page_param_meta。

SET ROLE fms_admin;

-- um.param_meta 参数元数据表（code 与 um.params 对齐）
CREATE TABLE IF NOT EXISTS um.param_meta (
    code         varchar(64) PRIMARY KEY,              -- 参数键（与 um.params.code 对应）
    grp          varchar(32) NOT NULL DEFAULT 'other', -- 分组（budget/mail/cash/wf/log/fa/rpt/web/ui/other）
    label        varchar(128) NOT NULL,                -- 展示名
    control_type varchar(16) NOT NULL DEFAULT 'text',  -- boolean|number|select|text|json
    unit         varchar(16),                          -- 单位后缀，如 %、天
    min_val      numeric,                              -- number 控件下限
    max_val      numeric,                              -- number 控件上限
    step         numeric,                              -- number 控件步长
    options      jsonb,                                -- select 控件选项（[{"label":..,"value":..}]）
    hint         varchar(256),                         -- 说明文案
    sort_no      int NOT NULL DEFAULT 0,               -- 组内排序
    sensitive    boolean NOT NULL DEFAULT false        -- 敏感参数，页面脱敏只读，禁写
);
COMMENT ON TABLE um.param_meta IS '系统参数元数据（Web 系统参数页分组表单渲染权威源；表级零职责角色授权，读经 fn_page_param_meta）';

-- 元数据读口（SECURITY DEFINER，属主 fms_admin 绕过表级 REVOKE，返回全部 meta 供管理页渲染）
CREATE OR REPLACE FUNCTION um.fn_page_param_meta()
RETURNS TABLE (code varchar, grp varchar, label varchar, control_type varchar,
               unit varchar, min_val numeric, max_val numeric, step numeric,
               options jsonb, hint varchar, sort_no int, sensitive boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = um, public
AS $$
    SELECT code, grp, label, control_type, unit, min_val, max_val, step,
           options, hint, sort_no, sensitive
      FROM um.param_meta
     ORDER BY grp, sort_no, code
$$;
COMMENT ON FUNCTION um.fn_page_param_meta() IS '系统参数元数据全量读口（SECURITY DEFINER；admin 管理页经 /api/admin/params/meta 调用）';

-- 20 个既有参数元数据种子（幂等，ON CONFLICT DO NOTHING）
INSERT INTO um.param_meta
    (code, grp, label, control_type, unit, min_val, max_val, step, options, hint, sort_no, sensitive)
VALUES
    ('budget.warn.ratio',            'budget', '预算预警阈值',       'number', '%',  0,   100, 5,   NULL, '执行率达该比例触发预警通知', 10, false),
    ('budget.block.ratio',           'budget', '预算超支阻断阈值',    'number', '%',  0,   200, 5,   NULL, '执行率达该比例阻断超支录入', 20, false),
    ('budget.block.enabled',         'budget', '超支阻断开关',        'boolean', NULL, NULL, NULL, NULL, NULL, '开启后超支预算项禁止录入', 30, false),
    ('smtp.host',                    'mail',   'SMTP 服务器',         'text',   NULL, NULL, NULL, NULL, NULL, '邮件发送服务器地址（配置后找回密码邮件自动发送）', 10, false),
    ('smtp.port',                    'mail',   'SMTP 端口',           'number', NULL, 1,   65535, 1,   NULL, '邮件服务器端口', 20, false),
    ('smtp.from',                    'mail',   '发件人',              'text',   NULL, NULL, NULL, NULL, NULL, '邮件发件人地址', 30, false),
    ('cash.reconcile.date.window.days', 'cash', '对账候选日期窗口',   'number', '天', 1,   30,   1,   NULL, '银行对账自动配对候选日期范围', 10, false),
    ('cash.reconcile.similarity.auto',  'cash', '自动配对相似度阈值', 'number', NULL, 0,   1,    0.05, NULL, '摘要相似度达该值自动匹配（低于留人工）', 20, false),
    ('wf.task.due.days',             'wf',     '审批任务到期时限',    'number', '天', 1,   30,   1,   NULL, '审批任务默认到期时限（到期自动催办）', 10, false),
    ('wf.timeout.batch.size',        'wf',     '超时催办批量上限',    'number', NULL, 1,   5000, 100, NULL, '超时置位单轮批量上限', 20, false),
    ('log.queue.batch.size',         'log',    '日志队列消费上限',    'number', NULL, 1,   5000, 100, NULL, '日志队列每轮每队列消费条数', 10, false),
    ('log.queue.vt.seconds',         'log',    '日志消息可见性超时',  'number', '秒', 1,   600,  10,  NULL, '日志消息未归档自动回投窗口', 20, false),
    ('audit.retention.years',        'log',    '审计日志保留期',      'number', '年', 1,   30,   1,   NULL, '审计日志分区保留期（合规 7 年基线）', 30, false),
    ('log.retention.days',           'log',    '应用日志保留期',      'number', '天', 1,   3650, 30,  NULL, '应用/访问日志分区保留期', 40, false),
    ('loginlog.retention.years',     'log',    '登录日志保留期',      'number', '年', 1,   10,   1,   NULL, '登录日志分区保留期', 50, false),
    ('fa.depr.expense.account',      'fa',     '折旧费用科目编码',    'text',   NULL, NULL, NULL, NULL, NULL, '折旧凭证费用行科目 code', 10, false),
    ('fa.depr.asset.account',        'fa',     '累计折旧科目编码',    'text',   NULL, NULL, NULL, NULL, NULL, '折旧凭证累计折旧行科目 code', 20, false),
    ('rpt.dashboard.alert.topn',     'rpt',    '看板告警展示上限',    'number', NULL, 1,   20,   1,   NULL, '首页预算/银行余额告警每类展示条数', 10, false),
    ('web.refdata.page.size',        'web',    '参考数据拉取上限',    'number', NULL, 10,  1000, 10,  NULL, '下拉列表（公司/供应商/客户等）一次性拉取上限', 10, false),
    ('ui.login.background',          'ui',     '登录页背景',          'json',   NULL, NULL, NULL, NULL, NULL, '登录页全局背景配置（{"v":{enabled,fit,blur,darken,opacity,...}}）', 10, false)
ON CONFLICT (code) DO NOTHING;

-- 授权收口，表零职责角色授权，读走 SECURITY DEFINER 函数，同 um.params 最小授权原则
REVOKE ALL ON TABLE um.param_meta FROM PUBLIC;
REVOKE ALL ON TABLE um.param_meta FROM fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
REVOKE ALL ON FUNCTION um.fn_page_param_meta() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION um.fn_page_param_meta() TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- ============================================================================
-- 对象参数子字段元数据（um.param_meta_field），{"v":{...}} 形态参数按字段拆分渲染子表单
-- （如 ui.login.background 的 enabled/fit/blur/darken/opacity/...），每字段独立控件，
-- 无子字段元数据的 json 参数保持整段 json 编辑兜底。
-- ============================================================================
CREATE TABLE IF NOT EXISTS um.param_meta_field (
    code         varchar(64) NOT NULL REFERENCES um.param_meta(code) ON DELETE CASCADE,
    field        varchar(64) NOT NULL,               -- 对象字段名（{"v":{...}} 内层键）
    label        varchar(128) NOT NULL,              -- 字段展示名
    control_type varchar(16) NOT NULL DEFAULT 'text',-- boolean|number|select|text|json
    unit         varchar(16),
    min_val      numeric,
    max_val      numeric,
    step         numeric,
    options      jsonb,                              -- select 选项（[{"label":..,"value":..}]）
    hint         varchar(256),
    sort_no      int NOT NULL DEFAULT 0,
    PRIMARY KEY (code, field)
);
COMMENT ON TABLE um.param_meta_field IS '对象参数子字段元数据（{"v":{...}} 形态参数按字段渲染子表单）';

-- 子字段读口（SECURITY DEFINER，属主 fms_admin 绕过表级 REVOKE）
CREATE OR REPLACE FUNCTION um.fn_page_param_meta_field()
RETURNS TABLE (code varchar, field varchar, label varchar, control_type varchar,
               unit varchar, min_val numeric, max_val numeric, step numeric,
               options jsonb, hint varchar, sort_no int)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = um, public
AS $$
    SELECT code, field, label, control_type, unit, min_val, max_val, step,
           options, hint, sort_no
      FROM um.param_meta_field
     ORDER BY code, sort_no, field
$$;
COMMENT ON FUNCTION um.fn_page_param_meta_field() IS '对象参数子字段元数据全量读口（SECURITY DEFINER）';

-- ui.login.background 子字段种子，登录页背景对象参数，10 字段
INSERT INTO um.param_meta_field
    (code, field, label, control_type, unit, min_val, max_val, step, options, hint, sort_no)
VALUES
    ('ui.login.background', 'enabled',        '启用背景',      'boolean', NULL, NULL, NULL, NULL, NULL, '是否启用登录页背景', 10),
    ('ui.login.background', 'imageUrl',       '背景图片',      'text',    NULL, NULL, NULL, NULL, NULL, '图片 URL 或 /api/public/files/{id}', 20),
    ('ui.login.background', 'fit',            '拉伸模式',      'select',  NULL, NULL, NULL, NULL,
        '[{"label":"铺满","value":"cover"},{"label":"完整","value":"contain"},{"label":"拉伸","value":"fill"}]'::jsonb,
        'cover 等比铺满裁剪 / contain 完整显示留边 / fill 铺满变形', 30),
    ('ui.login.background', 'blur',           '模糊级别',      'number',  'px', 0,    20,   1,   NULL, '背景模糊（越大越柔和）', 40),
    ('ui.login.background', 'darken',         '黑暗程度',      'number',  NULL, 0,    0.8,  0.05, NULL, '黑色蒙层（提升文字对比度）', 50),
    ('ui.login.background', 'opacity',        '图片透明度',    'number',  NULL, 0,    1,    0.05, NULL, '图片层不透明度（1 不透明）', 60),
    ('ui.login.background', 'contentOpacity', '内容区透明度',  'number',  NULL, 0.5,  1,    0.05, NULL, '内容区组件不透明度（<1 半透明露出背景）', 70),
    ('ui.login.background', 'desktop',        '桌面端覆盖',    'json',    NULL, NULL, NULL, NULL, NULL, '桌面档独立配置（留空继承顶层）', 80),
    ('ui.login.background', 'tablet',         '平板端覆盖',    'json',    NULL, NULL, NULL, NULL, NULL, '平板档独立配置（留空继承顶层）', 90),
    ('ui.login.background', 'mobile',         '手机端覆盖',    'json',    NULL, NULL, NULL, NULL, NULL, '手机档独立配置（留空继承顶层）', 100)
ON CONFLICT (code, field) DO NOTHING;

-- 子字段表授权收口，同 param_meta，零职责角色，读走函数
REVOKE ALL ON TABLE um.param_meta_field FROM PUBLIC;
REVOKE ALL ON TABLE um.param_meta_field FROM fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
REVOKE ALL ON FUNCTION um.fn_page_param_meta_field() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION um.fn_page_param_meta_field() TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- 自查，meta 与 params 参数键对齐，params 有而 meta 缺 = 前端 fallback 其他组 json 控件，不阻断
DO $$
DECLARE
    v_cnt int;
BEGIN
    SELECT count(*) INTO v_cnt
      FROM um.params p
      LEFT JOIN um.param_meta m ON m.code = p.code
     WHERE m.code IS NULL;
    IF v_cnt > 0 THEN
        RAISE NOTICE 'param_meta: % 个既有参数无元数据（前端 fallback 其他组 JSON 控件）', v_cnt;
    END IF;
END $$;

RESET ROLE;
