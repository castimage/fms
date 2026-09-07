package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 功能模块实体（um.modules，模块树/菜单层级）：identifier 唯一（org/gl.voucher 风格），parent_id 自引用；任何已认证用户可读，写仅 fms_admin，无 sort_order（按 identifier 排序）。
 */
@TableName(schema = "um", value = "modules")
@Data
public class Module {

    /** 主键（种子固定 id …500 起） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 唯一标识 */
    private String identifier;

    /** 模块名称（菜单名） */
    private String name;

    /** 说明 */
    private String description;

    /** 父模块 id（根为 null） */
    private UUID parentId;

    /** 启用状态 */
    private Boolean isEnabled;

    /** 系统内置（不可删） */
    private Boolean isSystem;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
