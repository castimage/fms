-- FMS 2fa 备用恢复码持久化（执行库，fms），
-- um.backup_codes（备用码盲索引落盘，code_hash = hmac(明文, blind_key)，库内不存明文，

--    与 um.api_keys 同模式），② auth.issue_backup_codes，签发，先软删旧未用码再批量插新行，
--    返回落库数，③ auth.consume_backup_code，核销，hmac 等值 + 条件更新 used_at 原子消费，
--    防并发双用。

-- 动机，恢复码需持久落盘：会话存储（CachePort，ttl 30min）下恢复码随 ttl/实例重启
-- 丢失，用户未及时抄录即失效，核销无持久记录，无法审计"何时用哪个码恢复过"。db 落盘
-- 后有效期由 valid_days 控制（Java 侧 fms.mfa.backup-code-valid-days，默认 30 天）。

-- 生成/核销审计（um.login_log fail_reason='backup_codes_generated'/'backup_code_used'）
-- 由 Java 侧 auth.record_login 完成，本文件不含。
-- 全部 DDL 幂等。

SET ROLE fms_admin;

-- 备用码表，盲索引，明文仅签发时一次返回给 Java
CREATE TABLE IF NOT EXISTS um.backup_codes (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    user_id     uuid         NOT NULL REFERENCES um.users(id),
    code_hash   char(64)     NOT NULL,               -- 盲索引，hmac(code, blind_key, 'sha256')，等值查询不泄露明文
    expires_at  timestamptz  NOT NULL,               -- 有效期（签发时 = now + valid_days）
    used_at     timestamptz,                          -- 核销时间（NULL=未用）
    created_at  timestamptz  NOT NULL DEFAULT now(),
    deleted_at  timestamptz,                          -- 软删，重新签发时旧未用码全部失效
    CONSTRAINT uk_backup_codes_hash UNIQUE (code_hash)
);
-- 分区表不使用部分索引，本表无分区，过期清理，过期行保留供审计，容量可控，每人 10 码/月
CREATE INDEX IF NOT EXISTS ix_backup_codes_user   ON um.backup_codes (user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_backup_codes_expiry ON um.backup_codes (expires_at)
    WHERE used_at IS NULL AND deleted_at IS NULL;

COMMENT ON TABLE um.backup_codes IS
'2FA 备用恢复码（盲索引落盘，库内不存明文）：签发/核销均经 auth.* SECURITY DEFINER 闸口；核销留 used_at 审计';
COMMENT ON COLUMN um.backup_codes.code_hash IS '盲索引：encode(hmac(code, blind_key, sha256), hex)——纯 digest 无防字典攻击能力，与 um.api_keys 同模式';
COMMENT ON COLUMN um.backup_codes.used_at IS '核销时间（NULL=未用）；条件更新 used_at 原子消费防并发双用';

-- 签发闸口（SECURITY DEFINER），先软删旧未用码，重新签发 = 旧码全部失效，再批量插入
-- 新码盲索引，明文仅经函数体/事务内处理，不进日志
CREATE OR REPLACE FUNCTION auth.issue_backup_codes(
    p_user_id    uuid,
    p_codes      text[],
    p_valid_days int)
RETURNS int
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_inserted int := 0;
    v_code     text;
BEGIN
    IF p_user_id IS NULL OR p_codes IS NULL OR array_length(p_codes, 1) = 0 THEN
        RAISE EXCEPTION 'issue_backup_codes: user_id 与 codes 必填';
    END IF;
    UPDATE um.backup_codes
       SET deleted_at = now()
     WHERE user_id = p_user_id AND used_at IS NULL AND deleted_at IS NULL;
    FOREACH v_code IN ARRAY p_codes LOOP
        IF btrim(v_code) = '' THEN
            CONTINUE;
        END IF;
        INSERT INTO um.backup_codes (user_id, code_hash, expires_at)
        VALUES (p_user_id,
                encode(hmac(v_code,
                            (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'),
                            'sha256'), 'hex'),
                now() + make_interval(days => p_valid_days));
        v_inserted := v_inserted + 1;
    END LOOP;
    RETURN v_inserted;
END $$;
COMMENT ON FUNCTION auth.issue_backup_codes(uuid, text[], int)
    IS '备用码签发（SECURITY DEFINER）：旧未用码软删失效 + 新码 hmac 盲索引落盘；返回落库数';
REVOKE ALL ON FUNCTION auth.issue_backup_codes(uuid, text[], int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.issue_backup_codes(uuid, text[], int) TO fms_app, fms_operator;

-- 核销闸口，SECURITY DEFINER，匿名 2fa 验证期调用，此时无沙箱上下文，rls 不可用，
-- hmac 等值 + 条件更新 used_at（WHERE used_at IS NULL 防并发双用），命中返回 true
CREATE OR REPLACE FUNCTION auth.consume_backup_code(
    p_user_id uuid,
    p_code    text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = auth, public
AS $$
DECLARE
    v_hash text;
BEGIN
    IF p_user_id IS NULL OR NULLIF(btrim(p_code), '') IS NULL THEN
        RETURN false;
    END IF;
    v_hash := encode(hmac(p_code,
                          (SELECT value->>'v' FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key'),
                          'sha256'), 'hex');
    UPDATE um.backup_codes
       SET used_at = now()
     WHERE user_id = p_user_id
       AND code_hash = v_hash
       AND used_at IS NULL
       AND expires_at > now()
       AND deleted_at IS NULL;
    RETURN FOUND;
END $$;
COMMENT ON FUNCTION auth.consume_backup_code(uuid, text)
    IS '备用码核销（SECURITY DEFINER）：hmac 盲索引等值 + 条件更新 used_at 原子消费（防并发双用）；未命中/已用/过期返回 false';
REVOKE ALL ON FUNCTION auth.consume_backup_code(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.consume_backup_code(uuid, text) TO fms_app, fms_operator;

-- rls，本人可见自己未删行，fms_admin 全见，写无策略，签发/核销经 SECURITY DEFINER 函数独占
ALTER TABLE um.backup_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE um.backup_codes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS backup_codes_select ON um.backup_codes;
CREATE POLICY backup_codes_select ON um.backup_codes
    FOR SELECT USING (deleted_at IS NULL
                      AND (auth.is_self(user_id) OR current_user = 'fms_admin'));
GRANT SELECT ON um.backup_codes TO fms_operator;

-- 文件末尾自查（配合 psql -v ON_ERROR_STOP=1 确认执行完整），期望 table_cnt=1、fn_cnt=2
SELECT 'backup_codes_persist' AS file_name,
       (SELECT count(*) FROM pg_tables WHERE schemaname = 'um' AND tablename = 'backup_codes') AS table_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth'
           AND p.proname IN ('issue_backup_codes', 'consume_backup_code')) AS fn_cnt;
