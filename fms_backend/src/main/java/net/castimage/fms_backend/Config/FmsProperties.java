package net.castimage.fms_backend.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/**
 * FMS 全局配置绑定（{@code fms.*}，yaml 键 kebab-case 自动绑定），security/jwt/mfa/login 四个内嵌配置树，
 * 加 file/audit/db/pgmq/ui/report/schedule/log（文件存储/审计导出/db 部署参数期望值/pgmq 消费/前端 ui 下发/
 * 报表白名单/pg_cron 调度登记/访问日志批接收）与 sessionStore（CachePort 实现选择，
 * in-memory | redis，多实例共享存储）、
 * roleMap（jwt role 短名映射 pg 职责角色，编译期白名单，不可由请求注入）。由 Service 层与配置类注入使用。
 */
@Component
@ConfigurationProperties(prefix = "fms")
@Getter
@Setter
public class FmsProperties {

    /** 网关签名校验配置（fms.security.*） */
    private Security security = new Security(); // 默认实例兜底未配置键，

    /** jwt 签发/验签配置（fms.jwt.*） */
    private Jwt jwt = new Jwt();

    /** 2fa 配置（fms.mfa.*） */
    private Mfa mfa = new Mfa();

    /** 登录锁定策略（fms.login.*） */
    private Login login = new Login();

    /** 访问日志批接收配置，fms.log.*，内部端点 /internal/log-batch 使用 */
    private Log log = new Log();

    /** 文件存储配置（fms.file.*） */
    private File file = new File();

    /** 审计导出配置（fms.audit.*） */
    private Audit audit = new Audit();

    /** 数据库部署参数期望值，fms.db.*，db 环境级，yaml 为期望值单一来源，运维按环境生效 */
    private Db db = new Db();

    /** pgmq 消费配置（fms.pgmq.*） */
    private Pgmq pgmq = new Pgmq();

    /** 前端 ui 配置，fms.ui.*，经 /api/config 下发收敛前端硬编码 */
    private Ui ui = new Ui();

    /** 报表导出白名单（fms.report.*） */
    private Report report = new Report();

    /** 邮件发送配置，fms.mail.*，密码找回闭环，enabled 默认关，未配置 smtp 时零副作用 */
    private Mail mail = new Mail();

    /** 后端补充限流，fms.rate-limit.*，网关已有首道限流，此处为绕过网关直连时的纵深闸 */
    private RateLimit rateLimit = new RateLimit();

    /** pg_cron 调度周期期望值登记，fms.schedule.cron，运维级，init/queues_cron.sql 与 yaml 保持一致 */
    private Schedule schedule = new Schedule();

    /** 会话存储实现，CachePort 实现选择，in-memory | redis，多实例共享存储 */
    private String sessionStore;

    /** jwt role 短名映射 pg 职责角色，与 fms_rgw allowed_roles 对齐 */
    private Map<String, String> roleMap;

    /** 内存票据/黑名单兜底清扫周期，毫秒，fms.cache-cleanup-interval-ms，@Scheduled 占位符读取 */
    private long cacheCleanupIntervalMs = 300_000;

    /**
     * 网关签名校验配置，sign_secret 与 fms_rgw sign.sign_secret 共享（≥32 字符），防重放窗口默认 ±300s，
     * nonce 去重表容量上限 fail-closed（显式 nonce-capacity 配置优先，未配置时按 nonce-peak-qps × replay-window-secs 动态计算）。
     */
    @Getter
    @Setter
    public static class Security {

        /** 与 fms_rgw sign.sign_secret 共享（≥32 字符，签名过滤器构造时 fail-fast） */
        private String signSecret;

        /** 防重放窗口，秒，默认 300（±5min） */
        private long replayWindowSecs = 300;

        /**
         * 内存 NonceStore 容量上限（fail-closed），0 = 未配置，哨兵，按
         * noncePeakQps × replayWindowSecs 动态推导，显式配置（>0）优先
         * （如 yaml nonce-capacity: 10000）
         */
        private int nonceCapacity = 0;

