package net.castimage.fms_backend.Utils;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.security.InvalidKeyException;

/**
 * 网关签名校验过滤器，order=2，urlPatterns=/api/*，TraceIdFilter order=1 先执行。
 *
 * 与 fms_rgw sign.rs 同构，X-FMS-Signature 协议，
 *
 * body_hash = hex(sha256(body))                          // 空 body = e3b0c442...
 * input     = method|path|body_hash|t|nonce|uid|role     // '|' = 0x7C，无转义
 * sig       = hex(hmac-sha256(sign_secret, input))       // 64 位小写 hex
 *
 * 处理流程，
 *
 * 请求体缓存，CachedBodyHttpServletRequest 贯穿到 Controller，防二次读流，
 * 读取 X-FMS-Signature，缺失则 40100，解析 t=&nonce=&sig= 三字段，非法则 40001，
 * 时间窗，|now - t| > replay-window-secs，300 则 40100，防重放，
 * 重算 hmac（身份头 uid/role 纳入输入），恒定时间比较（MessageDigest.isEqual）失败则 40100，
 * nonce 去重，NonceStore，时间窗与签名之后、采信身份之前命中则 40100（第二道闸），
 * 采信身份，X-FMS-UserId 必须 canonical uuid，X-FMS-Role 必须命中五白名单，否则 40100，api-key 模式携非空 X-api-key 豁免，身份由 ApiKeyFilter order=3 采信，
 * 剥离伪造 X-FMS-* 头，仅保留已采信的 X-FMS-UserId/X-FMS-Role，纵深防御，
 * 填充 RequestContext，finally 清理，防 ThreadLocal 泄漏。
 *
 * X-api-key 模式，系统间集成，网关不注入身份头、签名身份段为空串，hmac 校验以上述空串参与输入，步骤 6 已通过，本过滤器对携带非空 X-api-key 的
 * 请求不按「X-FMS-UserId 缺失」拒绝，跳过身份采信放行，由 ApiKeyFilter（order=3，
 * 本过滤器之后、身份采信之前）校验 um.api_keys 后按 key.user_id + scope 注入身份，
 * 任何失败在 ApiKeyFilter 侧一律 40100 fail-closed。
 */
@Slf4j
public class GatewaySignatureFilter extends OncePerRequestFilter {

    /** 空 body 的 SHA-256 hex（fms_rgw compute_body_hash 同构） */
    private static final String EMPTY_BODY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 请求体上限默认值，与网关 10MB 对齐，超限 413/40001，可经 fms.security.max-body-bytes 覆盖 */
    private static final int DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024;

    /** 可信身份头白名单，剥离后仅保留这两项 */
    private static final List<String> TRUSTED_FMS_HEADERS =
            List.of("X-FMS-USERID", "X-FMS-ROLE");

    /**
     * 匿名端点，与 fms_rgw auth.rs is_anonymous_path 同列表，登录发生在身份建立之前，
     * 网关对这些路径不注入身份头、签名输入 uid/role 为空串，此处同样放行空身份，
     * 否则经网关登录恒 40100；登录页公开端点，背景配置/背景图同属
     * 匿名白名单，无身份可采信，签名以空身份参与输入。
     */
    private static final List<String> ANONYMOUS_PATHS = List.of(
            "/api/auth/login", "/api/auth/refresh", "/api/auth/recovery",
            "/api/auth/recovery/reset", "/api/nt/ws", "/api/public/login-background");

    private static boolean isAnonymousPath(String path) {
        return ANONYMOUS_PATHS.contains(path) || path.startsWith("/api/auth/totp/")
                || path.startsWith("/api/public/files/");
    }

