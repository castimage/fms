package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 角色实体（um.roles，RBAC 业务角色）：种子固定角色 fms_admin_role/sys_admin/accountant/approver/cashier/auditor/report_viewer（id 常量 …400-…406）；写经 SECURITY DEFINER 闸口 auth.upsert_role；is_system 角色禁改禁删。
 */
@TableName(schema = "um", value = "roles")
@Data
public class Role {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 角色编码（唯一） */
    private String code;

    /** 角色名称 */
    private String name;

    /** 系统内置（禁改禁删） */
    private Boolean isSystem;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，
















}
