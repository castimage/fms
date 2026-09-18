package net.castimage.fms_backend.Service;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AccountRequest;
import net.castimage.fms_backend.Model.dto.RejectRequest;
import net.castimage.fms_backend.Model.dto.ReverseRequest;
import net.castimage.fms_backend.Model.dto.VoucherCreateRequest;
import net.castimage.fms_backend.Model.dto.VoucherUpdateRequest;
import net.castimage.fms_backend.Model.vo.AccountView;
import net.castimage.fms_backend.Model.vo.LedgerView;
import net.castimage.fms_backend.Model.vo.PeriodView;
import net.castimage.fms_backend.Model.vo.TrialBalanceView;
import net.castimage.fms_backend.Model.vo.VoucherDetailView;
import net.castimage.fms_backend.Model.vo.VoucherGapView;
import net.castimage.fms_backend.Model.vo.VoucherListView;

import java.util.List;
import java.util.UUID;

/** 总账模块业务接口（科目/凭证状态机/账簿查询）：状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、过账 POSTED、冲销 REVERSED，过账/冲销经 PG SECURITY DEFINER 函数；平衡校验 Java 预检 + DB 触发器终审（RAISE 统一映射 40900/40001） */
public interface GlService {

    // 科目（ltree 树 CRUD），

    /**
     * 科目树列表（path ltree 自然序；RLS 决定可见集合，companyId 仅过滤提示）。
     */
    List<AccountView> listAccounts(UUID companyId, String keyword);

    /**
     * 科目详情（RLS 空集返回 null 则响应 data:null）。
     */
    AccountView getAccount(UUID id);

    /**
     * 创建科目（path=父.path+'.'+code，level=父+1；父 is_leaf 翻 false；uk 冲突 409）。
     */
    AccountView createAccount(AccountRequest req);

    /**
     * 更新科目（code/path 禁改，仅 name/accountType/direction/isForeignCurrency/currency/status）。
     */
    AccountView updateAccount(UUID id, AccountRequest req);

    /**
     * 软删科目（有子科目/被分录引用 40900；删除后父科目无子则恢复 is_leaf=true）。
     */
    void deleteAccount(UUID id);

    // 凭证状态机，DRAFT 提交 SUBMITTED，审核 APPROVED，过账 POSTED，冲销 REVERSED，

    /**
     * 凭证分页列表（RLS 生效验证点：DRAFT 仅本人可见、越权公司空集、无上下文空集）。
     */
    PageResult<VoucherListView> pageVouchers(long page, long size, String period, String status,
                                             UUID companyId, String keyword, Boolean createdByMe);

    /**
     * 凭证详情（头 + 分录；他人 DRAFT/越权则 RLS 空集，返回 null）。
     */
    VoucherDetailView getVoucher(UUID id);

    /**
     * 创建 DRAFT 草稿（期间 OPEN 预检 + 平衡预检；DB 触发器终审）。
     */
    VoucherDetailView createVoucher(VoucherCreateRequest req);

    /**
     * 更新 DRAFT 草稿（仅 DRAFT；软删旧分录 + 全量重插；期间不可变更）。
     */
    VoucherDetailView updateVoucher(UUID id, VoucherUpdateRequest req);

    /**
     * 软删 DRAFT 草稿（仅 DRAFT）。
     */
    void deleteVoucher(UUID id);

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅制单人可提交；平衡/期间 OPEN 预检，DB 触发器终审。
     */
    VoucherDetailView submitVoucher(UUID id);

    /**
     * 审批（SUBMITTED 转 APPROVED）：审批人须非制单人（SoD，DB 触发器复核）。
     */
    VoucherDetailView approveVoucher(UUID id);

    /**
     * 过账（APPROVED 转 POSTED）：调 PG gl.fn_post_voucher（取号/审计原子完成）。
     */
    VoucherDetailView postVoucher(UUID id);

    /**
     * 驳回（SUBMITTED 转 REJECTED）：原因必填；调 PG gl.fn_reject_voucher（权限闸口
     * gl.voucher.reject + SoD 驳回人≠制单人 + 状态历史 remark=原因 + 审计原子完成）。
     */
    VoucherDetailView rejectVoucher(UUID id, RejectRequest req);

    /**
     * 退回（REJECTED 转 DRAFT）：被驳回凭证退回草稿修改后可重新提交/删除，解除"驳回即死数据"
     * 并放行期间结账；调 PG gl.fn_revert_voucher（权限闸口 gl.voucher.revert + 状态历史 + 审计原子完成）。
     */
    VoucherDetailView revertVoucher(UUID id);

    /**
     * 红字冲销（POSTED 转 REVERSED）：调 PG gl.fn_reverse_voucher，返回反向凭证详情。
     */
    VoucherDetailView reverseVoucher(UUID id, ReverseRequest req);

    // 账簿

    /**
     * 总账（期初/本期借/本期贷/期末；仅统计 POSTED/REVERSED 凭证，RLS 公司范围）。
     */
    List<LedgerView> ledger(UUID companyId, String period, UUID accountId);

    /**
     * 总账 CSV 导出（镜像 rpt.exportCsv 白名单模式：period 白名单校验 + UTF-8 BOM +
     * attachment 流式下载，文件名 ledger_yyyyMMdd.csv；方向列转中文 借/贷）。
     */
    void exportLedgerCsv(UUID companyId, String period, UUID accountId, HttpServletResponse response);

    /**
     * 试算平衡（分科目期初/本期/期末 + 汇总；balanced=借=贷）。
     */
    TrialBalanceView trialBalance(UUID companyId, String period);

    /**
     * 凭证断号清单（镜像 gl.fn_cron_voucher_number_check 期间缺口 SQL 的在线查询：
     * 按公司+期间返回 POSTED/REVERSED 凭证号缺失号段；RLS 收敛可见集合，空表=无断号）。
     */
    List<VoucherGapView> listVoucherGaps(UUID companyId, String period);

    // 期间开关 / 期末调汇

    /**
     * 期间状态清单（按公司返回已存在的期间行与其 OPEN/CLOSED 状态，供期间管理页回显；
     * 行级安全按公司范围收敛，越权空集）。
     */
    List<PeriodView> listPeriods(UUID companyId);

    /**
     * 期间结账（OPEN 转 CLOSED）：调 gl.fn_close_period 原子完成——完整性预检（期间内未完成
     * 凭证计数 > 0 或 POSTED/REVERSED 断号则 RAISE 40900）+ 置 CLOSED + 记 closed_by/closed_at
     * + 审计留痕；权限闸口 gl.period.close 在函数内（operator 经 um.permissions 注册后可用）。
     * 结账后期间由 trg_period_guard/trg_period_voucher_guard 联动锁定。
     */
    void closePeriod(UUID companyId, String period);

    /**
     * 期间反结账（CLOSED 转 OPEN，末位期间）：调 gl.fn_reopen_period——原因必填 + 二次确认开关
     * fms.force_reopen=on（未开启则 40900）+ 仅可反开最晚期间 + 清 closed_by/closed_at + 审计留痕。
     */
    void reopenPeriod(UUID companyId, String period, String reason);

    /**
     * 期末调汇（手动触发；cron 每月 1 日 03:00 仍自动跑 gl.fn_fx_adjustment）：
     * 外币科目期末余额 × 期末汇率与按 1:1 计价账面的差生成 DRAFT 调汇凭证草稿（幂等键
     * fx_adjustment:+period 防重复；无汇率跳过）；返回生成的调汇凭证数。
     */
    int runFxAdjustment(UUID companyId, String period);
}
