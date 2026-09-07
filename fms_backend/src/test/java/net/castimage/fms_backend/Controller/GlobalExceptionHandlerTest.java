package net.castimage.fms_backend.Controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpInputMessage;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Model.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器错误码映射测试，业务异常/校验失败/sql 状态码/兜底 50000 的矩阵断言。
 *
 * SQLState 映射为安全关键路径，42501 权限、23505 冲突、22P02 参数，逐状态码验证。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void fmsBaseExceptionMapsCodeAndHttpStatus() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleFmsBaseException(
                new BadRequestException("参数错误"), request);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals(40001, resp.getBody().getCode());
        assertEquals("参数错误", resp.getBody().getMessage());
        assertNotNull(resp.getBody().getTraceId());
    }

    @Test
    void forbiddenExceptionMaps403And40300() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleFmsBaseException(
                new ForbiddenException("无权限"), request);
        assertEquals(403, resp.getStatusCode().value());
        assertEquals(40300, resp.getBody().getCode());
    }

    /** SQLState 42501（rls/列级授权拒绝）映射 40300，不向客户端外泄 sql 细节 */
    @Test
    void sqlState42501Maps40300() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleException(
                wrap(new SQLException("permission denied for table users", "42501")), request);
        assertEquals(403, resp.getStatusCode().value());
        assertEquals(40300, resp.getBody().getCode());
    }

    @Test
    void sqlState23505Maps40900() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleException(
                wrap(new SQLException("duplicate key", "23505")), request);
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(40900, resp.getBody().getCode());
    }

    @Test
    void sqlState23503And23514And23502Map40900() {
        for (String state : List.of("23503", "23514", "23502")) {
            ResponseEntity<ApiResponse<Void>> resp = handler.handleException(
                    wrap(new SQLException("constraint", state)), request);
            assertEquals(40900, resp.getBody().getCode(), "sqlstate=" + state);
        }
    }

    @Test
    void sqlState22P02Maps40001() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleException(
                wrap(new SQLException("invalid input syntax for type uuid", "22P02")), request);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals(40001, resp.getBody().getCode());
    }

    /** P0001（RAISE EXCEPTION），权限类消息映射 40300，业务关键词映射 40900，未知映射 50000 */
    @Test
    void p0001MapsByMessageKeyword() {
        assertEquals(40300, handler.handleException(
                wrap(new SQLException("auth.require_permission: 无权限", "P0001")), request)
                .getBody().getCode());
        assertEquals(40900, handler.handleException(
                wrap(new SQLException("借贷不平衡", "P0001")), request)
                .getBody().getCode());
        assertEquals(40900, handler.handleException(
                wrap(new SQLException("期间已关闭 closed", "P0001")), request)
                .getBody().getCode());
        assertEquals(50000, handler.handleException(
                wrap(new SQLException("未知业务异常", "P0001")), request)
                .getBody().getCode());
    }

    @Test
    void unknownSqlStateAndPlainExceptionMap50000() {
        assertEquals(50000, handler.handleException(
                wrap(new SQLException("boom", "XX000")), request).getBody().getCode());
        ResponseEntity<ApiResponse<Void>> resp = handler.handleException(
                new IllegalStateException("unexpected"), request);
        assertEquals(500, resp.getStatusCode().value());
        assertEquals(50000, resp.getBody().getCode());
        assertNull(resp.getBody().getData());
    }

    /** MyBatis 包装为 DataAccessException 时沿 cause 链找 SQLException 根因 */
    @Test
    void multiLevelWrappingFindsSqlRoot() {
        SQLException root = new SQLException("permission denied", "42501");
        RuntimeException wrapped = new RuntimeException(new IllegalStateException(root));
        assertEquals(40300, handler.handleException(wrapped, request).getBody().getCode());
    }

    @Test
    void noResourceFoundMaps40400() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/nope", ""));
        assertEquals(404, resp.getStatusCode().value());
        assertEquals(40400, resp.getBody().getCode());
    }

    @Test
    void validationFailureMaps40001WithFirstFieldError() {
        FieldError fieldError = mock(FieldError.class);
        when(fieldError.getField()).thenReturn("loginName");
        when(fieldError.getDefaultMessage()).thenReturn("不能为空");
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleValidation(ex);
        assertEquals(40001, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("loginName"));
    }

    @Test
    void constraintViolationMaps40001() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(mock(Path.class));
        when(violation.getMessage()).thenReturn("不能为空");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleConstraintViolation(ex);
        assertEquals(40001, resp.getBody().getCode());
    }

    @Test
    void unreadableBodyMaps40001() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("bad json", mock(HttpInputMessage.class)));
        assertEquals(40001, resp.getBody().getCode());
    }

    /** 构造"根因是 SQLException 的包装异常"，模拟 MyBatis/Spring 包装链 */
    private RuntimeException wrap(SQLException sqlEx) {
        return new RuntimeException("wrapped", sqlEx);
    }
}
