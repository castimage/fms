package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AssetMapper;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.DepreciationRunMapper;
import net.castimage.fms_backend.Mapper.UserOrgScopeMapper;
import net.castimage.fms_backend.Mapper.VoucherLineMapper;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AssetRequest;
import net.castimage.fms_backend.Model.dto.DisposeRequest;
import net.castimage.fms_backend.Model.entity.Asset;
import net.castimage.fms_backend.Model.entity.DepreciationRun;
import net.castimage.fms_backend.Model.entity.UserOrgScope;
import net.castimage.fms_backend.Model.entity.Voucher;
import net.castimage.fms_backend.Model.entity.VoucherLine;
import net.castimage.fms_backend.Model.vo.AssetView;
import net.castimage.fms_backend.Model.vo.DepreciationLine;
import net.castimage.fms_backend.Model.vo.DepreciationRunView;
import net.castimage.fms_backend.Service.FaService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 固定资产模块业务实现：折旧计提在 Service 侧计算（直线/双倍余额递减 + 计提额封顶，
 * 与 PG 函数 fa.fn_run_depreciation 同口径）并批量写 depreciation_runs（DRAFT 批次，
 * lines JSONB 由 fastjson2 组装，落库经 ::jsonb 显式转换）；计提同时生效落资产——
 * ACTIVE 转 DEPRECIATING（首次）+ 累计折旧累加（同一期间 DRAFT 重建幂等跳过防双计提）；
 * 双路径统一：计提后生成折旧凭证草稿（DRAFT，科目经 gl.fn_depr_accounts 按 um.params
 * fa.depr.*.account 解析，幂等键 fa_depreciation:+period 与 cron PG 路径互斥）并回填
 * 批次 voucher_id（前端可跳转凭证）；凭证生成失败容错跳过不阻断批次落库；
 * 确认走 DepreciationRunMapper.confirm 原子 UPDATE（NOT EXISTS 同公司同期间 CONFIRMED
 * 双闸 + 部分唯一索引 uk_depr_run 兜底 23505 映射 40900）；
 * 删除冻结：仅 ACTIVE 可软删，DEPRECIATING/DISPOSED/SCRAPPED 一律 40900
 * （trg_asset_immutable 对处置/报废终审 RAISE 映射 40900 兜底）。
 */
@Service
public class FaServiceImpl implements FaService {

    private static final Logger log = LoggerFactory.getLogger(FaServiceImpl.class);

    /** 金额标度（NUMERIC(18,2) 对齐） */
    private static final int MONEY_SCALE = 2;

    /** 金额零值（标度 2） */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 双倍余额递减系数（2.0/月数） */
    private static final BigDecimal DOUBLE_DECLINING_RATE = BigDecimal.valueOf(2);

    /** 折旧方法白名单（DB CHECK 同构） */
    private static final Set<String> DEPRECIATION_METHODS = Set.of("STRAIGHT_LINE", "DOUBLE_DECLINING");

    /** 可计提资产状态（处置/报废后不再纳入计提） */
    private static final Set<String> DEPRECIABLE_STATUSES = Set.of("ACTIVE", "DEPRECIATING");

    private final AssetMapper assetMapper;
    private final DepreciationRunMapper depreciationRunMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final UserOrgScopeMapper userOrgScopeMapper;
    private final VoucherMapper voucherMapper;
    private final VoucherLineMapper voucherLineMapper;

    public FaServiceImpl(AssetMapper assetMapper, DepreciationRunMapper depreciationRunMapper,
                         AuthFunctionMapper authFunctionMapper, UserOrgScopeMapper userOrgScopeMapper,
                         VoucherMapper voucherMapper, VoucherLineMapper voucherLineMapper) {
        this.assetMapper = assetMapper;
        this.depreciationRunMapper = depreciationRunMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.userOrgScopeMapper = userOrgScopeMapper;
        this.voucherMapper = voucherMapper;
        this.voucherLineMapper = voucherLineMapper;
    }

