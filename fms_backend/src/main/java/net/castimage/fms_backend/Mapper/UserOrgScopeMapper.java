package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserOrgScope;

/**
 * 用户-公司数据范围 Mapper（um.user_org_scope），基类方法直查本表，当前调用点为
 * FaServiceImpl.resolveSessionCompany 解析会话公司；管理员的数据范围读写不经此，
 * 走 auth.list_user_scopes / auth.set_user_org_scope 闸口。
 */
public interface UserOrgScopeMapper extends BaseMapper<UserOrgScope> {
}
