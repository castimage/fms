package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.ApiKey;
import net.castimage.fms_backend.Model.vo.ApiKeyView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API 密钥 Mapper（um.api_keys）：key_hash 为带密钥 HMAC 盲索引（init/fill_missing.sql），
 * 而 sec.app_secrets 不对任何角色授权（um_org_base），Java 无法直读 blind_key，
 * 故校验/签发/吊销全部收敛于 SECURITY DEFINER 函数闸口（auth.api_key_validate /
 * auth.issue_api_key / auth.revoke_api_key，以 fms_admin 身份读密钥 + 绕 RLS）。
 */
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    /**
     * API-Key 校验（auth.api_key_validate SECURITY DEFINER，init/auth_api_key_validate.sql）：
     * 库内按 sec.app_secrets['pgcrypto.blind_key'] 计算 hex(hmac(key, blind_key, 'sha256'))
     * 等值查 um.api_keys.key_hash，status='ACTIVE' 且未过期才回写 last_used_at 并返回
     * jsonb {user_id, scope}；任何失败（哈希未命中/非 ACTIVE/吊销/过期/软删）返回 NULL——
     * 过滤器按 40100 fail-closed，不泄露失败原因。
     *
     * @param apiKey X-API-Key 头原值（非空）
     * @return jsonb 文本 {user_id, scope}，失败为 null
     */
    @Select("SELECT auth.api_key_validate(#{apiKey})")
    String validate(@Param("apiKey") String apiKey);

    /**
     * 签发（auth.issue_api_key SECURITY DEFINER，仅本人/维护会话）：'fms_' + 32B hex
     * 随机明文，库内只存 HMAC 盲索引，明文仅本次调用返回一次。
     *
     * @param userId    所属用户（当前认证用户，函数内 auth.is_self 校验）
     * @param name      密钥名称（≤64 字符）
     * @param scope     read / read_write
     * @param expiresAt 过期时间（null = 永不过期）
     * @return 明文密钥（fms_ 开头），签发失败抛异常
     */
    @Select("SELECT auth.issue_api_key(#{userId}::uuid, #{name}, #{scope}, #{expiresAt, jdbcType=TIMESTAMP})")
    String issue(@Param("userId") UUID userId, @Param("name") String name,
                 @Param("scope") String scope, @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 吊销（auth.revoke_api_key SECURITY DEFINER，仅本人）：status=REVOKED + revoked_at
     * 软删除语义，行保留与盲索引唯一约束共存；重复吊销幂等返回。
     */
    @Select("SELECT auth.revoke_api_key(#{keyId}::uuid)")
    Object revoke(@Param("keyId") UUID keyId);

    /**
     * 本人密钥列表（deleted_at IS NULL；RLS is_self 过滤 + user_id 显式条件双保险，
     * key_hash 盲索引恒不返回）。供 /api/admin/api-keys 列表展示；列集与
     * ApiKeyView record 组件一一对应（构造器自动映射，多列不参与）。
     */
    @Select("""
            SELECT id, name, key_prefix, scope, expires_at,
                   revoked_at, last_used_at, status, created_at
              FROM um.api_keys
             WHERE deleted_at IS NULL AND user_id = #{userId}
             ORDER BY created_at DESC
            """)
    List<ApiKeyView> listByUser(@Param("userId") UUID userId);
}
