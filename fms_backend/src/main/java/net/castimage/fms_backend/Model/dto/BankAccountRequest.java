package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 银行账户创建/更新请求：创建经 SECURITY DEFINER 闸口 cash.fn_create_bank_account
 * （accountNo 密文+盲索引函数内原子落库，uk_bank_account_hash 冲突 23505 映射 40900）；
 * 更新只动非敏感列（bankName/accountType/currency/status/isActive），accountNo 变更视为新建（禁改密文列）。
 * accountNoMasked 为请求方自持的展示掩码（如 ****1234），后端不存明文也不重建掩码。
 */
public record BankAccountRequest(
        /** 开户行名称 */
        @NotBlank(message = "开户行必填")
        String bankName,
        /** 银行账号（仅创建时生效；更新忽略） */
        @NotBlank(message = "银行账号必填")
        String accountNo,
        /** 展示掩码（前端自持，如 ****1234，不落库） */
        String accountNoMasked,
        /** 账户类型（CORPORATE/PERSONAL，缺省 CORPORATE） */
        String accountType,
        /** 币种（3 位 ISO，缺省 CNY） */
        String currency,
        /** 状态（ACTIVE/FROZEN/CLOSED，缺省 ACTIVE） */
        String status,
        /** 是否启用（缺省 true） */
        Boolean isActive) {
}
