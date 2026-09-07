package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Size;

/**
 * 任务动作请求：approve/reject 时 remark 为审批意见/驳回原因；transfer（转办）时 remark
 * 传新处理人 uuid 文本即转办（PG 函数内 CAST 失败则仅记事件不转办）。
 */
public record TaskActionRequest(
        /** 备注（驳回原因 / 转办目标处理人 uuid 文本） */
        @Size(max = 500, message = "备注最长 500 字符")
        String remark) {
}
