-- FMS 财务系统（init/auth_org_functions），闸口函数，auth/org 敏感写入 SECURITY DEFINER 函数族

-- 内容，decrypt_bank_account，current_user 职责身份守卫 + 密钥读 sec.app_secrets、jwt 委托断言、
--      解密出口授权收口，fms_app 不可执行、闸口守卫，update_user 白名单补 display_name、
--      非本人须 is_admin、record_login_failure 原子化、recovery_lookup/set_password/update_user
--      只读角色补发、pass_hash 列授权 + set_mfa 只读角色补发

-- 缘由，五层授权矩阵下 um 敏感列（pass_hash/mfa_secret_enc/email_encrypted）与 um.roles/user_role/role_perm 写不对
-- operator 开放，列级 SELECT 亦不可见、新公司 INSERT 被 rls WITH CHECK 恒拒，新建 company_id 不在任何 scope，
-- 密码/2fa/建用户/角色管理/建公司必须经本文件闸口函数，以属主 fms_admin 身份绕过调用者权限边界。

-- 范围，13 契约 14 个函数对象（verify_login / record_login_failure / recovery_lookup / create_user /
--       update_user / set_password / set_mfa / get_mfa_secret / assign_role / revoke_role /
--       upsert_role / grant+revoke_role_permission / org.fn_upsert_company）+ cash 扩展 1 个（fn_create_bank_account 密文写入口）。

-- 通用约束，全部 SECURITY DEFINER、SET search_path 收敛，<域>, public, pg_catalog，public 为 pgcrypto 扩展所在地，
--       与 init/um_org_base.sql文件 auth.decrypt_bank_account 的 'auth, public' 同构，去掉 public 则 pgp_sym_encrypt/crypt 运行期不可解析、
--       CREATE OR REPLACE 幂等、RAISE 统一 ERRCODE（业务 P0001 映射 40900、权限 42501 映射 40300），
--       GRANT 具名双通道，fms_app，直连通道+ fms_operator（SET ROLE 通道，AuthServiceImpl 匿名流程先 setRoleOperator），
--       不 GRANT PUBLIC，grants_base 对 auth schema 函数 REVOKE PUBLIC，具名授权不受影响。
SET ROLE fms_admin;

-- 契约 1，auth.verify_login， 登录凭据校验，唯一只读闸口，SECURITY DEFINER 直读 pass_hash 做 bcrypt 比对，
--   不暴露明文、零写入，失败计数/锁定由调用方经 auth.record_login_failure 原子化处理，用户不存在返回 {ok:false,status:'NOT_FOUND'} 不 RAISE 防枚举
CREATE OR REPLACE FUNCTION auth.verify_login(p_login_name text, p_password text)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_user um.users%ROWTYPE;
    v_ok   boolean := false;
BEGIN
    IF p_login_name IS NULL OR p_password IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'status', 'BAD_REQUEST');
    END IF;
    SELECT * INTO v_user FROM um.users
     WHERE login_name = btrim(p_login_name) AND deleted_at IS NULL;
    IF v_user.id IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'status', 'NOT_FOUND');
    END IF;
    -- bcrypt 比对，crypt(明文, 存量哈希) == 存量哈希（pgcrypto，public schema）
    IF v_user.pass_hash IS NOT NULL
       AND v_user.pass_hash = crypt(p_password, v_user.pass_hash) THEN
        v_ok := true;
    END IF;
    RETURN jsonb_build_object(
        'user_id', v_user.id, 'ok', v_ok, 'status', v_user.status,
        'locked_until', v_user.locked_until, 'mfa_enabled', v_user.mfa_enabled,
        'must_change_pwd', v_user.must_change_pwd,
        'failed_login_count', v_user.failed_login_count,
        'company_id', v_user.company_id);
END
$$;
COMMENT ON FUNCTION auth.verify_login(text,text)
    IS '登录凭据校验（内部 crypt BCrypt 比对；只读不写，失败计数/锁定由调用方经 update_user 处理）；用户不存在返回 {ok:false,status:NOT_FOUND} 不 RAISE（防枚举）';
GRANT EXECUTE ON FUNCTION auth.verify_login(text,text) TO fms_app, fms_operator;

-- 契约 1b，auth.record_login_failure， 登录失败计数原子递增

--   单条 UPDATE ... SET failed_login_count = failed_login_count + 1 RETURNING 行级原子自增，
--   UPDATE 行锁串行化并发写，防读-改-写丢失更新绕过暴力破解锁定，达阈值同事务内置
--   LOCKED + locked_until，阈值判断基于原子递增后的最新计数，返回 {failed_login_count,
--   locked}，SECURITY DEFINER 属主 fms_admin 绕过 rls，与 update_user 同构，匿名登录期
--   current_uid 为 NULL 时 is_self 守卫放行。
CREATE OR REPLACE FUNCTION auth.record_login_failure(
    p_user_id uuid,
    p_max_failures int,
    p_lock_minutes int)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_count  int;
    v_locked boolean := false;
BEGIN
    IF p_user_id IS NULL OR p_max_failures IS NULL OR p_max_failures <= 0 THEN
        RAISE EXCEPTION '参数非法' USING ERRCODE = 'P0001';
    END IF;
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权更新他人账号' USING ERRCODE = '42501';
    END IF;
    -- 原子递增，UPDATE 行锁串行化并发写，RETURNING 取最新计数，杜绝读-改-写丢失更新
    UPDATE um.users
       SET failed_login_count = failed_login_count + 1
     WHERE id = p_user_id AND deleted_at IS NULL
    RETURNING failed_login_count INTO v_count;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
    -- 达阈值，同事务内置 LOCKED + 锁定截止，基于原子递增后的最新计数，并发下不再低估
    IF v_count >= p_max_failures THEN
        UPDATE um.users
           SET status = 'LOCKED',
               locked_until = now() + make_interval(mins => p_lock_minutes)
         WHERE id = p_user_id AND deleted_at IS NULL;
        v_locked := true;
    END IF;
    RETURN jsonb_build_object('failed_login_count', v_count, 'locked', v_locked);
END
$$;
COMMENT ON FUNCTION auth.record_login_failure(uuid,int,int)
    IS '登录失败计数原子递增（行级自增串行化并发，达阈值置 LOCKED+locked_until）；防并发读-改-写丢失更新绕过暴力破解锁定';
GRANT EXECUTE ON FUNCTION auth.record_login_failure(uuid,int,int) TO fms_app, fms_operator;

