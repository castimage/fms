package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.User;

/** 用户 Mapper（um.users）：登录查询为唯一读取 pass_hash 的入口（列白名单承载，加密/盲索引列不映射防意外响应泄露）；匿名查找恒为空集，闭环依赖 03c 登录闸口 */
public interface UserMapper extends BaseMapper<User> {
    // 登录查询为唯一读 pass_hash 的入口（列白名单承载），
    // 匿名查找恒空集，闭环依赖 03c 登录闸口，
}
