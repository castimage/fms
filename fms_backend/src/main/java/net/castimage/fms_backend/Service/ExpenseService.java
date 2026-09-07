package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.ReimbursementRequest;
import net.castimage.fms_backend.Model.vo.ReimbursementDetailView;
import net.castimage.fms_backend.Model.vo.ReimbursementView;

import java.util.UUID;

/**
 * 费用报销模块业务接口（报销单 CRUD + 状态机 submit/approve/reject/pay）：
 * 状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、付款 PAID、过账 POSTED（REJECTED 仅自 SUBMITTED，退回可再提交），
 * 终审由 DB 触发器 expense.fn_reimburse_status_check（RAISE P0001 即 40900），Java 仅预检快速失败；
 * 头金额 = SUM(lines.amount) Service 内强制；wf 挂接：submit 后启动实例并回填 wf_instance_id。
 */
public interface ExpenseService {

    /**
     * 报销单分页列表（RLS 已按本人/本部门/终态收敛，companyId/status/keyword 仅过滤提示）。
     */
    PageResult<ReimbursementView> pageReimbursements(long page, long size, UUID companyId,
                                                     String status, String keyword);

    /**
     * 本人报销分页（applicant_id = 当前用户；RLS is_self 双路径兜底）。
     */
    PageResult<ReimbursementView> pageMyReimbursements(long page, long size, String status, String keyword);

    /**
     * 报销单详情（头 + 明细行；RLS 空集返回 null 则 data:null，不泄露存在性）。
     */
    ReimbursementDetailView getReimbursement(UUID id);

    /**
     * 创建 DRAFT 草稿（头 + 明细同事务；头金额强制 = 明细合计；申请人恒为当前用户）。
     */
    ReimbursementDetailView createReimbursement(ReimbursementRequest req);

    /**
     * 更新 DRAFT 草稿（仅 DRAFT；软删旧明细 + 全量重插；头金额重新校验）。
     */
    ReimbursementDetailView updateReimbursement(UUID id, ReimbursementRequest req);

    /**
     * 软删 DRAFT 草稿（仅 DRAFT；级联软删明细行）。
     */
    void deleteReimbursement(UUID id);

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅申请人本人；明细行数 > 0 预检；DB 触发器终审。
     */
    ReimbursementDetailView submitReimbursement(UUID id);

    /**
     * 审批（SUBMITTED 转 APPROVED）：Java 预检状态 + DB 触发器终审。
     */
    ReimbursementDetailView approveReimbursement(UUID id);

    /**
     * 驳回（SUBMITTED 转 REJECTED）：退回后申请人可再提交（REJECTED 转 DRAFT 由更新路径预检）。
     */
    ReimbursementDetailView rejectReimbursement(UUID id);

    /**
     * 出纳付款（APPROVED 转 PAID）：仅付款后允许过账（POSTED 由凭证回填触发，本批不生成凭证）。
     */
    ReimbursementDetailView payReimbursement(UUID id);

    /**
     * 回填明细行发票附件关联（invoice_file_id 写入链路）：附件须已上传至该行名下
     * （business_type=REIMBURSEMENT_LINE、business_id=行 id），归属不符 40001；
     * 明细行不可见（RLS 空集）40400。
     */
    void bindInvoiceFile(UUID lineId, UUID fileId);
}
