package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 科目创建/更新请求：根科目（无 parentId）必须显式指定公司，有父科目时以父科目公司为准（不一致 40900）；更新时 code/path 禁改，仅 name/accountType/direction/isForeignCurrency/currency/status 生效。
 */
public record AccountRequest(
        /** 所属公司（根科目必填；有父科目时校验一致） */
        UUID companyId,
        /** 科目编码（ltree 标签：字母/数字/下划线；创建必填，更新忽略） */
        @NotBlank(message = "科目编码必填")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "科目编码仅允许字母/数字/下划线（ltree 标签约束）")
        @Size(max = 32, message = "科目编码最长 32 字符")
        String code,
        /** 科目名称 */
        @NotBlank(message = "科目名称必填")
        @Size(max = 128, message = "科目名称最长 128 字符")
        String name,
        /** 父科目 id（null 表示根科目） */
        UUID parentId,
        /** 科目类型：ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE */
        @NotBlank(message = "科目类型必填")
        @Pattern(regexp = "ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE", message = "科目类型须为 ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE")
        String accountType,
        /** 余额方向：DEBIT/CREDIT */
        @NotBlank(message = "余额方向必填")
        @Pattern(regexp = "DEBIT|CREDIT", message = "余额方向须为 DEBIT/CREDIT")
        String direction,
        Boolean isForeignCurrency, // 缺省 false，
        /** 币种（CHAR(3)，外币科目必填） */
        @Size(max = 3, message = "币种为 3 位 ISO 代码")
        String currency, // 外币科目必填，
        /** 状态：ACTIVE/DISABLED（创建缺省 ACTIVE；更新时可改） */
        @Pattern(regexp = "ACTIVE|DISABLED", message = "科目状态须为 ACTIVE/DISABLED")
        String status) {
}
