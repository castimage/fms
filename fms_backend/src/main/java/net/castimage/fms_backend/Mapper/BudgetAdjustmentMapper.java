package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.BudgetAdjustment;
import net.castimage.fms_backend.Model.vo.BudgetAdjustmentView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 预算调整单 Mapper（budget.budget_adjustments）：状态机经 DRAFT 转 APPROVED/REJECTED；按计划查询/分页手写 */
public interface BudgetAdjustmentMapper extends BaseMapper<BudgetAdjustment> {

    /** 计划下调整单列表（join 父计划带出期间；RLS 经父计划公司范围收敛；时间倒序） */
    @Select("""
            SELECT ad.id, ad.company_id, ad.plan_id, p.period,
                   ad.amount, ad.reason, ad.status, ad.adjusted_by, ad.adjusted_at,
                   ad.created_at
              FROM budget.budget_adjustments ad
              JOIN budget.budget_plans p ON p.id = ad.plan_id AND p.deleted_at IS NULL
             WHERE ad.plan_id = #{planId} AND ad.deleted_at IS NULL
             ORDER BY ad.created_at DESC, ad.id DESC
            """)
    List<BudgetAdjustmentView> selectByPlanId(@Param("planId") UUID planId);

    /** 调整单详情（join 父计划带出期间；RLS 空集返回 null 则 data:null） */
    @Select("""
            SELECT ad.id, ad.company_id, ad.plan_id, p.period,
                   ad.amount, ad.reason, ad.status, ad.adjusted_by, ad.adjusted_at,
                   ad.created_at
              FROM budget.budget_adjustments ad
              JOIN budget.budget_plans p ON p.id = ad.plan_id AND p.deleted_at IS NULL
             WHERE ad.id = #{id} AND ad.deleted_at IS NULL
            """)
    BudgetAdjustmentView selectAdjustmentDetail(@Param("id") UUID id);

    /** 调整单分页列表（companyId/planId/status 可选过滤；join 父计划带出期间；排序白名单固定 created_at DESC） */
    @Select("""
            SELECT ad.id, ad.company_id, ad.plan_id, p.period,
                   ad.amount, ad.reason, ad.status, ad.adjusted_by, ad.adjusted_at,
                   ad.created_at
              FROM budget.budget_adjustments ad
              JOIN budget.budget_plans p ON p.id = ad.plan_id AND p.deleted_at IS NULL
             WHERE ad.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR ad.company_id = #{companyId}::uuid)
               AND (#{planId}::uuid IS NULL OR ad.plan_id = #{planId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR ad.status = #{status, jdbcType=VARCHAR})
             ORDER BY ad.created_at DESC, ad.id DESC
            """)
    IPage<BudgetAdjustmentView> selectAdjustmentPage(Page<BudgetAdjustmentView> page,
                                                     @Param("companyId") UUID companyId,
                                                     @Param("planId") UUID planId,
                                                     @Param("status") String status);
}
