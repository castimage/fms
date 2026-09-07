package net.castimage.fms_backend.Exception;

/**
 * 限流（42900）：后端侧补充闸（RateLimitFilter 已注册，与网关限流互补）。
 */
public class RateLimitException extends FmsBaseException {

    public RateLimitException(String message) {
        super(FmsBaseException.CODE_RATE_LIMIT, message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(FmsBaseException.CODE_RATE_LIMIT, message, cause);
    }
}
