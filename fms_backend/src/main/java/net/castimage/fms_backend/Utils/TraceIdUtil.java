package net.castimage.fms_backend.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.UUID;

/** traceId 单一事实来源，MDC 键、头解析、兜底生成统一在本类（键名与 logging.pattern.console 的 %X{traceId} 对齐） */
public final class TraceIdUtil {

    /** MDC 键名，日志 pattern 与 ApiResponse/异常处理共用 */
    public static final String MDC_KEY = "traceId";

    private static final Logger log = LoggerFactory.getLogger(TraceIdUtil.class);

    private TraceIdUtil() {
    }

    /** 取当前链路 traceId，MDC 优先，TraceIdFilter 写入，兜底现场生成 v7（恒非 null） */
    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        // 非请求线程，无 MDC 现场生成，
        return (traceId == null || traceId.isBlank()) ? UuidV7.next().toString() : traceId;
    }

    /** 合法 uuid 复用，canonical 小写，否则生成 v7，非法值一律不采信，与网关同构 */
    public static String resolve(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                return UUID.fromString(headerValue.trim()).toString().toLowerCase(Locale.ROOT); // 合法即复用 canonical 小写，
            } catch (IllegalArgumentException e) {
                log.warn("X-Trace-Id 非合法 uuid({}), 重新生成", headerValue.trim());
            }
        }
        return UuidV7.next().toString();
    }
}
