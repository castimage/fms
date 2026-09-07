package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 期末汇率实体（gl.fx_rates）：公司×币种×期间唯一（uk_fx_rate）；rate 为 1 单位外币折本币
 * （本币=1，如 USD 对 CNY 7.25）；期末调汇（fn_fx_adjustment）与 cron 月度任务的汇率依据，
 * 无汇率记录的外币科目在调汇时跳过。
 */
@TableName(schema = "gl", value = "fx_rates")
@Data
public class FxRate {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 币种（CHAR(3) ISO 代码） */
    private String currency;

    /** 期间（YYYY-MM） */
    private String period;

    /** 期末汇率（NUMERIC(18,6)，> 0） */
    private BigDecimal rate;

    /** 维护人（会话用户） */
    private UUID updatedBy;

    /** 更新时间（trg_fx_rates_updated 刷新） */
    private LocalDateTime updatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
