package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 部门创建/更新请求：code 仅字母/数字/下划线（ltree label 约束）；更新时 code/path/parentId/level 不可变更（结构字段），仅 name/managerId 生效。
 */
public record DepartmentRequest(
        /** 所属公司 */
        @NotNull(message = "companyId 不能为空")
        UUID companyId,
        /** 部门编码（公司内唯一；ltree 标签：字母/数字/下划线） */
        @NotBlank(message = "部门编码不能为空")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "部门编码仅允许字母/数字/下划线")
        @Size(max = 32, message = "部门编码最长 32 字符")
        String code,
        /** 部门名称 */
        @NotBlank(message = "部门名称不能为空")
        @Size(max = 128, message = "部门名称最长 128 字符")
        String name,
        UUID parentId, // 根部门为 null，
        /** 部门负责人（um.users.id，可空） */
        UUID managerId) {
}
