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
 * 收付款流水实体（cash.transactions）：match_status 三态 UNMATCHED/MATCHED/MANUAL，
 * 对账配对（自动/人工）双向互指 matched_line_id 指向 cash.bank_statement_lines，
 * similarity_score 记录配对摘要相似度（pg_trgm，自动对账 ≥0.9 才自动配对）。
 */
@TableName(schema = "cash", value = "transactions")
@Data
public class CashTransaction {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 银行账户 id（本表挂账账户） */
    private UUID bankAccountId;

    /** 方向：IN 收入 / OUT 支出 */
    private String direction;

    /** 金额（>0） */
    private BigDecimal amount;

    /** 交易对手 */
    private String partner;

    /** 摘要（trgm GIN 索引，对账模糊匹配数据源） */
    private String summary;

    /** 交易日期 */
    private LocalDate trxDate;

    /** 创建幂等键（规范化请求内容 SHA-256，uk_trx_idem 部分唯一索引防同 body 重复提交） */
    private String idemKey;

    /** 关联凭证分录 id（流水创建即联动：回填银行科目分录行 id，对账/核销追溯锚点） */
    private UUID voucherLineId;

    /** 匹配状态：UNMATCHED/MATCHED/MANUAL */
    private String matchStatus;

    /** 已配对的对账单行 id（双向互指） */
    private UUID matchedLineId;

    /** 配对摘要相似度（0-1，自动对账写入） */
    private BigDecimal similarityScore;

    /** 配对时间（自动/人工对账写入） */
    private LocalDateTime reconciledAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
