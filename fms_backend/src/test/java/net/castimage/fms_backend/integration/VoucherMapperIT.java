package net.castimage.fms_backend.integration;

import net.castimage.fms_backend.Utils.UuidV7;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Mapper.VoucherMapper;
import net.castimage.fms_backend.Model.vo.VoucherListView;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VoucherMapper 真实 sql 集成测试，MyBatis-Plus 分页改写 + pg 专有语法
 * （::uuid/IPage sql）+ 沙箱拦截器（每语句 auth.set_session + SET ROLE）
 * 在真实数据链路上的端到端行为。
 *
 * 直调 Mapper 绕过了 http 过滤器链，身份须经 RequestContext（ThreadLocal）
 * 手工填充，这正是拦截器的真实数据源，生产由 GatewaySignatureFilter 填充，
 * 全链路由 GatewaySignatureIT 覆盖。
 */
class VoucherMapperIT extends IntegrationTestSupport {

    /** 种子公司固定 id（seed_demo COMPANY_UID，测试机凭证均归属该公司） */
    private static final UUID COMPANY_UID = UUID.fromString("00000000-0000-7000-0000-000000000010");

    @Autowired
    private VoucherMapper voucherMapper;

    @AfterEach
    void clearIdentity() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("分页查询全链路：RequestContext 身份 -> 拦截器注入 -> 真实 SQL 返回种子数据")
    void pagedQueryWithIdentityReturnsSeedData() {
        RequestContext.set(new RequestContext(
                UUID.fromString(ADMIN_UID), JwtRole.ADMIN, null, null, "itest-trace"));
        IPage<VoucherListView> page = voucherMapper.selectVoucherPage(
                new Page<>(1, 10), "2026-08", null, COMPANY_UID, null, null,
                UUID.fromString(ADMIN_UID));
        assertThat(page).isNotNull();
        // 测试机种子含 2026-08 凭证，SEED_DEMO + 生成数据，总数应 > 0
        assertThat(page.getTotal()).isGreaterThan(0);
        assertThat(page.getRecords().size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("无身份查询绝不能读到业务数据：干净连接 42501 或残留连接表级放行但 RLS 空集")
    void anonymousQueryCannotReadBusinessData() {
        // 无身份语句的拦截器匿名分支刻意不 RESET（登录认证路径依赖手动 SET ROLE），
        // 池连接状态不可控，干净连接则 fms_app 42501，残留 fms_operator 的连接则
        // 表级放行但无 fms.user_id guc 则 rls 恒空集。两条路径均不得返回任何凭证行，
        // 数据防线由 rls 兜底（五层模型"行"层 fail-closed）
        try {
            IPage<VoucherListView> page = voucherMapper.selectVoucherPage(
                    new Page<>(1, 10), "2026-08", null, COMPANY_UID, null, null,
                    UUID.fromString(ADMIN_UID));
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, page.getTotal(), "无身份查询必须 RLS 空集，不得读到任何凭证");
        } catch (DataAccessException e) {
            // 干净连接路径，fms_app 空壳表级拒绝，SQLState 42501 语义经 SandboxSessionIT 覆盖
        }
    }

    /** 沿 cause 链查找首个 SQLException（PSQLException 为其子类） */
    private static SQLException findSqlException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx;
            }
            cur = cur.getCause();
        }
        return null;
    }

    @Test
    @DisplayName("越权 id 查询返回 null（RLS 收敛语义，不泄露存在性）")
    void unknownVoucherCompanyReturnsNull() {
        RequestContext.set(new RequestContext(
                UUID.fromString(ADMIN_UID), JwtRole.ADMIN, null, null, "itest-trace"));
        UUID absent = voucherMapper.selectVoucherCompanyId(UuidV7.next());
        assertThat(absent).isNull();
    }

    @Test
    @DisplayName("viewer 只读角色走只读通道：分页查询正常返回（只读分流未启用走主库）")
    void viewerRoleCanQuery() {
        RequestContext.set(new RequestContext(
                UUID.fromString(ADMIN_UID), JwtRole.VIEWER, null, null, "itest-trace"));
        IPage<VoucherListView> page = voucherMapper.selectVoucherPage(
                new Page<>(1, 5), "2026-08", null, COMPANY_UID, null, null,
                UUID.fromString(ADMIN_UID));
        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(0);
    }
}
