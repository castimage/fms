package net.castimage.fms_backend.Model;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.castimage.fms_backend.Utils.TraceIdUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FMS 统一响应体 {@code {code, message, data, traceId}}：成功 code=0/data=业务数据，失败 code=错误码/data=null
 * （与网关同构，不向客户端泄露细节）；data 与 traceId 恒渲染（@JsonInclude ALWAYS，全链路日志关联依赖 traceId）。
 *
 * @param <T> 业务数据类型
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponse<T> {

    /** 成功码 */
    public static final int CODE_OK = 0;
    /** 成功消息 */
    public static final String MESSAGE_OK = "ok";

    /** 业务错误码（成功恒 0） */
    private int code;

    /** 消息（成功恒 "ok"） */
    private String message;

    /** 业务数据；错误路径恒 null（注解保证成功路径即使为 null 也渲染） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private T data;

    /** 链路追踪 ID（网关透传/后端生成；注解保证恒渲染） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String traceId;
    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 统一响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, MESSAGE_OK, data, currentTraceId()); // 成功路径恒带 traceId，
    }

    /**
     * 错误响应（traceId 由调用方/异常处理器另行回填）。
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 统一响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, currentTraceId());
    }

    /**
     * 错误响应（携带 traceId）。
     *
     * @param code    业务错误码
     * @param message 错误消息
     * @param traceId 链路追踪 ID
     * @param <T>     数据类型
     * @return 统一响应
     */
    public static <T> ApiResponse<T> error(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }

    /**
     * 取当前链路 traceId：优先 MDC（TraceIdFilter 写入），兜底现场生成 v4（统一委托 TraceIdUtil.current）。
     *
     * @return traceId（恒非 null）
     */
    private static String currentTraceId() {
        return TraceIdUtil.current(); // 兜底生成不落库，
    }








}
