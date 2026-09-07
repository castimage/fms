package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 权限实体（um.permissions，code 格式 action:resource:data_scope 如 voucher:post:dept）：required_permissions（uuid[]）不映射，判定职责在 auth.user_permissions()；全量可读（current_uid 非空），写仅 fms_admin。
 */
@TableName(schema = "um", value = "permissions")
@Data
public class Permission {

    /** 主键（种子固定 id …600-…60b） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 权限编码（action:resource:data_scope） */
    private String code;

    /** 权限名称 */
    private String name;

    /** 归属模块（um.modules.id） */
    private UUID moduleId;

    /** 唯一标识（gl.voucher.reverse 风格） */
    private String identifier;

    /** 资源类型（voucher/account/customer/period/api/report...） */
    private String resourceType;

    /** 动作（view/create/update/delete/approve/post/reverse/close/reopen...） */
    private String action;

    /** 权限形态（MENU/ACTION/API/BUTTON/DATA） */
    private String permissionType;

    /** 启用状态 */
    private Boolean isEnabled;

    /** 系统内置 */
    private Boolean isSystem;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
