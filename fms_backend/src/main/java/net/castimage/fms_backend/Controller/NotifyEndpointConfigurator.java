package net.castimage.fms_backend.Controller;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.List;
import java.util.Map;

/**
 * 通知 WebSocket 端点握手配置器：把网关采信的身份从握手请求头搬进端点用户属性，
 * 供端点建立会话时使用。
 *
 * 身份头可信性来自签名链，握手请求经 GatewaySignatureFilter 校验 X-FMS-Signature 后放行，
 * 签名输入含 uid/role 两段，且过滤器会剥离客户端自带的 X-FMS-* 头后再采信，故头值不可伪造；
 * 端点据此取值，不再读查询参数里的 uid，客户端改参数无法订阅他人通知。
 */
public class NotifyEndpointConfigurator extends ServerEndpointConfig.Configurator {

    /** 采信用户 id 的用户属性键（X-FMS-UserId 头值，canonical uuid） */
    public static final String PROP_UID = "fmsUid";

    /** 采信角色短名的用户属性键（X-FMS-Role 头值，五角色白名单之一） */
    public static final String PROP_ROLE = "fmsRole";

    /**
     * 握手期取头写属性：头名大小写不敏感（容器实现不保证原样保留），取同名头的首个值；
     * 缺失即写 null，由端点按身份不完整拒绝连接（fail-closed）。
     */
    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
                                HandshakeResponse response) {
        Map<String, List<String>> headers = request.getHeaders();
        config.getUserProperties().put(PROP_UID, firstHeader(headers, "x-fms-userid"));
        config.getUserProperties().put(PROP_ROLE, firstHeader(headers, "x-fms-role"));
    }

    /** 取指定头名的首个值，大小写不敏感，头名或取值缺失返回 null */
    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> values = entry.getValue();
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && values != null && !values.isEmpty()) {
                return values.getFirst();
            }
        }
        return null;
    }
}
