package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.ApInvoiceRequest;
import net.castimage.fms_backend.Model.dto.MatchLineRequest;
import net.castimage.fms_backend.Model.dto.PaymentAllocateRequest;
import net.castimage.fms_backend.Model.dto.PaymentRequest;
import net.castimage.fms_backend.Model.dto.ReconciliationImportRequest;
import net.castimage.fms_backend.Model.dto.ReconciliationRequest;
import net.castimage.fms_backend.Model.dto.ReverseReceiptRequest;
import net.castimage.fms_backend.Model.dto.SupplierRequest;
import net.castimage.fms_backend.Model.vo.AllocationView;
import net.castimage.fms_backend.Model.vo.ApAgingRow;
import net.castimage.fms_backend.Model.vo.ApInvoiceView;
import net.castimage.fms_backend.Model.vo.PaymentView;
import net.castimage.fms_backend.Model.vo.ReconciliationDetailView;
import net.castimage.fms_backend.Model.vo.ReconciliationView;
import net.castimage.fms_backend.Model.vo.SupplierView;
import net.castimage.fms_backend.Service.ApService;
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
 * 应付模块 REST 接口（/api/ap）：供应商/发票 CRUD + 状态机（issue/void）、付款登记 +
 * 核销（防重双闸）/撤销/冲销、供应商对账（行导入/候选匹配/人工匹配/确认）；
 * 可见集合由 RLS 决定（越权空集/详情 data:null），写路径状态非法/超分/重复核销 40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ap")
public class ApController {

    private final ApService apService;


    // 供应商