-- 契约 2，auth.create_user， 建用户（INSERT um.users 唯一入口），pass_hash 为 Java 侧现成 bcrypt 哈希，防双哈希，
--   幂等，login_name 冲突返回已存在 id，email 联动 pgp_sym_encrypt 列加密 + hmac 盲索引，防持库离线爆破
CREATE OR REPLACE FUNCTION auth.create_user(
    p_login_name text, p_pass_hash text, p_email text,
    p_company_id uuid, p_must_change_pwd boolean, p_created_by uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_id    uuid;
    v_login text := btrim(p_login_name);
    v_key   text;
    v_blind text;
    v_enc   bytea;
    v_hash  char(64);
BEGIN
    IF v_login = '' THEN
        RAISE EXCEPTION '登录名不能为空' USING ERRCODE = 'P0001';
    END IF;
    IF p_pass_hash IS NULL OR length(p_pass_hash) < 20 THEN
        RAISE EXCEPTION '密码哈希非法' USING ERRCODE = 'P0001';
    END IF;
    IF p_company_id IS NULL THEN
        RAISE EXCEPTION '公司不能为空' USING ERRCODE = 'P0001';
    END IF;
    -- 幂等，登录名已存在，含软删前，返回已存在 id
    SELECT id INTO v_id FROM um.users
     WHERE login_name = v_login AND deleted_at IS NULL;
    IF v_id IS NOT NULL THEN
        RETURN v_id;
    END IF;
    SELECT value->>'v' INTO v_key   FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    SELECT value->>'v' INTO v_blind FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key';
    IF p_email IS NOT NULL AND p_email <> '' THEN
        v_enc  := pgp_sym_encrypt(p_email, v_key);
        v_hash := encode(hmac(p_email, v_blind, 'sha256'), 'hex')::char(64);
    END IF;
    INSERT INTO um.users
        (company_id, login_name, login_name_hash, pass_hash, display_name,
         email_encrypted, email_hash, status, must_change_pwd)
    VALUES
        (p_company_id, v_login, encode(digest(v_login, 'sha256'), 'hex')::char(64),
         p_pass_hash, v_login, v_enc, v_hash, 'ACTIVE', COALESCE(p_must_change_pwd, false))
    RETURNING id INTO v_id;
    RETURN v_id;
END
$$;
COMMENT ON FUNCTION auth.create_user(text,text,text,uuid,boolean,uuid)
    IS '建用户（幂等：login_name 冲突返回已存在 id）；email 走 pgp_sym_encrypt 列加密 + hmac 盲索引；pass_hash 为 BCrypt 现成哈希（不二次哈希）';
GRANT EXECUTE ON FUNCTION auth.create_user(text,text,text,uuid,boolean,uuid)
    TO fms_operator;

-- 契约 3，auth.update_user， 用户字段补丁更新，白名单键 email/status/must_change_pwd/failed_login_count/locked_until/last_login_at/display_name
--   键名白名单化不拼用户输入，值经 format %L 绑定，非白名单键 RAISE P0001，契约，拒绝不静默，email 联动重加密+盲索引重算，空补丁静默，用户不存在 RAISE
CREATE OR REPLACE FUNCTION auth.update_user(p_user_id uuid, p_patch jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_key   text;
    v_blind text;
    v_email text;
    v_sets  text[] := '{}';
    v_bad   text;
BEGIN
    IF p_user_id IS NULL OR p_patch IS NULL OR p_patch = '{}'::jsonb THEN
        RETURN;                                        -- 空补丁静默
    END IF;
    -- 白名单键校验（契约，非白名单键拒绝 RAISE，不得静默忽略，防调用方误以为字段已生效），
    -- 白名单 email/status/must_change_pwd/failed_login_count/locked_until/last_login_at，其余键一律 RAISE P0001
    FOR v_bad IN SELECT jsonb_object_keys(p_patch) LOOP
        IF v_bad NOT IN ('email','status','must_change_pwd','failed_login_count',
                         'locked_until','last_login_at') THEN
            RAISE EXCEPTION '非法字段 %（白名单：email/status/must_change_pwd/failed_login_count/locked_until/last_login_at）', v_bad
                USING ERRCODE = 'P0001';
        END IF;
    END LOOP;
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权更新他人账号' USING ERRCODE = '42501';
    END IF;
    IF p_patch ? 'email' THEN
        v_email := NULLIF(btrim(COALESCE(p_patch->>'email', '')), '');
        SELECT value->>'v' INTO v_key   FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
        SELECT value->>'v' INTO v_blind FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key';
        IF v_email IS NULL THEN
            v_sets := v_sets || 'email_encrypted = NULL'::text;
            v_sets := v_sets || 'email_hash = NULL'::text;
        ELSE
            v_sets := v_sets || format('email_encrypted = %L',
                                       pgp_sym_encrypt(v_email, v_key));
            v_sets := v_sets || format('email_hash = %L',
                                       encode(hmac(v_email, v_blind, 'sha256'), 'hex'));
        END IF;
    END IF;
    IF p_patch ? 'status' THEN
        v_sets := v_sets || format('status = %L', p_patch->>'status');
    END IF;
    IF p_patch ? 'must_change_pwd' THEN
        v_sets := v_sets || format('must_change_pwd = %L',
                                   (p_patch->>'must_change_pwd')::boolean);
    END IF;
    IF p_patch ? 'failed_login_count' THEN
        v_sets := v_sets || format('failed_login_count = %L',
                                   (p_patch->>'failed_login_count')::int);
    END IF;
    IF p_patch ? 'locked_until' THEN
        IF p_patch->>'locked_until' IS NULL THEN
            v_sets := v_sets || 'locked_until = NULL'::text;
        ELSE
            v_sets := v_sets || format('locked_until = %L::timestamptz',
                                       p_patch->>'locked_until');
        END IF;
    END IF;
    IF p_patch ? 'last_login_at' THEN
        IF p_patch->>'last_login_at' IS NULL THEN
            v_sets := v_sets || 'last_login_at = NULL'::text;
        ELSE
            v_sets := v_sets || format('last_login_at = %L::timestamptz',
                                       p_patch->>'last_login_at');
        END IF;
    END IF;
    IF cardinality(v_sets) = 0 THEN
        RETURN;
    END IF;
    EXECUTE 'UPDATE um.users SET ' || array_to_string(v_sets, ', ')
         || ' WHERE id = $1 AND deleted_at IS NULL'
        USING p_user_id;
    IF NOT FOUND THEN
        -- PG18 下 EXECUTE 的 no-op UPDATE，新值==现值 ROW_COUNT=0，FOUND 不可作存在性判据，
        -- 以独立 SELECT 复核行存在性，仅真不存在才 RAISE，防 set_password 后置位 must_change_pwd 误报
        PERFORM 1 FROM um.users WHERE id = p_user_id AND deleted_at IS NULL;
        IF NOT FOUND THEN
            RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
        END IF;
    END IF;
END
$$;
COMMENT ON FUNCTION auth.update_user(uuid,jsonb)
    IS '用户字段补丁（白名单 email/status/must_change_pwd/failed_login_count/locked_until/last_login_at，非白名单键 RAISE P0001 拒绝）；email 联动重加密+盲索引；键名白名单化不拼用户输入';
-- 自助改密清 must_change_pwd 复用同一闸口，本用户 is_self 或系统管理员可写，授只读角色供首登改密
GRANT EXECUTE ON FUNCTION auth.update_user(uuid,jsonb) TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- 契约 4，auth.set_password， 改密/重置，pass_hash 列级不可直写，唯一写入口，must_change_pwd 由调用方按场景经 update_user 置位
CREATE OR REPLACE FUNCTION auth.set_password(p_user_id uuid, p_bcrypt_hash text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    -- 身份闸口，current_uid 非空且非本人则 42501 拒绝，防越权改他人密码，未注入上下文为维护会话（fms_admin 通道）
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权修改他人密码' USING ERRCODE = '42501';
    END IF;
    IF p_bcrypt_hash IS NULL OR length(p_bcrypt_hash) < 20 THEN
        RAISE EXCEPTION '密码哈希非法' USING ERRCODE = 'P0001';
    END IF;
    UPDATE um.users
       SET pass_hash = p_bcrypt_hash
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
END
$$;
COMMENT ON FUNCTION auth.set_password(uuid,text)
    IS '改密/重置（pass_hash 列级不可直写）；must_change_pwd 由调用方按场景经 update_user 置位';
-- 自助改密（POST /api/auth/change-password）对所有已登录角色开放，函数体内含身份闸口
-- （current_uid 非空且非本人则 42501），已登录会话只能改自己密码，授只读角色无越权面
GRANT EXECUTE ON FUNCTION auth.set_password(uuid,text) TO fms_operator, fms_viewer, fms_auditor, fms_report;

-- pgcrypto 兼容重载，部署库适配，契约 5 set_mfa 以 bytea totp 种子 + text 主密钥调用
-- pgp_sym_encrypt，但部署库 pgcrypto 的 bytea 加密变体注册名为 pgp_sym_encrypt_bytea(bytea,text)，
-- 缺 pgp_sym_encrypt(bytea,text) 重载则 42883 function does not exist。此处幂等补建该重载，
--   存在 pgp_sym_encrypt_bytea(bytea,text)（定制 pgcrypto）则委托之（与 pgp_sym_decrypt(bytea,text) 对称），
--   标准 pgcrypto 环境则回退 text 重载，convert_from utf8 无损往返，db 编码为 utf8。
-- CREATE OR REPLACE 幂等，GRANT EXECUTE 具名双通道与 init/gl_core_functions.sql 同构。
DO $pgp_fix$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc p
                WHERE p.proname = 'pgp_sym_encrypt_bytea' AND p.pronargs = 2
                  AND p.pronamespace = 'public'::regnamespace) THEN
        CREATE OR REPLACE FUNCTION public.pgp_sym_encrypt(p_data bytea, p_key text)
        RETURNS bytea
        LANGUAGE sql
        IMMUTABLE
        STRICT
        AS $fn$ SELECT public.pgp_sym_encrypt_bytea(p_data, p_key) $fn$;
    ELSE
        CREATE OR REPLACE FUNCTION public.pgp_sym_encrypt(p_data bytea, p_key text)
        RETURNS bytea
        LANGUAGE sql
        IMMUTABLE
        STRICT
        AS $fn$ SELECT public.pgp_sym_encrypt(convert_from(p_data, 'UTF8'), p_key) $fn$;
    END IF;
END
$pgp_fix$;
COMMENT ON FUNCTION public.pgp_sym_encrypt(bytea,text)
    IS 'pgcrypto 兼容重载（bytea 数据 + text 主密钥）：部署库委托 pgp_sym_encrypt_bytea，标准环境回退 text 重载（UTF8 无损）';
GRANT EXECUTE ON FUNCTION public.pgp_sym_encrypt(bytea,text) TO fms_app, fms_operator, fms_viewer, fms_auditor, fms_report;

-- 契约 5，auth.set_mfa， 启用/禁用 2fa，mfa_secret_enc 列级不可直写，启用时 pgp_sym_encrypt 落密文，禁用时清空并复位开关
CREATE OR REPLACE FUNCTION auth.set_mfa(p_user_id uuid, p_secret bytea, p_enabled boolean)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_key text;
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权操作他人 2FA' USING ERRCODE = '42501';
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    IF COALESCE(p_enabled, false) THEN
        IF p_secret IS NULL OR octet_length(p_secret) = 0 THEN
            RAISE EXCEPTION 'TOTP 密钥为空' USING ERRCODE = 'P0001';
        END IF;
        UPDATE um.users
           SET mfa_enabled = true,
               mfa_secret_enc = pgp_sym_encrypt(p_secret, v_key)
         WHERE id = p_user_id AND deleted_at IS NULL;
    ELSE
        UPDATE um.users
           SET mfa_enabled = false,
               mfa_secret_enc = NULL
         WHERE id = p_user_id AND deleted_at IS NULL;
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
END
$$;
COMMENT ON FUNCTION auth.set_mfa(uuid,bytea,boolean)
    IS '启用/禁用 2FA（mfa_secret_enc 列级不可直写；pgp_sym_encrypt aes256 主密钥 sec.app_secrets.pgcrypto.master_key）';
GRANT EXECUTE ON FUNCTION auth.set_mfa(uuid,bytea,boolean) TO fms_operator;

-- 契约 6，auth.get_mfa_secret， totp 校验读密钥，解密唯一出口，仅本人或维护会话可调，越权 42501，未启用 2fa 返回 NULL
CREATE OR REPLACE FUNCTION auth.get_mfa_secret(p_user_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_secret bytea;
    v_key    text;
    v_uid    uuid := auth.current_uid();
BEGIN
    IF v_uid IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权读取他人 TOTP 密钥' USING ERRCODE = '42501';
    END IF;
    SELECT mfa_secret_enc INTO v_secret
      FROM um.users WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_secret IS NULL THEN
        RETURN NULL;                                 -- 未启用 2fa
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    -- bytea 配对解密，set_mfa 落库密文由 pgp_sym_encrypt_bytea（bytea 数据变体）产出，
    -- 对应解密用 pgp_sym_decrypt_bytea + convert_from（实测 text 变体 pgp_sym_decrypt 对
    -- bytea 密文报 39000 'Not text data'，convert_from(text,..) 亦 42883）
    RETURN convert_from(pgp_sym_decrypt_bytea(v_secret, v_key), 'UTF8');  -- base32 明文
END
$$;
COMMENT ON FUNCTION auth.get_mfa_secret(uuid)
    IS 'TOTP 校验读密钥（pgp_sym_decrypt_bytea + convert_from UTF8 返回 base32，与 set_mfa 的 bytea 加密配对；仅本人/维护会话可调，越权 42501）';
GRANT EXECUTE ON FUNCTION auth.get_mfa_secret(uuid) TO fms_operator;

-- 契约 7/8，auth.assign_role / auth.revoke_role， 角色分配/回收（um.user_role 写唯一入口），
--   assign_role 公司一致性 + ON CONFLICT 幂等重授，revoke_role 软删幂等，未命中静默
CREATE OR REPLACE FUNCTION auth.assign_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_role_id   uuid;
    v_user_comp uuid;
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权为他人分配角色' USING ERRCODE = '42501';
    END IF;
    SELECT id INTO v_role_id FROM um.roles
     WHERE code = btrim(p_role_code) AND deleted_at IS NULL;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION '角色不存在：%', p_role_code USING ERRCODE = 'P0001';
    END IF;
    SELECT company_id INTO v_user_comp FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_user_comp IS NULL THEN
        RAISE EXCEPTION '用户不存在' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO um.user_role (company_id, user_id, role_id, granted_by)
    VALUES (v_user_comp, p_user_id, v_role_id, auth.current_uid())
    ON CONFLICT (user_id, role_id) DO UPDATE SET deleted_at = NULL, updated_at = now();       -- 幂等重授（uk_user_role 兜底）
END
$$;

CREATE OR REPLACE FUNCTION auth.revoke_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权回收他人角色' USING ERRCODE = '42501';
    END IF;
    UPDATE um.user_role ur
       SET deleted_at = now()
      FROM um.roles r
     WHERE ur.role_id = r.id
       AND r.code = btrim(p_role_code)
       AND ur.user_id = p_user_id
       AND ur.deleted_at IS NULL;
END
$$;

GRANT EXECUTE ON FUNCTION auth.assign_role(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role(uuid,text) TO fms_operator;

-- 契约 9，auth.upsert_role， 角色创建/更新（um.roles 写唯一入口），company_id 取调用者公司，维护会话取最早公司，种子兼容，
--   uk_roles_code 幂等 upsert
CREATE OR REPLACE FUNCTION auth.upsert_role(p_code text, p_name text, p_is_system boolean)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, org, public, pg_catalog
AS $$
DECLARE
    v_id   uuid;
    v_comp uuid;
BEGIN
    IF btrim(p_code) = '' OR btrim(p_name) = '' THEN
        RAISE EXCEPTION '角色编码/名称不能为空' USING ERRCODE = 'P0001';
    END IF;
    v_comp := (SELECT company_id FROM um.users WHERE id = auth.current_uid() AND deleted_at IS NULL);
    IF v_comp IS NULL THEN
        v_comp := (SELECT id FROM org.companies WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1);
    END IF;
    INSERT INTO um.roles (company_id, code, name, is_system)
    VALUES (v_comp, btrim(p_code), btrim(p_name), COALESCE(p_is_system, false))
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, is_system = EXCLUDED.is_system
    RETURNING id INTO v_id;
    RETURN v_id;
END
$$;
COMMENT ON FUNCTION auth.upsert_role(text,text,boolean)
    IS '角色创建/更新（uk_roles_code 幂等 upsert；is_system 由调用方预检禁改/禁删）';
GRANT EXECUTE ON FUNCTION auth.upsert_role(text,text,boolean) TO fms_operator;

-- 契约 10，auth.grant_role_permission / auth.revoke_role_permission， 角色-权限绑定/解绑（um.role_perm 写唯一入口），
--   grant 权限点存在性校验+幂等，revoke 软删幂等，未命中静默
CREATE OR REPLACE FUNCTION auth.grant_role_permission(p_role_id uuid, p_perm_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_perm_id   uuid;
    v_role_comp uuid;
BEGIN
    SELECT id INTO v_perm_id FROM um.permissions
     WHERE code = btrim(p_perm_code) AND deleted_at IS NULL;
    IF v_perm_id IS NULL THEN
        RAISE EXCEPTION '权限点不存在：%', p_perm_code USING ERRCODE = 'P0001';
    END IF;
    SELECT company_id INTO v_role_comp FROM um.roles
     WHERE id = p_role_id AND deleted_at IS NULL;
    IF v_role_comp IS NULL THEN
        RAISE EXCEPTION '角色不存在' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO um.role_perm (company_id, role_id, perm_id)
    VALUES (v_role_comp, p_role_id, v_perm_id)
    ON CONFLICT (role_id, perm_id) DO UPDATE SET deleted_at = NULL, updated_at = now();       -- 幂等（uk_role_perm 兜底）
END
$$;

CREATE OR REPLACE FUNCTION auth.revoke_role_permission(p_role_id uuid, p_perm_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    UPDATE um.role_perm rp
       SET deleted_at = now()
      FROM um.permissions p
     WHERE rp.perm_id = p.id
       AND p.code = btrim(p_perm_code)
       AND rp.role_id = p_role_id
       AND rp.deleted_at IS NULL;
END
$$;

GRANT EXECUTE ON FUNCTION auth.grant_role_permission(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role_permission(uuid,text) TO fms_operator;

-- 契约 11，org.fn_upsert_company， 公司创建/更新（org.companies 写唯一入口），新建公司无 scope 直插必被 rls WITH CHECK 拒，
--   故 SECURITY DEFINER 绕过 + company_id 自引用回填（uk_companies_code 幂等 upsert）
CREATE OR REPLACE FUNCTION org.fn_upsert_company(
    p_code text, p_name text, p_currency text,
    p_fiscal_year_start date, p_status text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = org, public, pg_catalog
AS $$
DECLARE
    v_id uuid;
BEGIN
    IF btrim(p_code) = '' OR btrim(p_name) = '' THEN
        RAISE EXCEPTION '公司编码/名称不能为空' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO org.companies (code, name, currency, fiscal_year_start, status)
    VALUES (btrim(p_code), btrim(p_name),
            COALESCE(upper(btrim(p_currency)), 'CNY'),
            p_fiscal_year_start, COALESCE(p_status, 'ACTIVE'))
    ON CONFLICT (code) DO UPDATE
        SET name = EXCLUDED.name,
            currency = EXCLUDED.currency,
            fiscal_year_start = EXCLUDED.fiscal_year_start,
            status = EXCLUDED.status
    RETURNING id INTO v_id;
    -- 自引用回填，新建公司无任何 scope，can_access_org(company_id) 需 company_id=self.id 才可见
    UPDATE org.companies SET company_id = v_id
     WHERE id = v_id AND company_id IS NULL;
    RETURN v_id;
END
$$;
COMMENT ON FUNCTION org.fn_upsert_company(text,text,text,date,text)
    IS '公司创建/更新（幂等 upsert；SECURITY DEFINER 绕过新建公司 RLS WITH CHECK；company_id 自引用回填）';
GRANT EXECUTE ON FUNCTION org.fn_upsert_company(text,text,text,date,text)
    TO fms_operator;

-- 扩展，cash.fn_create_bank_account， 银行账户密文写入口，account_no_enc/account_no_hash 不授予任何角色且 Java 无 pgp 能力，
--   新建账户须经本 SECURITY DEFINER 入口（与 auth.decrypt_bank_account 对偶），account_no_hash=sha256 防重，
--   账户 UPDATE，冻结/关闭，只动非敏感列，operator 可直写
CREATE OR REPLACE FUNCTION cash.fn_create_bank_account(
    p_bank_name text, p_account_no text, p_account_type text,
    p_currency text, p_status text, p_is_active boolean)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = cash, um, public, pg_catalog
AS $$
DECLARE
    v_id  uuid;
    v_key text;
    v_comp uuid;
BEGIN
    IF btrim(p_account_no) = '' THEN
        RAISE EXCEPTION '银行账号不能为空' USING ERRCODE = 'P0001';
    END IF;
    v_comp := (SELECT company_id FROM um.users WHERE id = auth.current_uid() AND deleted_at IS NULL);
    IF v_comp IS NULL THEN
        RAISE EXCEPTION '无法确定公司（未注入用户上下文）' USING ERRCODE = '42501';
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    INSERT INTO cash.bank_accounts
        (company_id, bank_name, account_no_enc, account_no_hash, account_type,
         currency, is_active, status)
    VALUES
        (v_comp, btrim(p_bank_name),
         pgp_sym_encrypt(btrim(p_account_no), v_key),
         encode(digest(btrim(p_account_no), 'sha256'), 'hex')::char(64),
         COALESCE(p_account_type, 'CORPORATE'),
         COALESCE(upper(btrim(p_currency)), 'CNY'),
         COALESCE(p_is_active, true),
         COALESCE(p_status, 'ACTIVE'))
    RETURNING id INTO v_id;
    RETURN v_id;
END
$$;
COMMENT ON FUNCTION cash.fn_create_bank_account(text,text,text,text,text,boolean)
    IS '新建银行账户（列加密写入口：pgp_sym_encrypt 密文 + sha256 盲索引；uk_bank_account_hash 防重；需注入用户上下文确定公司）';
GRANT EXECUTE ON FUNCTION cash.fn_create_bank_account(text,text,text,text,text,boolean)
    TO fms_operator;

-- 契约 init/um_params_functions.sql，auth.recovery_lookup， 找回密码 email 一致性校验，唯一只读闸口，匿名期 rls is_self 恒拒、
-- Java 侧 selectOne 恒空集，recovery 链路无法经行策略读取加密列，必须经本闸口绕过行策略直读，
-- email 不匹配/用户不存在一律返回 NULL，防枚举语义与 verify_login 一致，防枚举票据由调用方构造
CREATE OR REPLACE FUNCTION auth.recovery_lookup(p_login_name text, p_email text)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_key  text;
    v_enc  bytea;
    v_id   uuid;
BEGIN
    IF p_login_name IS NULL OR p_email IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    SELECT id, email_encrypted INTO v_id, v_enc FROM um.users
     WHERE login_name = btrim(p_login_name) AND deleted_at IS NULL;
    IF v_id IS NULL OR v_enc IS NULL THEN
        RETURN NULL;
    END IF;
    BEGIN
        IF pgp_sym_decrypt(v_enc, v_key) = btrim(p_email) THEN
            RETURN v_id;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RETURN NULL; -- 密文异常按不匹配处理，不暴露差异
    END;
    RETURN NULL;
END
$$;
COMMENT ON FUNCTION auth.recovery_lookup(text,text)
    IS '找回密码 email 一致性校验（内部解密比对；不匹配/不存在返回 NULL 防枚举）';
GRANT EXECUTE ON FUNCTION auth.recovery_lookup(text,text) TO fms_app, fms_operator, fms_report, fms_viewer, fms_auditor;

-- ACL 兜底，CREATE OR REPLACE 会把函数 ACL 重置为默认 PUBLIC EXECUTE，grants_base 的 REVOKE 在增量重放 init/auth_org_functions.sql 时不重跑，
-- 故本文件内自收 PUBLIC，确保闸口函数仅具名角色可调（proacl 无 {=X/fms_admin} 残留）
REVOKE EXECUTE ON FUNCTION auth.verify_login(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.record_login_failure(uuid,int,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.create_user(text,text,text,uuid,boolean,uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.update_user(uuid,jsonb) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.set_password(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.set_mfa(uuid,bytea,boolean) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.get_mfa_secret(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.assign_role(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.revoke_role(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.upsert_role(text,text,boolean) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.grant_role_permission(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.revoke_role_permission(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION org.fn_upsert_company(text,text,text,date,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION cash.fn_create_bank_account(text,text,text,text,text,boolean) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.recovery_lookup(text,text) FROM PUBLIC;

RESET ROLE;

-- 2.2 auth.decrypt_bank_account，current_user 职责身份守卫，仅 fms_admin/资金职责角色可解，+ 密钥读 sec.app_secrets
CREATE OR REPLACE FUNCTION auth.decrypt_bank_account(p_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, pg_catalog, public
AS $$
DECLARE
    v_key text;
    v_no  text;
BEGIN
    IF current_user NOT IN ('fms_admin', 'fms_operator') THEN
        RAISE EXCEPTION 'decrypt_bank_account: 无权限（仅 fms_admin/资金职责角色）';
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets
     WHERE code = 'pgcrypto.master_key';
    IF v_key IS NULL THEN
        RAISE EXCEPTION 'decrypt_bank_account: pgcrypto.master_key 未配置（sec.app_secrets）';
    END IF;
    -- 显式 cipher-algo=aes256，解密选项是提示，存量 aes128 密文照常可解
    SELECT pgp_sym_decrypt(account_no_enc, v_key, 'cipher-algo=aes256') INTO v_no
      FROM cash.bank_accounts
     WHERE id = p_id AND deleted_at IS NULL;
    IF v_no IS NULL THEN
        RAISE EXCEPTION 'decrypt_bank_account: 银行账户 % 不存在', p_id;
    END IF;
    RETURN v_no;
END $$;

-- 2.3 jwt 委托断言，标准 jwt 签发/验签由 pgjwt 扩展统一实现，部署后断言
-- auth.jwt_sign 函数体委托 pgjwt.sign，无原生实现覆盖，断言不通过仅 WARNING，不阻断 apply 链
DO $$
DECLARE
    v_def text;
BEGIN
    SELECT pg_get_functiondef('auth.jwt_sign(jsonb,text)'::regprocedure) INTO v_def;
    IF v_def IS NULL THEN
        RAISE WARNING 'security_hardening_fix: auth.jwt_sign 缺失（init/um_org_base.sql 未执行？），JWT 委托断言未验证';
    ELSIF v_def LIKE '%pgjwt.sign%' THEN
        RAISE NOTICE 'security_hardening_fix: PASS——auth.jwt_sign 委托 pgjwt.sign（无原生实现覆盖）';
    ELSE
        RAISE WARNING 'security_hardening_fix: FAIL——auth.jwt_sign 函数体不含 pgjwt.sign（被原生实现覆盖？），JWT 委托链路受损';
    END IF;
END $$;

-- 2.4 解密出口授权收口，fms_app 不可执行
REVOKE EXECUTE ON FUNCTION auth.decrypt_bank_account(uuid) FROM PUBLIC, fms_app;
GRANT EXECUTE ON FUNCTION auth.decrypt_bank_account(uuid) TO fms_operator;

-- 2.5 用户邮箱解密出口，本人或系统管理员可读，其余返回 NULL
-- 返回 NULL 不抛错，因本出口被用户列表逐行调用，越权行抛错会使整表查询失败，且 NULL 不泄露存在性
-- 邮箱以 pgp_sym_encrypt(aes256) 加密存储，等值查询走 email_hash 盲索引，明文只经本出口返回
CREATE OR REPLACE FUNCTION auth.get_user_email(p_user_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = auth, um, sec, pg_catalog, public
AS $$
DECLARE
    v_key  text;
    v_enc  bytea;
BEGIN
    IF p_user_id IS NULL THEN
        RETURN NULL;
    END IF;
    IF NOT (auth.is_self(p_user_id) OR auth.is_admin()) THEN
        RETURN NULL;
    END IF;
    SELECT email_encrypted INTO v_enc FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_enc IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets
     WHERE code = 'pgcrypto.master_key';
    IF v_key IS NULL THEN
        RAISE EXCEPTION 'get_user_email: pgcrypto.master_key 未配置（sec.app_secrets）';
    END IF;
    RETURN pgp_sym_decrypt(v_enc, v_key, 'cipher-algo=aes256');
END $$;
COMMENT ON FUNCTION auth.get_user_email(uuid)
    IS '用户邮箱读取：本人或系统管理员可读，其余返回 NULL（不泄露存在性、不阻断列表查询）；密文经 pgp_sym_encrypt(aes256) 存储，明文仅经本出口返回';
REVOKE EXECUTE ON FUNCTION auth.get_user_email(uuid) FROM PUBLIC, fms_app;
GRANT EXECUTE ON FUNCTION auth.get_user_email(uuid)
    TO fms_operator, fms_viewer, fms_auditor, fms_report;

--闸口守卫，管理员（is_admin）可管理他人，非管理员仍仅本人，42501

--    update_user 白名单含 display_name

-- 契约 3，auth.update_user， 用户字段补丁更新
CREATE OR REPLACE FUNCTION auth.update_user(p_user_id uuid, p_patch jsonb)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_key   text;
    v_blind text;
    v_email text;
    v_sets  text[] := '{}';
    v_bad   text;
BEGIN
    IF p_user_id IS NULL OR p_patch IS NULL OR p_patch = '{}'::jsonb THEN
        RETURN;                                        -- 空补丁静默
    END IF;
    -- 白名单键校验，契约，非白名单键拒绝 RAISE，不得静默忽略，
    -- email/status/must_change_pwd/failed_login_count/locked_until/last_login_at/display_name
    FOR v_bad IN SELECT jsonb_object_keys(p_patch) LOOP
        IF v_bad NOT IN ('email','status','must_change_pwd','failed_login_count',
                         'locked_until','last_login_at','display_name') THEN
            RAISE EXCEPTION '非法字段 %（白名单：email/status/must_change_pwd/failed_login_count/locked_until/last_login_at/display_name）', v_bad
                USING ERRCODE = 'P0001';
        END IF;
    END LOOP;
    -- 身份闸口，非本人须系统管理员（is_admin），匿名维护会话（current_uid 空）放行
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权更新他人账号' USING ERRCODE = '42501';
    END IF;
    IF p_patch ? 'display_name' THEN
        v_sets := v_sets || format('display_name = %L', btrim(COALESCE(p_patch->>'display_name', '')));
    END IF;
    IF p_patch ? 'email' THEN
        v_email := NULLIF(btrim(COALESCE(p_patch->>'email', '')), '');
        SELECT value->>'v' INTO v_key   FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
        SELECT value->>'v' INTO v_blind FROM sec.app_secrets WHERE code = 'pgcrypto.blind_key';
        IF v_email IS NULL THEN
            v_sets := v_sets || 'email_encrypted = NULL'::text;
            v_sets := v_sets || 'email_hash = NULL'::text;
        ELSE
            v_sets := v_sets || format('email_encrypted = %L',
                                       pgp_sym_encrypt(v_email, v_key));
            v_sets := v_sets || format('email_hash = %L',
                                       encode(hmac(v_email, v_blind, 'sha256'), 'hex'));
        END IF;
    END IF;
    IF p_patch ? 'status' THEN
        v_sets := v_sets || format('status = %L', p_patch->>'status');
    END IF;
    IF p_patch ? 'must_change_pwd' THEN
        v_sets := v_sets || format('must_change_pwd = %L',
                                   (p_patch->>'must_change_pwd')::boolean);
    END IF;
    IF p_patch ? 'failed_login_count' THEN
        v_sets := v_sets || format('failed_login_count = %L',
                                   (p_patch->>'failed_login_count')::int);
    END IF;
    IF p_patch ? 'locked_until' THEN
        IF p_patch->>'locked_until' IS NULL THEN
            v_sets := v_sets || 'locked_until = NULL'::text;
        ELSE
            v_sets := v_sets || format('locked_until = %L::timestamptz',
                                       p_patch->>'locked_until');
        END IF;
    END IF;
    IF p_patch ? 'last_login_at' THEN
        IF p_patch->>'last_login_at' IS NULL THEN
            v_sets := v_sets || 'last_login_at = NULL'::text;
        ELSE
            v_sets := v_sets || format('last_login_at = %L::timestamptz',
                                       p_patch->>'last_login_at');
        END IF;
    END IF;
    IF cardinality(v_sets) = 0 THEN
        RETURN;
    END IF;
    EXECUTE 'UPDATE um.users SET ' || array_to_string(v_sets, ', ')
         || ' WHERE id = $1 AND deleted_at IS NULL'
        USING p_user_id;
    IF NOT FOUND THEN
        PERFORM 1 FROM um.users WHERE id = p_user_id AND deleted_at IS NULL;
        IF NOT FOUND THEN
            RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
        END IF;
    END IF;
END
$$;
COMMENT ON FUNCTION auth.update_user(uuid,jsonb)
    IS '用户字段补丁（白名单 email/status/must_change_pwd/failed_login_count/locked_until/last_login_at/display_name，非白名单键 RAISE P0001 拒绝）；email 联动重加密+盲索引；非本人须系统管理员（42501）';
REVOKE EXECUTE ON FUNCTION auth.update_user(uuid,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.update_user(uuid,jsonb) TO fms_operator;

-- 契约 4，auth.set_password， 改密/重置，管理员可重置他人密码，检查 7/8
CREATE OR REPLACE FUNCTION auth.set_password(p_user_id uuid, p_bcrypt_hash text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    -- 身份闸口，非本人须系统管理员（is_admin），未注入上下文为维护会话（fms_admin 通道）
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权修改他人密码' USING ERRCODE = '42501';
    END IF;
    IF p_bcrypt_hash IS NULL OR length(p_bcrypt_hash) < 20 THEN
        RAISE EXCEPTION '密码哈希非法' USING ERRCODE = 'P0001';
    END IF;
    UPDATE um.users
       SET pass_hash = p_bcrypt_hash
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
END
$$;
COMMENT ON FUNCTION auth.set_password(uuid,text)
    IS '改密/重置（pass_hash 列级不可直写）；非本人须系统管理员（42501）';
REVOKE EXECUTE ON FUNCTION auth.set_password(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.set_password(uuid,text) TO fms_operator;

-- 契约 5，auth.set_mfa， 启用/禁用 2fa
CREATE OR REPLACE FUNCTION auth.set_mfa(p_user_id uuid, p_secret bytea, p_enabled boolean)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_key text;
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权操作他人 2FA' USING ERRCODE = '42501';
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    IF COALESCE(p_enabled, false) THEN
        IF p_secret IS NULL OR octet_length(p_secret) = 0 THEN
            RAISE EXCEPTION 'TOTP 密钥为空' USING ERRCODE = 'P0001';
        END IF;
        UPDATE um.users
           SET mfa_enabled = true,
               mfa_secret_enc = pgp_sym_encrypt(p_secret, v_key)
         WHERE id = p_user_id AND deleted_at IS NULL;
    ELSE
        UPDATE um.users
           SET mfa_enabled = false,
               mfa_secret_enc = NULL
         WHERE id = p_user_id AND deleted_at IS NULL;
    END IF;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
END
$$;
COMMENT ON FUNCTION auth.set_mfa(uuid,bytea,boolean)
    IS '启用/禁用 2FA（mfa_secret_enc 列级不可直写）；非本人须系统管理员（42501）';
REVOKE EXECUTE ON FUNCTION auth.set_mfa(uuid,bytea,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.set_mfa(uuid,bytea,boolean) TO fms_operator;

-- 契约 6，auth.get_mfa_secret， totp 校验读密钥
CREATE OR REPLACE FUNCTION auth.get_mfa_secret(p_user_id uuid)
RETURNS text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_secret bytea;
    v_key    text;
    v_uid    uuid := auth.current_uid();
BEGIN
    IF v_uid IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权读取他人 TOTP 密钥' USING ERRCODE = '42501';
    END IF;
    SELECT mfa_secret_enc INTO v_secret
      FROM um.users WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_secret IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT value->>'v' INTO v_key FROM sec.app_secrets WHERE code = 'pgcrypto.master_key';
    RETURN convert_from(pgp_sym_decrypt_bytea(v_secret, v_key), 'UTF8');
END
$$;
COMMENT ON FUNCTION auth.get_mfa_secret(uuid)
    IS 'TOTP 校验读密钥（pgp_sym_decrypt_bytea + convert_from UTF8 返回 base32）；非本人须系统管理员（42501）';
REVOKE EXECUTE ON FUNCTION auth.get_mfa_secret(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.get_mfa_secret(uuid) TO fms_operator;

-- 契约 7，auth.assign_role， 角色分配（检查 init/um_params_functions.sql）
CREATE OR REPLACE FUNCTION auth.assign_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_role_id   uuid;
    v_user_comp uuid;
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权为他人分配角色' USING ERRCODE = '42501';
    END IF;
    SELECT id INTO v_role_id FROM um.roles
     WHERE code = btrim(p_role_code) AND deleted_at IS NULL;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION '角色不存在：%', p_role_code USING ERRCODE = 'P0001';
    END IF;
    SELECT company_id INTO v_user_comp FROM um.users
     WHERE id = p_user_id AND deleted_at IS NULL;
    IF v_user_comp IS NULL THEN
        RAISE EXCEPTION '用户不存在' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO um.user_role (company_id, user_id, role_id, granted_by)
    VALUES (v_user_comp, p_user_id, v_role_id, auth.current_uid())
    ON CONFLICT (user_id, role_id) DO UPDATE SET deleted_at = NULL, updated_at = now();
END
$$;

-- 契约 8，auth.revoke_role， 角色回收
CREATE OR REPLACE FUNCTION auth.revoke_role(p_user_id uuid, p_role_code text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
BEGIN
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) AND NOT auth.is_admin() THEN
        RAISE EXCEPTION '无权回收他人角色' USING ERRCODE = '42501';
    END IF;
    UPDATE um.user_role ur
       SET deleted_at = now()
      FROM um.roles r
     WHERE ur.role_id = r.id
       AND r.code = btrim(p_role_code)
       AND ur.user_id = p_user_id
       AND ur.deleted_at IS NULL;
END
$$;

REVOKE EXECUTE ON FUNCTION auth.assign_role(uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION auth.revoke_role(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.assign_role(uuid,text) TO fms_operator;
GRANT EXECUTE ON FUNCTION auth.revoke_role(uuid,text) TO fms_operator;

-- record_login_failure 原子化，Java 侧读-改-写计数累加，无行锁，在并发错误密码下
-- 丢失更新，暴力破解锁定可被并行批量尝试绕过，本函数单条 UPDATE 行级原子自增，
-- 达阈值同事务内置 LOCKED + locked_until，返回 {failed_login_count, locked}。

-- 幂等，CREATE OR REPLACE FUNCTION + GRANT/REVOKE 可重复执行。

CREATE OR REPLACE FUNCTION auth.record_login_failure(
    p_user_id uuid,
    p_max_failures int,
    p_lock_minutes int)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = um, public, pg_catalog
AS $$
DECLARE
    v_count  int;
    v_locked boolean := false;
BEGIN
    IF p_user_id IS NULL OR p_max_failures IS NULL OR p_max_failures <= 0 THEN
        RAISE EXCEPTION '参数非法' USING ERRCODE = 'P0001';
    END IF;
    IF auth.current_uid() IS NOT NULL AND NOT auth.is_self(p_user_id) THEN
        RAISE EXCEPTION '无权更新他人账号' USING ERRCODE = '42501';
    END IF;
    -- 原子递增，UPDATE 行锁串行化并发写，RETURNING 取最新计数，杜绝读-改-写丢失更新
    UPDATE um.users
       SET failed_login_count = failed_login_count + 1
     WHERE id = p_user_id AND deleted_at IS NULL
    RETURNING failed_login_count INTO v_count;
    IF NOT FOUND THEN
        RAISE EXCEPTION '用户不存在或已删除' USING ERRCODE = 'P0001';
    END IF;
    -- 达阈值，同事务内置 LOCKED + 锁定截止，基于原子递增后的最新计数，并发下不再低估
    IF v_count >= p_max_failures THEN
        UPDATE um.users
           SET status = 'LOCKED',
               locked_until = now() + make_interval(mins => p_lock_minutes)
         WHERE id = p_user_id AND deleted_at IS NULL;
        v_locked := true;
    END IF;
    RETURN jsonb_build_object('failed_login_count', v_count, 'locked', v_locked);
END
$$;
COMMENT ON FUNCTION auth.record_login_failure(uuid,int,int)
    IS '登录失败计数原子递增（行级自增串行化并发，达阈值置 LOCKED+locked_until）；防并发读-改-写丢失更新绕过暴力破解锁定';
GRANT EXECUTE ON FUNCTION auth.record_login_failure(uuid,int,int) TO fms_app, fms_operator;
REVOKE EXECUTE ON FUNCTION auth.record_login_failure(uuid,int,int) FROM PUBLIC;

-- 自查，函数存在 + fms_app 具名 EXECUTE + PUBLIC EXECUTE 清零
SELECT 'auth_login_failure_fix' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth' AND p.proname = 'record_login_failure') AS func_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth' AND p.proname = 'record_login_failure'
           AND has_function_privilege('fms_app', p.oid, 'EXECUTE')) AS app_exec_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'auth' AND p.proname = 'record_login_failure'
           AND p.proacl IS NOT NULL
           AND EXISTS (SELECT 1 FROM unnest(p.proacl) a
                        WHERE a = ('=X/' || pg_catalog.pg_get_userbyid(p.proowner))::aclitem)) AS public_exec_cnt;

-- recovery_lookup EXECUTE 补发只读角色，POST /api/auth/recovery 携带 Bearer token 时
-- 后端按 jwt 角色建立 db 会话（report01 对应 fms_report 等），其调用 auth.recovery_lookup
-- 须有 EXECUTE。

-- 安全语义，recovery_lookup 为 SECURITY DEFINER 只读闸口，
-- 仅当 login_name+email 匹配（pgp_sym_decrypt 比对）才返回用户 id，不匹配/不存在返回 NULL，
-- 与 fms_app 既有授权同构，findBack 凭票据复位密码仍走 auth.set_password（fms_app 专用），无新增泄露面。
SET ROLE fms_admin;

GRANT EXECUTE ON FUNCTION auth.recovery_lookup(text, text) TO fms_report, fms_viewer, fms_auditor;

RESET ROLE;

-- 自查，三只读角色 EXECUTE 到位
SET ROLE fms_admin;
SELECT 'auth_recovery_perm_fix' AS file_name,
       r.rolname,
       has_function_privilege(r.rolname, 'auth.recovery_lookup(text,text)', 'EXECUTE') AS can_exec
  FROM pg_roles r
 WHERE r.rolname IN ('fms_report','fms_viewer','fms_auditor')
 ORDER BY 2;
RESET ROLE;

-- set_password/update_user EXECUTE 补发只读角色（record_login 授权 init/fill_missing.sql 内联同构），
-- POST /api/auth/change-password 为已认证端点，已登录会话经 SandboxSessionInterceptor
-- 按 jwt 角色 SET ROLE（viewer01 对应 fms_viewer 等），调用 auth.set_password / auth.update_user
-- 须有 EXECUTE，自助改密对所有角色开放。安全语义不变，
-- auth.set_password，函数体内含身份闸口（current_uid 非空且非本人则 42501 拒绝），
--     已登录会话只能改自己的密码，与 fms_app/fms_operator 既有授权同构，
-- auth.update_user，白名单键补丁（email/status/must_change_pwd/failed_login_count/
--     locked_until/last_login_at），自助改密路径仅清 must_change_pwd，
-- auth.record_login，SECURITY DEFINER 登录审计落库，要求改密事件记
--     um.login_log（fail_reason='password_changed'），缺授权时审计静默丢失（仅 WARN）。
SET ROLE fms_admin;

GRANT EXECUTE ON FUNCTION auth.set_password(uuid, text) TO fms_report, fms_viewer, fms_auditor;
GRANT EXECUTE ON FUNCTION auth.update_user(uuid, jsonb) TO fms_report, fms_viewer, fms_auditor;
-- 注，auth.record_login 的 EXECUTE 授权由 init/fill_missing.sql 内联完成
-- （REVOKE PUBLIC + GRANT fms_app/fms_operator/fms_viewer/fms_auditor/fms_report），
-- 本文件不并入（执行时该函数尚未创建）

RESET ROLE;

-- 自查，三只读角色 EXECUTE 到位
SET ROLE fms_admin;
SELECT 'auth_change_password_perm_fix' AS file_name,
       r.rolname,
       has_function_privilege(r.rolname, 'auth.set_password(uuid,text)', 'EXECUTE')      AS can_exec_set_password,
       has_function_privilege(r.rolname, 'auth.update_user(uuid,jsonb)', 'EXECUTE')      AS can_exec_update_user
       -- record_login 授权自查省略，该函数定义于 init/fill_missing.sql，本文件之后，
       -- 此处引用会因函数不存在而报错（实测 init 失败点），授权已在 fill_missing 内联
  FROM pg_roles r
 WHERE r.rolname IN ('fms_report','fms_viewer','fms_auditor')
 ORDER BY 2;
RESET ROLE;

-- pass_hash 列授权 + set_mfa EXECUTE 补发只读角色，
-- totp setup/enable/disable 等 2fa 自服务端点经 UserMapper.selectById 读本人行
-- （含 pass_hash 做 bcrypt 校验），um.users 的 pass_hash 列授权覆盖
-- viewer/auditor/report 角色（SET ROLE fms_viewer 后 selectById 不可达）。
-- 安全语义不变，rls users_select 仅 is_self 可见本人行，非管理员，后端 selectById
-- 恒以请求者本人 id 查询，pass_hash 仅本人自校验（与 fms_operator 的授权同构，无新增泄露面）。
SET ROLE fms_admin;

GRANT SELECT (pass_hash) ON um.users TO fms_viewer, fms_auditor, fms_report;

-- 2fa 自服务闸口 EXECUTE，totp enable/disable 经 auth.set_mfa 写本人 mfa_secret_enc
-- （闸口内 is_self/is_admin 守卫，只读角色仅可操作本人 2fa，无越权面）
GRANT EXECUTE ON FUNCTION auth.set_mfa(uuid, bytea, boolean) TO fms_viewer, fms_auditor, fms_report;

RESET ROLE;

-- 自查
SET ROLE fms_admin;
SELECT 'um_totp_grants_fix' AS file_name,
       (SELECT count(*) FROM information_schema.column_privileges
         WHERE table_schema='um' AND table_name='users' AND column_name='pass_hash'
           AND grantee IN ('fms_viewer','fms_auditor','fms_report')) AS pass_hash_grant_cnt;
RESET ROLE;

-- 文件末尾自查，15 个函数对象存在（含契约 12 recovery_lookup）+ fms_app 具名 EXECUTE 到位 + PUBLIC EXECUTE 清零
SELECT 'auth_org_functions' AS file_name,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname IN ('auth','org','cash')
           AND p.proname IN ('verify_login','record_login_failure','create_user','update_user',
                             'set_password','set_mfa','get_mfa_secret','assign_role','revoke_role',
                             'upsert_role','grant_role_permission','revoke_role_permission',
                             'fn_upsert_company','fn_create_bank_account')) AS func_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname IN ('auth','org','cash')
           AND p.proname IN ('verify_login','record_login_failure','create_user','update_user',
                             'set_password','set_mfa','get_mfa_secret','assign_role','revoke_role',
                             'upsert_role','grant_role_permission','revoke_role_permission',
                             'fn_upsert_company','fn_create_bank_account')
           AND has_function_privilege('fms_app', p.oid, 'EXECUTE'))  AS app_exec_cnt,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname IN ('auth','org','cash')
           AND p.proname IN ('verify_login','record_login_failure','create_user','update_user',
                             'set_password','set_mfa','get_mfa_secret','assign_role','revoke_role',
                             'upsert_role','grant_role_permission','revoke_role_permission',
                             'fn_upsert_company','fn_create_bank_account')
           AND p.proacl IS NOT NULL
           AND EXISTS (SELECT 1 FROM unnest(p.proacl) a
                        WHERE a = ('=X/' || pg_catalog.pg_get_userbyid(p.proowner))::aclitem))
           AS public_exec_cnt;
