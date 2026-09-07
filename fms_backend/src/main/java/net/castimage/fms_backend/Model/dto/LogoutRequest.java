package net.castimage.fms_backend.Model.dto;

/**
 * 登出请求（POST /api/auth/logout，可空请求体）。
 */
public record LogoutRequest(
        String accessToken, // 入内存黑名单，TTL=access 有效期，
        /** refresh token（加入内存黑名单，TTL=refresh 有效期） */
        String refreshToken) {
}
