package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 双因子重置请求（管理员吊销他人双因子，经 auth.admin_reset_mfa，只吊销不接管，原因必填并留审计）。
 */
public record ResetMfaRequest(
        /** 重置原因，去空白后为空由库内闸口拒绝，落审计 */
        @NotBlank(message = "重置双因子原因不能为空")
        @Size(max = 200, message = "重置原因长度不能超过 200 字符")
        String reason) {
}
