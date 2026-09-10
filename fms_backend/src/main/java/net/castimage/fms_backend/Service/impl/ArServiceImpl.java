package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.ArInvoiceMapper;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BadDebtProvisionMapper;
import net.castimage.fms_backend.Mapper.CustomerMapper;
import net.castimage.fms_backend.Mapper.ReceiptAllocationMapper;
import net.castimage.fms_backend.Mapper.ReceiptMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AllocateRequest;
import net.castimage.fms_backend.Model.dto.ArInvoiceRequest;
import net.castimage.fms_backend.Model.dto.BadDebtRequest;
import net.castimage.fms_backend.Model.dto.CustomerRequest;
import net.castimage.fms_backend.Model.dto.ReceiptRequest;
import net.castimage.fms_backend.Model.dto.ReverseReceiptRequest;
import net.castimage.fms_backend.Model.entity.ArInvoice;
import net.castimage.fms_backend.Model.entity.BadDebtProvision;
import net.castimage.fms_backend.Model.entity.Customer;
import net.castimage.fms_backend.Model.entity.Receipt;
import net.castimage.fms_backend.Model.entity.ReceiptAllocation;
import net.castimage.fms_backend.Model.vo.AgingRow;
import net.castimage.fms_backend.Model.vo.AllocationView;
import net.castimage.fms_backend.Model.vo.ArInvoiceView;
import net.castimage.fms_backend.Model.vo.BadDebtView;
import net.castimage.fms_backend.Model.vo.CustomerView;
import net.castimage.fms_backend.Model.vo.ReceiptView;
import net.castimage.fms_backend.Service.ArService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 应收模块业务实现，发票状态机（经 DRAFT 开票 ISSUED、部分核销 PARTIAL、收清 PAID、可作废 VOID）Java 预检 + 不可变
 * 触发器终审，核销防重双闸，uk_ar_alloc 唯一约束 + version 乐观锁，B 类 updateWithVersion，
 * 收款登记防重，uk_ar_receipt_dedup 业务键部分唯一索引 + Java 预检，同键重复登记 40900，
 * 核销同对同金额重复请求 40900，防金额双计+ 收款单剩余可核销额预检，防循环累加超分，
 * 账龄窗口函数 sql 直查 rpt.ar_aging，异常统一 40900。发票/收款单↔凭证联动（voucher_id），
 * 开票/收款登记/核销时经 VoucherLinkService 尽力生成 DRAFT 凭证（失败 WARN + 单据继续，
 * 可经 POST /api/ar/invoices/{id}/link-voucher 补生成）。
 */
@Service
public class ArServiceImpl implements ArService {

    private static final Logger log = LoggerFactory.getLogger(ArServiceImpl.class);

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 金额零值 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 客户状态白名单，db CHECK 同构 */
    private static final Set<String> CUSTOMER_STATUSES = Set.of("ACTIVE", "INACTIVE", "BLOCKED");

    /** 发票状态白名单，状态机全集 */
    private static final Set<String> INVOICE_STATUSES = Set.of("DRAFT", "ISSUED", "PARTIAL", "PAID", "VOID");

    /** 可核销发票状态，ISSUED/PARTIAL，PAID/VOID/DRAFT 禁核销 */
    private static final Set<String> ALLOCABLE_INVOICE_STATUSES = Set.of("ISSUED", "PARTIAL");

    /** 可作废发票状态，唯一允许的已开票迁移 */
    private static final Set<String> VOIDABLE_INVOICE_STATUSES = Set.of("ISSUED", "PARTIAL");

    /** 坏账计提状态白名单 */
    private static final Set<String> BAD_DEBT_STATUSES = Set.of("DRAFT", "CONFIRMED", "REVERSED");

    private final CustomerMapper customerMapper;
    private final ArInvoiceMapper arInvoiceMapper;
    private final ReceiptMapper receiptMapper;
    private final ReceiptAllocationMapper receiptAllocationMapper;
    private final BadDebtProvisionMapper badDebtProvisionMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final VoucherLinkService voucherLinkService;

