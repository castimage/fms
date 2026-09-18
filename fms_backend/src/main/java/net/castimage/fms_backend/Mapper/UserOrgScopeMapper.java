package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserOrgScope;

/**
 * 用户-公司数据范围 Mapper，基类方法直查 um.user_org_scope，当前用于解析当前用户可见的
 * 公司集合，管理员的数据范围读写不经此，走 auth.list_user_scopes 与 auth.set_user_org_scope 闸口
 */
public interface UserOrgScopeMapper extends BaseMapper<UserOrgScope> {
}
