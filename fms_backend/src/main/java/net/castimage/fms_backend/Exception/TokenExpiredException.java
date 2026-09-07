package net.castimage.fms_backend.Exception;

/**
 * token 过期（40101）：refresh token 验签/exp 校验失败、token 已登出。
 */
public class TokenExpiredException extends FmsBaseException {

    public TokenExpiredException(String message) {
        super(FmsBaseException.CODE_TOKEN_EXPIRED, message); // 过期令牌统一 40101，
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(FmsBaseException.CODE_TOKEN_EXPIRED, message, cause);
    }
}