    public ArServiceImpl(CustomerMapper customerMapper, ArInvoiceMapper arInvoiceMapper,
                         ReceiptMapper receiptMapper, ReceiptAllocationMapper receiptAllocationMapper,
                         BadDebtProvisionMapper badDebtProvisionMapper,
                         AuthFunctionMapper authFunctionMapper,
                         VoucherLinkService voucherLinkService) {
        this.customerMapper = customerMapper;
        this.arInvoiceMapper = arInvoiceMapper;
        this.receiptMapper = receiptMapper;
        this.receiptAllocationMapper = receiptAllocationMapper;
        this.badDebtProvisionMapper = badDebtProvisionMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.voucherLinkService = voucherLinkService;
    }

    // 客户

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<CustomerView> pageCustomers(long page, long size, UUID companyId, String keyword) {
        return PageResult.of(customerMapper.selectCustomerPage(new Page<>(Math.max(page, 1),
                Math.min(Math.max(size, 1), 100)), companyId, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public CustomerView getCustomer(UUID id) {
        return toCustomerView(customerMapper.selectById(id)); // rls 空集则 null，映射 data:null
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public CustomerView createCustomer(CustomerRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("客户编码/名称必填");
        }
        if (req.status() != null) {
            validateEnum(req.status(), CUSTOMER_STATUSES, "客户状态");
        }
        Customer c = new Customer();
        c.setId(UuidV7.next());
        c.setCompanyId(req.companyId());
        c.setCode(req.code().trim());
        c.setName(req.name().trim());
        c.setTaxNo(req.taxNo());
        c.setCreditLimit(money(req.creditLimit()));
        c.setStatus(req.status() == null ? "ACTIVE" : req.status());
        customerMapper.insert(c); // uk_ar_customer_code 冲突 23505 映射 40900，全局映射
        return toCustomerView(c);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public CustomerView updateCustomer(UUID id, CustomerRequest req) {
        RequestContext.requireWriteRole();
        Customer c = requireCustomer(id);
        Customer patch = new Customer();
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
            validateEnum(req.status(), CUSTOMER_STATUSES, "客户状态");
            patch.setStatus(req.status());
        }
        customerMapper.updateById(patch);
        return toCustomerView(customerMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteCustomer(UUID id) {
        RequestContext.requireWriteRole();
        requireCustomer(id);

        // 引用检查，软删前，存在未删除的发票/收款单引用该客户则 40900 阻断，
        // 防"删客户后历史单据失去往来方语义"，与 GlServiceImpl.deleteAccount 分录引用检查同族
        long invoiceRefs = arInvoiceMapper.selectCount(new LambdaQueryWrapper<ArInvoice>()
                .isNull(ArInvoice::getDeletedAt)
                .eq(ArInvoice::getCustomerId, id));
        long receiptRefs = receiptMapper.selectCount(new LambdaQueryWrapper<Receipt>()
                .isNull(Receipt::getDeletedAt)
                .eq(Receipt::getCustomerId, id));
        if (invoiceRefs + receiptRefs > 0) {
            throw new ConflictException("客户存在未删除的发票/收款单据（" + invoiceRefs + "/" + receiptRefs
                    + " 张），无法删除");
        }

        // 软删统一经 auth.fn_soft_delete 闸口（SECURITY DEFINER），应用角色直接
        // UPDATE deleted_at 会被 rls 新行可见性检查拦截（new row violates rls 映射 40300）
        if (!authFunctionMapper.fnSoftDelete("ar", "customers", id)) {
            throw new ConflictException("删除失败：客户可能已不可见");
        }
    }

    // 发票

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ArInvoiceView> pageInvoices(long page, long size, UUID companyId,
                                                  UUID customerId, String status, String keyword) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, INVOICE_STATUSES, "发票状态");
        }
        return PageResult.of(arInvoiceMapper.selectInvoicePage(new Page<>(Math.max(page, 1),
                Math.min(Math.max(size, 1), 100)), companyId, customerId, status, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ArInvoiceView getInvoice(UUID id) {
        return arInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ArInvoiceView createInvoice(ArInvoiceRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        Customer customer = requireCustomer(req.customerId());
        if (!customer.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("客户与发票公司不一致");
        }
        BigDecimal total = money(req.total());
        if (total.signum() <= 0) {
            throw new BadRequestException("发票总额必须大于 0");
        }
        ArInvoice inv = new ArInvoice();
        inv.setId(UuidV7.next());
        inv.setCompanyId(req.companyId());
        inv.setCustomerId(req.customerId());
        inv.setInvoiceNo(req.invoiceNo().trim());
        inv.setInvoiceDate(req.invoiceDate());
        inv.setDueDate(req.dueDate());
        inv.setCurrency(trimCurrency(req.currency()));
        inv.setTotal(total);
        inv.setTax(money(req.tax()));
        inv.setStatus("DRAFT");
        inv.setVersion(0);
        arInvoiceMapper.insert(inv); // uk_ar_invoice_no 冲突 23505 映射 40900
        return arInvoiceMapper.selectDetail(inv.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ArInvoiceView updateInvoice(UUID id, ArInvoiceRequest req) {
        RequestContext.requireWriteRole();
        ArInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可修改（当前 " + inv.getStatus() + "，开票后不可变）");
        }
        ArInvoice patch = new ArInvoice();
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
        // customerId 禁改，uk 语义绑定，companyId/status/version 禁改
        arInvoiceMapper.updateById(patch);
        return arInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ArInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可删除（当前 " + inv.getStatus() + "，ISSUED+ 被不可变触发器拒绝）");
        }
        if (!authFunctionMapper.fnSoftDelete("ar", "invoices", id)) {
            throw new ConflictException("删除失败：发票可能已不可见");
        }
    }

    /**
     * 开票（DRAFT 转 ISSUED），预检发票编号/客户/金额后推进状态，ISSUED 之后发票进入
     * 资金闭环，trg_ar_invoice_immutable 终审，ISSUED/PARTIAL 仅可作废、PAID/VOID
     * 禁改禁删，预检通过但更新 0 行，并发/状态已变/rls 不可见一律 40900。
     *
     * @param id 发票 id（须 DRAFT）
     * @return 开票后详情
     * @throws ConflictException 状态非法或并发冲突，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public ArInvoiceView issueInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ArInvoice inv = requireInvoice(id);
        if (!"DRAFT".equals(inv.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态发票可开票（当前 " + inv.getStatus() + "）");
        }
        if (inv.getInvoiceNo() == null || inv.getInvoiceNo().isBlank()) {
            throw new BadRequestException("发票号必填");
        }
        if (money(inv.getTotal()).signum() <= 0) {
            throw new BadRequestException("发票总额必须大于 0");
        }
        ArInvoice patch = new ArInvoice();
        patch.setId(id);
        patch.setStatus("ISSUED");
        if (arInvoiceMapper.updateById(patch) == 0) {
            throw new ConflictException("开票失败：状态已变更或不可见");
        }

        // 凭证联动，尽力生成，同事务，开票即生成应收凭证 DRAFT，借应收/贷收入并回填
        // voucher_id，失败不影响开票主流程（WARN + voucher_id 留空，可经补生成端点重试）
        try {
            voucherLinkService.linkArInvoice(id);
        } catch (RuntimeException e) {
            log.warn("应收发票 {} 联动凭证失败（voucher_id 留空，可经 POST /api/ar/invoices/{}/link-voucher 补生成）：{}",
                    id, id, e.getMessage());
        }
        return arInvoiceMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkInvoiceVoucher(UUID id) {
        RequestContext.requireWriteRole();
        ArInvoice inv = requireInvoice(id);
        if (inv.getVoucherId() != null) {
            throw new ConflictException("发票已联动凭证（voucher_id=" + inv.getVoucherId() + "），请勿重复补生成");
        }
        if (!"ISSUED".equals(inv.getStatus())) {
            throw new ConflictException("仅 ISSUED 状态发票可补生成联动凭证（当前 " + inv.getStatus() + "）");
        }
        // 补生成与开票链路同实现，凭证 DRAFT + 回填 voucher_id，幂等键由发票派生防重
        return voucherLinkService.linkArInvoice(id);
    }

    /**
     * 作废（ISSUED/PARTIAL 转 VOID），不可变触发器允许的唯一已开票迁移，作废通道，
     * 作废后同 VOID 状态禁改禁删。Java 预检 + 0 行，并发/已作废则 40900。
     *
     * @param id 发票 id（须 ISSUED/PARTIAL）
     * @return 作废后详情
     * @throws ConflictException 状态非法，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public ArInvoiceView voidInvoice(UUID id) {
        RequestContext.requireWriteRole();
        ArInvoice inv = requireInvoice(id);
        if (!VOIDABLE_INVOICE_STATUSES.contains(inv.getStatus())) {
            throw new ConflictException("仅 ISSUED/PARTIAL 状态发票可作废（当前 " + inv.getStatus() + "）");
        }
        ArInvoice patch = new ArInvoice();
        patch.setId(id);
        patch.setStatus("VOID");
        if (arInvoiceMapper.updateById(patch) == 0) {
            throw new ConflictException("作废失败：状态已变更或不可见");
        }
        return arInvoiceMapper.selectDetail(id);
    }

    // 收款与核销

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReceiptView> pageReceipts(long page, long size, UUID companyId,
                                                UUID customerId, String status) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, Set.of("RECEIVED", "ALLOCATED", "REVERSED"), "收款单状态");
        }
        return PageResult.of(receiptMapper.selectReceiptPage(new Page<>(Math.max(page, 1),
                Math.min(Math.max(size, 1), 100)), companyId, customerId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ReceiptView getReceipt(UUID id) {
        return receiptMapper.selectDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReceiptView createReceipt(ReceiptRequest req) {
        RequestContext.requireWriteRole();
        if (req.companyId() == null) {
            throw new BadRequestException("companyId 必填");
        }
        Customer customer = requireCustomer(req.customerId());
        if (!customer.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("客户与收款单公司不一致");
        }
        BigDecimal amount = money(req.amount());
        if (amount.signum() <= 0) {
            throw new BadRequestException("收款金额必须大于 0");
        }

        // 防重复登记预检，业务键，同公司/同客户/同金额/同日期/同账户，db 侧
        // uk_ar_receipt_dedup 部分唯一索引终审（并发 23505 映射 40900），软删后的同键
        // 收款单不阻挡重新登记（WHERE deleted_at IS NULL）。
        if (receiptMapper.selectCount(new LambdaQueryWrapper<Receipt>()
                .eq(Receipt::getCompanyId, req.companyId())
                .eq(Receipt::getCustomerId, req.customerId())
                .eq(Receipt::getAmount, amount)
                .eq(Receipt::getReceiptDate, req.receiptDate())
                .eq(req.bankAccountId() != null, Receipt::getBankAccountId, req.bankAccountId())
                .isNull(req.bankAccountId() == null, Receipt::getBankAccountId)) > 0) {
            throw new ConflictException("疑似重复收款登记：同客户/同金额/同日期/同账户的有效收款单已存在，请勿重复提交");
        }
        Receipt r = new Receipt();
        r.setId(UuidV7.next());
        r.setCompanyId(req.companyId());
        r.setCustomerId(req.customerId());
        r.setAmount(amount);
        r.setBankAccountId(req.bankAccountId());
        r.setReceiptDate(req.receiptDate());
        r.setStatus("RECEIVED");
        receiptMapper.insert(r);

        // 凭证联动，尽力生成，同事务，收款登记即生成收款凭证 DRAFT（借银行/贷应收，
        // 金额=实收）并回填 voucher_id，失败不影响收款登记主流程，WARN + 留空，核销时再试
        try {
            voucherLinkService.linkReceipt(r.getId());
        } catch (RuntimeException e) {
            log.warn("收款单 {} 联动凭证失败（voucher_id 留空，核销时将重试）：{}", r.getId(), e.getMessage());
        }
        return receiptMapper.selectDetail(r.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteReceipt(UUID id) {
        RequestContext.requireWriteRole();
        Receipt r = requireReceipt(id);
        if (!"RECEIVED".equals(r.getStatus())) {
            throw new ConflictException("仅 RECEIVED 未核销收款单可删除（当前 " + r.getStatus() + "）");
        }
        if (!authFunctionMapper.fnSoftDelete("ar", "receipts", id)) {
            throw new ConflictException("删除失败：收款单可能已不可见");
        }
    }

    /**
     * 核销入口（收款单对发票），事务内顺序执行，防重双闸 + 防并发超分 + 请求级幂等，
     *
     *   ① FOR UPDATE 锁收款单（selectByIdForUpdate，写事务内），并发核销
     *       同一收款单串行化，status 须 RECEIVED，锁内校验，防超分，
     *   ② 发票须 ISSUED/PARTIAL，同对已有有效核销行且请求金额与累计金额相同
     *       则 40900 防重复核销，同一请求重复提交不产生第二次生效，
     *   ③ selectAllocatedSumByInvoice 取发票已核销累计，本次 ≤ total - 已核销
     *       （超分则 40900），
     *   ④ selectAllocatedSumByReceipt 取收款单已核销累计，本次 ≤ 收款金额
     *       （合并路径防循环累加超分则 40900），
     *   ⑤ 同对有行则乐观锁合并金额（updateWithVersion，uk_ar_alloc 唯一约束保持
     *       "同对仅一行"，不同金额视为部分核销推进），无行则 INSERT（version 0），
     *   ⑥ 累计 = total 则发票置 PAID 且收款单置 ALLOCATED，否则发票 PARTIAL，发票状态迁移由 trg_ar_invoice_immutable 终审，仅 ISSUED/PARTIAL 起迁
     *       允许，若 db 侧拒绝按 40900 上抛，事务整体回滚保持核销一致性。
     *
     * 收款单到凭证联动（voucher_id）为尽力生成，收款登记时已联动则此处跳过，幂等，
     * 存量未联动收款单在核销时补联动。
     *
     * @param receiptId 收款单 id，路径
     * @param req       核销请求（invoiceId + amount）
     * @return 新建核销明细
     * @throws ConflictException 收款单状态非法/超分/重复核销/发票不可核销，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public AllocationView allocate(UUID receiptId, AllocateRequest req) {
        RequestContext.requireWriteRole();
        // 锁收款单，防并发核销超分，锁内校验状态
        Receipt receipt = receiptMapper.selectByIdForUpdate(receiptId);
        if (receipt == null) {
            throw new NotFoundException("收款单不存在");
        }
        if (!"RECEIVED".equals(receipt.getStatus())) {
            throw new ConflictException("仅 RECEIVED 状态收款单可核销（当前 " + receipt.getStatus() + "）");
        }
        // 发票状态与余额预检
        ArInvoice invoice = requireInvoice(req.invoiceId());
        if (!ALLOCABLE_INVOICE_STATUSES.contains(invoice.getStatus())) {
            throw new ConflictException("发票状态不可核销（当前 " + invoice.getStatus() + "，须 ISSUED/PARTIAL）");
        }
        BigDecimal amount = money(req.amount());
        if (amount.signum() <= 0) {
            throw new BadRequestException("核销金额必须大于 0");
        }
        // 同对已有有效核销行预检，请求金额与累计金额相同则同一请求重复提交，幂等缺陷场景，40900 拒绝，不产生第二次生效，不同金额视为部分核销推进。
        List<AllocationView> pairRows = receiptAllocationMapper.selectByReceiptId(receiptId);
        AllocationView prior = pairRows.stream()
                .filter(v -> v.invoiceId().equals(invoice.getId()))
                .findFirst().orElse(null);
        if (prior != null && money(prior.amount()).compareTo(amount) == 0) {
            throw new ConflictException("重复核销请求：该发票已从本收款单核销 " + prior.amount()
                    + "，相同金额的重复请求已拒绝，请勿重复提交");
        }
        // 发票余额预检，防超分
        BigDecimal allocated = money(receiptAllocationMapper.selectAllocatedSumByInvoice(invoice.getId()));
        if (allocated.add(amount).compareTo(money(invoice.getTotal())) > 0) {
            throw new ConflictException("核销金额超过发票未核销余额（已核销 " + allocated + "，总额 " + invoice.getTotal() + "）");
        }
        // 收款单剩余可核销额预检，累计已核销 + 本次 ≤ 收款金额（合并路径防循环累加
        //    超分，小金额收款单不可通过多次核销累加超过收款金额）
        BigDecimal receiptUsed = money(receiptAllocationMapper.selectAllocatedSumByReceipt(receiptId));
        if (receiptUsed.add(amount).compareTo(money(receipt.getAmount())) > 0) {
            throw new ConflictException("核销金额超过收款单未核销余额（已核销 " + receiptUsed
                    + "，收款金额 " + receipt.getAmount() + "）");
        }
        // 核销明细写入，同 (receipt_id, invoice_id) 存在有效行则乐观锁合并金额
        //    （updateWithVersion，uk_ar_alloc 唯一约束保持"同对仅一行"语义，同对多次核销
        //    推进仅累加金额，防并发出双行/重复核销），无有效行则 INSERT 新行（version 0）。
        UUID allocId;
        int allocVersion;
        BigDecimal allocAmount;
        if (prior != null) {
            allocAmount = money(prior.amount()).add(amount);
            int oldVersion = prior.version() == null ? 0 : prior.version();
            if (receiptAllocationMapper.updateWithVersion(prior.id(), oldVersion, allocAmount) == 0) {
                throw new ConflictException("核销明细版本冲突（并发调整）");
            }
            allocId = prior.id();
            allocVersion = oldVersion + 1;
        } else {
            ReceiptAllocation alloc = new ReceiptAllocation();
            alloc.setId(UuidV7.next());
            alloc.setCompanyId(invoice.getCompanyId());
            alloc.setReceiptId(receiptId);
            alloc.setInvoiceId(invoice.getId());
            alloc.setAmount(amount);
            alloc.setVersion(0); // 乐观锁起始版本
            receiptAllocationMapper.insert(alloc);
            allocId = alloc.getId();
            allocVersion = 0;
            allocAmount = amount;
        }

        // 推进发票/收款单状态，迁移合法性由不可变触发器终审
        BigDecimal newAllocated = allocated.add(amount);
        boolean fullyPaid = newAllocated.compareTo(money(invoice.getTotal())) >= 0;
        // 发票，ISSUED/PARTIAL 迁至 PARTIAL/PAID（WHERE 状态守卫防并发竞态，0 行则 40900）
        if (arInvoiceMapper.update(null, new LambdaUpdateWrapper<ArInvoice>()
                .eq(ArInvoice::getId, invoice.getId())
                .in(ArInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                .set(ArInvoice::getStatus, fullyPaid ? "PAID" : "PARTIAL")) == 0) {
            throw new ConflictException("发票状态推进失败：状态已变更");
        }
        if (fullyPaid) {
            // 核销完毕，收款单 RECEIVED 迁至 ALLOCATED（0 行 = 已冲销/已删则 40900 回滚）
            if (receiptMapper.update(null, new LambdaUpdateWrapper<Receipt>()
                    .eq(Receipt::getId, receiptId)
                    .eq(Receipt::getStatus, "RECEIVED")
                    .set(Receipt::getStatus, "ALLOCATED")) == 0) {
                throw new ConflictException("收款单状态推进失败：状态已变更");
            }
        }
        // 凭证联动，尽力生成，同事务，收款登记未联动，存量单/登记时失败的收款单在
        // 核销时补联动，已联动幂等跳过，失败不影响核销主流程
        try {
            voucherLinkService.linkReceipt(receiptId);
        } catch (RuntimeException e) {
            log.warn("收款单 {} 核销后联动凭证失败（voucher_id 留空）：{}", receiptId, e.getMessage());
        }
        return new AllocationView(allocId, receiptId, invoice.getId(),
                invoice.getInvoiceNo(), invoice.getInvoiceDate(), allocAmount, allocVersion, LocalDateTime.now());
    }

    /**
     * 撤销核销，仅发票仍 PARTIAL 可撤，乐观锁条件软删（version 不匹配/已撤销则
     * 0 行则 40900），并回算发票状态（剩余已核销 = 0 则回 ISSUED，否则保持 PARTIAL）。
     *
     * PAID 发票的核销撤销被不可变触发器拒绝，Java 预检提前失败，撤销软删不触碰
     * 发票行本身，UPDATE 仅 ISSUED/PARTIAL 起始的迁移才被触发器放行。
     *
     * @param allocationId 核销明细 id
     * @throws ConflictException 发票已 PAID/核销不存在/版本冲突，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public void unallocate(UUID allocationId) {
        RequestContext.requireWriteRole();
        ReceiptAllocation alloc = receiptAllocationMapper.selectById(allocationId);
        if (alloc == null) {
            throw new NotFoundException("核销明细不存在");
        }
        ArInvoice invoice = requireInvoice(alloc.getInvoiceId());
        if (!"PARTIAL".equals(invoice.getStatus())) {
            throw new ConflictException("仅 PARTIAL 状态发票可撤销核销（当前 " + invoice.getStatus()
                    + "，PAID 发票撤销被不可变触发器拒绝）");
        }

        // 乐观锁软删（并发撤销/金额并发调整则版本不匹配，0 行则 40900）
        int version = alloc.getVersion() == null ? 0 : alloc.getVersion();
        if (receiptAllocationMapper.softDeleteWithVersion(allocationId, version) == 0) {
            throw new ConflictException("撤销核销失败：核销记录版本已变更");
        }

        // 回算发票状态，剩余已核销 = 0 则回 ISSUED，否则保持 PARTIAL
        BigDecimal remaining = money(receiptAllocationMapper.selectAllocatedSumByInvoice(invoice.getId()));
        String newStatus = remaining.signum() <= 0 ? "ISSUED" : "PARTIAL";
        arInvoiceMapper.update(null, new LambdaUpdateWrapper<ArInvoice>()
                .eq(ArInvoice::getId, invoice.getId())
                .in(ArInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                .set(ArInvoice::getStatus, newStatus));
    }

    /**
     * 收款冲销（RECEIVED/ALLOCATED 转 REVERSED），软性动作，置状态 + 软删该收款单
     * 全部核销明细，带版本条件逐条撤销，并回算受影响发票状态，ar.receipts 无状态机
     * 触发器，Java 预检即可，若受影响发票已 PAID，本单核销使其结清，冲销将触发
     * 不可变触发器拒绝则 Java 预检直接 40900，防 50000 兜底，提示清晰。
     *
     * @param id  收款单 id
     * @param req 冲销原因，审计追溯
     * @throws ConflictException 收款单已冲销/受影响发票已 PAID，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public void reverseReceipt(UUID id, ReverseReceiptRequest req) {
        RequestContext.requireWriteRole();
        Receipt receipt = requireReceipt(id);
        if ("REVERSED".equals(receipt.getStatus())) {
            throw new ConflictException("收款单已冲销，禁止重复冲销");
        }
        List<AllocationView> allocations = receiptAllocationMapper.selectByReceiptId(id);
        for (AllocationView view : allocations) {
            ArInvoice invoice = arInvoiceMapper.selectById(view.invoiceId());
            if (invoice != null && "PAID".equals(invoice.getStatus())) {
                // 预检，发票已结清，冲销会使发票余额回涨，不可变触发器终审拒绝，Java 先拦
                throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 已核销完毕（PAID），无法冲销该收款单");
            }
        }

        // 逐条乐观锁软删核销明细（并发调整则版本不匹配，40900 回滚）
        for (AllocationView view : allocations) {
            int version = view.version() == null ? 0 : view.version();
            if (receiptAllocationMapper.softDeleteWithVersion(view.id(), version) == 0) {
                throw new ConflictException("冲销失败：核销记录版本已变更");
            }
            ArInvoice invoice = arInvoiceMapper.selectById(view.invoiceId());
            if (invoice != null) {
                BigDecimal remaining = money(receiptAllocationMapper.selectAllocatedSumByInvoice(invoice.getId()));
                String newStatus = remaining.signum() <= 0 ? "ISSUED" : "PARTIAL";
                arInvoiceMapper.update(null, new LambdaUpdateWrapper<ArInvoice>()
                        .eq(ArInvoice::getId, invoice.getId())
                        .in(ArInvoice::getStatus, ALLOCABLE_INVOICE_STATUSES)
                        .set(ArInvoice::getStatus, newStatus));
            }
        }

        // 置冲销状态，reason 暂存审计用途，表无独立列，日志输出可追溯
        if (req != null && req.reason() != null && !req.reason().isBlank()) {
            log.info("收款单 {} 冲销，原因：{}", id, req.reason());
        }
        receiptMapper.update(null, new LambdaUpdateWrapper<Receipt>()
                .eq(Receipt::getId, id)
                .ne(Receipt::getStatus, "REVERSED")
                .set(Receipt::getStatus, "REVERSED"));
    }

    // 账龄 / 坏账

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<AgingRow> aging(UUID companyId) {
        // 直查 rpt.ar_aging 视图（ISSUED/PARTIAL + 未核销余额，security_invoker=on
        // 行级收敛，越权公司返回空集），分桶与汇总在 sql 内以窗口函数完成，B 类手写
        return arInvoiceMapper.selectAging(companyId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<BadDebtView> pageBadDebts(long page, long size, UUID companyId, String status) {
        if (status != null && !status.isBlank()) {
            validateEnum(status, BAD_DEBT_STATUSES, "计提状态");
        }
        return PageResult.of(badDebtProvisionMapper.selectBadDebtPage(new Page<>(Math.max(page, 1),
                Math.min(Math.max(size, 1), 100)), companyId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BadDebtView createBadDebt(BadDebtRequest req) {
        RequestContext.requireWriteRole();
        ArInvoice invoice = requireInvoice(req.invoiceId());
        if ("PAID".equals(invoice.getStatus())) {
            throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 已结清（PAID），禁止计提坏账");
        }
        if ("VOID".equals(invoice.getStatus())) {
            throw new ConflictException("发票已作废（VOID），禁止计提坏账");
        }
        if (badDebtProvisionMapper.selectByInvoicePeriod(invoice.getId(), req.period()) != null) {
            throw new ConflictException("发票 " + invoice.getInvoiceNo() + " 期间 " + req.period() + " 已计提，禁止重复计提");
        }
        BadDebtProvision b = new BadDebtProvision();
        b.setId(UuidV7.next());
        b.setCompanyId(invoice.getCompanyId());
        b.setInvoiceId(invoice.getId());
        b.setPeriod(req.period());
        b.setAmount(money(req.amount()));
        b.setStatus("DRAFT");
        badDebtProvisionMapper.insert(b);
        return new BadDebtView(b.getId(), b.getCompanyId(), b.getInvoiceId(), invoice.getInvoiceNo(),
                b.getPeriod(), b.getAmount(), b.getStatus(), LocalDateTime.now());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BadDebtView confirmBadDebt(UUID id) {
        RequestContext.requireWriteRole();
        BadDebtProvision b = requireBadDebt(id);
        if (!"DRAFT".equals(b.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态计提可确认（当前 " + b.getStatus() + "，确认后禁改禁删）");
        }
        BadDebtProvision patch = new BadDebtProvision();
        patch.setId(id);
        patch.setStatus("CONFIRMED");
        if (badDebtProvisionMapper.updateById(patch) == 0) {
            throw new ConflictException("确认失败：状态已变更或不可见");
        }
        BadDebtProvision confirmed = requireBadDebt(id);
        ArInvoice invoice = arInvoiceMapper.selectById(confirmed.getInvoiceId());
        return new BadDebtView(confirmed.getId(), confirmed.getCompanyId(), confirmed.getInvoiceId(),
                invoice == null ? null : invoice.getInvoiceNo(), confirmed.getPeriod(),
                confirmed.getAmount(), confirmed.getStatus(), confirmed.getCreatedAt());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteBadDebt(UUID id) {
        RequestContext.requireWriteRole();
        BadDebtProvision b = requireBadDebt(id);
        if (!"DRAFT".equals(b.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态计提可删除（当前 " + b.getStatus() + "）");
        }
        if (!authFunctionMapper.fnSoftDelete("ar", "bad_debt_provision", id)) {
            throw new ConflictException("删除失败：计提记录可能已不可见");
        }
    }

    // 私有方法

    /** 按 id 取客户（rls 空集则 40400，写路径显式报错） */
    private Customer requireCustomer(UUID id) {
        Customer c = customerMapper.selectById(id);
        if (c == null) {
            throw new NotFoundException("客户不存在");
        }
        return c;
    }

    /** 按 id 取发票（rls 空集则 40400） */
    private ArInvoice requireInvoice(UUID id) {
        ArInvoice inv = arInvoiceMapper.selectById(id);
        if (inv == null) {
            throw new NotFoundException("发票不存在");
        }
        return inv;
    }

    /** 按 id 取收款单（rls 空集则 40400） */
    private Receipt requireReceipt(UUID id) {
        Receipt r = receiptMapper.selectById(id);
        if (r == null) {
            throw new NotFoundException("收款单不存在");
        }
        return r;
    }

    /** 按 id 取计提记录（rls 空集则 40400） */
    private BadDebtProvision requireBadDebt(UUID id) {
        BadDebtProvision b = badDebtProvisionMapper.selectById(id);
        if (b == null) {
            throw new NotFoundException("坏账计提记录不存在");
        }
        return b;
    }

    /** 枚举白名单校验，db CHECK 终审兜底 */
    private void validateEnum(String value, Set<String> allowed, String label) {
        if (value != null && !allowed.contains(value)) {
            throw new BadRequestException(label + "非法：" + value);
        }
    }

    /** 实体转视图 */
    private CustomerView toCustomerView(Customer c) {
        if (c == null) {
            return null;
        }
        return new CustomerView(c.getId(), c.getCompanyId(), c.getCode(), c.getName(),
                c.getTaxNo(), c.getCreditLimit(), c.getStatus(), c.getCreatedAt());
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
