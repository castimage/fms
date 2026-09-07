package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API 密钥实体（um.api_keys）：key_hash 为带密钥 HMAC 盲索引（明文仅签发时一次返回），签发/吊销经 SECURITY DEFINER auth.issue_api_key。
 */
@TableName(schema = "um", value = "api_keys")
@Data
public class ApiKey {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 所属用户 */
    private UUID userId;

    /** 密钥名称 */
    private String name;

    /** 明文前缀（fms_ 前 8 位，仅展示） */
    private String keyPrefix;

    /** 盲索引哈希（SHA256/HMAC） */
    private String keyHash;

    /** 作用域：read/read_write */
    private String scope;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 吊销时间戳（status=REVOKED 时写入） */
    private LocalDateTime revokedAt;

    /** 最近使用时间 */
    private LocalDateTime lastUsedAt;

    /** 状态：ACTIVE/DISABLED/REVOKED */
    private String status;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
