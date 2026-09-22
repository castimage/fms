package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 报销单冲销请求（POST /api/exp/reimbursements/{id}/reverse）：冲销原因必填，供审计追溯。
 *
 * 冲销是已过账单据的受控出口，前置为账先冲、单后标——本单据的凭证须已在总账完成冲销，
 * 否则拒绝并提示先冲凭证；已冲销为终态，不可重复冲销。
 */
public record ReverseReimbursementRequest(
        /** 冲销原因（必填，最长 500 字符） */
        @NotBlank(message = "冲销原因必填")
        @Size(max = 500, message = "冲销原因最长 500 字符")
        String reason) {
}
