package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户-公司数据范围实体（um.user_org_scope；auth.set_session 预载数据源）：本批默认不写（登录注入 orgIds=null 则由 PG 预载），Mapper 预留 org_id 集合查询供 admin 全量/切换数据范围场景。
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
