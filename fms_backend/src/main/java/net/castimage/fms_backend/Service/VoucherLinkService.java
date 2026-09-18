package net.castimage.fms_backend.Service;

import java.util.UUID;

/**
 * 业务单据 ↔ 凭证联动服务（voucher_link，init/gl_voucher_link.sql）：ar/ap 发票开票、
 * 收款/付款单、资金流水过账时生成 gl 凭证 DRAFT 并回填 voucher_id / voucher_line_id。
 *
 * 凭证一律 DRAFT（不自动过账）——过账由会计在凭证模块完成（SoD 录入≠审核≠记账），
 * 联动只负责把单据转成草稿凭证落账并回填；
 * 科目映射读 um.params（ar.receivable_account / ar.revenue_account /
 * ap.payable_account / ap.expense_account / cash.bank_account /
 * cash.clearing_account，值 = 科目 id，经 um.fn_get_param_value SECURITY DEFINER
 * 读口，职责角色无参表权限）；参数缺失/非法抛 ConflictException（40900），
 * 由调用方"尽力生成"兜底（WARN + 单据继续，voucher_id 留空）；
 * 幂等：voucher_id/voucher_line_id 已回填即跳过；凭证头带来源单据派生幂等键
 * （uk_voucher_idem 部分唯一索引兜底），补生成/重试复用既有凭证不重复建账；
 * 联动在调用方事务内执行（同事务边界），失败抛异常由调用方决定回滚或放行。
 */
public interface VoucherLinkService {

    /**
     * 应收发票联动凭证（开票时/补生成端点调用）：DRAFT 凭证两行——借 ar.receivable_account、
     * 贷 ar.revenue_account，金额 = 发票总额，摘要含发票号，期间 = 开票期间，币种参照发票；
     * 回填 ar.invoices.voucher_id。幂等：voucher_id 已回填返回既有值。
     *
     * @param invoiceId 应收发票 id
     * @return 联动凭证 id（幂等命中返回既有凭证 id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkArInvoice(UUID invoiceId);

    /**
     * 应付发票联动凭证（开票时/补生成端点调用）：DRAFT 凭证两行——借 ap.expense_account、
     * 贷 ap.payable_account，金额 = 发票总额，摘要含发票号；回填 ap.invoices.voucher_id。
     * 幂等：voucher_id 已回填返回既有值。
     *
     * @param invoiceId 应付发票 id
     * @return 联动凭证 id（幂等命中返回既有凭证 id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkApInvoice(UUID invoiceId);

    /**
     * 收款单联动凭证（收款登记/核销时调用）：DRAFT 凭证两行——借 cash.bank_account、
     * 贷 ar.receivable_account，金额 = 实收金额（收款单金额）；回填 ar.receipts.voucher_id。
     * 幂等：voucher_id 已回填返回既有值。
     *
     * @param receiptId 收款单 id
     * @return 联动凭证 id（幂等命中返回既有凭证 id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkReceipt(UUID receiptId);

    /**
     * 付款单联动凭证（付款登记/核销时调用）：DRAFT 凭证两行——借 ap.payable_account、
     * 贷 cash.bank_account，金额 = 付款金额；回填 ap.payments.voucher_id。
     * 幂等：voucher_id 已回填返回既有值。
     *
     * @param paymentId 付款单 id
     * @return 联动凭证 id（幂等命中返回既有凭证 id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkPayment(UUID paymentId);

    /**
     * 资金流水联动凭证（流水创建过账时调用）：DRAFT 凭证两行——IN 借 cash.bank_account /
     * 贷 cash.clearing_account，OUT 借 cash.clearing_account / 贷 cash.bank_account，
     * 金额 = 流水金额；回填 cash.transactions.voucher_line_id = 银行科目那行分录 id。
     * 幂等：voucher_line_id 已回填返回既有值。
     *
     * @param trxId 资金流水 id
     * @return 银行科目分录 id（voucher_line_id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkCashTransaction(UUID trxId);

    /**
     * 报销单联动凭证（仅由显式生成凭证动作调用，付款后触发）：DRAFT 凭证两行——
     * 借 ap.expense_account（费用科目）、贷 ap.payable_account（应付账款），金额 = 报销总额，
     * 摘要含报销标题；回填 expense.reimbursements.voucher_id。幂等：voucher_id 已回填返回既有值。
     *
     * @param reimbId 报销单 id
     * @return 联动凭证 id（幂等命中返回既有凭证 id）
     * @throws net.castimage.fms_backend.Exception.ConflictException 科目映射参数缺失/非法或回填冲突（40900）
     */
    UUID linkExpense(UUID reimbId);
}