        /**
         * 期望峰值 qps（fms.security.nonce-peak-qps，默认 1000），nonce-capacity 未配置时
         * 容量 = noncePeakQps × replayWindowSecs（如 1000×300=300000）。
         * 6 万 qps 瞬时请求可击穿 10000 容量，fail-closed 拒绝合法请求，动态容量按
         * 峰值预期推导消除该缺口
         */
        private int noncePeakQps = 1000;

        /** 仅本地开发跳过签名校验，生产必须 false */
        private boolean bypassGateway;

        /** 网关请求体上限，字节，默认 10MB（与 fms_rgw 对齐） */
        private int maxBodyBytes = 10 * 1024 * 1024;

        /** 网关内部端点 token（fms.security.internal-token，默认空=端点禁用 fail-closed），/internal/log-batch 头 X-FMS-Int-Token 校验值，与网关内部中转约定一致 */
        private String internalToken = "";

        /** 密码最小长度，默认 8，仅允许收紧，Service 层取 max(配置,8) 防漂移出更弱策略 */
        private int passwordMinLength = 8;

        /** 密码最大长度，默认 64，与 dto @Size 对齐 */
        private int passwordMaxLength = 64;

        /** bcrypt 成本因子（默认 10，与 sql 种子 gen_salt('bf',10) 对齐，启动期幂等配置） */
        private int bcryptStrength = 10;

        /** 弱密码布隆过滤器预检配置（fms.security.weak-password.*） */
        private WeakPassword weakPassword = new WeakPassword();

    }

    /**
     * 弱密码预检配置，布隆预期元素数/误判率推导位数组（默认 1024/0.01 得 m≈9816、k=7），
     * 登录成功弱密码标记默认关，响应契约不变，产品确认后开启。
     */
    @Getter
    @Setter
    public static class WeakPassword {

        /** 总开关，默认开启，预检纯只读、零误伤，可安全默认开 */
        private boolean enabled = true;

        /** 布隆预期元素数，推导 m/k，内置集合 ~180 项留有 ~5 倍余量 */
        private int expectedInsertions = 1024;

        /** 布隆误判率，默认 1%，误判仅多一次 HashSet 查询，无拒绝风险 */
        private double falsePositiveRate = 0.01;

        /** 登录成功弱密码标记 requirePasswordChange，默认开，命中弱密码集合即提示改密 */
        private boolean markOnLogin = true;

    }

    /**
     * jwt 配置，secret 与网关 auth.jwt_secret 及 pg um.params['jwt.secret'] 三方一致，access 2h / refresh 7d。
     */
    @Getter
    @Setter
    public static class Jwt {

        /** 与网关 jwt_secret 及 pg sec.app_secrets.jwt.secret 一致 */
        private String secret;

        /** 签发者，与 fms_rgw expected_iss 对齐 */
        private String issuer = "fms";

        /** 受众，与 fms_rgw expected_aud 对齐 */
        private String audience = "fms-gateway";

        /** access 令牌有效期，默认 2h */
        private Duration accessTtl = Duration.ofHours(2);

        /** refresh 令牌有效期，默认 7d */
        private Duration refreshTtl = Duration.ofDays(7);

        /** db 侧 auth.jwt_sign 兜底签发有效期期望值（默认 24h，与 init/um_org_base.sql 缺省 interval '24 hours' 一致，非协议值，后端签发恒显式注入 exp，db 兜底缺省按本配置保留） */
        private Duration expiry = Duration.ofHours(24);

    }

    /**
     * 2fa 配置，totp otpauth uri issuer，两步登录票据 5 分钟，找回重置票据 30 分钟，
     * totp 失败计数/时钟偏移/票据与备用码参数全部收敛于此，原硬编码常量。
     */
    @Getter
    @Setter
    public static class Mfa {

        /** totp otpauth uri issuer */
        private String issuer = "FMS";

        /** 2fa 两步登录票据有效期，默认 5 分钟 */
        private Duration ticketTtl = Duration.ofMinutes(5);

        /** 密码找回重置票据有效期，默认 30 分钟 */
        private Duration recoveryTicketTtl = Duration.ofMinutes(30);

        /** totp 连续失败锁定阈值，默认 5 次 */
        private int totpMaxFails = 5;

        /** totp 失败窗口，默认 5 分钟，Duration 绑定必须带单位 */
        private Duration totpFailWindow = Duration.ofMinutes(5);

