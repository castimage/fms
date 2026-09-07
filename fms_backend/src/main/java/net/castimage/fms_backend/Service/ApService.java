package net.castimage.fms_backend.Service;

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

import java.util.List;
import java.util.UUID;

/**
 * 应付模块业务接口（供应商/发票/付款/核销/供应商对账）：发票状态机与 ar 同构
 * （trg_ap_invoice_immutable 终审）；付款核销防重双闸（uk_ap_alloc + version 乐观锁）；
 * 对账为核对动作（不改发票/付款状态，matched_invoice_id 仅回填不核销）；发票/付款单
 * 与 gl 凭证联动（voucher_id，VoucherLinkService 尽力生成 + 补生成端点）。
 */
public interface ApService {

    // 供应商

    /** 供应商分页列表（trgm 模糊检索；RLS 收敛可见集合） */
    PageResult<SupplierView> pageSuppliers(long page, long size, UUID companyId, String keyword);

    /** 供应商详情（RLS 空集则返回 null，映射 data:null） */
    SupplierView getSupplier(UUID id);

    /** 创建供应商（uk_ap_supplier_code 冲突 23505 映射 40900） */
    SupplierView createSupplier(SupplierRequest req);

    /** 更新供应商 */
    SupplierView updateSupplier(UUID id, SupplierRequest req);

    /** 软删供应商（存在未删除的发票/付款单引用则 40900 阻断） */
    void deleteSupplier(UUID id);

    // 发票（状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID）

    /** 发票分页列表（join 供应商名） */
    PageResult<ApInvoiceView> pageInvoices(long page, long size, UUID companyId,
                                           UUID supplierId, String status, String keyword);

    /** 发票详情（RLS 空集则返回 null） */
    ApInvoiceView getInvoice(UUID id);

    /** 创建 DRAFT 发票 */
    ApInvoiceView createInvoice(ApInvoiceRequest req);

    /** 更新 DRAFT 发票（开票后不可变） */
    ApInvoiceView updateInvoice(UUID id, ApInvoiceRequest req);

    /** 软删 DRAFT 发票 */
    void deleteInvoice(UUID id);

    /** 开票（DRAFT 转 ISSUED） */
    ApInvoiceView issueInvoice(UUID id);

    /** 作废（ISSUED/PARTIAL 转 VOID） */
    ApInvoiceView voidInvoice(UUID id);

    /**
     * 补生成发票联动凭证（POST /api/ap/invoices/{id}/link-voucher）：开票时联动失败
     * （voucher_id 留空）的补生成入口，仅 ISSUED 可补；已联动（voucher_id 非空）则
     * 40900。凭证 DRAFT + 回填 voucher_id，幂等键由发票派生（重复补生成复用既有凭证）。
     *
     * @return 联动凭证 id
     * @throws net.castimage.fms_backend.Exception.ConflictException 已联动/非 ISSUED（40900）
     */
    UUID linkInvoiceVoucher(UUID id);

    // 付款与核销（防重双闸）

    /** 付款单分页列表（join 供应商/银行账户名） */
    PageResult<PaymentView> pagePayments(long page, long size, UUID companyId,
                                         UUID supplierId, String status);

    /** 付款单详情（RLS 空集则返回 null） */
    PaymentView getPayment(UUID id);

    /** 付款登记（status=PAID） */
    PaymentView createPayment(PaymentRequest req);

    /** 软删付款单（仅 PAID 未核销可删） */
    void deletePayment(UUID id);

    /**
     * 付款核销（一次可核销多张发票）：事务内 ① FOR UPDATE 锁付款单（防并发超分，
     * status 须 PAID）② 逐张发票余额预检（累计≤total）③ 批量 INSERT
     * payment_allocations（uk_ap_alloc 防同对重复，23505 映射 40900）④ 核销合计=付款金额
     * 则付款单置 ALLOCATED；发票累计=total 则置 PAID，否则 PARTIAL（不可变触发器终审）。
     */
    List<AllocationView> allocate(UUID paymentId, List<PaymentAllocateRequest> items);

    /** 撤销核销（仅发票仍 PARTIAL 可撤）：乐观锁软删 + 回算发票状态 */
    void unallocate(UUID allocationId);

    /** 付款冲销（PAID/ALLOCATED 转 REVERSED）：清核销关联并回算受影响发票状态 */
    void reversePayment(UUID id, ReverseReceiptRequest req);

    // 账龄

    /**
     * 应付账龄分析（rpt.ap_aging 视图 + 窗口函数分桶 0-30/31-60/61-90/91-180/180+，
     * 按未核销余额聚合；companyId 仅过滤提示，越权值返回空集）
     */
    List<ApAgingRow> aging(UUID companyId);

    // 供应商对账（核对动作，不改发票/付款状态）

    /** 对账单分页列表（join 供应商名） */
    PageResult<ReconciliationView> pageReconciliations(long page, long size, UUID companyId,
                                                       UUID supplierId, String status);

    /** 对账单详情（头 + 行明细；RLS 空集则返回 null） */
    ReconciliationDetailView getReconciliation(UUID id);

    /** 创建对账单（DRAFT；supplier + statement_date） */
    ReconciliationView createReconciliation(ReconciliationRequest req);

    /** 行导入（全部落库 UNMATCHED；候选匹配 top1 &gt;0.6 自动建议回写 similarity_score） */
    void importLines(UUID id, ReconciliationImportRequest req);

    /** 人工匹配（updateMatchStatus 原子守卫 WHERE UNMATCHED，0 行则 40900 防双配） */
    void matchLine(UUID reconciliationId, MatchLineRequest req);

    /** 确认（DRAFT 转 CONFIRMED；全部行处理完方可确认，确认后禁改） */
    ReconciliationView confirm(UUID id);

    /** 软删对账单（仅 DRAFT） */
    void deleteReconciliation(UUID id);
}
