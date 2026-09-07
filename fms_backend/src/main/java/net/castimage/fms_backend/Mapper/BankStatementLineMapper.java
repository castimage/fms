package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.BankStatementLine;
import net.castimage.fms_backend.Model.vo.AutoMatchCandidate;
import net.castimage.fms_backend.Model.vo.BankStatementLineView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 银行对账单行 Mapper（cash.bank_statement_lines）：自动对账候选为 B 类核心 SQL
 * （金额相等 + 日期差≤3 天 + pg_trgm similarity 排序，走 ix_bsl_summary GIN trgm 索引）；
 * 配对/撤销与流水侧同构（WHERE match_status 原子守卫）。
 */
public interface BankStatementLineMapper extends BaseMapper<BankStatementLine> {

    /** 对账单行列表（详情接口）：按交易日期/主键自然序 */
    @Select("""
            SELECT l.id, l.statement_id, l.company_id, l.trx_date, l.amount, l.summary,
                   l.direction, l.match_status, l.matched_trx_id, l.similarity_score, l.created_at
              FROM cash.bank_statement_lines l
             WHERE l.statement_id = #{statementId}::uuid AND l.deleted_at IS NULL
             ORDER BY l.trx_date, l.id
            """)
    List<BankStatementLineView> selectByStatementId(@Param("statementId") UUID statementId);

    /**
     * 自动对账候选（B 类核心 SQL，与 PG 函数 cash.fn_auto_reconcile 同口径）：
     * 同一银行账户内 金额相等 + 日期差≤3 天 + 未匹配的对账单行，按摘要相似度降序取最优；
     * 摘要相似度用 pg_trgm similarity()（% 下界走 ix_bsl_summary GIN 索引消除全表候选扫描）；
     * 摘要为 NULL 时 % 求值为 NULL 则不进候选（与 similarity(NULL) 不配对行为一致）。
     *
     * @param companyId     流水公司
     * @param bankAccountId 银行账户（双方须同账户）
     * @param trxDate       流水日期
     * @param amount        流水金额
     * @param summary       流水摘要
     * @return 最优候选（sim≥0.9 才自动配对，否则留待人工/模糊匹配）
     */
    @Select("""
            SELECT bsl.id AS line_id, bs.id AS statement_id,
                   similarity(COALESCE(#{summary, jdbcType=VARCHAR}, ''),
                              COALESCE(bsl.summary, '')) AS sim
              FROM cash.bank_statement_lines bsl
              JOIN cash.bank_statements bs ON bs.id = bsl.statement_id AND bs.deleted_at IS NULL
             WHERE bsl.match_status = 'UNMATCHED' AND bsl.deleted_at IS NULL
               AND bs.bank_account_id = #{bankAccountId}::uuid
               AND bs.company_id = #{companyId}::uuid
               AND bsl.amount = #{amount}
               AND abs(bsl.trx_date - #{trxDate}::date) <= 3
               AND bsl.summary % #{summary, jdbcType=VARCHAR}::text
             ORDER BY similarity(COALESCE(#{summary, jdbcType=VARCHAR}, ''),
                                 COALESCE(bsl.summary, '')) DESC,
                      abs(bsl.trx_date - #{trxDate}::date), bsl.id
             LIMIT 1
            """)
    AutoMatchCandidate selectBestCandidate(@Param("companyId") UUID companyId,
                                           @Param("bankAccountId") UUID bankAccountId,
                                           @Param("trxDate") LocalDate trxDate,
                                           @Param("amount") BigDecimal amount,
                                           @Param("summary") String summary);

    /**
     * 对账单行侧原子配对：仅 UNMATCHED 可消费（防并发双配）；
     * 0 行时调用方需回滚流水侧配对（undoMarked 补偿，保持双边一致）。
     *
     * @param id    对账单行 id
     * @param trxId 流水 id（双向互指）
     * @param score 相似度（人工对账为 null）
     * @return 影响行数
     */
    @Update("""
            UPDATE cash.bank_statement_lines
               SET match_status = 'MATCHED',
                   matched_trx_id = #{trxId}::uuid,
                   similarity_score = #{score},
                   updated_at = now()
             WHERE id = #{id}::uuid AND match_status = 'UNMATCHED' AND deleted_at IS NULL
            """)
    int markMatched(@Param("id") UUID id, @Param("trxId") UUID trxId,
                    @Param("score") BigDecimal score);

    /**
     * 对账单行侧配对撤销（人工撤销/自动对账补偿）：仅当互指关系与请求一致才回置 UNMATCHED。
     */
    @Update("""
            UPDATE cash.bank_statement_lines
               SET match_status = 'UNMATCHED',
                   matched_trx_id = NULL,
                   similarity_score = NULL,
                   updated_at = now()
             WHERE id = #{id}::uuid AND matched_trx_id = #{trxId}::uuid
               AND match_status IN ('MATCHED', 'MANUAL') AND deleted_at IS NULL
            """)
    int undoMatched(@Param("id") UUID id, @Param("trxId") UUID trxId);
}
