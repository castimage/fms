package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.ArInvoice;
import net.castimage.fms_backend.Model.vo.AgingRow;
import net.castimage.fms_backend.Model.vo.ArInvoiceView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 应收发票 Mapper（ar.invoices）：状态机经 DRAFT 开票 ISSUED、部分核销 PARTIAL、收清 PAID、可作废 VOID，不可变触发器终审；分页/未核销余额/客户维度查询手写 */
public interface ArInvoiceMapper extends BaseMapper<ArInvoice> {

    /** 发票分页列表（join 客户名 + 状态/关键字过滤；排序白名单固定 invoice_date DESC, invoice_no） */
    @Select("""
            SELECT i.id, i.company_id, i.customer_id, c.name AS customer_name,
                   i.invoice_no, i.invoice_date, i.due_date, i.currency, i.total, i.tax,
                   i.status, i.voucher_id, i.version, i.created_at
              FROM ar.invoices i
              JOIN ar.customers c ON c.id = i.customer_id
             WHERE i.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR i.company_id = #{companyId}::uuid)
               AND (#{customerId}::uuid IS NULL OR i.customer_id = #{customerId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR i.status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR i.invoice_no ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY i.invoice_date DESC, i.invoice_no
            """)
    IPage<ArInvoiceView> selectInvoicePage(Page<ArInvoiceView> page,
                                           @Param("companyId") UUID companyId,
                                           @Param("customerId") UUID customerId,
                                           @Param("status") String status,
                                           @Param("keyword") String keyword);

    /** 发票详情（join 客户名；RLS 空集返回 null 则 data:null） */
    @Select("""
            SELECT i.id, i.company_id, i.customer_id, c.name AS customer_name,
                   i.invoice_no, i.invoice_date, i.due_date, i.currency, i.total, i.tax,
                   i.status, i.voucher_id, i.version, i.created_at
              FROM ar.invoices i
              JOIN ar.customers c ON c.id = i.customer_id
             WHERE i.id = #{id} AND i.deleted_at IS NULL
            """)
    ArInvoiceView selectDetail(@Param("id") UUID id);

    /** 客户维度发票列表（账龄数据源：ISSUED/PARTIAL 未付清发票） */
    @Select("""
            SELECT i.id, i.company_id, i.customer_id, c.name AS customer_name,
                   i.invoice_no, i.invoice_date, i.due_date, i.currency, i.total, i.tax,
                   i.status, i.voucher_id, i.version, i.created_at
              FROM ar.invoices i
              JOIN ar.customers c ON c.id = i.customer_id
             WHERE i.customer_id = #{customerId} AND i.deleted_at IS NULL
             ORDER BY i.invoice_date, i.invoice_no
            """)
    List<ArInvoiceView> selectByCustomerId(@Param("customerId") UUID customerId);

    /**
     * 账龄分析（B 类窗口函数手写 SQL）：直查 rpt.ar_aging 视图（ISSUED/PARTIAL 且
     * 未核销余额 = total - ar_aging_paid IMMV，security_invoker=on 行级收敛），
     * 按 invoice_date 到当日的天数分桶（0-30/31-60/61-90/91-180/180+），窗口函数
     * SUM OVER (PARTITION BY 客户×桶) 聚合未核销余额后 DISTINCT 折叠成行；
     * companyId 仅过滤提示，越权值返回空集。
     */
    @Select("""
            SELECT DISTINCT customer_id, customer_name, bucket,
                   SUM(open_balance) OVER (PARTITION BY customer_id, bucket) AS open_balance
              FROM (
                   SELECT a.customer_id, c.name AS customer_name,
                          CASE WHEN (CURRENT_DATE - a.invoice_date) <= 30 THEN '0-30'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 60 THEN '31-60'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 90 THEN '61-90'
                               WHEN (CURRENT_DATE - a.invoice_date) <= 180 THEN '91-180'
                               ELSE '180+'
                          END AS bucket,
                          a.open_balance
                     FROM rpt.ar_aging a
                     JOIN ar.customers c ON c.id = a.customer_id
                    WHERE a.open_balance > 0
                      AND (#{companyId}::uuid IS NULL OR a.company_id = #{companyId}::uuid)
                   ) t
             ORDER BY customer_id, bucket
            """)
    List<AgingRow> selectAging(@Param("companyId") UUID companyId);

    /**
     * 客户未核销应收余额 = SUM(total - 已核销)；已核销取 rpt.ar_aging_paid IMMV
     * （receipt_allocations 增量聚合，automatic 模式提交即一致）；仅统计
     * ISSUED/PARTIAL（作废/结清不构成应收）。
     */
    @Select("""
            SELECT COALESCE(SUM(i.total - COALESCE(a.paid_amount, 0)), 0)
              FROM ar.invoices i
              LEFT JOIN rpt.ar_aging_paid a ON a.invoice_id = i.id
             WHERE i.customer_id = #{customerId}
               AND i.status IN ('ISSUED', 'PARTIAL')
               AND i.deleted_at IS NULL
            """)
    BigDecimal selectOutstandingByCustomer(@Param("customerId") UUID customerId);
}