        /** totp 校验时钟偏移步数（±window，默认 1，与客户端/网关算法协商一致） */
        private int totpWindow = 1;

        /** totp setup 待启用密钥有效期，默认 10 分钟 */
        private Duration pendingSecretTtl = Duration.ofMinutes(10);

        /** 2fa 备用恢复码数量，默认 10 个 */
        private int backupCodeCount = 10;

        /** 2fa 备用恢复码位数，默认 8 位 */
        private int backupCodeLength = 8;

        /** 2fa 备用恢复码有效期，默认 30 天，db 盲索引落盘，过期行保留供审计 */
        private int backupCodeValidDays = 30;

    }

    /**
     * 登录锁定策略，连续失败 6 次锁定 15 分钟。
     */
    @Getter
    @Setter
    public static class Login {

        /** 连续失败锁定阈值，默认 6 */
        private int maxFailures = 6;

        /** 锁定时长，默认 15 分钟，yaml 中 Duration 绑定必须带单位 */
        private Duration lockMinutes = Duration.ofMinutes(15);

    }

    /**
     * 访问日志批接收配置，fms.log.*，内部端点 /internal/log-batch 使用，
     * 单批上限防超大 body 拖垮队列/db（与网关 log.batch_size 联动，网关 batch_size
     * 调大后不得超过本上限，否则 40001 拒绝），path/ua 兜底截断与网关侧
     * log.path_max_len / ua_max_len 收敛同值，网关已截断，此处防异常体撑爆 pgmq 消息。
     */
    @Getter
    @Setter
    public static class Log {

        /** 单批上限，默认 1000 条，超限 40001 拒绝 */
        private int batchMaxItems = 1000;

        /** path 字段兜底截断长度，默认 1024，与网关 log.path_max_len 同值，防异常体 */
        private int pathMaxLen = 1024;

        /** ua 字段兜底截断长度，默认 256，与网关 log.ua_max_len 同值，防异常体 */
        private int uaMaxLen = 256;

    }

    /**
     * 文件存储配置（fms.file.*），本地卷目录 + 大小上限 + 扩展名白名单。
     *
     * 大小上限默认 8MB，低于网关 10MB 请求体上限（fms_rgw filter.rs MAX_BODY_BYTES），
     * 为 multipart 边界/字段开销留余量，扩展名白名单为上传唯一类型裁决，mime 仅记录元数据。
     */
    @Getter
    @Setter
    public static class File {

        /** 本地存储根目录（默认 ./data/files，容器内 /app/data/files + compose fms-files 命名卷持久化） */
        private String storageDir = "./data/files";

        /** 单文件大小上限，字节，默认 8MB = 8388608，与 docs.documents CHECK 上限对齐 */
        private long maxSizeBytes = 8L * 1024 * 1024;

        /** 扩展名白名单，小写、不含点，大小写不敏感匹配，tsv 对齐前端对账单导入白名单 ReconcilePage，两端名单经 /api/config 收敛一致 */
        private List<String> allowedExtensions = List.of(
                "pdf", "jpg", "jpeg", "png", "gif", "doc", "docx", "xls", "xlsx", "csv", "txt", "tsv");

        /** 存储根目录，Path 形态，FileServiceImpl 解析相对路径用 */
        public Path getStorageDirPath() {
            return Path.of(storageDir);
        }

    }

    /**
     * 审计导出配置（fms.audit.*），csv 导出游标循环每页取数行数，运维级性能/内存旋钮，
     * 控制单次结果集与连接占用，大审计表下不同部署规模需不同值，默认 2000。
     */
    @Getter
    @Setter
    public static class Audit {

        /** 导出游标每页行数，默认 2000 */
        private long exportPageSize = 2000;

    }

    /**
     * 数据库部署参数期望值，fms.db.*，db 环境级，yaml 为期望值单一来源，
     * 运维按环境经 ALTER DATABASE / ALTER ROLE 生效，本类仅承载登记，后端运行不依赖其生效。
     */
    @Getter
    @Setter
    public static class Db {

        /** pg_trgm.similarity_threshold guc 期望值，默认 0.3，对账候选集 % 运算符相似度下界过滤依赖，init/bootstrap_base.sql ALTER DATABASE 登记 */
        private double trgmSimilarityThreshold = 0.3;