    /** 是否 api-key 模式，携带非空 X-api-key，与网关 auth.rs trim 判空同口径，本过滤器跳过身份采信，交由 ApiKeyFilter 校验后注入 */
    private static boolean hasApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        return apiKey != null && !apiKey.isBlank();
    }

    private final byte[] signSecret;
    private final long replayWindowSecs;
    private final boolean bypassGateway;
    private final int maxBodyBytes;
    private final NonceStore nonceStore;

    /**
     * 便捷构造，内部自建 InMemoryNonceStore（非 Bean，无 @Scheduled 兜底清理，仅供测试/独立
     * 装配场景，生产经 WebConfig 注入 Bean，按 fms.session-store 选择内存或 Redis 实现），
     * 容量按 effectiveCapacity 推导，显式 nonce-capacity（>0）优先，
     * 否则按 noncePeakQps × replayWindowSecs 动态计算（默认 1000×300=300000，
     * 6 万 qps 可击穿 10000 固定容量）。
     *
     * @param signSecret      sign_secret（与 fms_rgw sign.sign_secret 共享，≥32 字符，启动期 fail-fast）
     * @param replayWindowSecs 防重放窗口秒数，默认 300
     * @param nonceCapacity    NonceStore 容量上限，0 = 未配置，走动态推导
     * @param noncePeakQps     期望峰值 qps（默认 1000，仅 nonceCapacity=0 时参与推导）
     * @param bypassGateway    仅本地开发跳过签名校验，生产必须 false
     */
    public GatewaySignatureFilter(String signSecret, long replayWindowSecs, int nonceCapacity,
                                  int noncePeakQps, boolean bypassGateway) {
        this(signSecret, replayWindowSecs,
                new InMemoryNonceStore(NonceStore.effectiveCapacity(nonceCapacity, noncePeakQps, replayWindowSecs),
                        replayWindowSecs * 1000L),
                DEFAULT_MAX_BODY_BYTES, bypassGateway);
    }

    /**
     * 注入外部 NonceStore（Spring Bean，含 @Scheduled 兜底清理）。
     *
     * @param signSecret       sign_secret（≥32 字符，启动期 fail-fast）
     * @param replayWindowSecs 防重放窗口秒数
     * @param nonceStore       nonce 去重表实例
     * @param maxBodyBytes     请求体上限，fms.security.max-body-bytes，默认 10MB
     * @param bypassGateway    仅本地开发跳过签名校验，生产必须 false
     */
    public GatewaySignatureFilter(String signSecret, long replayWindowSecs, NonceStore nonceStore,
                                  int maxBodyBytes, boolean bypassGateway) {
        if (signSecret == null || signSecret.length() < 32) {
            throw new IllegalArgumentException("fms.security.sign-secret 必须 ≥32 字符（与 fms_rgw sign.sign_secret 一致）");
        }
        this.signSecret = signSecret.getBytes(StandardCharsets.UTF_8);
        this.replayWindowSecs = replayWindowSecs;
        this.bypassGateway = bypassGateway;
        this.maxBodyBytes = maxBodyBytes;
        this.nonceStore = nonceStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 本地开发 bypass，/internal/health 为网关熔断探测端点，不要求签名
        return bypassGateway || "/internal/health".equals(request.getRequestURI());
    }

    /**
     * 网关签名校验主流程，任何一步校验失败即写统一错误响应，签名类 40100、格式类 40001、
     * 超限 413 并短路返回，不进入后续过滤器链。执行序，缓存请求体，超限则提前拒绝，再校验
     * 签名头 t/nonce/sig 三字段，随后时间窗（|now-t| ≤ 窗口，防重放），再按原始身份头重算 hmac
     * 并恒定时间比较，随后 nonce 去重（时间窗+签名之后、采信身份之前），再采信身份，UserId 须
     * canonical uuid、Role 须命中白名单，随后剥离伪造 X-FMS-* 头，再填充 RequestContext 后放行，
     * finally 双保险清理 ThreadLocal。身份仅在此处采信一次，jwt 模式，下游一律读
     * RequestContext，不得自行解析头，X-api-key 模式，网关不注入身份头、签名身份段
     * 空串本过滤器跳过身份采信，由 ApiKeyFilter（order=3）校验后按密钥注入身份。
     *
     * 校验链语义，与 fms_rgw verify_signature_header 同构，失败优先级一致，签名缺失则 40100
     * fail-closed，宁可拒绝不可放行，t/nonce/sig 三字段解析（'&' 分隔、未知字段拒绝、
     * nonce 须 canonical uuid、sig 须 64 位小写 hex）失败则 40001，|now - t| > 窗口，300s，
     * abs 双向覆盖未来时间戳防提前重放则 40100，hmac 重算，method|path|body_hash|t|nonce|
     * uid|role，uid/role 取请求头原值，网关一跳内换头即不匹配恒定时间比较失败则 40100，
     * nonce 已在窗口内用过则 40100（时间窗+签名之后才去重，杜绝先占 nonce 的拒绝型 dos，
     * 采信身份前拒绝，重放者拿不到任何反馈差异）。全部通过后才采信身份并放行。
     *
     * 身份采信规则，X-FMS-UserId 必须为 canonical uuid（36 字符小写，与网关注入格式
     * 对齐，大写/非标准格式一律拒绝），X-FMS-Role 必须命中五角色白名单（JwtRole.from），
     * 否则 40100，此后剥离所有伪造 X-FMS-* 头，仅保留已采信的两项，下游与 Controller
     * 只能读到可信值，配合 RequestContext 单一数据源形成纵深防御。body 在步骤 1 已缓存为
     * CachedBodyHttpServletRequest 贯穿到 Controller，防二次读流，超限，声明或实际提前
     * 413/40001，不进入签名环节。
     *
     * @param request      原始请求，body 尚未读取，本方法负责缓存以贯穿到 Controller
     * @param response     校验失败时写统一错误响应，http 401/400/413 + 业务码
     * @param filterChain  校验通过后继续执行的后继过滤器链，最终到达 Controller
     * @throws ServletException 下游过滤器/处理器传播的异常
     * @throws IOException      读请求体或写错误响应时的 io 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 缓存请求体，超限直接 413/40001，不等签名
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, maxBodyBytes);
        if (cached.isTooLarge()) {
            writeError(response, 413, 40001, "request body too large");
            return;
        }

        // 签名头缺失则 40100（fail-closed）
        String signature = request.getHeader("X-FMS-Signature");
        if (signature == null || signature.isBlank()) {
            log.warn("缺少 X-FMS-Signature: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            writeError(response, 401, 40100, "missing signature");
            return;
        }

        // 解析 t/nonce/sig，字段非法则 40001
        ParsedSignature parsed;
        try {
            parsed = parseSignature(signature);
        } catch (IllegalArgumentException e) {
            log.warn("X-FMS-Signature 格式非法: {}", e.getMessage());
            writeError(response, 400, 40001, "malformed signature");
            return;
        }

        // 时间窗，|now - t| ≤ replayWindowSecs，防重放
        long nowSecs = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSecs - parsed.t()) > replayWindowSecs) {
            log.warn("X-FMS-Signature 时间窗超限: t={} now={} window={}", parsed.t(), nowSecs, replayWindowSecs);
            writeError(response, 401, 40100, "signature expired");
            return;
        }

        // body_hash + hmac 重算，uid/role 取请求头原值，纳入签名输入
        String bodyHash = sha256Hex(cached.getBody());
        String uid = request.getHeader("X-FMS-UserId");
        String role = request.getHeader("X-FMS-Role");
        String expected = computeSignature(request.getMethod(), request.getRequestURI(),
                bodyHash, parsed.t(), parsed.nonce(), uid, role);

        // 恒定时间比较，防时序侧信道
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                parsed.sig().getBytes(StandardCharsets.US_ASCII))) {
            log.warn("X-FMS-Signature 校验失败: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            writeError(response, 401, 40100, "invalid signature");
            return;
        }

        // nonce 去重，时间窗+签名校验之后、采信身份之前，重放第二道闸
        if (!nonceStore.checkAndInsert(parsed.nonce())) {
            log.warn("nonce 重放拒绝: nonce={}", parsed.nonce());
            writeError(response, 401, 40100, "replayed nonce");
            return;
        }

        // 采信身份，UserId 必须 canonical uuid，Role 必须命中五角色白名单。

        // 匿名端点豁免（login/refresh/recovery/totp/ws 无身份可采信，hmac 以空串
        // 参与输入且已通过步骤 6，与网关注入的空身份签名一致），其余路径 fail-closed，
        // 但携带非空 X-api-key 的 api-key 模式请求豁免，网关不注入身份头、签名身份段
        // 空串，步骤 6 已通过，身份采信延后到 ApiKeyFilter（order=3）校验后注入。
        // 匿名白名单路径若带身份头（网关对 /api/auth/totp/* 携带有效 Bearer 时
        // "尽力注入"身份），同样采信身份与角色，否则 RequestContext 有 userId 无
        // role，SandboxSessionInterceptor 拒绝注入，连接退化为 fms_app 空壳，
        // permission denied（2fa setup/enable/disable 报 40300）。
        String path = request.getRequestURI();
        UUID userId = parseCanonicalUuid(uid);
        JwtRole jwtRole = null;
        if (userId != null) {
            jwtRole = JwtRole.from(role);
            if (jwtRole == null) {
                log.warn("X-FMS-Role 不在白名单: role={}", role);
                writeError(response, 401, 40100, "unknown role");
                return;
            }
        } else if (!isAnonymousPath(path) && !hasApiKey(request)) {
            log.warn("X-FMS-UserId 非法或缺失且无 X-API-Key: path={} ip={}", path, request.getRemoteAddr());
            writeError(response, 401, 40100, "invalid user id");
            return;
        }

        // 剥离伪造 X-FMS-* 头，仅保留已采信的 UserId/Role，Signature 消费后一并移除，纵深防御，
        // 剥离由 CachedBodyHttpServletRequest 的 getHeader/getHeaderNames 覆写完成，直接放行包装器
        HttpServletRequest trusted = cached;

        // 填充身份上下文（orgIds/deptIds 传 null，由 pg 侧 auth.set_session 从 scope 表预载，
        // traceId 统一取 Utils.TraceIdUtil），匿名端点无身份可采信，不设上下文
        try {
            if (userId != null) {
                RequestContext.set(new RequestContext(userId, jwtRole, null, null, TraceIdUtil.current()));
            }
            filterChain.doFilter(trusted, response);
        } finally {
            // 双保险清理，TraceIdFilter finally 亦清理，防 ThreadLocal 泄漏
            RequestContext.clear();
        }
    }

    // 签名计算

    /** 按 fms_rgw 精确算法重算签名（method|path|body_hash|t|nonce|uid|role） */
    private String computeSignature(String method, String path, String bodyHash,
                                    long t, String nonce, String uid, String role) {
        String input = String.join("|", method, path, bodyHash, Long.toString(t), nonce,
                uid == null ? "" : uid, role == null ? "" : role);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 为 JDK 必备算法，此处仅理论不可达
            throw new IllegalStateException("HmacSHA256 初始化失败", e);
        }
    }

    private static String sha256Hex(byte[] body) {
        if (body.length == 0) {
            // 空 body 哈希与 fms_rgw compute_body_hash 逐字一致，常量固化，防实现漂移
            return EMPTY_BODY_HASH;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 解析 X-FMS-Signature 头值（t=&nonce=&sig=），任一字段缺失/非法抛 IllegalArgumentException */
    private static ParsedSignature parseSignature(String headerValue) {
        long t = Long.MIN_VALUE;
        String nonce = null;
        String sig = null;
        for (String part : headerValue.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                throw new IllegalArgumentException("字段格式非法: " + part);
            }
            String key = part.substring(0, idx);
            String value = part.substring(idx + 1);
            switch (key) {
                case "t" -> t = Long.parseLong(value);
                case "nonce" -> nonce = value;
                case "sig" -> sig = value;
                default -> throw new IllegalArgumentException("未知字段: " + key);
            }
        }
        if (t == Long.MIN_VALUE || nonce == null || sig == null) {
            throw new IllegalArgumentException("t/nonce/sig 字段缺失");
        }
        if (parseCanonicalUuid(nonce) == null) {
            throw new IllegalArgumentException("nonce 非 canonical uuid");
        }
        if (sig.length() != 64 || !sig.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sig 非 64 位小写 hex");
        }
        return new ParsedSignature(t, nonce, sig);
    }

    /**
     * 严格 canonical uuid 解析，uuid.fromString 通过后要求原值 == canonical 小写，拒绝大写/非标准格式，与网关注入格式，36 字符小写对齐。
     */
    private static UUID parseCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value.trim());
            return value.trim().equals(uuid.toString()) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // 统一错误响应

    /** 写统一错误响应（四字段，{code,message,data:null,traceId}），错误路径 data 恒为 null */
    private void writeError(HttpServletResponse response, int status, int code, String message) throws IOException {
        // traceId 统一取 Utils.TraceIdUtil（MDC 优先 + v4 兜底）
        String traceId = TraceIdUtil.current();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        body.put("traceId", traceId);
        response.getWriter().write(JSON.toJSONString(body));
    }

    /** 签名头解析结果，record 风格值对象 */
    private record ParsedSignature(long t, String nonce, String sig) {
    }

    // 请求体缓存 + 头剥离包装

    /**
     * 请求包装器，读流缓存到 byte[]（贯穿 Controller，防二次读流），
     * 并剥离客户端伪造的 X-FMS-* 头，仅保留 X-FMS-UserId/X-FMS-Role，纵深防御。
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private byte[] body;
        private boolean tooLarge;
        /** 惰性解析的 multipart parts（缓存 body 自解析，线程内单次请求使用） */
        private List<Part> parts;

        CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
            super(request);
            int declared = request.getContentLength();
            if (declared > maxBytes) {
                this.body = new byte[0];
                this.tooLarge = true;
                return;
            }
            try {
                this.body = readBody(request.getInputStream(), maxBytes);
                this.tooLarge = false;
            } catch (BodyTooLargeException e) {
                this.body = new byte[0];
                this.tooLarge = true;
            }
        }

        boolean isTooLarge() {
            return tooLarge;
        }

        byte[] getBody() {
            return body;
        }

        /**
         * multipart parts 自解析，本过滤器已消费原始请求流，Tomcat parseParts 再读
         * 已耗尽的 coyote 流必然得到空 parts（MissingServletRequestPartException
         * "Required part file is not present"），故必须从缓存 body 重新解析。
         *
         * 非 multipart 请求不触发，Spring StandardServletMultipartResolver 先查 Content-Type。
         */
        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            if (parts == null) {
                parts = parseMultipart();
            }
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            for (Part p : getParts()) {
                if (name.equals(p.getName())) {
                    return p;
                }
            }
            return null;
        }

        /**
         * multipart 文本 part 以请求参数形态暴露，模拟 Tomcat 原生 parseParts 行为，
         * 原生实现解析 parts 后会把无 filename 的文本 part 并入请求参数表。缓存 body
         * 自解析后，Spring 多部件装配对参数表的合并依赖具体实现版本，此处显式兜底，
         * 保证 @RequestParam 文本字段，如 bankAccountId 恒可绑定。非 multipart 请求
         * getParts 为空，各方法行为与原生包装器一致，仅透传查询串参数。
         */
        @Override
        public String getParameter(String name) {
            String queryValue = super.getParameter(name);
            if (queryValue != null) {
                return queryValue;
            }
            return multipartTextParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] queryValues = super.getParameterValues(name);
            if (queryValues != null && queryValues.length > 0) {
                return queryValues;
            }
            String mp = multipartTextParameter(name);
            return mp != null ? new String[]{mp} : null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> merged = new LinkedHashMap<>(super.getParameterMap());
            try {
                for (Part p : getParts()) {
                    if (p.getSubmittedFileName() == null && p.getName() != null
                            && !merged.containsKey(p.getName())) {
                        String value = multipartTextParameter(p.getName());
                        if (value != null) {
                            merged.put(p.getName(), new String[]{value});
                        }
                    }
                }
            } catch (IOException | ServletException ignored) {
                // 解析失败按仅查询串参数处理
            }
            return merged;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getParameterNames()));
            try {
                for (Part p : getParts()) {
                    if (p.getSubmittedFileName() == null && p.getName() != null) {
                        names.add(p.getName());
                    }
                }
            } catch (IOException | ServletException ignored) {
                // 解析失败按仅查询串参数处理
            }
            return Collections.enumeration(names);
        }

        /** 取 multipart 文本 part（无 filename）的 utf-8 值，值已由 parseMultipart 剥离分隔行尾，
         * 此处仅二次兜底防御 */
        private String multipartTextParameter(String name) {
            try {
                for (Part p : getParts()) {
                    if (name.equals(p.getName()) && p.getSubmittedFileName() == null) {
                        byte[] raw = p.getInputStream().readAllBytes();
                        int end = raw.length;
                        if (end > 1 && raw[end - 2] == '\r' && raw[end - 1] == '\n') {
                            end -= 2;
                        } else if (end > 0 && raw[end - 1] == '\n') {
                            end -= 1;
                        }
                        return new String(raw, 0, end, StandardCharsets.UTF_8);
                    }
                }
            } catch (IOException | ServletException ignored) {
                // 读取失败按参数缺失处理，查询串参数已由 super 兜底
            }
            return null;
        }

        /**
         * RFC 2046 最小 multipart 解析，字节级精确切片，兼容 \r\n 与 \n 行结构，按"行首
         * boundary"定位分隔行，前一字节为 \n，part 内容 = 头区空行之后至下一分隔行的
         * 原始字节切片，不做任何行归一化，二进制附件，PDF/图片等上传/下载必须
         * 逐字节往返一致，行级 split 重拼会使 LF 变 CRLF 污染文件内容，文件存储模块依赖项。
         *
         * boundary 取自 Content-Type 参数，引号包裹亦支持，非 multipart / 缺 boundary /
         * 空 body 返回空列表，与 Servlet 空 parts 语义一致。
         */
        private List<Part> parseMultipart() {
            List<Part> result = new ArrayList<>();
            String contentType = super.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                return result;
            }
            String boundary = extractBoundary(contentType);
            if (boundary == null || boundary.isEmpty() || body.length == 0) {
                return result;
            }
            byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int pos = 0;
            while (pos < body.length) {
                int bStart = indexOfBoundaryAtLineStart(body, delim, pos);
                if (bStart < 0) {
                    break;
                }
                boolean closing = matchesAt(body, bStart + delim.length, "--");
                // 跳过边界行本身（收尾行多 "--"）及其行终止符
                int cursor = skipLineEnd(body, bStart + delim.length + (closing ? 2 : 0));
                // part 头，直至空行，ASCII 文本区，安全按行切分
                int headerEnd = indexOfEmptyLine(body, cursor);
                if (headerEnd < 0) {
                    break;
                }
                String headerText = new String(body, cursor, headerEnd - cursor,
                        StandardCharsets.ISO_8859_1);
                List<String> headerLines = new ArrayList<>();
                for (String h : headerText.split("\\r?\\n", -1)) {
                    if (!h.isEmpty()) {
                        headerLines.add(h);
                    }
                }
                // part 内容，空行之后至下一分隔行，行终止符属分隔行，天然排除的原始字节切片，
                // 剥离末尾一个 CRLF/LF（RFC 2046，该行尾属边界分隔符而非值，不剥离会让文件
                // part 内容被污染，下载时多出 \r\n，文本 part 值带行尾（已由
                // multipartTextParameter 二次剥离，此处统一后幂等）
                int cStart = skipLineEnd(body, headerEnd);
                int cEnd = indexOfBoundaryAtLineStart(body, delim, cStart);
                if (cEnd < 0) {
                    break;
                }
                byte[] content = stripTrailingCrlf(Arrays.copyOfRange(body, cStart, cEnd));
                result.add(buildPart(headerLines, content));
                pos = cEnd; // 从下一分隔行继续
            }
            return result;
        }

        /** 自 from 起查找行首 boundary（body[0] 或前一字节为 \n 处，限定行首防文件内容含 boundary 子串误切），未命中返回 -1 */
        private static int indexOfBoundaryAtLineStart(byte[] body, byte[] delim, int from) {
            int idx = from;
            while (idx < body.length) {
                int hit = indexOfBytes(body, delim, idx);
                if (hit < 0) {
                    return -1;
                }
                if (hit == 0 || body[hit - 1] == '\n') {
                    return hit;
                }
                idx = hit + 1;
            }
            return -1;
        }

        /** 朴素字节子串查找，multipart 体上限 10MB 内开销可忽略 */
        private static int indexOfBytes(byte[] body, byte[] needle, int from) {
            outer:
            for (int i = Math.max(from, 0); i <= body.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (body[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        /** 自 from 起跳过行终止符，\r\n 或 \n，返回下一行首字节下标 */
        private static int skipLineEnd(byte[] body, int from) {
            int i = Math.max(from, 0);
            while (i < body.length && (body[i] == '\r' || body[i] == '\n')) {
                i++;
            }
            return i;
        }

        /** 自 from 起查找空行起始，该行为空，行首即 \r\n/\n 或为 body 末尾，未命中返回 -1。
         * 仅"行首 + 当前字节为 \r/\n"判为空行：把任意行尾 \r\n 判为空行时，双行头 part
         * （文件，Content-Disposition + Content-Type）会把第二行头并入内容写盘（下载多出
         * "Content-Type: ...\r\n\r\n" 前缀）。行首取 body[0] 或前一字节为 \n 处 */
        private static int indexOfEmptyLine(byte[] body, int from) {
            int i = Math.max(from, 0);
            while (i < body.length) {
                if ((i == 0 || body[i - 1] == '\n')
                        && (body[i] == '\r' || body[i] == '\n')) {
                    return i;
                }
                i++;
            }
            return -1;
        }

        /** 朴素逐字节匹配，needle 为 ASCII 文本，ISO-8859-1 下逐字节等价 */
        private static boolean matchesAt(byte[] body, int at, String needle) {
            if (at < 0 || at + needle.length() > body.length) {
                return false;
            }
            for (int i = 0; i < needle.length(); i++) {
                if (body[at + i] != needle.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** 剥离末尾一个 CRLF/LF（RFC 2046 边界分隔行尾，二进制文件真实以 \r\n 结尾时
         * 分隔符 CRLF 恰在其后，剥离一个即还原真实内容，不误伤） */
        private static byte[] stripTrailingCrlf(byte[] content) {
            int end = content.length;
            if (end > 1 && content[end - 2] == '\r' && content[end - 1] == '\n') {
                end -= 2;
            } else if (end > 0 && content[end - 1] == '\n') {
                end -= 1;
            }
            return end == content.length ? content : Arrays.copyOf(content, end);
        }

        /** 从 Content-Type 提取 boundary 参数值，大小写不敏感、去引号 */
        private static String extractBoundary(String contentType) {
            for (String param : contentType.split(";")) {
                int eq = param.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = param.substring(0, eq).trim();
                if (!key.equalsIgnoreCase("boundary")) {
                    continue;
                }
                String value = param.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
            return null;
        }

        /** 由 part 头行构建 Part（name/filename 取 Content-Disposition，Content-Type 独立头） */
        private static Part buildPart(List<String> headerLines, byte[] content) {
            String name = null;
            String fileName = null;
            String partContentType = null;
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String h : headerLines) {
                int colon = h.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String hname = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String hvalue = h.substring(colon + 1).trim();
                headers.computeIfAbsent(hname, k -> new ArrayList<>()).add(hvalue);
                if (hname.equals("content-type")) {
                    partContentType = hvalue;
                } else if (hname.equals("content-disposition")) {
                    name = extractParam(hvalue, "name");
                    fileName = extractParam(hvalue, "filename");
                }
            }
            return new CachedPart(name, fileName, partContentType, content, headers);
        }

        /** 解析 Content-Disposition 参数，引号包裹与未包裹均支持，取首个匹配 */
        private static String extractParam(String headerValue, String param) {
            int idx = headerValue.toLowerCase(Locale.ROOT).indexOf(param + "=");
            if (idx < 0) {
                return null;
            }
            String v = headerValue.substring(idx + param.length() + 1);
            if (v.startsWith("\"")) {
                int end = v.indexOf('"', 1);
                return end < 0 ? null : v.substring(1, end);
            }
            int end = v.indexOf(';');
            return (end < 0 ? v : v.substring(0, end)).trim();
        }

        private static byte[] readBody(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > maxBytes) {
                    throw new BodyTooLargeException();
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 同步 Servlet 场景无需异步监听
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            return isStripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            // Servlet 契约要求空 Enumeration 而非 null（个别下游组件遍历头时 NPE）
            return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !isStripped(n))
                    .toList();
            return Collections.enumeration(names);
        }

        /** X-FMS-* 前缀头除可信白名单外一律剥离，大小写不敏感 */
        private boolean isStripped(String name) {
            if (name == null) {
                return false;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.startsWith("X-FMS-") && !TRUSTED_FMS_HEADERS.contains(upper);
        }
    }

    /**
     * 内存 multipart part（jakarta.servlet.http.Part 最小实现），内容为缓存 body 的
     * 字节切片，不落盘，write/delete 仅为契约实现。
     */
    private static final class CachedPart implements Part {

        private final String name;
        private final String fileName;
        private final String contentType;
        private final byte[] content;
        private final Map<String, List<String>> headers;

        CachedPart(String name, String fileName, String contentType, byte[] content,
                   Map<String, List<String>> headers) {
            this.name = name;
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
            this.headers = headers;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return fileName;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public void write(String fileName) throws IOException {
            Path target = Path.of(fileName);
            if (!target.isAbsolute()) {
                target = Path.of(System.getProperty("java.io.tmpdir", "."), fileName);
            }
            Files.write(target, content);
        }

        @Override
        public void delete() {
            // 内存 part 无落盘副作用，无需清理
        }

        @Override
        public String getHeader(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return (values == null || values.isEmpty()) ? null : values.getFirst();
        }

        @Override
        public Collection<String> getHeaders(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? Collections.emptyList() : values;
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }
    }

    /** 请求体超过上限的内部信号 */
    private static final class BodyTooLargeException extends IOException {
    }
}
