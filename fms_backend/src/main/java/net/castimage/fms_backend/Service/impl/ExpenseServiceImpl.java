package net.castimage.fms_backend.Service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.ReimbursementLineMapper;
import net.castimage.fms_backend.Mapper.ReimbursementMapper;
import net.castimage.fms_backend.Mapper.WfDefinitionMapper;
import net.castimage.fms_backend.Mapper.WfInstanceMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.ReimbursementLineRequest;
import net.castimage.fms_backend.Model.dto.ReimbursementRequest;
import net.castimage.fms_backend.Model.entity.Reimbursement;
import net.castimage.fms_backend.Model.entity.ReimbursementLine;
import net.castimage.fms_backend.Model.entity.WfDefinition;
import net.castimage.fms_backend.Model.entity.WfInstance;
import net.castimage.fms_backend.Model.vo.DocumentView;
import net.castimage.fms_backend.Model.vo.ReimbursementDetailView;
import net.castimage.fms_backend.Model.vo.ReimbursementLineView;
import net.castimage.fms_backend.Model.vo.ReimbursementView;
import net.castimage.fms_backend.Service.ExpenseService;
import net.castimage.fms_backend.Service.FileService;
import net.castimage.fms_backend.Service.VoucherLinkService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 费用报销模块业务实现：报销单 CRUD（头 + 明细同事务）与状态机 submit/approve/reject/pay——
 * Java 预检（金额一致性、状态前置、申请人归属）为第一层快速失败，DB 触发器
 * expense.fn_reimburse_status_check 终审（非法流转 RAISE P0001 映射 40900）；
 * updateById 影响 0 行（状态已变/RLS 不可见）一律 40900 冲突，不做重试猜测。
 * wf 挂接：提交成功后按公司 ACTIVE 报销定义（code=expense_reimburse）启动审批实例并
 * 回填 wf_instance_id，审批/驳回时同步实例状态（记录型；失败仅 WARN 不阻断报销主流程）。
 */
