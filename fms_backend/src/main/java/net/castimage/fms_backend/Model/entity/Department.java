package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 部门实体（org.departments，ltree 部门树）：path 为编码路径（如 1.1，仅字母/数字/下划线），创建时计算 path=父.path+'.'+code、level=父.level+1；code/path/parent/level 创建后禁改。
 */
@TableName(schema = "org", value = "departments")
@Data
public class Department {

    /** 主键（种子固定 id …020/…021） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 父部门 id（根部门为 null） */
    private UUID parentId;

    /** 部门编码（公司内唯一） */
    private String code;

    /** 部门名称 */
    private String name;

    /** 部门树路径（ltree 文本：父.path + '.' + code） */
    private String path;

    /** 层级（根为 1） */
    private Integer level;

    /** 部门负责人（um.users.id） */
    private UUID managerId;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
