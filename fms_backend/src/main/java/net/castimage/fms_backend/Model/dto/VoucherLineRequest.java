package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 凭证分录请求（创建/更新共用单行）：借/贷金额互斥（单边分录）、非负、不可双零；科目须末级且 ACTIVE（trg_voucher_line_leaf）——Java 预检 + DB 约束/触发器双保险。
 */
public record VoucherLineRequest(
        /** 行号（可选；缺省按数组顺序 1..n，重复由 DB uk_voucher_line 兜底 409） */
        Integer lineNo,
        /** 末级科目 id */
        @NotNull(message = "分录科目必填")
        UUID accountId,
        /** 摘要 */
        @Size(max = 500, message = "摘要最长 500 字符")
        String summary,
        /** 借方金额（缺省 0；与 credit 互斥） */
        @Digits(integer = 16, fraction = 2, message = "借方金额最多 2 位小数")
        BigDecimal debit,
        /** 贷方金额（缺省 0；与 debit 互斥） */
        @Digits(integer = 16, fraction = 2, message = "贷方金额最多 2 位小数")
        BigDecimal credit,
        /** 币种（缺省 CNY） */
        @Size(max = 3, message = "币种为 3 位 ISO 代码")
        String currency,
        /** 部门 id（分录级，缺省继承凭证头） */
        UUID deptId, // 分录级覆盖，
        /** 往来单位 id（ar/ap 批次使用） */
        UUID partnerId) {
}
