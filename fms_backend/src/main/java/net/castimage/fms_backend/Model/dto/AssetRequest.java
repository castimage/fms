package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 固定资产创建/更新请求：创建恒 ACTIVE，companyId 可缺省——由 Service 从会话
 * scope（um.user_org_scope 唯一可见公司）推导，多公司用户仍须显式指定；
 * 更新仅非敏感字段（assetCode/category/residualValue/
 * lifeMonths/depreciationMethod/startDate），companyId 禁改（与现有资产不一致 40001），
 * originalValue 仅在 ACTIVE 可改（开始计提后冻结）；
 * 折旧方法/状态枚举 DB CHECK 兜底；残值≤原值、累计折旧≤原值 DB CHECK 兜底。
 */
public record AssetRequest(
        /** 所属公司（创建可缺省，Service 从会话 scope 推导；更新时须与资产现有公司一致） */
        UUID companyId,
        /** 资产编码（公司内唯一 uk_asset） */
        @NotBlank(message = "资产编码必填")
        String assetCode,
        /** 资产类别 */
        @NotBlank(message = "资产类别必填")
        String category,
        /** 原值（>=0） */
        @NotNull(message = "原值必填")
        @DecimalMin(value = "0", message = "原值不能为负")
        BigDecimal originalValue,
        /** 残值（>=0，缺省 0） */
        @DecimalMin(value = "0", message = "残值不能为负")
        BigDecimal residualValue,
        /** 使用月数（>0） */
        @NotNull(message = "使用月数必填")
        @Min(value = 1, message = "使用月数必须大于 0")
        @Max(value = 600, message = "使用月数超上限（50 年）")
        Integer lifeMonths,
        /** 折旧方法：STRAIGHT_LINE / DOUBLE_DECLINING（缺省 STRAIGHT_LINE） */
        @Pattern(regexp = "STRAIGHT_LINE|DOUBLE_DECLINING", message = "折旧方法仅允许 STRAIGHT_LINE/DOUBLE_DECLINING")
        String depreciationMethod,
        /** 开始计提日期（缺省当日） */
        LocalDate startDate) {
}
