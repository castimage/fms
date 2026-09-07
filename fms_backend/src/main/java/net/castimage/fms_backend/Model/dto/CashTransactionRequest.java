package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 收付款流水创建请求：company_id 由所属银行账户推导（防跨公司挂账，RLS WITH CHECK 兜底）；
 * 金额 > 0（DB CHECK 兜底），方向 IN/OUT（DB CHECK 兜底）；初始 match_status=UNMATCHED。
 */
public record CashTransactionRequest(
        /** 银行账户 id */
        @NotNull(message = "银行账户必填")
        UUID bankAccountId,
        /** 方向：IN 收入 / OUT 支出 */
        @NotBlank(message = "方向必填")
        @Pattern(regexp = "IN|OUT", message = "方向仅允许 IN/OUT")
        String direction,
        /** 金额（>0） */
        @NotNull(message = "金额必填")
        @DecimalMin(value = "0.01", message = "金额必须大于 0")
        BigDecimal amount,
        /** 交易对手 */
        String partner,
        /** 摘要（对账模糊匹配数据源） */
        String summary,
        /** 交易日期（缺省当日） */
        LocalDate trxDate) {
}
