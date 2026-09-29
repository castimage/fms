package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.ApInvoiceMapper;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.PaymentAllocationMapper;
import net.castimage.fms_backend.Mapper.PaymentMapper;
import net.castimage.fms_backend.Mapper.ReconciliationLineMapper;
import net.castimage.fms_backend.Mapper.ReconciliationMapper;
import net.castimage.fms_backend.Mapper.SupplierMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.ApInvoiceRequest;
import net.castimage.fms_backend.Model.dto.MatchLineRequest;
import net.castimage.fms_backend.Model.dto.PaymentAllocateRequest;
import net.castimage.fms_backend.Model.dto.PaymentRequest;
import net.castimage.fms_backend.Model.dto.ReconciliationImportRequest;
import net.castimage.fms_backend.Model.dto.ReconciliationRequest;
import net.castimage.fms_backend.Model.dto.ReverseReceiptRequest;
import net.castimage.fms_backend.Model.dto.SupplierRequest;
import net.castimage.fms_backend.Model.entity.ApInvoice;
import net.castimage.fms_backend.Model.entity.Payment;
import net.castimage.fms_backend.Model.entity.PaymentAllocation;
import net.castimage.fms_backend.Model.entity.Reconciliation;
import net.castimage.fms_backend.Model.entity.ReconciliationLine;
import net.castimage.fms_backend.Model.entity.Supplier;
import net.castimage.fms_backend.Model.vo.AllocationView;
import net.castimage.fms_backend.Model.vo.ApAgingRow;
import net.castimage.fms_backend.Model.vo.ApInvoiceView;
import net.castimage.fms_backend.Model.vo.MatchCandidate;
import net.castimage.fms_backend.Model.vo.PaymentView;
import net.castimage.fms_backend.Model.vo.ReconciliationDetailView;
import net.castimage.fms_backend.Model.vo.ReconciliationLineView;
import net.castimage.fms_backend.Model.vo.ReconciliationView;
import net.castimage.fms_backend.Model.vo.SupplierView;
import net.castimage.fms_backend.Service.ApService;
import net.castimage.fms_backend.Service.VoucherLinkService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 应付模块业务实现，供应商/发票/付款状态机，与 ar 同构，不可变触发器终审，付款核销
 * 防重双闸，uk_ap_alloc 唯一约束 + version 乐观锁，供应商对账为核对动作（行导入 后
 * 候选建议，经人工匹配原子守卫，全部处理完再确认），matched_invoice_id 仅回填不核销，
 * 异常统一 40900。发票/付款单↔凭证联动（voucher_id），开票/付款登记/核销时经
 * VoucherLinkService 尽力生成 DRAFT 凭证（失败 WARN + 单据继续，可经
 * POST /api/ap/invoices/{id}/link-voucher 补生成）。
 */
@Service
public class ApServiceImpl implements ApService {

    private static final Logger log = LoggerFactory.getLogger(ApServiceImpl.class);

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 金额零值 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 供应商状态白名单，db CHECK 同构 */
    private static final Set<String> SUPPLIER_STATUSES = Set.of("ACTIVE", "INACTIVE", "BLOCKED");

    /** 发票状态白名单，状态机全集 */
    private static final Set<String> INVOICE_STATUSES = Set.of("DRAFT", "ISSUED", "PARTIAL", "PAID", "VOID");

    /** 可核销发票状态 */
    private static final Set<String> ALLOCABLE_INVOICE_STATUSES = Set.of("ISSUED", "PARTIAL");

    /** 可作废发票状态 */
    private static final Set<String> VOIDABLE_INVOICE_STATUSES = Set.of("ISSUED", "PARTIAL");

    /** 对账单状态白名单 */
    private static final Set<String> RECONCILIATION_STATUSES = Set.of("DRAFT", "CONFIRMED", "VOID");

    /** 自动建议相似度阈值，top1 > 0.6 才建议，否则留人工 */
    private static final BigDecimal SUGGEST_THRESHOLD = new BigDecimal("0.6");

    /** 候选匹配扫描上限 */
    private static final int CANDIDATE_LIMIT = 10;

    private final SupplierMapper supplierMapper;
    private final ApInvoiceMapper apInvoiceMapper;
    private final PaymentMapper paymentMapper;
    private final PaymentAllocationMapper paymentAllocationMapper;
    private final ReconciliationMapper reconciliationMapper;
    private final ReconciliationLineMapper reconciliationLineMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final VoucherLinkService voucherLinkService;

