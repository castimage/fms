package net.castimage.fms_backend.Service.impl;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Config.FmsProperties;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Exception.TokenExpiredException;
import net.castimage.fms_backend.Exception.UnauthorizedException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.RoleMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Model.dto.BackupCodesResponse;
import net.castimage.fms_backend.Model.dto.ChangePasswordRequest;
import net.castimage.fms_backend.Model.dto.LoginRequest;
import net.castimage.fms_backend.Model.dto.LoginResponse;
import net.castimage.fms_backend.Model.dto.LogoutRequest;
import net.castimage.fms_backend.Model.dto.RecoveryRequest;
import net.castimage.fms_backend.Model.dto.RecoveryResetRequest;
import net.castimage.fms_backend.Model.dto.RecoveryResponse;
import net.castimage.fms_backend.Model.dto.RefreshRequest;
import net.castimage.fms_backend.Model.dto.TotpEnableRequest;
import net.castimage.fms_backend.Model.dto.TotpPasswordRequest;
import net.castimage.fms_backend.Model.dto.TotpSetupResponse;
import net.castimage.fms_backend.Model.dto.TotpVerifyRequest;
import net.castimage.fms_backend.Model.entity.Role;
import net.castimage.fms_backend.Model.entity.User;
import net.castimage.fms_backend.Model.vo.UserSessionView;
import net.castimage.fms_backend.Service.AuthService;
import net.castimage.fms_backend.Utils.CachePort;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.MailService;
import net.castimage.fms_backend.Utils.PasswordUtils;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.TotpUtils;
import net.castimage.fms_backend.Utils.WeakPasswordFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.SQLException;

