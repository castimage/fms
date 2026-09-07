package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 折旧计提批次实体（fa.depreciation_runs）：period 为 'YYYY-MM'；lines 为 JSONB
 * （[{asset_id, asset_code, category, amount, method}]，实体映射 String + fastjson2 解析，
 * 不引 Jackson JsonNode 入实体）；status DRAFT/CONFIRMED，部分唯一索引 uk_depr_run
 * （company_id, period）WHERE status='CONFIRMED' 兜底防同公司同期间重复计提；
 * voucher_id 为双路径（fa.fn_run_depreciation cron / FaServiceImpl 手动）生成的折旧
 * 凭证草稿回填列（展示链路：批次到凭证跳转）。
 */
@TableName(schema = "fa", value = "depreciation_runs")
@Data
public class DepreciationRun {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 计提期间（YYYY-MM） */
    private String period;

    /** 计提人（auth.current_uid()） */
    private UUID runBy;

    /** 状态：DRAFT/CONFIRMED */
    private String status;

    /** 计提明细 JSONB（String 透传，fastjson2 解析） */
    private String lines;

    /** 折旧凭证草稿 id（gl.vouchers，双路径回填） */
    private UUID voucherId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