    public ApServiceImpl(SupplierMapper supplierMapper, ApInvoiceMapper apInvoiceMapper,
                         PaymentMapper paymentMapper, PaymentAllocationMapper paymentAllocationMapper,
                         ReconciliationMapper reconciliationMapper,
                         ReconciliationLineMapper reconciliationLineMapper,
                         AuthFunctionMapper authFunctionMapper,
                         VoucherLinkService voucherLinkService) {
        this.supplierMapper = supplierMapper;
        this.apInvoiceMapper = apInvoiceMapper;
        this.paymentMapper = paymentMapper;
        this.paymentAllocationMapper = paymentAllocationMapper;
        this.reconciliationMapper = reconciliationMapper;
        this.reconciliationLineMapper = reconciliationLineMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.voucherLinkService = voucherLinkService;
    }

    // 供应商

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<SupplierView> pageSuppliers(long page, long size, UUID companyId, String keyword) {
        return PageResult.of(supplierMapper.selectSupplierPage(new Page<>(Math.max(page, 1),
                Math.clamp(size, 1, 100)), companyId, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public SupplierView getSupplier(UUID id) {
        return toSupplierView(supplierMapper.selectById(id)); // rls 空集则 null，映射 data:null
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public SupplierView createSupplier(SupplierRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("供应商编码/名称必填");
        }
        if (req.status() != null) {
            validateEnum(req.status(), SUPPLIER_STATUSES, "供应商状态");
        }
        Supplier s = new Supplier();
        s.setId(UuidV7.next());
        s.setCompanyId(req.companyId());
        s.setCode(req.code().trim());
        s.setName(req.name().trim());
        s.setTaxNo(req.taxNo());
        s.setCreditLimit(money(req.creditLimit()));
        s.setStatus(req.status() == null ? "ACTIVE" : req.status());
        supplierMapper.insert(s); // uk_ap_supplier_code 冲突 23505 映射 40900，全局映射
        return toSupplierView(s);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public SupplierView updateSupplier(UUID id, SupplierRequest req) {
        RequestContext.requireWriteRole();
        Supplier s = requireSupplier(id);
        Supplier patch = new Supplier();
        patch.setId(id);
        if (req.code() != null && !req.code().isBlank()) {
            patch.setCode(req.code().trim());
        }
        if (req.name() != null && !req.name().isBlank()) {
            patch.setName(req.name().trim());
        }
        if (req.taxNo() != null) {
            patch.setTaxNo(req.taxNo());
        }
        if (req.creditLimit() != null) {
            patch.setCreditLimit(money(req.creditLimit()));
        }
        if (req.status() != null) {
            validateEnum(req.status(), SUPPLIER_STATUSES, "供应商状态");
            patch.setStatus(req.status());
        }
        supplierMapper.updateById(patch);
        return toSupplierView(supplierMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteSupplier(UUID id) {
        RequestContext.requireWriteRole();
        requireSupplier(id);

        // 引用检查，软删前，存在未删除的发票/付款单引用该供应商则 40900 阻断，
        // 防"删供应商后历史单据失去往来方语义"，与 ArServiceImpl.deleteCustomer 同族
        long invoiceRefs = apInvoiceMapper.selectCount(new LambdaQueryWrapper<ApInvoice>()
                .isNull(ApInvoice::getDeletedAt)
                .eq(ApInvoice::getSupplierId, id));
        long paymentRefs = paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                .isNull(Payment::getDeletedAt)
                .eq(Payment::getSupplierId, id));
        if (invoiceRefs + paymentRefs > 0) {
            throw new ConflictException("供应商存在未删除的发票/付款单据（" + invoiceRefs + "/" + paymentRefs
                    + " 张），无法删除");
        }

        // 软删统一经 auth.fn_soft_delete 闸口，SECURITY DEFINER，rls 新行可见性检查豁免
        if (!authFunctionMapper.fnSoftDelete("ap", "suppliers", id)) {
            throw new ConflictException("删除失败：供应商可能已不可见");
        }
    }

    // 发票

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ApInvoiceView> pageInvoices(long page, long size, UUID companyId,
                                                  UUID supplierId, String status, String keyword) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, INVOICE_STATUSES, "发票状态");
        }
        return PageResult.of(apInvoiceMapper.selectInvoicePage(new Page<>(Math.max(page, 1),
                Math.clamp(size, 1, 100)), companyId, supplierId, status, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ApInvoiceView getInvoice(UUID id) {
        return apInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ApInvoiceView createInvoice(ApInvoiceRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        Supplier supplier = requireSupplier(req.supplierId());
        if (!supplier.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("供应商与发票公司不一致");
        }
        BigDecimal total = money(req.total());
        if (total.signum() <= 0) {
            throw new BadRequestException("发票总额必须大于 0");
        }
        ApInvoice inv = new ApInvoice();
        inv.setId(UuidV7.next());
        inv.setCompanyId(req.companyId());
        inv.setSupplierId(req.supplierId());
        inv.setInvoiceNo(req.invoiceNo().trim());
        inv.setInvoiceDate(req.invoiceDate());
        inv.setDueDate(req.dueDate());
        inv.setCurrency(trimCurrency(req.currency()));
        inv.setTotal(total);
        inv.setTax(money(req.tax()));
        inv.setStatus("DRAFT");
        inv.setVersion(0);
        apInvoiceMapper.insert(inv); // uk_ap_invoice_no 冲突 23505 映射 40900
        return apInvoiceMapper.selectDetail(inv.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ApInvoiceView updateInvoice(UUID id, ApInvoiceRequest req) {
        RequestContext.requireWriteRole();
        ApInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可修改（当前 " + inv.getStatus() + "，开票后不可变）");
        }
        ApInvoice patch = new ApInvoice();
        patch.setId(id);
        if (req.invoiceNo() != null && !req.invoiceNo().isBlank()) {
            patch.setInvoiceNo(req.invoiceNo().trim());
        }
        if (req.invoiceDate() != null) {
            patch.setInvoiceDate(req.invoiceDate());
        }
        if (req.dueDate() != null) {
            patch.setDueDate(req.dueDate());
        }
        if (req.currency() != null) {
            patch.setCurrency(trimCurrency(req.currency()));
        }
        if (req.total() != null) {
            BigDecimal total = money(req.total());
            if (total.signum() <= 0) {
                throw new BadRequestException("发票总额必须大于 0");
            }
            patch.setTotal(total);
        }
        if (req.tax() != null) {
            patch.setTax(money(req.tax()));
        }
        // supplierId 禁改，companyId/status/version 禁改
        apInvoiceMapper.updateById(patch);
        return apInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ApInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可删除（当前 " + inv.getStatus() + "，ISSUED+ 被不可变触发器拒绝）");
        }
        if (!authFunctionMapper.fnSoftDelete("ap", "invoices", id)) {
            throw new ConflictException("删除失败：发票可能已不可见");
        }
    }

    /**
     * {@inheritDoc}
     *
     * 开票（DRAFT 转 ISSUED），预检发票号/供应商/金额后推进状态，ISSUED 之后发票进入
     * 资金闭环，trg_ap_invoice_immutable 终审，ISSUED/PARTIAL 仅可作废、PAID/VOID
     * 禁改禁删，预检通过但更新 0 行，并发/状态已变/rls 不可见一律 40900。
     *
     * @param id 发票 id（须 DRAFT）
     * @return 开票后详情
     * @throws ConflictException 状态非法或并发冲突，40900
     */
    @Override
    @Transactional
    public ApInvoiceView issueInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ApInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可开票（当前 " + inv.getStatus() + "）");
        }
        if (inv.getInvoiceNo() == null || inv.getInvoiceNo().isBlank()) {
            throw new BadRequestException("发票号必填");
        }
        if (money(inv.getTotal()).signum() <= 0) {
            throw new BadRequestException("发票总额必须大于 0");
        }
        ApInvoice patch = new ApInvoice();
        patch.setId(id);
        patch.setStatus("ISSUED");
        if (apInvoiceMapper.updateById(patch) == 0) {
            throw new ConflictException("开票失败：状态已变更或不可见");
        }

        // 凭证联动，尽力生成，同事务，开票即生成应付凭证 DRAFT，借费用/贷应付并回填
        // voucher_id，失败不影响开票主流程（WARN + voucher_id 留空，可经补生成端点重试）
        try {
            voucherLinkService.linkApInvoice(id);
        } catch (RuntimeException e) {
            log.warn("应付发票 {} 联动凭证失败（voucher_id 留空，可经 POST /api/ap/invoices/{}/link-voucher 补生成）：{}",
                    id, id, e.getMessage());
        }
        return apInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkInvoiceVoucher(UUID id) {
        RequestContext.requireWriteRole();
        ApInvoice inv = requireInvoice(id);
        if (inv.getVoucherId() != null) {
            throw new ConflictException("发票已联动凭证（voucher_id=" + inv.getVoucherId() + "），请勿重复补生成");
        }
        if (!"ISSUED".equals(inv.getStatus())) {
            throw new ConflictException("仅 ISSUED 状态发票可补生成联动凭证（当前 " + inv.getStatus() + "）");
        }
        // 补生成与开票链路同实现，凭证 DRAFT + 回填 voucher_id，幂等键由发票派生防重
        return voucherLinkService.linkApInvoice(id);
    }

    /**
     * {@inheritDoc}
     *
     * 作废（ISSUED/PARTIAL 转 VOID），不可变触发器允许的唯一已开票迁移，作废后
     * 同 VOID 状态禁改禁删。Java 预检 + 0 行，并发/已作废则 40900。
     *
     * @param id 发票 id（须 ISSUED/PARTIAL）
     * @return 作废后详情
     * @throws ConflictException 状态非法，40900
     */
    @Override
    @Transactional
    public ApInvoiceView voidInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ApInvoice inv = requireInvoice(id);
        if (!VOIDABLE_INVOICE_STATUSES.contains(inv.getStatus())) {
            throw new ConflictException("仅 ISSUED/PARTIAL 状态发票可作废（当前 " + inv.getStatus() + "）");
        }
        ApInvoice patch = new ApInvoice();
        patch.setId(id);
        patch.setStatus("VOID");
        if (apInvoiceMapper.updateById(patch) == 0) {
            throw new ConflictException("作废失败：状态已变更或不可见");
        }
        return apInvoiceMapper.selectDetail(id);
    }

    // 付款与核销

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<PaymentView> pagePayments(long page, long size, UUID companyId,
                                                UUID supplierId, String status) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, Set.of("PAID", "ALLOCATED", "REVERSED"), "付款单状态");
        }
        return PageResult.of(paymentMapper.selectPaymentPage(new Page<>(Math.max(page, 1),
                Math.clamp(size, 1, 100)), companyId, supplierId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PaymentView getPayment(UUID id) {
        return paymentMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public PaymentView createPayment(PaymentRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        Supplier supplier = requireSupplier(req.supplierId());
        if (!supplier.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("供应商与付款单公司不一致");
        }
        BigDecimal amount = money(req.amount());
        if (amount.signum() <= 0) {
            throw new BadRequestException("付款金额必须大于 0");
        }

        // 幂等双闸 ① 预检，同公司/供应商/银行账户/金额/付款日期 已存在有效付款单则 40900，防客户端重复提交/重试产生重复付款，uk_ap_payments_dup 部分唯一索引为②并发兜底
        UUID dupKey = paymentDupKey(req.companyId(), req.supplierId(), req.bankAccountId(),
                amount, req.paymentDate());
        if (paymentMapper.selectActiveByDupKey(req.companyId(), dupKey) != null) {
            throw new ConflictException("重复付款登记：相同公司/供应商/银行账户/金额/付款日期"
                    + "已存在有效付款单，请勿重复提交");
        }
        Payment p = new Payment();
        p.setId(UuidV7.next());
        p.setCompanyId(req.companyId());
        p.setSupplierId(req.supplierId());
        p.setAmount(amount);
        p.setBankAccountId(req.bankAccountId());
        p.setPaymentDate(req.paymentDate());
        p.setStatus("PAID");
        p.setDupKey(dupKey);
        paymentMapper.insert(p); // uk_ap_payments_dup 并发冲突 23505 映射 40900，全局映射

        // 凭证联动，尽力生成，同事务，付款登记即生成付款凭证 DRAFT（借应付/贷银行，
        // 金额=实付）并回填 voucher_id，失败不影响付款登记主流程，WARN + 留空，核销时再试
        try {
            voucherLinkService.linkPayment(p.getId());
        } catch (RuntimeException e) {
            log.warn("付款单 {} 联动凭证失败（voucher_id 留空，核销时将重试）：{}", p.getId(), e.getMessage());
        }
        return paymentMapper.selectDetail(p.getId());
    }

    /**
     * 付款幂等键，公司/供应商/银行账户/金额/付款日期 规范串生成确定性 uuid（MD5 name-based）。
     *
     * 金额以规范化 scale=2 参与，1000 与 1000.00 同键，银行账户为空以空串参与。
     */
    private UUID paymentDupKey(UUID companyId, UUID supplierId, UUID bankAccountId,
                               BigDecimal amount, LocalDate paymentDate) {
        String canonical = companyId + "|" + supplierId + "|"
                + (bankAccountId == null ? "" : bankAccountId) + "|"
                + amount.toPlainString() + "|" + paymentDate;
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deletePayment(UUID id) {
        RequestContext.requireWriteRole();
        Payment p = requirePayment(id);
        if (!"PAID".equals(p.getStatus())) {
            throw new ConflictException("仅 PAID 未核销付款单可删除（当前 " + p.getStatus() + "）");
        }
        if (!authFunctionMapper.fnSoftDelete("ap", "payments", id)) {
            throw new ConflictException("删除失败：付款单可能已不可见");
        }
    }

    /**
     * {@inheritDoc}
     *
     * 付款核销入口，一次核销多张发票，事务内顺序执行，防重双闸 + 防并发超分，
     *
     * FOR UPDATE 锁付款单（selectByIdForUpdate，写事务内），并发核销

     * 同一付款单串行化，status 须 PAID，锁内校验，
     * 逐张发票预检，status 须 ISSUED/PARTIAL + 累计已核销 + 本次 ≤ total

     * （超分则 40900），同时累加本次核销合计，不得超付款单金额，
     * 批量 INSERT payment_allocations，uk_ap_alloc(payment_id, invoice_id)

     * 唯一约束防同对重复核销（23505 映射 40900），version 乐观锁写入 0，
     * 发票累计 = total 则置 PAID，否则 PARTIAL，不可变触发器终审，

     * 核销合计 = 付款金额则付款单置 ALLOCATED。
     *
     * 付款单到凭证联动（voucher_id）为尽力生成，付款登记时已联动则此处跳过，幂等，
     * 存量未联动付款单在核销时补联动。
     *
     * @param paymentId 付款单 id，路径
     * @param items     核销项列表（invoiceId + amount）
     * @return 新建核销明细列表
     * @throws ConflictException 付款单状态非法/超分/重复核销/发票不可核销，40900
     */
    @Override
    @Transactional
    public List<AllocationView> allocate(UUID paymentId, List<PaymentAllocateRequest> items) {
        RequestContext.requireWriteRole();
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("核销项不能为空");
        }
        // 锁付款单，防并发核销超分，锁内校验状态
        Payment payment = paymentMapper.selectByIdForUpdate(paymentId);
        if (payment == null) {
            throw new NotFoundException("付款单不存在");
        }
        if (!"PAID".equals(payment.getStatus())) {
            throw new ConflictException("仅 PAID 状态付款单可核销（当前 " + payment.getStatus() + "）");
        }
        // 发票余额预检 + 本次合计预检
        BigDecimal paymentAmount = money(payment.getAmount());
        BigDecimal totalAllocated = ZERO;
        List<ApInvoice> invoices = new ArrayList<>();
        for (PaymentAllocateRequest item : items) {
            ApInvoice invoice = requireInvoice(item.invoiceId());
            if (!ALLOCABLE_INVOICE_STATUSES.contains(invoice.getStatus())) {
                throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 状态不可核销（当前 "
                        + invoice.getStatus() + "，须 ISSUED/PARTIAL）");
            }
            BigDecimal amount = money(item.amount());
            if (amount.signum() <= 0) {
                throw new BadRequestException("核销金额必须大于 0");
            }
            BigDecimal paid = money(paymentAllocationMapper.selectPaidSumByInvoice(invoice.getId()));
            if (paid.add(amount).compareTo(money(invoice.getTotal())) > 0) {
                throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 核销金额超过未核销余额（已核销 "
                        + paid + "，总额 " + invoice.getTotal() + "）");
            }
            totalAllocated = totalAllocated.add(amount);
            invoices.add(invoice);
        }
        if (totalAllocated.compareTo(paymentAmount) > 0) {
            throw new ConflictException("核销合计 " + totalAllocated + " 超过付款单金额 " + paymentAmount);
        }
        // 批量插入核销明细（uk_ap_alloc 唯一约束防同对重复核销，23505 映射 40900）
        List<AllocationView> result = new ArrayList<>();
        int index = 0;
        for (PaymentAllocateRequest item : items) {
            ApInvoice invoice = invoices.get(index);
            PaymentAllocation alloc = new PaymentAllocation();
            alloc.setId(UuidV7.next());
            alloc.setCompanyId(invoice.getCompanyId());
            alloc.setPaymentId(paymentId);
            alloc.setInvoiceId(invoice.getId());
            alloc.setAmount(money(item.amount()));
            alloc.setVersion(0); // 乐观锁起始版本，
            paymentAllocationMapper.insert(alloc);
            result.add(new AllocationView(alloc.getId(), paymentId, invoice.getId(),
                    invoice.getInvoiceNo(), invoice.getInvoiceDate(), alloc.getAmount(), 0, LocalDateTime.now()));
            index++;
        }
        // 发票状态推进，迁移合法性由不可变触发器终审 + 付款单核销完毕置 ALLOCATED
        for (ApInvoice invoice : invoices) {
            BigDecimal paid = money(paymentAllocationMapper.selectPaidSumByInvoice(invoice.getId()));
            boolean fullyPaid = paid.compareTo(money(invoice.getTotal())) >= 0;
            if (apInvoiceMapper.update(null, new LambdaUpdateWrapper<ApInvoice>()
                    .eq(ApInvoice::getId, invoice.getId())
                    .in(ApInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                    .set(ApInvoice::getStatus, fullyPaid ? "PAID" : "PARTIAL")) == 0) {
                throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 状态推进失败：状态已变更");
            }
        }
        if (totalAllocated.compareTo(paymentAmount) == 0) {
            if (paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                    .eq(Payment::getId, paymentId)
                    .eq(Payment::getStatus, "PAID")
                    .set(Payment::getStatus, "ALLOCATED")) == 0) {
                throw new ConflictException("付款单状态推进失败：状态已变更");
            }
        }
        // 凭证联动，尽力生成，同事务，付款登记未联动，存量单/登记时失败的付款单在
        // 核销时补联动，已联动幂等跳过，失败不影响核销主流程
        try {
            voucherLinkService.linkPayment(paymentId);
        } catch (RuntimeException e) {
            log.warn("付款单 {} 核销后联动凭证失败（voucher_id 留空）：{}", paymentId, e.getMessage());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * 撤销核销，仅发票仍 PARTIAL 可撤，乐观锁条件软删（version 不匹配/已撤销则
     * 0 行则 40900），并回算发票状态（剩余已核销 = 0 则回 ISSUED，否则保持 PARTIAL）。
     *
     * PAID 发票的核销撤销被不可变触发器拒绝，Java 预检提前失败。
     *
     * @param allocationId 核销明细 id
     * @throws ConflictException 发票已 PAID/核销不存在/版本冲突，40900
     */
    @Override
    @Transactional
    public void unallocate(UUID allocationId) {
        RequestContext.requireWriteRole();
        PaymentAllocation alloc = paymentAllocationMapper.selectById(allocationId);
        if (alloc == null) {
            throw new NotFoundException("核销明细不存在");
        }
        ApInvoice invoice = requireInvoice(alloc.getInvoiceId());
        if (!"PARTIAL".equals(invoice.getStatus())) {
            throw new ConflictException("仅 PARTIAL 状态发票可撤销核销（当前 " + invoice.getStatus()
                    + "，PAID 发票撤销被不可变触发器拒绝）");
        }
        int version = alloc.getVersion() == null ? 0 : alloc.getVersion();
        if (paymentAllocationMapper.softDeleteWithVersion(allocationId, version) == 0) {
            throw new ConflictException("撤销核销失败：核销记录版本已变更");
        }
        BigDecimal remaining = money(paymentAllocationMapper.selectPaidSumByInvoice(invoice.getId()));
        String newStatus = remaining.signum() <= 0 ? "ISSUED" : "PARTIAL";
        apInvoiceMapper.update(null, new LambdaUpdateWrapper<ApInvoice>()
                .eq(ApInvoice::getId, invoice.getId())
                .in(ApInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                .set(ApInvoice::getStatus, newStatus));
    }

    /**
     * {@inheritDoc}
     *
     * 付款冲销（PAID/ALLOCATED 转 REVERSED），软性动作，置状态 + 软删该付款单全部
     * 核销明细，带版本条件逐条撤销，并回算受影响发票状态，ap.payments 无状态机
     * 触发器，Java 预检即可，若受影响发票已 PAID，冲销触发不可变触发器拒绝则
     * Java 预检直接 40900。
     *
     * @param id  付款单 id
     * @param req 冲销原因，审计追溯
     * @throws ConflictException 付款单已冲销/受影响发票已 PAID，40900
     */
    @Override
    @Transactional
    public void reversePayment(UUID id, ReverseReceiptRequest req) {
        RequestContext.requireWriteRole();
        Payment payment = requirePayment(id);
        if ("REVERSED".equals(payment.getStatus())) {
            throw new ConflictException("付款单已冲销，禁止重复冲销");
        }
        List<AllocationView> allocations = paymentAllocationMapper.selectByPaymentId(id);
        for (AllocationView view : allocations) {
            ApInvoice invoice = apInvoiceMapper.selectById(view.invoiceId());
            if (invoice != null && "PAID".equals(invoice.getStatus())) {
                throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 已核销完毕（PAID），无法冲销该付款单");
            }
        }
        for (AllocationView view : allocations) {
            int version = view.version() == null ? 0 : view.version();
            if (paymentAllocationMapper.softDeleteWithVersion(view.id(), version) == 0) {
                throw new ConflictException("冲销失败：核销记录版本已变更");
            }
            ApInvoice invoice = apInvoiceMapper.selectById(view.invoiceId());
            if (invoice != null) {
                BigDecimal remaining = money(paymentAllocationMapper.selectPaidSumByInvoice(invoice.getId()));
                String newStatus = remaining.signum() <= 0 ? "ISSUED" : "PARTIAL";
                apInvoiceMapper.update(null, new LambdaUpdateWrapper<ApInvoice>()
                        .eq(ApInvoice::getId, invoice.getId())
                        .in(ApInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                        .set(ApInvoice::getStatus, newStatus));
            }
        }
        if (req != null && req.reason() != null && !req.reason().isBlank()) {
            log.info("付款单 {} 冲销，原因：{}", id, req.reason());
        }
        paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                .eq(Payment::getId, id)
                .ne(Payment::getStatus, "REVERSED")
                .set(Payment::getStatus, "REVERSED"));
    }

    // 供应商对账

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReconciliationView> pageReconciliations(long page, long size, UUID companyId,
                                                              UUID supplierId, String status) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, RECONCILIATION_STATUSES, "对账单状态");
        }
        return PageResult.of(reconciliationMapper.selectReconciliationPage(new Page<>(Math.max(page, 1),
                Math.clamp(size, 1, 100)), companyId, supplierId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ReconciliationDetailView getReconciliation(UUID id) {
        ReconciliationView header = reconciliationMapper.selectDetail(id);
        if (header == null) {
            return null; // rls 空集则 data:null，不泄露存在性
        }
        return new ReconciliationDetailView(header,
                reconciliationLineMapper.selectByReconciliationId(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReconciliationView createReconciliation(ReconciliationRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        Supplier supplier = requireSupplier(req.supplierId());
        if (!supplier.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("供应商与对账单公司不一致");
        }
        Reconciliation r = new Reconciliation();
        r.setId(UuidV7.next());
        r.setCompanyId(req.companyId());
        r.setSupplierId(req.supplierId());
        r.setStatementDate(req.statementDate());
        r.setStatus("DRAFT");
        reconciliationMapper.insert(r);
        return reconciliationMapper.selectDetail(r.getId());
    }

    /**
     * {@inheritDoc}
     *
     * 对账单行导入，全部落库 UNMATCHED，逐行做候选匹配建议，金额相等 + 日期差≤3 天
     * + similarity(摘要, 发票号) 降序取 top1，相似度 > 0.6 回写 similarity_score
     * 供 ui 展示建议，不自动匹配，留待人工 matchLine 或放弃，对账为核对动作，
     * matched_invoice_id 仅回填不核销，核销走 payment_allocations，与对账解耦。
     *
     * @param id  对账单 id（须 DRAFT）
     * @param req 行列表
     * @throws ConflictException 对账单非 DRAFT，40900
     */
    @Override
    @Transactional
    public void importLines(UUID id, ReconciliationImportRequest req) {
        RequestContext.requireWriteRole();
        Reconciliation reconciliation = requireReconciliation(id);
        if (!"DRAFT".equals(reconciliation.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态对账单可导入行（当前 " + reconciliation.getStatus() + "，确认后禁改）");
        }
        for (ReconciliationImportRequest.ReconciliationLineRequest line : req.lines()) {
            ReconciliationLine entity = new ReconciliationLine();
            entity.setId(UuidV7.next());
            entity.setCompanyId(reconciliation.getCompanyId());
            entity.setReconciliationId(id);
            entity.setTrxDate(line.trxDate());
            entity.setAmount(money(line.amount()));
            entity.setSummary(line.summary());
            entity.setMatchStatus("UNMATCHED");

            // 候选建议，top1 > 0.6 回写 similarity_score，仅建议，不自动匹配
            List<MatchCandidate> candidates = apInvoiceMapper.selectMatchCandidates(
                    reconciliation.getCompanyId(), line.trxDate(), money(line.amount()),
                    line.summary(), CANDIDATE_LIMIT);
            if (!candidates.isEmpty() && candidates.getFirst().similarity() != null
                    && candidates.getFirst().similarity().compareTo(SUGGEST_THRESHOLD) > 0) {
                entity.setSimilarityScore(candidates.getFirst().similarity());
            }
            reconciliationLineMapper.insert(entity);
        }
    }

    /**
     * {@inheritDoc}
     *
     * 对账行人工匹配，updateMatchStatus 原子守卫（WHERE match_status='UNMATCHED'，
     * 0 行则 40900"行已被匹配"，防并发双配），发票须 ISSUED/PARTIAL，matched_invoice_id
     * 仅回填不核销，与对账解耦，注释明确。
     *
     * @param reconciliationId 对账单 id（路径，仅 DRAFT 可匹配）
     * @param req              lineId + invoiceId
     * @throws ConflictException 对账单非 DRAFT/行已被消费/发票不可匹配，40900
     */
    @Override
    @Transactional
    public void matchLine(UUID reconciliationId, MatchLineRequest req) {
        RequestContext.requireWriteRole();
        Reconciliation reconciliation = requireReconciliation(reconciliationId);
        if (!"DRAFT".equals(reconciliation.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态对账单可匹配（当前 " + reconciliation.getStatus() + "，确认后禁改）");
        }
        ApInvoice invoice = requireInvoice(req.invoiceId());
        if (!ALLOCABLE_INVOICE_STATUSES.contains(invoice.getStatus())) {
            throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 状态不可匹配（当前 "
                    + invoice.getStatus() + "，须 ISSUED/PARTIAL）");
        }
        // 原子守卫，仅 UNMATCHED 可被消费，防并发双配，0 行则 40900
        if (reconciliationLineMapper.updateMatchStatus(req.lineId(), "MATCHED", req.invoiceId()) == 0) {
            throw new ConflictException("对账单行已被匹配或不存在，禁止重复匹配");
        }
    }

    /**
     * {@inheritDoc}
     *
     * 确认对账单（DRAFT 转 CONFIRMED），预检全部行已处理，无 UNMATCHED 残留，对账
     * 为核对动作不改发票/付款状态，确认后禁改（Java 预检 + updateById 0 行则 40900）。
     *
     * @param id 对账单 id（须 DRAFT 且全部行已处理）
     * @return 确认后详情
     * @throws ConflictException 存在未匹配行/状态非法，40900
     */
    @Override
    @Transactional
    public ReconciliationView confirm(UUID id) {
        RequestContext.requireWriteRole();
        Reconciliation reconciliation = requireReconciliation(id);
        if (!"DRAFT".equals(reconciliation.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态对账单可确认（当前 " + reconciliation.getStatus() + "）");
        }
        if (reconciliationLineMapper.countUnmatched(id) > 0) {
            throw new ConflictException("存在未匹配的对账单行，请先完成匹配再确认");
        }
        Reconciliation patch = new Reconciliation();
        patch.setId(id);
        patch.setStatus("CONFIRMED");
        if (reconciliationMapper.updateById(patch) == 0) {
            throw new ConflictException("确认失败：状态已变更或不可见");
        }
        return reconciliationMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteReconciliation(UUID id) {
        RequestContext.requireWriteRole();
        Reconciliation reconciliation = requireReconciliation(id);
        if (!"DRAFT".equals(reconciliation.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态对账单可删除（当前 " + reconciliation.getStatus() + "，确认后禁改）");
        }
        // 级联软删行明细，保持查询不残留，逐行经统一闸口
        List<ReconciliationLine> lines = reconciliationLineMapper.selectList(
                new LambdaQueryWrapper<ReconciliationLine>()
                        .eq(ReconciliationLine::getReconciliationId, id)
                        .isNull(ReconciliationLine::getDeletedAt));
        for (ReconciliationLine line : lines) {
            authFunctionMapper.fnSoftDelete("ap", "reconciliation_lines", line.getId());
        }
        if (!authFunctionMapper.fnSoftDelete("ap", "reconciliations", id)) {
            throw new ConflictException("删除失败：对账单可能已不可见");
        }
    }

    // 账龄

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<ApAgingRow> aging(UUID companyId) {
        // 直查 rpt.ap_aging 视图（ISSUED/PARTIAL + 未核销余额 = total - rpt.ap_aging_paid immv
        // 已核销，security_invoker=on 行级收敛，越权公司返回空集），分桶与汇总在 sql 内以
        // 窗口函数完成，B 类手写，镜像 ArServiceImpl.aging
        return apInvoiceMapper.selectAging(companyId);
    }

    // 私有方法

    /** 按 id 取供应商（rls 空集则 40400，写路径显式报错） */
    private Supplier requireSupplier(UUID id) {
        Supplier s = supplierMapper.selectById(id);
        if (s == null) {
            throw new NotFoundException("供应商不存在");
        }
        return s;
    }

    /** 按 id 取发票（rls 空集则 40400） */
    private ApInvoice requireInvoice(UUID id) {
        ApInvoice inv = apInvoiceMapper.selectById(id);
        if (inv == null) {
            throw new NotFoundException("发票不存在");
        }
        return inv;
    }

    /** 按 id 取付款单（rls 空集则 40400） */
    private Payment requirePayment(UUID id) {
        Payment p = paymentMapper.selectById(id);
        if (p == null) {
            throw new NotFoundException("付款单不存在");
        }
        return p;
    }

    /** 按 id 取对账单（rls 空集则 40400） */
    private Reconciliation requireReconciliation(UUID id) {
        Reconciliation r = reconciliationMapper.selectById(id);
        if (r == null) {
            throw new NotFoundException("对账单不存在");
        }
        return r;
    }

    /** 枚举白名单校验，db CHECK 终审兜底 */
    private void validateEnum(String value, Set<String> allowed, String label) {
        if (value != null && !allowed.contains(value)) {
            throw new BadRequestException(label + "非法：" + value);
        }
    }

    /** 实体转视图 */
    private SupplierView toSupplierView(Supplier s) {
        if (s == null) {
            return null;
        }
        return new SupplierView(s.getId(), s.getCompanyId(), s.getCode(), s.getName(),
                s.getTaxNo(), s.getCreditLimit(), s.getStatus(), s.getCreatedAt());
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 币种规范化，CHAR，3 大写 */
    private String trimCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "CNY";
        }
        String c = currency.trim();
        if (c.length() != 3) {
            throw new BadRequestException("币种须为 3 位 ISO 代码");
        }
        return c.toUpperCase(Locale.ROOT);
    }
}
