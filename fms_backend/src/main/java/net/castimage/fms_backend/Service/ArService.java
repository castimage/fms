package net.castimage.fms_backend.Service;

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

import java.util.List;
import java.util.UUID;

/**
 * 应收模块业务接口（客户/发票/收款/核销/坏账/账龄）：发票状态机
 * 经 DRAFT 开票 ISSUED、部分核销 PARTIAL、收清 PAID、可作废 VOID（trg_ar_invoice_immutable 终审）；核销防重双闸
 * （uk_ar_alloc 唯一约束 + version 乐观锁）；账龄直查 rpt.ar_aging 视图（B 类窗口 SQL）；
 * voucher_id 联动凭证（VoucherLinkService 尽力生成 + 补生成端点）。
 */
public interface ArService {

    // 客户

    /** 客户分页列表（trgm 模糊检索；RLS 收敛可见集合，companyId 仅过滤提示） */
    PageResult<CustomerView> pageCustomers(long page, long size, UUID companyId, String keyword);

    /** 客户详情（RLS 空集则返回 null，映射 data:null） */
    CustomerView getCustomer(UUID id);

    /** 创建客户（uk_ar_customer_code 冲突 23505 映射 40900） */
    CustomerView createCustomer(CustomerRequest req);

    /** 更新客户（code/name/status 等可改） */
    CustomerView updateCustomer(UUID id, CustomerRequest req);

    /** 软删客户（deleted_at=now()；存在未删除的发票/收款单引用则 40900 阻断） */
    void deleteCustomer(UUID id);

    // 发票（状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID）

    /** 发票分页列表（join 客户名；status/customerId/keyword 过滤） */
    PageResult<ArInvoiceView> pageInvoices(long page, long size, UUID companyId,
                                           UUID customerId, String status, String keyword);

    /** 发票详情（RLS 空集则返回 null） */
    ArInvoiceView getInvoice(UUID id);

    /** 创建 DRAFT 发票（客户须存在；total > 0 预检） */
    ArInvoiceView createInvoice(ArInvoiceRequest req);

    /** 更新 DRAFT 发票（开票后不可变，仅 DRAFT 可改） */
    ArInvoiceView updateInvoice(UUID id, ArInvoiceRequest req);

    /** 软删 DRAFT 发票（ISSUED+ 被不可变触发器拒绝则 40900） */
    void deleteInvoice(UUID id);

    /** 开票（DRAFT 转 ISSUED）：客户存在/金额>0 预检 + 触发器终审（ISSUED 后禁改禁删） */
    ArInvoiceView issueInvoice(UUID id);

    /** 作废（ISSUED/PARTIAL 转 VOID）：唯一允许的已开票状态迁移 */
    ArInvoiceView voidInvoice(UUID id);

    /**
     * 补生成发票联动凭证（POST /api/ar/invoices/{id}/link-voucher）：开票时联动失败
     * （voucher_id 留空）的补生成入口——仅 ISSUED 可补（DRAFT 未入账不生成、PAID/VOID
     * 终态不生成）；已联动（voucher_id 非空）则 40900。凭证 DRAFT + 回填 voucher_id，
     * 幂等键由发票派生（重复补生成复用既有凭证）。
     *
     * @return 联动凭证 id
     * @throws net.castimage.fms_backend.Exception.ConflictException 已联动/非 ISSUED（40900）
     */
    UUID linkInvoiceVoucher(UUID id);

    // 收款与核销（防重双闸）

    /** 收款单分页列表（join 客户/银行账户名） */
    PageResult<ReceiptView> pageReceipts(long page, long size, UUID companyId,
                                         UUID customerId, String status);

    /** 收款单详情（RLS 空集则返回 null） */
    ReceiptView getReceipt(UUID id);

    /** 收款登记（status=RECEIVED；amount > 0 预检） */
    ReceiptView createReceipt(ReceiptRequest req);

    /** 软删收款单（仅 RECEIVED 未核销可删） */
    void deleteReceipt(UUID id);

    /**
     * 核销（收款单对发票）：事务内 ① FOR UPDATE 锁收款单（防并发超分，status 须
     * RECEIVED）② 发票须 ISSUED/PARTIAL + 累计≤total ③ INSERT allocation
     * （uk_ar_alloc 防同对重复，23505 映射 40900）④ 累计=total 则发票置 PAID + 收款单
     * ALLOCATED，否则发票 PARTIAL（发票状态迁移由不可变触发器终审）。
     */
    AllocationView allocate(UUID receiptId, AllocateRequest req);

    /** 撤销核销（仅发票仍 PARTIAL 可撤）：乐观锁软删 + 回算发票状态（PAID 发票被触发器拒绝） */
    void unallocate(UUID allocationId);

    /** 收款冲销（RECEIVED/ALLOCATED 转 REVERSED）：清核销关联并回算受影响发票状态 */
    void reverseReceipt(UUID id, ReverseReceiptRequest req);

    // 账龄 / 坏账

    /** 账龄分析（rpt.ar_aging 视图 + 窗口函数分桶 0-30/31-60/61-90/91-180/180+） */
    List<AgingRow> aging(UUID companyId);

    /** 坏账计提分页列表 */
    PageResult<BadDebtView> pageBadDebts(long page, long size, UUID companyId, String status);

    /** 创建计提（(invoice_id, period) 查重；发票 PAID 禁提） */
    BadDebtView createBadDebt(BadDebtRequest req);

    /** 确认计提（DRAFT 转 CONFIRMED；确认后禁改禁删） */
    BadDebtView confirmBadDebt(UUID id);

    /** 软删计提（仅 DRAFT） */
    void deleteBadDebt(UUID id);
}
