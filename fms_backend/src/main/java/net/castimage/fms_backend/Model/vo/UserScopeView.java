package net.castimage.fms_backend.Model.vo;

import java.util.List;
import java.util.UUID;

/**
 * 用户数据范围视图，经 auth.list_user_scopes 读取，供配置界面回显。
 * 部门范围含子树开关，本接口对全部部门统一设置，故回显取同一取值。
 */
public record UserScopeView(List<UUID> orgIds, List<UUID> deptIds, Boolean includeSubtree) { // 当前生效范围
}
