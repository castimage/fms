package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流任务事件实体（wf.task_events）：仅追加流水（CLAIM/APPROVE/REJECT/TRANSFER/
 * REMIND/TIMEOUT，DB CHECK）；不挂审计触发器（本身即日志）；company_id 由触发器从任务
 * 补全；RLS can_access_org 公司范围收敛。
 */
@TableName(schema = "wf", value = "task_events")
@Data
public class WfTaskEvent {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS 冗余，触发器从任务补全） */
    private UUID companyId;

    /** 关联任务（wf.tasks） */
    private UUID taskId;

    /** 动作：CLAIM/APPROVE/REJECT/TRANSFER/REMIND/TIMEOUT */
    private String action;

    /** 操作人 */
    private UUID operatorId;

    /** 来源节点 */
    private String fromNode;

    /** 去向节点 */
    private String toNode;

    /** 备注 */
    private String remark;

    /** 发生时间 */
    private LocalDateTime occurredAt;

}
