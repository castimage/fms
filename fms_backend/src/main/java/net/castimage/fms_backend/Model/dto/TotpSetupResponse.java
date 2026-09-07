package net.castimage.fms_backend.Model.dto;

import java.time.LocalDateTime;

/**
 * TOTP setup 响应：密钥仅本次返回（enable 才落库，未启用前密钥仅存内存 10min）。
 */
public record TotpSetupResponse(
        /** Base32 密钥（扫码/手动录入用） */
        String secretBase32,
        /** otpauth URI（otpauth://totp/FMS:loginName?...） */
        String otpauthUrl,
        /** 密钥在服务端内存的有效期 */
        LocalDateTime expiresAt) { // 未启用前仅存内存 10min，
}
