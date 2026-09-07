package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Department;
import org.apache.ibatis.annotations.Insert;

/** 部门 Mapper（org.departments，ltree 树）：code/path/parent_id/level 创建后禁改，仅 name/manager_id 可更新；仅 INSERT 手写（path 需 ::ltree 显式转换） */
public interface DepartmentMapper extends BaseMapper<Department> {
    // 结构字段（code/path/parent/level）创建后禁改，
    // 单表计数/更新走 BaseMapper + Wrapper，

    /** 创建部门：path 需 #{path}::ltree 显式转换（text 参数无隐式 ltree 赋值转换，Wrapper 无法表达） */
    @Insert("""
            INSERT INTO org.departments (id, company_id, parent_id, code, name, path, level, manager_id)
            VALUES (#{id}, #{companyId}, #{parentId}, #{code}, #{name}, #{path}::ltree, #{level}, #{managerId})
            """)
    int insertDepartment(Department dept);
}
