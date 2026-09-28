package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Model.vo.SystemInfoView;
import net.castimage.fms_backend.Service.SystemInfoService;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 系统信息聚合实现，JdbcTemplate 查数据库版本，连接经沙箱池，权限面最小、
 * StringRedisTemplate 读 Redis INFO（仅 session-store=redis 时装配，缺失按未启用呈现）、
 * FmsProperties 白名单键摘配置，密钥/口令绝不外泄。采集失败按不可达呈现，fail-open 于展示，
 * 不影响主流程，与告警检测（AlertService fail-closed）语义区分，展示信息无安全裁决作用。
 */
@Service
public class SystemInfoServiceImpl implements SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoServiceImpl.class);

    /** 进程启动时刻，首次访问时近似，用于 uptime 展示 */
    private final Instant startedAt = Instant.now();

    /** 对等探测 HttpClient，实例级复用（连接池 keep-alive），避免每次新建 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** 对等探测虚拟线程执行器，多 peer 并行采集，2s 超时由 CompletableFuture 兜底 */
    private final ExecutorService probeExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /** 诊断：current() 调用栈日志节流（定位后删除） */
    private volatile long lastDiagNanos = 0;

    private final FmsProperties fmsProperties;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate; // session-store=redis 时装配，否则 null
    private final Environment environment;

    public SystemInfoServiceImpl(FmsProperties fmsProperties, JdbcTemplate jdbcTemplate,
                                 org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider,
                                 Environment environment) {
        this.fmsProperties = fmsProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.environment = environment;
    }

    @Override
    public SystemInfoView current() {
        SystemInfoView.SelfInfo self = selfInfo();
        return new SystemInfoView(
                self.javaVersion(), self.osName(), self.osArch(), self.startedAt(),
                self.uptimeSecs(), self.springBootVersion(), self.db(), self.redis(),
                backends(), redisNodes(), dbNodes(), self.configSummary());
    }

    /**
     * 本实例自身信息，纯本机采集，不触发对等探测，
     * 供 /internal/cluster-info 与聚合视图复用，避免递归
     */
    public SystemInfoView.SelfInfo selfInfo() {
        String springVersion;
        try {
            springVersion = SpringBootVersion.getVersion();
        } catch (RuntimeException e) {
            springVersion = "unknown";
        }
        return new SystemInfoView.SelfInfo(
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", ""),
                System.getProperty("os.arch", "unknown"),
                LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault()),
                (Instant.now().getEpochSecond() - startedAt.getEpochSecond()),
                springVersion,
                dbInfo(),
                redisInfo(),
                configSummary());
    }

    /** 数据库，pg 版本 + 连接 url 主机段，口令为独立配置项，url 不含凭证，仅展示 host:port */
    private SystemInfoView.DbInfo dbInfo() {
        try {
            String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
            String url = environment.getProperty("spring.datasource.url", "");
            String host = url;
            int idx = url.indexOf("://");
            if (idx >= 0) {
                String rest = url.substring(idx + 3);
                int slash = rest.indexOf('/');
                host = slash >= 0 ? rest.substring(0, slash) : rest;
            }
            return new SystemInfoView.DbInfo(version == null ? "unknown" : version.trim(), host, true);
        } catch (Exception e) {
            log.warn("系统信息-数据库采集失败: {}", e.getMessage());
            return new SystemInfoView.DbInfo("unreachable", "", false);
        }
    }

    /** Redis，INFO server/memory/stats + DBSIZE，in-memory 模式或不可达按未启用/不可达呈现 */
    private SystemInfoView.RedisInfo redisInfo() {
        if (redisTemplate == null) {
            return new SystemInfoView.RedisInfo("in-memory", false, "", null, null, 0);
        }
        try {
            var conn = redisTemplate.getConnectionFactory().getConnection();
            try {
                String version = conn.info("server").getProperty("redis_version");
                String usedMem = conn.info("memory").getProperty("used_memory");
                String maxMem = conn.info("memory").getProperty("maxmemory");
                long keys = conn.dbSize();
                long usedBytes = usedMem == null ? -1 : Long.parseLong(usedMem);
                double pct = 0;
                long max = maxMem == null ? 0 : Long.parseLong(maxMem);
                if (max > 0) {
                    pct = Math.round(usedBytes * 1000.0 / max) / 10.0;
                }
                return new SystemInfoView.RedisInfo("redis", true, version, keys, usedBytes >= 0 ? usedBytes : null, pct);
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            log.warn("系统信息-Redis 采集失败: {}", e.getMessage());
            return new SystemInfoView.RedisInfo("redis", false, "", null, null, 0);
        }
    }

    /**
     * 数据库主从节点列表，主库取连接串主机，从库经 pg_stat_replication 聚合
     * application_name/client_addr/复制状态/同步方式/WAL 复制延迟，
     * 非主从部署仅主库一条，采集失败返回空列表，fail-open 于展示
     */
    private List<SystemInfoView.DbNodeInfo> dbNodes() {
        List<SystemInfoView.DbNodeInfo> result = new ArrayList<>();
        String sql = "SELECT application_name, COALESCE(client_addr::text, ''), state, sync_state, "
                + "pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) "
                + "FROM pg_stat_replication ORDER BY application_name";
        try {
            jdbcTemplate.query(sql, rs -> {
                String appName = rs.getString(1);
                String host = rs.getString(2);
                String state = rs.getString(3);
                String syncState = rs.getString(4);
                long lag = rs.getLong(5);
                if (rs.wasNull()) {
                    lag = -1;
                }
                String name = appName.isBlank() ? host : appName;
                result.add(new SystemInfoView.DbNodeInfo("replica", name, host, state, syncState, lag));
            });
        } catch (Exception e) {
            log.warn("系统信息-数据库主从采集失败: {}", e.getMessage());
            return result;
        }
        result.add(0, new SystemInfoView.DbNodeInfo("master", "primary", localHostPort(), "primary", "-", 0));
        return result;
    }

    /**
     * 对等后端实例列表，fms.cluster.peers 为空时仅返回本实例，
     * 虚拟线程并行探测 /internal/cluster-info，统一 2s 超时（orTimeout），
     * 结果保持 peers 顺序，失败按不可达呈现，fail-open 于展示，不影响主流程
     */
    private List<SystemInfoView.BackendInfo> backends() {
        List<String> peers = fmsProperties.getCluster().getPeers();
        if (peers.isEmpty()) {
            peers = List.of(localHostPort());
        }
        List<CompletableFuture<SystemInfoView.BackendInfo>> futures = new ArrayList<>();
        for (String peer : peers) {
            String host = peer.trim();
            futures.add(CompletableFuture
                    .supplyAsync(() -> probeBackend(host), probeExecutor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> unreachableBackend(host)));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /** 单实例探测，GET /internal/cluster-info 聚合运行信息，失败抛异常由调用方兜底为不可达 */
    private SystemInfoView.BackendInfo probeBackend(String host) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + "/internal/cluster-info"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var node = com.alibaba.fastjson2.JSON.parseObject(resp.body());
                String instance = node.getString("instance");
                String redisMode = node.getJSONObject("redis").getString("mode");
                return new SystemInfoView.BackendInfo(
                        instance == null ? "unknown" : instance, host, true,
                        node.getString("javaVersion"),
                        node.getString("springBootVersion"),
                        node.getLongValue("uptimeSecs"), redisMode);
            }
            throw new IOException("HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.warn("系统信息-后端实例探测失败: {}", host);
            throw new RuntimeException(e);
        }
    }

    private SystemInfoView.BackendInfo unreachableBackend(String host) {
        return new SystemInfoView.BackendInfo("unknown", host, false, "", "", 0, "");
    }

    /** 本实例 host:port，取 spring.datasource.url 主机段（与 dbInfo 同源），无 peers 配置时兜底 */
    private String localHostPort() {
        String url = environment.getProperty("spring.datasource.url", "");
        int idx = url.indexOf("://");
        if (idx >= 0) {
            String rest = url.substring(idx + 3);
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(0, slash) : rest;
        }
        return "127.0.0.1:8081";
    }

    /**
     * Redis 集群节点列表，哨兵模式经 Lettuce 哨兵连接查询主/从节点
     * （RedisSentinelConnection 封装 RESP 协议与连接管理，主从由哨兵发现），
     * 哨兵节点取配置列表逐个 TCP 探测可达性（仅 connect 不解析协议），
     * 单节点模式仅返回当前节点，聚合失败返回空列表，fail-open 于展示
     */
    private List<SystemInfoView.RedisNodeInfo> redisNodes() {
        if (redisTemplate == null) {
            return new ArrayList<>();
        }
        try {
            var conn = redisTemplate.getConnectionFactory().getSentinelConnection();
            if (conn == null) {
                return singleNode();
            }
            try {
                List<SystemInfoView.RedisNodeInfo> result = new ArrayList<>();
                var masters = conn.masters();
                if (masters.isEmpty()) {
                    return result;
                }
                org.springframework.data.redis.connection.RedisServer master = masters.iterator().next();
                result.add(redisNodeFromServer("master", master));
                for (org.springframework.data.redis.connection.RedisServer r : conn.replicas(master)) {
                    result.add(redisNodeFromServer("replica", r));
                }
                for (String hp : sentinelHostPorts()) {
                    result.add(sentinelProbe(hp));
                }
                return result.stream().distinct().toList();
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            log.warn("系统信息-Redis 节点聚合失败: {}", e.getMessage());
            return singleNode();
        }
    }

    /** 哨兵节点配置列表，FMS_REDIS_SENTINEL_NODES 逗号分隔，host[:port]，port 缺省 26379 */
    private List<String> sentinelHostPorts() {
        List<String> result = new ArrayList<>();
        for (String node : environment.getProperty("FMS_REDIS_SENTINEL_NODES", "").split(",")) {
            String hp = node.trim();
            if (!hp.isEmpty()) {
                result.add(hp);
            }
        }
        return result;
    }

    /** 哨兵节点 TCP 探测，仅 connect 验证可达性，2s 超时，失败按 unreachable 呈现 */
    private SystemInfoView.RedisNodeInfo sentinelProbe(String hp) {
        String host = hp;
        int port = 26379;
        int colon = hp.lastIndexOf(':');
        if (colon > 0) {
            host = hp.substring(0, colon);
            try {
                port = Integer.parseInt(hp.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        boolean reachable;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        return new SystemInfoView.RedisNodeInfo("sentinel", hp, host, port,
                reachable ? "reachable" : "unreachable", "", null, null);
    }

    /** 单节点模式，当前连接节点信息 */
    private List<SystemInfoView.RedisNodeInfo> singleNode() {
        List<SystemInfoView.RedisNodeInfo> result = new ArrayList<>();
        try {
            var conn = redisTemplate.getConnectionFactory().getConnection();
            try {
                String role = conn.info("replication").getProperty("role");
                String version = conn.info("server").getProperty("redis_version");
                long keys = conn.dbSize();
                result.add(new SystemInfoView.RedisNodeInfo(
                        role == null ? "master" : role, "current", "localhost", 6379,
                        "reachable", version, keys, null));
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            log.warn("系统信息-Redis 节点采集失败: {}", e.getMessage());
        }
        return result;
    }

    /** 哨兵响应节点转展示模型，flags 文本即状态，name 缺失回退 host:port */
    private SystemInfoView.RedisNodeInfo redisNodeFromServer(String role,
                                                             org.springframework.data.redis.connection.RedisServer server) {
        String host = server.getHost();
        int port = server.getPort();
        String name = server.getName();
        if (name == null || name.isBlank()) {
            name = host + ":" + port;
        }
        String status = server.getFlags();
        if (status == null || status.isBlank()) {
            status = "reachable";
        }
        return new SystemInfoView.RedisNodeInfo(role, name, host, port, status, "", null, null);
    }

    /** 关键运行配置摘要，白名单键，密钥/口令/私密连接串一律不展示 */
    private Map<String, String> configSummary() {
        Map<String, String> m = new LinkedHashMap<>();
        var security = fmsProperties.getSecurity();
        var jwt = fmsProperties.getJwt();
        var mfa = fmsProperties.getMfa();
        var mail = fmsProperties.getMail();
        m.put("会话存储", fmsProperties.getSessionStore());
        m.put("Access TTL", jwt.getAccessTtl().toString());
        m.put("Refresh TTL", jwt.getRefreshTtl().toString());
        m.put("密码策略", security.getPasswordMinLength() + "-" + security.getPasswordMaxLength() + " 位");
        m.put("弱密码标记", security.getWeakPassword().isMarkOnLogin() ? "开启" : "关闭");
        m.put("2FA 备用码有效期", mfa.getBackupCodeValidDays() + " 天");
        m.put("备用码数量", mfa.getBackupCodeCount() + " 个");
        m.put("邮件通知", mail.isEnabled() && !mail.getHost().isBlank() ? "已配置（host=" + mail.getHost() + "）" : "未配置（默认不发送）");
        m.put("请求体上限", (security.getMaxBodyBytes() / 1024 / 1024) + " MB");
        m.put("主从复制监控", fmsProperties.getAlert().getReplicaExpected() > 0 ? "已启用（期望 " + fmsProperties.getAlert().getReplicaExpected() + " 从库）" : "未启用");
        return m;
    }
}