/** 认证服务实现，登录/刷新/登出/2fa/找回/会话，匿名端点先 SET ROLE fms_operator 再查 um.users（当前 users_select 对匿名恒空集，闭环依赖登录闸口），jwt 由 pg auth.jwt_sign 签发，密钥 sec.app_secrets 权威，2fa 票据/黑名单等会话态经 CachePort 收敛，in-memory|redis 双实现，fms.session-store 切换，任意实例写入全局可见 */
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    /** last_login_at 写入格式，与 Jackson date-format 一致，pg timestamptz 隐式识别 */
    private static final DateTimeFormatter LOGIN_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final AuthFunctionMapper authFunctionMapper;
    private final FmsProperties fmsProperties;
    private final WeakPasswordFilter weakPasswordFilter;
    private final MailService mailService;

    /**
     * 缓存端口（会话态经 CachePort 收敛，多实例共享），2fa 票据/找回票据/pending 密钥/
     * totp 失败计数/令牌黑名单全部收敛于此，任意后端实例写入对全部实例可见，票据 A 实例签发、B 实例可验证，in-memory|redis 双实现由 fms.session-store 切换。
     *
     * 备用码由 um.backup_codes + auth.* 闸口承载，不走本端口。
     */
    private final CachePort cachePort;

    /** totp 失败锁定阈值/窗口，构造期从 fms.mfa.* 快照，滑动窗口实现用 */
    private final int totpMaxFails;
    private final long totpFailWindowMillis;

    public AuthServiceImpl(UserMapper userMapper, RoleMapper roleMapper,
                           AuthFunctionMapper authFunctionMapper, FmsProperties fmsProperties,
                           WeakPasswordFilter weakPasswordFilter, CachePort cachePort,
                           MailService mailService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.authFunctionMapper = authFunctionMapper;
        this.fmsProperties = fmsProperties;
        this.weakPasswordFilter = weakPasswordFilter;
        this.cachePort = cachePort;
        this.mailService = mailService;
        this.totpMaxFails = fmsProperties.getMfa().getTotpMaxFails();
        this.totpFailWindowMillis = fmsProperties.getMfa().getTotpFailWindow().toMillis();
    }

    /**
     * 密码登录主流程，事务内，先 SET ROLE fms_operator 以列白名单查 um.users（匿名恒空集，
     * 配合登录闸口防匿名枚举），查得后注入本用户 guc，本用户行更新经 is_self 放行 rls。
     * 校验序，锁定未到期拒绝 / 锁定过期自动解锁清零 / 禁用拒绝则 bcrypt 口令校验，失败
     * 累加计数，达阈值置 LOCKED 并记锁定截止时间，成功则清零失败计数并刷新 last_login。
     * 启用 2fa 时仅发两步票据、不签发 jwt，弱密码标记暂存票据，二次验证成功才生效，
     * 否则直接签发令牌对。任何失败路径均写登录审计，落库失败仅 WARN 不影响主流程，
     * finally 复位角色。用户不存在与口令错误统一返回「用户名或密码错误」，不泄露账号
     * 存在性。
     *
     * 执行细节，查用户用列白名单，pass_hash 参与 bcrypt 校验但不出列给响应，
     * 匿名阶段 SELECT 因 rls 恒空集，配合登录闸口防登录名枚举，查到用户后注入
     * fms.user_id guc，随后的计数/锁定/last_login 更新经 is_self 策略放行，本用户
     * 只写自己的行。口令失败语义分两档，未达阈值仅累加计数（响应仍统一"用户名或
     * 密码错误"，不泄露是账号问题还是口令问题），达 max-failures 阈值置 LOCKED 并
     * 记 locked-until，响应提示剩余锁定分钟，此时账号存在性已被锁定状态间接证实，
     * 属可接受的信息暴露。锁定过期自动解锁并清零计数，无需管理员介入。
     *
     * 2fa 分支，启用 2fa 的用户首步只发两步票据（ttl=fms.mfa.ticket-ttl）不签发
     * 令牌，弱密码标记随票据暂存，二次验证成功才生效，避免首步泄露弱密码状态，
     * 未启用直接签发 access/refresh 令牌对。审计覆盖全部失败路径（user_not_found/
     * locked/disabled/bad_password/success），审计落库失败仅 WARN 不影响主流程。
     *
     * @param req       登录请求（loginName + password）
     * @param ip        客户端 ip（getClientIp 已规范化）
     * @param userAgent 客户端 ua 字符串，审计用，可为空
     * @return 登录响应，2fa 启用时为 mfaRequired 票据，首步恒 false，否则为 access/refresh 令牌对
     * @throws UnauthorizedException 账号不存在/锁定/禁用/口令错误，40100
     */
    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse login(LoginRequest req, String ip, String userAgent) {
        authFunctionMapper.setRoleOperator();
        try {
            // 登录闸口（SECURITY DEFINER），匿名 SELECT 因 um.users rls 恒空集恒查不到，
            // 用户查找 + bcrypt 校验，crypt 比对全部在 pg 内完成，防匿名枚举且 pass_hash 不出库。

            // 返回 {user_id, ok, status, locked_until, mfa_enabled, must_change_pwd,
            // failed_login_count, company_id}，不存在时 user_id 为空（status=NOT_FOUND）。
            VerifyLoginResult verify = parseVerifyLogin(
                    authFunctionMapper.verifyLogin(req.loginName(), req.password()));
            if (verify.userId() == null) {
                recordLogin(null, req.loginName(), false, "user_not_found", ip, userAgent);
                throw new UnauthorizedException("用户名或密码错误");
            }
            UUID userId = verify.userId();

            // 状态机，闸口返回权威状态，锁定，未到期拒绝，锁定已过期自动解锁（update_user 闸口，
            // 锁定态 current_uid 为 NULL，直接 UPDATE 会被 rls is_self 挡回），禁用拒绝。
            if ("LOCKED".equals(verify.status()) && verify.lockedUntil() != null
                    && verify.lockedUntil().isAfter(Instant.now())) {
                long remainMin = Math.max(
                        Duration.between(Instant.now(), verify.lockedUntil()).toMinutes(), 1);
                recordLogin(userId, req.loginName(), false, "locked", ip, userAgent);
                throw new UnauthorizedException("账户已锁定，请 " + remainMin + " 分钟后重试");
            }
            if ("LOCKED".equals(verify.status())) {
                // 锁定已过期，经闸口自动解锁并清零失败计数
                authFunctionMapper.updateUser(userId, patch(JSONObject.of(
                        "status", "ACTIVE", "failed_login_count", 0, "locked_until", null)));
            }
            if ("DISABLED".equals(verify.status())) {
                recordLogin(userId, req.loginName(), false, "disabled", ip, userAgent);
                throw new UnauthorizedException("账户已禁用，请联系管理员");
            }

            // 口令校验结果（闸口内 crypt 比对，失败计数原子递增 + 暴力锁定全部下沉 pg 闸口
            // auth.record_login_failure 行级原子自增：Java 侧读改写并发下会漏计，暴力破解
            // 锁定可被绕过；单条 UPDATE 行级原子自增，行锁串行化，达阈值同事务直接置
            // LOCKED+locked_until）
            if (!verify.ok()) {
                JSONObject failRes = JSON.parseObject(authFunctionMapper.recordLoginFailure(
                        userId, fmsProperties.getLogin().getMaxFailures(),
                        (int) fmsProperties.getLogin().getLockMinutes().toMinutes()));
                if (Boolean.TRUE.equals(failRes.getBoolean("locked"))) {
                    recordLogin(userId, req.loginName(), false, "locked", ip, userAgent);
                    throw new UnauthorizedException("密码错误次数过多，账户已锁定");
                }
                recordLogin(userId, req.loginName(), false, "bad_password", ip, userAgent);
                throw new UnauthorizedException("用户名或密码错误");
            }

            // 口令正确，注入本用户 guc 后读全量行，is_self 放行 rls，
            // 经 auth.update_user 门面清计数/解锁/刷登录时间（白名单含
            // last_login_at，列级授权下 operator 直更 users 仅 display_name）
            authFunctionMapper.setLocalUserId(userId);
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new UnauthorizedException("用户不存在或已删除");
            }
            authFunctionMapper.updateUser(userId, patch(JSONObject.of(
                    "failed_login_count", 0, "locked_until", null,
                    "last_login_at", LocalDateTime.now().format(LOGIN_TS_FMT))));
            recordLogin(userId, req.loginName(), true, null, ip, userAgent);

            // 2fa 检测，启用则仅发两步票据，不签发 jwt（弱密码标记暂存票据，二次验证成功才生效，首步响应恒 false）
            if (Boolean.TRUE.equals(user.getMfaEnabled())) {
                return LoginResponse.mfaRequired(issueMfaTicket(userId, isWeakLoginPassword(req.password())),
                        toSessionView(user, resolveJwtRole(userId)));
            }
            return issueLogin(user, isWeakLoginPassword(req.password()));
        } finally {
            authFunctionMapper.resetRole();
        }
    }

    // 失败路径先落登录审计再抛 UnauthorizedException，事务不回滚以保全审计记录，与 login 同口径
    @Override
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse verifyTotp(TotpVerifyRequest req, String ip, String userAgent) {
        // 会话态经 CachePort 收敛，多实例共享，2fa 票据 A 实例签发、B 实例可验证，
        // ttl 过期即 key 消失，解析失败按"票据无效"处理（fail-closed）
        MfaTicket ticket = cachePort.get(ticketKey(req.ticket()))
                .map(this::parseMfaTicket).orElse(null);
        if (ticket == null) {
            throw new UnauthorizedException("票据无效或已过期，请重新登录");
        }
        // 暴力防护，totp 连续失败 totpMaxFails 次，totpFailWindow 窗口拒绝。

        // 会话态经 CachePort 收敛，多实例共享，滑动窗口语义，fail 时 increment 刷新 ttl，
        // ttl 过期自动归零，无值按 0 计
        if (!totpFailTryAcquire(ticket.userId())) {
            throw new UnauthorizedException("TOTP 验证失败次数过多，请稍后再试");
        }
        authFunctionMapper.setRoleOperator();
        try {
            // auth.get_mfa_secret（pgp_sym_decrypt 后返回 base32），totp 校验失败时回退一次性备用恢复码。

            // 备用码路径审计区分，同口径，成功 success=true + fail_reason='backup_code_used'
            // （区分"经备用码登录"与普通 totp 登录，安全审计可追溯降级通道），失败归
            // 'bad_backup_code'，totp 错 + 备用码无/错统一走此原因
            boolean usedBackupCode = false;
            String secret = authFunctionMapper.getMfaSecret(ticket.userId());
            if (!TotpUtils.verify(secret, req.code(),
                    fmsProperties.getMfa().getTotpWindow())) {
                if (!consumeBackupCode(ticket.userId(), req.code())) {
                    totpFailRecord(ticket.userId());
                    recordLogin(ticket.userId(), null, false, "bad_backup_code", ip, userAgent);
                    throw new UnauthorizedException("验证码错误");
                }
                usedBackupCode = true;
            }
            totpFailReset(ticket.userId()); // 验证通过，重置失败计数，key 删除
            cachePort.delete(ticketKey(req.ticket())); // 票据一次性消费，原 mfaTickets.remove，语义一致且跨实例原子
            // 注入目标用户 guc，匿名会话，SET ROLE fms_operator、无 fms.user_id 下
            // users_select rls 仅 is_self 可见，selectById 恒空集则 40100，与 refresh
            // 流程同口径注入后自读放行，2fa 验证通过即代表本人，票据已绑 userId
            authFunctionMapper.setLocalUserId(ticket.userId());
            User user = userMapper.selectById(ticket.userId());
            if (user == null) {
                throw new UnauthorizedException("用户不存在或已删除");
            }
            if ("DISABLED".equals(user.getStatus())) {
                recordLogin(user.getId(), user.getLoginName(), false, "disabled", ip, userAgent);
                throw new UnauthorizedException("账户已禁用，请联系管理员");
            }
            recordLogin(user.getId(), user.getLoginName(), true,
                    usedBackupCode ? "backup_code_used" : null, ip, userAgent);
            return issueLogin(user, ticket.weakPassword());
        } finally {
            authFunctionMapper.resetRole();
        }
    }

    /**
     * refresh token 换新令牌对，旋转语义，黑名单命中直接抛 TokenExpiredException，否则
     * 经 auth.jwt_verify 严格验签，签名/exp 由 pg 侧把关，验签失败按过期统一处理并断言
     * type=refresh、sub 为合法 uuid，按 sub 重查用户（已删则 40100）。通过后签发新令牌对，
     * 并把旧 refresh 原值立即入黑名单，旋转后旧令牌在剩余 ttl 内失效，防旧令牌重放。
     * 期间注入本用户 guc 满足 rls 自读。
     *
     * 安全语义，刷新是新会话的重新授权点，黑名单在验签之前检查，被撤销的令牌
     * 即使签名仍有效也立即拒绝，pg 侧 jwt_verify 严格校验签名与 exp（后端无 jwt
     * 密钥副本，权威在 db），验签失败统一按 TokenExpiredException 处理（40100 语义，
     * 不区分"伪造"与"过期"，不给攻击者反馈差异），载荷断言 type=refresh（access
     * 令牌不可用于刷新，防 access 泄漏被滥用为长期会话）、sub 为合法 uuid。
     * 每次刷新都签发全新令牌对且旧 refresh 立即入黑名单，ttl 内失效，同一 refresh
     * 的并发/重放使用最多成功一次。用户已删除则 40100，旋转链条中断，强制重新登录。
     *
     * @param req 含 refreshToken 的刷新请求
     * @return 新签发的 access/refresh 令牌对，新 access 也可直接使用
     * @throws TokenExpiredException 黑名单命中或验签失败，40100 语义
     * @throws UnauthorizedException 令牌类型错误/载荷非法/用户已删除
     */
    @Override
    @Transactional
    public LoginResponse refresh(RefreshRequest req) {
        // 会话态经 CachePort 收敛，多实例共享，黑名单任意实例写入、任意实例校验
        if (cachePort.get(blacklistKey(req.refreshToken())).isPresent()) {
            throw new TokenExpiredException("refresh token 已失效，请重新登录");
        }
        JwtClaims claims = verifyRefreshToken(req.refreshToken());
        authFunctionMapper.setRoleOperator();
        try {
            authFunctionMapper.setLocalUserId(claims.userId());
            User user = userMapper.selectById(claims.userId());
            if (user == null) {
                throw new UnauthorizedException("用户不存在或已删除");
            }
            // 与 login 同口径，DISABLED 用户不得续期会话
            if ("DISABLED".equals(user.getStatus())) {
                throw new UnauthorizedException("账户已禁用，请联系管理员");
            }
            LoginResponse response = issueLogin(user);

            // 刷新后旧 refresh 立即失效，旋转语义，会话态经 CachePort 收敛，多实例共享，
            // 值占位 "1"，ttl 与令牌对齐自动过期
            cachePort.set(blacklistKey(req.refreshToken()), "1",
                    fmsProperties.getJwt().getRefreshTtl());
            return response;
        } finally {
            authFunctionMapper.resetRole();
        }
    }

    /**
     * 登出，把请求携带的 access/refresh 令牌原值写入 CachePort 黑名单，失效时长与对应
     * 令牌 ttl 对齐，条目随 ttl 自动过期，无状态 jwt 无法服务端真正撤销，此为 ttl
     * 内的尽力失效，refresh 校验路径先查黑名单，命中即拒。req 为 null 或令牌为空白时
     * 静默返回，不抛错，幂等可重放。
     *
     * 语义细节，access 入黑名单仅防 ttl 内的残余使用，网关验签不查后端黑名单，
     * 实际生效面主要是 refresh 路径与后端敏感操作的可选校验，黑名单按原始 token
     * 文本为键，哈希存储不可行，需精确匹配原始串，多实例共享、无泄漏。登出
     * 不要求已认证，匿名登出幂等放行，不写审计，登录审计已覆盖会话生命周期。
     *
     * @param req 含 accessToken/refreshToken 的登出请求，可为 null
     */
    @Override
    public void logout(LogoutRequest req) {
        if (req == null) {
            return;
        }
        // 会话态经 CachePort 收敛，多实例共享，值占位 "1"，ttl 与对应令牌 ttl 对齐，自动过期
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            cachePort.set(blacklistKey(req.accessToken()), "1",
                    fmsProperties.getJwt().getAccessTtl());
        }
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            cachePort.set(blacklistKey(req.refreshToken()), "1",
                    fmsProperties.getJwt().getRefreshTtl());
        }
        log.info("登出完成（令牌入 CachePort 黑名单，TTL 内失效，多实例共享）");
    }

    @Override
    @Transactional(readOnly = true)
    public UserSessionView session() {
        UUID userId = RequestContext.userId();
        if (userId == null) {
            throw new UnauthorizedException("未认证");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        JwtRole role = RequestContext.role() != null ? RequestContext.role() : resolveJwtRole(userId);
        return toSessionView(user, role);
    }

    @Override
    @Transactional(readOnly = true)
    public TotpSetupResponse totpSetup() {
        UUID userId = RequestContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        String secret = TotpUtils.generateSecret();
        LocalDateTime expireAt = LocalDateTime.now().plus(fmsProperties.getMfa().getPendingSecretTtl());
        // 会话态经 CachePort 收敛，多实例共享，pending 密钥 ttl 内任意实例可继续 enable 流程
        cachePort.set(pendingKey(userId), secret, fmsProperties.getMfa().getPendingSecretTtl());
        String otpauthUrl = TotpUtils.otpauthUrl(
                fmsProperties.getMfa().getIssuer(), user.getLoginName(), secret);
        return new TotpSetupResponse(secret, otpauthUrl, expireAt);
    }

    @Override
    @Transactional
    public void totpEnable(TotpEnableRequest req) {
        UUID userId = RequestContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        if (!PasswordUtils.matches(req.password(), user.getPassHash())) {
            throw new UnauthorizedException("密码错误");
        }

        // 会话态经 CachePort 收敛，多实例共享，ttl 过期即 key 消失
        Optional<String> pending = cachePort.get(pendingKey(userId));
        if (pending.isEmpty()) {
            throw new BadRequestException("TOTP 密钥已过期，请重新 setup");
        }
        if (!TotpUtils.verify(pending.get(), req.code(), fmsProperties.getMfa().getTotpWindow())) {
            throw new BadRequestException("验证码错误");
        }

        // auth.set_mfa（pgp_sym_encrypt 落 mfa_secret_enc，列级不可直写）
        authFunctionMapper.setMfa(userId, pending.get(), true);
        cachePort.delete(pendingKey(userId)); // 启用成功即消费，原 pendingSecrets.remove，跨实例原子
        log.info("用户 {} 已启用 2FA", userId);
    }

    @Override
    @Transactional
    public void totpDisable(TotpPasswordRequest req) {
        UUID userId = RequestContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        if (!PasswordUtils.matches(req.password(), user.getPassHash())) {
            throw new UnauthorizedException("密码错误");
        }
        authFunctionMapper.setMfa(userId, null, false);
        log.info("用户 {} 已禁用 2FA", userId);
    }

    @Override
    @Transactional
    public BackupCodesResponse backupCodes(String ip, String userAgent) {
        UUID userId = RequestContext.requireUserId();
        // 生成事件留痕，同口径，登录/2fa/改密统一落 um.login_log，
        // 备用码是 2fa 的降级通道，生成/核销都必须可审计。

        // 本方法为读写事务，recordLogin 写 um.login_log，
        // readOnly 连接下 pg 会以 25006 拒绝 INSERT
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        List<String> codes = TotpUtils.generateBackupCodes(fmsProperties.getMfa().getBackupCodeCount(),
                fmsProperties.getMfa().getBackupCodeLength());
        LocalDateTime expireAt = LocalDateTime.now()
                .plusDays(fmsProperties.getMfa().getBackupCodeValidDays());
        // 备用码持久化，db 盲索引落盘，SECURITY DEFINER 闸口，不随会话 ttl/
        // 实例重启丢失，核销留 used_at 可审计，
        // 明文仅函数体/事务内处理，库内只存 hmac 盲索引
        int inserted = authFunctionMapper.issueBackupCodes(userId,
                codes.toArray(new String[0]), fmsProperties.getMfa().getBackupCodeValidDays());
        if (inserted != codes.size()) {
            log.warn("备用码落库数与生成数不一致: {}/{}", inserted, codes.size());
        }
        // 生成审计，success=true + fail_reason='backup_codes_generated'（区分普通登录成功，
        // 审计可追溯"何时给谁生成过降级凭证"），落库失败仅 WARN 不影响主流程
        recordLogin(userId, user.getLoginName(), true, "backup_codes_generated", ip, userAgent);
        return new BackupCodesResponse(codes, expireAt);
    }

    @Override
    @Transactional
    public RecoveryResponse recovery(RecoveryRequest req) {
        // email 为加密存储（bytea + hmac 盲索引），应用侧无法比对明文、也无法取出明文，
        // 且匿名期 rls is_self 恒拒、selectOne 恒空集，一致性校验走
        // auth.recovery_lookup 闸口，SECURITY DEFINER 直读盲索引
        UUID uid = authFunctionMapper.recoveryLookup(req.loginName(), req.email());
        // 防用户名探测，用户不存在/email 不匹配同样返回票据占位（占位票据绑定随机 uuid，
        // reset 时经 set_password 0 行落库并按无效票据处理，与真实票据不可区分）
        String ticket = UuidV7.next().toString();
        LocalDateTime expireAt = LocalDateTime.now()
                .plus(fmsProperties.getMfa().getRecoveryTicketTtl());
        // 会话态经 CachePort 收敛，多实例共享，值=userId 字符串，set 为覆盖语义，
        // 占位票据绑随机 uuid，与真实票据同结构不可区分，ttl 自动过期
        cachePort.set(recoveryKey(ticket), (uid == null ? UuidV7.next() : uid).toString(),
                fmsProperties.getMfa().getRecoveryTicketTtl());
        // 邮件闭环，关键约束，邮箱加密存储无法取出明文，目标邮箱只能取请求体自报值，
        // 仅当 recovery_lookup 命中，自报邮箱与盲索引一致，等于证明邮箱归属才发送，
        // 未命中不发送，避免邮箱轰炸但响应与命中完全一致，防枚举口径不变。

        // 发送失败在 MailService 内部仅 WARN，不影响票据返回
        if (uid != null) {
            mailService.sendPasswordReset(req.loginName(), req.email(), ticket, expireAt);
        }
        return new RecoveryResponse(ticket, expireAt);
    }

    @Override
    @Transactional
    public void recoveryReset(RecoveryResetRequest req, String ip, String userAgent) {
        // 会话态经 CachePort 收敛，多实例共享，ttl 过期即 key 消失，
        // 值损坏按无效票据处理，fail-closed，与无效/过期同语义
        Optional<String> ticketUserId = cachePort.get(recoveryKey(req.ticket()));
        if (ticketUserId.isEmpty()) {
            throw new UnauthorizedException("票据无效或已过期，请重新发起找回");
        }
        UUID ticketOwner;
        try {
            ticketOwner = UUID.fromString(ticketUserId.get());
        } catch (RuntimeException e) {
            throw new UnauthorizedException("票据无效或已过期，请重新发起找回");
        }
        authFunctionMapper.setRoleOperator();
        try {
            // 弱密码预检，长度二次校验 + 布隆预筛精确确认，命中 40001 拒绝写入
            PasswordUtils.checkLength(req.newPassword(), fmsProperties.getSecurity().getPasswordMinLength(),
                    fmsProperties.getSecurity().getPasswordMaxLength());
            weakPasswordFilter.rejectIfWeak(req.newPassword());

            // auth.set_password（bcrypt 哈希写入，列级不可直写），占位票据对不存在的用户
            // 落库 0 行 RAISE「用户不存在」，与无效票据同语义处理，防二次账号探测
            try {
                authFunctionMapper.setPassword(ticketOwner, PasswordUtils.encode(req.newPassword()));
            } catch (RuntimeException e) {
                Throwable root = unwrap(e);
                if (root instanceof SQLException sqlEx
                        && sqlEx.getMessage() != null && sqlEx.getMessage().contains("用户不存在")) {
                    throw new UnauthorizedException("票据无效或已过期，请重新发起找回");
                }
                throw e;
            }
            cachePort.delete(recoveryKey(req.ticket())); // 票据一次性消费，原 recoveryTickets.remove，跨实例原子
            recordLogin(ticketOwner, null, true, null, ip, userAgent);
            log.info("用户 {} 已通过找回流程重置密码", ticketOwner);
        } finally {
            authFunctionMapper.resetRole();
        }
    }

    /**
     * 自助改密，已认证，身份取自 RequestContext，网关签名校验采信，沙箱拦截器
     * 已注入本用户 guc + SET ROLE，selectById 经 is_self 放行 rls 自读。校验序，
     * 旧密码 bcrypt 复验，与 auth.verify_login 同语义，库中哈希为空恒 false 则
     * 新密码与旧密码同值拒绝则做弱密码预检，长度二次校验 + 布隆预筛精确确认，
     * 命中 40001 不落库，与 recovery/reset 同链路，随后 auth.set_password 写 bcrypt（pass_hash 列级不可直写的唯一写入口），再 auth.update_user 清 must_change_pwd（首登改密完成后下次登录不再强制），最后 auth.record_login 记 um.login_log
     * （success=true + fail_reason='password_changed' 标记改密事件，统一
     * 登录/登出/2fa/改密审计）。请求携带的令牌原值入 CachePort 黑名单，当前会话立即失效，强制重新登录，他端会话为无状态 jwt 无法批量吊销，ttl 内自然失效。
     *
     * @param req       改密请求，oldPassword/newPassword + 可选 accessToken/refreshToken
     * @param ip        客户端 ip，审计用
     * @param userAgent 客户端 ua，审计用
     * @throws UnauthorizedException 未认证/旧密码错误，40100
     * @throws BadRequestException   新密码与旧密码相同/弱密码/长度非法，40001
     */
    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest req, String ip, String userAgent) {
        UUID userId = RequestContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        // 与 refresh/totp 同口径，禁用账户不得执行敏感操作，含改密
        if ("DISABLED".equals(user.getStatus())) {
            throw new UnauthorizedException("账户已禁用，请联系管理员");
        }
        // 旧密码复验，bcrypt 比对，语义与 auth.verify_login 一致，totp enable/disable 同口径
        if (!PasswordUtils.matches(req.oldPassword(), user.getPassHash())) {
            throw new UnauthorizedException("旧密码错误");
        }
        // 新密码与旧密码同值拒绝（防"改了等于没改"，改密无意义）
        if (req.newPassword().equals(req.oldPassword())) {
            throw new BadRequestException("新密码不能与旧密码相同");
        }
        // 弱密码预检，长度二次校验，fms.security.password-min/max-length 收紧生效后
        // 布隆预筛精确确认，命中 40001 不落库，与 recovery/reset 同一链路
        PasswordUtils.checkLength(req.newPassword(), fmsProperties.getSecurity().getPasswordMinLength(),
                fmsProperties.getSecurity().getPasswordMaxLength());
        weakPasswordFilter.rejectIfWeak(req.newPassword());

        // 改密唯一写入口 auth.set_password（bcrypt 哈希写入，pass_hash 列级不可直写）
        authFunctionMapper.setPassword(userId, PasswordUtils.encode(req.newPassword()));
        // 清 must_change_pwd（update_user 白名单键，首登强制改密闭环在此解除）
        authFunctionMapper.updateUser(userId, patch(JSONObject.of("must_change_pwd", false)));

        // 改密事件记 um.login_log（success=true + fail_reason='password_changed' 标记，
        // 登录/登出/2fa/密码修改统一落登录日志）
        recordLogin(userId, user.getLoginName(), true, "password_changed", ip, userAgent);
        // 会话策略，请求携带的令牌原值入黑名单，当前会话立即失效，防旧口令会话续期
        blacklistTokens(req);
        log.info("用户 {} 已自助修改密码（um.login_log 已记 password_changed）", userId);
    }

    /** 改密后令牌失效，请求携带的 access/refresh 原值入 CachePort 黑名单，ttl 与各自令牌对齐，自动过期 */
    private void blacklistTokens(ChangePasswordRequest req) {
        // 会话态经 CachePort 收敛，多实例共享，值占位 "1"，ttl 与对应令牌 ttl 对齐
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            cachePort.set(blacklistKey(req.accessToken()), "1",
                    fmsProperties.getJwt().getAccessTtl());
        }
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            cachePort.set(blacklistKey(req.refreshToken()), "1",
                    fmsProperties.getJwt().getRefreshTtl());
        }
    }

    // 内部实现

    /**
     * 签发 access/refresh 令牌对，pg auth.jwt_sign，role 由用户角色集合解析，无弱密码标记。
     */
    private LoginResponse issueLogin(User user) {
        return issueLogin(user, false);
    }

    /**
     * 签发令牌对并携带改密标记，弱密码标记，mark-on-login 开启且登录密码命中弱密码
     * 集合，由调用方计算传入或 must_change_pwd 首登强制改密，管理员建号/重置默认置位
     * 任一命中即要求改密，响应 requirePasswordChange=true，前端强制跳改密页。
     */
    private LoginResponse issueLogin(User user, boolean requirePasswordChange) {
        // 附录 B 首登改密闭环，must_change_pwd 与弱密码标记合并为同一改密要求信号
        boolean mustChange = requirePasswordChange || Boolean.TRUE.equals(user.getMustChangePwd());
        JwtRole role = resolveJwtRole(user.getId());
        String access = signToken(user.getId(), role,
                fmsProperties.getJwt().getAccessTtl(), "access");
        String refresh = signToken(user.getId(), role,
                fmsProperties.getJwt().getRefreshTtl(), "refresh");
        return LoginResponse.success(access, refresh, toSessionView(user, role), mustChange);
    }

    /** 调用 pg auth.jwt_sign 签发，载荷含 sub/role/exp/iss/aud/type，json 序列化交由 fastjson2，pg 侧按键名读取不依赖字段序 */
    private String signToken(UUID userId, JwtRole role, Duration ttl, String type) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        JSONObject payload = new JSONObject();
        payload.put("sub", userId.toString());
        payload.put("role", role.shortName());
        payload.put("exp", exp);
        payload.put("iss", fmsProperties.getJwt().getIssuer());
        payload.put("aud", fmsProperties.getJwt().getAudience());
        payload.put("type", type);
        return authFunctionMapper.jwtSign(payload.toJSONString());
    }

    /** refresh 验签，auth.jwt_verify（签名/exp 严格校验）通过则断言 type=refresh */
    private JwtClaims verifyRefreshToken(String token) {
        String payload;
        try {
            payload = authFunctionMapper.jwtVerify(token);
        } catch (RuntimeException e) {
            // pgjwt 验签失败 RAISE，经 Spring 包装为 DataAccessException，统一按过期处理
            throw new TokenExpiredException("refresh token 无效或已过期", unwrap(e));
        }
        JSONObject fields;
        try {
            fields = payload == null || payload.isBlank()
                    ? new JSONObject() : JSON.parseObject(payload);
        } catch (RuntimeException e) {
            // 防御，jwt_verify 正常只返回受控紧凑 json，解析失败即 40100
            throw new UnauthorizedException("令牌载荷非法");
        }
        if (!"refresh".equals(fields.getString("type"))) {
            throw new UnauthorizedException("令牌类型错误");
        }
        try {
            return new JwtClaims(UUID.fromString(fields.getString("sub")));
        } catch (RuntimeException e) {
            throw new UnauthorizedException("令牌载荷非法");
        }
    }

    /** 角色集合映射 jwt role 短名（fms_admin_role/sys_admin 归 admin，accountant/approver/cashier 归 operator，auditor 归 auditor，report_viewer 归 report，其余归 viewer） */
    private JwtRole resolveJwtRole(UUID userId) {
        List<String> codes = roleMapper.selectRolesByUserId(userId).stream()
                .map(Role::getCode)
                .toList();
        if (codes.contains("fms_admin_role") || codes.contains("sys_admin")) {
            return JwtRole.ADMIN;
        }
        if (codes.contains("accountant") || codes.contains("approver") || codes.contains("cashier")) {
            return JwtRole.OPERATOR;
        }
        if (codes.contains("auditor")) {
            return JwtRole.AUDITOR;
        }
        if (codes.contains("report_viewer")) {
            return JwtRole.REPORT;
        }
        return JwtRole.VIEWER;
    }

    /**
     * 会话视图，白名单字段，永不含敏感列。
     */
    private UserSessionView toSessionView(User user, JwtRole role) {
        return new UserSessionView(user.getId(), user.getLoginName(), user.getDisplayName(),
                role.shortName(), user.getMfaEnabled(), user.getMustChangePwd(),
                user.getLastLoginAt(), user.getCompanyId());
    }

    /**
     * 2fa 两步票据签发（会话态经 CachePort 收敛，多实例共享，ttl=fms.mfa.ticket-ttl，
     * 过期即 key 消失，setIfAbsent 防 uuid 碰撞，理论上恒真，重试仅防御性兜底，
     * weakPassword 标记暂存至二次验证成功）。
     */
    private String issueMfaTicket(UUID userId, boolean weakPassword) {
        String ticket;
        do {
            ticket = UuidV7.next().toString();
        } while (!cachePort.setIfAbsent(ticketKey(ticket), JSONObject.of(
                "uid", userId.toString(), "weak", weakPassword).toJSONString(),
                fmsProperties.getMfa().getTicketTtl()));
        return ticket;
    }

    /**
     * 弱密码登录标记，开关 fms.security.weak-password.mark-on-login 开启且命中弱密码集合才置位，默认恒 false。
     */
    private boolean isWeakLoginPassword(String rawPassword) {
        return fmsProperties.getSecurity().getWeakPassword().isMarkOnLogin()
                && weakPasswordFilter.isWeak(rawPassword);
    }

    /**
     * 登录审计统一入口，auth.record_login，失败不影响主流程，仅 WARN。
     */
    private void recordLogin(UUID userId, String loginName, boolean success,
                             String failReason, String ip, String userAgent) {
        try {
            authFunctionMapper.recordLogin(userId,
                    loginName == null ? "" : loginName,
                    ip,
                    userAgent == null ? "" : userAgent,
                    success, failReason);
        } catch (RuntimeException e) {
            log.warn("登录审计落库失败（不影响主流程）: userId={} reason={} err={}",
                    userId, failReason, e.getMessage());
        }
    }

    /**
     * 解析 auth.verify_login 返回的 jsonb（{user_id, ok, status, locked_until, mfa_enabled,
     * must_change_pwd, failed_login_count, company_id}），锁定时间戳为 timestamptz，json 渲染
     * 带时区偏移（ISO-8601），以 OffsetDateTime 解析转 Instant 比较，与 pg 语义对齐。
     */
    private VerifyLoginResult parseVerifyLogin(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("auth.verify_login 返回空结果");
        }
        JSONObject o = JSON.parseObject(json);
        String uidText = o.getString("user_id");
        UUID userId = uidText == null ? null : UUID.fromString(uidText);
        String lockedText = o.getString("locked_until");
        Instant lockedUntil = lockedText == null || lockedText.isBlank()
                ? null : OffsetDateTime.parse(lockedText).toInstant();
        return new VerifyLoginResult(userId,
                Boolean.TRUE.equals(o.getBoolean("ok")),
                o.getString("status"),
                lockedUntil,
                Boolean.TRUE.equals(o.getBoolean("mfa_enabled")),
                o.getInteger("failed_login_count"));
    }

    /** update_user 补丁序列化，白名单键，email/status/must_change_pwd/failed_login_count/locked_until，null 值经 JSONObject 保留 */
    private String patch(JSONObject fields) {
        return fields.toJSONString();
    }

    /**
     * 核销一次性备用恢复码，verifyTotp 的 totp 校验失败回退路径，未命中/已用/过期返回 false。
     *
     * db 权威核销（SECURITY DEFINER），hmac 盲索引等值 + 条件更新 used_at 原子消费，WHERE used_at IS NULL 防并发双用，明文仅函数体/事务内比对，不进日志，
     * 2fa 验证期无沙箱上下文，guc 未注入，故必须经闸口而非表直写。
     */
    private boolean consumeBackupCode(UUID userId, String code) {
        return authFunctionMapper.consumeBackupCode(userId, code);
    }

    private Throwable unwrap(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // CachePort 键构造，命名空间 fms:*，与 CachePort 契约一一对应，多实例共享语义见接口注释
    private String ticketKey(String ticket) {
        return "fms:ticket:" + ticket;
    }

    private String recoveryKey(String ticket) {
        return "fms:recovery:" + ticket;
    }

    private String pendingKey(UUID userId) {
        return "fms:pending:" + userId;
    }

    private String totpFailKey(UUID userId) {
        return "fms:totpfail:" + userId;
    }

    private String blacklistKey(String token) {
        return "fms:blacklist:" + token;
    }

    /**
     * 票据 json 转 MfaTicket（{"uid":"<uuid>","weak":true/false}），解析失败/字段缺失
     * 按 null 处理（fail-closed，值损坏视为"票据无效或已过期"，宁可拒不可放）
     */
    private MfaTicket parseMfaTicket(String json) {
        try {
            JSONObject o = JSON.parseObject(json);
            return new MfaTicket(UUID.fromString(o.getString("uid")),
                    o.getBooleanValue("weak"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // totp 失败计数（会话态经 CachePort 收敛，多实例共享，滑动窗口语义，fail 时
    // increment 刷新 ttl，滚动窗口，ttl 过期自动归零），

    /** 窗口内是否仍可尝试，当前失败计数 < totpMaxFails，无记录视为 0，值损坏按 0 放行一次 */
    private boolean totpFailTryAcquire(UUID userId) {
        long fails = cachePort.get(totpFailKey(userId))
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                }).orElse(0L);
        return fails < totpMaxFails;
    }

    /** 记录一次失败，原子自增 + 刷新 ttl，滑动窗口滚动 */
    private void totpFailRecord(UUID userId) {
        cachePort.increment(totpFailKey(userId), Duration.ofMillis(totpFailWindowMillis));
    }

    /** 验证通过清零，key 删除 */
    private void totpFailReset(UUID userId) {
        cachePort.delete(totpFailKey(userId));
    }

    // 记录类型

    /** 2fa 两步票据，weakPassword，登录第 1 步计算的弱密码标记，二次验证成功时生效，过期由 CachePort ttl 承载 */
    private record MfaTicket(UUID userId, boolean weakPassword) {
    }

    /** refresh 验签结果 */
    private record JwtClaims(UUID userId) {
    }

    /** auth.verify_login 闸口解析结果，status=NOT_FOUND 时 userId 为 null */
    private record VerifyLoginResult(UUID userId, boolean ok, String status,
                                     Instant lockedUntil, boolean mfaEnabled,
                                     Integer failedLoginCount) {
    }
}
