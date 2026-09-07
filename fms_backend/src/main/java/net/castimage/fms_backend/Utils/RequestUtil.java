package net.castimage.fms_backend.Utils;

import jakarta.servlet.http.HttpServletRequest;

/** 请求工具，客户端 ip 提取（登录审计 um.login_log.ip 用，xff 由网关剥离伪造值，后端取首段，非法值兜底 0.0.0.0 防 ::inet 抛错） */
public final class RequestUtil {

    private RequestUtil() {
    }

    /** 提取客户端 ip，xff 首段取 remoteAddr，无则 0.0.0.0 兜底，恒非 null */
    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        // xff 首段即真实客户端，网关已剥离伪造值，
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        String remote = request.getRemoteAddr();
        // remoteAddr 兜底，容器直连场景无 xff，
        return remote == null || remote.isBlank() ? "0.0.0.0" : remote;
    }

    /** inet 兼容清洗，仅允许 ip 类字符，数字/点/冒号/hex，否则回退 0.0.0.0（防 ::inet 抛 22P02） */
    public static String sanitizeIp(String ip) {
        if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) {
            return "0.0.0.0";
        }
        return ip;
    }
}
