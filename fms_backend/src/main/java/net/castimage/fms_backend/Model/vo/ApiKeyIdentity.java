package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * API-Key 校验通过后的身份凭据（X6-2）：user_id 为密钥所属用户，scope 决定注入角色
 * （read 映射 fms_viewer、read_write 映射 fms_operator）；身份仅来自 um.api_keys 校验结果，
 * 不得采信任何 X-FMS-* 头（网关 API-Key 模式不注入身份头，签名身份段为空串）。
 */
public record ApiKeyIdentity(UUID userId, String scope) {
}
