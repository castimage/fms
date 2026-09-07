package net.castimage.fms_backend.Model.dto;

import java.time.LocalDateTime;

/**
 * 密码找回响应：一次性重置票据（30min，CachePort 会话存储）。邮件链路已闭环——
 * 命中真实用户且邮件启用时同步发送重置邮件（发送失败仅 WARN 不影响票据返回），
 * 未命中/未启用同样返回票据占位（防用户名探测，响应口径一致）。
 */
public record RecoveryResponse(
        /** 一次性重置票据 */
        String ticket,
        /** 票据有效期 */
        LocalDateTime expiresAt) {
}
