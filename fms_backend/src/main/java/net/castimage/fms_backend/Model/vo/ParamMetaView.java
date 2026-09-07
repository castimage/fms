package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 系统参数元数据视图（GET /api/admin/params/meta；经 um.fn_page_param_meta SECURITY DEFINER
 * 读口，管理页分组表单化渲染的权威源）：前端按 controlType 渲染对应控件，
 * options JSONB 经 ::text 转字符串映射（同 ParamView.value 模式）。
 * fields 为对象参数（{"v":{...}} 形态）的子字段元数据（非空时前端渲染子表单）。
 */
public record ParamMetaView(String code, String grp, String label, String controlType,
                            String unit, BigDecimal minVal, BigDecimal maxVal, BigDecimal step,
                            String options, String hint, Integer sortNo, Boolean sensitive,
                            List<ParamFieldView> fields) {
}

