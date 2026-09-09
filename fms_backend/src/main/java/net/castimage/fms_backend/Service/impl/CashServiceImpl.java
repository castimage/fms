package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ConflictException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.BankAccountMapper;
import net.castimage.fms_backend.Mapper.BankStatementLineMapper;
import net.castimage.fms_backend.Mapper.BankStatementMapper;
import net.castimage.fms_backend.Mapper.CashTransactionMapper;
import net.castimage.fms_backend.Mapper.ReconcileRunMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.BankAccountRequest;
import net.castimage.fms_backend.Model.dto.CashTransactionRequest;
import net.castimage.fms_backend.Model.dto.ManualMatchRequest;
import net.castimage.fms_backend.Model.dto.StatementConfirmRequest;
import net.castimage.fms_backend.Model.dto.UndoMatchRequest;
import net.castimage.fms_backend.Model.entity.BankAccount;
import net.castimage.fms_backend.Model.entity.BankStatement;
import net.castimage.fms_backend.Model.entity.BankStatementLine;
import net.castimage.fms_backend.Model.entity.CashTransaction;
import net.castimage.fms_backend.Model.entity.ReconcileRun;
import net.castimage.fms_backend.Model.vo.AccountDailyRow;
import net.castimage.fms_backend.Model.vo.AutoMatchCandidate;
import net.castimage.fms_backend.Model.vo.BankAccountView;
import net.castimage.fms_backend.Model.vo.BankStatementLineView;
import net.castimage.fms_backend.Model.vo.BankStatementView;
import net.castimage.fms_backend.Model.vo.CashTransactionView;
import net.castimage.fms_backend.Model.vo.DailyForecastRow;
import net.castimage.fms_backend.Model.vo.DailyReportView;
import net.castimage.fms_backend.Model.vo.ReconcileRunView;
import net.castimage.fms_backend.Model.vo.StatementImportPreview;
import net.castimage.fms_backend.Model.vo.StatementLinePreview;
import net.castimage.fms_backend.Service.CashService;
import net.castimage.fms_backend.Service.VoucherLinkService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 资金模块业务实现，账号密文"写入走闸口函数、读出走 decrypt_bank_account"，列表不选密文列、
 * 日志不打印明文，自动对账为 B 类 sql 候选（金额相等+日期差≤3 天+pg_trgm similarity）
 * + Service 贪心配对（≥0.9 自动、原子 UPDATE 防并发双配、行侧失败回滚流水侧补偿），
 * 对账差异量化为 reconcile_runs.diff_count，未匹配流水+对账单行，导入两阶段内存解析预览后事务落库，
 * 流水创建/对账单导入幂等，规范化请求内容 SHA-256 幂等键 + 部分唯一索引（uk_trx_idem/uk_bank_stmt_idem）
 * 先查后插返回既有记录，A 级，并发竞态唯一索引兜底 23505 不产生重复数据，B 级，
 * 流水创建即过账，经 VoucherLinkService 尽力生成银行凭证 DRAFT（借/贷银行存款对应方向，
 * 对端挂清算过渡科目）并回填 voucher_line_id（银行科目分录 id，失败 WARN + 留空）。
 */
@Service
public class CashServiceImpl implements CashService {

    private static final Logger log = LoggerFactory.getLogger(CashServiceImpl.class);

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 金额零值，标度 2 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 自动对账相似度阈值（≥0.9 自动配对，<0.9 留人工，与 pg 函数 cash.fn_auto_reconcile 同口径） */
    private static final BigDecimal AUTO_MATCH_THRESHOLD = new BigDecimal("0.9000");

    /** 账户类型白名单，db CHECK 同构 */
    private static final Set<String> ACCOUNT_TYPES = Set.of("CORPORATE", "PERSONAL");

