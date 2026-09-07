package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户-角色关联实体（um.user_role）：写入经 SECURITY DEFINER 闸口 auth.assign_role / auth.revoke_role；uk_user_role UNIQUE(user_id, role_id) 兜底重复分配 23505 映射 40900；RLS：is_self(user_id) OR fms_admin。
 */
@TableName(schema = "um", value = "user_role")
@Data
public class UserRole {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 用户 id */
    private UUID userId;

    /** 角色 id */
    private UUID roleId;

    /** 授予人 */
    private UUID grantedBy;

    /** 授予时间 */
    private LocalDateTime grantedAt;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
