package net.castimage.fms_backend.Utils;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/** 请求工具，客户端 ip 提取与规范化（登录审计与限流键用，X-Real-IP 由网关覆盖写入，优先采信） */
public final class RequestUtil {

    private static final String UNKNOWN = "0.0.0.0";

    private RequestUtil() {
    }

    /**
     * 提取客户端 ip，优先 X-Real-IP，其次 X-Forwarded-For 末段，最后 remoteAddr，均经规范化，
     * 非法值回退 0.0.0.0。网关会保留客户端自带的 XFF 并追加真实对端，首段可伪造，末段才是网关所见地址；
     * X-Real-IP 由网关覆盖写入单值，故优先级最高。
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = headerValue(request.getHeader("X-Real-IP"));
        if (ip != null) {
            return sanitizeIp(ip);
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            ip = headerValue(parts[parts.length - 1]);
            if (ip != null) {
                return sanitizeIp(ip);
            }
        }

        return sanitizeIp(request.getRemoteAddr());
    }

    /**
     * 解析并规范化 ip 字面量，支持 [v6] 与 host:port、zone 后缀，非法值回退 0.0.0.0（防 ::inet 抛 22P02）。
     * 只接受 ip 字面量，不做域名解析；IPv6 输出为全展开小写形式，IPv4-mapped 归一为 IPv4，
     * 保证等价写法落到同一限流键与审计口径。
     */
    public static String sanitizeIp(String ip) {
        String s = headerValue(ip);
        if (s == null) {
            return UNKNOWN;
        }

        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end <= 1) {
                return UNKNOWN;
            }
            String tail = s.substring(end + 1);
            if (!tail.isEmpty() && !tail.matches(":\\d{1,5}")) {
                return UNKNOWN;
            }
            s = s.substring(1, end);
        } else {
            int colon = s.lastIndexOf(':');
            if (colon > 0 && colon == s.indexOf(':') && s.substring(colon + 1).matches("\\d{1,5}")) {
                s = s.substring(0, colon);
            }
        }

        int zone = s.indexOf('%');
        if (zone > 0) {
            s = s.substring(0, zone);
        }

        try {
            return InetAddress.ofLiteral(s).getHostAddress();
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** 去首尾空白与成对引号，空值返回 null */
    private static String headerValue(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.isEmpty() ? null : s;
    }
}