    /**
     * 供应商分页列表（keyword 走 trgm 相似度 + ILIKE 兜底）。
     */
    @GetMapping("/suppliers")
    public ApiResponse<PageResult<SupplierView>> pageSuppliers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(apService.pageSuppliers(page, size, companyId, keyword));
    }

    /**
     * 供应商详情（越权则 data:null）。
     */
    @GetMapping("/suppliers/{id}")
    public ApiResponse<SupplierView> getSupplier(@PathVariable UUID id) {
        return ApiResponse.ok(apService.getSupplier(id));
    }

    /**
     * 创建供应商（uk_ap_supplier_code 冲突 40900）。
     */
    @PostMapping("/suppliers")
    public ApiResponse<SupplierView> createSupplier(@RequestBody @Valid SupplierRequest req) {
        return ApiResponse.ok(apService.createSupplier(req));
    }

    /**
     * 更新供应商。
     */
    @PutMapping("/suppliers/{id}")
    public ApiResponse<SupplierView> updateSupplier(@PathVariable UUID id,
                                                    @RequestBody @Valid SupplierRequest req) {
        return ApiResponse.ok(apService.updateSupplier(id, req));
    }

    /**
     * 软删供应商。
     */
    @DeleteMapping("/suppliers/{id}")
    public ApiResponse<Void> deleteSupplier(@PathVariable UUID id) {
        apService.deleteSupplier(id);
        return ApiResponse.ok(null);
    }

    // 发票（状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID，与 ar 同构）

    /**
     * 发票分页列表（supplierId/status/keyword 过滤；RLS 收敛）。
     */
    @GetMapping("/invoices")
    public ApiResponse<PageResult<ApInvoiceView>> pageInvoices(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(apService.pageInvoices(page, size, companyId, supplierId, status, keyword));
    }

    /**
     * 发票详情（越权则 data:null）。
     */
    @GetMapping("/invoices/{id}")
    public ApiResponse<ApInvoiceView> getInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(apService.getInvoice(id));
    }

    /**
     * 创建 DRAFT 发票（供应商须存在且公司一致）。
     */
    @PostMapping("/invoices")
    public ApiResponse<ApInvoiceView> createInvoice(@RequestBody @Valid ApInvoiceRequest req) {
        return ApiResponse.ok(apService.createInvoice(req));
    }

    /**
     * 更新 DRAFT 发票（开票后不可变，触发器终审）。
     */
    @PutMapping("/invoices/{id}")
    public ApiResponse<ApInvoiceView> updateInvoice(@PathVariable UUID id,
                                                    @RequestBody @Valid ApInvoiceRequest req) {
        return ApiResponse.ok(apService.updateInvoice(id, req));
    }

    /**
     * 软删 DRAFT 发票（ISSUED+ 被不可变触发器拒绝则 40900）。
     */
    @DeleteMapping("/invoices/{id}")
    public ApiResponse<Void> deleteInvoice(@PathVariable UUID id) {
        apService.deleteInvoice(id);
        return ApiResponse.ok(null);
    }

    /**
     * 开票（DRAFT 转 ISSUED）：开票后发票进入资金/核算闭环，禁改禁删（仅可作废）。
     */
    @PostMapping("/invoices/{id}/issue")
    public ApiResponse<ApInvoiceView> issue(@PathVariable UUID id) {
        return ApiResponse.ok(apService.issueInvoice(id));
    }

    /**
     * 作废（ISSUED/PARTIAL 转 VOID）：不可变触发器允许的唯一已开票迁移。
     */
    @PostMapping("/invoices/{id}/void")
    public ApiResponse<ApInvoiceView> voidInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(apService.voidInvoice(id));
    }

    /**
     * 补生成发票联动凭证（开票时联动失败的单据；已联动则 40900）。凭证 DRAFT
     * （借费用/贷应付），过账由会计在凭证模块完成。
     */
    @PostMapping("/invoices/{id}/link-voucher")
    public ApiResponse<UUID> linkVoucher(@PathVariable UUID id) {
        return ApiResponse.ok(apService.linkInvoiceVoucher(id));
    }

    // 付款与核销

    /**
     * 付款单分页列表（supplierId/status 过滤）。
     */
    @GetMapping("/payments")
    public ApiResponse<PageResult<PaymentView>> pagePayments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(apService.pagePayments(page, size, companyId, supplierId, status));
    }

    /**
     * 付款单详情（越权则 data:null）。
     */
    @GetMapping("/payments/{id}")
    public ApiResponse<PaymentView> getPayment(@PathVariable UUID id) {
        return ApiResponse.ok(apService.getPayment(id));
    }

    /**
     * 付款登记（status=PAID，金额>0）。
     */
    @PostMapping("/payments")
    public ApiResponse<PaymentView> createPayment(@RequestBody @Valid PaymentRequest req) {
        return ApiResponse.ok(apService.createPayment(req));
    }

    /**
     * 软删付款单（仅 PAID 未核销可删）。
     */
    @DeleteMapping("/payments/{id}")
    public ApiResponse<Void> deletePayment(@PathVariable UUID id) {
        apService.deletePayment(id);
        return ApiResponse.ok(null);
    }

    /**
     * 付款核销（一次核销多张发票）：FOR UPDATE 锁 + 余额预检 + uk_ap_alloc 唯一约束
     * 防重复 + version 乐观锁；核销合计=付款金额时付款单置 ALLOCATED。
     */
    @PostMapping("/payments/{id}/allocate")
    public ApiResponse<List<AllocationView>> allocate(@PathVariable UUID id,
                                                      @RequestBody @Valid List<PaymentAllocateRequest> items) {
        return ApiResponse.ok(apService.allocate(id, items));
    }

    /**
     * 撤销核销（仅发票仍 PARTIAL 可撤；乐观锁软删 + 回算发票状态）。
     */
    @PostMapping("/allocations/{id}/unallocate")
    public ApiResponse<Void> unallocate(@PathVariable UUID id) {
        apService.unallocate(id);
        return ApiResponse.ok(null);
    }

    /**
     * 付款冲销（PAID/ALLOCATED 转 REVERSED）：清核销关联并回算发票状态。
     */
    @PostMapping("/payments/{id}/reverse")
    public ApiResponse<Void> reverse(@PathVariable UUID id,
                                     @RequestBody(required = false) @Valid ReverseReceiptRequest req) {
        apService.reversePayment(id, req == null ? new ReverseReceiptRequest(null) : req);
        return ApiResponse.ok(null);
    }

    // 账龄

    /**
     * 应付账龄分析（rpt.ap_aging 视图 + 窗口函数分桶 0-30/31-60/61-90/91-180/180+，
     * 按未核销余额聚合；companyId 仅过滤提示，越权值返回空集）。
     */
    @GetMapping("/aging")
    public ApiResponse<List<ApAgingRow>> aging(@RequestParam(required = false) UUID companyId) {
        return ApiResponse.ok(apService.aging(companyId));
    }

    // 供应商对账（核对动作，不改发票/付款状态）

    /**
     * 对账单分页列表（supplierId/status 过滤）。
     */
    @GetMapping("/reconciliations")
    public ApiResponse<PageResult<ReconciliationView>> pageReconciliations(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(apService.pageReconciliations(page, size, companyId, supplierId, status));
    }

    /**
     * 对账单详情（头 + 行明细；越权则 data:null）。
     */
    @GetMapping("/reconciliations/{id}")
    public ApiResponse<ReconciliationDetailView> getReconciliation(@PathVariable UUID id) {
        return ApiResponse.ok(apService.getReconciliation(id));
    }

    /**
     * 创建对账单（DRAFT；supplier + statement_date）。
     */
    @PostMapping("/reconciliations")
    public ApiResponse<ReconciliationView> createReconciliation(@RequestBody @Valid ReconciliationRequest req) {
        return ApiResponse.ok(apService.createReconciliation(req));
    }

    /**
     * 软删对账单（仅 DRAFT；级联软删行）。
     */
    @DeleteMapping("/reconciliations/{id}")
    public ApiResponse<Void> deleteReconciliation(@PathVariable UUID id) {
        apService.deleteReconciliation(id);
        return ApiResponse.ok(null);
    }

    /**
     * 行导入（全部落库 UNMATCHED；候选 top1 >0.6 自动建议回写 similarity_score）。
     */
    @PostMapping("/reconciliations/{id}/lines")
    public ApiResponse<Void> importLines(@PathVariable UUID id,
                                         @RequestBody @Valid ReconciliationImportRequest req) {
        apService.importLines(id, req);
        return ApiResponse.ok(null);
    }

    /**
     * 人工匹配（updateMatchStatus 原子守卫 WHERE UNMATCHED，0 行则 40900 防双配；
     * matched_invoice_id 仅回填不核销）。
     */
    @PostMapping("/reconciliations/{id}/match")
    public ApiResponse<Void> matchLine(@PathVariable UUID id,
                                       @RequestBody @Valid MatchLineRequest req) {
        apService.matchLine(id, req);
        return ApiResponse.ok(null);
    }

    /**
     * 确认（DRAFT 转 CONFIRMED；全部行处理完方可确认，确认后禁改）。
     */
    @PostMapping("/reconciliations/{id}/confirm")
    public ApiResponse<ReconciliationView> confirm(@PathVariable UUID id) {
        return ApiResponse.ok(apService.confirm(id));
    }
}
