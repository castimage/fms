package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.CashTransaction;
import net.castimage.fms_backend.Model.vo.CashTransactionView;
import net.castimage.fms_backend.Model.vo.DailyForecastRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 收付款流水 Mapper（cash.transactions）：对账配对（自动/人工）为原子 UPDATE
 * （WHERE match_status='UNMATCHED' 守卫，0 行即已被并发消费则 Service 抛 40900）；
 * 未来 7 天资金预测为 B 类手写 SQL（ar 应收到期未核销 - ap 应付到期未核销，逐日聚合）。
 */
public interface CashTransactionMapper extends BaseMapper<CashTransaction> {

    /** 流水分页列表：join 银行账户名；direction/matchStatus/companyId 可选过滤（排序白名单固定） */
    @Select("""
            SELECT t.id, t.company_id, t.bank_account_id, b.bank_name, t.direction,
                   t.amount, t.partner, t.summary, t.trx_date, t.match_status,
                   t.matched_line_id, t.similarity_score, t.reconciled_at, t.created_at
              FROM cash.transactions t
              LEFT JOIN cash.bank_accounts b ON b.id = t.bank_account_id AND b.deleted_at IS NULL
             WHERE t.deleted_at IS NULL
               AND (#{bankAccountId}::uuid IS NULL OR t.bank_account_id = #{bankAccountId}::uuid)
               AND (#{companyId}::uuid IS NULL OR t.company_id = #{companyId}::uuid)
               AND (#{direction, jdbcType=VARCHAR} IS NULL OR #{direction, jdbcType=VARCHAR} = ''
                    OR t.direction = #{direction, jdbcType=VARCHAR})
               AND (#{matchStatus, jdbcType=VARCHAR} IS NULL OR #{matchStatus, jdbcType=VARCHAR} = ''
                    OR t.match_status = #{matchStatus, jdbcType=VARCHAR})
             ORDER BY t.trx_date DESC, t.id
            """)
    IPage<CashTransactionView> selectTransactionPage(Page<CashTransactionView> page,
                                                     @Param("bankAccountId") UUID bankAccountId,
                                                     @Param("companyId") UUID companyId,
                                                     @Param("direction") String direction,
                                                     @Param("matchStatus") String matchStatus);

    /**
     * 流水侧原子配对（自动对账/人工对账共用）：仅 UNMATCHED 可消费，防并发双配；
     * 命中 0 行返回 0（调用方按"已被消费"40900 处理或跳过继续）。
     *
     * @param id     流水 id
     * @param lineId 对账单行 id（双向互指）
     * @param score  相似度（人工对账为 null）
     * @return 影响行数（0 表示已被并发消费）
     */
    @Update("""
            UPDATE cash.transactions
               SET match_status = 'MATCHED',
                   matched_line_id = #{lineId}::uuid,
                   similarity_score = #{score},
                   reconciled_at = now(),
                   updated_at = now()
             WHERE id = #{id}::uuid AND match_status = 'UNMATCHED' AND deleted_at IS NULL
            """)
    int markMatched(@Param("id") UUID id, @Param("lineId") UUID lineId,
                    @Param("score") BigDecimal score);

    /**
     * 流水侧配对回滚（对账单行已被并发消费时补偿，防双边状态不一致）。
     */
    @Update("""
            UPDATE cash.transactions
               SET match_status = 'UNMATCHED',
                   matched_line_id = NULL,
                   similarity_score = NULL,
                   reconciled_at = NULL,
                   updated_at = now()
             WHERE id = #{id}::uuid
            """)
    void undoMarked(@Param("id") UUID id);

    /**
     * 人工对账撤销（流水侧）：仅当互指关系与请求一致才回置 UNMATCHED（清相似度/配对时间），
     * 0 行则 Service 抛 40900（"流水未处于已匹配状态"）。
     */
    @Update("""
            UPDATE cash.transactions
               SET match_status = 'UNMATCHED',
                   matched_line_id = NULL,
                   similarity_score = NULL,
                   reconciled_at = NULL,
                   updated_at = now()
             WHERE id = #{id}::uuid AND matched_line_id = #{lineId}::uuid
               AND match_status IN ('MATCHED', 'MANUAL') AND deleted_at IS NULL
            """)
    int undoMatched(@Param("id") UUID id, @Param("lineId") UUID lineId);

    /**
     * 资金日报 JSON（B 类）：调 PG SECURITY DEFINER 函数 cash.fn_daily_report
     * （STABLE，函数内校验公司数据范围，越权 RAISE 则 40300），返回 jsonb 字符串
     * {opening_balance, income, expense, net, closing_balance, by_account[]}，
     * Service 侧 fastjson2 解析。
     */
    @Select("SELECT cash.fn_daily_report(#{companyId}::uuid, #{date})")
    String selectDailyReportJson(@Param("companyId") UUID companyId, @Param("date") LocalDate date);

    /**
     * 未来 7 天资金预测（B 类核心 SQL）：按日聚合 ar 应收到期未核销（ISSUED/PARTIAL
     * 发票余额 = total - 已核销）与 ap 应付到期未核销，净额 = 应收 - 应付；
     * 各子查询带 company_id 等值 + RLS can_access_org 行级收敛（越权公司返回 0）。
     */
    @Select("""
            SELECT day, ar_due, ap_due, ar_due - ap_due AS net_due
              FROM (
                SELECT g.day::date AS day,
                       COALESCE((SELECT SUM(i.total - COALESCE(alloc.amt, 0))
                                   FROM ar.invoices i
                                   LEFT JOIN (SELECT invoice_id, SUM(amount) AS amt
                                                FROM ar.receipt_allocations
                                               WHERE deleted_at IS NULL GROUP BY invoice_id) alloc
                                     ON alloc.invoice_id = i.id
                                  WHERE i.company_id = #{companyId}::uuid
                                    AND i.deleted_at IS NULL
                                    AND i.status IN ('ISSUED', 'PARTIAL')
                                    AND i.due_date = g.day::date), 0) AS ar_due,
                       COALESCE((SELECT SUM(p.total - COALESCE(paid.amt, 0))
                                   FROM ap.invoices p
                                   LEFT JOIN (SELECT invoice_id, SUM(amount) AS amt
                                                FROM ap.payment_allocations
                                               WHERE deleted_at IS NULL GROUP BY invoice_id) paid
                                     ON paid.invoice_id = p.id
                                  WHERE p.company_id = #{companyId}::uuid
                                    AND p.deleted_at IS NULL
                                    AND p.status IN ('ISSUED', 'PARTIAL')
                                    AND p.due_date = g.day::date), 0) AS ap_due
                  FROM generate_series(#{date}::date + 1, #{date}::date + 7, interval '1 day') g(day)
              ) t
             ORDER BY day
            """)
    List<DailyForecastRow> selectForecast(@Param("companyId") UUID companyId,
                                          @Param("date") LocalDate date);
}
