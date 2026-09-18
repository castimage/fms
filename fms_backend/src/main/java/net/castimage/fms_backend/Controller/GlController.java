package net.castimage.fms_backend.Controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AccountRequest;
import net.castimage.fms_backend.Model.dto.PeriodActionRequest;
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
import net.castimage.fms_backend.Service.GlService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * 总账模块 REST 接口（/api/gl）：科目 CRUD（树）、凭证 CRUD + 状态机（submit/approve/post/reverse）、总账/试算平衡查询；
 * 身份由安全链路（X-FMS-Signature 校验 + 沙箱注入）保证，RLS 决定可见集合——列表接口直接过滤（越权空集），
 * 只读详情越权返回 data:null（不泄露存在性），写路径越权/状态非法 40400/40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/gl")
public class GlController {

    private final GlService glService;


    // 科目

    /**
     * 科目树列表（path ltree 自然序；companyId/keyword 过滤提示，RLS 收敛可见集合）。
     */
    @GetMapping("/accounts")
    public ApiResponse<List<AccountView>> listAccounts(@RequestParam(required = false) UUID companyId,
                                                       @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(glService.listAccounts(companyId, keyword));
    }

    /**
     * 科目详情（越权则 RLS 空集，回 data:null）。
     */
    @GetMapping("/accounts/{id}")
    public ApiResponse<AccountView> getAccount(@PathVariable UUID id) {
        return ApiResponse.ok(glService.getAccount(id));
    }

    /**
     * 创建科目（path=父.path+'.'+code；父 is_leaf 翻 false；uk_account 冲突 40900）。
     */
    @PostMapping("/accounts")
    public ApiResponse<AccountView> createAccount(@RequestBody @Valid AccountRequest req) {
        return ApiResponse.ok(glService.createAccount(req));
    }

    /**
     * 更新科目（code/path 禁改，仅 name/status/accountType 等）。
     */
    @PutMapping("/accounts/{id}")
    public ApiResponse<AccountView> updateAccount(@PathVariable UUID id, @RequestBody @Valid AccountRequest req) {
        return ApiResponse.ok(glService.updateAccount(id, req));
    }

    /**
     * 软删科目（有子科目/被分录引用 40900）。
     */
    @DeleteMapping("/accounts/{id}")
    public ApiResponse<Void> deleteAccount(@PathVariable UUID id) {
        glService.deleteAccount(id);
        return ApiResponse.ok(null);
    }

    // 凭证