        /** 运行时连接角色 fms_app 的 CONNECTION LIMIT 期望值，默认 100，与 Hikari 池上限联动，init/bootstrap_base.sql 角色重建生效 */
        private int connectionLimit = 100;

    }

    /**
     * pgmq 消费配置（fms.pgmq.*），消费失败重试阈值，read_ct 超限转死信队列 q_sys_retry，后端 pgmq 消费逻辑读取，db 侧无硬编码 + 通知生成源消费者
     * （q_notify_task/q_budget_warning 投 notify.notifications，NotifyQueueConsumer 轮询）。
     */
    @Getter
    @Setter
    public static class Pgmq {

        /** 消费失败重试阈值，默认 5 次，read_ct 超限转死信 */
        private int retryThreshold = 5;

        /** 通知生成源消费者总开关，默认开，关停时消息留在队列，pgmq vt 过期后仍可被重拾。
         * NotifyQueueConsumer 内置 pgmq 可用性自探测，pg_extension 无 pgmq 的部署、
         * 镜像未编译该扩展时自动降级停用消费者，消息留队、限频 WARN，扩展补齐后
         * 自动恢复。本开关为最高优先级的显式硬关停，两者叠加满足"含 pgmq 部署或按部署形态关闭"。 */
        private boolean notifyConsumerEnabled = true;

        /** 通知消费者轮询周期，毫秒，默认 30s，@Scheduled 占位符读取，独立于 cache-cleanup */
        private long notifyConsumerIntervalMs = 30_000;

    }

    /**
     * 邮件发送配置，fms.mail.*，密码找回闭环，enabled 总开关默认关，smtp 未配置的
     * 环境零副作用，recovery 仍返回票据，兼容旧契约，host/port/username/password 与
     * spring.mail.* 同源占位，MailService 优先取 Spring 自动装配的 JavaMailSender，
     * 缺 bean 时按本段自建兜底，from/主题/正文模板为发信内容来源。
     */
    @Getter
    @Setter
    public static class Mail {

        /** 总开关，默认关，开启后 recovery 命中真实用户即发重置邮件，发送失败仅 WARN */
        private boolean enabled;

        /** smtp 主机（与 spring.mail.host 同源占位 ${FMS_SMTP_HOST:}，空 = 未配置） */
        private String host;

        /** smtp 端口，默认 465 SSL，与 spring.mail.port 同源占位 */
        private int port = 465;

        /** smtp 用户名，与 spring.mail.username 同源占位，可空=匿名认证 */
        private String username;

        /** smtp 密码，与 spring.mail.password 同源占位，无默认明文 */
        private String password;

        /** 发件人地址，默认 no-reply@fms.local，与 seed_data um.params smtp.from 占位对齐 */
        private String from = "no-reply@fms.local";

        /** 密码重置邮件主题 */
        private String resetSubject = "FMS 密码重置";

        /** 密码重置邮件正文模板（{loginName}/{ticket}/{expiresAt} 占位符，发送前替换） */
        private String resetBodyTemplate =
                "您好 {loginName}：\n您正在找回 FMS 财务系统登录密码。\n重置票据：{ticket}\n有效期至：{expiresAt}\n请妥善保管，勿转发他人。";

    }

    /**
     * 后端补充限流，fms.rate-limit.*，纵深防御，网关已有首道限流，本段为绕过网关
     * 直连后端时的补充闸，仅覆盖三个敏感/匿名入口，login/recovery 防爆破、/internal/log-batch
     * 防日志注入泛滥，不重复限流业务 /api/** 全量，避免与网关双倍影响。滑动窗口
     * 计数经 CachePort（fms:ratelimit:{type}:{ip}，increment+ttl=60s）。
     */
    @Getter
    @Setter
    public static class RateLimit {

        /** /internal/log-batch 每 ip 每分钟上限，默认 600，宽松，正常网关批量上报远低于此 */
        private int internalBatchPerMin = 600;

        /** /api/auth/login 每 ip 每分钟上限，默认 20 */
        private int loginPerMin = 20;

        /** /api/auth/recovery 每 ip 每分钟上限，默认 10，防找回接口被当邮件轰炸器 */
        private int recoveryPerMin = 10;

    }

