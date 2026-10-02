-- FMS 财务系统（init/role_fms_public，执行库，postgres + fms），fms_public 公开只读角色 + fms_app 最小权限收敛

-- fms_app 为沙箱连接角色，仅保留 CONNECT 与认证函数（auth.* 认证族
--        EXECUTE，登录/2fa/jwt 签发为连接角色认证职责），禁止业务/公开类函数 EXECUTE。

-- 收敛，新角色 fms_public（NOLOGIN），公开读口（/api/public/**）匿名请求经
--        SandboxSessionInterceptor 注入，仅 EXECUTE fn_get_param_value / fn_public_document_meta
--        + um/docs/log schema USAGE，函数解析必需，无任何表权限，
-- fms_app 撤销 4 个业务/公开类函数 EXECUTE（docs.fn_public_document_meta /

--        um.fn_get_param_value / log.fn_send_access_batch / log.fn_apply_access_log），
-- 内部投递（/internal/log-batch，X-FMS-Int-Token 保护）匿名请求注入 fms_operator

--        （log.* 投递函数 EXECUTE 授 fms_operator）。

-- 幂等，CREATE ROLE 判存 / REVOKE/GRANT 幂等，依赖 00（fms_app/职责角色）、init/um_params_functions.sql（fn_get_param_value）、
--        23（fn_public_document_meta）、27（fn_send_access_batch/fn_apply_access_log）已部署。

-- 角色（集群级，postgres 库），fms_public NOLOGIN，公开读口最小角色
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fms_public') THEN
        CREATE ROLE fms_public NOLOGIN;
    END IF;
END $$;
COMMENT ON ROLE fms_public IS '公开读口角色（NOLOGIN）：仅 EXECUTE fn_get_param_value/fn_public_document_meta + 对应 schema USAGE，无任何表权限；匿名 /api/public/** 请求经 SandboxSessionInterceptor SET ROLE 注入';

-- fms_app 作为 fms_public 成员（SET ROLE 前置，当前角色须为目标角色成员，WITH INHERIT FALSE 与职责角色同约定）
GRANT fms_public TO fms_app WITH INHERIT FALSE;

-- fms_app 撤销业务/公开类函数 EXECUTE，沙箱最小化，认证函数 auth.* 保留
REVOKE EXECUTE ON FUNCTION docs.fn_public_document_meta(uuid) FROM fms_app;
REVOKE EXECUTE ON FUNCTION um.fn_get_param_value(text, jsonb) FROM fms_app;
REVOKE EXECUTE ON FUNCTION log.fn_send_access_batch(jsonb) FROM fms_app;
REVOKE EXECUTE ON FUNCTION log.fn_apply_access_log(jsonb) FROM fms_app;

-- 公开读口（fms_public），仅两个公开函数 + schema USAGE，函数解析必需，无表权限
GRANT USAGE ON SCHEMA um, docs, log TO fms_public;
GRANT EXECUTE ON FUNCTION um.fn_get_param_value(text, jsonb), docs.fn_public_document_meta(uuid) TO fms_public;

-- 业务读口，职责角色，公开读函数授 fms_operator，认证请求注入 fms_operator 后可用
GRANT EXECUTE ON FUNCTION um.fn_get_param_value(text, jsonb), docs.fn_public_document_meta(uuid) TO fms_operator;

-- 内部投递（/internal/log-batch），日志投递函数授 fms_operator，匿名内部请求注入 fms_operator
GRANT EXECUTE ON FUNCTION log.fn_send_access_batch(jsonb), log.fn_apply_access_log(jsonb) TO fms_operator;

-- 文件末尾自查
SELECT 'role_fms_public' AS file_name,
       (SELECT count(*) FROM pg_roles WHERE rolname = 'fms_public') AS public_role_cnt,
       has_function_privilege('fms_app', 'log.fn_send_access_batch(jsonb)', 'EXECUTE') AS app_no_send_batch,
       has_function_privilege('fms_public', 'um.fn_get_param_value(text,jsonb)', 'EXECUTE') AS public_can_read_param,
       has_function_privilege('fms_operator', 'log.fn_send_access_batch(jsonb)', 'EXECUTE') AS op_can_send_batch;
