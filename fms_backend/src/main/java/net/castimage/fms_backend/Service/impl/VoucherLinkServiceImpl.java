package net.castimage.fms_backend.Service.impl;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.ApInvoiceMapper;
import net.castimage.fms_backend.Mapper.ArInvoiceMapper;
import net.castimage.fms_backend.Mapper.CashTransactionMapper;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Mapper.PaymentMapper;
import net.castimage.fms_backend.Mapper.ReceiptMapper;
import net.castimage.fms_backend.Mapper.ReimbursementMapper;
import net.castimage.fms_backend.Mapper.VoucherLineMapper;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.entity.ApInvoice;
import net.castimage.fms_backend.Model.entity.ArInvoice;
import net.castimage.fms_backend.Model.entity.CashTransaction;
import net.castimage.fms_backend.Model.entity.Payment;
import net.castimage.fms_backend.Model.entity.Receipt;
import net.castimage.fms_backend.Model.entity.Reimbursement;
import net.castimage.fms_backend.Model.entity.Voucher;
import net.castimage.fms_backend.Model.entity.VoucherLine;
import net.castimage.fms_backend.Service.VoucherLinkService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 凭证联动实现（init/gl_voucher_link.sql 的 Java 侧）：凭证创建路径与 GlServiceImpl.createVoucher
 * 同源（DRAFT 头 + 两行分录直插，DB 触发器终审：DRAFT 豁免平衡校验、期间 CLOSED 拒建、
 * 分录末级科目校验），但幂等键改由来源单据派生（同来源恒同键，uk_voucher_idem
 * 唯一索引兜底并发）——补生成/重试复用既有凭证，业务单据侧以 voucher_id/voucher_line_id
 * 判空做幂等闸。科目映射经 um.fn_get_param_value 读口读取（值 = 科目 id）。
 *
 * 事务边界：联动在调用方事务内执行（同事务提交/回滚，与单据状态推进原子一致）；
 * 调用方以"尽力生成"策略包裹（失败 WARN + 单据继续，voucher_id 留空可补生成）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherLinkServiceImpl implements VoucherLinkService {

    /** 金额标度（NUMERIC(18,2) 对齐） */
    private static final int MONEY_SCALE = 2;

    /** 金额零值（标度 2） */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 科目映射参数（um.params，值 = 科目 id，{"v":"<uuid>"} 或 "<uuid>" 双形态兼容） */
    private static final String PARAM_AR_RECEIVABLE = "ar.receivable_account";
    private static final String PARAM_AR_REVENUE = "ar.revenue_account";
    private static final String PARAM_AP_PAYABLE = "ap.payable_account";
    private static final String PARAM_AP_EXPENSE = "ap.expense_account";
    private static final String PARAM_CASH_BANK = "cash.bank_account";
    private static final String PARAM_CASH_CLEARING = "cash.clearing_account";

    private final VoucherMapper voucherMapper;
    private final VoucherLineMapper voucherLineMapper;
    private final ParamMapper paramMapper;
    private final ArInvoiceMapper arInvoiceMapper;
    private final ApInvoiceMapper apInvoiceMapper;
    private final ReceiptMapper receiptMapper;
    private final PaymentMapper paymentMapper;
    private final CashTransactionMapper cashTransactionMapper;
    private final ReimbursementMapper reimbursementMapper;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkArInvoice(UUID invoiceId) {
        ArInvoice inv = arInvoiceMapper.selectById(invoiceId);
        if (inv == null) {
            throw new NotFoundException("应收发票不存在");
        }
        if (inv.getVoucherId() != null) {
            return inv.getVoucherId();             // 幂等：已联动跳过
        }
        LocalDate date = inv.getInvoiceDate() == null ? LocalDate.now() : inv.getInvoiceDate();
        BigDecimal amount = money(inv.getTotal());
        // 开票即确认收入：借 应收账款 / 贷 主营业务收入（金额 = 发票总额）
        UUID voucherId = createDraftVoucher(inv.getCompanyId(), periodOf(date), date,
                "AR_INVOICE", inv.getId(), currencyOf(inv.getCurrency()), inv.getCustomerId(),
                "应收发票凭证（" + inv.getInvoiceNo() + "）",
                requireAccountParam(PARAM_AR_RECEIVABLE), amount,
                requireAccountParam(PARAM_AR_REVENUE), amount);
        if (arInvoiceMapper.update(null, new LambdaUpdateWrapper<ArInvoice>()
                .eq(ArInvoice::getId, invoiceId)
                .isNull(ArInvoice::getVoucherId)   // CAS 守卫：并发补生成仅一方回填成功
                .set(ArInvoice::getVoucherId, voucherId)) == 0) {
            throw new ConflictException("应收发票凭证回填失败：voucher_id 已被占用或单据不可见");
        }
        log.info("应收发票 {} 联动凭证 {} 完成（金额 {}）", invoiceId, voucherId, amount);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkApInvoice(UUID invoiceId) {
        ApInvoice inv = apInvoiceMapper.selectById(invoiceId);
        if (inv == null) {
            throw new NotFoundException("应付发票不存在");
        }
        if (inv.getVoucherId() != null) {
            return inv.getVoucherId();             // 幂等：已联动跳过
        }
        LocalDate date = inv.getInvoiceDate() == null ? LocalDate.now() : inv.getInvoiceDate();
        BigDecimal amount = money(inv.getTotal());
        // 应付入账：借 费用 / 贷 应付账款（金额 = 发票总额）
        UUID voucherId = createDraftVoucher(inv.getCompanyId(), periodOf(date), date,
                "AP_INVOICE", inv.getId(), currencyOf(inv.getCurrency()), inv.getSupplierId(),
                "应付发票凭证（" + inv.getInvoiceNo() + "）",
                requireAccountParam(PARAM_AP_EXPENSE), amount,
                requireAccountParam(PARAM_AP_PAYABLE), amount);
        if (apInvoiceMapper.update(null, new LambdaUpdateWrapper<ApInvoice>()
                .eq(ApInvoice::getId, invoiceId)
                .isNull(ApInvoice::getVoucherId)
                .set(ApInvoice::getVoucherId, voucherId)) == 0) {
            throw new ConflictException("应付发票凭证回填失败：voucher_id 已被占用或单据不可见");
        }
        log.info("应付发票 {} 联动凭证 {} 完成（金额 {}）", invoiceId, voucherId, amount);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkReceipt(UUID receiptId) {
        Receipt r = receiptMapper.selectById(receiptId);
        if (r == null) {
            throw new NotFoundException("收款单不存在");
        }
        if (r.getVoucherId() != null) {
            return r.getVoucherId();               // 幂等：已联动跳过
        }
        LocalDate date = r.getReceiptDate() == null ? LocalDate.now() : r.getReceiptDate();
        BigDecimal amount = money(r.getAmount());  // 金额 = 实收金额（收款单金额）
        // 收款入账：借 银行存款 / 贷 应收账款（金额 = 实收）
        UUID voucherId = createDraftVoucher(r.getCompanyId(), periodOf(date), date,
                "AR_RECEIPT", r.getId(), "CNY", r.getCustomerId(),
                "收款单凭证（实收 " + amount.toPlainString() + "）",
                requireAccountParam(PARAM_CASH_BANK), amount,
                requireAccountParam(PARAM_AR_RECEIVABLE), amount);
        if (receiptMapper.update(null, new LambdaUpdateWrapper<Receipt>()
                .eq(Receipt::getId, receiptId)
                .isNull(Receipt::getVoucherId)
                .set(Receipt::getVoucherId, voucherId)) == 0) {
            throw new ConflictException("收款单凭证回填失败：voucher_id 已被占用或单据不可见");
        }
        log.info("收款单 {} 联动凭证 {} 完成（实收 {}）", receiptId, voucherId, amount);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkPayment(UUID paymentId) {
        Payment p = paymentMapper.selectById(paymentId);
        if (p == null) {
            throw new NotFoundException("付款单不存在");
        }
        if (p.getVoucherId() != null) {
            return p.getVoucherId();               // 幂等：已联动跳过
        }
        LocalDate date = p.getPaymentDate() == null ? LocalDate.now() : p.getPaymentDate();
        BigDecimal amount = money(p.getAmount());
        // 付款入账：借 应付账款 / 贷 银行存款（金额 = 付款金额）
        UUID voucherId = createDraftVoucher(p.getCompanyId(), periodOf(date), date,
                "AP_PAYMENT", p.getId(), "CNY", p.getSupplierId(),
                "付款单凭证（实付 " + amount.toPlainString() + "）",
                requireAccountParam(PARAM_AP_PAYABLE), amount,
                requireAccountParam(PARAM_CASH_BANK), amount);
        if (paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                .eq(Payment::getId, paymentId)
                .isNull(Payment::getVoucherId)
                .set(Payment::getVoucherId, voucherId)) == 0) {
            throw new ConflictException("付款单凭证回填失败：voucher_id 已被占用或单据不可见");
        }
        log.info("付款单 {} 联动凭证 {} 完成（实付 {}）", paymentId, voucherId, amount);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkCashTransaction(UUID trxId) {
        CashTransaction trx = cashTransactionMapper.selectById(trxId);
        if (trx == null) {
            throw new NotFoundException("资金流水不存在");
        }
        if (trx.getVoucherLineId() != null) {
            return trx.getVoucherLineId();         // 幂等：已联动跳过
        }
        LocalDate date = trx.getTrxDate() == null ? LocalDate.now() : trx.getTrxDate();
        BigDecimal amount = money(trx.getAmount());
        UUID bank = requireAccountParam(PARAM_CASH_BANK);
        UUID clearing = requireAccountParam(PARAM_CASH_CLEARING);
        // 方向映射：IN 借 银行存款 / 贷 清算过渡；OUT 借 清算过渡 / 贷 银行存款
        boolean inbound = "IN".equals(trx.getDirection());
        UUID debitAccount = inbound ? bank : clearing;
        UUID creditAccount = inbound ? clearing : bank;
        String summary = trx.getSummary() == null || trx.getSummary().isBlank()
                ? "银行流水凭证" : "银行流水：" + trx.getSummary().trim();
        UUID voucherId = createDraftVoucher(trx.getCompanyId(), periodOf(date), date,
                "CASH_TRX", trx.getId(), "CNY", null, summary,
                debitAccount, amount, creditAccount, amount);
        // voucher_line_id 回填银行科目那行分录（凭证 2 行中银行科目行——对账/核销追溯锚点）
        VoucherLine bankLine = voucherLineMapper.selectOne(new LambdaQueryWrapper<VoucherLine>()
                .eq(VoucherLine::getVoucherId, voucherId)
                .eq(VoucherLine::getAccountId, bank)
                .isNull(VoucherLine::getDeletedAt));
        if (bankLine == null) {
            throw new ConflictException("资金流水凭证银行分录缺失，联动异常");
        }
        if (cashTransactionMapper.update(null, new LambdaUpdateWrapper<CashTransaction>()
                .eq(CashTransaction::getId, trxId)
                .isNull(CashTransaction::getVoucherLineId)
                .set(CashTransaction::getVoucherLineId, bankLine.getId())) == 0) {
            throw new ConflictException("资金流水凭证回填失败：voucher_line_id 已被占用或流水不可见");
        }
        log.info("资金流水 {} 联动凭证 {}（银行分录 {}）完成", trxId, voucherId, bankLine.getId());
        return bankLine.getId();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkExpense(UUID reimbId) {
        Reimbursement r = reimbursementMapper.selectById(reimbId);
        if (r == null) {
            throw new NotFoundException("报销单不存在");
        }
        if (r.getVoucherId() != null) {
            return r.getVoucherId();               // 幂等：已联动跳过
        }
        LocalDate date = r.getCreatedAt() == null ? LocalDate.now() : r.getCreatedAt().toLocalDate();
        BigDecimal amount = money(r.getAmount());
        // 报销入账：借 费用科目（ap.expense_account 管理费用）/ 贷 应付账款（未付款挂应付）
        String title = r.getTitle() == null || r.getTitle().isBlank() ? "费用报销" : r.getTitle().trim();
        UUID voucherId = createDraftVoucher(r.getCompanyId(), periodOf(date), date,
                "EXPENSE", r.getId(), currencyOf(r.getCurrency()), null,
                "报销单凭证（" + title + "）",
                requireAccountParam(PARAM_AP_EXPENSE), amount,
                requireAccountParam(PARAM_AP_PAYABLE), amount);
        if (reimbursementMapper.update(null, new LambdaUpdateWrapper<Reimbursement>()
                .eq(Reimbursement::getId, reimbId)
                .isNull(Reimbursement::getVoucherId)
                .set(Reimbursement::getVoucherId, voucherId)) == 0) {
            throw new ConflictException("报销单凭证回填失败：voucher_id 已被占用或单据不可见");
        }
        log.info("报销单 {} 联动凭证 {} 完成（金额 {}）", reimbId, voucherId, amount);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public String voucherStatus(UUID voucherId) {
        if (voucherId == null) {
            return null;
        }
        Voucher v = voucherMapper.selectById(voucherId);
        return v == null ? null : v.getStatus();
    }

    // 私有方法

    /**
     * 创建 DRAFT 凭证（两行分录，借贷平衡）：头 + 分录与 GlServiceImpl 同源落库（DB 触发器
     * 终审：DRAFT 豁免平衡校验、trg_period_voucher_guard 拒绝已结账期间、分录末级科目校验）。
     * 幂等键由来源单据派生（sourceType|sourceId 确定性 SHA-256）——补生成/重试同键命中
     * 既有凭证直接复用（uk_voucher_idem 部分唯一索引兜底并发，23505 回查返回先提交者）。
     *
     * @return 凭证 id（新建或幂等命中既有）
     */
    private UUID createDraftVoucher(UUID companyId, String period, LocalDate voucherDate,
                                    String sourceType, UUID sourceId, String currency,
                                    UUID partnerId, String note,
                                    UUID debitAccountId, BigDecimal debitAmount,
                                    UUID creditAccountId, BigDecimal creditAmount) {
        UUID userId = RequestContext.requireUserId();
        String idemKey = sha256Hex(sourceType + "|" + sourceId);
        Voucher existing = findByIdemKey(companyId, period, userId, idemKey);
        if (existing != null) {
            log.info("联动凭证幂等命中：来源 {} {} 已存在凭证 id={}，复用", sourceType, sourceId, existing.getId());
            return existing.getId();
        }
        Voucher v = new Voucher();
        v.setId(UuidV7.next());
        v.setCompanyId(companyId);
        v.setPeriod(period);
        v.setVoucherDate(voucherDate);
        v.setStatus("DRAFT");            // 联动凭证一律 DRAFT：过账由会计在凭证模块完成（SoD 录入≠审核≠记账）
        v.setSourceType(sourceType);     // 来源类型（AR_INVOICE/AP_INVOICE/AR_RECEIPT/AP_PAYMENT/CASH_TRX/EXPENSE）
        v.setSourceId(sourceId);         // 来源单据 id（业务追溯锚点）
        v.setIsPrivate(false);
        v.setCreatedBy(userId);
        v.setTotalDebit(money(debitAmount));
        v.setTotalCredit(money(creditAmount));
        v.setNote(note);
        v.setIdemKey(idemKey);
        try {
            voucherMapper.insert(v);
        } catch (DuplicateKeyException e) {
            // 并发同来源竞态：唯一索引拒绝重复，回查返回先提交者（不产生重复凭证）
            Voucher winner = findByIdemKey(companyId, period, userId, idemKey);
            if (winner != null) {
                log.info("联动凭证并发幂等命中：id={} 已先创建，复用", winner.getId());
                return winner.getId();
            }
            throw e;
        }
        insertLine(v.getId(), companyId, 1, debitAccountId, note, money(debitAmount), ZERO, currency, partnerId);
        insertLine(v.getId(), companyId, 2, creditAccountId, note, ZERO, money(creditAmount), currency, partnerId);
        return v.getId();
    }

    /** 插入分录（ck_line_single_side 单边校验：借/贷互斥） */
    private void insertLine(UUID voucherId, UUID companyId, int lineNo, UUID accountId,
                            String summary, BigDecimal debit, BigDecimal credit,
                            String currency, UUID partnerId) {
        VoucherLine line = new VoucherLine();
        line.setId(UuidV7.next());
        line.setVoucherId(voucherId);
        line.setLineNo(lineNo);
        line.setAccountId(accountId);
        line.setSummary(summary);
        line.setDebit(debit);
        line.setCredit(credit);
        line.setCurrency(currency == null || currency.isBlank() ? "CNY" : currency.trim());
        line.setDeptId(null);            // 联动凭证不绑定部门（RLS 按公司范围可见）
        line.setPartnerId(partnerId);    // 往来方（客户/供应商 id，供账龄/往来辅助核算追溯）
        line.setCompanyId(companyId);
        voucherLineMapper.insert(line);
    }

    /**
     * 科目映射参数读取（um.fn_get_param_value SECURITY DEFINER 读口）：值 = 科目 id，
     * 形态 {"v":"<uuid>"}（jsonb 对象）与 "<uuid>"（jsonb 字符串）双兼容；缺失/非法
     * 抛 40900——调用方"尽力生成"兜底（WARN + 单据继续，voucher_id 留空可补生成）。
     */
    private UUID requireAccountParam(String code) {
        String text = paramMapper.selectPublicParamValue(code, "null");
        String idText = null;
        if (text != null && !text.isBlank() && !"null".equalsIgnoreCase(text.trim())) {
            Object parsed = JSON.parse(text);
            if (parsed instanceof JSONObject obj) {
                idText = obj.getString("v");
            } else if (parsed instanceof String s) {
                idText = s;
            }
        }
        if (idText == null || idText.isBlank()) {
            throw new ConflictException("科目映射参数 " + code + " 未配置（um.params），无法生成联动凭证");
        }
        try {
            return UUID.fromString(idText.trim());
        } catch (IllegalArgumentException e) {
            throw new ConflictException("科目映射参数 " + code + " 值非法：" + idText + "（须为科目 id）");
        }
    }

    /** 按幂等键查既有凭证（同人+公司+期间；uk_voucher_idem 唯一索引保证至多 1 行） */
    private Voucher findByIdemKey(UUID companyId, String period, UUID userId, String idemKey) {
        return voucherMapper.selectOne(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getCompanyId, companyId)
                .eq(Voucher::getPeriod, period)
                .eq(Voucher::getCreatedBy, userId)
                .eq(Voucher::getIdemKey, idemKey)
                .isNull(Voucher::getDeletedAt));
    }

    /** 业务日期转会计期间（YYYY-MM） */
    private String periodOf(LocalDate date) {
        return date.toString().substring(0, 7);
    }

    /** 币种规范化（CHAR(3)，缺省 CNY） */
    private String currencyOf(String currency) {
        return currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase(Locale.ROOT);
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** SHA-256 十六进制（64 字符，voucher.idem_key varchar(64) 对齐） */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
