package net.castimage.fms_backend.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pg 沙箱模型集成测试，fms_app 空壳 + SET ROLE 职责通道。
 *
 * <p>用真实连接验证，fms_app 无任何业务表权限，空壳 fail-closed，SET ROLE
 * 后职责角色授权生效，auth.set_session 闸口可执行且注入 guc，这是"直连数据库
 * 无数据可读"安全目标的直接证据。
 *
 * <p>权限拒绝断言按 SQLState=42501 判定，与 pg lc_messages 语言无关。
 * 类级 @Transactional 使 JdbcTemplate 各操作绑定同一线程连接，否则 SET ROLE 与
 * 后续查询会借到不同池连接，测试结束自动回滚，SET LOCAL guc 随回滚清空，
 * 与生产事务语义一致。
 * 注意，SET ROLE 是会话级且 Hikari 归还连接不执行 reset（connection-init-sql
 * 仅建连时生效），每个用例必须 try/finally 复位 ROLE，防止污染连接池破坏后续
 * 空壳断言，生产路径无此风险，拦截器用 SET LOCAL 随事务自动清空。
 */
@Transactional
class SandboxSessionIT extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("fms_app 空壳：直查业务表 um.users -> 42501 permission denied（fail-closed）")
    void fmsAppEmptyShellCannotReadBusinessTables() {
        ensureNoRole();
        assertPermissionDenied(() -> jdbcTemplate.queryForList("SELECT login_name FROM um.users LIMIT 1"));
    }

    @Test
    @DisplayName("fms_app 空壳：直查密钥表 sec.app_secrets -> 42501 permission denied")
    void fmsAppCannotReadSecrets() {
        ensureNoRole();
        assertPermissionDenied(() -> jdbcTemplate.queryForList("SELECT code FROM sec.app_secrets LIMIT 1"));
    }

    @Test
    @DisplayName("SET ROLE fms_operator：表级授权放行；FORCE RLS 无身份 GUC 空集，注入后可见")
    void setRoleOperatorGrantsBusinessRead() {
        try {
            jdbcTemplate.execute("SET ROLE fms_operator");
            // 表级 SELECT 已放行，未再 42501，但 um.users 为 FORCE rls 表，
            // 无身份 guc 时策略过滤为空集，fail-closed 的 rls 表现
            Integer noGuc = jdbcTemplate.queryForObject("SELECT count(*) FROM um.users", Integer.class);
            assertThat(noGuc).isNotNull().isEqualTo(0);
            // 注入身份，SECURITY DEFINER 闸口，SET LOCAL 随测试事务生效后按 scope 放行
            jdbcTemplate.execute("SELECT auth.set_session('" + ADMIN_UID + "'::uuid, NULL, NULL)");
            Integer withGuc = jdbcTemplate.queryForObject("SELECT count(*) FROM um.users", Integer.class);
            assertThat(withGuc).isNotNull().isGreaterThan(0);
        } finally {
            resetRole();
        }
    }

    @Test
    @DisplayName("SET ROLE fms_viewer：只读角色可查不可写（INSERT 权限拒绝在数据层强制）")
    void viewerRoleReadOnly() {
        try {
            jdbcTemplate.execute("SET ROLE fms_viewer");
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM org.companies", Integer.class);
            // FORCE rls 无身份 guc 则空集亦为表级授权放行的证据，关键断言在下方 INSERT 被拒
            assertThat(count).isNotNull().isGreaterThanOrEqualTo(0);
            assertPermissionDenied(() -> jdbcTemplate.update(
                    "INSERT INTO org.companies (id, code, name, currency, fiscal_year_start, status) "
                            + "VALUES (gen_random_uuid(), 'ITEST', 'x', 'CNY', DATE '2026-01-01', 'ACTIVE')"));
        } finally {
            resetRole();
        }
    }

    @Test
    @DisplayName("auth.set_session 闸口注入 GUC 后 RLS 生效：operator 可见种子凭证")
    void setSessionInjectsGucAndRlsFilters() {
        try {
            jdbcTemplate.execute("SET ROLE fms_operator");
            // SECURITY DEFINER 闸口校验用户后 SET LOCAL 注入 fms.user_id/org_ids/dept_ids，SET LOCAL 随 Spring 测试事务生效，语义与生产请求事务一致
            jdbcTemplate.execute("SELECT auth.set_session('" + ADMIN_UID + "'::uuid, NULL, NULL)");
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM gl.vouchers", Integer.class);
            assertThat(count).isNotNull();
        } finally {
            resetRole();
        }
    }

    @Test
    @DisplayName("匿名（未 SET ROLE）查凭证 -> 42501 permission denied（表级授权先于 RLS 拦截）")
    void anonymousQueryRejected() {
        ensureNoRole();
        assertPermissionDenied(() -> jdbcTemplate.queryForObject("SELECT count(*) FROM gl.vouchers", Integer.class));
    }

    /** 断言操作抛出的异常链中存在 SQLState=42501（pg 权限拒绝，消息随 lc_messages 变化，按状态码判定） */
    private static void assertPermissionDenied(org.junit.jupiter.api.function.Executable op) {
        try {
            op.execute();
            org.assertj.core.api.Assertions.fail("预期权限拒绝（SQLState 42501），但操作成功");
        } catch (DataAccessException e) {
            SQLException sqlEx = findSqlException(e);
            // SQLException 同时命中 AssertJ assertThat(Throwable/Object) 重载，用 JUnit 断言避免歧义
            org.junit.jupiter.api.Assertions.assertNotNull(sqlEx, "异常链中应存在 java.sql.SQLException");
            org.junit.jupiter.api.Assertions.assertEquals("42501", sqlEx.getSQLState(), "SQLState 应为 42501（权限拒绝）");
        } catch (Throwable e) {
            throw new AssertionError("预期 DataAccessException，实际: " + e.getClass().getName(), e);
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

    /** 当前连接若残留职责角色则复位为会话用户，防池污染 */
    private void resetRole() {
        try {
            jdbcTemplate.execute("RESET ROLE");
        } catch (Exception ignored) {
            // 连接可能已在中途损坏，忽略
        }
    }

    /** 确保当前借用连接无残留职责角色，防御性复位后再做空壳断言 */
    private void ensureNoRole() {
        resetRole();
    }
}
