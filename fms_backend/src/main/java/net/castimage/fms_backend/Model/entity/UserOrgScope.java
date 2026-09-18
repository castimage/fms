package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户-公司数据范围实体，auth.set_session 的数据范围预载源，登录注入 orgIds=null 时由 PG
 * 直读本表，管理员的数据范围读写走 auth.list_user_scopes 与 auth.set_user_org_scope 闸口
 */
@TableName(schema = "um", value = "user_org_scope")
@Data
public class UserOrgScope {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 用户 id */
    private UUID userId;

    /** 可见公司 id */
    private UUID orgId;

    /** 范围类型：GRANTED/MANAGER/DEFAULT */
    private String scopeType;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
