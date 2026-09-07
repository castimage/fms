package net.castimage.fms_backend.Utils;

/**
 * 沙箱角色提示（ThreadLocal）：匿名请求按端点语义注入最小职责角色，替代 fms_app 空壳
 * 直调——fms_app 仅保留 CONNECT 与认证函数（沙箱原则，用户 2026-08-19 明确）。
 * <p>PUBLIC_READ：公开读口（/api/public/**）则 SET ROLE fms_public（仅 EXECUTE
 * fn_get_param_value / fn_public_document_meta，无任何表权限）；INTERNAL_OPERATOR：
 * 内部投递（/internal/log-batch，token 保护）则 SET ROLE fms_operator（日志投递函数）。
 * 请求结束由 Controller finally 清理（与 RequestContext 同生命周期约定）。
 */
public final class SandboxRoleHint {

    public enum Hint { PUBLIC_READ, INTERNAL_OPERATOR }

    private static final ThreadLocal<Hint> HINT = new ThreadLocal<>();

    private SandboxRoleHint() {
    }

    public static void set(Hint h) {
        HINT.set(h);
    }

    public static Hint get() {
        return HINT.get();
    }

    public static void clear() {
        HINT.remove();
    }
}
