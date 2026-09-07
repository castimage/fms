package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;

/**
 * 对象参数子字段元数据视图（um.param_meta_field；经 um.fn_page_param_meta_field 读口）：
 * {"v":{...}} 形态参数按字段拆分渲染子表单（如 ui.login.background 的 enabled/fit/blur 等），
 * options JSONB 经 ::text 转字符串映射。
 */
public record ParamFieldView(String code, String field, String label, String controlType,
                             String unit, BigDecimal minVal, BigDecimal maxVal, BigDecimal step,
                             String options, String hint, Integer sortNo) {
}
