package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API 密钥视图（GET /api/admin/api-keys 列表）：key_prefix 仅展示用（fms_ 前 8 位），
 * key_hash 盲索引恒不返回；scope 为 read/read_write，status 为 ACTIVE/DISABLED/REVOKED。
 */
public record ApiKeyView(UUID id, String name, String keyPrefix, String scope,
                         LocalDateTime expiresAt, LocalDateTime revokedAt,
                         LocalDateTime lastUsedAt, String status, LocalDateTime createdAt) {
}
