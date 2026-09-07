package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.BudgetAdjustmentRequest;
import net.castimage.fms_backend.Model.dto.BudgetPlanRequest;
import net.castimage.fms_backend.Model.vo.BudgetAdjustmentView;
import net.castimage.fms_backend.Model.vo.BudgetExecutionRow;
import net.castimage.fms_backend.Model.vo.BudgetPlanView;
import net.castimage.fms_backend.Service.BudgetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * 预算模块 REST 接口（/api/budget）：预算计划 CRUD + 提交/审批状态机、预算调整单 CRUD +
 * 审批/驳回、预算执行对比（plan vs rpt.budget_actual IMMV）；超支阻断在 DB 触发器
 * （gl.voucher_lines 凭证分录保存时 RAISE 即 40900），本层不实现阻断逻辑。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/budget")
public class BudgetController {

    private final BudgetService budgetService;


    // 预算计划

    /**
     * 预算计划分页列表（RLS 公司/部门/本人收敛；companyId/period/deptId/status 过滤提示）。
     */
    @GetMapping("/plans")
    public ApiResponse<PageResult<BudgetPlanView>> pagePlans(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(budgetService.pagePlans(page, size, companyId, period, deptId, status));
    }

    /**
     * 预算计划详情（越权则 data:null）。
     */
    @GetMapping("/plans/{id}")
    public ApiResponse<BudgetPlanView> getPlan(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.getPlan(id));
    }

    /**
     * 创建 DRAFT 计划（version=0；同键位重复 40900）。
     */
    @PostMapping("/plans")
    public ApiResponse<BudgetPlanView> createPlan(@RequestBody @Valid BudgetPlanRequest req) {
        return ApiResponse.ok(budgetService.createPlan(req));
    }

    /**
     * 更新 DRAFT 计划（仅 DRAFT）。
     */
    @PutMapping("/plans/{id}")
    public ApiResponse<BudgetPlanView> updatePlan(@PathVariable UUID id,
                                                  @RequestBody @Valid BudgetPlanRequest req) {
        return ApiResponse.ok(budgetService.updatePlan(id, req));
    }

    /**
     * 软删 DRAFT 计划（仅 DRAFT；已被调整单引用 40900）。
     */
    @DeleteMapping("/plans/{id}")
    public ApiResponse<Void> deletePlan(@PathVariable UUID id) {
        budgetService.deletePlan(id);
        return ApiResponse.ok(null);
    }

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅创建人本人。
     */
    @PostMapping("/plans/{id}/submit")
    public ApiResponse<BudgetPlanView> submitPlan(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.submitPlan(id));
    }

    /**
     * 审批（SUBMITTED 转 APPROVED）：批准后只读（RLS update 策略兜底）。
     */
    @PostMapping("/plans/{id}/approve")
    public ApiResponse<BudgetPlanView> approvePlan(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.approvePlan(id));
    }

    // 预算调整单

    /**
     * 调整单分页列表（companyId/planId/status 可选过滤）。
     */
    @GetMapping("/adjustments")
    public ApiResponse<PageResult<BudgetAdjustmentView>> pageAdjustments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(budgetService.pageAdjustments(page, size, companyId, planId, status));
    }

    /**
     * 调整单详情（越权则 data:null）。
     */
    @GetMapping("/adjustments/{id}")
    public ApiResponse<BudgetAdjustmentView> getAdjustment(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.getAdjustment(id));
    }

    /**
     * 创建 DRAFT 调整单（amount 正=追加/负=调减，<> 0）。
     */
    @PostMapping("/adjustments")
    public ApiResponse<BudgetAdjustmentView> createAdjustment(@RequestBody @Valid BudgetAdjustmentRequest req) {
        return ApiResponse.ok(budgetService.createAdjustment(req));
    }

    /**
     * 软删 DRAFT 调整单（仅 DRAFT）。
     */
    @DeleteMapping("/adjustments/{id}")
    public ApiResponse<Void> deleteAdjustment(@PathVariable UUID id) {
        budgetService.deleteAdjustment(id);
        return ApiResponse.ok(null);
    }

    /**
     * 审批（DRAFT 转 APPROVED）：已批调整净额并入预算执行基数。
     */
    @PostMapping("/adjustments/{id}/approve")
    public ApiResponse<BudgetAdjustmentView> approveAdjustment(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.approveAdjustment(id));
    }

    /**
     * 驳回（DRAFT 转 REJECTED）。
     */
    @PostMapping("/adjustments/{id}/reject")
    public ApiResponse<BudgetAdjustmentView> rejectAdjustment(@PathVariable UUID id) {
        return ApiResponse.ok(budgetService.rejectAdjustment(id));
    }

    // 预算执行对比

    /**
     * 预算执行对比：计划最新版本 LEFT JOIN rpt.budget_actual IMMV + 已批调整净额，得出
     * ratio/overLimit/level（warn 80% / block 100%）；超支拒绝由 DB 触发器承担。
     */
    @GetMapping("/execution")
    public ApiResponse<List<BudgetExecutionRow>> execution(
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) UUID deptId) {
        return ApiResponse.ok(budgetService.execution(companyId, period, deptId));
    }
}
