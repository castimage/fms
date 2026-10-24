package net.castimage.fms_backend.Utils;

/**
 * 客户端 ip 上下文 ThreadLocal，请求进入时由 TraceIdFilter 经 RequestUtil 提取规范化，
 * 优先网关覆盖写入的 X-Real-IP，其次 X-Forwarded-For 末段或 remoteAddr，供沙箱会话
 * 拦截器注入 fms.client_ip，库侧 audit.record 在实参为空时回退取用。
 *
 * ip 只作审计元数据，不参与身份与权限判定；网关保留客户端自带的 X-Forwarded-For 并追加
 * 真实对端，首段可伪造，故不取首段。请求结束由 TraceIdFilter 在 finally 清理，与
 * RequestContext 同生命周期。
 */
public final class ClientIpContext {

    private static final ThreadLocal<String> IP = new ThreadLocal<>();

    private ClientIpContext() {
    }

    public static void set(String ip) {
        IP.set(ip);
    }

    public static String get() {
        return IP.get();
    }

    public static void clear() {
        IP.remove();
    }
}
