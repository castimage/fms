package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.VoucherLine;
import net.castimage.fms_backend.Model.vo.LedgerView;
import net.castimage.fms_backend.Model.vo.TrialBalanceRow;
import net.castimage.fms_backend.Model.vo.VoucherLineView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

/** 凭证分录 Mapper（gl.voucher_lines）：cost_amount 为列级敏感列（查询列排除）；单表更新经 BaseMapper + Wrapper，JOIN/聚合查询手写 */
public interface VoucherLineMapper extends BaseMapper<VoucherLine> {

    /** 凭证分录列表（LEFT JOIN 联查科目 code/name，按 line_no 排序；Wrapper 无 JOIN 能力）。
     * 列序与列别名必须与 VoucherLineView record 组件序一致（camelCase 别名），否则 MyBatis 按位映射会把 char(3) currency 'CNY' 读到 BigDecimal debit，报 22003 映射 500 */
    @Select("""
            SELECT vl.id AS id,
                   vl.voucher_id AS voucherId,
                   vl.line_no AS lineNo,
                   vl.account_id AS accountId,
                   a.code AS accountCode,
                   a.name AS accountName,
                   vl.summary AS summary,
                   vl.debit AS debit,
                   vl.credit AS credit,
                   vl.currency AS currency,
                   vl.dept_id AS deptId,
                   vl.partner_id AS partnerId,
                   vl.company_id AS companyId
              FROM gl.voucher_lines vl
              LEFT JOIN gl.chart_accounts a ON a.id = vl.account_id AND a.deleted_at IS NULL
             WHERE vl.voucher_id = #{voucherId}::uuid AND vl.deleted_at IS NULL
             ORDER BY vl.line_no
            """)
    List<VoucherLineView> selectByVoucherId(@Param("voucherId") UUID voucherId);

    /** 借贷合计（COALESCE(SUM)::numeric(18,2)，提交/更新预检用；BaseMapper 无原生聚合能力） */
    @Select("""
            SELECT COALESCE(SUM(debit), 0)::numeric(18,2) AS debit,
                   COALESCE(SUM(credit), 0)::numeric(18,2) AS credit
              FROM gl.voucher_lines
             WHERE voucher_id = #{voucherId}::uuid AND deleted_at IS NULL
            """)
    Sum selectSum(@Param("voucherId") UUID voucherId); // 单查询取借贷合计，

    /** 借贷合计行（列别名与组件名一致：debit/credit） */
    record Sum(BigDecimal debit, BigDecimal credit) { // 列别名自动映射，
    }

    /** 总账：仅统计 POSTED/REVERSED（REVERSED 与反向 POSTED 金额相互抵消）；期初=期间前累计净额，期末=截至本期累计净额；按 path ltree 序，无发生额科目亦列出 */
    @Select("""
            SELECT a.id AS account_id, a.code AS account_code, a.name AS account_name, a.direction,
                   COALESCE(SUM(CASE WHEN v.period::text < #{period} THEN vl.debit - vl.credit ELSE 0 END), 0)::numeric(18,2) AS opening_balance,
                   COALESCE(SUM(CASE WHEN v.period::text = #{period} THEN vl.debit ELSE 0 END), 0)::numeric(18,2) AS period_debit,
                   COALESCE(SUM(CASE WHEN v.period::text = #{period} THEN vl.credit ELSE 0 END), 0)::numeric(18,2) AS period_credit,
                   COALESCE(SUM(CASE WHEN v.period::text <= #{period} THEN vl.debit - vl.credit ELSE 0 END), 0)::numeric(18,2) AS closing_balance
              FROM gl.chart_accounts a
              LEFT JOIN gl.voucher_lines vl ON vl.account_id = a.id AND vl.deleted_at IS NULL
              LEFT JOIN gl.vouchers v ON v.id = vl.voucher_id AND v.deleted_at IS NULL
                   AND v.status IN ('POSTED','REVERSED')
             WHERE a.deleted_at IS NULL
               AND a.company_id = #{companyId}::uuid
               AND (#{accountId}::uuid IS NULL OR a.id = #{accountId}::uuid)
             GROUP BY a.id, a.code, a.name, a.direction
             ORDER BY a.path
            """)
    List<LedgerView> selectLedger(@Param("companyId") UUID companyId,
                                  @Param("period") String period,
                                  @Param("accountId") UUID accountId);

    /** 试算平衡（双 LEFT JOIN + CASE WHEN 条件 SUM + GROUP BY，按 path ltree 序） */
    @Select("""
            SELECT a.id AS account_id, a.code AS account_code, a.name AS account_name, a.direction,
                   COALESCE(SUM(CASE WHEN v.period::text < #{period} THEN vl.debit - vl.credit ELSE 0 END), 0)::numeric(18,2) AS opening_balance,
                   COALESCE(SUM(CASE WHEN v.period::text = #{period} THEN vl.debit ELSE 0 END), 0)::numeric(18,2) AS period_debit,
                   COALESCE(SUM(CASE WHEN v.period::text = #{period} THEN vl.credit ELSE 0 END), 0)::numeric(18,2) AS period_credit,
                   COALESCE(SUM(CASE WHEN v.period::text <= #{period} THEN vl.debit - vl.credit ELSE 0 END), 0)::numeric(18,2) AS closing_balance
              FROM gl.chart_accounts a
              LEFT JOIN gl.voucher_lines vl ON vl.account_id = a.id AND vl.deleted_at IS NULL
              LEFT JOIN gl.vouchers v ON v.id = vl.voucher_id AND v.deleted_at IS NULL
                   AND v.status IN ('POSTED','REVERSED')
             WHERE a.deleted_at IS NULL
               AND a.company_id = #{companyId}::uuid
             GROUP BY a.id, a.code, a.name, a.direction
             ORDER BY a.path
            """)
    List<TrialBalanceRow> selectTrialBalance(@Param("companyId") UUID companyId,
                                             @Param("period") String period);
}
