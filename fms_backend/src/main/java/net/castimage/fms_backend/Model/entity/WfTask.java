package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流任务实体（wf.tasks）：并发领取核心表——领取/办结由 PG 函数 wf.claim_task /
 * wf.complete_task 原子完成（FOR UPDATE SKIP LOCKED 仅一人成功 + 事件留痕）；
 * status PENDING 领取转 CLAIMED，再 APPROVED/REJECTED/TRANSFERRED/TIMEOUT；
 * version 乐观锁（领取/办结 +1，函数内维护）；company_id 由触发器从实例补全。
 */
@TableName(schema = "wf", value = "tasks")
@Data
public class WfTask {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS 冗余，trg_wf_task_fill_company 从实例补全） */
    private UUID companyId;

    /** 所属流程实例（wf.instances） */
    private UUID instanceId;

    /** 节点编码（对应定义的节点编排） */
    private String nodeCode;

    /** 处理人（领取仅限分配给本人的 PENDING 任务） */
    private UUID assigneeId;

    /** 任务类型：APPROVE/COUNTERSIGN/OR_SIGN */
    private String taskType;

    /** 状态：PENDING/CLAIMED/APPROVED/REJECTED/TRANSFERRED/TIMEOUT */
    private String status;

    /** 截止时间（催办扫描用） */
    private LocalDateTime dueAt;

    /** 领取时间 */
    private LocalDateTime claimAt;

    /** 乐观锁版本号（领取/办结 +1） */
    private Integer version;

    /** 备注（转办时为新处理人 uuid 文本） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
