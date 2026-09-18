package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 用户数据范围配置请求，经 auth.set_user_org_scope 与 auth.set_user_dept_scope 两个闸口写入，
 * 全量替换语义，列表为空表示清空该维度范围。
 */
public record UserScopeRequest(
        /** 可见公司 id 集合，可跨公司授予，空列表表示清空 */
        @NotNull(message = "orgIds 不能为空，清空请传空数组")
        List<UUID> orgIds,
        /** 可见部门 id 集合，空列表表示清空 */
        @NotNull(message = "deptIds 不能为空，清空请传空数组")
        List<UUID> deptIds,
        /** 部门范围是否含子树，施加于全部所选部门，缺省按 true，与表列默认一致 */
        Boolean includeSubtree) { // 缺省 true
}
