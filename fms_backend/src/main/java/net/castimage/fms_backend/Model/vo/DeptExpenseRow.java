package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 部门费用行（GET /api/rpt/dept-expense；MyBatis 结果映射）：数据源 rpt.dept_expense IMMV
 * （pg_ivm automatic 自动维护，提交即一致）——费用凭证分录按（公司×期间×部门×科目）SUM(debit-credit)；
 * LEFT JOIN org.departments / gl.chart_accounts 联查名称（B 类手写，Wrapper 无 JOIN 能力）。
 */
public record DeptExpenseRow(String period, UUID deptId, String deptName,
                             UUID accountId, String accountCode, String accountName,
                             BigDecimal amount) { // 部门费用仪表板数据源，
}
