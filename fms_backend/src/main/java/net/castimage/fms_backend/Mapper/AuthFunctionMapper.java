package net.castimage.fms_backend.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.UUID;

/** auth.* / org.* pg 函数调用 Mapper（SECURITY DEFINER 闸口统一出口，Wrapper 无函数调用能力故手写），jwt 密钥传 NULL 由库内 sec.app_secrets 权威 */
public interface AuthFunctionMapper {

    /** jwt 签发（HS256），payload 含 sub/role/exp，密钥传 NULL 走 sec.app_secrets.jwt.secret（与网关 RGW_JWT_SECRET 同源，避免配置漂移） */
    @Select("SELECT auth.jwt_sign(#{payload}::jsonb, NULL)")
    String jwtSign(@Param("payload") String payload);

    /** jwt 验签（HS256 + exp 检查），失败/篡改 RAISE，返回 payload 紧凑 json */
    @Select("SELECT auth.jwt_verify(#{token}, NULL)")
    String jwtVerify(@Param("token") String token);

    /**
     * 03c 登录闸口，匿名 SELECT 因 um.users rls 恒空集，用户查找 + bcrypt 校验全在 pg 内完成，
     * SECURITY DEFINER 属主 fms_admin 绕过 rls，pass_hash 列匿名不可直读，校验不落地 Java。
     *
     * 返回 jsonb，{user_id, ok, status, locked_until, mfa_enabled, must_change_pwd,
     * failed_login_count, company_id}，用户不存在返回 {ok:false,status:'NOT_FOUND'}。
     */
    @Select("""
            SELECT auth.verify_login(#{loginName}, #{password})
            """)
    String verifyLogin(@Param("loginName") String loginName, @Param("password") String password);

    /**
     * 找回密码 email 一致性校验，SECURITY DEFINER 直读加密列，匿名期 rls is_self 恒拒，
     * 直查恒空集，必须经闸口，email 匹配返回用户 id，不匹配/不存在返回 null，防枚举
     */
    @Select("SELECT auth.recovery_lookup(#{loginName}, #{email})")
    UUID recoveryLookup(@Param("loginName") String loginName, @Param("email") String email);

    /**
     * 登录失败计数原子递增，SECURITY DEFINER，2026-08-13 并发安全修复，单条 UPDATE
     * SET failed_login_count = failed_login_count + 1 RETURNING 行级原子自增，行锁串行化并发，
     * 达阈值同事务内置 LOCKED + locked_until，返回 jsonb {failed_login_count, locked}。
     *
     * 取代原 Java 侧读-改-写，并发错误密码丢失更新，暴力破解锁定可被绕过。
     */
    @Select("""
            SELECT auth.record_login_failure(#{userId}::uuid, #{maxFailures}, #{lockMinutes})
            """)
    String recordLoginFailure(@Param("userId") UUID userId,
                              @Param("maxFailures") int maxFailures,
                              @Param("lockMinutes") int lockMinutes);

    /**
     * 登录审计落库，SECURITY DEFINER，匿名/上下文注入前均可调用，登录日志写封闭仅此函数可写
     *
     * @param userId     用户 id（未知用户为 null）
     * @param loginName  登录名
     * @param ip         客户端 ip（已清洗，::inet 可转）
     * @param userAgent  ua
     * @param success    是否成功
     * @param failReason 失败原因，成功为 null
     */
    @Select("""
            SELECT auth.record_login(#{userId}::uuid, #{loginName}, #{ip}::inet, #{userAgent}, #{success}, #{failReason})
            """)
    Object recordLogin(@Param("userId") UUID userId, @Param("loginName") String loginName,
                     @Param("ip") String ip, @Param("userAgent") String userAgent,
                     @Param("success") boolean success, @Param("failReason") String failReason);

    /** 软删除闸口，SECURITY DEFINER 白名单表，数据范围校验后 UPDATE deleted_at=now */
    @Select("SELECT auth.fn_soft_delete(#{schema}, #{table}, #{id}::uuid)")
    boolean fnSoftDelete(@Param("schema") String schema, @Param("table") String table,
                         @Param("id") UUID id);

    /** 匿名流程将当前连接提升为 fms_operator（调用方 finally RESET ROLE，已认证请求由 SandboxSessionInterceptor 注入） */
    @Update("SET ROLE fms_operator")
    void setRoleOperator(); // 登录/找回事务内显式提升，

    /** 复位职责角色，防连接池会话残留，Hikari connection-init-sql RESET ALL 双保险 */
    @Update("RESET ROLE")
    void resetRole(); // 每处 setRoleOperator 的配套复位，

    /** 事务内注入 fms.user_id（本用户行后续 UPDATE 经 is_self 放行 rls） */
    @Select("SELECT set_config('fms.user_id', #{userId}::text, true)")
    String setLocalUserId(@Param("userId") UUID userId);

