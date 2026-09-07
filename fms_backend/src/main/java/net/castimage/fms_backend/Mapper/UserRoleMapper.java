package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserRole;

/** 用户-角色关联 Mapper（um.user_role）：写入经 auth.assign_role / auth.revoke_role 闸口，本 Mapper 承载查询（is_self OR fms_admin）；当前无 Service 引用，供后续直查场景启用 */
public interface UserRoleMapper extends BaseMapper<UserRole> {
    // 写入经 assign_role/revoke_role 闸口，本 Mapper 只查，
    // 预留：后续批次直查场景启用，
}
