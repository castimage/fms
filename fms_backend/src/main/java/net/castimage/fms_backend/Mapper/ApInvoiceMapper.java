package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.ApInvoice;
import net.castimage.fms_backend.Model.vo.ApAgingRow;
import net.castimage.fms_backend.Model.vo.ApInvoiceView;
import net.castimage.fms_backend.Model.vo.MatchCandidate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 应付发票 Mapper（ap.invoices）：状态机与 ar 同构，不可变触发器终审；分页/未核销余额/对账候选匹配手写 */
public interface ApInvoiceMapper extends BaseMapper<ApInvoice> {

    /** 发票分页列表（join 供应商名；supplierId/status/keyword 过滤；排序白名单固定） */
    @Select("""
            SELECT i.id, i.company_id, i.supplier_id, s.name AS supplier_name,
                   i.invoice_no, i.invoice_date, i.due_date, i.currency, i.total, i.tax,
                   i.status, i.voucher_id, i.version, i.created_at
              FROM ap.invoices i
              JOIN ap.suppliers s ON s.id = i.supplier_id
             WHERE i.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR i.company_id = #{companyId}::uuid)
               AND (#{supplierId}::uuid IS NULL OR i.supplier_id = #{supplierId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR i.status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR i.invoice_no ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY i.invoice_date DESC, i.invoice_no
            """)
    IPage<ApInvoiceView> selectInvoicePage(Page<ApInvoiceView> page,
                                           @Param("companyId") UUID companyId,
                                           @Param("supplierId") UUID supplierId,
                                           @Param("status") String status,
                                           @Param("keyword") String keyword);

    /** 发票详情（join 供应商名；RLS 空集则返回 null） */
    @Select("""
            SELECT i.id, i.company_id, i.supplier_id, s.name AS supplier_name,
                   i.invoice_no, i.invoice_date, i.due_date, i.currency, i.total, i.tax,
                   i.status, i.voucher_id, i.version, i.created_at
              FROM ap.invoices i
              JOIN ap.suppliers s ON s.id = i.supplier_id
             WHERE i.id = #{id} AND i.deleted_at IS NULL
            """)
    ApInvoiceView selectDetail(@Param("id") UUID id);

    /**
     * 供应商未核销应付余额 = SUM(total - 已核销)；已核销为 payment_allocations
     * 聚合（关联子查询）；仅统计 ISSUED/PARTIAL（作废/结清不构成应付）。
     */
    @Select("""
            SELECT COALESCE(SUM(i.total - COALESCE(pa.paid, 0)), 0)
              FROM ap.invoices i
              LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid
                           FROM ap.payment_allocations
                          WHERE deleted_at IS NULL
                          GROUP BY invoice_id) pa ON pa.invoice_id = i.id
             WHERE i.supplier_id = #{supplierId}
               AND i.status IN ('ISSUED', 'PARTIAL')
               AND i.deleted_at IS NULL
            """)
    BigDecimal selectOutstanding(@Param("supplierId") UUID supplierId);

    /**
     * 应付账龄分析（B 类窗口函数手写 SQL）：直查 rpt.ap_aging 视图（ISSUED/PARTIAL 且
     * 未核销余额 = total - rpt.ap_aging_paid IMMV 已核销，security_invoker=on 行级收敛），
     * 按 invoice_date 到当日的天数分桶（0-30/31-60/61-90/91-180/180+），窗口函数
     * SUM OVER (PARTITION BY 供应商×桶) 聚合未核销余额后 DISTINCT 折叠成行；
     * companyId 仅过滤提示，越权值返回空集。
     */
    @Select("""
            SELECT DISTINCT supplier_id, supplier_name, bucket,
                   SUM(open_balance) OVER (PARTITION BY supplier_id, bucket) AS open_balance
              FROM (
                   SELECT a.supplier_id, s.name AS supplier_name,
                          CASE WHEN (CURRENT_DATE - a.invoice_date) <= 30 THEN '0-30'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 60 THEN '31-60'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 90 THEN '61-90'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 180 THEN '91-180'
                               ELSE '180+'
                          END AS bucket,
                          a.open_balance
                     FROM rpt.ap_aging a
                     JOIN ap.suppliers s ON s.id = a.supplier_id
                    WHERE a.open_balance > 0
                      AND (#{companyId}::uuid IS NULL OR a.company_id = #{companyId}::uuid)
                   ) t
             ORDER BY supplier_id, bucket
            """)
    List<ApAgingRow> selectAging(@Param("companyId") UUID companyId);

    /**
     * 对账候选匹配（B 类手写 SQL）：金额相等 + 日期差≤3 天 + 未核销
     * （ISSUED/PARTIAL）的应付发票，按 similarity(摘要, 发票号) 降序取前 N；
     * 已核销金额取 payment_allocations 聚合（不核销，仅回填 matched_invoice_id）。
     *
     * @param companyId 公司（RLS 收敛，越权值返回空集）
     * @param date      对账单行交易日期（|差|≤3 天）
     * @param amount    对账单行金额（与发票总额相等）
     * @param summary   对账单行摘要（similarity 数据源）
     * @return 候选列表（降序）
     */
    @Select("""
            SELECT i.id AS invoice_id, i.invoice_no, i.invoice_date, i.total,
                   i.total - COALESCE(pa.paid, 0) AS outstanding,
                   similarity(COALESCE(#{summary, jdbcType=VARCHAR}, ''), i.invoice_no) AS similarity
              FROM ap.invoices i
              LEFT JOIN (SELECT invoice_id, SUM(amount) AS paid
                           FROM ap.payment_allocations
                          WHERE deleted_at IS NULL
                          GROUP BY invoice_id) pa ON pa.invoice_id = i.id
             WHERE i.company_id = #{companyId}
               AND i.status IN ('ISSUED', 'PARTIAL')
               AND i.deleted_at IS NULL
               AND i.total = #{amount}
               AND abs(i.invoice_date - CAST(#{date} AS date)) <= 3
             ORDER BY similarity DESC, abs(i.invoice_date - CAST(#{date} AS date)), i.invoice_no
             LIMIT #{limit}
            """)
    List<MatchCandidate> selectMatchCandidates(@Param("companyId") UUID companyId,
                                               @Param("date") LocalDate date,
                                               @Param("amount") BigDecimal amount,
                                               @Param("summary") String summary,
                                               @Param("limit") int limit);
}
