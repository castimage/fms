package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AllocateRequest;
import net.castimage.fms_backend.Model.dto.ArInvoiceRequest;
import net.castimage.fms_backend.Model.dto.BadDebtRequest;
import net.castimage.fms_backend.Model.dto.CustomerRequest;
import net.castimage.fms_backend.Model.dto.ReceiptRequest;
import net.castimage.fms_backend.Model.dto.ReverseReceiptRequest;
import net.castimage.fms_backend.Model.vo.AgingRow;
import net.castimage.fms_backend.Model.vo.AllocationView;
import net.castimage.fms_backend.Model.vo.ArInvoiceView;
import net.castimage.fms_backend.Model.vo.BadDebtView;
import net.castimage.fms_backend.Model.vo.CustomerView;
import net.castimage.fms_backend.Model.vo.ReceiptView;
import net.castimage.fms_backend.Service.ArService;
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
 * 应收模块 REST 接口（/api/ar）：客户/发票 CRUD + 状态机（issue/void）、收款登记 +
 * 核销（防重双闸）/撤销/冲销、账龄分析、坏账计提；
 * 可见集合由 RLS 决定（越权空集/详情 data:null），写路径状态非法/超分/重复核销 40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ar")
public class ArController {

    private final ArService arService;


    // 客户

    /**
     * 客户分页列表（keyword 走 trgm 相似度 + ILIKE 兜底）。
     */
    @GetMapping("/customers")
    public ApiResponse<PageResult<CustomerView>> pageCustomers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(arService.pageCustomers(page, size, companyId, keyword));
    }

    /**
     * 客户详情（越权则 data:null）。
     */
    @GetMapping("/customers/{id}")
    public ApiResponse<CustomerView> getCustomer(@PathVariable UUID id) {
        return ApiResponse.ok(arService.getCustomer(id));
    }

    /**
     * 创建客户（uk_ar_customer_code 冲突 40900）。
     */
    @PostMapping("/customers")
    public ApiResponse<CustomerView> createCustomer(@RequestBody @Valid CustomerRequest req) {
        return ApiResponse.ok(arService.createCustomer(req));
    }

    /**
     * 更新客户。
     */
    @PutMapping("/customers/{id}")
    public ApiResponse<CustomerView> updateCustomer(@PathVariable UUID id,
                                                    @RequestBody @Valid CustomerRequest req) {
        return ApiResponse.ok(arService.updateCustomer(id, req));
    }

    /**
     * 软删客户。
     */
    @DeleteMapping("/customers/{id}")
    public ApiResponse<Void> deleteCustomer(@PathVariable UUID id) {
        arService.deleteCustomer(id);
        return ApiResponse.ok(null);
    }

    // 发票（状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID）

    /**
     * 发票分页列表（customerId/status/keyword 过滤；RLS 收敛）。
     */
    @GetMapping("/invoices")
    public ApiResponse<PageResult<ArInvoiceView>> pageInvoices(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(arService.pageInvoices(page, size, companyId, customerId, status, keyword));
    }

    /**
     * 发票详情（越权则 data:null）。
     */
    @GetMapping("/invoices/{id}")
    public ApiResponse<ArInvoiceView> getInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(arService.getInvoice(id));
    }

    /**
     * 创建 DRAFT 发票（客户须存在且公司一致）。
     */
    @PostMapping("/invoices")
    public ApiResponse<ArInvoiceView> createInvoice(@RequestBody @Valid ArInvoiceRequest req) {
        return ApiResponse.ok(arService.createInvoice(req));
    }

    /**
     * 更新 DRAFT 发票（开票后不可变，触发器终审）。
     */
    @PutMapping("/invoices/{id}")
    public ApiResponse<ArInvoiceView> updateInvoice(@PathVariable UUID id,
                                                    @RequestBody @Valid ArInvoiceRequest req) {
        return ApiResponse.ok(arService.updateInvoice(id, req));
    }

    /**
     * 软删 DRAFT 发票（ISSUED+ 被不可变触发器拒绝则 40900）。
     */
    @DeleteMapping("/invoices/{id}")
    public ApiResponse<Void> deleteInvoice(@PathVariable UUID id) {
        arService.deleteInvoice(id);
        return ApiResponse.ok(null);
    }

    /**
     * 开票（DRAFT 转 ISSUED）：开票后发票进入资金/核算闭环，禁改禁删（仅可作废）。
     */
    @PostMapping("/invoices/{id}/issue")
    public ApiResponse<ArInvoiceView> issue(@PathVariable UUID id) {
        return ApiResponse.ok(arService.issueInvoice(id));
    }

    /**
     * 作废（ISSUED/PARTIAL 转 VOID）：不可变触发器允许的唯一已开票迁移。
     */
    @PostMapping("/invoices/{id}/void")
    public ApiResponse<ArInvoiceView> voidInvoice(@PathVariable UUID id) {
        return ApiResponse.ok(arService.voidInvoice(id));
    }

    /**
     * 补生成发票联动凭证（开票时联动失败的单据；已联动则 40900）。凭证 DRAFT
     * （借应收/贷收入），过账由会计在凭证模块完成。
     */
    @PostMapping("/invoices/{id}/link-voucher")
    public ApiResponse<UUID> linkVoucher(@PathVariable UUID id) {
        return ApiResponse.ok(arService.linkInvoiceVoucher(id));
    }

    // 收款与核销

    /**
     * 收款单分页列表（customerId/status 过滤）。
     */
    @GetMapping("/receipts")
    public ApiResponse<PageResult<ReceiptView>> pageReceipts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(arService.pageReceipts(page, size, companyId, customerId, status));
    }

    /**
     * 收款单详情（越权则 data:null）。
     */
    @GetMapping("/receipts/{id}")
    public ApiResponse<ReceiptView> getReceipt(@PathVariable UUID id) {
        return ApiResponse.ok(arService.getReceipt(id));
    }

    /**
     * 收款登记（status=RECEIVED，金额>0）。
     */
    @PostMapping("/receipts")
    public ApiResponse<ReceiptView> createReceipt(@RequestBody @Valid ReceiptRequest req) {
        return ApiResponse.ok(arService.createReceipt(req));
    }

    /**
     * 软删收款单（仅 RECEIVED 未核销可删）。
     */
    @DeleteMapping("/receipts/{id}")
    public ApiResponse<Void> deleteReceipt(@PathVariable UUID id) {
        arService.deleteReceipt(id);
        return ApiResponse.ok(null);
    }

    /**
     * 核销（收款单对发票）：FOR UPDATE 锁 + 余额预检 + uk_ar_alloc 唯一约束防重复 +
     * version 乐观锁；累计=total 时发票置 PAID/收款单置 ALLOCATED。
     */
    @PostMapping("/receipts/{id}/allocate")
    public ApiResponse<AllocationView> allocate(@PathVariable UUID id,
                                                @RequestBody @Valid AllocateRequest req) {
        // receiptId 以路径为准（请求体同字段被覆盖，防客户端改路径不一致）
        return ApiResponse.ok(arService.allocate(id, new AllocateRequest(id, req.invoiceId(), req.amount())));
    }

    /**
     * 撤销核销（仅发票仍 PARTIAL 可撤；乐观锁软删 + 回算发票状态）。
     */
    @PostMapping("/allocations/{id}/unallocate")
    public ApiResponse<Void> unallocate(@PathVariable UUID id) {
        arService.unallocate(id);
        return ApiResponse.ok(null);
    }

    /**
     * 收款冲销（RECEIVED/ALLOCATED 转 REVERSED）：清核销关联并回算发票状态。
     */
    @PostMapping("/receipts/{id}/reverse")
    public ApiResponse<Void> reverse(@PathVariable UUID id,
                                     @RequestBody(required = false) @Valid ReverseReceiptRequest req) {
        arService.reverseReceipt(id, req == null ? new ReverseReceiptRequest(null) : req);
        return ApiResponse.ok(null);
    }

    // 账龄 / 坏账

    /**
     * 账龄分析（rpt.ar_aging 视图 + 窗口函数分桶 0-30/31-60/61-90/91-180/180+）。
     */
    @GetMapping("/aging")
    public ApiResponse<List<AgingRow>> aging(@RequestParam(required = false) UUID companyId) {
        return ApiResponse.ok(arService.aging(companyId));
    }

    /**
     * 坏账计提分页列表。
     */
    @GetMapping("/bad-debt")
    public ApiResponse<PageResult<BadDebtView>> pageBadDebts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(arService.pageBadDebts(page, size, companyId, status));
    }

    /**
     * 创建计提（(invoice_id, period) 查重防重复；发票 PAID/VOID 禁提）。
     */
    @PostMapping("/bad-debt")
    public ApiResponse<BadDebtView> createBadDebt(@RequestBody @Valid BadDebtRequest req) {
        return ApiResponse.ok(arService.createBadDebt(req));
    }

    /**
     * 确认计提（DRAFT 转 CONFIRMED；确认后禁改禁删）。
     */
    @PostMapping("/bad-debt/{id}/confirm")
    public ApiResponse<BadDebtView> confirmBadDebt(@PathVariable UUID id) {
        return ApiResponse.ok(arService.confirmBadDebt(id));
    }

    /**
     * 软删计提（仅 DRAFT）。
     */
    @DeleteMapping("/bad-debt/{id}")
    public ApiResponse<Void> deleteBadDebt(@PathVariable UUID id) {
        arService.deleteBadDebt(id);
        return ApiResponse.ok(null);
    }
}
