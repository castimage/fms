package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统参数更新请求（PUT /api/admin/params/{code}）：value 为 JSONB 文本（JSON 标量或对象，
 * AdminService 侧 fastjson2 校验合法性后经 ::jsonb 落库）。
 */
public record ParamUpdateRequest(
        @NotBlank(message = "参数值必填")
        @Size(max = 65536, message = "参数值最长 65536 字符")
        String value) { // 参数值须为合法 JSON，
}
