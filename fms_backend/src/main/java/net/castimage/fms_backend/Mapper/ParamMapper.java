package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.vo.ParamFieldView;
import net.castimage.fms_backend.Model.vo.ParamMetaView;
import net.castimage.fms_backend.Model.vo.ParamView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 系统参数 Mapper（um.params）：职责角色无 um.params 表级 SELECT/UPDATE/INSERT（um_org_base
 * 最小授权：密钥收敛 sec.app_secrets，参数仅经 SECURITY DEFINER 函数以 fms_admin 身份读写），
 * 故读分页/单查/写 upsert 全部走 um.fn_* 函数（函数内 auth.require_permission 闸口：
 * admin.param.view/admin.param.update，operator 等角色 40300 按设计拒绝）。
 */
public interface ParamMapper extends BaseMapper<net.castimage.fms_backend.Model.entity.Param> { // 全限定名避让 @Param 注解简名冲突，

    /**
     * 参数分页（code 模糊过滤，排序白名单 code；value JSONB 经 ::text 映射字符串）。
     * 经 um.fn_page_params SECURITY DEFINER 函数读取（admin.param.view 闸口）。
     */
    @Select("""
            SELECT id, code, value::text AS value, updated_by, created_at, updated_at
              FROM um.fn_page_params(#{keyword, jdbcType=VARCHAR})
            """)
    IPage<ParamView> selectParamsPage(Page<ParamView> page, @Param("keyword") String keyword);

    /**
     * 单参数查询（按 code，未删过滤；不存在返回 NULL 由服务层 40400）。经 um.fn_get_param 读取。
     */
    @Select("""
            SELECT id, code, value::text AS value, updated_by, created_at, updated_at
              FROM um.fn_get_param(#{code})
            """)
    ParamView selectParamByCode(@Param("code") String code);

    /**
     * 参数 upsert（插入或原子更新 value/updated_by/updated_at），经 um.fn_upsert_param
     * SECURITY DEFINER 函数写入（admin.param.update 闸口 + 敏感键白名单双保险）；返回行 id。
     */
    @Select("SELECT um.fn_upsert_param(#{code}, #{value}::jsonb, #{updatedBy}::uuid)")
    UUID upsertParam(@Param("code") String code, @Param("value") String value,
                     @Param("updatedBy") UUID updatedBy);

    /**
     * 参数元数据全量（管理页分组表单化渲染）：经 um.fn_page_param_meta SECURITY DEFINER
     * 函数读取（um.param_meta 表零职责角色授权，同 um.params 最小授权原则）。
     */
    @Select("""
            SELECT code, grp, label, control_type AS control_type, unit,
                   min_val, max_val, step, options::text AS options, hint, sort_no, sensitive,
                   NULL AS fields  -- 占位列对齐 13 参构造器（fields 由服务层合并子字段元数据填充）
              FROM um.fn_page_param_meta()
            """)
    List<ParamMetaView> selectParamMeta();

    /**
     * 对象参数子字段元数据全量（{"v":{...}} 形态参数按字段渲染子表单）：
     * 经 um.fn_page_param_meta_field SECURITY DEFINER 函数读取（同 param_meta 最小授权）。
     */
    @Select("""
            SELECT code, field, label, control_type AS control_type, unit,
                   min_val, max_val, step, options::text AS options, hint, sort_no
              FROM um.fn_page_param_meta_field()
            """)
    List<ParamFieldView> selectParamMetaField();

    /**
     * 公共参数值读取（配置下发 /api/config refdataPageSize 支撑）：经 um.fn_get_param_value
     * SECURITY DEFINER 函数读取，无需 admin.param.view（仅读取非敏感业务参数，敏感键
     * 函数内过滤返回缺省）；参数缺失/异常同样返回 defaultValue，调用方不崩溃。
     */
    @Select("SELECT um.fn_get_param_value(#{code}, #{defaultValue}::jsonb)::text")
    String selectPublicParamValue(@Param("code") String code,
                                  @Param("defaultValue") String defaultValue);
}
