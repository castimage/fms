package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Module;

/** 功能模块 Mapper（um.modules，perm-tree 数据源）：查询按 deleted_at 过滤 + is_enabled + identifier 排序（Service 侧构造 Wrapper） */
public interface ModuleMapper extends BaseMapper<Module> {
    // 权限树骨架：启用且未删的模块才入树，
    // 查询按 identifier 排序保证树序稳定，
}
