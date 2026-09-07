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
 * 银行对账单行实体（cash.bank_statement_lines）：与 cash.transactions 双向互指配对
 * （matched_trx_id 与 transactions.matched_line_id 双向互指），match_status 三态与流水同构；
 * summary 走 trgm GIN 索引支撑自动对账相似度候选扫描。
 */
@TableName(schema = "cash", value = "bank_statement_lines")
@Data
public class BankStatementLine {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 所属对账单 id */
    private UUID statementId;

    /** 交易日期 */
    private LocalDate trxDate;

    /** 金额（银行侧口径） */
    private BigDecimal amount;

    /** 摘要（trgm GIN 索引） */
    private String summary;

    /** 方向：IN/OUT（银行对账单可缺省） */
    private String direction;

    /** 匹配状态：UNMATCHED/MATCHED/MANUAL */
    private String matchStatus;

    /** 已配对的收付款流水 id（双向互指） */
    private UUID matchedTrxId;

    /** 配对摘要相似度（0-1，自动对账写入） */
    private BigDecimal similarityScore;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
