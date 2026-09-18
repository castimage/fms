package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.UserDeptScope;

/**
 * 用户-部门数据范围 Mapper（um.user_dept_scope），无自定义方法；部门范围的消费方在库内
 * （auth.set_session 预载 + auth.can_access_dept 判定），管理员写入走 auth.set_user_dept_scope 闸口。
 */
public interface UserDeptScopeMapper extends BaseMapper<UserDeptScope> {
}
