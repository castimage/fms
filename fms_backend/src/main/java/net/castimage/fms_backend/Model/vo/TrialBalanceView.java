package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 试算平衡响应（GET /api/gl/trial-balance）：balanced = 期末借方合计 == 期末贷方合计（每张已过账凭证借贷平衡则恒等，异常即断链）。
 */
public record TrialBalanceView(String period, List<TrialBalanceRow> rows,
                               BigDecimal openingDebitTotal, BigDecimal openingCreditTotal,
                               BigDecimal periodDebitTotal, BigDecimal periodCreditTotal,
                               BigDecimal closingDebitTotal, BigDecimal closingCreditTotal,
                               boolean balanced) { // 恒等断链即异常，
}
