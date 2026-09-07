package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Size;

/**
 * 收款冲销请求：冲销为软性动作（置 REVERSED + 清核销关联），reason 记录冲销原因
 * 供审计追溯（可空）。
 */
public record ReverseReceiptRequest(
        /** 冲销原因 */
        @Size(max = 256, message = "冲销原因最长 256 字符")
        String reason) {
}
