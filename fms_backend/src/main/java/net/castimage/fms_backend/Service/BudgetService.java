package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.BudgetAdjustmentRequest;
import net.castimage.fms_backend.Model.dto.BudgetPlanRequest;
import net.castimage.fms_backend.Model.vo.BudgetAdjustmentView;
import net.castimage.fms_backend.Model.vo.BudgetExecutionRow;
import net.castimage.fms_backend.Model.vo.BudgetPlanView;

import java.util.List;
import java.util.UUID;

/**
 * 预算模块业务接口（预算编制/调整/执行对比）：计划状态机经 DRAFT 提交 SUBMITTED 再 APPROVED
 * （批准后只读，RLS update 策略兜底）；调整单经 DRAFT 转 APPROVED/REJECTED；执行对比
 * 查 rpt.budget_actual IMMV 计算执行比例；超支阻断全部在 DB 触发器
 * （budget.fn_voucher_budget_block 挂 gl.voucher_lines，RAISE P0001 映射 40900），Java 零实现。
 */
public interface BudgetService {

    /**
     * 预算计划分页列表（RLS 公司/部门/本人收敛；companyId/period/deptId/status 过滤提示）。
     */
    PageResult<BudgetPlanView> pagePlans(long page, long size, UUID companyId, String period,
                                         UUID deptId, String status);

    /**
     * 预算计划详情（RLS 空集返回 null 则 data:null）。
     */
    BudgetPlanView getPlan(UUID id);

    /**
     * 创建 DRAFT 计划（version=0；uk_plan 同键位重复 40900）。
     */
    BudgetPlanView createPlan(BudgetPlanRequest req);

    /**
     * 更新 DRAFT 计划（仅 DRAFT；金额/科目/部门/期间可改，版本键位冲突 40900）。
     */
    BudgetPlanView updatePlan(UUID id, BudgetPlanRequest req);

    /**
     * 软删 DRAFT 计划（仅 DRAFT；已被调整单引用 40900）。
     */
    void deletePlan(UUID id);

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅创建人本人；DB 触发器/RLS 终审。
     */
    BudgetPlanView submitPlan(UUID id);

    /**
     * 审批（SUBMITTED 转 APPROVED）：写审批人/审批时间；批准后只读。
     */
    BudgetPlanView approvePlan(UUID id);

    /**
     * 调整单分页列表（companyId/planId/status 可选过滤）。
     */
    PageResult<BudgetAdjustmentView> pageAdjustments(long page, long size, UUID companyId,
                                                     UUID planId, String status);

    /**
     * 调整单详情（RLS 空集返回 null）。
     */
    BudgetAdjustmentView getAdjustment(UUID id);

    /**
     * 创建 DRAFT 调整单（amount <> 0；父计划须存在）。
     */
    BudgetAdjustmentView createAdjustment(BudgetAdjustmentRequest req);

    /**
     * 审批（DRAFT 转 APPROVED）：已批调整净额并入执行基数。
     */
    BudgetAdjustmentView approveAdjustment(UUID id);

    /**
     * 驳回（DRAFT 转 REJECTED）。
     */
    BudgetAdjustmentView rejectAdjustment(UUID id);

    /**
     * 软删 DRAFT 调整单。
     */
    void deleteAdjustment(UUID id);

    /**
     * 预算执行对比：计划最新版本（APPROVED 优先）LEFT JOIN rpt.budget_actual IMMV +
     * 已批调整净额算出 ratio/overLimit/level 标记（warn 80% / block 100%）。
     */
    List<BudgetExecutionRow> execution(UUID companyId, String period, UUID deptId);
}
