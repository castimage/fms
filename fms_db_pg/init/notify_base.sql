-- FMS 财务系统（init/notify_base），notify schema + notifications 表，新库初始化组成部分

-- 与实体约定对齐（Notification.java，id uuidv7 PK、user_id、title、content、type、
-- ref_type、ref_id、is_read、read_at、created_at、updated_at、deleted_at），
-- rls 按 auth.current_uid = user_id 收敛，Java 侧恒加 user_id 过滤双保险，
-- 全幂等可重跑（CREATE IF NOT EXISTS / DROP POLICY IF EXISTS / ON CONFLICT）。

SET ROLE fms_admin;

-- schema 与表

CREATE SCHEMA IF NOT EXISTS notify;

CREATE TABLE IF NOT EXISTS notify.notifications (
    id         uuid          PRIMARY KEY DEFAULT uuidv7(),
    user_id    uuid          NOT NULL,
    title      varchar(128)  NOT NULL,
    content    text          NOT NULL DEFAULT '',
    type       varchar(32)   NOT NULL DEFAULT 'SYSTEM',
    ref_type   varchar(64),
    ref_id     uuid,
    is_read    boolean       NOT NULL DEFAULT false,
    read_at    timestamptz,
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_notify_user ON notify.notifications (user_id, created_at DESC);

-- 幂等键唯一索引，通知生成的去重依据，两个插入闸口函数据此 ON CONFLICT DO NOTHING，
-- 消息重投与并发插入不会重复落库；部分索引排除软删行，软删后同键可再次通知；
-- ref_id 或 ref_type 为 NULL 的行不受约束，PG 视 NULL 互不相等，无业务引用的通知可重复插入
CREATE UNIQUE INDEX IF NOT EXISTS uk_notify_user_ref
    ON notify.notifications (user_id, ref_type, ref_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE notify.notifications IS
'站内通知（按收件人 user_id 归属；RLS 按 auth.current_uid() = user_id 收敛，Java 侧恒加 user_id 过滤双保险）';

-- rls（FORCE，user_id 收敛，无 company_id 维度）

ALTER TABLE notify.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.notifications FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS notifications_select ON notify.notifications;
CREATE POLICY notifications_select ON notify.notifications
    FOR SELECT USING (deleted_at IS NULL AND user_id = auth.current_uid());

DROP POLICY IF EXISTS notifications_insert ON notify.notifications;
CREATE POLICY notifications_insert ON notify.notifications
    FOR INSERT WITH CHECK (user_id = auth.current_uid());

DROP POLICY IF EXISTS notifications_update ON notify.notifications;
CREATE POLICY notifications_update ON notify.notifications
    FOR UPDATE USING (user_id = auth.current_uid())
    WITH CHECK (user_id = auth.current_uid());

DROP POLICY IF EXISTS notifications_delete ON notify.notifications;
CREATE POLICY notifications_delete ON notify.notifications
    FOR DELETE USING (user_id = auth.current_uid());

-- 授权，全部职责角色本人通知读写，沙箱连接角色 fms_app 保留 USAGE 供 SET ROLE 切换

GRANT USAGE ON SCHEMA notify TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT SELECT, INSERT, UPDATE ON notify.notifications
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- 审计捕获触发器挂载，与 init 层其余业务表文件同款模式

DO $$
BEGIN
    IF to_regclass('notify.notifications') IS NOT NULL THEN
        EXECUTE 'DROP TRIGGER IF EXISTS trg_notifications_audit ON notify.notifications';
        EXECUTE 'CREATE TRIGGER trg_notifications_audit AFTER INSERT OR UPDATE OR DELETE '
                || 'ON notify.notifications FOR EACH ROW EXECUTE FUNCTION audit.fn_audit_log()';
    END IF;
END $$;

-- 种子，演示用户欢迎通知，幂等，已存在该用户通知则不重复插入

INSERT INTO notify.notifications (user_id, title, content, type, created_at)
SELECT u.id, '欢迎使用 FMS 财务管理系统', '您的账号已就绪，可开始办理业务。', 'SYSTEM', now()
  FROM um.users u
 WHERE u.login_name IN ('operator01','viewer01','auditor01','report01')
   AND NOT EXISTS (SELECT 1 FROM notify.notifications n WHERE n.user_id = u.id);

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'notify_base' AS file_name,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'notify' AND c.relname = 'notifications') AS table_cnt,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'notify') AS policy_cnt;
