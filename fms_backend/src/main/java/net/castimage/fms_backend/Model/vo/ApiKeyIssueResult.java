package net.castimage.fms_backend.Model.vo;

/**
 * API 密钥签发结果（POST /api/admin/api-keys）：明文仅此一次返回（库内只存 HMAC 盲索引），
 * keyPrefix 为明文前 8 位（fms_xxxx）供列表展示复用。
 */
public record ApiKeyIssueResult(String apiKey, String keyPrefix) {
}
