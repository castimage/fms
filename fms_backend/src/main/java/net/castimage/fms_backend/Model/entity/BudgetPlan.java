package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 预算计划实体（budget.budget_plans）：(company_id, period, account_id, dept_id, version)
 * 唯一（uk_plan）——预算调整以新版本叠加；超支阻断在 DB 触发器（budget.fn_voucher_budget_block
 * 挂 gl.voucher_lines，读 rpt.budget_actual IMMV 计算执行比例 ≥100% 则 RAISE 40900），Java 零实现；
 * 状态机经 DRAFT 提交 SUBMITTED 再 APPROVED，批准后只读（RLS update 策略兜底）。
 */
@TableName(schema = "budget", value = "budget_plans")
@Data
public class BudgetPlan {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 预算期间（YYYY-MM） */
    private String period;

    /** 预算科目（gl.chart_accounts） */
    private UUID accountId;

    /** 预算部门（org.departments；RLS can_access_dept） */
    private UUID deptId;

    /** 预算金额（> 0，CHECK 兜底） */
    private BigDecimal amount;

    /** 版本号（0 起；调整以新版本叠加，乐观锁防并发覆盖） */
    private Integer version;

    /** 状态：DRAFT/SUBMITTED/APPROVED（批准后只读） */
    private String status;

    /** 审批人 */
    private UUID approvedBy;

    /** 审批时间 */
    private LocalDateTime approvedAt;

    /** 创建人（RLS is_self(created_by)） */
    private UUID createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
