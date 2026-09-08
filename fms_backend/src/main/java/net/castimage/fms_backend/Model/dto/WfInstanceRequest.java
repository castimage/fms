package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 流程实例发起请求（POST /api/wf/instances）：定义（须 ACTIVE 且当前用户可见）+
 * 业务类型 + 业务单号。创建 RUNNING 实例 + 首个审批节点（跳过 START/END）任务集，
 * 处理人按节点 strategy 与 handlers 展开，单审取首个可解析人，会签与或签各展开一票，
 * 由 PG 函数 wf.fn_start_instance（SECURITY DEFINER）原子完成，规避 um.users/user_role
 * RLS 空集。
 */
public record WfInstanceRequest(
        /** 流程定义（须 ACTIVE；RLS 不可见则 40400） */
        @NotNull(message = "流程定义必填")
        UUID definitionId,
        /** 业务类型（REIMBURSEMENT 等） */
        @NotBlank(message = "业务类型必填")
        String businessType,
        /** 业务单据主键 */
        @NotNull(message = "业务单号必填")
        UUID businessId) {
}
