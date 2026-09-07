package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.WfDefinition;
import net.castimage.fms_backend.Model.vo.WfDefinitionView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 流程定义 Mapper（wf.definitions）：状态机经 DRAFT 启用 ACTIVE、可停用 DISABLED；分页手写（单表无 join） */
public interface WfDefinitionMapper extends BaseMapper<WfDefinition> {

    /** 流程定义分页列表（companyId/status/keyword 可选过滤；排序白名单固定 created_at DESC） */
    @Select("""
            SELECT id, company_id, code, name, version, form_type, node_json, status,
                   created_at, updated_at
              FROM wf.definitions
             WHERE deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR company_id = #{companyId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR code ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'
                    OR name ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY created_at DESC, id DESC
            """)
    IPage<WfDefinitionView> selectDefinitionPage(Page<WfDefinitionView> page,
                                                 @Param("companyId") UUID companyId,
                                                 @Param("status") String status,
                                                 @Param("keyword") String keyword);

    /** 回草稿调 wf.fn_revise_definition，SECURITY DEFINER 豁免 RLS 计数 RUNNING，false = 非 DISABLED 或在途 */
    @Select("SELECT wf.fn_revise_definition(#{id}::uuid)")
    boolean reviseDefinition(@Param("id") UUID id);

    /** 全字段新鲜查询，独立 statement 绕一级缓存，fn 改状态后取最新值 */
    @Select("SELECT id, company_id, code, name, version, form_type, node_json, status, created_at, updated_at FROM wf.definitions WHERE id = #{id} AND deleted_at IS NULL")
    WfDefinition selectByIdFresh(@Param("id") UUID id);
}
