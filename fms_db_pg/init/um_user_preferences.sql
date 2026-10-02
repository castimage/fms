-- FMS 背景设置（init/um_user_preferences），um.user_preferences 用户级背景偏好，rls 限定本人，四职责角色可读写自己的行，个人 ui 偏好非业务数据，不属业务写权限面，docs.documents 业务类型白名单扩展（LOGIN_BACKGROUND/USER_BACKGROUND 背景图），docs.fn_public_document_meta 匿名读口，仅放行 LOGIN_BACKGROUND 类型，登录页匿名加载背景图的最小暴露面

-- 幂等，CREATE TABLE IF NOT EXISTS / DROP CONSTRAINT IF EXISTS / CREATE OR REPLACE FUNCTION，依赖 init/um_org_base.sql（um.users/auth.current_uid）与 init/docs_base.sql（docs.documents）已部署

SET ROLE fms_admin;

-- 用户级背景偏好表（jsonb 值，{enabled, imageUrl, fit, blur, darken, desktop/tablet/mobile 断点档覆盖}）
-- 行级安全，本人行策略，rls 与后端"仅操作自己"双层收敛，后端从签名身份取 user_id，rls 兜底防伪造
CREATE TABLE IF NOT EXISTS um.user_preferences (
    user_id    uuid        PRIMARY KEY REFERENCES um.users(id) ON DELETE CASCADE,
    background jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
COMMENT ON TABLE  um.user_preferences IS '用户级 UI 偏好（背景图配置 JSONB；RLS 限定本人行，四职责角色一致可读写——个人偏好非业务数据，不参与业务写权限面）';
COMMENT ON COLUMN um.user_preferences.background IS '背景配置：{enabled,imageUrl,fit,blur,darken,opacity,desktop,tablet,mobile}；断点档仅覆盖给定字段，缺省继承顶层；opacity 为图片层不透明度 0-1（1 不透明）';

-- rls，本人行策略，CHECK 同 USING，插入/更新只能写自己，admin 亦受此限，无跨用户查看需求
ALTER TABLE um.user_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.user_preferences FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS user_prefs_self ON um.user_preferences;
CREATE POLICY user_prefs_self ON um.user_preferences
    USING (user_id = auth.current_uid())
    WITH CHECK (user_id = auth.current_uid());

-- 授权，四职责角色 SELECT/INSERT/UPDATE，无 DELETE，覆盖写与软删即可，fms_app 沙箱经 SET ROLE 注入后访问
GRANT SELECT, INSERT, UPDATE ON um.user_preferences
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- docs.documents 业务类型白名单扩展（背景图文件，登录页全局背景 LOGIN_BACKGROUND / 用户级背景 USER_BACKGROUND，
-- 与 Java FileServiceImpl.BUSINESS_TYPES 同构，双侧同步维护）
ALTER TABLE docs.documents DROP CONSTRAINT IF EXISTS documents_business_type_check;
ALTER TABLE docs.documents ADD CONSTRAINT documents_business_type_check
    CHECK (business_type IN ('REIMBURSEMENT_LINE','VOUCHER','LOGIN_BACKGROUND','USER_BACKGROUND'));

-- 匿名读口，登录页背景图，SECURITY DEFINER 以属主身份绕过 rls，仅返回 LOGIN_BACKGROUND 且未删文件的存储元数据，
-- 最小暴露面，其他类型/已删文件一律空行，字节读取由后端按 storage_path 读磁盘流，不经过 rls 查询路径
CREATE OR REPLACE FUNCTION docs.fn_public_document_meta(p_id uuid)
RETURNS TABLE(storage_path text, file_name text, content_type text)
LANGUAGE sql SECURITY DEFINER
SET search_path = docs, pg_temp
AS $$
    SELECT d.storage_path, d.file_name, d.content_type
      FROM docs.documents d
     WHERE d.id = p_id
       AND d.business_type = 'LOGIN_BACKGROUND'
       AND d.deleted_at IS NULL
$$;
REVOKE ALL ON FUNCTION docs.fn_public_document_meta(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION docs.fn_public_document_meta(uuid) TO fms_app;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1）
SELECT 'um_user_preferences' AS file_name,
       to_regclass('um.user_preferences') AS user_pref_tbl,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'um' AND tablename = 'user_preferences') AS pref_policy_cnt,
       has_table_privilege('fms_viewer', 'um.user_preferences', 'INSERT') AS viewer_can_insert_self_pref;
