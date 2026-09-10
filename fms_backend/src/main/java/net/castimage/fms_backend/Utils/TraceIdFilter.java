package net.castimage.fms_backend.Utils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** traceId 透传/生成过滤器，order=1 过滤链最外层，与网关 mw_trace 同构，合法 X-Trace-Id 复用，canonical 小写否则生成 v4，写回响应头，finally 清理 MDC/RequestContext 防泄漏 */
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * traceId 透传主流程，order=1 过滤链最外层，与网关 mw_trace 同构，合法的 X-Trace-Id，canonical 小写直接复用，非法或缺失则生成 v4 兜底，解析统一委托 TraceIdUtil，
     * 先注入仅含 traceId 的 RequestContext（供 GatewaySignatureFilter 后续填充身份与异常
     * 兜底取用）并写入 MDC，响应头在链前写回，保证下游任何 4xx/5xx 响应恒携带 traceId，
     * finally 双清理 MDC 与 RequestContext，防线程复用污染。
     *
     * traceId 贯穿全链路（网关经后端到日志再到响应头），本过滤器是后端
     * 唯一入口，复用外部 traceId 时严格 canonical 校验，非法值视为缺失重新生成，
     * 杜绝日志注入与伪造链路归属，缺失/非法时生成 v4 兜底，RequestContext 先注入
     * 仅含 traceId 的占位上下文，GatewaySignatureFilter 校验通过后在同一实例上填充
     * 身份，顺序保证，本过滤器 order=1 恒先于签名过滤器 order=2 执行。
     *
     * 清理语义，响应头在链前写回，即使后续 4xx/5xx 也保证响应携带 traceId，
     * finally 双清理，MDC 与 RequestContext，与 GatewaySignatureFilter 的清理互为
     * 双保险，防容器线程复用导致的跨请求污染。
     *
     * @param request     原始请求，X-Trace-Id 头来源，可为空
     * @param response    写回 X-Trace-Id 响应头
     * @param filterChain 后继过滤器链，GatewaySignatureFilter 等
     * @throws ServletException 下游过滤器/处理器传播的异常
     * @throws IOException      读请求或写响应时的 io 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 头解析/兜底生成统一委托 TraceIdUtil，
        String traceId = TraceIdUtil.resolve(request.getHeader("X-Trace-Id"));

        // 先注入仅含 traceId 的上下文，供下游填充身份与异常兜底取用
        RequestContext.set(new RequestContext(null, null, null, null, traceId));
        MDC.put(TraceIdUtil.MDC_KEY, traceId);

        // 响应头在链前写回，保证下游，含 4xx/5xx 响应恒携带，
        response.setHeader("X-Trace-Id", traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove(TraceIdUtil.MDC_KEY);
        }
    }
}
