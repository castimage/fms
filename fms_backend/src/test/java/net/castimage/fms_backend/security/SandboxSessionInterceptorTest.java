package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Security.SandboxSessionInterceptor;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.SandboxRoleHint;
import net.castimage.fms_backend.Utils.UuidV7;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 沙箱会话注入拦截器测试，按连接上实际执行的 sql 序列判定：匿名后台任务复位会话态回空壳角色、
 * 匿名占位角色先复位再注入职责角色、匿名无标记的登录路径完全不碰会话态、有身份按事务有无
 * 分派 set_session 注入与会话级 guc 注入、半采信异常态复位后拒绝注入。
 *
 * 断言落在 sql 语句本身而非调用计数，故"复位语句被漏掉"或"该 SET ROLE 而未 SET ROLE"
 * 都会失败；语句序列与生产路径逐条对齐，不依赖真实数据库。
 */
class SandboxSessionInterceptorTest {

    private static final List<String> RESET_SQL = List.of(
            "RESET ROLE", "RESET fms.user_id", "RESET fms.org_ids", "RESET fms.dept_ids");

    private SandboxSessionInterceptor interceptor;
    private Executor executor;
    private Connection conn;
    private Statement st;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() throws SQLException {
        interceptor = new SandboxSessionInterceptor();
        executor = mock(Executor.class);
        conn = mock(Connection.class);
        st = mock(Statement.class);
        ps = mock(PreparedStatement.class);
        Transaction tx = mock(Transaction.class);
        when(executor.getTransaction()).thenReturn(tx);
        when(tx.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(st);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        SandboxRoleHint.clear();
    }

    /** 拦截器在连接上执行过的 sql 序列，按调用顺序 */
    private List<String> executedSql() throws SQLException {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(st, atLeast(0)).execute(captor.capture());
        return captor.getAllValues();
    }

    /** 走一次查询语句的注入（willDoQuery 与 willDoUpdate 共用 injectSandbox，取其一即可） */
    private void inject() throws SQLException {
        interceptor.willDoQuery(executor, null, null, null, null, null);
    }

    @Test
    void backgroundTaskResetsSessionStateAndKeepsShellRole() throws SQLException {
        SandboxRoleHint.set(SandboxRoleHint.Hint.BACKGROUND_APP);
        inject();
        assertThat(executedSql()).containsExactlyElementsOf(RESET_SQL);
    }

    @Test
    void publicReadHintResetsThenInjectsPublicRole() throws SQLException {
        SandboxRoleHint.set(SandboxRoleHint.Hint.PUBLIC_READ);
        inject();
        assertThat(executedSql()).containsExactly(
                "RESET ROLE", "RESET fms.user_id", "RESET fms.org_ids", "RESET fms.dept_ids",
                "SET ROLE fms_public");
    }

    @Test
    void internalOperatorHintResetsThenInjectsOperatorRole() throws SQLException {
        SandboxRoleHint.set(SandboxRoleHint.Hint.INTERNAL_OPERATOR);
        inject();
        assertThat(executedSql()).containsExactly(
                "RESET ROLE", "RESET fms.user_id", "RESET fms.org_ids", "RESET fms.dept_ids",
                "SET ROLE fms_operator");
    }

    @Test
    void anonymousWithoutHintLeavesSessionStateUntouched() throws SQLException {
        // 登录等认证路径依赖 AuthServiceImpl 手动 SET ROLE fms_operator，拦截器必须全然不碰连接
        inject();
        assertThat(executedSql()).isEmpty();
        verify(conn, never()).createStatement();
    }

    @Test
    void halfTrustedIdentityResetsWithoutInjectingRole() throws SQLException {
        RequestContext.set(new RequestContext(UuidV7.next(), null, null, null, null));
        inject();
        assertThat(executedSql()).containsExactlyElementsOf(RESET_SQL);
    }

    @Test
    void failedResetRollsBackAndRetries() throws SQLException {
        // 事务中止态复位语句必失败，回滚后重试一次，重试的四条语句仍须齐全
        doThrow(new SQLException("current transaction is aborted"))
                .doReturn(false).when(st).execute("RESET ROLE");
        RequestContext.set(new RequestContext(UuidV7.next(), null, null, null, null));
        inject();
        verify(conn, times(1)).rollback();
        assertThat(executedSql()).containsExactly(
                "RESET ROLE", "RESET ROLE", "RESET fms.user_id", "RESET fms.org_ids", "RESET fms.dept_ids");
    }

    @Test
    void identifiedQueryInTransactionInjectsSessionAndRole() throws SQLException {
        java.util.UUID uid = UuidV7.next();
        RequestContext.set(new RequestContext(uid, JwtRole.OPERATOR, null, null, null));
        when(conn.getAutoCommit()).thenReturn(false);
        inject();
        verify(conn).prepareStatement("SELECT auth.set_session(?::uuid, ?::uuid[], ?::uuid[])");
        verify(ps).setObject(1, uid);
        verify(ps).execute();
        assertThat(executedSql()).containsExactly("RESET ROLE", "SET ROLE fms_operator");
    }

    @Test
    void identifiedQueryWithoutTransactionInjectsSessionLevelGuc() throws SQLException {
        java.util.UUID uid = UuidV7.next();
        RequestContext.set(new RequestContext(uid, JwtRole.VIEWER, null, null, null));
        when(conn.getAutoCommit()).thenReturn(true);
        inject();
        assertThat(executedSql()).containsExactly(
                "RESET ROLE",
                "SET fms.user_id = '" + uid + "'",
                "SET fms.org_ids = ''",
                "SET fms.dept_ids = ''",
                "SET ROLE fms_viewer");
        verify(conn, never()).prepareStatement(anyString());
    }
}
