package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.BankStatement;
import net.castimage.fms_backend.Model.vo.BankStatementView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

/** 银行对账单 Mapper（cash.bank_statements）：头表 CRUD 走 BaseMapper + Wrapper；分页/状态流转手写 */
public interface BankStatementMapper extends BaseMapper<BankStatement> {

    /** 对账单分页列表：join 银行账户名；bankAccountId 可选过滤（排序白名单固定） */
    @Select("""
            SELECT s.id, s.company_id, s.bank_account_id, b.bank_name, s.filename,
                   s.import_date, s.status, s.total_lines, s.created_at
              FROM cash.bank_statements s
              LEFT JOIN cash.bank_accounts b ON b.id = s.bank_account_id AND b.deleted_at IS NULL
             WHERE s.deleted_at IS NULL
               AND (#{bankAccountId}::uuid IS NULL OR s.bank_account_id = #{bankAccountId}::uuid)
             ORDER BY s.import_date DESC, s.created_at DESC
            """)
    IPage<BankStatementView> selectPage(Page<BankStatementView> page,
                                        @Param("bankAccountId") UUID bankAccountId);

    /** 状态流转（如作废 IMPORTED 转 VOID）：影响 0 行则 Service 按 40900 处理 */
    @Update("""
            UPDATE cash.bank_statements
               SET status = #{status}, updated_at = now()
             WHERE id = #{id}::uuid AND deleted_at IS NULL
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);
}
