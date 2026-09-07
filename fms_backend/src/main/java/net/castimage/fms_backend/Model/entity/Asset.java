package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 固定资产卡片实体（fa.assets）：net_value 为 GENERATED ALWAYS 生成列（原值-累计折旧），
 * 只读不参与 INSERT/UPDATE（FieldStrategy.NEVER）；计提只纳入 status IN (ACTIVE, DEPRECIATING)
 * 且 start_date 已到期的资产，处置/报废后 trg_asset_immutable 终审禁改禁删。
 */
@TableName(schema = "fa", value = "assets")
@Data
public class Asset {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 资产编码（公司内唯一 uk_asset） */
    private String assetCode;

    /** 资产类别（如 办公设备/运输工具） */
    private String category;

    /** 原值（>=0） */
    private BigDecimal originalValue;

    /** 残值（>=0，缺省 0） */
    private BigDecimal residualValue;

    /** 使用月数（>0） */
    private Integer lifeMonths;

    /** 折旧方法：STRAIGHT_LINE 直线 / DOUBLE_DECLINING 双倍余额递减 */
    private String depreciationMethod;

    /** 累计折旧（>=0，计提批次确认后由后续过账流程累加） */
    private BigDecimal accumulatedDepreciation;

    /** 净值（生成列：original_value - accumulated_depreciation，只读不写） */
    @TableField(value = "net_value", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal netValue;

    /** 开始计提日期（NULL 表示未开始计提） */
    private LocalDate startDate;

    /** 状态：ACTIVE/DEPRECIATING/DISPOSED/SCRAPPED */
    private String status;

    /** 处置/报废日期（DISPOSED/SCRAPPED 时写入） */
    private LocalDate disposalDate;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
