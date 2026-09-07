package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 用户视图（字段白名单：永不含 pass_hash/mfa_secret_enc/phone/id_card 等敏感列；
 * email 为加密存储本批不输出）。
 */
public record UserView(UUID id, String loginName, String displayName, String status,
                       Boolean mfaEnabled, Boolean mustChangePwd,
                       LocalDateTime lastLoginAt, UUID companyId,
                       List<String> roles) { // join um.user_role，
}
