package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.FmsBaseException;
import net.castimage.fms_backend.Exception.UnauthorizedException;
import net.castimage.fms_backend.Mapper.AuthFunctionMapper;
import net.castimage.fms_backend.Mapper.CompanyMapper;
import net.castimage.fms_backend.Mapper.DepartmentMapper;
import net.castimage.fms_backend.Mapper.ModuleMapper;
import net.castimage.fms_backend.Mapper.PermissionMapper;
import net.castimage.fms_backend.Mapper.RoleMapper;
import net.castimage.fms_backend.Mapper.UserMapper;
import net.castimage.fms_backend.Model.dto.LoginRequest;
import net.castimage.fms_backend.Model.dto.LoginResponse;
import net.castimage.fms_backend.Model.dto.RecoveryRequest;
import net.castimage.fms_backend.Model.dto.RecoveryResetRequest;
import net.castimage.fms_backend.Model.dto.ResetPasswordRequest;
import net.castimage.fms_backend.Model.dto.UserCreateRequest;
import net.castimage.fms_backend.Model.entity.User;
import net.castimage.fms_backend.Service.impl.AuthServiceImpl;
import net.castimage.fms_backend.Service.impl.OrgServiceImpl;
import net.castimage.fms_backend.Utils.InMemoryCachePort;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.PasswordUtils;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.WeakPasswordFilter;
import net.castimage.fms_backend.config.FmsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 弱密码接入集成测试，/6.3，Mockito mock Mapper/闸口，构造 Filter 实例注入 Service，
 * 注册/管理员重置/找回重置三处写入路径弱密码拒绝，40001，落库未调用、正常密码通过、
 * 登录成功弱密码标记开关、总开关关闭放行、锁定阈值与密码最小长度可配置生效。
 */
class WeakPasswordIntegrationTest {

