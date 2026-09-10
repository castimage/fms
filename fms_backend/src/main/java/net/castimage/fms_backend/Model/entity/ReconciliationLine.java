package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对账单行实体（ap.reconciliation_lines）：match_status UNMATCHED/MATCHED/MANUAL
 * （DB CHECK 兜底）；matched_invoice_id 仅回填不核销（核销走 payment_allocations，
 * 与对账解耦）；人工匹配经 updateMatchStatus 原子守卫（WHERE match_status='UNMATCHED'，
 * 0 行则 40900 防双配）；similarity_score 存候选建议（>0.6 自动建议）。
 */
@TableName(schema = "ap", value = "reconciliation_lines")
@Data
public class ReconciliationLine {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 对账单 id（语义引用 ap.reconciliations） */
    private UUID reconciliationId;

    /** 交易日期（供应商对账单行） */
    private LocalDate trxDate;

    /** 金额 */
    private BigDecimal amount;

    /** 摘要（候选匹配 similarity 数据源） */
    private String summary;

    /** 匹配状态：UNMATCHED/MATCHED/MANUAL */
    private String matchStatus;

    /** 回填匹配发票（仅回填不核销） */
    private UUID matchedInvoiceId;

    /** 匹配相似度（NUMERIC(6,4)） */
    private BigDecimal similarityScore;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
