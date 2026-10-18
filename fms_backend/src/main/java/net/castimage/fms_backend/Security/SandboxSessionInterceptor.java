package net.castimage.fms_backend.Security;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import net.castimage.fms_backend.Utils.ClientIpContext;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.SandboxRoleHint;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 沙箱会话注入拦截器 MyBatis-Plus InnerInterceptor，每语句执行前向连接注入沙箱上下文，事务内 auth.set_session 与 SET ROLE，无事务退化为会话级 SET 与 SET ROLE，每语句注入幂等防事务边界失效，匿名按端点标记注入最小职责角色或复位回空壳角色，rls 缺身份时 fail-closed */
public class SandboxSessionInterceptor implements InnerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionInterceptor.class);

    @Override
    public boolean willDoQuery(Executor executor, MappedStatement ms, Object parameter,
                               RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        injectSandbox(executor);
        return true;
    }

    @Override
    public boolean willDoUpdate(Executor executor, MappedStatement ms, Object parameter) throws SQLException {
        injectSandbox(executor);
        return true;
    }

    /**
     * 向 MyBatis 当前连接注入沙箱上下文，每语句执行前由 willDoQuery/willDoUpdate 调用，
     * 幂等，防事务边界失效，先取 RequestContext 身份，有身份则按连接是否处于事务分派，
     * 事务内走 auth.set_session 即 bind 参数注入，guc 随提交/回滚自动结束，无事务退化为
     * 会话级 SET，残留由连接池 RESET ALL 兜底。
     *
     * 匿名请求按端点标记分三类，占位角色类先复位再注入职责角色，后台任务类复位回空壳角色，
     * 登录等认证路径类不复位保持既有会话态。复位清掉前序请求提交后残留在复用连接上的
     * 会话级角色与 guc，不复位则本语句继承前任身份。有身份但角色缺失属半采信异常状态，
     * 拒绝注入并告警，同样先复位，防异常态下越权。
     *
     * 安全模型，这是后端数据访问的最后一道闸，任何 sql 执行前都必须把
     * GatewaySignatureFilter 采信的身份，user_id 与角色与数据范围注入 pg 会话，
     * rls 策略据此过滤行。匿名请求不注入任何 guc，空壳登录角色无数据权限，
     * rls 恒拒绝即 fail-closed，有身份但角色为 null 属半采信异常态，正常链路
     * GatewaySignatureFilter 同步写入两者，拒绝注入同样按匿名兜底，绝不让
     * 身份不完整时泄露数据。角色名来自 JwtRole.pgRole 编译期枚举白名单，
     * SET ROLE 拼接的值不可能来自请求输入。
     *
     * 事务分派语义，MyBatis 事务内即 autoCommit=false 时 guc 用 SELECT auth.set_session
     * 以 bind 参数注入，orgIds/deptIds 显式传快照，null 则由函数从 scope 表预载，
     * guc 随提交/回滚自动清空，无跨事务残留，事务外即 autoCommit=true 时 SET LOCAL 无效，
     * 退化为会话级 SET 三个 guc 与 SET ROLE，残留由连接池归还时的 RESET ALL 兜底。
     * 每语句重复注入幂等，天然覆盖同一连接上的事务边界切换。身份注入之后紧接注入审计
     * 上下文 fms.client_ip 与 fms.request_id，供库侧 audit.record 在实参为空时回退取用。
     *
     * @param executor 当前 MyBatis 执行器，其事务持有目标数据库连接
     * @throws SQLException 注入 sql 执行失败，向 MyBatis 传播，本语句不执行
     */
    private void injectSandbox(Executor executor) throws SQLException {
        UUID userId = RequestContext.userId();
        if (userId == null) {
            // 匿名，按端点语义注入最小职责角色，空壳角色仅保留 CONNECT 与认证函数，
            // 公开读口注入 fms_public，内部投递注入 fms_operator，后台定时任务无端点语义，
            // 复位会话态后即为空壳角色，告警检测与队列消费函数仅授权该角色。

            // 无标记的登录等认证路径刻意不复位，其依赖 AuthServiceImpl 手动 SET ROLE fms_operator，
            // 复位会破坏认证流程，残留的职责角色对其无害，其本就需要 operator 角色，
            // 且角色切换依赖会话用户的成员关系，任意职责角色都能切到 operator。
            Connection conn = executor.getTransaction().getConnection();
            SandboxRoleHint.Hint hint = SandboxRoleHint.get();
            if (hint == SandboxRoleHint.Hint.BACKGROUND_APP) {
                // 后台任务无端点标记可依，连接可能带前序请求提交后残留的会话级角色与 guc，
                // 不复位即继承前任职责角色，而该角色对告警与队列函数无 EXECUTE
                resetSessionState(conn);
                return;
            }
            String anonRole = switch (hint) {
                case PUBLIC_READ -> "fms_public";
                case INTERNAL_OPERATOR -> "fms_operator";
                case BACKGROUND_APP -> null; // 已在上方复位后返回
                case null -> null;
            };
            if (anonRole != null) {
                // 复位后再注入，防前序请求残留的会话级角色与 guc 左右本请求判定
                resetSessionState(conn);
                try (Statement st = conn.createStatement()) {
                    st.execute("SET ROLE " + anonRole);
                }
                // 匿名写路径同样留审计 ip 与请求号，公开读无写操作，注入无副作用
                injectAuditContext(conn, false);
            } else {
                // 登录等认证路径刻意不复位会话态，只补审计上下文，不动角色与身份 guc，
                // 使登录过程触发的 um.users 审计行同样带上客户端 ip 与请求号
                injectAuditContext(conn, false);
            }
            return;
        }

        JwtRole role = RequestContext.role();
        if (role == null) {
            log.warn("RequestContext 存在身份但缺角色，拒绝注入(匿名兜底): userId={}", userId);
            // 异常态兜底须先复位残留会话态，前序请求在事务外路径残留的会话级
            // SET ROLE 与 guc 会延续到本语句（Hikari 归还连接不执行 reset，
            // connection-init-sql 仅建连时生效），不复位即继承他人身份读写数据
            Connection conn = executor.getTransaction().getConnection();
            try {
                resetSessionState(conn);
            } catch (SQLException e) {
                conn.rollback(); // 事务中止态复位语句必失败，回滚后重试一次，仍失败向上传播
                resetSessionState(conn);
            }
            return;
        }

        Connection conn = executor.getTransaction().getConnection();
        // 业务请求先重置会话角色，匿名注入（fms_public/fms_operator）残留会破坏 set_session
        // （fms_public 无 auth USAGE 则 42501），脏连接，中止事务残留 ROLLBACK 后重试。

        // 匿名分支不 RESET，登录等认证路径依赖 AuthServiceImpl 手动 SET ROLE fms_operator
        try (Statement st0 = conn.createStatement()) {
            st0.execute("RESET ROLE");
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败，连接已断，由后续语句失败传播
            }
            try (Statement st1 = conn.createStatement()) {
                st1.execute("RESET ROLE");
            }
        }

        boolean inTransaction = !conn.getAutoCommit();
        String pgRole = role.pgRole();
        List<UUID> orgIds = RequestContext.orgIds();
        List<UUID> deptIds = RequestContext.deptIds();

        if (inTransaction) {
            // 事务内 guc 随提交/回滚自动结束，无残留顾虑，
            injectInTransaction(conn, userId, orgIds, deptIds, pgRole);
        } else {
            injectSessionLevel(conn, userId, orgIds, deptIds, pgRole);
        }
    }

    /**
     * 事务内注入，以 bind 参数调用 auth.set_session(?::uuid, ?::uuid[], ?::uuid[]) 预载
     * 数据范围，orgIds/deptIds 显式传入时按调用方快照生效，传 null 时由函数从 scope 表
     * 自行预载，随后 SET ROLE 切换职责角色。SET ROLE 的角色名来自 JwtRole.pgRole 编译期
     * 白名单，绝不拼接请求输入，guc 随事务提交/回滚自动清空，无跨事务残留。
     *
     * @param conn    当前事务连接
     * @param userId  已采信的用户 id（canonical uuid）
     * @param orgIds  显式公司范围，null 表示由 auth.set_session 从 scope 表预载
     * @param deptIds 显式部门范围，null 表示由 auth.set_session 从 scope 表预载
     * @param pgRole  白名单映射出的 pg 职责角色（fms_operator/fms_viewer/fms_auditor/fms_report）
     * @throws SQLException 任一注入语句执行失败
     */
    private void injectInTransaction(Connection conn, UUID userId, List<UUID> orgIds,
                                     List<UUID> deptIds, String pgRole) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT auth.set_session(?::uuid, ?::uuid[], ?::uuid[])")) {
            ps.setObject(1, userId); // pgjdbc 将 java.util.uuid 映射为 pg uuid，
            ps.setArray(2, toArray(conn, orgIds));
            ps.setArray(3, toArray(conn, deptIds));
            ps.execute();
        }

        try (Statement st = conn.createStatement()) {
            st.execute("SET ROLE " + pgRole);
        }

        injectAuditContext(conn, true);
    }

    /**
     * 注入审计上下文 fms.client_ip 与 fms.request_id，供库侧 audit.record 在实参为空时
     * 回退取用，使业务审计行带上客户端 ip 与全链路 traceId。两值均来自服务端，ip 由网关
     * 剥离伪造后经 RequestUtil 清洗，请求号取 TraceIdUtil 规范化后的 traceId，统一走
     * set_config 绑定参数，无拼接注入面。事务内 local 语义随事务结束，事务外为会话级，
     * 由连接归还时的 RESET ALL 兜底。
     *
     * @param conn  当前连接
     * @param local true 为事务内局部生效，false 为会话级
     * @throws SQLException 注入语句执行失败
     */
    private void injectAuditContext(Connection conn, boolean local) throws SQLException {
        String ip = ClientIpContext.get();
        String requestId = RequestContext.traceId();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('fms.client_ip', ?, ?), set_config('fms.request_id', ?, ?)")) {
            ps.setString(1, ip == null ? "" : ip);
            ps.setBoolean(2, local);
            ps.setString(3, requestId == null ? "" : requestId);
            ps.setBoolean(4, local);
            ps.execute();
        }
    }

    /**
     * 无事务连接（autoCommit=true）的注入退化路径，SET LOCAL 在事务外无效，故改会话级
     * SET 三个 guc（fms.user_id / fms.org_ids / fms.dept_ids）+ SET ROLE。注入值均为
     * 服务端生成的 canonical uuid，拼接前无可信边界问题，会话级 guc 在本连接归还连接池
     * 前可能残留，由 rls 策略侧依赖的 RESET ALL 兜底清理。
     *
     * @param conn    当前连接，autoCommit=true，无事务上下文
     * @param userId  已采信的用户 id
     * @param orgIds  公司范围（null/空则空串，函数侧按未显式提供处理）
     * @param deptIds 部门范围（null/空则空串）
     * @param pgRole  白名单映射出的 pg 职责角色
     * @throws SQLException 任一注入语句执行失败
     */
    private void injectSessionLevel(Connection conn, UUID userId, List<UUID> orgIds,
                                    List<UUID> deptIds, String pgRole) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET fms.user_id = '" + userId + "'");
            st.execute("SET fms.org_ids = '" + (orgIds == null ? "" : joinCsv(orgIds)) + "'");
            st.execute("SET fms.dept_ids = '" + (deptIds == null ? "" : joinCsv(deptIds)) + "'");
            st.execute("SET ROLE " + pgRole);
        }

        injectAuditContext(conn, false);
    }

    /** List<uuid> 转 pg uuid[] 数组参数，null/空返回 NULL（auth.set_session 从 scope 表预载） */
    private Array toArray(Connection conn, List<UUID> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        String[] arr = ids.stream().map(UUID::toString).toArray(String[]::new);
        return conn.createArrayOf("uuid", arr);
    }

    private static String joinCsv(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /**
     * 复位连接会话态，先 RESET ROLE 回到登录角色，再清三个沙箱 guc 使 rls 取不到身份，
     * 供匿名后台路径注入前与半采信异常态兜底调用，复位前该连接可能带前序请求残留的
     * 会话级角色与 guc。
     *
     * 角色复位只能走 RESET ROLE，角色切换依赖会话用户的成员关系，登录角色可切任意
     * 职责角色，职责角色之间无反向成员关系，故 SET ROLE 回空壳角色行不通。
     *
     * @param conn 目标连接
     * @throws SQLException 复位语句执行失败，事务中止态下由调用方回滚后重试
     */
    private void resetSessionState(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("RESET ROLE");
            st.execute("RESET fms.user_id");
            st.execute("RESET fms.org_ids");
            st.execute("RESET fms.dept_ids");
            // 审计两参数改用 set_config 置空，RESET 对从未定义过的自定义参数会报 unrecognized
            st.execute("SELECT set_config('fms.client_ip', '', false)");
            st.execute("SELECT set_config('fms.request_id', '', false)");
        }
    }
}
