-- FMS 财务系统， api-key 后端校验收敛(系统间集成）

-- 网关无密钥库不校验（auth.rs 透传 + 剥离 X-FMS-* + 不注入身份头 + 签名身份段
-- 空串），校验收敛在后端，um.api_keys.key_hash 为 hmac(key, blind_key) 盲索引
-- （init/fill_missing.sql），而 sec.app_secrets 不对任何角色授权（um_org_base），
-- Java 侧无法直读 blind_key，故盲索引计算 + 等值查 + last_used_at 回写必须
-- 收敛于 SECURITY DEFINER 函数，以 fms_admin 身份读密钥，明文永不离开库内。

-- 网关中 'key_hash=sha256(key)' 为 sha256 表述，本部署实现为 hmac 盲索引
-- （防字典攻击），故校验必须在库内完成。
-- 幂等，全部 CREATE OR REPLACE，可重复执行。

SET ROLE fms_admin;

-- api-key 校验 + last_used_at 回写，单次往返原子完成

-- 任何失败（哈希未命中/非 ACTIVE/已吊销/已过期/已软删/空串）一律返回 NULL，
-- 由后端过滤器按 40100 fail-closed，不向调用方泄露失败原因，防枚举。
CREATE OR REPLACE FUNCTION auth.api_key_validate(p_key text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_hash text;
    v_row  um.api_keys%ROWTYPE;
BEGIN
    IF p_key IS NULL OR btrim(p_key) = '' THEN
        RETURN NULL;
    END IF;
    -- 盲索引，按 sec.app_secrets['pgcrypto.blind_key'] 计算 hex(hmac(key, blind_key, 'sha256'))
    v_hash := encode(hmac(btrim(p_key),
                          (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'),
                          'sha256'), 'hex');
    SELECT * INTO v_row FROM um.api_keys WHERE key_hash = v_hash AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RETURN NULL;                        -- 哈希未命中，含已软删则 40100
    END IF;
    -- status='ACTIVE' 且未吊销且未过期（expires_at 空 = 永不过期）
    IF v_row.status <> 'ACTIVE' OR v_row.revoked_at IS NOT NULL
       OR (v_row.expires_at IS NOT NULL AND v_row.expires_at <= now()) THEN
        RETURN NULL;
    END IF;
    -- 回写 last_used_at，校验成功使用才记
    UPDATE um.api_keys SET last_used_at = now() WHERE id = v_row.id;
    RETURN jsonb_build_object('user_id', v_row.user_id::text, 'scope', v_row.scope);
END $$;
COMMENT ON FUNCTION auth.api_key_validate(text)
    IS 'API-Key 后端校验（SECURITY DEFINER）：hmac(key, blind_key) 盲索引等值查 um.api_keys.key_hash，ACTIVE 且未过期才回写 last_used_at 并返回 {user_id, scope}，任何失败返回 NULL（后端 40100 fail-closed）';
REVOKE ALL ON FUNCTION auth.api_key_validate(text) FROM PUBLIC;
-- 校验发生在身份注入之前（RequestContext 尚未建立），连接可能残留上一请求的
-- SET ROLE 职责角色，连接池会话级状态，SandboxSessionInterceptor 此时跳过注入，
-- 故五个职责角色 + fms_app 全部授予 EXECUTE，函数为 SECURITY DEFINER（属主 fms_admin），
-- 调用方仅需 EXECUTE 权限，内部权限与 rls 由属主身份决定，不存在提权面。
GRANT EXECUTE ON FUNCTION auth.api_key_validate(text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- 吊销（软删除语义，revoked_at + status=REVOKED，行保留与 uk_api_keys_hash 唯一约束共存）

-- 仅本人可吊销，维护会话 fms_admin/postgres 可代吊销，重复吊销幂等返回。
CREATE OR REPLACE FUNCTION auth.revoke_api_key(p_key_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_user_id uuid;
    v_status  text;
BEGIN
    IF p_key_id IS NULL THEN
        RAISE EXCEPTION 'revoke_api_key: key_id 必填';
    END IF;
    SELECT user_id, status INTO v_user_id, v_status
      FROM um.api_keys
     WHERE id = p_key_id AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'revoke_api_key: 密钥不存在';
    END IF;
    IF NOT auth.is_self(v_user_id) AND session_user NOT IN ('fms_admin', 'postgres') THEN
        RAISE EXCEPTION 'revoke_api_key: 仅可吊销自己的密钥';
    END IF;
    IF v_status = 'REVOKED' THEN
        RETURN;                             -- 幂等，已吊销直接返回
    END IF;
    UPDATE um.api_keys
       SET status = 'REVOKED', revoked_at = now(), updated_at = now()
     WHERE id = p_key_id;
END $$;
COMMENT ON FUNCTION auth.revoke_api_key(uuid)
    IS 'API-Key 吊销（SECURITY DEFINER）：仅本人可吊销，status=REVOKED + revoked_at 软删除语义，行保留不破坏盲索引唯一约束；重复吊销幂等返回';
REVOKE ALL ON FUNCTION auth.revoke_api_key(uuid) FROM PUBLIC;
-- 同 api_key_validate，连接池会话级角色残留可能落在任意职责角色上，统一授 EXECUTE，函数内 is_self 按 guc 判定归属，SECURITY DEFINER 属主权限封闭
GRANT EXECUTE ON FUNCTION auth.revoke_api_key(uuid) TO fms_operator, fms_viewer, fms_auditor, fms_report;

RESET ROLE;

-- 文件末尾自查（纯逻辑命名约定，`SELECT '<名>' AS file_name` 与文件名一致，配合 psql -v ON_ERROR_STOP=1 确认本文件执行完整）
SELECT 'auth_api_key_validate' AS file_name;
