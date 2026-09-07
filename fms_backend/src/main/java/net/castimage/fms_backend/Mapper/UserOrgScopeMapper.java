package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserOrgScope;

/** 用户-公司数据范围 Mapper（um.user_org_scope）：本批默认不写（orgIds=null 则由 PG 从 scope 表预载），供 admin 全量/切换数据范围场景启用（is_self OR fms_admin） */
public interface UserOrgScopeMapper extends BaseMapper<UserOrgScope> {
    // orgIds=null 时由 PG 从 scope 表预载，
    // 预留：admin 全量/切换数据范围场景启用，
}