    /**
     * 前端 ui 配置（fms.ui.*），列表分页每页条数选项，经 /api/config 下发收敛
     * DataTable/Pager 两处硬编码，默认 10/20/50。
     */
    @Getter
    @Setter
    public static class Ui {

        /** 分页每页条数选项，默认 [10, 20, 50] */
        private List<Integer> pageSizes = List.of(10, 20, 50);

    }

    /**
     * 报表导出白名单（fms.report.*），后端 RptServiceImpl 校验，未知值 40001 +
     * 前端导出选项经 /api/config 下发收敛，默认六类报表。
     */
    @Getter
    @Setter
    public static class Report {

        /** 导出报表白名单，默认六类，trial-balance/income-statement/balance-sheet/cash-flow/ar-aging/dept-expense */
        private List<String> enabledTypes = List.of(
                "trial-balance", "income-statement", "balance-sheet", "cash-flow", "ar-aging", "dept-expense");

    }

    /**
     * pg_cron 调度周期期望值登记（fms.schedule.cron），运维/部署级调度配置，
     * yaml 为环境期望值单一来源，值含时区随部署环境差异，init/queues_cron.sql 登记值保持与 yaml 一致。
     */
    @Getter
    @Setter
    public static class Schedule {

        /** 9 个 pg_cron 任务 cron 表达式（job_name 对应 cron） */
        private Map<String, String> cron = Map.of(
                "voucher-number-check", "0 6 * * *",
                "fa-depreciation", "0 2 1 * *",
                "fx-adjustment", "0 3 1 * *",
                "close-check", "0 4 1 * *",
                "budget-warning", "*/15 * * * *",
                "wf-timeout", "* * * * *",
                "partman-maintenance", "0 2 * * *",
                "audit-chain-check", "0 5 * * *",
                "log-consumer", "* * * * *");

    }

    /** 安全告警配置，fms.alert.*，AlertService 定时检测 */
    private Alert alert = new Alert();

    /** 安全告警检测参数，暴力破解聚合/审计链/nonce 水位 */
    @Getter
    @Setter
    public static class Alert {

        /** 检测周期，毫秒，@Scheduled 占位符读取 */
        private long intervalMs = 300_000;

        /** 暴力破解判定阈值，窗口内同 ip 登录失败次数达到即告警 */
        private int loginFailThreshold = 20;

        /** 暴力破解统计窗口，分钟 */
        private int loginFailWindowMin = 15;

        /** nonce 水位告警比率，近似条目数/期望容量，超过即 WARN */
        private double nonceWatermarkRatio = 0.7;

        /** 期望从库数（0=未启用主从监控，集群形态填实际从库数，实际 < 期望即告警"从库缺失"） */
        private int replicaExpected = 0;

        /** 从库复制延迟告警阈值，毫秒，streaming 最大 replay_lag 超过即告警 */
        private long replicaLagThresholdMs = 5_000;

    }

    /** 只读数据源配置，fms.datasource-ro.*，从库只读分流，默认禁用 = 单源直连主库 */
    private DatasourceRo datasourceRo = new DatasourceRo();

    /**
     * 只读数据源，从库分流，enabled=true 时 RoutingDataSource 启用读写路由，
     * readOnly=true 事务走只读 url（pgbouncer-ro 指向从库），其余走主库。
     *
     * url 缺省回退主库 url，防配置疏漏，从库不可用时报表查询降级主库。
     */
    @Getter
    @Setter
    public static class DatasourceRo {

        /** 是否启用读写分离，单机/多机形态 false，集群形态 true */
        private boolean enabled = false;

        /** 只读连接 url（如 jdbc:postgresql://fms-pgbouncer-ro:6433/fms?prepareThreshold=0），空 = 回退主库 url */
        private String url;

    }

    /** 集群配置，fms.cluster.*，多实例综合信息展示 */
    private Cluster cluster = new Cluster();

    /** 集群配置，peers 为对等后端实例地址列表，多实例信息经内部端点聚合 */
    @Getter
    @Setter
    public static class Cluster {

        /** 对等后端实例 host:port 列表，FMS_CLUSTER_PEERS 逗号分隔注入，应含自身 */
        private List<String> peers = List.of();

    }
}
