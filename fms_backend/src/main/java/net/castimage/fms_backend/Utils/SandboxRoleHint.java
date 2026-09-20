package net.castimage.fms_backend.Utils;

/**
 * 沙箱角色提示 ThreadLocal，匿名请求按端点语义注入最小职责角色，不以空壳角色直调，
 * 空壳角色仅保留 CONNECT 与认证函数，符合沙箱最小授权。
 *
 * `PUBLIC_READ` 公开读口即 /api/public/**，注入 fms_public，仅可执行公开参数读取与
 * 公开文档元数据函数，无任何表权限。
 *
 * `INTERNAL_OPERATOR` 内部投递即 /internal/log-batch，经 token 保护，注入 fms_operator
 * 供日志投递函数使用。
 *
 * `BACKGROUND_APP` 后台定时任务无会话身份也无端点语义，拦截器先复位会话态回到登录角色
 * fms_app，告警检测与队列消费函数仅授权该角色。
 *
 * 请求结束由 Controller 在 finally 清理，与 RequestContext 同生命周期约定。
 */
public final class SandboxRoleHint {

    public enum Hint { PUBLIC_READ, INTERNAL_OPERATOR, BACKGROUND_APP }

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