    // 资产卡片

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<AssetView> pageAssets(long page, long size, String keyword, String status) {
        Page<AssetView> pg = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return PageResult.of(assetMapper.selectAssetPage(pg, keyword, status));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public AssetView getAsset(UUID id) {
        return toAssetView(assetMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public AssetView createAsset(AssetRequest req) {
        RequestContext.requireWriteRole();
        UUID companyId = req.companyId() != null ? req.companyId() : resolveSessionCompany();
        if (companyId == null) {
            throw new BadRequestException("公司必填（当前会话无法唯一推导公司，请显式指定 companyId）");
        }
        String method = req.depreciationMethod() == null || req.depreciationMethod().isBlank()
                ? "STRAIGHT_LINE" : req.depreciationMethod().trim();
        validateEnum(method, DEPRECIATION_METHODS, "折旧方法");
        BigDecimal original = money(req.originalValue());
        BigDecimal residual = money(req.residualValue());
        if (residual.compareTo(original) > 0) {
            throw new BadRequestException("残值不能大于原值");
        }
        Asset asset = new Asset();
        asset.setId(UuidV7.next());
        asset.setCompanyId(companyId); // RLS can_access_org WITH CHECK 必需；缺省时从会话 scope 推导
        asset.setAssetCode(req.assetCode().trim());
        asset.setCategory(req.category().trim());
        asset.setOriginalValue(original);
        asset.setResidualValue(residual);
        asset.setLifeMonths(req.lifeMonths());
        asset.setDepreciationMethod(method);
        asset.setAccumulatedDepreciation(ZERO);
        asset.setStartDate(req.startDate() == null ? LocalDate.now() : req.startDate());
        asset.setStatus("ACTIVE");
        assetMapper.insert(asset); // uk_asset(company_id, asset_code) 冲突 23505 映射 40900
        return toAssetView(asset);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public AssetView updateAsset(UUID id, AssetRequest req) {
        RequestContext.requireWriteRole();
        Asset asset = requireAsset(id);
        if ("DISPOSED".equals(asset.getStatus()) || "SCRAPPED".equals(asset.getStatus())) {
            throw new ConflictException("已处置/报废资产禁止修改（不可变触发器终审）");
        }
        if (req.companyId() != null && !asset.getCompanyId().equals(req.companyId())) {
            throw new BadRequestException("资产公司不可变更");
        }
        Asset patch = new Asset();
        patch.setId(id);
        if (req.assetCode() != null && !req.assetCode().isBlank()) {
            patch.setAssetCode(req.assetCode().trim());
        }
        if (req.category() != null && !req.category().isBlank()) {
            patch.setCategory(req.category().trim());
        }
        if (req.residualValue() != null) {
            BigDecimal residual = money(req.residualValue());
            if (residual.compareTo(asset.getOriginalValue()) > 0) {
                throw new BadRequestException("残值不能大于原值");
            }
            patch.setResidualValue(residual);
        }
        if (req.lifeMonths() != null) {
            patch.setLifeMonths(req.lifeMonths());
        }
        if (req.depreciationMethod() != null && !req.depreciationMethod().isBlank()) {
            String method = req.depreciationMethod().trim();
            validateEnum(method, DEPRECIATION_METHODS, "折旧方法");
            patch.setDepreciationMethod(method);
        }
        if (req.startDate() != null) {
            patch.setStartDate(req.startDate());
        }
        // 原值仅 ACTIVE 可改（开始计提后冻结，防折旧基数漂移）；accounted 后其余财务参数同样冻结
        if (req.originalValue() != null) {
            if (!"ACTIVE".equals(asset.getStatus()) || hasDepreciated(asset)) {
                throw new ConflictException("已开始计提的资产禁止修改原值/折旧参数");
            }
            patch.setOriginalValue(money(req.originalValue()));
        }
        assetMapper.updateById(patch);
        return toAssetView(assetMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteAsset(UUID id) {
        RequestContext.requireWriteRole();
        Asset asset = requireAsset(id);
        // 冻结禁删：已开始计提（DEPRECIATING）/处置（DISPOSED）/报废（SCRAPPED）一律 40900，
        // 与 expense 仅 DRAFT 可删同构（状态机外冻结）；仅未计提的 ACTIVE 资产可软删清理。
        if (!"ACTIVE".equals(asset.getStatus())) {
            throw new ConflictException("仅 ACTIVE 资产可删除（已计提/处置/报废资产冻结，当前 " + asset.getStatus() + "）");
        }
        // 软删统一经 auth.fn_soft_delete 闸口（SECURITY DEFINER，RLS 新行可见性检查豁免）
        if (!authFunctionMapper.fnSoftDelete("fa", "assets", id)) {
            throw new ConflictException("资产删除失败，状态可能已变更");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>处置：仅 ACTIVE/DEPRECIATING 可处置（Java 预检快速失败），置 DISPOSED +
     * disposal_date（缺省当日）；trg_asset_immutable 终审（处置后禁改禁删 RAISE 映射 40900），
     * 后续计提 SELECT 不再纳入（status 白名单）。
     */
    @Override
    @Transactional
    public void dispose(UUID id, DisposeRequest req) {
        RequestContext.requireWriteRole();
        changeAssetStatus(id, req, "DISPOSED");
    }

    /**
     * {@inheritDoc}
     *
     * <p>报废：与处置同构，置 SCRAPPED + disposal_date；不可变触发器终审。
     */
    @Override
    @Transactional
    public void scrap(UUID id, DisposeRequest req) {
        RequestContext.requireWriteRole();
        changeAssetStatus(id, req, "SCRAPPED");
    }

    // 折旧计提

    /**
     * {@inheritDoc}
     *
     * <p>计提计算（Service 侧，与 PG 函数 fa.fn_run_depreciation 同口径）：
     * <ul>
     *   <li>直线法：monthly = round((原值 - 残值) / 月数, 2)</li>
     *   <li>双倍余额递减：monthly = round((原值 - 累计折旧) * 2 / 月数, 2)</li>
     *   <li>计提额封顶：amount = min(monthly, 原值 - 残值 - 累计折旧)（<=0 不计提）</li>
     * </ul>
     * 批次写：CONFIRMED 批次存在（同公司同期间）则跳过返回 0；DRAFT 旧批次软删重建（幂等，
     * 旧批次已计提资产本次跳过落资产防双计提）；计提生效落资产（ACTIVE 转 DEPRECIATING +
     * 累计折旧累加，net_value 生成列联动）；
     * lines JSONB（[{asset_id,asset_code,category,amount,method}]）fastjson2 组装批量落库
     * （::jsonb 显式转换）；
     * 计提范围 = status IN (ACTIVE,DEPRECIATING) 且 start_date 早于期间末日
     * 且累计折旧未提满（RLS can_access_org 收敛可见公司）。
     *
     * @return 参与计提的资产数
     */
    @Override
    @Transactional
    public long runDepreciation(UUID companyId, String period) {
        RequestContext.requireWriteRole();
        if (companyId == null) {
            throw new BadRequestException("公司必填");
        }
        String p = normalizePeriod(period);
        LocalDate periodEnd = YearMonth.parse(p).atEndOfMonth();
        // 防重复：该期间已存在 CONFIRMED 批次则跳过（部分唯一索引 uk_depr_run 兜底）
        DepreciationRun existing = depreciationRunMapper.selectByCompanyPeriod(companyId, p, "CONFIRMED");
        if (existing != null) {
            return 0;
        }
        // 幂等：DRAFT 旧批次软删重建（统一经 auth.fn_soft_delete 闸口）；
        // 先解析旧批次 lines 记录本期间已计提资产（重建时跳过，防同一期间重复 run 双计提）
        Set<UUID> appliedThisPeriod = new HashSet<>();
        List<DepreciationRun> oldDrafts = depreciationRunMapper.selectRuns(companyId, p);
        for (DepreciationRun old : oldDrafts) {
            if ("DRAFT".equals(old.getStatus())) {
                if (old.getLines() != null && !old.getLines().isBlank()) {
                    JSONArray arr = JSON.parseArray(old.getLines());
                    for (int i = 0; i < arr.size(); i++) {
                        String aid = arr.getJSONObject(i).getString("asset_id");
                        if (aid != null) {
                            appliedThisPeriod.add(UUID.fromString(aid));
                        }
                    }
                }
                authFunctionMapper.fnSoftDelete("fa", "depreciation_runs", old.getId());
            }
        }

        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getCompanyId, companyId)
                .isNull(Asset::getDeletedAt)
                .in(Asset::getStatus, DEPRECIABLE_STATUSES)
                .isNotNull(Asset::getStartDate)
                .orderByAsc(Asset::getId));
        JSONArray lines = new JSONArray();
        long count = 0;
        BigDecimal totalAmount = ZERO; // 本期计提合计（折旧凭证草稿借贷总额）
        for (Asset asset : assets) {
            // start_date 须早于期间末日且未提满（原值-残值-已计提 > 0）才可计提
            if (!asset.getStartDate().isBefore(periodEnd)) {
                continue;
            }
            BigDecimal remaining = asset.getOriginalValue().subtract(asset.getResidualValue())
                    .subtract(asset.getAccumulatedDepreciation());
            if (remaining.compareTo(ZERO) <= 0) {
                continue;
            }
            BigDecimal monthly;
            if ("DOUBLE_DECLINING".equals(asset.getDepreciationMethod())) {
                // 双倍余额递减：月计提 = 净值 * 2 / 月数（净值=原值-累计折旧）
                monthly = asset.getOriginalValue().subtract(asset.getAccumulatedDepreciation())
                        .multiply(DOUBLE_DECLINING_RATE)
                        .divide(BigDecimal.valueOf(asset.getLifeMonths()), 10, RoundingMode.HALF_UP);
            } else {
                // 直线法：月计提 = (原值 - 残值) / 月数
                monthly = asset.getOriginalValue().subtract(asset.getResidualValue())
                        .divide(BigDecimal.valueOf(asset.getLifeMonths()), 10, RoundingMode.HALF_UP);
            }
            monthly = monthly.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            // 计提额封顶：min(月计提, 尚可计提上限)
            BigDecimal amount = monthly.min(remaining.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            if (amount.compareTo(ZERO) > 0) {
                JSONObject line = new JSONObject();
                line.put("asset_id", asset.getId().toString());
                line.put("asset_code", asset.getAssetCode());
                line.put("category", asset.getCategory());
                line.put("amount", amount);
                line.put("method", asset.getDepreciationMethod());
                lines.add(line);
                // 计提生效落资产：ACTIVE 转 DEPRECIATING（首次）+ 累计折旧累加（净值生成列联动）；
                // 同一期间旧 DRAFT 重建时已计提资产跳过（防重复累加）
                if (!appliedThisPeriod.contains(asset.getId())) {
                    Asset patch = new Asset();
                    patch.setId(asset.getId());
                    patch.setAccumulatedDepreciation(asset.getAccumulatedDepreciation().add(amount));
                    if ("ACTIVE".equals(asset.getStatus())) {
                        patch.setStatus("DEPRECIATING");
                    }
                    assetMapper.updateById(patch);
                }
                totalAmount = totalAmount.add(amount);
                count++;
            }
        }

        DepreciationRun run = new DepreciationRun();
        run.setId(UuidV7.next());
        run.setCompanyId(companyId);
        run.setPeriod(p);
        run.setRunBy(RequestContext.userId());
        run.setStatus("DRAFT");
        run.setLines(lines.toJSONString());
        depreciationRunMapper.insertRun(run); // lines JSONB 经 ::jsonb 显式转换落库（防 42804）
        // 双路径统一：生成折旧凭证草稿（DRAFT，同 PG fa.fn_run_depreciation 口径）并回填批次
        createDepreciationVoucher(companyId, p, run.getId(), totalAmount);
        log.info("折旧计提：公司 {} 期间 {} 计提 {} 项资产（批次 {}）", companyId, p, count, run.getId());
        return count;
    }

    /**
     * 折旧凭证草稿生成 + 批次回填（双路径统一的 Java 侧；PG 侧 fa.fn_run_depreciation 同款）：
     * 借 折旧费用科目 / 贷 累计折旧科目（科目经 gl.fn_depr_accounts 按 um.params fa.depr.*.account
     * 解析，单一来源在 PG——职责角色无 um.params 表级授权）；幂等键 'fa_depreciation:'+period
     * 跨路径互斥（cron 已生成则跳过，查重经 SECURITY DEFINER 无视 RLS——DRAFT 仅创建人可见）；
     * 凭证为 DRAFT 草稿，期间 CLOSED/科目缺失等失败容错跳过（仅记录日志，不阻断计提批次落库）。
     */
    private void createDepreciationVoucher(UUID companyId, String period, UUID runId, BigDecimal total) {
        if (total == null || total.compareTo(ZERO) <= 0) {
            return; // 无计提额不生成凭证（与 PG 侧 v_total > 0 同口径）
        }
        try {
            if (depreciationRunMapper.idemVoucherExists(companyId, period, "fa_depreciation:" + period)) {
                log.info("折旧凭证草稿已存在（幂等键 fa_depreciation:{}，cron/PG 路径已生成），跳过", period);
                return;
            }
            String accJson = depreciationRunMapper.deprAccounts(companyId);
            if (accJson == null || accJson.isBlank()) {
                log.warn("折旧凭证草稿生成失败：公司 {} 科目解析为空", companyId);
                return;
            }
            JSONObject accs = JSON.parseObject(accJson);
            UUID depAccount = accs.getObject("depreciation", UUID.class);
            UUID faAccount = accs.getObject("accumulated", UUID.class);
            if (depAccount == null || faAccount == null) {
                log.warn("折旧凭证草稿生成失败：公司 {} 折旧科目（6602/1601 或 um.params 配置）缺失，跳过", companyId);
                return;
            }
            Voucher voucher = new Voucher();
            voucher.setId(UuidV7.next());
            voucher.setCompanyId(companyId);
            voucher.setPeriod(period);
            voucher.setVoucherDate(YearMonth.parse(period).atEndOfMonth()); // 期间末日（与 PG 侧一致）
            voucher.setStatus("DRAFT");
            voucher.setSourceType("DEPRECIATION");
            voucher.setCreatedBy(RequestContext.userId());
            voucher.setTotalDebit(total);
            voucher.setTotalCredit(total);
            voucher.setNote("折旧计提 " + period);
            voucher.setIdemKey("fa_depreciation:" + period); // 幂等键：跨路径同期间仅一张折旧凭证，
            voucherMapper.insert(voucher);
            insertDepreciationLine(voucher.getId(), companyId, 1, depAccount,
                    "折旧费用 " + period, total, ZERO);
            insertDepreciationLine(voucher.getId(), companyId, 2, faAccount,
                    "累计折旧 " + period, ZERO, total);
            depreciationRunMapper.linkVoucher(runId, voucher.getId());
            log.info("折旧凭证草稿已生成：批次 {} -> 凭证 {}（期间 {}）", runId, voucher.getId(), period);
        } catch (DuplicateKeyException e) {
            // 并发竞态：另一路径已先行插入（uk_voucher_idem 兜底），批次未回填属可接受（重跑会命中幂等键跳过）
            log.warn("折旧凭证草稿生成并发跳过（幂等键已存在）：{}", e.getMessage());
        } catch (Exception e) {
            // 容错：凭证生成失败不阻断计提批次落库（与 PG 侧 EXCEPTION + RAISE NOTICE 同款设计）
            log.warn("折旧凭证草稿生成失败（批次 {} 已记录，容错跳过）：{}", runId, e.getMessage());
        }
    }

    /** 折旧凭证分录插入（单边：借或贷；currency 缺省 CNY） */
    private void insertDepreciationLine(UUID voucherId, UUID companyId, int lineNo, UUID accountId,
                                        String summary, BigDecimal debit, BigDecimal credit) {
        VoucherLine line = new VoucherLine();
        line.setId(UuidV7.next());
        line.setVoucherId(voucherId);
        line.setLineNo(lineNo);
        line.setAccountId(accountId);
        line.setSummary(summary);
        line.setDebit(debit);
        line.setCredit(credit);
        line.setCurrency("CNY");
        line.setCompanyId(companyId);
        voucherLineMapper.insert(line);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<DepreciationRunView> pageDepreciationRuns(long page, long size, UUID companyId, String period) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        // period 先判空再 trim：eq(condition, col, value) 的参数在方法调用前即求值，
        // 原写法 period 为 null 时 period.trim() 必然 NPE（可选参数缺省即 500）
        String p = period == null ? null : period.trim();
        Page<DepreciationRun> pg = new Page<>(pageNum, sizeNum);
        IPage<DepreciationRun> result = depreciationRunMapper.selectPage(pg,
                new LambdaQueryWrapper<DepreciationRun>()
                        .isNull(DepreciationRun::getDeletedAt)
                        .eq(companyId != null, DepreciationRun::getCompanyId, companyId)
                        .eq(p != null && !p.isBlank(), DepreciationRun::getPeriod, p)
                        .orderByDesc(DepreciationRun::getCreatedAt, DepreciationRun::getId));
        return PageResult.of(result, this::toRunView);
    }

    /**
     * {@inheritDoc}
     *
     * <p>确认（DRAFT 转 CONFIRMED）：应用层 NOT EXISTS 同公司同期间 CONFIRMED 双闸 +
     * 部分唯一索引 uk_depr_run(company_id, period) WHERE status='CONFIRMED' 终审
     * （并发下 23505 映射 40900 由异常映射）；RLS update 策略（仅 DRAFT 可改）兜底恒 0 行则 40900。
     */
    @Override
    @Transactional
    public DepreciationRunView confirmDepreciation(UUID id) {
        RequestContext.requireWriteRole();
        DepreciationRun run = requireRun(id);
        if (depreciationRunMapper.confirm(id) == 0) {
            throw new ConflictException("确认失败：批次非 DRAFT 或同公司同期间已存在 CONFIRMED 批次");
        }
        run.setStatus("CONFIRMED");
        return toRunView(run);
    }

    // 私有方法

    /**
     * 会话公司推导：companyId 缺省时取当前用户唯一可见公司（um.user_org_scope，
     * 与 RLS can_access_org 判定的 fms.org_ids 同源，保证推导值必然可写）；
     * 0 个或多于 1 个可见公司时返回 null（调用方按 40001 拒绝，多公司用户须
     * 显式指定 companyId）。
     */
    private UUID resolveSessionCompany() {
        UUID uid = RequestContext.userId();
        if (uid == null) {
            return null;
        }
        List<UserOrgScope> scopes = userOrgScopeMapper.selectList(new LambdaQueryWrapper<UserOrgScope>()
                .eq(UserOrgScope::getUserId, uid)
                .isNull(UserOrgScope::getDeletedAt));
        if (scopes.size() == 1) {
            return scopes.get(0).getOrgId();
        }
        return null;
    }

    /** 按 id 取资产（RLS 空集则 40400） */
    private Asset requireAsset(UUID id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new NotFoundException("资产不存在");
        }
        return asset;
    }

    /** 按 id 取批次（RLS 空集则 40400） */
    private DepreciationRun requireRun(UUID id) {
        DepreciationRun run = depreciationRunMapper.selectById(id);
        if (run == null) {
            throw new NotFoundException("折旧批次不存在");
        }
        return run;
    }

    /** 处置/报废共置（Java 预检 ACTIVE/DEPRECIATING；trg_asset_immutable 终审） */
    private void changeAssetStatus(UUID id, DisposeRequest req, String targetStatus) {
        Asset asset = requireAsset(id);
        if (!DEPRECIABLE_STATUSES.contains(asset.getStatus())) {
            throw new ConflictException("仅 ACTIVE/DEPRECIATING 资产可处置（当前 " + asset.getStatus() + "）");
        }
        LocalDate date = req == null || req.disposeDate() == null ? LocalDate.now() : req.disposeDate();
        Asset patch = new Asset();
        patch.setId(id);
        patch.setStatus(targetStatus);
        patch.setDisposalDate(date);
        // 0 行（RLS/状态已变/不可变触发器拒绝）则 40900
        if (assetMapper.updateById(patch) == 0) {
            throw new ConflictException(targetStatus + " 失败：状态已变更或不可见");
        }
    }

    /** 是否已发生计提（累计折旧 > 0 则财务参数冻结） */
    private boolean hasDepreciated(Asset asset) {
        return asset.getAccumulatedDepreciation() != null
                && asset.getAccumulatedDepreciation().compareTo(ZERO) > 0;
    }

    /** 期间格式校验（YYYY-MM）并规范化 */
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

    /** 枚举校验（DB CHECK 终审兜底） */
    private void validateEnum(String value, Set<String> allowed, String label) {
        if (value != null && !allowed.contains(value)) {
            throw new BadRequestException(label + "非法：" + value);
        }
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 实体转资产视图 */
    private AssetView toAssetView(Asset asset) {
        if (asset == null) {
            return null;
        }
        return new AssetView(asset.getId(), asset.getCompanyId(), asset.getAssetCode(),
                asset.getCategory(), asset.getOriginalValue(), asset.getResidualValue(),
                asset.getLifeMonths(), asset.getDepreciationMethod(),
                asset.getAccumulatedDepreciation(), asset.getNetValue(), asset.getStartDate(),
                asset.getStatus(), asset.getDisposalDate(), asset.getCreatedAt());
    }

    /** 实体转批次视图（lines JSONB fastjson2 解析为明细列表） */
    private DepreciationRunView toRunView(DepreciationRun run) {
        if (run == null) {
            return null;
        }
        List<DepreciationLine> lines = new ArrayList<>();
        if (run.getLines() != null && !run.getLines().isBlank()) {
            JSONArray arr = JSON.parseArray(run.getLines());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject row = arr.getJSONObject(i);
                lines.add(new DepreciationLine(
                        row.getString("asset_id") == null ? null
                                : UUID.fromString(row.getString("asset_id")),
                        row.getString("asset_code"),
                        row.getString("category"),
                        row.getBigDecimal("amount"),
                        row.getString("method")));
            }
        }
        return new DepreciationRunView(run.getId(), run.getCompanyId(), run.getPeriod(),
                run.getRunBy(), run.getStatus(), run.getVoucherId(), lines,
                run.getCreatedAt(), run.getUpdatedAt());
    }
}