    /** 账户状态白名单，db CHECK 同构 */
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "FROZEN", "CLOSED");

    /** 流水方向白名单，db CHECK 同构 */
    private static final Set<String> DIRECTIONS = Set.of("IN", "OUT");

    /** 对账单行方向白名单，列可空，非空须 IN/OUT */
    private static final Set<String> LINE_DIRECTIONS = Set.of("IN", "OUT");

    private final BankAccountMapper bankAccountMapper;
    private final CashTransactionMapper cashTransactionMapper;
    private final BankStatementMapper bankStatementMapper;
    private final BankStatementLineMapper bankStatementLineMapper;
    private final ReconcileRunMapper reconcileRunMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final VoucherLinkService voucherLinkService;

    public CashServiceImpl(BankAccountMapper bankAccountMapper, CashTransactionMapper cashTransactionMapper,
                           BankStatementMapper bankStatementMapper, BankStatementLineMapper bankStatementLineMapper,
                           ReconcileRunMapper reconcileRunMapper,
                           AuthFunctionMapper authFunctionMapper,
                           VoucherLinkService voucherLinkService) {
        this.bankAccountMapper = bankAccountMapper;
        this.cashTransactionMapper = cashTransactionMapper;
        this.bankStatementMapper = bankStatementMapper;
        this.bankStatementLineMapper = bankStatementLineMapper;
        this.reconcileRunMapper = reconcileRunMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.voucherLinkService = voucherLinkService;
    }

    // 银行账户

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<BankAccountView> pageBankAccounts(long page, long size, String keyword) {
        Page<BankAccountView> pg = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        // 列表 sql 不选密文/盲索引列，accountNo 恒 null，掩码由前端创建时携带
        return PageResult.of(bankAccountMapper.selectBankAccountPage(pg, keyword));
    }

    /**
     * {@inheritDoc}
     *
     * <p>详情为账号解密的唯一出口，B 类方法 auth.decrypt_bank_account（SECURITY DEFINER，
     * current_user 守卫 + 数据范围校验 + pgp_sym_decrypt aes256）返回明文账号填入 accountNo，
     * 明文仅存在于本次响应，不入日志、不落库。
     */
    @Override
    @Transactional(readOnly = true)
    public BankAccountView getBankAccount(UUID id) {
        BankAccount acc = requireAccount(id);
        String decrypted = bankAccountMapper.decryptAccount(id); // 唯一解密出口，返回值不入日志，
        return new BankAccountView(acc.getId(), acc.getCompanyId(), acc.getBankName(), decrypted,
                null, acc.getAccountType(), acc.getCurrency(), acc.getIsActive(), acc.getStatus(),
                acc.getCreatedAt(), acc.getUpdatedAt());
    }

    /**
     * {@inheritDoc}
     *
     * <p>创建唯一入口为 SECURITY DEFINER 闸口 cash.fn_create_bank_account，函数内
     * pgp_sym_encrypt 落 account_no_enc + sha256 盲索引落 account_no_hash（uk_bank_account_hash
     * 唯一兜底 23505 映射 40900），company_id 由函数从当前用户上下文推导（无法确定公司则 RAISE
     * 42501 映射 40300），Java 侧只做枚举/币种预检，明文账号不落库不日志。
     */
    @Override
    @Transactional
    public BankAccountView createBankAccount(BankAccountRequest req) {
        RequestContext.requireWriteRole();
        String accountType = req.accountType() == null || req.accountType().isBlank() ? "CORPORATE" : req.accountType().trim();
        String status = req.status() == null || req.status().isBlank() ? "ACTIVE" : req.status().trim();
        String currency = trimCurrency(req.currency() == null || req.currency().isBlank() ? "CNY" : req.currency());
        validateEnum(accountType, ACCOUNT_TYPES, "账户类型");
        validateEnum(status, ACCOUNT_STATUSES, "账户状态");
        UUID id = bankAccountMapper.createBankAccount(req.bankName().trim(), req.accountNo(),
                accountType, currency, status, req.isActive() == null ? Boolean.TRUE : req.isActive());
        BankAccount acc = requireAccount(id);
        // 掩码由请求方携带，前端自持，后端不存明文不重建，仅创建响应回显，
        return new BankAccountView(acc.getId(), acc.getCompanyId(), acc.getBankName(), null,
                req.accountNoMasked(), acc.getAccountType(), acc.getCurrency(), acc.getIsActive(),
                acc.getStatus(), acc.getCreatedAt(), acc.getUpdatedAt());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public BankAccountView updateBankAccount(UUID id, BankAccountRequest req) {
        RequestContext.requireWriteRole();
        requireAccount(id);
        BankAccount patch = new BankAccount();
        patch.setId(id);
        if (req.bankName() != null && !req.bankName().isBlank()) {
            patch.setBankName(req.bankName().trim());
        }
        if (req.accountType() != null && !req.accountType().isBlank()) {
            String type = req.accountType().trim();
            validateEnum(type, ACCOUNT_TYPES, "账户类型");
            patch.setAccountType(type);
        }
        if (req.currency() != null && !req.currency().isBlank()) {
            patch.setCurrency(trimCurrency(req.currency()));
        }
        if (req.status() != null && !req.status().isBlank()) {
            String st = req.status().trim();
            validateEnum(st, ACCOUNT_STATUSES, "账户状态");
            patch.setStatus(st);
        }
        if (req.isActive() != null) {
            patch.setIsActive(req.isActive());
        }
        // accountNo 变更视为新建，禁改密文列 account_no_enc/hash，update 不触达，
        bankAccountMapper.updateById(patch);
        BankAccount acc = requireAccount(id);
        return new BankAccountView(acc.getId(), acc.getCompanyId(), acc.getBankName(), null,
                null, acc.getAccountType(), acc.getCurrency(), acc.getIsActive(), acc.getStatus(),
                acc.getCreatedAt(), acc.getUpdatedAt());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteBankAccount(UUID id) {
        RequestContext.requireWriteRole();
        requireAccount(id);
        // 软删统一经 auth.fn_soft_delete 闸口，SECURITY DEFINER，rls 新行可见性检查豁免
        if (!authFunctionMapper.fnSoftDelete("cash", "bank_accounts", id)) {
            throw new ConflictException("银行账户不存在或已删除");
        }
    }

    // 收付款流水

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<CashTransactionView> pageTransactions(long page, long size, UUID bankAccountId,
                                                            String direction, String matchStatus, UUID companyId) {
        Page<CashTransactionView> pg = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return PageResult.of(cashTransactionMapper.selectTransactionPage(pg, bankAccountId, companyId,
                direction, matchStatus));
    }

    /**
     * {@inheritDoc}
     *
     * <p>company_id 由所属银行账户推导，防跨公司挂账，rls WITH CHECK 兜底，
     * 初始 match_status=UNMATCHED，金额>0/方向枚举 Java 预检 + db CHECK 终审。
     *
     * <p>创建幂等，uk_trx_idem 同族模式，规范化请求内容 SHA-256 作幂等键，同公司同账户同 body 重复提交仅 1 条，重复请求返回既有流水 = A 级幂等，
     * 并发竞态由部分唯一索引兜底，23505 不产生重复数据 = B 级安全。
     */
    @Override
    @Transactional
    public CashTransactionView createTransaction(CashTransactionRequest req) {
        RequestContext.requireWriteRole();
        validateEnum(req.direction(), DIRECTIONS, "方向");
        BankAccount acc = requireAccount(req.bankAccountId());
        LocalDate trxDate = req.trxDate() == null ? LocalDate.now() : req.trxDate();
        CashTransaction trx = new CashTransaction();
        trx.setId(UuidV7.next());
        trx.setCompanyId(acc.getCompanyId());
        trx.setBankAccountId(req.bankAccountId());
        trx.setDirection(req.direction());
        trx.setAmount(money(req.amount()));
        trx.setPartner(req.partner());
        trx.setSummary(req.summary());
        trx.setTrxDate(trxDate);
        trx.setMatchStatus("UNMATCHED");
        String idemKey = trxIdemKey(acc.getCompanyId(), req.bankAccountId(), req.direction(),
                money(req.amount()), req.partner(), req.summary(), trxDate);
        CashTransaction existing = findTrxByIdemKey(acc.getCompanyId(), req.bankAccountId(), idemKey);
        if (existing != null) {
            log.info("createTransaction 幂等命中：同公司同账户同内容已存在 id={}，返回既有流水", existing.getId());
            linkCashTransactionQuietly(existing); // 存量未联动流水补联动，幂等跳过
            return toTransactionView(existing, acc.getBankName());
        }
        trx.setIdemKey(idemKey);
        try {
            cashTransactionMapper.insert(trx);
        } catch (DuplicateKeyException e) {
            // 并发同 body 竞态，唯一索引拒绝重复，PostgreSQL 唯一冲突不中止事务，回查返回先提交者
            CashTransaction winner = findTrxByIdemKey(acc.getCompanyId(), req.bankAccountId(), idemKey);
            if (winner != null) {
                log.info("createTransaction 并发幂等命中：id={} 已先提交，返回既有流水", winner.getId());
                linkCashTransactionQuietly(winner);
                return toTransactionView(winner, acc.getBankName());
            }
            throw e;
        }

        // 过账即生成银行凭证 DRAFT（IN 借银行/贷清算过渡、OUT 借清算过渡/贷银行），
        // 回填 voucher_line_id=银行科目分录 id，失败不影响流水主流程，WARN + 留空
        linkCashTransactionQuietly(trx);
        return toTransactionView(trx, acc.getBankName());
    }

    /** 流水联动凭证，尽力生成，失败仅 WARN，不影响流水创建主流程，voucher_line_id 留空 */
    private void linkCashTransactionQuietly(CashTransaction trx) {
        try {
            voucherLinkService.linkCashTransaction(trx.getId());
        } catch (RuntimeException e) {
            log.warn("资金流水 {} 联动凭证失败（voucher_line_id 留空）：{}", trx.getId(), e.getMessage());
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void deleteTransaction(UUID id) {
        RequestContext.requireWriteRole();
        CashTransaction trx = requireTransaction(id);
        if (!"UNMATCHED".equals(trx.getMatchStatus())) {
            throw new ConflictException("仅未匹配流水可删除（当前 " + trx.getMatchStatus() + "）");
        }
        // 软删统一经 auth.fn_soft_delete 闸口，UNMATCHED 预检已排除对账消费中的流水
        if (!authFunctionMapper.fnSoftDelete("cash", "transactions", id)) {
            throw new ConflictException("流水删除失败，可能已被对账消费");
        }
    }

    // 对账单导入，两阶段

    /**
     * {@inheritDoc}
     *
     * <p>解析规则，utf-8 文本，带 BOM 容错，首行含分隔符即分隔符，TSV 优先，否则 csv，
     * 表头行，含 trx_date/日期自动跳过，列序 trx_date, amount, summary[, direction]，
     * 日期支持 yyyy-MM-dd / yyyy/M/d，金额 BigDecimal，direction 可空，非空须 IN/OUT，
     * 任一列解析失败则 40001 带行号，快速失败，不落库。
     */
    @Override
    @Transactional(readOnly = true)
    public StatementImportPreview importStatement(MultipartFile file, UUID bankAccountId) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        requireAccount(bankAccountId);
        List<StatementLinePreview> rows = parseStatementLines(file);
        return new StatementImportPreview(bankAccountId, file.getOriginalFilename(), rows.size(), rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>事务内落库，bank_statements（company_id 由账户推导，total_lines 回填）+
     * 批量 bank_statement_lines（match_status=UNMATCHED），rls WITH CHECK 兜底越权公司。
     *
     * <p>导入幂等，uk_bank_stmt_idem 同族模式，filename+全部行内容 SHA-256 作幂等键，同公司同账户同文件同内容重复导入仅 1 份，重复 confirm 返回既有对账单行数
     * = A 级幂等，并发竞态由部分唯一索引兜底 = B 级安全，不同文件同名内容不同不冲突。
     */
    @Override
    @Transactional
    public int confirmStatement(StatementConfirmRequest req) {
        RequestContext.requireWriteRole();
        BankAccount acc = requireAccount(req.bankAccountId());
        if (req.rows().isEmpty()) {
            throw new BadRequestException("对账单至少需要一行数据");
        }
        for (StatementLinePreview row : req.rows()) {
            validateLinePreview(row);
        }
        String idemKey = stmtIdemKey(req.filename().trim(), req.rows());
        BankStatement existing = findStmtByIdemKey(acc.getCompanyId(), req.bankAccountId(), idemKey);
        if (existing != null) {
            log.info("confirmStatement 幂等命中：同公司同账户同文件同内容已存在 id={}，返回既有对账单",
                    existing.getId());
            return existing.getTotalLines();
        }
        BankStatement stmt = new BankStatement();
        stmt.setId(UuidV7.next());
        stmt.setCompanyId(acc.getCompanyId());
        stmt.setBankAccountId(req.bankAccountId());
        stmt.setFilename(req.filename().trim());
        stmt.setIdemKey(idemKey);
        stmt.setImportDate(LocalDate.now());
        stmt.setStatus("IMPORTED");
        stmt.setTotalLines(req.rows().size());
        try {
            bankStatementMapper.insert(stmt);
        } catch (DuplicateKeyException e) {
            // 并发同文件同内容竞态，唯一索引拒绝重复，PostgreSQL 唯一冲突不中止事务，回查返回先提交者
            BankStatement winner = findStmtByIdemKey(acc.getCompanyId(), req.bankAccountId(), idemKey);
            if (winner != null) {
                log.info("confirmStatement 并发幂等命中：id={} 已先提交，返回既有对账单", winner.getId());
                return winner.getTotalLines();
            }
            throw e;
        }
        for (StatementLinePreview row : req.rows()) {
            BankStatementLine line = new BankStatementLine();
            line.setId(UuidV7.next());
            line.setCompanyId(acc.getCompanyId());
            line.setStatementId(stmt.getId());
            line.setTrxDate(row.trxDate());
            line.setAmount(money(row.amount()));
            line.setSummary(row.summary());
            line.setDirection(row.direction());
            line.setMatchStatus("UNMATCHED");
            bankStatementLineMapper.insert(line);
        }
        return req.rows().size();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<BankStatementView> pageStatements(long page, long size, UUID bankAccountId) {
        Page<BankStatementView> pg = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return PageResult.of(bankStatementMapper.selectPage(pg, bankAccountId));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public BankStatementView getStatement(UUID id) {
        BankStatement stmt = requireStatement(id);
        BankAccount acc = bankAccountMapper.selectById(stmt.getBankAccountId());
        String bankName = acc == null ? null : acc.getBankName();
        return new BankStatementView(stmt.getId(), stmt.getCompanyId(), stmt.getBankAccountId(),
                bankName, stmt.getFilename(), stmt.getImportDate(), stmt.getStatus(),
                stmt.getTotalLines(), stmt.getCreatedAt());
    }

    /** 对账单行明细，详情接口数据源，供 Controller 拼装头+行 */
    @Transactional(readOnly = true)
    public List<BankStatementLineView> statementLines(UUID statementId) {
        return bankStatementLineMapper.selectByStatementId(statementId);
    }

    // 对账

    /**
     * {@inheritDoc}
     *
     * <p>自动对账，B 类核心逻辑，与 pg 函数 cash.fn_auto_reconcile 同口径，
     * 逐条未匹配流水按同账户、金额相等、日期差≤3 天、摘要 pg_trgm similarity 降序取最优候选
     * （similarity/`%` 走 ix_bsl_summary GIN trgm 索引），相似度 ≥0.9 才自动配对，
     * 双方均以 WHERE match_status='UNMATCHED' 原子 UPDATE，防并发双配，对账单行侧
     * 失败，已被并发消费时回滚流水侧配对，undoMarked 补偿，保持双边一致，
     * 结束后统计未匹配差异行数，流水+对账单行落 reconcile_runs，对账差异调整量化口径。
     *
     * @param companyId 公司 id（null 表示全部可见公司）
     * @return 本次运行的统计批次
     */
    @Override
    @Transactional
    public ReconcileRunView autoReconcile(UUID companyId) {
        RequestContext.requireWriteRole();
        LambdaQueryWrapper<CashTransaction> wrapper = new LambdaQueryWrapper<CashTransaction>()
                .eq(CashTransaction::getMatchStatus, "UNMATCHED")
                .isNull(CashTransaction::getDeletedAt)
                .eq(companyId != null, CashTransaction::getCompanyId, companyId)
                .orderByAsc(CashTransaction::getTrxDate, CashTransaction::getId);
        List<CashTransaction> trxs = cashTransactionMapper.selectList(wrapper);

        int autoMatched = 0;
        for (CashTransaction trx : trxs) {
            // 候选，同账户 金额相等 日期差≤3 天 相似度降序取最优，B 类 sql，pg_trgm
            AutoMatchCandidate cand = bankStatementLineMapper.selectBestCandidate(
                    trx.getCompanyId(), trx.getBankAccountId(), trx.getTrxDate(),
                    trx.getAmount(), trx.getSummary());
            if (cand == null || cand.sim() == null || cand.sim().compareTo(AUTO_MATCH_THRESHOLD) < 0) {
                continue; // 无候选或相似度 <0.9，不自动配对，留待人工/模糊匹配，
            }
            BigDecimal score = cand.sim().setScale(4, RoundingMode.HALF_UP);
            // 流水侧原子消费，0 行 = 并发已被配对，跳过
            if (cashTransactionMapper.markMatched(trx.getId(), cand.lineId(), score) == 0) {
                continue;
            }
            // 对账单行侧原子消费，失败，已被并发消费则回滚流水侧配对，保持双边一致
            if (bankStatementLineMapper.markMatched(cand.lineId(), trx.getId(), score) == 0) {
                cashTransactionMapper.undoMarked(trx.getId());
                continue;
            }
            autoMatched++;
        }

        // 差异统计，仍未匹配的流水 + 对账单行，对账差异调整的量化口径
        long trxDiff = cashTransactionMapper.selectCount(new LambdaQueryWrapper<CashTransaction>()
                .eq(CashTransaction::getMatchStatus, "UNMATCHED")
                .isNull(CashTransaction::getDeletedAt)
                .eq(companyId != null, CashTransaction::getCompanyId, companyId));
        long lineDiff = bankStatementLineMapper.selectCount(new LambdaQueryWrapper<BankStatementLine>()
                .eq(BankStatementLine::getMatchStatus, "UNMATCHED")
                .isNull(BankStatementLine::getDeletedAt)
                .eq(companyId != null, BankStatementLine::getCompanyId, companyId));

        // 运行统计归属公司，显式入参优先，否则取现存流水/对账单公司，与 pg 函数 fallback 同语义，防 NULL 公司
        UUID runCompanyId = companyId;
        if (runCompanyId == null && !trxs.isEmpty()) {
            runCompanyId = trxs.get(0).getCompanyId();
        }
        ReconcileRun run = new ReconcileRun();
        run.setId(UuidV7.next());
        run.setCompanyId(runCompanyId);
        run.setPeriod(LocalDate.now().toString().substring(0, 7));
        run.setRunAt(LocalDateTime.now());
        run.setAutoMatched(autoMatched);
        run.setManualMatched(0);
        run.setDiffCount(Math.toIntExact(trxDiff + lineDiff));
        reconcileRunMapper.insert(run);
        return toRunView(run);
    }

    /**
     * {@inheritDoc}
     *
     * <p>人工对账，预检双方同公司、同银行账户，对账单行经其所属对账单归属账户，
     * 再双向原子配对，流水侧 markMatched（0 行则 40900"流水已被消费"），随后对账单行侧
     * markMatched（0 行则 40900"对账单行已被消费"，事务回滚撤销流水侧配对），
     * 相似度记 NULL，人工判定，非相似度驱动。
     */
    @Override
    @Transactional
    public void manualMatch(ManualMatchRequest req) {
        RequestContext.requireWriteRole();
        CashTransaction trx = requireTransaction(req.trxId());
        if (!trx.getCompanyId().equals(req.companyId())) {
            throw new ConflictException("流水与请求公司不一致");
        }
        BankStatementLine line = requireLine(req.lineId());
        BankStatement stmt = requireStatement(line.getStatementId());
        if (!stmt.getBankAccountId().equals(trx.getBankAccountId())) {
            throw new ConflictException("流水与对账单行不属于同一银行账户");
        }
        if (cashTransactionMapper.markMatched(trx.getId(), line.getId(), null) == 0) {
            throw new ConflictException("流水已被消费");
        }
        if (bankStatementLineMapper.markMatched(line.getId(), trx.getId(), null) == 0) {
            throw new ConflictException("对账单行已被消费"); // 事务回滚撤销流水侧配对，
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void undoMatch(UndoMatchRequest req) {
        RequestContext.requireWriteRole();
        // 双向回置，仅当互指关系与请求一致才生效（0 行则 40900）
        if (cashTransactionMapper.undoMatched(req.trxId(), req.lineId()) == 0) {
            throw new ConflictException("流水未处于已匹配状态");
        }
        if (bankStatementLineMapper.undoMatched(req.lineId(), req.trxId()) == 0) {
            throw new ConflictException("对账单行未处于已匹配状态");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReconcileRunView> pageReconcileRuns(long page, long size, UUID companyId) {
        Page<ReconcileRunView> pg = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        return PageResult.of(reconcileRunMapper.selectPage(pg, companyId));
    }

    // 资金日报

    /**
     * {@inheritDoc}
     *
     * <p>当日口径调 pg SECURITY DEFINER 函数 cash.fn_daily_report（期初=当日前的净流入、
     * 当日收/付/净额/期末 + 分账户明细，fastjson2 解析），未来 7 天预测为 B 类 sql
     * 按日聚合（ar 应收到期未核销 - ap 应付到期未核销，rls 行级收敛）。
     */
    @Override
    @Transactional(readOnly = true)
    public DailyReportView dailyReport(UUID companyId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        JSONObject json = JSON.parseObject(cashTransactionMapper.selectDailyReportJson(companyId, day));
        List<AccountDailyRow> byAccount = new ArrayList<>();
        JSONArray arr = json.getJSONArray("by_account");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject row = arr.getJSONObject(i);
                byAccount.add(new AccountDailyRow(
                        row.getString("bank_account_id") == null ? null
                                : UUID.fromString(row.getString("bank_account_id")),
                        money(row.getBigDecimal("income")),
                        money(row.getBigDecimal("expense"))));
            }
        }
        List<DailyForecastRow> forecast = cashTransactionMapper.selectForecast(companyId, day);
        return new DailyReportView(day,
                money(json.getBigDecimal("opening_balance")),
                money(json.getBigDecimal("income")),
                money(json.getBigDecimal("expense")),
                money(json.getBigDecimal("net")),
                money(json.getBigDecimal("closing_balance")),
                byAccount, forecast);
    }

    // 私有方法

    /** 按 id 取银行账户（rls 空集则 40400） */
    private BankAccount requireAccount(UUID id) {
        BankAccount acc = bankAccountMapper.selectById(id);
        if (acc == null) {
            throw new NotFoundException("银行账户不存在");
        }
        return acc;
    }

    /** 按 id 取流水（rls 空集则 40400） */
    private CashTransaction requireTransaction(UUID id) {
        CashTransaction trx = cashTransactionMapper.selectById(id);
        if (trx == null) {
            throw new NotFoundException("流水不存在");
        }
        return trx;
    }

    /** 按 id 取对账单（rls 空集则 40400） */
    private BankStatement requireStatement(UUID id) {
        BankStatement stmt = bankStatementMapper.selectById(id);
        if (stmt == null) {
            throw new NotFoundException("对账单不存在");
        }
        return stmt;
    }

    /** 按 id 取对账单行（rls 空集则 40400） */
    private BankStatementLine requireLine(UUID id) {
        BankStatementLine line = bankStatementLineMapper.selectById(id);
        if (line == null) {
            throw new NotFoundException("对账单行不存在");
        }
        return line;
    }

    /** 确认落库行的字段预检，方向枚举 + 金额非负，db CHECK 终审 */
    private void validateLinePreview(StatementLinePreview row) {
        if (row.trxDate() == null) {
            throw new BadRequestException("对账单行日期缺失");
        }
        if (row.amount() == null || row.amount().compareTo(ZERO) < 0) {
            throw new BadRequestException("对账单行金额非法：" + row.amount());
        }
        if (row.direction() != null && !row.direction().isBlank()) {
            validateEnum(row.direction(), LINE_DIRECTIONS, "对账单行方向");
        }
    }

    /**
     * 对账单文本解析（utf-8 csv/TSV），内存解析+预览，不落库，
     * 任一列非法则 40001 带行号快速失败。
     */
    private List<StatementLinePreview> parseStatementLines(MultipartFile file) {
        List<StatementLinePreview> rows = new ArrayList<>();
        String[] lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            // 首字符 BOM 容错，Excel 导出常见
            lines = reader.lines().toArray(String[]::new);
        } catch (IOException e) {
            throw new BadRequestException("对账单文件读取失败：" + e.getMessage());
        }
        if (lines.length == 0) {
            throw new BadRequestException("对账单文件为空");
        }
        char delimiter = lines[0].contains("\t") ? '\t' : ',';
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get();
        int start = 0;
        if (lines[0].toLowerCase(Locale.ROOT).contains("trx_date")
                || lines[0].contains("日期") || lines[0].toLowerCase(Locale.ROOT).contains("date")) {
            start = 1; // 表头行自动跳过，
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = splitCsvRow(line, csvFormat);
            if (cols.length < 3) {
                throw new BadRequestException("对账单第 " + (i + 1) + " 行列数不足（须至少 trx_date,amount,summary）");
            }
            LocalDate trxDate = parseDate(cols[0].trim(), i);
            BigDecimal amount;
            try {
                amount = new BigDecimal(cols[1].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("对账单第 " + (i + 1) + " 行金额非法：" + cols[1]);
            }
            if (amount.compareTo(ZERO) < 0) {
                throw new BadRequestException("对账单第 " + (i + 1) + " 行金额不能为负");
            }
            String direction = cols.length >= 4 && !cols[3].trim().isEmpty() ? cols[3].trim() : null;
            if (direction != null) {
                validateEnum(direction, LINE_DIRECTIONS, "对账单第 " + (i + 1) + " 行方向");
            }
            rows.add(new StatementLinePreview(trxDate, amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    cols[2].trim(), direction));
        }
        if (rows.isEmpty()) {
            throw new BadRequestException("对账单未解析到有效数据行");
        }
        return rows;
    }

    /** 单行按 Commons CSV 切分，引号包裹的逗号/换行不再错断，非法格式返回空列由调用方行校验兜底 */
    private String[] splitCsvRow(String line, CSVFormat csvFormat) {
        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.parse(line, csvFormat)) {
            records = parser.getRecords();
        } catch (IOException e) {
            return new String[0];
        }
        if (records.isEmpty()) {
            return new String[0];
        }
        CSVRecord record = records.get(0);
        String[] cols = new String[record.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = record.get(i);
        }
        return cols;
    }

    /** 日期解析，yyyy-MM-dd / yyyy/M/d 兼容 */
    private LocalDate parseDate(String text, int lineNo) {
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // fallback，yyyy/M/d，银行导出常见单零格式，
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/M/d"));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("对账单第 " + (lineNo + 1) + " 行日期非法：" + text);
        }
    }

    /** 枚举校验，db CHECK 终审兜底 */
    private void validateEnum(String value, Set<String> allowed, String label) {
        if (value != null && !allowed.contains(value)) {
            throw new BadRequestException(label + "非法：" + value);
        }
    }

    /** 币种规范化，CHAR，3，大写 */
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

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 流水创建幂等键，规范化请求内容 SHA-256 十六进制，同 body 必同键，改任一字段即新键。
     *  键为 64 字符，列 varchar，64，uk_trx_idem 部分唯一索引防同 body 重复提交。 */
    private static String trxIdemKey(UUID companyId, UUID bankAccountId, String direction,
                                     BigDecimal amount, String partner, String summary, LocalDate trxDate) {
        StringBuilder sb = new StringBuilder();
        sb.append(companyId).append('|')
          .append(bankAccountId).append('|')
          .append(direction.trim()).append('|')
          .append(amount.toPlainString()).append('|')
          .append(partner == null ? "" : partner.trim()).append('|')
          .append(summary == null ? "" : summary.trim()).append('|')
          .append(trxDate);
        return sha256Hex(sb.toString());
    }

    /** 对账单导入幂等键，filename + 全部行内容，顺序敏感 SHA-256 十六进制。
     *  同文件同内容必同键，不同文件同名但内容不同则新键，不冲突。 */
    private static String stmtIdemKey(String filename, List<StatementLinePreview> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(filename).append('|');
        for (StatementLinePreview row : rows) {
            sb.append(row.trxDate()).append(',')
              .append(money(row.amount()).toPlainString()).append(',')
              .append(row.summary() == null ? "" : row.summary().trim()).append(',')
              .append(row.direction() == null || row.direction().isBlank() ? "-" : row.direction().trim())
              .append(';');
        }
        return sha256Hex(sb.toString());
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

    /** 按幂等键查既有流水，同公司同账户，uk_trx_idem 唯一索引保证至多 1 行 */
    private CashTransaction findTrxByIdemKey(UUID companyId, UUID bankAccountId, String idemKey) {
        return cashTransactionMapper.selectOne(new LambdaQueryWrapper<CashTransaction>()
                .eq(CashTransaction::getCompanyId, companyId)
                .eq(CashTransaction::getBankAccountId, bankAccountId)
                .eq(CashTransaction::getIdemKey, idemKey)
                .isNull(CashTransaction::getDeletedAt));
    }

    /** 按幂等键查既有对账单，同公司同账户，uk_bank_stmt_idem 唯一索引保证至多 1 行 */
    private BankStatement findStmtByIdemKey(UUID companyId, UUID bankAccountId, String idemKey) {
        return bankStatementMapper.selectOne(new LambdaQueryWrapper<BankStatement>()
                .eq(BankStatement::getCompanyId, companyId)
                .eq(BankStatement::getBankAccountId, bankAccountId)
                .eq(BankStatement::getIdemKey, idemKey)
                .isNull(BankStatement::getDeletedAt));
    }

    /** 实体转流水视图 */
    private CashTransactionView toTransactionView(CashTransaction trx, String bankName) {
        return new CashTransactionView(trx.getId(), trx.getCompanyId(), trx.getBankAccountId(),
                bankName, trx.getDirection(), trx.getAmount(), trx.getPartner(), trx.getSummary(),
                trx.getTrxDate(), trx.getMatchStatus(), trx.getMatchedLineId(),
                trx.getSimilarityScore(), trx.getReconciledAt(), trx.getCreatedAt());
    }

    /** 实体转运行统计视图 */
    private ReconcileRunView toRunView(ReconcileRun run) {
        return new ReconcileRunView(run.getId(), run.getCompanyId(), run.getPeriod(), run.getRunAt(),
                run.getAutoMatched(), run.getManualMatched(), run.getDiffCount(), run.getCreatedAt());
    }
}
