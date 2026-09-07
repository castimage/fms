package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Exception.UnauthorizedException;

import java.util.List;
import java.util.UUID;

/** 请求身份上下文（ThreadLocal，仅存活单次请求线程）：TraceIdFilter 注入 traceId，GatewaySignatureFilter 校验后填身份；字段值只允许来自签名校验通过的 X-FMS-* 头与 API-Key 校验结果，Controller 参数一律不得写入 */

public class RequestContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    /** 用户 id（canonical uuid），匿名请求为 null */
    private final UUID userId;

    /** 职责角色短名（X-FMS-Role），匿名请求为 null */
    private final JwtRole role;

    /** 可见公司 id 集合，null 表示由 PG 侧从 scope 表预载（不可变快照） */
    private final List<UUID> orgIds;

    /** 可见部门 id 集合，null 表示由 PG 侧从 scope 表预载（不可变快照） */
    private final List<UUID> deptIds;

    /** 全链路 traceId（网关透传或后端生成） */
    private final String traceId;

    public RequestContext(UUID userId, JwtRole role, List<UUID> orgIds, List<UUID> deptIds, String traceId) {
        this.userId = userId;
        this.role = role;
        // 防御拷贝成不可变快照，杜绝调用方篡改，
        this.orgIds = orgIds == null ? null : List.copyOf(orgIds);
        this.deptIds = deptIds == null ? null : List.copyOf(deptIds);
        this.traceId = traceId;
    }

    /** 当前线程上下文是否已认证（签名校验通过并采信了身份） */
    public static boolean authenticated() {
        RequestContext ctx = HOLDER.get();
        return ctx != null && ctx.userId != null;
    }

    // 静态访问入口

    public static RequestContext get() {
        return HOLDER.get(); // 匿名端点上下文为 null，属常态，
    }

    public static void set(RequestContext ctx) {
        HOLDER.set(ctx);
    }

    /** 请求结束清理 ThreadLocal（防连接池/线程复用污染） */
    public static void clear() {
        HOLDER.remove();
    }

    public static UUID userId() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.userId;
    }

    /**
     * 取当前认证用户 id；未认证（上下文缺失或 userId 为 null，即匿名请求或签名校验未通过）
     * 时抛 UnauthorizedException（40100），调用方无需再判空。仅限已通过网关签名校验或
     * API-Key 校验的端点调用，返回的 id 恒为签名采信后的 canonical uuid，不可能是
     * 客户端伪造值。
     *
     * <p>使用边界：要求身份的业务端点（查本人数据、写操作审计主体等）应以此取值，
     * 不要自行解析请求头——GatewaySignatureFilter 是身份唯一采信点，头可能被剥离或
     * 伪造；上下文缺失只可能发生在匿名端点误调用，属编程错误，按未认证 40100 暴露
     * 而非 NPE，便于快速定位。
     *
     * @return 当前认证用户 id（恒非 null）
     * @throws UnauthorizedException 上下文缺失或 userId 为 null 时抛出（40100）
     */
    public static UUID requireUserId() {
        UUID userId = userId();
        if (userId == null) {
            throw new UnauthorizedException("未认证");
        }
        return userId;
    }

    /**
     * RBAC 写预检：先要求已认证（上下文缺失则 40100），再拒绝只读角色——viewer/auditor/
     * report 三个只读角色一律抛 ForbiddenException（40300），operator/admin 放行。公司/
     * 部门/用户/角色/凭证等写路径端点统一以本方法作第一道闸，角色之后的细粒度数据范围
     * 仍由 PG 侧 RLS 兜底，双层防御。
     *
     * <p>判定语义：只读角色为固定三枚举（VIEWER/AUDITOR/REPORT），新增角色不会自动
     * 获得写权限（枚举显式列举，放行仅 operator/admin 两值）；角色为 null 时与未认证
     * 同语义（40100 而非 40300——先证明身份再谈权限）。写端点应在业务逻辑入口第一行
     * 调用，保证任何写路径（含子资源、批量操作）都先过角色闸。
     *
     * @throws UnauthorizedException 上下文缺失或角色为 null（未认证，40100）
     * @throws ForbiddenException    当前角色为只读角色（40300）
     */
    public static void requireWriteRole() {
        JwtRole role = role();
        if (role == null) {
            throw new UnauthorizedException("未认证");
        }
        if (role == JwtRole.VIEWER || role == JwtRole.AUDITOR || role == JwtRole.REPORT) {
            throw new ForbiddenException("当前角色无写权限");
        }
    }

    /**
     * 管理员专属预检：先要求已认证（上下文缺失则 40100），再要求角色恰为 ADMIN，
     * 否则一律抛 ForbiddenException（40300）。系统管理类端点（/api/admin 全量、
     * /api/org/sod/check）以本方法作第一道闸——operator/viewer/auditor/report 均
     * 不得触碰管理员专属路由，与任务权限矩阵「系统管理 = admin」一致；角色为 null
     * 时与未认证同语义（40100——先证明身份再谈权限）。写端点可在 requireWriteRole
     * 之外按矩阵语义叠加本闸（例如 org 管理写路径仍由 DB 侧 admin.* 权限点兜底）。
     *
     * @throws UnauthorizedException 上下文缺失或角色为 null（未认证，40100）
     * @throws ForbiddenException    当前角色非 ADMIN（40300）
     */
    public static void requireAdminRole() {
        JwtRole role = role();
        if (role == null) {
            throw new UnauthorizedException("未认证");
        }
        if (role != JwtRole.ADMIN) {
            throw new ForbiddenException("该操作仅系统管理员可执行");
        }
    }

    /**
     * 审计模块专属预检：先要求已认证（上下文缺失则 40100），再要求角色为 AUDITOR 或
     * ADMIN，否则一律抛 ForbiddenException（40300）。审计端点（/api/audit 全量）以本
     * 方法作第一道闸——viewer/report/operator 一律不得触碰审计数据，与任务权限矩阵
     * 「审计日志 = auditor/admin」一致；矩阵之外的角色即使在 PG 层经 RLS 行级收敛，
     * 也在 API 层显式拒绝，防"权限过宽"回归（audit:read:all 仅 auditor/admin）。
     *
     * @throws UnauthorizedException 上下文缺失或角色为 null（未认证，40100）
     * @throws ForbiddenException    当前角色非 AUDITOR/ADMIN（40300）
     */
    public static void requireAuditRole() {
        JwtRole role = role();
        if (role == null) {
            throw new UnauthorizedException("未认证");
        }
        if (role != JwtRole.AUDITOR && role != JwtRole.ADMIN) {
            throw new ForbiddenException("该操作仅审计/系统管理员可执行");
        }
    }

    public static JwtRole role() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.role;
    }

    public static List<UUID> orgIds() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.orgIds;
    }

    public static List<UUID> deptIds() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.deptIds;
    }

    public static String traceId() {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.traceId;
    }
}
