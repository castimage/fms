package net.castimage.fms_backend.Controller;


import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import net.castimage.fms_backend.Exception.FmsBaseException;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Utils.TraceIdUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * 全局异常处理器，把业务异常/校验失败/sql 异常映射 FMS 错误码 + http 状态码 + traceId
 * （42501 映射 40300、23505/23503/23514/23502 映射 40900、22P02 映射 40001、P0001 按消息关键词映射 40900、其余 50000）。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常，http 状态码取 FmsBaseException.httpStatus，错误码前三位 */
    @ExceptionHandler(FmsBaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleFmsBaseException(FmsBaseException ex, HttpServletRequest request) {
        String traceId = resolveTraceId();
        log.warn("业务异常 code={} path={} traceId={} msg={}", ex.getCode(), request.getRequestURI(), traceId, ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), traceId));
    }

    /** @RequestBody 参数校验失败则 40001，取首个字段错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return buildError(FmsBaseException.CODE_BAD_REQUEST, message);
    }

    /** @RequestParam/@PathVariable 等约束校验失败则 40001 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(cv -> cv.getPropertyPath() + " " + cv.getMessage())
                .orElse("参数校验失败");
        return buildError(FmsBaseException.CODE_BAD_REQUEST, message);
    }

    /** 请求体不可读，非法 json/类型不匹配则 40001 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return buildError(FmsBaseException.CODE_BAD_REQUEST, "请求体格式错误");
    }

    /** 缺必填 @RequestParam / 参数类型不匹配，如 ?page=abc 则 40001，客户端错误，勿落入 50000 兜底 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingOrMismatchedParam(Exception ex) {
        return buildError(FmsBaseException.CODE_BAD_REQUEST, "参数缺失或格式非法");
    }

    /** multipart 缺 file 部件 / 非 multipart 请求打到 multipart 端点，如 json 体则 40001，客户端错误，勿落入 50000 兜底 */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingMultipartPart(Exception ex) {
        return buildError(FmsBaseException.CODE_BAD_REQUEST, "multipart 上传参数缺失或请求格式非法");
    }

    /** 内容类型不支持（http 415）则 40001 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return buildError(FmsBaseException.CODE_BAD_REQUEST, "不支持的内容类型");
    }

    /** 请求方法不支持，如对 POST-only 端点发 PUT/GET，http 405 则 40001，客户端错误，勿落入 50000 兜底 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return buildError(FmsBaseException.CODE_BAD_REQUEST, "请求方法不支持");
    }

    /** 路由不存在，Spring mvc 资源解析标准异常则 40400，避免落入 Exception 兜底误报 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return buildError(FmsBaseException.CODE_NOT_FOUND, "请求的资源不存在");
    }

    /** 未预期异常，sql 错误按 SQLState 映射，其余 50000，不向客户端泄露堆栈 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex, HttpServletRequest request) {
        SQLException sqlEx = findSqlException(ex);
        if (sqlEx != null) {
            return handleSqlException(sqlEx, request);
        }

        String traceId = resolveTraceId();
        log.error("未预期异常 path={} traceId={}", request.getRequestURI(), traceId, ex);
        return buildError(FmsBaseException.CODE_INTERNAL, "系统内部错误", traceId);
    }

    /**
     * sql 错误码映射，42501 映射 40300 权限，23505/23503/23514/23502 映射 40900 唯一/外键/检查/非空，22P02 映射 40001（非法 uuid 文本）、
     * P0001 则按消息关键词（balance/period/closed/immutable/leaf）映射 40900，其余映射 50000。
     */
    private ResponseEntity<ApiResponse<Void>> handleSqlException(SQLException sqlEx, HttpServletRequest request) {
        String sqlState = sqlEx.getSQLState();
        String message = sqlEx.getMessage() == null ? "" : sqlEx.getMessage().toLowerCase();
        String traceId = resolveTraceId();
        log.warn("SQL 异常 sqlState={} path={} traceId={} msg={}", sqlState, request.getRequestURI(), traceId, message);

        return switch (sqlState) {
            case "42501" -> buildError(FmsBaseException.CODE_FORBIDDEN, "权限不足", traceId);
            case "23505" -> buildError(FmsBaseException.CODE_CONFLICT, "数据已存在（唯一约束冲突）", traceId);
            case "23503" -> buildError(FmsBaseException.CODE_CONFLICT, "数据被引用，操作冲突", traceId);
            case "23514", "23502" -> buildError(FmsBaseException.CODE_CONFLICT, "数据不满足约束条件", traceId);
            case "22P02" -> buildError(FmsBaseException.CODE_BAD_REQUEST, "参数格式非法", traceId); // 非法 uuid 文本，
            // P0001（RAISE EXCEPTION），先判定权限类消息映射 40300（auth.require_permission 等，消息含"无权限"/permission denied），再按业务规则关键词映射 40900
            case "P0001" -> {
                if (message.contains("无权限") || message.contains("permission denied")) {
                    yield buildError(FmsBaseException.CODE_FORBIDDEN, "权限不足", traceId);
                }
                if (Stream.of("balance", "借贷", "period", "closed", "锁定", "immutable", "不可变",
                        "leaf", "末级", "期间", "过账", "冲销", "结账", "审核", "凭证", "科目",
                        "处理人", "白名单", "whitelist", "软删除").anyMatch(message::contains)) {
                    yield buildError(FmsBaseException.CODE_CONFLICT, "业务规则校验失败", traceId);
                }
                yield buildError(FmsBaseException.CODE_INTERNAL, "数据库操作失败", traceId); // 未命中关键词即兜底 50000，
            }
            default -> buildError(FmsBaseException.CODE_INTERNAL, "数据库操作失败", traceId);
        };
    }

    /** 沿异常链查找根因 SQLException（MyBatis/Spring 可能包装为 DataAccessException） */
    private SQLException findSqlException(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException e) {
                return e;
            }
            t = t.getCause();
        }
        return null;
    }

    /** 组装错误响应，http 状态码 = 错误码前三位 */
    private ResponseEntity<ApiResponse<Void>> buildError(int code, String message) {
        return ResponseEntity.status(code / 100).body(ApiResponse.error(code, message, resolveTraceId()));
    }

    /** 组装错误响应，显式 traceId */
    private ResponseEntity<ApiResponse<Void>> buildError(int code, String message, String traceId) {
        return ResponseEntity.status(code / 100).body(ApiResponse.error(code, message, traceId));
    }

    /** traceId 取值，优先 MDC（TraceIdFilter 写入），兜底生成 v4（统一委托 Utils.TraceIdUtil） */
    private String resolveTraceId() {
        return TraceIdUtil.current();
    }
}
