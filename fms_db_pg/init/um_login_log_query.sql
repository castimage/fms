-- FMS 财务系统（init/um_login_log_query），登录日志独立查询端点支撑，um.login_log rls 策略调整

-- 安全中心（2fa/api Key/登录审计）要求登录审计独立查询，um.login_log 已建
--       （auth.record_login SECURITY DEFINER 写入，rls，is_self OR has_role('fms_auditor') OR
--       current_user='fms_admin'，按月分区），此前 SecurityPage 以应用日志占位、缺独立查询端点。

-- 问题，admin 在 pg 层映射 fms_operator（JwtRole.pgRole 白名单，operator/admin 并入 fms_operator），
--       rls 策略对 fms_operator 仅 is_self 分支生效则 admin 只能看到本人登录记录，
--       无法承担登录审计职责，"查询以 fms_auditor 职责角色执行"受阻，pg 限制
--       'cannot set parameter role within security-definer function'，函数内 SET ROLE 不可行。

-- 方案，设计稿二选一之"调整策略"，login_log_select 策略增补 has_permission('audit:read:all')
--       分支，该权限点按 rbac 矩阵仅 auditor/admin 持有（auditor 角色 audit:read:all、admin 的
--       fms_admin_role 全量），has_permission 为 SECURITY DEFINER 读 guc fms.user_id，不依赖
--       current_user，沙箱每语句注入，operator/viewer/report 恒 false 则仍仅本人可见，
--       rls 行级收敛保持 fail-closed，未注入上下文则 current_uid NULL，全部分支 false，回空集。
--       由此 admin 以 fms_operator 登录角色 + 审计权限点放行全量，等价于以审计职责读取。

-- 依赖，init/um_perm.sql（auth.has_permission、audit:read:all 存量回填）与 init/seed_data.sql，auditor 角色种子。
--       全部 DDL 幂等可重跑。
SET ROLE fms_admin;

-- 废弃函数清理，auth.query_login_logs（SECURITY DEFINER 内 SET LOCAL ROLE 被 pg 18 拒绝，
--   不可用，DROP 幂等）
DROP FUNCTION IF EXISTS auth.query_login_logs(text, timestamptz, timestamptz, boolean, boolean);

-- rls 策略调整，登录审计全量读取 = 本人（is_self） OR 审计职责角色（has_role fms_auditor）
--   OR 持有 audit:read:all 权限点（auditor/admin 经 rbac 矩阵，SECURITY DEFINER 读 guc 判用户）
--   OR 维护直通（current_user fms_admin，SECURITY DEFINER 函数内/维护会话）
DROP POLICY IF EXISTS login_log_select ON um.login_log;
CREATE POLICY login_log_select ON um.login_log
    FOR SELECT USING (auth.is_self(user_id)
                      OR auth.has_role('fms_auditor')
                      OR auth.has_permission('audit:read:all')
                      OR current_user = 'fms_admin');
COMMENT ON POLICY login_log_select ON um.login_log IS
    '登录日志 SELECT：本人可见 + fms_auditor 全读 + audit:read:all 权限点（admin 经 RBAC 放行全量，operator/viewer/report 仅本人） + fms_admin 维护直通';

-- 文件末尾自查，策略存在 + 权限点可达，fms_admin 会话直读恒 t
SELECT 'um_login_log_query' AS file_name,
       (SELECT count(*) FROM pg_policies
         WHERE schemaname = 'um' AND tablename = 'login_log'
           AND policyname = 'login_log_select') AS policy_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth' AND p.proname = 'query_login_logs') AS leftover_fn_cnt;

RESET ROLE;
