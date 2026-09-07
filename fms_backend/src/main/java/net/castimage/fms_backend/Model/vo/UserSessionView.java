package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户会话视图（登录响应/GET /api/auth/session）：字段白名单永不含 pass_hash/mfa_secret_enc/phone/id_card 等敏感列（列级无权限 + 响应层双保险）；email 为加密存储，明文需 SECURITY DEFINER 解密闸口，本批不输出。
 */
public record UserSessionView(UUID id, String loginName, String displayName, String role, // JWT role 短名，
                              Boolean mfaEnabled, Boolean mustChangePwd,
                              LocalDateTime lastLoginAt, UUID companyId) {
}
