package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.ChartAccount;
import org.apache.ibatis.annotations.Insert;

/** 会计科目 Mapper（gl.chart_accounts）：可见集合由 RLS can_access_org(company_id) 收敛，删除/引用校验均在可见行内查询；单表操作统一 BaseMapper + Wrapper */
public interface ChartAccountMapper extends BaseMapper<ChartAccount> {
    // 可见集合由 RLS 收敛，删除/引用校验均在可见行内，
    // 单表等值/计数/软删统一走 Wrapper，

    /** 科目树插入：path 列为 ltree 类型，MyBatis-Plus 自动 INSERT 将 String 直绑 varchar 导致 42804 失败，须显式 #{path}::ltree 强转；parent_id/currency 为 NULL 时等价省略列 */
    @Insert("""
            INSERT INTO gl.chart_accounts(id, company_id, code, name, path, parent_id, level,
                                          is_leaf, account_type, direction, is_foreign_currency, currency, status)
            VALUES (#{id}, #{companyId}, #{code}, #{name}, #{path}::ltree, #{parentId}, #{level},
                    #{isLeaf}, #{accountType}, #{direction}, #{isForeignCurrency}, #{currency}, #{status})
            """)
    int insertChartAccount(ChartAccount account);
}
