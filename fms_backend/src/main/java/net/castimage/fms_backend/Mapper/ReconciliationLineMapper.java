package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.ReconciliationLine;
import net.castimage.fms_backend.Model.vo.ReconciliationLineView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 对账单行 Mapper（ap.reconciliation_lines）：行导入/列表/人工匹配原子守卫 */
public interface ReconciliationLineMapper extends BaseMapper<ReconciliationLine> {

    /** 按对账单查行列表（join 匹配发票号；按交易日期排序） */
    @Select("""
            SELECT l.id, l.reconciliation_id, l.trx_date, l.amount, l.summary,
                   l.match_status, l.matched_invoice_id, i.invoice_no AS matched_invoice_no,
                   l.similarity_score, l.created_at
              FROM ap.reconciliation_lines l
              LEFT JOIN ap.invoices i ON i.id = l.matched_invoice_id
             WHERE l.reconciliation_id = #{reconciliationId} AND l.deleted_at IS NULL
             ORDER BY l.trx_date, l.created_at
            """)
    List<ReconciliationLineView> selectByReconciliationId(@Param("reconciliationId") UUID reconciliationId);

    /**
     * 人工匹配原子守卫（B 类手写 SQL）：WHERE match_status='UNMATCHED' 条件化——
     * 并发双配/已匹配行命中 0 行则 Service 转 40900"行已被匹配"；matched_invoice_id
     * 仅回填不核销（核销走 payment_allocations，与对账解耦）。
     *
     * @param id        对账单行 id
     * @param status    目标状态（MATCHED/MANUAL）
     * @param invoiceId 匹配发票 id（可为 null 表示清除匹配）
     * @return 更新行数（0 = 已被消费）
     */
    @Update("""
            UPDATE ap.reconciliation_lines
               SET match_status = #{status},
                   matched_invoice_id = #{invoiceId},
                   updated_at = now()
             WHERE id = #{id} AND match_status = 'UNMATCHED' AND deleted_at IS NULL
            """)
    int updateMatchStatus(@Param("id") UUID id,
                          @Param("status") String status,
                          @Param("invoiceId") UUID invoiceId);

    /** 未匹配行计数（confirm 前校验全部行处理完） */
    @Select("""
            SELECT COUNT(*)
              FROM ap.reconciliation_lines
             WHERE reconciliation_id = #{reconciliationId}
               AND match_status = 'UNMATCHED' AND deleted_at IS NULL
            """)
    long countUnmatched(@Param("reconciliationId") UUID reconciliationId);
}
