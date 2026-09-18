package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户-部门数据范围实体，auth.set_session 的数据范围预载源，include_subtree 指示可见范围
 * 是否含子树，auth.can_access_dept 据此判断，管理员的数据范围读写走 auth.list_user_scopes
 * 与 auth.set_user_dept_scope 闸口
 */
@TableName(schema = "um", value = "user_dept_scope")
@Data
public class UserDeptScope {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 用户 id */
    private UUID userId;

    /** 可见部门 id */
    private UUID deptId;

    /** 范围类型：GRANTED/MANAGER/DEFAULT */
    private String scopeType;

    /** 是否含子树 */
    private Boolean includeSubtree;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
