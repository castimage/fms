package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Permission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 权限 Mapper（um.permissions）：全量可读（current_uid() IS NOT NULL），user_role/role_perm 联查按注入上下文收敛（is_self OR fms_admin）；两处手写——三表 JOIN 与 role_perm 计数 */
public interface PermissionMapper extends BaseMapper<Permission> {

    /** 用户权限编码集合（user_role、role_perm、permissions 三表 JOIN + DISTINCT，按 code 排序；Wrapper 无跨表 JOIN 能力） */
    @Select("""
            SELECT DISTINCT p.code
              FROM um.permissions p
              JOIN um.role_perm rp ON rp.perm_id = p.id AND rp.deleted_at IS NULL
              JOIN um.user_role ur ON ur.role_id = rp.role_id AND ur.deleted_at IS NULL
             WHERE ur.user_id = #{userId}::uuid AND p.deleted_at IS NULL
             ORDER BY p.code
            """)
    List<String> selectCodesByUser(@Param("userId") UUID userId); // 权限集合按注入上下文收敛，

    /** 角色的权限绑定数（RoleView.permissionCount；um.role_perm 无实体承载，只能手写单表 count） */
    @Select("SELECT count(*) FROM um.role_perm WHERE role_id = #{roleId}::uuid AND deleted_at IS NULL")
    long countByRoleId(@Param("roleId") UUID roleId); // 计数仅此入口，

    /** 角色已绑定的权限编码集合（role_perm 与 permissions 两表 JOIN，供授权界面回显与差量解绑） */
    @Select("""
            SELECT DISTINCT p.code
              FROM um.permissions p
              JOIN um.role_perm rp ON rp.perm_id = p.id AND rp.deleted_at IS NULL
             WHERE rp.role_id = #{roleId}::uuid AND p.deleted_at IS NULL
             ORDER BY p.code
            """)
    List<String> selectCodesByRole(@Param("roleId") UUID roleId); // 权限集合按注入上下文收敛，
}
