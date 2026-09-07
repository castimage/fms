package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 预算调整单实体（budget.budget_adjustments）：amount 正=追加/负=调减（CHECK amount <> 0）；
 * 状态机经 DRAFT 转 APPROVED/REJECTED（RLS update USING status='DRAFT' 兜底）；
 * APPROVED 调整净额并入预算执行基数（BudgetPlanMapper.selectExecutionRows 中
 * SUM(CASE WHEN status='APPROVED' THEN amount ELSE 0 END)）。
 */
@TableName(schema = "budget", value = "budget_adjustments")
@Data
public class BudgetAdjustment {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS 经父预算计划公司范围子查询收敛） */
    private UUID companyId;

    /** 关联预算计划（budget.budget_plans） */
    private UUID planId;

    /** 调整金额（正=追加 负=调减，CHECK <> 0） */
    private BigDecimal amount;

    /** 调整原因 */
    private String reason;

    /** 状态：DRAFT/APPROVED/REJECTED */
    private String status;

    /** 审批人（APPROVED/REJECTED 时写入） */
    private UUID adjustedBy;

    /** 审批时间 */
    private LocalDateTime adjustedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

    /** 幂等键（服务层对 planId+amount+reason 规范化 SHA-256；uk_adjustment_idem 部分唯一索引防重复提交） */
    private String idemKey;

}
