package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Size;

/**
 * 期间开关动作请求（POST /api/gl/periods/{companyId}/{period}/close|reopen）：
 * reason 为反结账原因（必填，审计留痕，fn_reopen_period 拒绝空值）；结账端点无需原因，
 * reason 仅作扩展占位（函数不消费）。Service 层对空白原因二次兜底（btrim 非空）。
 */
public record PeriodActionRequest(
        /** 反结账原因（最长 500 字符；结账端点忽略） */
        @Size(max = 500, message = "原因最长 500 字符")
        String reason) {
}
