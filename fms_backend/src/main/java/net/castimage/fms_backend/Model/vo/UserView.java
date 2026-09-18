package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户视图（字段白名单：永不含 pass_hash/mfa_secret_enc/phone/id_card 等敏感列；
 * email 在 um.users 中为密文列，此处由 auth.get_user_email 闸口解出，
 * 系统管理员得全部、其余人仅得本人，无权与未设邮箱同为 null）。
 */
public record UserView(UUID id, String loginName, String displayName, String status,
                       Boolean mfaEnabled, Boolean mustChangePwd,
                       LocalDateTime lastLoginAt, UUID companyId, String email,
                       List<String> roles) { // join um.user_role，
}
