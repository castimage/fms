package net.castimage.fms_backend.Model.dto;

import net.castimage.fms_backend.Model.vo.UserSessionView;

/**
 * 登录响应：JWT 令牌对或 2FA 两步验证票据（mfaRequired=true 时无令牌）；
 * requirePasswordChange 为弱密码标记（fms.security.weak-password.mark-on-login=true 且登录密码命中弱密码集合时置位，默认恒 false，响应契约不变）。
 */
public record LoginResponse(
        /** access token（JWT，2h；2FA 未完成时为 null） */
        String accessToken,
        /** refresh token（JWT，7d；2FA 未完成时为 null） */
        String refreshToken,
        /** 是否要求 TOTP 二次验证 */
        boolean mfaRequired,
        String ticket, // mfaRequired=true 时非 null，5min 有效，
        /** 用户会话视图 */
        UserSessionView user,
        /** 登录密码命中内置弱密码集合（标记开关开启时），默认 false */
        boolean requirePasswordChange) {

    /**
     * 一步登录成功（JWT 已签发，无弱密码标记）。
     */
    public static LoginResponse success(String accessToken, String refreshToken, UserSessionView user) {
        return new LoginResponse(accessToken, refreshToken, false, null, user, false);
    }

    /**
     * 一步登录成功（带弱密码标记；mark-on-login 开关开启时使用）。
     */
    public static LoginResponse success(String accessToken, String refreshToken,
                                        UserSessionView user, boolean requirePasswordChange) {
        return new LoginResponse(accessToken, refreshToken, false, null, user, requirePasswordChange);
    }

    /**
     * 2FA 两步验证（仅票据，不签发 JWT；首步未验密，弱密码标记恒 false）。
     */
    public static LoginResponse mfaRequired(String ticket, UserSessionView user) {
        return new LoginResponse(null, null, true, ticket, user, false);
    }
}
