package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Company;

/** 公司 Mapper（org.companies）：创建/更新经 org.fn_upsert_company 闸口（新建公司 RLS INSERT WITH CHECK 恒 false），软删经 auth.fn_soft_delete；查询 RLS can_access_org(id) */
public interface CompanyMapper extends BaseMapper<Company> {
    // 新建公司不在任何数据范围，只能走 upsert 闸口，
    // 查询按 RLS can_access_org 收敛，
}
