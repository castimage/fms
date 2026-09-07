package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 令牌刷新请求（匿名端点 POST /api/auth/refresh）。
 */
public record RefreshRequest(
        /** refresh token（后端经 auth.jwt_verify 验签并断言 type=refresh） */
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
