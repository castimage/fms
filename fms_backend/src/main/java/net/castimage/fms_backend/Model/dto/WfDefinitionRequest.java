package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 流程定义创建/更新请求：code 公司内唯一（uk_wf_def）且创建后不可变更；node_json 为 JSON
 * 数组文本（[{node_code, type, assignee_role}]，Java 仅校验 JSON 合法性，激活前须为非空数组）；
 * 状态经 DRAFT 启用 ACTIVE、可停用 DISABLED，由 activate/disable 端点推进。
 */
public record WfDefinitionRequest(
        /** 所属公司（必填，须在当前用户可见范围内） */
        @NotNull(message = "公司必填")
        UUID companyId,
        /** 流程编码（公司内唯一，如 REIMBURSEMENT） */
        @NotBlank(message = "流程编码必填")
        String code,
        /** 流程名称 */
        @NotBlank(message = "流程名称必填")
        String name,
        /** 关联表单类型（可空） */
        String formType,
        /** 节点编排 JSONB 文本（须为合法 JSON，激活前须为非空数组） */
        @NotBlank(message = "节点编排必填")
        String nodeJson) {
}
