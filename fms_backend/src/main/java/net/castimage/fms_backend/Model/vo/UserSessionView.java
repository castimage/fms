package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户会话视图（登录响应/GET /api/auth/session）：字段白名单永不含 pass_hash/mfa_secret_enc/phone/id_card 等敏感列（列级无权限 + 响应层双保险）。
 * email 在库内为密文列，明文只经 auth.get_user_email 闸口解出，本视图不携带该字段，会话态不回传邮箱。
 */
public record UserSessionView(UUID id, String loginName, String displayName, String role, // JWT role 短名，
                              Boolean mfaEnabled, Boolean mustChangePwd,
                              LocalDateTime lastLoginAt, UUID companyId) {
}
