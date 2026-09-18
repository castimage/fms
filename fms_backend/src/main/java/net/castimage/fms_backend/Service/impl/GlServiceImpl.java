package net.castimage.fms_backend.Service.impl;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.InternalServerException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.ChartAccountMapper;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Mapper.PeriodMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Mapper.VoucherLineMapper;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AccountRequest;
import net.castimage.fms_backend.Model.dto.RejectRequest;
import net.castimage.fms_backend.Model.dto.ReverseRequest;
import net.castimage.fms_backend.Model.dto.VoucherCreateRequest;
import net.castimage.fms_backend.Model.dto.VoucherLineRequest;
import net.castimage.fms_backend.Model.dto.VoucherUpdateRequest;
import net.castimage.fms_backend.Model.entity.ChartAccount;
import net.castimage.fms_backend.Model.entity.Period;
import net.castimage.fms_backend.Model.entity.User;
import net.castimage.fms_backend.Model.entity.Voucher;
import net.castimage.fms_backend.Model.entity.VoucherLine;
import net.castimage.fms_backend.Model.vo.AccountView;
import net.castimage.fms_backend.Model.vo.LedgerView;
import net.castimage.fms_backend.Model.vo.PeriodView;
import net.castimage.fms_backend.Model.vo.TrialBalanceRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceView;
import net.castimage.fms_backend.Model.vo.VoucherDetailView;
import net.castimage.fms_backend.Model.vo.VoucherGapView;
import net.castimage.fms_backend.Model.vo.VoucherLineView;
import net.castimage.fms_backend.Model.vo.VoucherListView;
import net.castimage.fms_backend.Service.GlService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 总账模块业务实现，submit/approve 为 Service 事务，Java 预检 + db 触发器终审，post/reverse 调 pg SECURITY DEFINER 函数，取号/状态推进/审计原子完成，规避 rls 与不可变触发器拒绝顺序，平衡校验双层，异常统一 40900 */
@Service
public class GlServiceImpl implements GlService {

    private static final Logger log = LoggerFactory.getLogger(GlServiceImpl.class);

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 金额零值，标度 2，比较/累加基准 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 科目类型白名单，db CHECK 同构 */
    private static final Set<String> ACCOUNT_TYPES = Set.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");

    /** 余额方向白名单 */
    private static final Set<String> DIRECTIONS = Set.of("DEBIT", "CREDIT");

