package net.castimage.fms_backend.Service.impl;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BudgetAdjustmentMapper;
import net.castimage.fms_backend.Mapper.BudgetPlanMapper;
import net.castimage.fms_backend.Mapper.ChartAccountMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.BudgetAdjustmentRequest;
import net.castimage.fms_backend.Model.dto.BudgetPlanRequest;
import net.castimage.fms_backend.Model.entity.BudgetAdjustment;
import net.castimage.fms_backend.Model.entity.BudgetPlan;
import net.castimage.fms_backend.Model.entity.ChartAccount;
import net.castimage.fms_backend.Model.vo.BudgetAdjustmentView;
import net.castimage.fms_backend.Model.vo.BudgetExecutionRow;
import net.castimage.fms_backend.Model.vo.BudgetPlanView;
import net.castimage.fms_backend.Service.BudgetService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 预算模块业务实现，计划/调整单状态机（Java 预检 + rls update 策略与 db 终审兜底，0 行则 40900），
 * 执行对比取计划最新版本 LEFT JOIN rpt.budget_actual immv + 已批调整净额，Service 计算
 * ratio/overLimit/level，超支阻断由 db 触发器 budget.fn_voucher_budget_block 承担
 * （gl.voucher_lines BEFORE INSERT/UPDATE，RAISE P0001 含"预算超支"则 40900），本类零实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 零值，标度 2 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 计划状态机合法状态，db CHECK 同构 */
    private static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "SUBMITTED", "APPROVED");

    /** 调整单状态机合法状态，db CHECK 同构 */
    private static final Set<String> ADJUSTMENT_STATUSES = Set.of("DRAFT", "APPROVED", "REJECTED");

    /** 执行比例阈值，warn（um.params budget.warn.ratio 缺省 0.8）/ block，超支阻断阈值缺省 1.0 */
    private static final BigDecimal WARN_RATIO = new BigDecimal("0.8");
    private static final BigDecimal BLOCK_RATIO = BigDecimal.ONE;

    /** 执行比例标度，展示位 4 */
    private static final int RATIO_SCALE = 4;

    private final BudgetPlanMapper planMapper;
    private final BudgetAdjustmentMapper adjustmentMapper;
    private final ChartAccountMapper chartAccountMapper;
    private final AuthFunctionMapper authFunctionMapper;

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<BudgetPlanView> pagePlans(long page, long size, UUID companyId, String period,
                                                UUID deptId, String status) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(planMapper.selectPlanPage(new Page<>(pageNum, sizeNum),
                companyId, period, deptId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public BudgetPlanView getPlan(UUID id) {
        // rls 空集返回 null 则 data:null，不泄露存在性
        return planMapper.selectPlanDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetPlanView createPlan(BudgetPlanRequest req) {
        RequestContext.requireWriteRole();
        String period = normalizePeriod(req.period());
        requireAccount(req.accountId());
        BudgetPlan p = new BudgetPlan();
        p.setId(UuidV7.next());
        p.setCompanyId(req.companyId());
        p.setPeriod(period);
        p.setAccountId(req.accountId());
        p.setDeptId(req.deptId());
        p.setAmount(money(req.amount()));
        p.setVersion(0); // 新计划版本 0，调整以新版本叠加
        p.setStatus("DRAFT");
        p.setCreatedBy(RequestContext.requireUserId());
        planMapper.insert(p); // uk_plan 同键位重复 23505 映射 40900
        return requirePlanView(p.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetPlanView updatePlan(UUID id, BudgetPlanRequest req) {
        RequestContext.requireWriteRole();
        BudgetPlan p = requirePlan(id);
        if (!p.getCreatedBy().equals(RequestContext.requireUserId())) {
            throw new ForbiddenException("仅创建人可修改预算计划");
        }
        if (!"DRAFT".equals(p.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态预算计划可修改（当前 " + p.getStatus() + "）");
        }
        requireAccount(req.accountId());
        BudgetPlan patch = new BudgetPlan();
        patch.setId(id);
        patch.setAmount(money(req.amount()));
        patch.setAccountId(req.accountId());
        patch.setDeptId(req.deptId());
        patch.setPeriod(normalizePeriod(req.period()));
        // 键位（company/period/account/dept/version）变更撞已有行则 uk_plan 23505 映射 40900
        if (planMapper.updateById(patch) == 0) {
            throw new ConflictException("更新失败：状态已变更或不可见");
        }
        return requirePlanView(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deletePlan(UUID id) {
        RequestContext.requireWriteRole();
        BudgetPlan p = requirePlan(id);
        if (!p.getCreatedBy().equals(RequestContext.requireUserId())) {
            throw new ForbiddenException("仅创建人可删除预算计划");
        }
        if (!"DRAFT".equals(p.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态预算计划可删除（当前 " + p.getStatus() + "）");
        }

        // 已被调整单引用则禁止删除，防外键悬空，调整单 rls 经父计划收敛，计数同口径
        if (adjustmentMapper.selectCount(new LambdaQueryWrapper<BudgetAdjustment>()
                .isNull(BudgetAdjustment::getDeletedAt)
                .eq(BudgetAdjustment::getPlanId, id)) > 0) {
            throw new ConflictException("预算计划已被调整单引用，禁止删除");
        }
        if (!authFunctionMapper.fnSoftDelete("budget", "budget_plans", id)) {
            throw new ConflictException("删除失败：状态可能已变更");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetPlanView submitPlan(UUID id) {
        RequestContext.requireWriteRole();
        BudgetPlan p = requirePlan(id);
        if (!"DRAFT".equals(p.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态预算计划可提交（当前 " + p.getStatus() + "）");
        }
        if (!p.getCreatedBy().equals(RequestContext.requireUserId())) {
            throw new ConflictException("仅创建人可提交预算计划");
        }
        BudgetPlan patch = new BudgetPlan();
        patch.setId(id);
        patch.setStatus("SUBMITTED");
        if (planMapper.updateById(patch) == 0) {
            throw new ConflictException("提交失败：状态已变更或不可见");
        }
        return requirePlanView(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetPlanView approvePlan(UUID id) {
        RequestContext.requireWriteRole();
        BudgetPlan p = requirePlan(id);
        if (!"SUBMITTED".equals(p.getStatus())) {
            throw new ConflictException("仅 SUBMITTED 状态预算计划可审批（当前 " + p.getStatus() + "）");
        }
        UUID userId = RequestContext.requireUserId();
        if (p.getCreatedBy().equals(userId)) {
            throw new ForbiddenException("创建人不能审批本人预算计划");
        }
        BudgetPlan patch = new BudgetPlan();
        patch.setId(id);
        patch.setStatus("APPROVED");
        patch.setApprovedBy(userId);
        patch.setApprovedAt(LocalDateTime.now());
        if (planMapper.updateById(patch) == 0) {
            throw new ConflictException("审批失败：状态已变更或不可见");
        }
        return requirePlanView(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<BudgetAdjustmentView> pageAdjustments(long page, long size, UUID companyId,
                                                           UUID planId, String status) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(adjustmentMapper.selectAdjustmentPage(
                new Page<>(pageNum, sizeNum), companyId, planId, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public BudgetAdjustmentView getAdjustment(UUID id) {
        return adjustmentMapper.selectAdjustmentDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetAdjustmentView createAdjustment(BudgetAdjustmentRequest req) {
        RequestContext.requireWriteRole();
        BudgetPlan plan = requirePlan(req.planId());
        BigDecimal amount = money(req.amount());
        if (amount.signum() == 0) {
            throw new BadRequestException("调整金额不能为 0（正=追加 负=调减）");
        }
        String reason = req.reason().trim();
        // 幂等键，同计划同金额同原因，规范化后必同键，同键已存在且未软删则返回既有调整单，A 级幂等
        String idemKey = adjustmentIdemKey(plan.getCompanyId(), req.planId(), amount, reason);
        BudgetAdjustment existing = findByAdjustmentIdemKey(plan.getCompanyId(), req.planId(), idemKey);
        if (existing != null) {
            log.info("createAdjustment 幂等命中：同计划同金额同原因已存在 id={}，返回既有调整单", existing.getId());
            return requireAdjustmentView(existing.getId());
        }
        BudgetAdjustment ad = new BudgetAdjustment();
        ad.setId(UuidV7.next());
        ad.setCompanyId(plan.getCompanyId()); // rls INSERT 经父计划公司范围 WITH CHECK
        ad.setPlanId(req.planId());
        ad.setAmount(amount);
        ad.setReason(reason);
        ad.setIdemKey(idemKey);
        ad.setStatus("DRAFT");
        try {
            adjustmentMapper.insert(ad);
        } catch (DuplicateKeyException e) {
            // 并发同 body 竞态，uk_adjustment_idem 唯一索引拒绝重复，23505 不产生重复数据，回查返回先提交者
            BudgetAdjustment winner = findByAdjustmentIdemKey(plan.getCompanyId(), req.planId(), idemKey);
            if (winner != null) {
                log.info("createAdjustment 并发幂等命中：id={} 已先提交，返回既有调整单", winner.getId());
                return requireAdjustmentView(winner.getId());
            }
            throw e;
        }
        return requireAdjustmentView(ad.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetAdjustmentView approveAdjustment(UUID id) {
        RequestContext.requireWriteRole();
        BudgetAdjustment ad = requireAdjustment(id);
        if (!"DRAFT".equals(ad.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态调整单可审批（当前 " + ad.getStatus() + "）");
        }
        UUID userId = RequestContext.requireUserId();
        BudgetAdjustment patch = new BudgetAdjustment();
        patch.setId(id);
        patch.setStatus("APPROVED");
        patch.setAdjustedBy(userId);
        patch.setAdjustedAt(LocalDateTime.now());
        if (adjustmentMapper.updateById(patch) == 0) {
            throw new ConflictException("审批失败：状态已变更或不可见");
        }
        return requireAdjustmentView(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BudgetAdjustmentView rejectAdjustment(UUID id) {
        RequestContext.requireWriteRole();
        BudgetAdjustment ad = requireAdjustment(id);
        if (!"DRAFT".equals(ad.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态调整单可驳回（当前 " + ad.getStatus() + "）");
        }
        UUID userId = RequestContext.requireUserId();
        BudgetAdjustment patch = new BudgetAdjustment();
        patch.setId(id);
        patch.setStatus("REJECTED");
        patch.setAdjustedBy(userId);
        patch.setAdjustedAt(LocalDateTime.now());
        if (adjustmentMapper.updateById(patch) == 0) {
            throw new ConflictException("驳回失败：状态已变更或不可见");
        }
        return requireAdjustmentView(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteAdjustment(UUID id) {
        RequestContext.requireWriteRole();
        BudgetAdjustment ad = requireAdjustment(id);
        if (!"DRAFT".equals(ad.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态调整单可删除（当前 " + ad.getStatus() + "）");
        }
        if (!authFunctionMapper.fnSoftDelete("budget", "budget_adjustments", id)) {
            throw new ConflictException("删除失败：状态可能已变更");
        }
    }

    /**
     * {@inheritDoc}
     *
     * 预算执行对比，取计划最新版本，APPROVED 优先 LEFT JOIN rpt.budget_actual immv
     * （company×period×account×dept 直接对键）+ 已批调整净额，ratio = (actual + adjusted) / plan，
     * overLimit = ratio ≥ 100%，超支阻断阈值，拒绝由 db 触发器承担，level = BLOCK/WARN/NORMAL，warn 阈值 80%，availableAmount = plan + adjusted - actual。
     */
    @Override
    @Transactional(readOnly = true)
    public List<BudgetExecutionRow> execution(UUID companyId, String period, UUID deptId) {
        return planMapper.selectExecutionRows(companyId, period, deptId).stream()
                .map(this::decorateExecutionRow)
                .toList();
    }

    // 私有方法

    /** 执行对比行补算 ratio/overLimit/level（sql 侧只取原始金额，展示口径收敛到 Service） */
    private BudgetExecutionRow decorateExecutionRow(BudgetExecutionRow row) {
        BigDecimal plan = money(row.planAmount());
        BigDecimal committed = money(row.actualAmount()).add(money(row.adjustedAmount()));
        BigDecimal ratio = committed.divide(plan, RATIO_SCALE, RoundingMode.HALF_UP);
        boolean overLimit = ratio.compareTo(BLOCK_RATIO) >= 0;
        String level = overLimit ? "BLOCK"
                : ratio.compareTo(WARN_RATIO) >= 0 ? "WARN" : "NORMAL";
        return new BudgetExecutionRow(row.planId(), row.companyId(), row.period(), row.accountId(),
                row.accountCode(), row.accountName(), row.deptId(), row.deptName(),
                plan, money(row.actualAmount()), money(row.adjustedAmount()),
                money(row.availableAmount()), ratio, overLimit, level);
    }

    /** 按 id 取计划（rls 空集则 40400） */
    private BudgetPlan requirePlan(UUID id) {
        BudgetPlan p = planMapper.selectById(id);
        if (p == null) {
            throw new NotFoundException("预算计划不存在");
        }
        return p;
    }

    /** 按 id 取调整单（rls 空集则 40400） */
    private BudgetAdjustment requireAdjustment(UUID id) {
        BudgetAdjustment ad = adjustmentMapper.selectById(id);
        if (ad == null) {
            throw new NotFoundException("预算调整单不存在");
        }
        return ad;
    }

    /** 调整单幂等键，companyId|planId|amount|reason 规范化 SHA-256 十六进制，同 body 必同键 */
    private static String adjustmentIdemKey(UUID companyId, UUID planId, BigDecimal amount, String reason) {
        return sha256Hex(companyId + "|" + planId + "|" + amount.toPlainString() + "|" + reason);
    }

    /** SHA-256 十六进制，64 字符 */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按幂等键查既有调整单，同公司同计划，uk_adjustment_idem 唯一索引保证至多 1 行 */
    private BudgetAdjustment findByAdjustmentIdemKey(UUID companyId, UUID planId, String idemKey) {
        return adjustmentMapper.selectOne(new LambdaQueryWrapper<BudgetAdjustment>()
                .eq(BudgetAdjustment::getCompanyId, companyId)
                .eq(BudgetAdjustment::getPlanId, planId)
                .eq(BudgetAdjustment::getIdemKey, idemKey)
                .isNull(BudgetAdjustment::getDeletedAt));
    }

    /** 计划详情视图（写路径 after-action 回读，rls 空集则 40400） */
    private BudgetPlanView requirePlanView(UUID id) {
        BudgetPlanView v = planMapper.selectPlanDetail(id);
        if (v == null) {
            throw new NotFoundException("预算计划不存在或不可见");
        }
        return v;
    }

    /** 调整单详情视图（写路径 after-action 回读，rls 空集则 40400） */
    private BudgetAdjustmentView requireAdjustmentView(UUID id) {
        BudgetAdjustmentView v = adjustmentMapper.selectAdjustmentDetail(id);
        if (v == null) {
            throw new NotFoundException("预算调整单不存在或不可见");
        }
        return v;
    }

    /** 预算科目存在性预检（rls 空集则 40400，防 FK 23503 落到 40900 语义不清） */
    private void requireAccount(UUID accountId) {
        ChartAccount acc = chartAccountMapper.selectById(accountId);
        if (acc == null) {
            throw new NotFoundException("预算科目不存在或不可见");
        }
    }

    /** 期间规范化（YYYY-MM）并校验 */
    private String normalizePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("期间格式非法：" + period + "（须为 YYYY-MM）");
        }
        int month = Integer.parseInt(period.substring(5, 7));
        if (month < 1 || month > 12) {
            throw new BadRequestException("期间月份非法：" + period);
        }
        return period;
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
