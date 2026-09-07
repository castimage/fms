package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Role;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** 角色 Mapper（um.roles）：全内部角色可读，写经 auth.upsert_role 闸口；单表查询经 BaseMapper + Wrapper，selectRolesByUserId 联查手写 */
public interface RoleMapper extends BaseMapper<Role> {
    // 写操作全走 upsert_role 闸口，

    /** 用户角色列表（um.roles JOIN um.user_role 联查，按 code 排序；JWT role 解析与用户详情用） */
    @Select("""
            SELECT r.id, r.company_id, r.code, r.name, r.is_system, r.created_at, r.updated_at, r.deleted_at
              FROM um.roles r
              JOIN um.user_role ur ON ur.role_id = r.id AND ur.deleted_at IS NULL
            WHERE ur.user_id = #{userId}::uuid AND r.deleted_at IS NULL
              ORDER BY r.code
  """)
    List<Role> selectRolesByUserId(@Param("userId") UUID userId); // 角色集合按 code 排序，
}