    /** 科目状态白名单 */
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "DISABLED");

    /** csv 文件名字时间戳 */
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ChartAccountMapper chartAccountMapper;
    private final VoucherMapper voucherMapper;
    private final VoucherLineMapper voucherLineMapper;
    private final PeriodMapper periodMapper;
    private final UserMapper userMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final ParamMapper paramMapper;

    public GlServiceImpl(ChartAccountMapper chartAccountMapper, VoucherMapper voucherMapper,
                         VoucherLineMapper voucherLineMapper, PeriodMapper periodMapper,
                         UserMapper userMapper,
                         AuthFunctionMapper authFunctionMapper, ParamMapper paramMapper) {
        this.chartAccountMapper = chartAccountMapper;
        this.voucherMapper = voucherMapper;
        this.voucherLineMapper = voucherLineMapper;
        this.periodMapper = periodMapper;
        this.userMapper = userMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.paramMapper = paramMapper;
    }

    // 科目

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<AccountView> listAccounts(UUID companyId, String keyword) {
        // 软删过滤 + companyId/keyword 可选条件 + ORDER BY path（ltree 自然序）
        LambdaQueryWrapper<ChartAccount> wrapper = new LambdaQueryWrapper<ChartAccount>()
                .isNull(ChartAccount::getDeletedAt)
                .eq(companyId != null, ChartAccount::getCompanyId, companyId)
                .and(keyword != null && !keyword.isBlank(), kw ->
                        kw.like(ChartAccount::getCode, keyword)
                                .or()
                                .like(ChartAccount::getName, keyword))
                .orderByAsc(ChartAccount::getPath);
        return chartAccountMapper.selectList(wrapper).stream().map(this::toAccountView).toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public AccountView getAccount(UUID id) {
        return toAccountView(chartAccountMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public AccountView createAccount(AccountRequest req) {
        RequestContext.requireWriteRole();
        validateAccountEnum(req);
        UUID companyId = req.companyId();
        if (companyId == null) {
            throw new BadRequestException("companyId 必填（根科目需显式指定公司）");
        }
        if (req.code() == null || req.code().isBlank()) {
            throw new BadRequestException("科目编码必填");
        }
        String code = req.code().trim();

        ChartAccount parent = null;
        if (req.parentId() != null) {
            parent = chartAccountMapper.selectById(req.parentId());
            if (parent == null) {
                throw new NotFoundException("父科目不存在");
            }
            if (!companyId.equals(parent.getCompanyId())) {
                throw new ConflictException("父科目与目标公司不一致");
            }
        }

        ChartAccount acc = new ChartAccount();
        acc.setId(UuidV7.next());
        acc.setCompanyId(companyId);
        acc.setCode(code);
        acc.setName(req.name().trim());
        // path = 父.path + '.' + code（ltree 编码路径），
        acc.setPath(parent == null ? code : parent.getPath() + "." + code);
        acc.setParentId(req.parentId());
        acc.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        acc.setIsLeaf(true);
        acc.setAccountType(req.accountType());
        acc.setDirection(req.direction());
        acc.setIsForeignCurrency(Boolean.TRUE.equals(req.isForeignCurrency()));
        acc.setCurrency(trimCurrency(req.currency()));
        acc.setStatus(req.status() == null ? "ACTIVE" : req.status());
        chartAccountMapper.insertChartAccount(acc); // path 列 ltree 需 ::ltree 强转，BaseMapper 自动 INSERT 会 42804，

        // 父科目有子后 is_leaf 翻 false
        if (parent != null && Boolean.TRUE.equals(parent.getIsLeaf())) {
            ChartAccount patch = new ChartAccount();
            patch.setId(parent.getId());
            patch.setIsLeaf(false);
            chartAccountMapper.updateById(patch);
        }
        return toAccountView(acc);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public AccountView updateAccount(UUID id, AccountRequest req) {
        RequestContext.requireWriteRole();
        ChartAccount acc = requireAccount(id);
        ChartAccount patch = new ChartAccount();
        patch.setId(id);
        if (req.name() != null && !req.name().isBlank()) {
            patch.setName(req.name().trim());
        }
        if (req.accountType() != null) {
            validateEnum(req.accountType(), ACCOUNT_TYPES, "科目类型");
            patch.setAccountType(req.accountType());
        }
        if (req.direction() != null) {
            validateEnum(req.direction(), DIRECTIONS, "余额方向");
            patch.setDirection(req.direction());
        }
        if (req.isForeignCurrency() != null) {
            patch.setIsForeignCurrency(req.isForeignCurrency());
        }
        if (req.currency() != null) {
            patch.setCurrency(trimCurrency(req.currency()));
        }
        if (req.status() != null) {
            validateEnum(req.status(), ACCOUNT_STATUSES, "科目状态");
            patch.setStatus(req.status());
        }
        // code/path/parentId/level 禁改，uk_account_path 不可变，树结构仅可增删叶子，
        chartAccountMapper.updateById(patch);
        return toAccountView(chartAccountMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteAccount(UUID id) {
        RequestContext.requireWriteRole();
        ChartAccount acc = requireAccount(id);
        if (chartAccountMapper.selectCount(new LambdaQueryWrapper<ChartAccount>()
                .isNull(ChartAccount::getDeletedAt)
                .eq(ChartAccount::getParentId, id)) > 0) {
            throw new ConflictException("科目 " + acc.getCode() + " 存在子科目，禁止删除");
        }

        // 分录引用检查，跨实体计数
        if (voucherLineMapper.selectCount(new LambdaQueryWrapper<VoucherLine>()
                .isNull(VoucherLine::getDeletedAt)
                .eq(VoucherLine::getAccountId, id)) > 0) {
            throw new ConflictException("科目 " + acc.getCode() + " 已被凭证分录引用，禁止删除");
        }

        // 软删统一经 auth.fn_soft_delete 闸口，SECURITY DEFINER，rls 新行可见性检查豁免
        if (!authFunctionMapper.fnSoftDelete("gl", "chart_accounts", id)) {
            throw new ConflictException("科目删除失败，可能已不可见");
        }

        // 父科目无其余子科目时恢复 is_leaf=true
        if (acc.getParentId() != null
                && chartAccountMapper.selectCount(new LambdaQueryWrapper<ChartAccount>()
                .isNull(ChartAccount::getDeletedAt)
                .eq(ChartAccount::getParentId, acc.getParentId())
                .ne(ChartAccount::getId, id)) == 0) {
            ChartAccount patch = new ChartAccount();
            patch.setId(acc.getParentId());
            patch.setIsLeaf(true);
            chartAccountMapper.updateById(patch);
        }
    }

    // 凭证

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<VoucherListView> pageVouchers(long page, long size, String period, String status,
                                                    UUID companyId, String keyword, Boolean createdByMe) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        Page<VoucherListView> pg = new Page<>(pageNum, sizeNum);
        return PageResult.of(voucherMapper.selectVoucherPage(pg, period, status, companyId,
                createdByMe, keyword, RequestContext.userId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public VoucherDetailView getVoucher(UUID id) {
        // 他人 DRAFT / 越权公司则 rls 空集，返回 null（Controller 层 data:null，不泄露存在性）
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView createVoucher(VoucherCreateRequest req) {
        RequestContext.requireWriteRole();
        UUID userId = currentUserId();
        String period = normalizePeriod(req.period());
        requireOpenPeriod(req.companyId(), period);
        List<VoucherLineRequest> lines = req.lines();
        assertLinesValid(lines, req.companyId());
        // 缺省日期，当日与显式日期同口径受期间校验，防缺省值逃逸校验
        LocalDate voucherDate = req.voucherDate() == null ? LocalDate.now() : req.voucherDate();
        assertVoucherDateInPeriod(voucherDate, period);

        // 创建幂等，规范化请求内容 SHA-256 作幂等键（同人+公司+期间+同 body 重复提交仅 1 张，
        // 重复请求返回既有凭证=A 级幂等，并发竞态由 uk_voucher_idem 部分唯一索引兜底，23505 不产生重复数据）
        String idemKey = idemKeyOf(req, voucherDate);
        Voucher existing = findByIdemKey(req.companyId(), period, userId, idemKey);
        if (existing != null) {
            log.info("createVoucher 幂等命中：同人同公司同期间同内容已存在 id={}，返回既有凭证", existing.getId());
            return buildDetail(existing.getId());
        }

        Voucher voucher = new Voucher();
        voucher.setId(UuidV7.next());
        voucher.setCompanyId(req.companyId());
        voucher.setPeriod(period);
        voucher.setVoucherDate(voucherDate);
        voucher.setStatus("DRAFT");
        voucher.setSourceType("MANUAL");
        voucher.setIsPrivate(Boolean.TRUE.equals(req.isPrivate()));
        voucher.setDeptId(req.deptId());
        voucher.setCreatedBy(userId);
        voucher.setTotalDebit(sumDebit(lines));
        voucher.setTotalCredit(sumCredit(lines));
        voucher.setNote(req.note());
        voucher.setIdemKey(idemKey);
        try {
            voucherMapper.insert(voucher);
        } catch (DuplicateKeyException e) {
            // 并发同 body 竞态，唯一索引拒绝重复，PostgreSQL 唯一冲突不中止事务，回查返回先提交者
            Voucher winner = findByIdemKey(req.companyId(), period, userId, idemKey);
            if (winner != null) {
                log.info("createVoucher 并发幂等命中：id={} 已先提交，返回既有凭证", winner.getId());
                return buildDetail(winner.getId());
            }
            throw e;
        }

        insertLines(voucher.getId(), voucher.getCompanyId(), req.deptId(), lines);
        return buildDetail(voucher.getId());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView updateVoucher(UUID id, VoucherUpdateRequest req) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"DRAFT".equals(voucher.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态凭证可修改（当前 " + voucher.getStatus() + "）");
        }
        if (req.voucherDate() != null) {
            assertVoucherDateInPeriod(req.voucherDate(), voucher.getPeriod());
        }
        List<VoucherLineRequest> lines = req.lines();
        assertLinesValid(lines, voucher.getCompanyId());

        Voucher patch = new Voucher();
        patch.setVoucherDate(req.voucherDate() == null ? voucher.getVoucherDate() : req.voucherDate());
        patch.setDeptId(req.deptId() == null ? voucher.getDeptId() : req.deptId());
        patch.setIsPrivate(req.isPrivate() == null ? voucher.getIsPrivate() : req.isPrivate());
        patch.setNote(req.note() == null ? voucher.getNote() : req.note());

        // 期间不可变更，软删旧分录 + 全量重插，触发器同步凭证头总额，此处双保险，
        softDeleteLinesByVoucher(id);
        insertLines(id, voucher.getCompanyId(),
                req.deptId() == null ? voucher.getDeptId() : req.deptId(), lines);
        VoucherLineMapper.Sum sum = voucherLineMapper.selectSum(id);
        // 条件更新防并发竞争，仅 DRAFT 状态可写，与 deleteVoucher 同口径，
        // 并发 submit 先行则本更新落 0 行，40900，事务回滚软删+重插的分录
        if (voucherMapper.update(null, new LambdaUpdateWrapper<Voucher>()
                .eq(Voucher::getId, id)
                .eq(Voucher::getStatus, "DRAFT")
                .set(Voucher::getVoucherDate, patch.getVoucherDate())
                .set(Voucher::getDeptId, patch.getDeptId())
                .set(Voucher::getIsPrivate, patch.getIsPrivate())
                .set(Voucher::getNote, patch.getNote())
                .set(Voucher::getTotalDebit, sum.debit())
                .set(Voucher::getTotalCredit, sum.credit())) == 0) {
            throw new ConflictException("凭证更新失败：状态可能已变更");
        }
        return buildDetail(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"DRAFT".equals(voucher.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态凭证可删除（当前 " + voucher.getStatus() + "）");
        }
        softDeleteLinesByVoucher(id);
        // 软删仅 DRAFT（统一经 auth.fn_soft_delete 闸口）
        if (!authFunctionMapper.fnSoftDelete("gl", "vouchers", id)) {
            throw new ConflictException("凭证删除失败，状态可能已变更");
        }
    }

    /**
     * 提交凭证（状态机 DRAFT 转 SUBMITTED 第一步），仅制单人本人可提交，否则 40900，防
     * 他人代提交，Java 侧预检借贷平衡，按 db 现存分录复核，与触发器同步口径与期间
     * OPEN（db 触发器 trg_voucher_submit_check 终审，RAISE 统一映射 40900），通过后置
     * SUBMITTED。提交后凭证进入审批队列，不可再改删，updateById 影响行数为 0（状态已
     * 变更或 rls 不可见）按冲突处理。
     *
     * 状态机语义，DRAFT 是唯一可编辑状态，改/删仅 DRAFT，rls update 策略兜底，
     * 提交是"封存"动作，提交后不可再改删，只能沿 SUBMITTED 审核到 APPROVED、过账到 POSTED、再
     * REVERSED 单向推进。预检与终审双层，Java 预检，借贷平衡 assertBalanced + 期间
     * OPEN requireOpenPeriod 为第一层快速失败，db 触发器 trg_voucher_submit_check
     * 为最终裁决，防绕过 Service 直接 UPDATE，口径同源，预检通过但 updateById 影响
     * 0 行，并发提交/状态已变/rls 不可见一律 40900 冲突，不做重试猜测。非制单人
     * 提交，created_by != 当前用户在 Java 层即拒，防代提交绕过审批人职责。
     *
     * @param id 凭证 id
     * @return 提交后的凭证详情
     * @throws ConflictException 状态非法/非制单人/借贷不平/期间未开，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView submitVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"DRAFT".equals(voucher.getStatus())) {
            throw new ConflictException("仅 DRAFT 状态凭证可提交（当前 " + voucher.getStatus() + "）");
        }
        // submit 需当前用户创建，制单人提交
        if (!voucher.getCreatedBy().equals(RequestContext.userId())) {
            throw new ConflictException("仅制单人可提交凭证");
        }
        // Java 预检平衡（db 触发器 trg_voucher_submit_check 终审，RAISE 则 40900）
        assertBalanced(voucher.getId());
        requireOpenPeriod(voucher.getCompanyId(), voucher.getPeriod());

        Voucher patch = new Voucher();
        patch.setId(id);
        patch.setStatus("SUBMITTED");
        if (voucherMapper.updateById(patch) == 0) {
            throw new ConflictException("提交失败：状态已变更或不可见");
        }
        return buildDetail(id);
    }

    /**
     * 审批凭证（状态机 SUBMITTED 转 APPROVED 第二步），SoD 强制审批人不得为制单人
     * （录入≠审核，db 触发器复核 approved_by≠created_by 终审），通过后写 APPROVED 并
     * 记录审批人/审批时间。审批后凭证进入过账队列，updateById 影响行数为 0（状态已变更
     * 或 rls 不可见）按冲突处理。
     *
     * 状态机语义，仅 SUBMITTED 可审批，其余状态直接 40900 冲突，审批通过即
     * 记录 approved_by/approved_at 双审计字段，审批人/审批时间不可变，为过账与审计
     * 提供依据，SoD 互斥，制单 ↔ 审核在 Java 层与 db 触发器双层校验，Java 层
     * 拒绝同人审批，快速失败，db 触发器 approved_by ≠ created_by 终审，防绕过
     * Service 直写，与 OrgServiceImpl 的 SoD 检查同族语义。审批后凭证不可再退
     * 回 DRAFT，单向状态机，只能进入过账队列。
     *
     * @param id 凭证 id
     * @return 审批后的凭证详情
     * @throws ConflictException 状态非法或审批人=制单人，SoD，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView approveVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"SUBMITTED".equals(voucher.getStatus())) {
            throw new ConflictException("仅 SUBMITTED 状态凭证可审批（当前 " + voucher.getStatus() + "）");
        }
        // approve 需非创建人（SoD，录入≠审核，db 触发器复核 approved_by≠created_by）
        UUID userId = currentUserId();
        if (voucher.getCreatedBy().equals(userId)) {
            throw new ConflictException("审批职责分离（SoD）失败：审批人不得为制单人");
        }

        Voucher patch = new Voucher();
        patch.setId(id);
        patch.setStatus("APPROVED");
        patch.setApprovedBy(userId);
        patch.setApprovedAt(LocalDateTime.now());
        if (voucherMapper.updateById(patch) == 0) {
            throw new ConflictException("审批失败：状态已变更或不可见");
        }
        return buildDetail(id);
    }

    /**
     * 过账凭证（状态机 APPROVED 转 POSTED 第三步），取号、状态推进与审计由 pg SECURITY
     * DEFINER 函数 fn_post_voucher 原子完成，规避 rls 与不可变触发器拒绝顺序，期间
     * CLOSED/状态非法/借贷不平由 db 侧 RAISE，异常统一映射 40900。过账后凭证不可再
     * 修改，只能走冲销，凭证号由 db 侧按期间序列签发。
     *
     * 状态机语义，仅 APPROVED 可过账，Java 层预检，其余状态 40900，过账是
     * 记账生效点，Java 层只做状态预检，取号/状态推进/审计全部委托 pg SECURITY
     * DEFINER 函数 fn_post_voucher 原子完成，单一事务内完成，规避应用层 rls 对
     * 状态列写入的限制与不可变触发器拒绝顺序问题，期间 CLOSED（period 关闭后
     * 不可再过账）、借贷不平衡等终审条件在 db 侧 RAISE，统一映射 40900 返回。
     * 凭证号（voucher_no）由 db 侧按期间序列签发，应用层不参与，保证同期间内
     * 连续无重复。POSTED 后凭证冻结，不可改/删/退审，唯一去向是 REVERSED 冲销。
     *
     * @param id 凭证 id
     * @return 过账后的凭证详情
     * @throws ConflictException 状态非法或 db 终审拒绝，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView postVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"APPROVED".equals(voucher.getStatus())) {
            throw new ConflictException("仅 APPROVED 状态凭证可过账（当前 " + voucher.getStatus() + "）");
        }
        // 过账/取号/审计由 pg SECURITY DEFINER 函数原子完成（期间 CLOSED/状态非法/借贷不平 RAISE 映射 40900）
        voucherMapper.postVoucher(id);
        return buildDetail(id);
    }

    /**
     * 驳回凭证（状态机 SUBMITTED 转 REJECTED），驳回为审核侧决策，原因必填，写入
     * voucher_status_history.remark 与审计，SoD 驳回人不得为制单人（与审批同族，录入≠
     * 审核），状态推进/原因/审计由 pg SECURITY DEFINER 函数 fn_reject_voucher 原子完成，权限闸口 gl.voucher.reject + 状态机白名单触发器终审，异常 RAISE 统一映射 40900。
     * 驳回后凭证进入 REJECTED，列表可见/可筛选，下一批次经 REJECTED 转 DRAFT 退回
     * （POST /vouchers/{id}/revert，见 revertVoucher）修改后重新提交。
     *
     * 状态机语义，仅 SUBMITTED 可驳回，其余状态 Java 层预检直接 40900，驳回不改凭证
     * 账务，原分录/金额原样保留，仅状态推进并记录原因，状态历史 remark + 审计双通道，
     * 与冲销（POSTED 转 REVERSED）同族"新凭证抵账"不同，驳回是可逆的审核回退动作。
     *
     * @param id  已提交凭证 id（须 SUBMITTED）
     * @param req 驳回请求，reason 必填，btrim 非空
     * @return 驳回后的凭证详情
     * @throws ConflictException 状态非法/驳回人=制单人（SoD）/原因缺失或 db 终审拒绝，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView rejectVoucher(UUID id, RejectRequest req) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"SUBMITTED".equals(voucher.getStatus())) {
            throw new ConflictException("仅 SUBMITTED 状态凭证可驳回（当前 " + voucher.getStatus() + "）");
        }
        // 驳回职责分离（SoD），驳回人为审核侧，不得为制单人，与审批同族，db 函数复核终审
        if (voucher.getCreatedBy().equals(currentUserId())) {
            throw new ConflictException("驳回职责分离（SoD）失败：驳回人不得为制单人");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException("驳回原因必填");
        }
        // 驳回/原因/状态历史/审计由 pg SECURITY DEFINER 函数原子完成（权限闸口 + RAISE 映射 40900）
        voucherMapper.rejectVoucher(id, reason);
        return buildDetail(id);
    }

    /**
     * 退回（状态机 REJECTED 转 DRAFT），被驳回凭证退回草稿：updateVoucher/deleteVoucher
     * 仅放行 DRAFT，无退回路径时 REJECTED 凭证成为死数据且阻断所在期间结账，
     * fn_close_period 将 REJECTED 计入未完成凭证；退回后凭证回到可编辑 DRAFT，可修改后
     * 重新提交或删除。状态推进/状态历史/审计由 pg SECURITY DEFINER 函数 fn_revert_voucher
     * 原子完成（权限闸口 gl.voucher.revert + 状态机白名单触发器终审，白名单含 REJECTED
     * 转 DRAFT 且退回目标 DRAFT 豁免平衡/期间校验），异常 RAISE 统一映射 40900。
     *
     * 状态机语义，仅 REJECTED 可退回，其余状态 Java 层预检直接 40900，退回是可逆的审核
     * 回退动作，与驳回（SUBMITTED 转 REJECTED）互逆，构成驳回-退回闭环，下一批次可重新提交。
     *
     * @param id 被驳回凭证 id（须 REJECTED）
     * @return 退回后的凭证详情（DRAFT）
     * @throws ConflictException 状态非法或 db 终审拒绝，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView revertVoucher(UUID id) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"REJECTED".equals(voucher.getStatus())) {
            throw new ConflictException("仅 REJECTED 状态凭证可退回（当前 " + voucher.getStatus() + "）");
        }
        // 退回/状态历史/审计由 pg SECURITY DEFINER 函数原子完成（权限闸口 + RAISE 映射 40900）
        voucherMapper.revertVoucher(id);
        return buildDetail(id);
    }

    /**
     * 红字冲销（状态机 POSTED 转 REVERSED 末步），反向日期缺省当日、原因缺省空串，pg
     * SECURITY DEFINER 函数 fn_reverse_voucher 完成，新建借贷互换的反向凭证、置原凭证
     * REVERSED 并双向互链（reversal_of_voucher_id / reversed_by_voucher_id），返回反向
     * 凭证 id 后按此构建详情。冲销后原凭证冻结，反向凭证作为独立凭证走完整状态机。
     *
     * 状态机语义，仅 POSTED 可冲销，Java 层预检，其余状态 40900，冲销不改原
     * 凭证的账，原凭证冻结为 REVERSED，保留原状供审计，新建一张借贷方向互换的
     * 反向凭证承载冲销账务，两张凭证经 reversal_of_voucher_id / reversed_by_voucher_id
     * 双向互链（审计可追溯"这张冲销了哪张/被哪张冲销"），全部动作，新建反向凭证、
     * 原凭证状态推进、互链、审计在 pg SECURITY DEFINER 函数内原子完成，规避
     * rls 与不可变触发器拒绝顺序。反向凭证是独立凭证，DRAFT 起步，可继续走
     * 提交/审批/过账完整状态机，对冲销凭证本身的再冲销由该状态机自然承接。
     * 反向日期/原因均可空，缺省当日/空串。
     *
     * @param id  已过账凭证 id（须 POSTED）
     * @param req 冲销请求，reverseDate/reason 均可空
     * @return 新建反向凭证的详情
     * @throws ConflictException 状态非法，非 POSTED 或 db 终审拒绝，40900
     */
    /** {@inheritDoc} */
    @Override
    @Transactional
    public VoucherDetailView reverseVoucher(UUID id, ReverseRequest req) {
        RequestContext.requireWriteRole();
        Voucher voucher = requireVoucher(id);
        if (!"POSTED".equals(voucher.getStatus())) {
            throw new ConflictException("仅 POSTED 状态凭证可冲销（当前 " + voucher.getStatus() + "）");
        }
        LocalDate reverseDate = req.reverseDate() == null ? LocalDate.now() : req.reverseDate();
        String reason = req.reason() == null ? "" : req.reason();
        // 红字冲销由 pg 函数完成，建反向凭证，借贷互换后原凭证置 REVERSED，返回反向凭证 id
        UUID reversedId = voucherMapper.reverseVoucher(id, reverseDate, reason);
        return buildDetail(reversedId);
    }

    // 账簿

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<LedgerView> ledger(UUID companyId, String period, UUID accountId) {
        validatePeriod(period);
        return voucherLineMapper.selectLedger(companyId, normalizePeriod(period), accountId);
    }

    /**
     * {@inheritDoc}
     *
     * 镜像 rpt.exportCsv 白名单模式，period 白名单校验（YYYY-MM）校验通过后加 utf-8 BOM（\uFEFF）
     * 流式写 csv（表头 + 数据行 + flush，不 close 输出流，由 Servlet 容器接管），
     * 文件名 ledger_yyyyMMdd.csv，Content-Disposition attachment。方向列转中文，DEBIT=借/CREDIT=贷与前端列渲染同源，金额为有符号净额，正=借向、负=贷向。
     */
    @Override
    @Transactional(readOnly = true)
    public void exportLedgerCsv(UUID companyId, String period, UUID accountId, HttpServletResponse response) {
        String p = normalizePeriod(period); // 白名单校验，非法期间 40001，与 ledger 查询同构，
        try {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=ledger_" + LocalDate.now().format(FILE_DATE) + ".csv");
            PrintWriter writer = response.getWriter();
            writer.write('\uFEFF'); // utf-8 BOM，Excel 打开 csv 不乱码，
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
            printer.printRecord("科目编码", "科目名称", "方向", "期初余额", "本期借方", "本期贷方", "期末余额");
            for (LedgerView r : voucherLineMapper.selectLedger(companyId, p, accountId)) {
                printer.printRecord(r.accountCode(), r.accountName(),
                        "DEBIT".equals(r.direction()) ? "借" : "贷",
                        r.openingBalance(), r.periodDebit(), r.periodCredit(), r.closingBalance());
            }
            printer.flush();
            writer.flush();
        } catch (IOException e) {
            throw new InternalServerException("总账导出失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public TrialBalanceView trialBalance(UUID companyId, String period) {
        validatePeriod(period);
        List<TrialBalanceRow> rows = voucherLineMapper.selectTrialBalance(companyId, normalizePeriod(period));

        BigDecimal openingDebit = ZERO, openingCredit = ZERO;
        BigDecimal periodDebit = ZERO, periodCredit = ZERO;
        BigDecimal closingDebit = ZERO, closingCredit = ZERO;
        // 按符号拆借/贷两列，正=借、负=贷，
        for (TrialBalanceRow row : rows) {
            BigDecimal ob = money(row.openingBalance());
            BigDecimal cb = money(row.closingBalance());
            if (ob.signum() > 0) {
                openingDebit = openingDebit.add(ob);
            } else {
                openingCredit = openingCredit.add(ob.abs());
            }
            if (cb.signum() > 0) {
                closingDebit = closingDebit.add(cb);
            } else {
                closingCredit = closingCredit.add(cb.abs());
            }
            periodDebit = periodDebit.add(money(row.periodDebit()));
            periodCredit = periodCredit.add(money(row.periodCredit()));
        }
        return new TrialBalanceView(normalizePeriod(period), rows,
                openingDebit, openingCredit, periodDebit, periodCredit,
                closingDebit, closingCredit, closingDebit.compareTo(closingCredit) == 0);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<VoucherGapView> listVoucherGaps(UUID companyId, String period) {
        // 期间白名单校验，与 ledger/trial-balance 同口径，公司可为空=跨公司扫描，rls 收敛
        return voucherMapper.selectVoucherGaps(companyId,
                period == null ? null : normalizePeriod(period));
    }

    // 期间开关 / 期末调汇

    /**
     * {@inheritDoc}
     *
     * 结账（OPEN 转 CLOSED）委托 gl.fn_close_period 原子完成，完整性预检（期间内 DRAFT/
     * SUBMITTED/APPROVED 凭证计数 > 0 或 POSTED/REVERSED 断号则 RAISE）+ 置 CLOSED + 记
     * closed_by（函数内取 auth.current_uid，即本请求注入的 RequestContext 用户）+ 审计。
     * 预检内置于函数（p_force=false），Service 层不重复查询，避免与 db 侧口径漂移，
     * RAISE 消息含"结账/凭证"关键词则统一映射 40900。
     */
    @Override
    @Transactional(readOnly = true)
    public List<PeriodView> listPeriods(UUID companyId) {
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        return periodMapper.selectList(new LambdaQueryWrapper<Period>()
                        .eq(Period::getCompanyId, companyId)
                        .orderByAsc(Period::getPeriod))
                .stream()
                .map(p -> new PeriodView(p.getPeriod(), p.getStatus(),
                        p.getClosedBy(), p.getClosedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void closePeriod(UUID companyId, String period) {
        RequestContext.requireWriteRole();
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        String p = normalizePeriod(period);
        // p_force 恒 false，业务结账必须过完整性预检，p_force 仅限已知历史数据修复，由 DBA 直连调用
        periodMapper.closePeriod(companyId, p, false);
    }

    /**
     * {@inheritDoc}
     *
     * 反结账委托 gl.fn_reopen_period，原因必填，Java 预检快速失败，与驳回同款，二次确认
     * 开关 fms.force_reopen 未开启/存在更晚已结账期间则函数 RAISE，40900。反结账是受控的
     * 唯一逆向路径，审计留痕原因。
     */
    @Override
    @Transactional
    public void reopenPeriod(UUID companyId, String period, String reason) {
        RequestContext.requireWriteRole();
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        String p = normalizePeriod(period);
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new BadRequestException("反结账原因必填（审计留痕）");
        }

        // 二次确认，fn_reopen_period 校验 guc fms.force_reopen='on'（设计偏差 c，guc 缺失
        // 兜底 fail-closed），api 连接不携带该 guc，须读持久开关（um.params，管理员参数页
        // 可管理）后同事务 SET LOCAL 注入，未开启时函数 RAISE 拒绝，默认安全，防误反结账
        String flag = paramMapper.selectPublicParamValue("fms.force_reopen", "false");
        if (flag != null && flag.contains("true")) {
            periodMapper.setForceReopenOn();
        }
        periodMapper.reopenPeriod(companyId, p, r);
    }

    /**
     * {@inheritDoc}
     *
     * 期末调汇委托 gl.fn_fx_adjustment（手动触发，cron 每月 1 日 03:00 自动路径不变），
     * 外币科目期末余额 × 期末汇率与按 1:1 计价账面的差生成 DRAFT 调汇凭证草稿，幂等键
     * fx_adjustment:+period 防同期间重复生成，无汇率记录的公司/科目跳过（NOTICE），
     * 返回生成的调汇凭证数，0 = 无外币余额/无汇率/已生成过。
     */
    @Override
    @Transactional
    public int runFxAdjustment(UUID companyId, String period) {
        RequestContext.requireWriteRole();
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        String p = normalizePeriod(period);
        return periodMapper.runFxAdjustment(companyId, p, "CNY");
    }

    // 私有方法

    /** 当前用户（沙箱上下文由安全过滤器注入，缺失即未认证则 40100） */
    private UUID currentUserId() {
        return RequestContext.requireUserId();
    }

    /** 按 id 取科目（rls 空集则 40400，写路径显式报错） */
    private ChartAccount requireAccount(UUID id) {
        ChartAccount acc = chartAccountMapper.selectById(id);
        if (acc == null) {
            throw new NotFoundException("科目不存在");
        }
        return acc;
    }

    /** 按幂等键查既有凭证，同人+公司+期间，rls 对本人创建行可见，唯一索引保证至多 1 行 */
    private Voucher findByIdemKey(UUID companyId, String period, UUID userId, String idemKey) {
        return voucherMapper.selectOne(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getCompanyId, companyId)
                .eq(Voucher::getPeriod, period)
                .eq(Voucher::getCreatedBy, userId)
                .eq(Voucher::getIdemKey, idemKey)
                .isNull(Voucher::getDeletedAt));
    }

    /** 创建幂等键，规范化请求内容，与 insertLines 落库口径同源，行号缺省按序、币种缺省 CNY SHA-256 十六进制。
     * 同 body 必同键，改任一字段即新键，有意录入同内容不同凭据时变更为新键即可，键为 64 字符，列 varchar，64。 */
    private String idemKeyOf(VoucherCreateRequest req, LocalDate voucherDate) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.companyId()).append('|')
          .append(req.period().trim()).append('|')
          .append(voucherDate).append('|')
          .append(req.deptId() == null ? "-" : req.deptId()).append('|')
          .append(Boolean.TRUE.equals(req.isPrivate()) ? "1" : "0").append('|')
          .append(req.note() == null ? "" : req.note().trim()).append('|');
        int index = 1;
        for (VoucherLineRequest line : req.lines()) {
            sb.append(line.lineNo() == null ? index : line.lineNo()).append(',')
              .append(line.accountId()).append(',')
              .append(line.summary() == null ? "" : line.summary().trim()).append(',')
              .append(money(line.debit()).toPlainString()).append(',')
              .append(money(line.credit()).toPlainString()).append(',')
              .append(line.currency() == null || line.currency().isBlank() ? "CNY" : line.currency().trim()).append(',')
              .append(line.deptId() == null ? "-" : line.deptId()).append(',')
              .append(line.partnerId() == null ? "-" : line.partnerId()).append(';');
            index++;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按 id 取凭证（rls 空集则 40400，写路径显式报错） */
    private Voucher requireVoucher(UUID id) {
        Voucher voucher = voucherMapper.selectById(id);
        if (voucher == null) {
            throw new NotFoundException("凭证不存在");
        }
        return voucher;
    }

    /** 软删凭证全部分录，触发器重算凭证头总额，逐行经统一软删闸口 */
    private void softDeleteLinesByVoucher(UUID voucherId) {
        List<VoucherLine> lines = voucherLineMapper.selectList(
                new LambdaQueryWrapper<VoucherLine>()
                        .eq(VoucherLine::getVoucherId, voucherId)
                        .isNull(VoucherLine::getDeletedAt));
        for (VoucherLine line : lines) {
            authFunctionMapper.fnSoftDelete("gl", "voucher_lines", line.getId());
        }
    }

    /** 制单人登录名，UserMapper 侧 Wrapper 查询 */
    private String loginNameOf(UUID userId) {
        List<Object> objs = userMapper.selectObjs(new LambdaQueryWrapper<User>()
                .select(User::getLoginName)
                .eq(User::getId, userId));
        return objs.isEmpty() ? null : String.valueOf(objs.get(0));
    }

    /** 科目枚举校验（@Pattern 之外 Service 侧兜底，db CHECK 终审） */
    private void validateAccountEnum(AccountRequest req) {
        if (req.accountType() == null) {
            throw new BadRequestException("科目类型必填");
        }
        if (req.direction() == null) {
            throw new BadRequestException("余额方向必填");
        }
        validateEnum(req.accountType(), ACCOUNT_TYPES, "科目类型");
        validateEnum(req.direction(), DIRECTIONS, "余额方向");
        if (req.status() != null) {
            validateEnum(req.status(), ACCOUNT_STATUSES, "科目状态");
        }
    }

    private void validateEnum(String value, Set<String> allowed, String label) {
        if (value != null && !allowed.contains(value)) {
            throw new BadRequestException(label + "非法：" + value);
        }
    }

    /** 期间格式校验（YYYY-MM） */
    private void validatePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("期间格式非法：" + period + "（须为 YYYY-MM）");
        }
        int month = Integer.parseInt(period.substring(5, 7));
        if (month < 1 || month > 12) {
            throw new BadRequestException("期间月份非法：" + period);
        }
    }

    /** 期间规范化，去空白并校验 */
    private String normalizePeriod(String period) {
        if (period == null) {
            throw new BadRequestException("期间必填");
        }
        String p = period.trim();
        validatePeriod(p);
        return p;
    }

    /** 期间 OPEN 预检，db 触发器终审 */
    private void requireOpenPeriod(UUID companyId, String period) {
        Period p = periodMapper.selectOne(new LambdaQueryWrapper<Period>()
                .isNull(Period::getDeletedAt)
                .eq(Period::getCompanyId, companyId)
                .eq(Period::getPeriod, period));
        if (p == null) {
            throw new ConflictException("期间 " + period + " 不存在或不可见");
        }
        if (!"OPEN".equals(p.getStatus())) {
            throw new ConflictException("期间 " + period + " 已结账（status=" + p.getStatus() + "），禁止操作");
        }
    }

    /** 凭证日期须落在凭证期间内 */
    private void assertVoucherDateInPeriod(LocalDate date, String period) {
        if (date != null && !period.equals(date.toString().substring(0, 7))) {
            throw new BadRequestException("凭证日期 " + date + " 与期间 " + period + " 不一致");
        }
    }

    /** 分录预检，非空、单边，借/贷互斥、金额非负非双零、借贷合计相等、科目末级且 ACTIVE（db 触发器终审权威） */
    private void assertLinesValid(List<VoucherLineRequest> lines, UUID companyId) {
        if (lines == null || lines.isEmpty()) {
            throw new BadRequestException("凭证至少需要一条分录");
        }
        BigDecimal debitSum = ZERO;
        BigDecimal creditSum = ZERO;
        for (VoucherLineRequest line : lines) {
            BigDecimal d = money(line.debit());
            BigDecimal c = money(line.credit());
            if (d.signum() < 0 || c.signum() < 0) {
                throw new BadRequestException("分录金额不能为负数");
            }
            if (d.signum() > 0 && c.signum() > 0) {
                throw new BadRequestException("每行分录仅允许一个方向的金额（借/贷互斥）");
            }
            if (d.signum() == 0 && c.signum() == 0) {
                throw new BadRequestException("分录金额不能全为 0");
            }
            if (line.accountId() == null) {
                throw new BadRequestException("分录科目必填");
            }
            ChartAccount acc = chartAccountMapper.selectById(line.accountId());
            if (acc == null) {
                throw new BadRequestException("分录科目不存在：" + line.accountId());
            }
            if (!Boolean.TRUE.equals(acc.getIsLeaf())) {
                throw new ConflictException("科目 " + acc.getCode() + " 非末级科目，禁止录入分录");
            }
            if (!"ACTIVE".equals(acc.getStatus())) {
                throw new ConflictException("科目 " + acc.getCode() + " 已停用（status=" + acc.getStatus() + "）");
            }
            if (!companyId.equals(acc.getCompanyId())) {
                throw new BadRequestException("分录科目与凭证公司不一致");
            }
            debitSum = debitSum.add(d);
            creditSum = creditSum.add(c);
        }
        if (debitSum.compareTo(creditSum) != 0) {
            throw new ConflictException("凭证借贷不平衡：借方合计 " + debitSum + "，贷方合计 " + creditSum);
        }
    }

    /** 提交/更新前按 db 现存分录复核平衡，与触发器同步口径 */
    private void assertBalanced(UUID voucherId) {
        VoucherLineMapper.Sum sum = voucherLineMapper.selectSum(voucherId);
        BigDecimal d = money(sum.debit());
        BigDecimal c = money(sum.credit());
        if (d.compareTo(c) != 0) {
            throw new ConflictException("凭证借贷不平衡：借方合计 " + d + "，贷方合计 " + c);
        }
    }

    /** 插入分录，行号缺省按序 1..n，外币列缺省 CNY，分录部门缺省继承凭证头 */
    private void insertLines(UUID voucherId, UUID companyId, UUID headerDeptId, List<VoucherLineRequest> lines) {
        int index = 1;
        for (VoucherLineRequest line : lines) {
            VoucherLine entity = new VoucherLine();
            entity.setId(UuidV7.next());
            entity.setVoucherId(voucherId);
            entity.setLineNo(line.lineNo() == null ? index : line.lineNo());
            entity.setAccountId(line.accountId());
            entity.setSummary(line.summary() == null ? "" : line.summary());
            entity.setDebit(money(line.debit()));
            entity.setCredit(money(line.credit()));
            entity.setCurrency(line.currency() == null || line.currency().isBlank() ? "CNY" : line.currency().trim());
            entity.setDeptId(line.deptId() == null ? headerDeptId : line.deptId());
            entity.setPartnerId(line.partnerId());
            entity.setCompanyId(companyId);
            voucherLineMapper.insert(entity);
            index++;
        }
    }

    /** 凭证详情，头 + 分录 + 制单人登录名，rls 空集返回 null */
    private VoucherDetailView buildDetail(UUID id) {
        Voucher v = voucherMapper.selectById(id);
        if (v == null) {
            return null;
        }
        List<VoucherLineView> lines = voucherLineMapper.selectByVoucherId(id);
        return new VoucherDetailView(v.getId(), v.getCompanyId(), v.getVoucherNo(),
                v.getPeriod(), v.getVoucherDate(), v.getStatus(), v.getSourceType(),
                v.getIsPrivate(), v.getDeptId(), v.getCreatedBy(),
                v.getCreatedBy() == null ? null : loginNameOf(v.getCreatedBy()),
                v.getApprovedBy(), v.getApprovedAt(), v.getPostedBy(), v.getPostedAt(),
                v.getTotalDebit(), v.getTotalCredit(), v.getNote(),
                v.getReversalOfVoucherId(), v.getReversedByVoucherId(), lines);
    }

    /** 实体转视图 */
    private AccountView toAccountView(ChartAccount acc) {
        if (acc == null) {
            return null;
        }
        return new AccountView(acc.getId(), acc.getCompanyId(), acc.getCode(), acc.getName(),
                acc.getPath(), acc.getParentId(), acc.getLevel(), acc.getIsLeaf(),
                acc.getAccountType(), acc.getDirection(), acc.getIsForeignCurrency(),
                acc.getCurrency(), acc.getStatus());
    }

    private BigDecimal sumDebit(List<VoucherLineRequest> lines) {
        BigDecimal sum = ZERO;
        for (VoucherLineRequest line : lines) {
            sum = sum.add(money(line.debit()));
        }
        return sum;
    }

    private BigDecimal sumCredit(List<VoucherLineRequest> lines) {
        BigDecimal sum = ZERO;
        for (VoucherLineRequest line : lines) {
            sum = sum.add(money(line.credit()));
        }
        return sum;
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 币种规范化（CHAR，3） */
    private String trimCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String c = currency.trim();
        if (c.length() != 3) {
            throw new BadRequestException("币种须为 3 位 ISO 代码");
        }
        return c.toUpperCase(Locale.ROOT);
    }
}
