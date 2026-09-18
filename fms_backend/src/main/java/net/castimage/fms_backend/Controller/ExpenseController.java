package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.InvoiceFileBindRequest;
import net.castimage.fms_backend.Model.dto.ReimbursementRequest;
import net.castimage.fms_backend.Model.vo.ReimbursementDetailView;
import net.castimage.fms_backend.Model.vo.ReimbursementView;
import net.castimage.fms_backend.Service.ExpenseService;
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
import java.util.UUID;

/**
 * 费用报销模块 REST 接口（/api/exp）：报销单 CRUD（头 + 明细同事务）、提交/审批/驳回/出纳付款/
 * 生成过账凭证状态机、本人报销列表；身份由安全链路保证，RLS 决定可见集合——本人/本部门/终态可见，
 * 详情越权返回 data:null（不泄露存在性），状态机非法流转由 DB 触发器终审（40900）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exp")
public class ExpenseController {

    private final ExpenseService expenseService;


    /**
     * 报销单分页列表（RLS 已按本人/本部门/终态收敛；companyId/status/keyword 过滤提示）。
     */
    @GetMapping("/reimbursements")
    public ApiResponse<PageResult<ReimbursementView>> pageReimbursements(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(expenseService.pageReimbursements(page, size, companyId, status, keyword));
    }

    /**
     * 本人报销分页（applicant_id = 当前用户；RLS is_self 双路径兜底）。
     */
    @GetMapping("/my-reimbursements")
    public ApiResponse<PageResult<ReimbursementView>> myReimbursements(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(expenseService.pageMyReimbursements(page, size, status, keyword));
    }

    /**
     * 报销单详情（头 + 明细行；他人草稿/越权则 data:null，不泄露存在性）。
     */
    @GetMapping("/reimbursements/{id}")
    public ApiResponse<ReimbursementDetailView> getReimbursement(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.getReimbursement(id));
    }

    /**
     * 创建 DRAFT 草稿（头金额须 = 明细合计；申请人恒为当前用户）。
     */
    @PostMapping("/reimbursements")
    public ApiResponse<ReimbursementDetailView> createReimbursement(@RequestBody @Valid ReimbursementRequest req) {
        return ApiResponse.ok(expenseService.createReimbursement(req));
    }

    /**
     * 更新 DRAFT 草稿（仅 DRAFT；软删旧明细 + 全量重插）。
     */
    @PutMapping("/reimbursements/{id}")
    public ApiResponse<ReimbursementDetailView> updateReimbursement(@PathVariable UUID id,
                                                                    @RequestBody @Valid ReimbursementRequest req) {
        return ApiResponse.ok(expenseService.updateReimbursement(id, req));
    }

    /**
     * 软删 DRAFT 草稿（仅 DRAFT；级联软删明细行）。
     */
    @DeleteMapping("/reimbursements/{id}")
    public ApiResponse<Void> deleteReimbursement(@PathVariable UUID id) {
        expenseService.deleteReimbursement(id);
        return ApiResponse.ok(null);
    }

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅申请人本人；DB 触发器终审状态机。
     */
    @PostMapping("/reimbursements/{id}/submit")
    public ApiResponse<ReimbursementDetailView> submit(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.submitReimbursement(id));
    }

    /**
     * 审批（SUBMITTED 转 APPROVED）。
     */
    @PostMapping("/reimbursements/{id}/approve")
    public ApiResponse<ReimbursementDetailView> approve(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.approveReimbursement(id));
    }

    /**
     * 驳回（SUBMITTED 转 REJECTED）：退回后申请人可修改再提交。
     */
    @PostMapping("/reimbursements/{id}/reject")
    public ApiResponse<ReimbursementDetailView> reject(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.rejectReimbursement(id));
    }

    /**
     * 出纳付款（APPROVED 转 PAID）：付款后单据冻结，仅余过账一步。
     */
    @PostMapping("/reimbursements/{id}/pay")
    public ApiResponse<ReimbursementDetailView> pay(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.payReimbursement(id));
    }

    /**
     * 生成过账凭证，仅 PAID 状态可调用，幂等，生成 DRAFT 凭证并回填 voucher_id，同事务推进 POSTED
     *
     * 返回联动凭证 id，供前端跳转凭证详情
     */
    @PostMapping("/reimbursements/{id}/link-voucher")
    public ApiResponse<UUID> linkVoucher(@PathVariable UUID id) {
        return ApiResponse.ok(expenseService.linkReimbursementVoucher(id));
    }

    /**
     * 回填明细行发票附件关联（invoice_file_id 写入链路）：附件须已上传至该明细行名下
     * （/api/files/upload，business_type=REIMBURSEMENT_LINE、business_id=行 id）。
     */
    @PutMapping("/reimbursement-lines/{lineId}/invoice-file")
    public ApiResponse<Void> bindInvoiceFile(@PathVariable UUID lineId,
                                             @RequestBody @Valid InvoiceFileBindRequest req) {
        expenseService.bindInvoiceFile(lineId, req.fileId());
        return ApiResponse.ok(null);
    }
}
