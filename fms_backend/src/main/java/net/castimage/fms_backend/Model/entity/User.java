package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户实体（um.users）：敏感/加密列（pass_hash 除外）不映射——列级盲索引与密文由 SECURITY DEFINER 闸口及登录流程列白名单查询处理，本实体不承载，防意外响应泄露；pass_hash 仅用于 BCrypt 校验，任何视图 DTO 均不输出。
 */
@TableName(schema = "um", value = "users")
@Data
public class User {

    /** 主键（uuidv7，Java 侧 UuidV7 显式生成或 PG DEFAULT） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 登录名（唯一） */
    private String loginName;

    /** 显示名 */
    private String displayName;

    /** BCrypt 口令哈希（敏感：仅登录校验读取，绝不出现在视图） */
    private String passHash;

    /** 状态：ACTIVE/LOCKED/DISABLED */
    private String status;

    /** 下次登录须改密 */
    private Boolean mustChangePwd;

    /** 连续失败次数（暴力锁定计数） */
    private Integer failedLoginCount;

    /** 锁定到期时间（NULL=未锁定） */
    private LocalDateTime lockedUntil;

    /** MFA 启用开关（TOTP 二次验证） */
    private Boolean mfaEnabled;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 创建时间 */
    private LocalDateTime createdAt; // 审计时间戳，

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间（RLS 恒过滤 deleted_at IS NULL），




























}
