-- FMS 财务系统（init/um_params_functions），um.params 读/写经 SECURITY DEFINER 函数以 fms_admin 身份执行

-- um_org_base 对 um.params 执行最小授权（REVOKE ALL FROM 全部职责角色，密钥已收敛 sec.app_secrets），
--      /api/admin/params 读与 CRUD 经 um.fn_page_params / um.fn_get_param / um.fn_upsert_param
--      三个 SECURITY DEFINER 函数执行（属主 fms_admin 绕过表级 REVOKE，rls 策略
--      params_select/params_write 显式放行 current_user='fms_admin'），函数内 auth.require_permission
--      闸口，admin.param.view / admin.param.update， operator 等非管理角色 40300 按 rbac 设计拒绝，
--      admin（fms_admin_role 全量授权）200 可用。全部 DDL 幂等可重跑。

-- 依赖，init/um_perm.sql（auth.require_permission / admin.param.view / admin.param.update 种子）。

-- 1. 参数分页（keyword 按 code 模糊，STABLE）
CREATE OR REPLACE FUNCTION um.fn_page_params(p_keyword text DEFAULT NULL)
RETURNS TABLE (id uuid, code text, value text, updated_by uuid, created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public
AS $$
BEGIN
    PERFORM auth.require_permission('admin.param.view');
    RETURN QUERY
        SELECT q.id, q.code, q.value::text, q.updated_by, q.created_at, q.updated_at
          FROM um.params q
         WHERE q.deleted_at IS NULL
           AND (p_keyword IS NULL OR p_keyword = '' OR q.code ILIKE '%' || p_keyword || '%')
         ORDER BY q.code;
END $$;

-- 2. 单参数查询，按 code，未命中返回空集，服务层 40400
CREATE OR REPLACE FUNCTION um.fn_get_param(p_code text)
RETURNS TABLE (id uuid, code text, value text, updated_by uuid, created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public
AS $$
BEGIN
    PERFORM auth.require_permission('admin.param.view');
    RETURN QUERY
        SELECT q.id, q.code, q.value::text, q.updated_by, q.created_at, q.updated_at
          FROM um.params q
         WHERE q.code = p_code AND q.deleted_at IS NULL;
END $$;

-- 3. 参数 upsert（插入或原子更新 value/updated_by/updated_at，返回行 id）。
--    双保险，Java 侧 assertNotSensitive 先行，40300，db 侧白名单镜像，消息含"无权限"则同样 40300。
CREATE OR REPLACE FUNCTION um.fn_upsert_param(p_code text, p_value jsonb, p_updated_by uuid)
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = um, public
AS $$
DECLARE
    v_lower text;
    v_id    uuid;
BEGIN
    PERFORM auth.require_permission('admin.param.update');
    IF p_code IS NULL OR btrim(p_code) = '' THEN
        RAISE EXCEPTION '参数编码必填';
    END IF;
    v_lower := lower(p_code);
    IF v_lower LIKE 'jwt.%' OR v_lower LIKE 'pgcrypto.%'
       OR v_lower LIKE '%secret%' OR v_lower LIKE '%password%'
       OR v_lower LIKE '%token%' OR v_lower LIKE '%master_key%'
       OR v_lower LIKE '%blind_key%' THEN
        RAISE EXCEPTION '无权限：敏感参数禁止经此通道写入：%', p_code;
    END IF;
    INSERT INTO um.params (code, value, updated_by, updated_at)
    VALUES (p_code, p_value, p_updated_by, now())
    ON CONFLICT (code) DO UPDATE
       SET value = EXCLUDED.value,
           updated_by = EXCLUDED.updated_by,
           updated_at = now()
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;

-- 4. 公共参数值读取（配置下发 /api/admin/params/public 支撑），任意登录角色可读的轻量读口，
--    仅返回单码 jsonb 值，参数缺失/异常返回 p_default，调用方必须传缺省值，防删参数后崩溃，
--    敏感键（jwt./pgcrypto. 前缀或含 secret/password/token/master_key/blind_key）一律返回
--    p_default，与写口白名单（fn_upsert_param）镜像双保险，密钥恒不经本通道外泄，
--    um.params 内已无敏感键，此过滤为纵深防御。
CREATE OR REPLACE FUNCTION um.fn_get_param_value(p_code text, p_default jsonb DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public
AS $$
DECLARE
    v_lower text;
    v_val   jsonb;
BEGIN
    IF p_code IS NULL OR btrim(p_code) = '' THEN
        RETURN p_default;
    END IF;
    v_lower := lower(p_code);
    IF v_lower LIKE 'jwt.%' OR v_lower LIKE 'pgcrypto.%'
       OR v_lower LIKE '%secret%' OR v_lower LIKE '%password%'
       OR v_lower LIKE '%token%' OR v_lower LIKE '%master_key%'
       OR v_lower LIKE '%blind_key%' THEN
        RETURN p_default;                        -- 敏感键读口关闭，与写口白名单镜像
    END IF;
    SELECT value INTO v_val FROM um.params
     WHERE code = p_code AND deleted_at IS NULL;
    RETURN COALESCE(v_val, p_default);
EXCEPTION WHEN OTHERS THEN
    RETURN p_default;                            -- 任何异常回落缺省，调用方不崩溃
END $$;
COMMENT ON FUNCTION um.fn_get_param_value(text, jsonb)
    IS '公共参数值读取（配置下发）：非敏感单码 JSONB 值，缺失/异常/敏感键返回 p_default（调用方须传缺省值）';

-- 5. 授权（对齐 init 层惯例，新函数默认 PUBLIC EXECUTE 须自收，EXECUTE 授给后端连接角色与全部职责角色）
REVOKE ALL ON FUNCTION um.fn_page_params(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION um.fn_get_param(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION um.fn_upsert_param(text, jsonb, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION um.fn_get_param_value(text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION um.fn_page_params(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION um.fn_get_param(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION um.fn_upsert_param(text, jsonb, uuid) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;
GRANT EXECUTE ON FUNCTION um.fn_get_param_value(text, jsonb) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- 6. 文件末尾自查
SELECT 'um_params_functions' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'um' AND p.proname IN ('fn_page_params','fn_get_param','fn_upsert_param','fn_get_param_value')) AS definer_fn_cnt,
       (SELECT count(*) FROM um.params) AS param_cnt;
