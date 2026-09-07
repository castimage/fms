package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * SoD 冲突检查请求（仅告警不阻断）。
 */
public record SodCheckRequest(
        /** 目标用户 id */
        @NotNull(message = "userId 不能为空")
        UUID userId) { // 仅告警不阻断，
}