    /**
     * 凭证分页列表（RLS 生效验证点：companyId 越权则空集；DRAFT 仅本人可见；keyword 命中备注/摘要）。
     */
    @GetMapping("/vouchers")
    public ApiResponse<PageResult<VoucherListView>> pageVouchers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean createdByMe) {
        return ApiResponse.ok(glService.pageVouchers(page, size, period, status, companyId, keyword, createdByMe));
    }

    /**
     * 凭证详情（头 + 分录；他人 DRAFT/越权则 data:null，不泄露存在性）。
     */
    @GetMapping("/vouchers/{id}")
    public ApiResponse<VoucherDetailView> getVoucher(@PathVariable UUID id) {
        return ApiResponse.ok(glService.getVoucher(id));
    }

    /**
     * 创建 DRAFT 草稿（期间 OPEN 预检 + 平衡预检；DB 触发器终审）。
     */
    @PostMapping("/vouchers")
    public ApiResponse<VoucherDetailView> createVoucher(@RequestBody @Valid VoucherCreateRequest req) {
        return ApiResponse.ok(glService.createVoucher(req));
    }

    /**
     * 更新 DRAFT 草稿（仅 DRAFT；期间不可变更）。
     */
    @PutMapping("/vouchers/{id}")
    public ApiResponse<VoucherDetailView> updateVoucher(@PathVariable UUID id,
                                                        @RequestBody @Valid VoucherUpdateRequest req) {
        return ApiResponse.ok(glService.updateVoucher(id, req));
    }

    /**
     * 软删 DRAFT 草稿（仅 DRAFT）。
     */
    @DeleteMapping("/vouchers/{id}")
    public ApiResponse<Void> deleteVoucher(@PathVariable UUID id) {
        glService.deleteVoucher(id);
        return ApiResponse.ok(null);
    }

    /**
     * 提交（DRAFT 转 SUBMITTED）：仅制单人；DB 触发器终审平衡/期间 OPEN。
     */
    @PostMapping("/vouchers/{id}/submit")
    public ApiResponse<VoucherDetailView> submit(@PathVariable UUID id) {
        return ApiResponse.ok(glService.submitVoucher(id));
    }

    /**
     * 审批（SUBMITTED 转 APPROVED）：审批人须非制单人（SoD，DB 触发器复核）。
     */
    @PostMapping("/vouchers/{id}/approve")
    public ApiResponse<VoucherDetailView> approve(@PathVariable UUID id) {
        return ApiResponse.ok(glService.approveVoucher(id));
    }

    /**
     * 过账（APPROVED 转 POSTED）：调 PG gl.fn_post_voucher（取号/审计原子完成）。
     */
    @PostMapping("/vouchers/{id}/post")
    public ApiResponse<VoucherDetailView> post(@PathVariable UUID id) {
        return ApiResponse.ok(glService.postVoucher(id));
    }

    /**
     * 驳回（SUBMITTED 转 REJECTED）：原因必填（记 voucher_status_history.remark 与审计）；
     * 驳回为审核侧决策（SoD 驳回人≠制单人），下一批次退回修改后重新提交。
     */
    @PostMapping("/vouchers/{id}/reject")
    public ApiResponse<VoucherDetailView> reject(@PathVariable UUID id,
                                                 @RequestBody @Valid RejectRequest req) {
        return ApiResponse.ok(glService.rejectVoucher(id, req));
    }

    /**
     * 退回（REJECTED 转 DRAFT）：被驳回凭证退回草稿修改后可重新提交/删除（解除驳回死数据并
     * 放行期间结账）；调 PG gl.fn_revert_voucher（权限闸口 gl.voucher.revert）。
     */
    @PostMapping("/vouchers/{id}/revert")
    public ApiResponse<VoucherDetailView> revert(@PathVariable UUID id) {
        return ApiResponse.ok(glService.revertVoucher(id));
    }

    /**
     * 红字冲销（POSTED 转 REVERSED）：调 PG gl.fn_reverse_voucher，返回反向凭证详情。
     */
    @PostMapping("/vouchers/{id}/reverse")
    public ApiResponse<VoucherDetailView> reverse(@PathVariable UUID id,
                                                  @RequestBody(required = false) @Valid ReverseRequest req) {
        return ApiResponse.ok(glService.reverseVoucher(id, req == null ? new ReverseRequest(null, null) : req)); // 空请求体按全量冲销，
    }

    // 账簿

    /**
     * 总账（期初/本期借/本期贷/期末；仅统计 POSTED/REVERSED 凭证，RLS 公司范围）。
     */
    @GetMapping("/ledger")
    public ApiResponse<List<LedgerView>> ledger(@RequestParam UUID companyId,
                                                @RequestParam String period,
                                                @RequestParam(required = false) UUID accountId) {
        return ApiResponse.ok(glService.ledger(companyId, period, accountId));
    }

    /**
     * 总账 CSV 导出（镜像 rpt.exportCsv 白名单模式：period 白名单校验 + UTF-8 BOM +
     * attachment 流式下载，文件名 ledger_yyyyMMdd.csv；查询口径与 /ledger 一致）。
     */
    @GetMapping("/ledger/export")
    public void exportLedger(@RequestParam UUID companyId,
                             @RequestParam String period,
                             @RequestParam(required = false) UUID accountId,
                             HttpServletResponse response) {
        glService.exportLedgerCsv(companyId, period, accountId, response);
    }

    /**
     * 试算平衡（分科目期初/本期/期末 + 借贷汇总与平衡标志）。
     */
    @GetMapping("/trial-balance")
    public ApiResponse<TrialBalanceView> trialBalance(@RequestParam UUID companyId,
                                                      @RequestParam String period) {
        return ApiResponse.ok(glService.trialBalance(companyId, period));
    }

    /**
     * 凭证断号清单（镜像 gl.fn_cron_voucher_number_check 期间缺口 SQL 的在线查询：
     * 按公司+期间返回 POSTED/REVERSED 凭证号缺失号段，RLS 收敛可见集合；空数组=无断号）。
     */
    @GetMapping("/voucher-gaps")
    public ApiResponse<List<VoucherGapView>> voucherGaps(@RequestParam UUID companyId,
                                                         @RequestParam String period) {
        return ApiResponse.ok(glService.listVoucherGaps(companyId, period));
    }

    // 期间开关 / 期末调汇

    /**
     * 期间状态清单（按公司返回已存在的期间行与其 OPEN/CLOSED 状态，供期间管理页回显，
     * 行级安全按公司范围收敛，越权空集）。
     */
    @GetMapping("/periods")
    public ApiResponse<List<PeriodView>> listPeriods(@RequestParam UUID companyId) {
        return ApiResponse.ok(glService.listPeriods(companyId));
    }

    /**
     * 期间结账（OPEN 转 CLOSED）：调 gl.fn_close_period——未完成凭证/断号完整性预检（违规
     * 40900）+ 置 CLOSED + 审计；body.reason 仅作扩展占位（函数内不消费，结账无需原因）。
     */
    @PostMapping("/periods/{companyId}/{period}/close")
    public ApiResponse<Void> closePeriod(@PathVariable UUID companyId,
                                         @PathVariable String period,
                                         @RequestBody(required = false) @Valid PeriodActionRequest req) {
        glService.closePeriod(companyId, period);
        return ApiResponse.ok(null);
    }

    /**
     * 期间反结账（CLOSED 转 OPEN，末位期间）：调 gl.fn_reopen_period——原因必填 + 二次确认开关
     * fms.force_reopen=on（未开启 40900，提示先开开关）+ 审计；closed_by 由函数取会话用户。
     */
    @PostMapping("/periods/{companyId}/{period}/reopen")
    public ApiResponse<Void> reopenPeriod(@PathVariable UUID companyId,
                                          @PathVariable String period,
                                          @RequestBody(required = false) @Valid PeriodActionRequest req) {
        glService.reopenPeriod(companyId, period, req == null ? null : req.reason());
        return ApiResponse.ok(null);
    }

    /**
     * 期末调汇（手动触发；cron 每月 1 日 03:00 仍自动调 gl.fn_fx_adjustment）：外币科目
     * 期末余额 × 期末汇率 与按 1:1 计价账面的差生成 DRAFT 调汇凭证草稿（幂等防重复）；返回生成的
     * 调汇凭证数。
     */
    @PostMapping("/fx-adjustment")
    public ApiResponse<Integer> runFxAdjustment(@RequestParam UUID companyId,
                                                @RequestParam String period) {
        return ApiResponse.ok(glService.runFxAdjustment(companyId, period));
    }
}
