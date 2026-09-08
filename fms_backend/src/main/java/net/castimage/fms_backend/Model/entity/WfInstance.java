package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流实例实体（wf.instances）：业务单据通用挂接（business_type + business_id）；
 * 状态机 RUNNING 转 APPROVED/REJECTED/CANCELED（推进由 PG 函数 wf.fn_complete_task /
 * wf.fn_advance_instance 完成）；RLS SELECT 限定本人发起或终态（APPROVED/REJECTED），
 * 他人 RUNNING 实例不可见。
 */
@TableName(schema = "wf", value = "instances")
@Data
public class WfInstance {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 流程定义（wf.definitions） */
    private UUID definitionId;

    /** 业务类型（REIMBURSEMENT 等） */
    private String businessType;

    /** 业务单据主键 */
    private UUID businessId;

    /** 发起人（RLS is_self(initiator_id)） */
    private UUID initiatorId;

    /** 当前节点编码（null 表示尚未推进） */
    private String currentNode;

    /** 状态：RUNNING/APPROVED/REJECTED/CANCELED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