    // SECURITY DEFINER 闸口，11 契约全量，sql 侧 sql/init/auth_org_functions.sql

    /** 建用户，03c 契约 2，INSERT um.users 幂等，login_name 冲突返回已存在 id */
    @Select("""
            SELECT auth.create_user(#{loginName}, #{passHash}, #{email}, #{companyId}::uuid, #{mustChangePwd}, #{createdBy}::uuid)
            """)
    UUID createUser(@Param("loginName") String loginName, @Param("passHash") String passHash,
                    @Param("email") String email, @Param("companyId") UUID companyId,
                    @Param("mustChangePwd") boolean mustChangePwd, @Param("createdBy") UUID createdBy);

    /** 用户字段补丁（03c），白名单键 email/status/must_change_pwd/failed_login_count/locked_until（pass_hash 等列不可直写） */
    @Select("""
            SELECT auth.update_user(#{userId}::uuid, #{patch}::jsonb)
            """)
    Object updateUser(@Param("userId") UUID userId, @Param("patch") String patch);

    /** 改密/重置密码（03c），UPDATE pass_hash，列级不可直写 */
    @Select("""
            SELECT auth.set_password(#{userId}::uuid, #{bcryptHash})
            """)
    Object setPassword(@Param("userId") UUID userId, @Param("bcryptHash") String bcryptHash);

    /** 启用/禁用 2fa（03c），secret 经函数内 pgp_sym_encrypt 落 mfa_secret_enc */
    @Select("""
            SELECT auth.set_mfa(#{userId}::uuid, convert_to(#{secretBase32}, 'UTF8'), #{enabled})
            """)
    Object setMfa(@Param("userId") UUID userId, @Param("secretBase32") String secretBase32,
                @Param("enabled") boolean enabled);

    /** 读解密后的 totp 密钥（03c），pgp_sym_decrypt 后返回 base32，未配置返回 null */
    @Select("""
            SELECT auth.get_mfa_secret(#{userId}::uuid)
            """)
    String getMfaSecret(@Param("userId") UUID userId);

    /** 分配角色（03c），INSERT um.user_role（uk 冲突 23505 映射 40900） */
    @Select("""
            SELECT auth.assign_role(#{userId}::uuid, #{roleCode})
            """)
    Object assignRole(@Param("userId") UUID userId, @Param("roleCode") String roleCode);

    /** 回收角色（03c），user_role 软删或物理删 */
    @Select("""
            SELECT auth.revoke_role(#{userId}::uuid, #{roleCode})
            """)
    Object revokeRole(@Param("userId") UUID userId, @Param("roleCode") String roleCode);

    /** 角色创建/更新（03c），内部 ON CONFLICT(code) DO UPDATE name */
    @Select("""
            SELECT auth.upsert_role(#{code}, #{name}, #{isSystem})
            """)
    UUID upsertRole(@Param("code") String code, @Param("name") String name,
                    @Param("isSystem") boolean isSystem);

    /** 角色-权限绑定（03c） */
    @Select("""
            SELECT auth.grant_role_permission(#{roleId}::uuid, #{permCode})
            """)
    Object grantRolePermission(@Param("roleId") UUID roleId, @Param("permCode") String permCode);

    /** 角色-权限解绑（03c） */
    @Select("""
            SELECT auth.revoke_role_permission(#{roleId}::uuid, #{permCode})
            """)
    Object revokeRolePermission(@Param("roleId") UUID roleId, @Param("permCode") String permCode);

    /** 公司创建/更新（03c），org.fn_upsert_company 幂等 upsert（新建公司绕过 rls INSERT WITH CHECK 恒 false，属 SECURITY DEFINER 闸口） */
    @Select("""
            SELECT org.fn_upsert_company(#{code}, #{name}, #{currency}, #{fiscalYearStart}, #{status})
            """)
    UUID fnUpsertCompany(@Param("code") String code, @Param("name") String name,
                         @Param("currency") String currency,
                         @Param("fiscalYearStart") LocalDate fiscalYearStart,
                         @Param("status") String status);

    /** 备用码签发，SECURITY DEFINER，init/backup_codes_persist.sql，旧未用码软删失效 + 新码 hmac 盲索引落盘，返回落库数 */
    @Select("""
            SELECT auth.issue_backup_codes(#{userId}::uuid, #{codes}::text[], #{validDays})
            """)
    int issueBackupCodes(@Param("userId") UUID userId, @Param("codes") String[] codes,
                         @Param("validDays") int validDays);

    /** 备用码核销（SECURITY DEFINER），hmac 盲索引等值 + 条件更新 used_at 原子消费，防并发双用，未命中/已用/过期返回 false */
    @Select("""
            SELECT auth.consume_backup_code(#{userId}::uuid, #{code})
            """)
    boolean consumeBackupCode(@Param("userId") UUID userId, @Param("code") String code);
}
