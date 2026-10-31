package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.BudgetPlan;
import net.castimage.fms_backend.Model.vo.BudgetExecutionRow;
import net.castimage.fms_backend.Model.vo.BudgetPlanView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 预算计划 Mapper（budget.budget_plans）：状态机经 DRAFT 提交 SUBMITTED 再 APPROVED（批准后 RLS 只读）；分页/最新版本/执行对比手写 */
public interface BudgetPlanMapper extends BaseMapper<BudgetPlan> {

    /** 预算计划分页列表（join 科目编码/名称 + 部门名；RLS 已按公司/部门/本人收敛；排序白名单固定 period DESC, version DESC） */
    @Select("""
            SELECT p.id, p.company_id, p.period, p.account_id, a.code AS account_code,
                   a.name AS account_name, p.dept_id, d.name AS dept_name,
                   p.amount, p.version, p.status, p.approved_by, p.approved_at,
                   p.created_by, p.created_at
              FROM budget.budget_plans p
              JOIN gl.chart_accounts a ON a.id = p.account_id AND a.deleted_at IS NULL
              JOIN org.departments d ON d.id = p.dept_id AND d.deleted_at IS NULL
             WHERE p.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR p.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR p.period = #{period, jdbcType=VARCHAR})
               AND (#{deptId}::uuid IS NULL OR p.dept_id = #{deptId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR p.status = #{status, jdbcType=VARCHAR})
             ORDER BY p.period DESC, p.version DESC, p.id DESC
            """)
    IPage<BudgetPlanView> selectPlanPage(Page<BudgetPlanView> page,
                                         @Param("companyId") UUID companyId,
                                         @Param("period") String period,
                                         @Param("deptId") UUID deptId,
                                         @Param("status") String status);

    /** 计划详情（join 科目/部门名；RLS 空集返回 null 则 data:null，不泄露存在性） */
    @Select("""
            SELECT p.id, p.company_id, p.period, p.account_id, a.code AS account_code,
                   a.name AS account_name, p.dept_id, d.name AS dept_name,
                   p.amount, p.version, p.status, p.approved_by, p.approved_at,
                   p.created_by, p.created_at
              FROM budget.budget_plans p
              JOIN gl.chart_accounts a ON a.id = p.account_id AND a.deleted_at IS NULL
              JOIN org.departments d ON d.id = p.dept_id AND d.deleted_at IS NULL
             WHERE p.id = #{id} AND p.deleted_at IS NULL
            """)
    BudgetPlanView selectPlanDetail(@Param("id") UUID id);

    /** 计划最新版本（APPROVED 优先，version DESC；超支阻断与执行对比同口径取数） */
    @Select("""
            SELECT * FROM budget.budget_plans
             WHERE company_id = #{companyId}::uuid
               AND period = #{period}
               AND account_id = #{accountId}::uuid
               AND dept_id IS NOT DISTINCT FROM #{deptId}::uuid
               AND deleted_at IS NULL
             ORDER BY (status = 'APPROVED') DESC, version DESC
             LIMIT 1
            """)
    BudgetPlan selectLatestVersion(@Param("companyId") UUID companyId,
                                   @Param("period") String period,
                                   @Param("accountId") UUID accountId,
                                   @Param("deptId") UUID deptId);

    /**
     * 预算执行对比（B 类核心）：按 (company, period, account, dept) 取计划最新版本
     * （DISTINCT ON + APPROVED 优先 + version DESC，与超支阻断函数同口径），LEFT JOIN
     * rpt.budget_actual IMMV（company×period×account×dept 直接对键，dept 用
     * IS NOT DISTINCT FROM 兼容 NULL）+ 已批调整净额（SUM(CASE WHEN status='APPROVED')）；
     * available_amount = plan + adjusted - actual；ratio/overLimit/level 由 Service 计算。
     */
    @Select("""
            SELECT DISTINCT ON (p.company_id, p.period, p.account_id, p.dept_id)
                   p.id AS plan_id, p.company_id, p.period, p.account_id,
                   a.code AS account_code, a.name AS account_name,
                   p.dept_id, d.name AS dept_name,
                   p.amount AS plan_amount,
                   COALESCE(b.actual_amount, 0) AS actual_amount,
                   COALESCE(adj.adj_amount, 0) AS adjusted_amount,
                   p.amount + COALESCE(adj.adj_amount, 0)
                       - COALESCE(b.actual_amount, 0) AS available_amount,
                   0::numeric AS ratio, false AS over_limit, 'NORMAL' AS level
              FROM budget.budget_plans p
              JOIN gl.chart_accounts a ON a.id = p.account_id AND a.deleted_at IS NULL
              JOIN org.departments d ON d.id = p.dept_id AND d.deleted_at IS NULL
              LEFT JOIN rpt.v_budget_actual b
                     ON b.company_id = p.company_id AND b.period = p.period
                    AND b.account_id = p.account_id
                    AND b.dept_id IS NOT DISTINCT FROM p.dept_id
              LEFT JOIN (SELECT plan_id,
                                SUM(CASE WHEN status = 'APPROVED' THEN amount ELSE 0 END) AS adj_amount
                           FROM budget.budget_adjustments
                          WHERE deleted_at IS NULL
                          GROUP BY plan_id) adj ON adj.plan_id = p.id
             WHERE p.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR p.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR p.period = #{period, jdbcType=VARCHAR})
               AND (#{deptId}::uuid IS NULL OR p.dept_id = #{deptId}::uuid)
             ORDER BY p.company_id, p.period, p.account_id, p.dept_id,
                      (p.status = 'APPROVED') DESC, p.version DESC
            """)
    List<BudgetExecutionRow> selectExecutionRows(@Param("companyId") UUID companyId,
                                                 @Param("period") String period,
                                                 @Param("deptId") UUID deptId);
}
