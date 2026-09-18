package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 站内通知实体（notify.notifications）：业务通知（预算预警/审批待办/系统消息等），
 * 按收件人 user_id 归属（RLS 策略按 auth.current_uid() = user_id 收敛，Java 侧恒加
 * user_id 过滤双保险）；is_read/read_at 承载已读状态，未读数 = 本人未读行计数。
 * 表结构（约定 schema，后续 SQL 批对齐）：id uuidv7 PK、user_id、title、content、
 * type、ref_type、ref_id、is_read bool、read_at、created_at、updated_at、deleted_at。
 */
@TableName(schema = "notify", value = "notifications")
@Data
public class Notification {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 收件人（本人，RLS user_id = auth.current_uid()） */
    private UUID userId;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 类型（BUDGET_WARNING/APPROVAL/SYSTEM 等） */
    private String type;

    /** 关联业务类型，系统公告固定 ANNOUNCEMENT，预算预警 budget_warning，与 ref_id 及 title 共同构成广播幂等键 */
    private String refType;

    /** 关联业务 id，预算预警取 company_id 作公司级幂等键，公告为 NULL */
    private UUID refId;

    /** 是否已读 */
    private Boolean isRead;

    /** 已读时间（NULL=未读） */
    private LocalDateTime readAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
