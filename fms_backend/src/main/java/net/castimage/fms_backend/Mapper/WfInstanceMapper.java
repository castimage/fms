package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.WfInstance;
import net.castimage.fms_backend.Model.vo.WfInstanceView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 流程实例 Mapper（wf.instances）：RLS SELECT 限定本人发起或终态；分页/详情/本人列表手写 */
public interface WfInstanceMapper extends BaseMapper<WfInstance> {

    /** 实例分页列表（join 定义编码/名称 + 发起人登录名；companyId/status/businessType 可选过滤；排序白名单固定 created_at DESC） */
    @Select("""
            SELECT i.id, i.company_id, i.definition_id, d.code AS definition_code,
                   d.name AS definition_name, i.business_type, i.business_id,
                   i.initiator_id, u.login_name AS initiator_name,
                   i.current_node, i.status, i.created_at
              FROM wf.instances i
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = i.initiator_id
             WHERE i.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR i.company_id = #{companyId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR i.status = #{status, jdbcType=VARCHAR})
               AND (#{businessType, jdbcType=VARCHAR} IS NULL OR #{businessType, jdbcType=VARCHAR} = ''
                    OR i.business_type = #{businessType, jdbcType=VARCHAR})
             ORDER BY i.created_at DESC, i.id DESC
            """)
    IPage<WfInstanceView> selectInstancePage(Page<WfInstanceView> page,
                                             @Param("companyId") UUID companyId,
                                             @Param("status") String status,
                                             @Param("businessType") String businessType);

    /** 实例详情（join 名称；RLS 空集返回 null 则 data:null，不泄露他人 RUNNING 实例存在性） */
    @Select("""
            SELECT i.id, i.company_id, i.definition_id, d.code AS definition_code,
                   d.name AS definition_name, i.business_type, i.business_id,
                   i.initiator_id, u.login_name AS initiator_name,
                   i.current_node, i.status, i.created_at
              FROM wf.instances i
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = i.initiator_id
             WHERE i.id = #{id} AND i.deleted_at IS NULL
            """)
    WfInstanceView selectInstanceDetail(@Param("id") UUID id);

    /** 本人发起实例分页（initiator_id = 当前用户；RLS is_self 双路径兜底） */
    @Select("""
            SELECT i.id, i.company_id, i.definition_id, d.code AS definition_code,
                   d.name AS definition_name, i.business_type, i.business_id,
                   i.initiator_id, u.login_name AS initiator_name,
                   i.current_node, i.status, i.created_at
              FROM wf.instances i
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
              LEFT JOIN um.users u ON u.id = i.initiator_id
             WHERE i.initiator_id = #{initiatorId}::uuid
               AND i.deleted_at IS NULL
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR i.status = #{status, jdbcType=VARCHAR})
             ORDER BY i.created_at DESC, i.id DESC
            """)
    IPage<WfInstanceView> selectMyPage(Page<WfInstanceView> page,
                                       @Param("initiatorId") UUID initiatorId,
                                       @Param("status") String status);

    /** 某业务单据是否已有未删除实例（发起幂等预检；uk_wf_inst_business 唯一索引兜底并发） */
    @Select("""
            SELECT id FROM wf.instances
             WHERE business_type = #{businessType}
               AND business_id = #{businessId}::uuid
               AND deleted_at IS NULL
             LIMIT 1
            """)
    UUID selectByBusiness(@Param("businessType") String businessType,
                          @Param("businessId") UUID businessId);

    /**
     * 发起流程实例：调 wf.fn_start_instance（SECURITY DEFINER，规避 um.users/user_role
     * RLS 空集）——原子创建 RUNNING 实例 + 首个审批节点 PENDING 任务（处理人按
     * assignee_role 解析：applicant=发起人，先角色 code 匹配，再公司内兜底 ACTIVE 用户）。
     * 返回实例 id。
     */
    @Select("""
            SELECT wf.fn_start_instance(#{companyId}::uuid, #{definitionId}::uuid,
                                        #{businessType}, #{businessId}::uuid,
                                        #{initiatorId}::uuid, #{nodeCode}, #{assigneeRole})
            """)
    UUID startInstance(@Param("companyId") UUID companyId,
                       @Param("definitionId") UUID definitionId,
                       @Param("businessType") String businessType,
                       @Param("businessId") UUID businessId,
                       @Param("initiatorId") UUID initiatorId,
                       @Param("nodeCode") String nodeCode,
                       @Param("assigneeRole") String assigneeRole);
}