@Service
public class ExpenseServiceImpl implements ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseServiceImpl.class);

    /** 金额标度（NUMERIC(18,2) 对齐） */
    private static final int MONEY_SCALE = 2;

    /** 金额零值（标度 2，比较/累加基准） */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 报销单状态机合法状态（DB CHECK 同构） */
    private static final Set<String> REIMBURSEMENT_STATUSES = Set.of(
            "DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "PAID", "POSTED", "REVERSED");

    private final ReimbursementMapper reimbursementMapper;
    private final ReimbursementLineMapper lineMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final FileService fileService;
    private final WfDefinitionMapper wfDefinitionMapper;
    private final WfInstanceMapper wfInstanceMapper;
    private final VoucherLinkService voucherLinkService;

    public ExpenseServiceImpl(ReimbursementMapper reimbursementMapper, ReimbursementLineMapper lineMapper,
                              AuthFunctionMapper authFunctionMapper, FileService fileService,
                              WfDefinitionMapper wfDefinitionMapper, WfInstanceMapper wfInstanceMapper,
                              VoucherLinkService voucherLinkService) {
        this.reimbursementMapper = reimbursementMapper;
        this.lineMapper = lineMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.fileService = fileService;
        this.wfDefinitionMapper = wfDefinitionMapper;
        this.wfInstanceMapper = wfInstanceMapper;
        this.voucherLinkService = voucherLinkService;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReimbursementView> pageReimbursements(long page, long size, UUID companyId,
                                                            String status, String keyword) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(reimbursementMapper.selectReimbursementPage(
                new Page<>(pageNum, sizeNum), companyId, status, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReimbursementView> pageMyReimbursements(long page, long size, String status, String keyword) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(reimbursementMapper.selectMyPage(
                new Page<>(pageNum, sizeNum), RequestContext.requireUserId(), status, keyword));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ReimbursementDetailView getReimbursement(UUID id) {
        // 他人草稿/越权公司则 RLS 空集，返回 null（Controller 层 data:null，不泄露存在性）
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReimbursementDetailView createReimbursement(ReimbursementRequest req) {
        RequestContext.requireWriteRole();
        UUID userId = RequestContext.requireUserId();
        List<ReimbursementLineRequest> lines = req.lines();
        assertLinesValid(lines);
        // 头金额一致性：amount = SUM(lines.amount)（Service 内强制，防头行不一致）
        BigDecimal sum = sumLines(lines);
        if (money(req.amount()).compareTo(sum) != 0) {
            throw new ConflictException("报销总额与明细合计不一致：头 " + money(req.amount()) + "，行合计 " + sum);
        }

        // 创建幂等（uk_reimb_idem 同族模式）：规范化请求内容 SHA-256 作幂等键
        // （同公司同申请人同 body 重复提交仅 1 条，重复请求返回既有草稿 = A 级幂等；
        // 并发竞态由部分唯一索引兜底，23505 不产生重复数据 = B 级安全）
        String idemKey = reimbIdemKey(req.companyId(), userId, req);
        Reimbursement existing = findByIdemKey(req.companyId(), userId, idemKey);
        if (existing != null) {
            log.info("createReimbursement 幂等命中：同公司同申请人同内容已存在 id={}，返回既有报销单", existing.getId());
            return buildDetail(existing.getId());
        }

        Reimbursement r = new Reimbursement();
        r.setId(UuidV7.next());
        r.setCompanyId(req.companyId());
        r.setApplicantId(userId); // RLS INSERT WITH CHECK is_self(applicant_id) 兜底
        r.setDeptId(req.deptId());
        r.setTitle(req.title().trim());
        r.setAmount(sum);
        r.setCurrency(trimCurrency(req.currency()));
        r.setStatus("DRAFT");
        r.setCreatedBy(userId);
        r.setIdemKey(idemKey);
        try {
            reimbursementMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 并发同 body 竞态：唯一索引拒绝重复（PostgreSQL 唯一冲突不中止事务），回查返回先提交者
            Reimbursement winner = findByIdemKey(req.companyId(), userId, idemKey);
            if (winner != null) {
                log.info("createReimbursement 并发幂等命中：id={} 已先提交，返回既有报销单", winner.getId());
                return buildDetail(winner.getId());
            }
            throw e;
        }

        insertLines(r.getId(), r.getCompanyId(), lines);
        return buildDetail(r.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReimbursementDetailView updateReimbursement(UUID id, ReimbursementRequest req) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        // DRAFT 可任意修改；REJECTED（驳回退回）经更新路径转回 DRAFT——DB 触发器
        // expense.fn_reimburse_status_check 显式允许 REJECTED 转 DRAFT（"退回后可再提交"），
        // 与前端"修改后重新提交"按钮对齐，避免 REJECTED 在 API 层成为终态死锁
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw new ConflictException("仅 DRAFT/REJECTED 状态报销单可修改（当前 " + r.getStatus() + "）");
        }
        List<ReimbursementLineRequest> lines = req.lines();
        assertLinesValid(lines);
        BigDecimal sum = sumLines(lines);
        if (money(req.amount()).compareTo(sum) != 0) {
            throw new ConflictException("报销总额与明细合计不一致：头 " + money(req.amount()) + "，行合计 " + sum);
        }
        // 软删旧明细 + 全量重插（行号按序 1..n 重排），头字段补丁更新；公司/申请人不可变更
        softDeleteLines(id);
        insertLines(id, r.getCompanyId(), lines);
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        if ("REJECTED".equals(r.getStatus())) {
            patch.setStatus("DRAFT"); // 驳回退回，更新即退回草稿，允许再次提交（触发器放行 REJECTED 转 DRAFT）
        }
        patch.setTitle(req.title().trim());
        patch.setAmount(sum);
        patch.setCurrency(trimCurrency(req.currency()));
        patch.setDeptId(req.deptId());
        reimbursementMapper.updateById(patch);
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteReimbursement(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        // DRAFT/REJECTED 均可删除（驳回退回单可视同草稿撤销；软删不改状态列，触发器不拦截）
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw new ConflictException("仅 DRAFT/REJECTED 状态报销单可删除（当前 " + r.getStatus() + "）");
        }
        softDeleteLines(id);
        // 软删统一经 auth.fn_soft_delete 闸口（SECURITY DEFINER，RLS 新行可见性检查豁免）
        if (!authFunctionMapper.fnSoftDelete("expense", "reimbursements", id)) {
            throw new ConflictException("报销单删除失败，状态可能已变更");
        }
    }

    /**
     * {@inheritDoc}
     *
     * 提交报销单（状态机 DRAFT 转 SUBMITTED）：仅申请人本人可提交（防他人代提交）；明细行数
     * > 0 预检（行与头金额一致性在创建/更新时已强制）；DB 触发器 trg_reimburse_status_check
     * 终审（非法流转 RAISE 则 40900）。提交后单据进入审批队列，不可再改删。
     */
    @Override
    @Transactional
    public ReimbursementDetailView submitReimbursement(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        // DRAFT 直接提交；REJECTED（驳回后未修改直接再提交）先退回 DRAFT 再提交——
        // DB 状态机仅放行 REJECTED 转 DRAFT 再 SUBMITTED，两步均在触发器允许范围内
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw new ConflictException("仅 DRAFT/REJECTED 状态报销单可提交（当前 " + r.getStatus() + "）");
        }
        if (!r.getApplicantId().equals(RequestContext.requireUserId())) {
            throw new ConflictException("仅申请人本人可提交报销单");
        }
        if (lineMapper.selectByReimbursementId(id).isEmpty()) {
            throw new ConflictException("报销单没有明细行，无法提交");
        }
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        if ("REJECTED".equals(r.getStatus())) {
            // REJECTED 转 DRAFT（退回草稿，触发器放行）
            Reimbursement reopen = new Reimbursement();
            reopen.setId(id);
            reopen.setStatus("DRAFT");
            if (reimbursementMapper.updateById(reopen) == 0) {
                throw new ConflictException("提交失败：状态已变更或不可见");
            }
        }
        patch.setStatus("SUBMITTED");
        if (reimbursementMapper.updateById(patch) == 0) {
            throw new ConflictException("提交失败：状态已变更或不可见");
        }
        // wf 挂接（尽力生成，同事务）：提交即按公司 ACTIVE 报销审批流定义启动实例并回填
        // wf_instance_id；定义缺失/未激活/失败仅 WARN（wf 为记录型，不阻断报销主流程，
        // 可经 POST /api/wf/instances 手动发起补挂接）
        try {
            startWfInstance(id);
        } catch (RuntimeException e) {
            log.warn("报销单 {} 挂接审批流失败（wf_instance_id 留空，可经 /api/wf/instances 手动发起）：{}",
                    id, e.getMessage());
        }
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReimbursementDetailView approveReimbursement(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new ConflictException("仅 SUBMITTED 状态报销单可审批（当前 " + r.getStatus() + "）");
        }
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        patch.setStatus("APPROVED");
        if (reimbursementMapper.updateById(patch) == 0) {
            throw new ConflictException("审批失败：状态已变更或不可见");
        }
        // 同步 wf 实例状态 RUNNING 转 APPROVED（记录型；实例不可见/已终态 0 行仅 WARN）
        syncWfStatus(id, "APPROVED");
        // 审批通过不生成凭证，费用入账在付款后由显式生成凭证动作触发，
        // 审批期不占位，凭证回填与状态过账同步完成，两者时机一致
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ReimbursementDetailView rejectReimbursement(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new ConflictException("仅 SUBMITTED 状态报销单可驳回（当前 " + r.getStatus() + "）");
        }
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        patch.setStatus("REJECTED");
        if (reimbursementMapper.updateById(patch) == 0) {
            throw new ConflictException("驳回失败：状态已变更或不可见");
        }
        // 同步 wf 实例状态 RUNNING 转 REJECTED（记录型；实例不可见/已终态 0 行仅 WARN）
        syncWfStatus(id, "REJECTED");
        return buildDetail(id);
    }

    /**
     * {@inheritDoc}
     *
     * 出纳付款，状态机 APPROVED 转 PAID，Java 预检状态前置，DB 触发器终审
     *
     * 付款后单据冻结，仅可沿 PAID 转 POSTED 推进，该推进由显式生成凭证动作一并完成，
     * 本动作只落状态，不触碰凭证
     */
    @Override
    @Transactional
    public ReimbursementDetailView payReimbursement(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        if (!"APPROVED".equals(r.getStatus())) {
            throw new ConflictException("仅 APPROVED 状态报销单可付款（当前 " + r.getStatus() + "）");
        }
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        patch.setStatus("PAID");
        if (reimbursementMapper.updateById(patch) == 0) {
            throw new ConflictException("付款失败：状态已变更或不可见");
        }
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public UUID linkReimbursementVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        // 幂等，凭证已生成则直接返回既有凭证 id，重复点击或重试不产生第二张凭证
        if (r.getVoucherId() != null) {
            return r.getVoucherId();
        }
        if (!"PAID".equals(r.getStatus())) {
            throw new ConflictException("仅 PAID 状态报销单可生成过账凭证，当前 " + r.getStatus()
                    + "，已过账单据直接返回既有凭证");
        }
        // 科目映射缺失、期间已结账、预算阻断等异常一律向调用方传播，不做静默降级，
        // 显式动作由人触发，失败原因须对调用方可见
        UUID voucherId = voucherLinkService.linkExpense(id);
        // 凭证回填成功后再推进状态，CAS 守住 PAID，并发双击仅一方成功，无凭证则 DB 触发器拒绝过账
        if (reimbursementMapper.update(null, new LambdaUpdateWrapper<Reimbursement>()
                .eq(Reimbursement::getId, id)
                .eq(Reimbursement::getStatus, "PAID")
                .set(Reimbursement::getStatus, "POSTED")) == 0) {
            throw new ConflictException("报销单过账失败：状态已变更或不可见");
        }
        log.info("报销单 {} 生成过账凭证 {} 并推进 POSTED", id, voucherId);
        return voucherId;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void reverseReimbursement(UUID id, String reason) {
        RequestContext.requireWriteRole();
        Reimbursement r = requireReimbursement(id);
        if (!"POSTED".equals(r.getStatus())) {
            throw new ConflictException("仅已过账状态报销单可冲销（当前 " + r.getStatus()
                    + "，已冲销为终态不可重复冲销）");
        }
        // 账先冲、单后标：凭证已可见且未冲销时提前给出可读原因；凭证查询受行级策略约束
        // （如凭证仍为草稿且非本人创建、或凭证归属超出本会话数据范围时会返回空），
        // 此时不预判，直接落到数据库状态机的权威前置上终审——触发器以属主身份执行，
        // 不受行级策略限制，判定比应用侧所见更完整，避免误拒本可冲销的单据
        String voucherStatus = voucherLinkService.voucherStatus(r.getVoucherId());
        if (voucherStatus != null && !"REVERSED".equals(voucherStatus)) {
            throw new ConflictException("请先在总账冲销该报销单的凭证后再冲销报销单（凭证当前状态："
                    + voucherStatus + "）");
        }
        if (reimbursementMapper.update(null, new LambdaUpdateWrapper<Reimbursement>()
                .eq(Reimbursement::getId, id)
                .eq(Reimbursement::getStatus, "POSTED")
                .set(Reimbursement::getStatus, "REVERSED")) == 0) {
            throw new ConflictException("报销单冲销失败：状态已变更或不可见");
        }
        log.info("报销单 {} 冲销（凭证 {} 已冲销）{}", id, r.getVoucherId(),
                reason == null || reason.isBlank() ? "" : "，原因：" + reason);
    }

    // wf 挂接（私有）

    /**
     * 启动报销审批实例并回填 wf_instance_id（幂等：已挂接跳过）：按公司查 ACTIVE 报销
     * 审批流定义（code=expense_reimburse，seed_data 种子），经 wf.fn_start_instance
     * （SECURITY DEFINER）原子创建 RUNNING 实例并激活首个 APPROVE 节点任务集，首节点
     * 与处理人展开由引擎读定义 node_json 完成；定义缺失/未激活跳过。
     */
    private void startWfInstance(UUID id) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null || r.getWfInstanceId() != null) {
            return;                              // 幂等：已挂接跳过
        }
        WfDefinition def = wfDefinitionMapper.selectOne(new LambdaQueryWrapper<WfDefinition>()
                .eq(WfDefinition::getCompanyId, r.getCompanyId())
                .eq(WfDefinition::getCode, "expense_reimburse")
                .eq(WfDefinition::getStatus, "ACTIVE")
                .isNull(WfDefinition::getDeletedAt));
        if (def == null) {
            log.warn("报销单 {} 未找到公司 {} 的 ACTIVE 报销审批流定义（expense_reimburse），跳过 wf 挂接",
                    id, r.getCompanyId());
            return;
        }
        UUID instanceId = wfInstanceMapper.startInstance(r.getCompanyId(), def.getId(),
                "REIMBURSEMENT", r.getId(), RequestContext.requireUserId());
        if (instanceId == null) {
            log.warn("报销单 {} wf 实例发起返回空（业务单据已有实例或数据不可见），跳过回填", id);
            return;
        }
        Reimbursement patch = new Reimbursement();
        patch.setId(id);
        patch.setWfInstanceId(instanceId);
        if (reimbursementMapper.updateById(patch) == 0) {
            log.warn("报销单 {} wf 实例 {} 回填失败（wf_instance_id 留空）", id, instanceId);
        }
        log.info("报销单 {} 挂接 wf 实例 {} 完成", id, instanceId);
    }

    /**
     * 同步 wf 实例状态（记录型同步，同事务）：报销审批/驳回后置实例 RUNNING 转
     * APPROVED/REJECTED（wf.instances RLS 更新策略放行 RUNNING 转终态）；实例不可见/
     * 已非 RUNNING 0 行仅 WARN——wf 任务仍可沿自身审批流独立推进，互不阻塞。
     */
    private void syncWfStatus(UUID id, String status) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null || r.getWfInstanceId() == null) {
            return;                              // 未挂接（定义缺失/发起失败）无需同步
        }
        WfInstance patch = new WfInstance();
        patch.setId(r.getWfInstanceId());
        patch.setStatus(status);
        if (wfInstanceMapper.updateById(patch) == 0) {
            log.warn("报销单 {} 同步 wf 实例 {} 状态 {} 未生效（实例不可见或已非 RUNNING）",
                    id, r.getWfInstanceId(), status);
        }
    }

    // 私有方法

    /** 按 id 取报销单（RLS 空集则 40400；写路径显式报错） */
    private Reimbursement requireReimbursement(UUID id) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new NotFoundException("报销单不存在");
        }
        return r;
    }

    /** 明细预检：非空、金额 > 0、type/summary 非空（DB CHECK/uk 兜底） */
    private void assertLinesValid(List<ReimbursementLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BadRequestException("报销单至少需要一条明细");
        }
        for (ReimbursementLineRequest line : lines) {
            if (line.type() == null || line.type().isBlank()) {
                throw new BadRequestException("明细类型必填");
            }
            if (line.summary() == null || line.summary().isBlank()) {
                throw new BadRequestException("明细摘要必填");
            }
            if (money(line.amount()).signum() <= 0) {
                throw new BadRequestException("明细金额必须大于 0");
            }
        }
    }

    /** 明细金额合计（标度 2） */
    private BigDecimal sumLines(List<ReimbursementLineRequest> lines) {
        BigDecimal sum = ZERO;
        for (ReimbursementLineRequest line : lines) {
            sum = sum.add(money(line.amount()));
        }
        return sum;
    }

    /** 插入明细行（行号按序 1..n；company_id 显式写父单公司，保证 RLS WITH CHECK 成立） */
    private void insertLines(UUID reimbursementId, UUID companyId, List<ReimbursementLineRequest> lines) {
        int index = 1;
        for (ReimbursementLineRequest line : lines) {
            ReimbursementLine entity = new ReimbursementLine();
            entity.setId(UuidV7.next());
            entity.setReimbursementId(reimbursementId);
            entity.setCompanyId(companyId);
            entity.setLineNo(index);
            entity.setType(line.type().trim());
            entity.setAmount(money(line.amount()));
            entity.setTrxDate(line.trxDate());
            entity.setSummary(line.summary().trim());
            entity.setInvoiceFileId(line.invoiceFileId());
            lineMapper.insert(entity);
            index++;
        }
    }

    /** 软删报销单全部分录（更新/删除时先清理再重插/收尾；逐行经统一软删闸口） */
    private void softDeleteLines(UUID reimbursementId) {
        List<ReimbursementLine> lines = lineMapper.selectList(
                new LambdaQueryWrapper<ReimbursementLine>()
                        .eq(ReimbursementLine::getReimbursementId, reimbursementId)
                        .isNull(ReimbursementLine::getDeletedAt));
        for (ReimbursementLine line : lines) {
            authFunctionMapper.fnSoftDelete("expense", "reimbursement_lines", line.getId());
        }
    }

    /** 报销单详情（头 + 明细行；RLS 空集返回 null） */
    private ReimbursementDetailView buildDetail(UUID id) {
        ReimbursementView v = reimbursementMapper.selectDetail(id);
        if (v == null) {
            return null;
        }
        List<ReimbursementLineView> lines = lineMapper.selectByReimbursementId(id);
        Reimbursement r = reimbursementMapper.selectById(id);
        return new ReimbursementDetailView(v.id(), v.companyId(), v.applicantId(), v.applicantName(),
                v.deptId(), v.deptName(), v.title(), v.amount(), v.currency(), v.status(),
                v.wfInstanceId(), v.voucherId(),
                r == null ? null : r.getCreatedBy(), v.createdAt(), v.updatedAt(), lines);
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 币种规范化（CHAR(3)，缺省 CNY） */
    private String trimCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "CNY";
        }
        String c = currency.trim();
        if (c.length() != 3) {
            throw new BadRequestException("币种须为 3 位 ISO 代码");
        }
        return c.toUpperCase(Locale.ROOT);
    }

    /** 报销单创建幂等键：规范化请求内容 SHA-256 十六进制（同 body 必同键，改任一字段即新键）。
     * 键为 64 字符（列 varchar(64)），uk_reimb_idem 部分唯一索引防同 body 重复提交。 */
    private String reimbIdemKey(UUID companyId, UUID applicantId, ReimbursementRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(companyId).append('|')
          .append(applicantId).append('|')
          .append(req.title().trim()).append('|')
          .append(money(req.amount()).toPlainString()).append('|')
          .append(trimCurrency(req.currency())).append('|')
          .append(req.deptId()).append('|');
        for (ReimbursementLineRequest line : req.lines()) {
            sb.append(line.type().trim()).append(',')
              .append(money(line.amount()).toPlainString()).append(',')
              .append(line.trxDate()).append(',')
              .append(line.summary().trim()).append(',')
              .append(line.invoiceFileId() == null ? "" : line.invoiceFileId().trim())
              .append(';');
        }
        return sha256Hex(sb.toString());
    }

    /** SHA-256 十六进制（64 字符） */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按幂等键查既有报销单（同公司同申请人；uk_reimb_idem 唯一索引保证至多 1 行） */
    private Reimbursement findByIdemKey(UUID companyId, UUID applicantId, String idemKey) {
        return reimbursementMapper.selectOne(new LambdaQueryWrapper<Reimbursement>()
                .eq(Reimbursement::getCompanyId, companyId)
                .eq(Reimbursement::getApplicantId, applicantId)
                .eq(Reimbursement::getIdemKey, idemKey)
                .isNull(Reimbursement::getDeletedAt));
    }

    /**
     * {@inheritDoc}
     *
     * 写入链路：明细行主键经 RLS 收敛查询（不可见则 40400），随后附件归属校验（须已上传至该
     * 行名下：business_type=REIMBURSEMENT_LINE、business_id=行 id，否则 40001），随后回填
     * invoice_file_id（RLS UPDATE 策略行级收敛，影响 0 行即状态异常 40900）。
     */
    @Override
    @Transactional
    public void bindInvoiceFile(UUID lineId, UUID fileId) {
        RequestContext.requireWriteRole();
        if (lineId == null || fileId == null) {
            throw new BadRequestException("明细行 id 与附件 id 必填");
        }
        ReimbursementLine line = lineMapper.selectLineById(lineId);
        if (line == null) {
            throw new NotFoundException("报销明细不存在或无权访问");
        }
        DocumentView doc = fileService.getDocument(fileId); // RLS 收敛，越权/软删 40400
        if (!"REIMBURSEMENT_LINE".equals(doc.businessType()) || !lineId.equals(doc.businessId())) {
            throw new BadRequestException("附件未上传至该明细行名下，请先上传后绑定");
        }
        int rows = lineMapper.updateInvoiceFileId(lineId, fileId.toString());
        if (rows == 0) {
            throw new ConflictException("报销明细状态已变更，请刷新后重试");
        }
        log.info("报销明细发票附件绑定 lineId={} fileId={} by={}", lineId, fileId, RequestContext.userId());
    }
}
