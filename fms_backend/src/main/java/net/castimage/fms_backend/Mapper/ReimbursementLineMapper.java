package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.ReimbursementLine;
import net.castimage.fms_backend.Model.vo.ReimbursementLineView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 报销单明细 Mapper（expense.reimbursement_lines）：行级公司范围经父报销单子查询 RLS 收敛，仅按父单查询 */
public interface ReimbursementLineMapper extends BaseMapper<ReimbursementLine> {

    /** 报销单明细（行号升序；RLS 经父报销单公司范围收敛） */
    @Select("""
            SELECT id, reimbursement_id, line_no, type, amount, trx_date, summary, invoice_file_id
              FROM expense.reimbursement_lines
             WHERE reimbursement_id = #{reimbursementId} AND deleted_at IS NULL
             ORDER BY line_no
            """)
    List<ReimbursementLineView> selectByReimbursementId(@Param("reimbursementId") UUID reimbursementId);

    /** 明细行主键查询（RLS 收敛；越权/软删则 null，由 Service 映射 40400） */
    @Select("""
            SELECT id, company_id, reimbursement_id, line_no, type, amount, trx_date, summary, invoice_file_id
              FROM expense.reimbursement_lines
             WHERE id = #{id} AND deleted_at IS NULL
            """)
    ReimbursementLine selectLineById(@Param("id") UUID id);

    /** 明细行所属公司（RLS 子查询收敛；越权/软删则 null，附件上传公司推导用，客户端不可自报） */
    @Select("""
            SELECT r.company_id
              FROM expense.reimbursement_lines l
              JOIN expense.reimbursements r ON r.id = l.reimbursement_id
             WHERE l.id = #{lineId} AND l.deleted_at IS NULL AND r.deleted_at IS NULL
            """)
    UUID selectLineCompanyId(@Param("lineId") UUID lineId);

    /** 回填发票附件关联（invoice_file_id 写入链路；RLS 行级收敛，越权/已软删影响 0 行） */
    @Update("""
            UPDATE expense.reimbursement_lines
               SET invoice_file_id = #{fileId}
             WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateInvoiceFileId(@Param("id") UUID id, @Param("fileId") String fileId);
}
