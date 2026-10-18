package net.castimage.fms_backend.Utils;

/**
 * 客户端 ip 上下文 ThreadLocal，请求进入时由 TraceIdFilter 从 X-Forwarded-For 首段或
 * remoteAddr 提取，经 RequestUtil 清洗后写入，供沙箱会话拦截器注入 fms.client_ip，
 * 库侧 audit.record 在实参为空时回退取用，使审计行带上发起请求的客户端 ip。
 *
 * ip 只作审计元数据，不参与身份与权限判定，网关已剥离客户端伪造的 X-Forwarded-For，
 * 后端取首段即真实客户端。请求结束由 TraceIdFilter 在 finally 清理，与 RequestContext
 * 同生命周期。
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
