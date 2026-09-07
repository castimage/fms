package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录日志实体（um.login_log，仅追加只读）：按月分区父表（partman 管理），登录尝试记录
 * （含失败原因 fail_reason / 锁定到期 locked_until）；RLS 策略 is_self OR has_role('fms_auditor')
 * OR has_permission('audit:read:all')（admin 经 RBAC 权限点放行全量，operator/viewer/report
 * 仅本人）——写入封闭（应用无 INSERT 权限 + FORCE RLS），本实体只读。
 */
@TableName(schema = "um", value = "login_log")
@Data
public class LoginLog {

    /** 主键（uuidv7，分区父表 PK 含分区键 occurred_at） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 用户 id（未知用户登录失败为 NULL） */
    private UUID userId;

    /** 登录名（未知用户登录失败为尝试值） */
    private String loginName;

    /** 来源 IP（inet 列，经 ::text 映射 String） */
    private String ip;

    /** 用户代理 */
    private String userAgent;

    /** 是否成功 */
    private Boolean success;

    /** 失败原因（error_msg 口径）：user_not_found/locked/disabled/bad_password/bad_totp/password_changed */
    private String failReason;

    /** 登录失败触发的账户锁定到期时间（NULL=未触发锁定） */
    private LocalDateTime lockedUntil;

    /** 发生时间（timestamptz，分区键） */
    private LocalDateTime occurredAt;

}