    private static final UUID UID = UuidV7.next();
    private static final UUID COMPANY_ID = UuidV7.next();
    private static final String STRONG_PASSWORD = "Kx#9mQ2vLpZ4sD!2026";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final AuthFunctionMapper authFunctionMapper = mock(AuthFunctionMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final ModuleMapper moduleMapper = mock(ModuleMapper.class);

    @AfterEach
    void clearContext() {
        RequestContext.clear(); // 防 ThreadLocal 串测，
    }

    private AuthServiceImpl newAuthService(FmsProperties props, WeakPasswordFilter filter) {
        // 会话态经 CachePort 收敛，构造器第 6 参，邮件服务第 7 参 mock（props 默认 mail.enabled=false，
        // recovery 流程不会触碰 sender，仅构造签名占位）
        return new AuthServiceImpl(userMapper, roleMapper, authFunctionMapper, props, filter,
                new InMemoryCachePort(), mock(net.castimage.fms_backend.Utils.MailService.class));
    }

    private OrgServiceImpl newOrgService(FmsProperties props, WeakPasswordFilter filter) {
        return new OrgServiceImpl(companyMapper, departmentMapper, userMapper, roleMapper,
                permissionMapper, moduleMapper, authFunctionMapper, filter, props);
    }

    private void setWriteContext() {
        RequestContext.set(new RequestContext(UID, JwtRole.ADMIN, null, null, "test-trace"));
    }

    private User activeUser(String loginName, String rawPassword) {
        User user = new User();
        user.setId(UID);
        user.setCompanyId(COMPANY_ID);
        user.setLoginName(loginName);
        user.setDisplayName(loginName);
        user.setPassHash(PasswordUtils.encode(rawPassword));
        user.setStatus("ACTIVE");
        user.setFailedLoginCount(0);
        user.setMustChangePwd(false);
        user.setMfaEnabled(false);
        return user;
    }

    /**
     * 构造 auth.verify_login 闸口返回的 jsonb（登录走 03c 闸口流，Java 侧不再直查 um.users，
     * 匿名 SELECT 被 rls is_self 恒拒，联调实测，mock 返回闸口 jsonb 等价真实行为）
     */
    private static String verifyLoginJson(boolean ok, String status, UUID userId, int failedCount) {
        return "{\"ok\":" + ok + ",\"status\":\"" + status + "\",\"user_id\":\"" + userId
                + "\",\"company_id\":\"" + COMPANY_ID + "\",\"mfa_enabled\":false,\"locked_until\":null,"
                + "\"must_change_pwd\":false,\"failed_login_count\":" + failedCount + "}";
    }

    // ---------------- 接入点，注册/重置/找回 弱密码拒绝 ----------------

    @Test
    void weakPasswordRegistrationRejectedWith40001() {
        setWriteContext();
        OrgServiceImpl orgService = newOrgService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01));
        // password123，9 位过长度校验，8-64，且在内置弱密码集合，命中布隆预筛则 40001
        UserCreateRequest req = new UserCreateRequest("bob", "password123", null, "bob@x.com", COMPANY_ID, true);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> orgService.createUser(req));
        assertEquals(FmsBaseException.CODE_BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("常见弱密码"));
        verify(authFunctionMapper, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void strongPasswordRegistrationPasses() {
        setWriteContext();
        OrgServiceImpl orgService = newOrgService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01));
        User created = activeUser("bob", STRONG_PASSWORD);
        when(authFunctionMapper.createUser(anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(UID);
        when(userMapper.selectById(UID)).thenReturn(created);
        when(roleMapper.selectRolesByUserId(UID)).thenReturn(List.of());

        UserCreateRequest req = new UserCreateRequest("bob", STRONG_PASSWORD, null, "bob@x.com", COMPANY_ID, true);
        var view = orgService.createUser(req);
        assertNotNull(view);
        assertEquals("bob", view.loginName());
        verify(authFunctionMapper, times(1)).createUser(anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void adminResetPasswordWeakRejected() {
        setWriteContext();
        OrgServiceImpl orgService = newOrgService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01));
        when(userMapper.selectById(UID)).thenReturn(activeUser("bob", STRONG_PASSWORD));

        ResetPasswordRequest req = new ResetPasswordRequest("password123");
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> orgService.resetPassword(UID, req));
        assertEquals(FmsBaseException.CODE_BAD_REQUEST, ex.getCode());
        verify(authFunctionMapper, never()).setPassword(any(), any());
    }

    @Test
    void recoveryResetWeakRejected() {
        AuthServiceImpl authService = newAuthService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01));
        when(authFunctionMapper.recoveryLookup(anyString(), anyString())).thenReturn(UID);
        // 先走 recovery 拿一次性票据，闸口 mock 放行 email 匹配
        var recovery = authService.recovery(new RecoveryRequest("alice", "alice@x.com"));
        assertNotNull(recovery.ticket());

        RecoveryResetRequest req = new RecoveryResetRequest(recovery.ticket(), "password123");
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> authService.recoveryReset(req, "1.2.3.4", "UA"));
        assertEquals(FmsBaseException.CODE_BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("常见弱密码"));
        verify(authFunctionMapper, never()).setPassword(any(), any());
    }

    @Test
    void recoveryResetStrongPasswordPasses() {
        AuthServiceImpl authService = newAuthService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01));
        when(authFunctionMapper.recoveryLookup(anyString(), anyString())).thenReturn(UID);
        var recovery = authService.recovery(new RecoveryRequest("alice", "alice@x.com"));

        RecoveryResetRequest req = new RecoveryResetRequest(recovery.ticket(), STRONG_PASSWORD);
        authService.recoveryReset(req, "1.2.3.4", "UA"); // 不抛即通过
        verify(authFunctionMapper, times(1)).setPassword(any(), anyString());
    }

    // ---------------- 登录成功弱密码标记，mark-on-login 开关 ----------------

    private LoginResponse doLogin(AuthServiceImpl authService, String password) {
        when(authFunctionMapper.verifyLogin(anyString(), anyString()))
                .thenReturn(verifyLoginJson(true, "ACTIVE", UID, 0));
        when(authFunctionMapper.setLocalUserId(any())).thenReturn(null);
        when(userMapper.selectById(UID)).thenReturn(activeUser("alice", password));
        when(roleMapper.selectRolesByUserId(UID)).thenReturn(List.of());
        when(authFunctionMapper.jwtSign(anyString())).thenReturn("eyJ.fake.sig");
        return authService.login(new LoginRequest("alice", password), "1.2.3.4", "UA");
    }

    @Test
    void loginMarksWeakPasswordWhenSwitchOn() {
        FmsProperties props = new FmsProperties();
        props.getSecurity().getWeakPassword().setMarkOnLogin(true);
        LoginResponse resp = doLogin(newAuthService(props, WeakPasswordFilter.create(1024, 0.01)), "123456");
        assertFalse(resp.mfaRequired());
        assertTrue(resp.requirePasswordChange(), "开关开启 + 弱密码登录应标记改密");
    }

    @Test
    void loginNoMarkForStrongPasswordWhenSwitchOn() {
        FmsProperties props = new FmsProperties();
        props.getSecurity().getWeakPassword().setMarkOnLogin(true);
        LoginResponse resp = doLogin(newAuthService(props, WeakPasswordFilter.create(1024, 0.01)), STRONG_PASSWORD);
        assertFalse(resp.requirePasswordChange());
    }

    @Test
    void loginMarksWeakPasswordByDefault() {
        // 默认 mark-on-login=true，生产默认开启，弱密码登录成功即标记改密
        LoginResponse resp = doLogin(newAuthService(new FmsProperties(), WeakPasswordFilter.create(1024, 0.01)), "123456");
        assertTrue(resp.requirePasswordChange(), "默认开启时弱密码登录应标记改密");
        assertNotNull(resp.accessToken());
    }

    @Test
    void mfaFirstStepNeverMarks() {
        FmsProperties props = new FmsProperties();
        props.getSecurity().getWeakPassword().setMarkOnLogin(true);
        User user = activeUser("alice", "123456");
        user.setMfaEnabled(true);
        when(authFunctionMapper.verifyLogin(anyString(), anyString()))
                .thenReturn(verifyLoginJson(true, "ACTIVE", UID, 0));
        when(authFunctionMapper.setLocalUserId(any())).thenReturn(null);
        when(userMapper.selectById(UID)).thenReturn(user);
        when(roleMapper.selectRolesByUserId(UID)).thenReturn(List.of());

        LoginResponse resp = newAuthService(props, WeakPasswordFilter.create(1024, 0.01))
                .login(new LoginRequest("alice", "123456"), "1.2.3.4", "UA");
        assertTrue(resp.mfaRequired());
        assertFalse(resp.requirePasswordChange(), "2FA 首步未验密，恒 false");
    }

    // ---------------- 开关与参数配置化 ----------------

    @Test
    void disabledSwitchLetsWeakPasswordThrough() {
        setWriteContext();
        OrgServiceImpl orgService = newOrgService(new FmsProperties(), WeakPasswordFilter.disabled());
        UserCreateRequest req = new UserCreateRequest("bob", "password123", null, "bob@x.com", COMPANY_ID, true);
        when(authFunctionMapper.createUser(anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(UID);
        when(userMapper.selectById(UID)).thenReturn(activeUser("bob", "password123"));
        when(roleMapper.selectRolesByUserId(UID)).thenReturn(List.of());
        orgService.createUser(req); // 不抛
        verify(authFunctionMapper, times(1)).createUser(anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void loginLockThresholdConfigurable() {
        // fms.login.max-failures=3 + 已有 2 次失败则第 3 次失败即锁定，配置生效，默认 6，
        // 计数与锁定走 auth.record_login_failure 闸口（2026-08-13 并发安全修复，行级原子自增
        // 取代 Java 侧读-改-写，达阈值同事务置 LOCKED，返回 {failed_login_count, locked}）
        FmsProperties props = new FmsProperties();
        props.getLogin().setMaxFailures(3);
        when(authFunctionMapper.verifyLogin(anyString(), anyString()))
                .thenReturn(verifyLoginJson(false, "ACTIVE", UID, 2));
        when(authFunctionMapper.recordLoginFailure(any(), anyInt(), anyInt()))
                .thenReturn("{\"failed_login_count\":3,\"locked\":true}");

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> newAuthService(props, WeakPasswordFilter.create(1024, 0.01))
                        .login(new LoginRequest("alice", "wrong-password"), "1.2.3.4", "UA"));
        assertTrue(ex.getMessage().contains("账户已锁定"));
        verify(authFunctionMapper, times(1)).recordLoginFailure(any(), anyInt(), anyInt()); // 达阈值置 LOCKED 一次
    }

    @Test
    void passwordMinLengthConfigurableTightens() {
        // fms.security.password-min-length=12 收紧生效，10 位密码即使非弱也被 40001 拒绝，min 取 max(配置,8)
        setWriteContext();
        FmsProperties props = new FmsProperties();
        props.getSecurity().setPasswordMinLength(12);
        OrgServiceImpl orgService = newOrgService(props, WeakPasswordFilter.create(1024, 0.01));
        UserCreateRequest req = new UserCreateRequest("bob", "Kx#9mQ2vLp", null, "bob@x.com", COMPANY_ID, true);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> orgService.createUser(req));
        assertEquals(FmsBaseException.CODE_BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("长度须在 12"));
        verify(authFunctionMapper, never()).createUser(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void configCannotWeakenBelowDtoFloor() {
        // 配置 min=4 不生效，取 max(4,8)=8，8 位以下仍拒绝，防 dto @Size(min=8) 与配置漂移出更弱策略
        setWriteContext();
        FmsProperties props = new FmsProperties();
        props.getSecurity().setPasswordMinLength(4);
        OrgServiceImpl orgService = newOrgService(props, WeakPasswordFilter.create(1024, 0.01));
        UserCreateRequest req = new UserCreateRequest("bob", "Kx#9mQ2", null, "bob@x.com", COMPANY_ID, true);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> orgService.createUser(req));
        assertEquals(FmsBaseException.CODE_BAD_REQUEST, ex.getCode());
    }
}
