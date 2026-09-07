package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Reimbursement;
import net.castimage.fms_backend.Model.vo.ReimbursementView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 报销单 Mapper（expense.reimbursements）：状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、付款 PAID、过账 POSTED 触发器终审；分页/本人列表/详情手写 */
public interface ReimbursementMapper extends BaseMapper<Reimbursement> {

    /** 报销单分页列表（join 申请人/部门名；RLS 已按本人/本部门/终态收敛，companyId 仅过滤提示；排序白名单固定 created_at DESC） */
    @Select("""
            SELECT r.id, r.company_id, r.applicant_id, u.login_name AS applicant_name,
                   r.dept_id, d.name AS dept_name, r.title, r.amount, r.currency,
                   r.status, r.wf_instance_id, r.voucher_id, r.created_at, r.updated_at
              FROM expense.reimbursements r
              LEFT JOIN um.users u ON u.id = r.applicant_id
              LEFT JOIN org.departments d ON d.id = r.dept_id
             WHERE r.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR r.company_id = #{companyId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR r.status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR r.title ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY r.created_at DESC, r.id DESC
            """)
    IPage<ReimbursementView> selectReimbursementPage(Page<ReimbursementView> page,
                                                     @Param("companyId") UUID companyId,
                                                     @Param("status") String status,
                                                     @Param("keyword") String keyword);

    /** 本人报销分页（applicant_id = 当前用户；RLS is_self 双路径兜底，SQL 显式过滤保证口径） */
    @Select("""
            SELECT r.id, r.company_id, r.applicant_id, u.login_name AS applicant_name,
                   r.dept_id, d.name AS dept_name, r.title, r.amount, r.currency,
                   r.status, r.wf_instance_id, r.voucher_id, r.created_at, r.updated_at
              FROM expense.reimbursements r
              LEFT JOIN um.users u ON u.id = r.applicant_id
              LEFT JOIN org.departments d ON d.id = r.dept_id
             WHERE r.applicant_id = #{applicantId}::uuid
               AND r.deleted_at IS NULL
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR r.status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR r.title ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY r.created_at DESC, r.id DESC
            """)
    IPage<ReimbursementView> selectMyPage(Page<ReimbursementView> page,
                                          @Param("applicantId") UUID applicantId,
                                          @Param("status") String status,
                                          @Param("keyword") String keyword);

    /** 报销单详情（join 申请人/部门名；RLS 空集返回 null 则 data:null，不泄露存在性） */
    @Select("""
            SELECT r.id, r.company_id, r.applicant_id, u.login_name AS applicant_name,
                   r.dept_id, d.name AS dept_name, r.title, r.amount, r.currency,
                   r.status, r.wf_instance_id, r.voucher_id, r.created_at, r.updated_at
              FROM expense.reimbursements r
              LEFT JOIN um.users u ON u.id = r.applicant_id
              LEFT JOIN org.departments d ON d.id = r.dept_id
             WHERE r.id = #{id} AND r.deleted_at IS NULL
            """)
    ReimbursementView selectDetail(@Param("id") UUID id);
}
