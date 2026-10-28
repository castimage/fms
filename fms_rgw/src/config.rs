//! 环境变量覆盖`FMS_RGW_*`仅覆盖非空变量，其次 yaml配置加载，最后默认值
//!
//! 全部结构体使用#[serde，default]确保缺段缺字段回退默认值
//!
//! main启动期调用，[CircuitCfg]供`circuit.rs`使用，各配置段被中间件读取
#![forbid(unsafe_code)]

use crate::filter::MAX_BODY_BYTES;
use serde::Deserialize;
use std::io;
use std::path::Path;
use thiserror::Error;

/// 配置加载错误，启动期失败允许main报错退出
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("配置读取失败：{0}")]
    Io(#[from] io::Error),

    #[error("配置解析失败：{0}")]
    Parse(#[from] noyalib::compat::serde_yaml::Error),
}

/// 读取配置，优先读取`FMS_RGW_CONFIG`环境变量指定的路径，否则使用`config/fms_rgw.yaml`
///
/// 如全部缺失则使用默认值做配置
pub fn load() -> Result<Config, ConfigError> {
    load_with(&|name| std::env::var(name).ok())
}

/// [load]的实现，传入函数更方便测试
///
/// 优先读取传入函数获取`FMS_RGW_CONFIG`的值，一般是环境变量，否则使用`config/fms_rgw.yaml`
fn load_with(get_env: &dyn Fn(&str) -> Option<String>) -> Result<Config, ConfigError> {
    if let Some(p) = get_env("FMS_RGW_CONFIG")
        && !p.is_empty()
    {
        let path = Path::new(&p);
        if path.exists() {
            return load_from(path);
        }

        return Err(ConfigError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("FMS_RGW_CONFIG 指定文件不存在: {p}"),
        )));
    }

    let path = Path::new("config/fms_rgw.yaml");
    if path.exists() {
        load_from(path)
    } else {
        // 默认配置文件缺失就全部用默认值
        Ok(Config::default())
    }
}

/// 显式从指定配置文件路径获取配置
pub fn load_from(path: &Path) -> Result<Config, ConfigError> {
    let text = std::fs::read_to_string(path)?;
    Ok(noyalib::compat::serde_yaml::from_str(&text)?)
}

/// 环境变量覆盖，仅非空环境变量生效
///
/// 优先级，环境变量 > yaml > 默认值
pub fn apply_env_overrides(cfg: &mut Config) {
    apply_env_overrides_with(cfg, &|name| std::env::var(name).ok());
}

/// [apply_env_overrides]的实现，仅非空值生效，传入函数更方便测试
fn apply_env_overrides_with(cfg: &mut Config, get_env: &dyn Fn(&str) -> Option<String>) {
    override_str(&mut cfg.server.listen, "FMS_RGW_LISTEN", get_env);
    override_str(&mut cfg.upstream.base_url, "FMS_RGW_UPSTREAM", get_env);
    override_str(&mut cfg.upstream.mode, "FMS_RGW_UPSTREAM_MODE", get_env);
    if let Some(v) = get_env("FMS_RGW_UPSTREAM_SERVERS")
        && !v.is_empty()
    {
        cfg.upstream.servers = v
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
    }
    override_str(&mut cfg.auth.jwt_secret, "FMS_RGW_JWT_SECRET", get_env);
    override_str(&mut cfg.sign.sign_secret, "FMS_RGW_SIGN_SECRET", get_env);
    override_str(&mut cfg.auth.deny_store, "FMS_RGW_DENY_STORE", get_env);

    // 爬虫ua拦截开关，仅解析true/false文本
    if let Some(v) = get_env("FMS_RGW_BLOCK_CRAWLERS")
        && (v == "true" || v == "false")
    {
        cfg.server.block_crawlers = v == "true";
    }
    override_str(&mut cfg.static_cfg.dir, "FMS_RGW_STATIC_DIR", get_env);
    if let Some(v) = get_env("FMS_RGW_TLS_CERT")
        && !v.is_empty()
    {
        cfg.tls.cert = Some(v);
    }
    if let Some(v) = get_env("FMS_RGW_TLS_KEY")
        && !v.is_empty()
    {
        cfg.tls.key = Some(v);
    }
    if let Some(v) = get_env("FMS_RGW_LOG_FILE")
        && !v.is_empty()
    {
        cfg.log.access_file = Some(v);
    }
    if let Some(v) = get_env("FMS_RGW_LOG_TOKEN")
        && !v.is_empty()
    {
        // pgmq投递内部token(X-FMS-Int-Token)，yaml留空，经环境变量注入
        cfg.log.pgmq.token = v;
    }
    override_u64(
        &mut cfg.log.pgmq.timeout_secs,
        "FMS_RGW_LOG_TIMEOUT",
        get_env,
    );

    // trace采样率，百分比语义，100=全量
    override_u32(
        &mut cfg.trace.sample_rate,
        "FMS_RGW_TRACE_SAMPLE_RATE",
        get_env,
    );

    // 限流跳过路径，逗号分隔前缀列表
    if let Some(v) = get_env("FMS_RGW_RATELIMIT_SKIP_PATHS")
        && !v.is_empty()
    {
        cfg.limits.skip_paths = v
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
    }

    // ip访问白名单，逗号分隔ip/cidr列表，空配置=不启用
    if let Some(v) = get_env("FMS_RGW_IP_ALLOWLIST")
        && !v.is_empty()
    {
        cfg.server.ip_allowlist = v
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
    }
}

/// 覆盖环境变量的str值，非空才覆盖，传入函数更方便测试
fn override_str(target: &mut String, env: &str, get_env: &dyn Fn(&str) -> Option<String>) {
    if let Some(v) = get_env(env)
        && !v.is_empty()
    {
        *target = v;
    }
}

/// 覆盖环境变量的u64值，非空才覆盖，传入函数更方便测试
fn override_u64(target: &mut u64, env: &str, get_env: &dyn Fn(&str) -> Option<String>) {
    if let Some(v) = get_env(env)
        && !v.is_empty()
        && let Ok(n) = v.trim().parse::<u64>()
    {
        *target = n;
    }
}

/// 覆盖环境变量的u32值，非空才覆盖，传入函数更方便测试
fn override_u32(target: &mut u32, env: &str, get_env: &dyn Fn(&str) -> Option<String>) {
    if let Some(v) = get_env(env)
        && !v.is_empty()
        && let Ok(n) = v.trim().parse::<u32>()
    {
        *target = n;
    }
}

// 所有config全部用#[serde，default]确保回退默认值

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Config {
    pub server: ServerCfg,
    pub tls: TlsCfg,
    pub upstream: UpstreamCfg,
    pub auth: AuthCfg,
    pub sign: SignCfg,
    pub limits: RateLimitCfg,
    pub trace: TraceCfg,
    pub circuit: CircuitCfg,
    pub cors: CorsCfg,
    pub ws: WsCfg,
    pub log: LogCfg,
    /// `static`为rust关键字，序列化名映射为static
    #[serde(rename = "static")]
    pub static_cfg: StaticCfg,
}

/// 服务器配置
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct ServerCfg {
    /// 监听地址端口，默认0.0.0.0:8080
    pub listen: String,
    /// 并发连接数上限，默认8192
    ///
    /// 基于信号量semaphore实现，兼作in-flight计数，优雅关闭等待全部释放后退出，
    /// 满则拒绝新连接
    pub max_connections: usize,
    /// 优雅关闭宽限期，停止accept后等待in-flight请求完成的最长时限，超时强制退出，默认10s
    pub graceful_shutdown_secs: u64,
    /// 请求体上限，content-length声明和实际缓冲双重校验，超限返回413，
    /// websocket握手声明长度复用，默认10mb
    ///
    /// 如果要批量导入或附件上传场景则需调大，要与后端`fms.security.max-body-bytes`对齐，
    /// 而且必须大于`fms.file.max-size-bytes`，8mb留multipart余量
    pub max_body_bytes: usize,
    /// ip访问白名单，ip或cidr文本列表，如["10.0.0.0/8", "192.168.1.5"]
    ///
    /// 留空则不启用，全部来源放行。启用后非白名单来源直接拒绝，安全链最前一环，
    /// 返回403 fail-closed，用真实客户端ip，对端ip判定，因此`XFF`/`X-Real-IP`伪造不影响
    pub ip_allowlist: Vec<String>,
    /// 爬虫ua拦截开关，默认打开
    ///
    /// 识别到已知脚本爬虫特征，python-requests/scrapy等则直接403丢弃，
    /// 不拦curl/wget/真实浏览器，留正常访问流量和测试流量正常通过
    pub block_crawlers: bool,
    /// 响应体压缩，gzip/deflate/br/zstd
    pub compression: CompressionCfg,
}

/// 响应体压缩配置，静态资源与api响应通用
///
/// 启用后自动加`Vary: Accept-Encoding`和`Content-Encoding`
///
/// websocket升级握手自动跳过
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct CompressionCfg {
    /// 总开关，默认打开
    ///
    /// 关闭则不装配压缩层，响应不压缩直接返回
    pub enabled: bool,
    /// 最小压缩字节数，低于此长度的响应体不压缩，默认1024
    pub min_length: usize,
    /// 压缩级别，范围0-9，0表示不压缩、1最快、9最佳
    ///
    /// 默认设置为6，映射`CompressionLevel::Preset`
    pub level: i32,
}

/// 压缩配置的默认值
impl Default for CompressionCfg {
    fn default() -> Self {
        Self {
            enabled: true,
            min_length: 1024,
            level: 6,
        }
    }
}

/// 服务器配置的默认值
impl Default for ServerCfg {
    fn default() -> Self {
        Self {
            listen: "0.0.0.0:8080".to_string(),
            max_connections: 8192,
            graceful_shutdown_secs: 10,
            // 与filter::MAX_BODY_BYTES同源，10mb，两处默认值须一致
            max_body_bytes: MAX_BODY_BYTES,
            compression: CompressionCfg::default(),
            ip_allowlist: Vec::new(),
            block_crawlers: true,
        }
    }
}

/// tls配置
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct TlsCfg {
    /// tls证书路径
    pub cert: Option<String>,
    pub key: Option<String>,
    /// 证书缺失/加载失败时是否允许降级明文http，仅开发模式打开，
    /// 生产必须关闭，若缺证书则直接启动失败
    pub allow_insecure_fallback: bool,
}

/// 上游配置
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct UpstreamCfg {
    /// 上游base_url，默认http://127.0.0.1:8081(servers为空时的单上游地址，
    /// `FMS_RGW_UPSTREAM`环境变量可覆盖)
    pub base_url: String,
    /// 多服务器列表(url字符串，非空时优先于[base_url]，servers为空时内部等价于vec!\[base_url\]，
    /// 单上游时任何mode行为一致，天然兼容)，`FMS_RGW_UPSTREAM_SERVERS`，逗号分隔覆盖
    pub servers: Vec<String>,
    /// 负载均衡算法
    ///
    /// 轮询`round-robin`，默认 | 随机`random` | 最少连接`least-conn` | ip哈希`ip-hash` | 自动`auto`
    ///
    /// `auto` = `least-conn` + 自动健康管理
    ///
    /// `FMS_RGW_UPSTREAM_MODE`覆盖
    pub mode: String,
    /// 转发失败自动重试次数上限，仅重试健康实例，默认2次，初次 + 2次重试
    pub retry_count: usize,
    /// 请求超时时间，默认30s
    pub request_timeout_secs: u64,
    /// 连接池空闲回收时间，默认60s
    pub idle_timeout_secs: u64,
    /// 健康探测路径，默认`/internal/health`
    pub health_path: String,
    /// 主动探测周期时间，默认5s
    pub health_interval_secs: u64,
    /// 探测请求超时时间，默认2s
    pub health_timeout_secs: u64,
    /// 上游连接池每host空闲连接上限，默认16
    pub pool_max_idle_per_host: usize,
}

/// 上游配置的默认值
impl Default for UpstreamCfg {
    fn default() -> Self {
        Self {
            base_url: "http://127.0.0.1:8081".to_string(),
            servers: Vec::new(),
            mode: "round-robin".to_string(),
            retry_count: 2,
            request_timeout_secs: 30,
            idle_timeout_secs: 60,
            health_path: "/internal/health".to_string(),
            health_interval_secs: 5,
            health_timeout_secs: 2,
            pool_max_idle_per_host: 16,
        }
    }
}

/// 认证配置
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct AuthCfg {
    /// jwt验签密钥，必填，为空则main启动报错退出
    pub jwt_secret: String,
    /// iss校验，默认为None，不校验iss
    pub expected_iss: Option<String>,
    /// aud校验，默认为None，不校验aud
    pub expected_aud: Option<String>,
    /// exp时钟偏差容忍，默认60s
    pub leeway_secs: i64,
    /// 角色白名单
    ///
    /// `claims.role`必须命中，未知角色一律401/40100
    ///
    /// 与pg角色映射，
    ///
    /// `operator` 映射 `fms_operator`
    ///
    /// `viewer` 映射 `fms_viewer`
    ///
    /// `auditor` 映射 `fms_auditor`
    ///
    /// `report` 映射 `fms_report`
    ///
    /// `admin` 映射 `fms_operator`
    pub allowed_roles: Vec<String>,
    /// 登出黑名单回退ttl
    ///
    /// token无法解析出exp时的默认到期时间，对齐后端access token
    /// 默认2h `fms.jwt.access-ttl`，后端调整access ttl时网关需同步，默认7200s
    pub logout_blacklist_ttl_secs: i64,
    /// 登出撤销共享后端
    ///
    /// `memory`，进程内，默认，单网关实例 | `redis` (多网关实例共享，
    /// 复用`limits.redis_url`连接，登出撤销任意实例写入、全部实例生效)
    ///
    /// 若配置为redis时缺url则rgw启动失败fail-closed，redis不可达时撤销为尽力而为
    ///
    /// 本实例进程内表兜底 + WARN，后端refresh撤销由redis黑名单权威保证
    pub deny_store: String,
}

/// 认证配置的默认值
impl Default for AuthCfg {
    fn default() -> Self {
        Self {
            jwt_secret: String::new(),
            expected_iss: None,
            expected_aud: None,
            leeway_secs: 60,
            allowed_roles: vec![
                "operator".to_string(),
                "viewer".to_string(),
                "auditor".to_string(),
                "report".to_string(),
                "admin".to_string(),
            ],
            logout_blacklist_ttl_secs: 7200,
            deny_store: "memory".to_string(),
        }
    }
}

/// 签名配置
///
/// 注，防重放时间窗为协议常量`DEFAULT_REPLAY_WINDOW_SECS` = 300
///
/// 网关只生成签名不校验客户端签名，后端同构校验与生成侧必须使用同一常量)
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct SignCfg {
    /// hmac签名密钥，必填，与后端共享，为空则main启动报错退出
    pub sign_secret: String,
}

/// 限流配置
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct RateLimitCfg {
    /// ip维度每窗口次数上限，默认100
    pub ip_per_window: u32,
    /// 用户维度每窗口次数上限，默认200
    pub user_per_window: u32,
    /// 登录端点维度，按ip收敛，每窗口次数上限，默认10
    pub login_per_window: u32,
    /// 限流表分片数，key按hash均摊到各`DashMap`分片，热路径无跨片锁竞争，默认64
    pub shards: usize,
    /// 限流表条目数上限，内存保护，key数达上限先清扫过期窗口，仍满则拒绝
    /// fail-closed防内存耗尽，按分片均摊，每片至少1，默认100000
    pub max_keys: usize,
    /// 限流滑动窗口时长(ip/用户/登录三维度共用，与`ip_per_window`等每窗次数键配套)，
    /// 默认60s
    pub window_secs: u64,
    /// `periodic_cleanup`清扫周期
    ///
    /// 限流表过期窗口与登出令牌黑名单过期条目按此周期回收，
    /// 影响内存回收时效与表容量水位，默认60s
    pub cleanup_interval_secs: u64,
    /// 限流跳过路径，前缀匹配列表
    ///
    /// 静态资源/健康检查等不需配额的路径不计入限流计数
    ///
    /// 默认保守列表["/assets/", "/healthz", "/favicon", "/internal/"]
    ///
    /// `max_connections`、`Semaphore`仍兜底防连接耗尽dos，无需改
    pub skip_paths: Vec<String>,
    /// 限流后端，
    ///
    /// `memory`，默认，无外部依赖，单网关实例
    ///
    /// `redis` (多网关实例共享计数，ZSET + WATCH/MULTI 乐观事务滑动窗口，经`redis_url`连接，任一实例超限则全局限流，防止多实例分散攻击)
    pub backend: String,
    /// redis后端连接url，默认空
    ///
    /// 仅backend=`redis`时必填，backend=`memory`时忽略
    pub redis_url: Option<String>,
}

/// 限流配置的默认值
impl Default for RateLimitCfg {
    fn default() -> Self {
        Self {
            ip_per_window: 100,
            user_per_window: 200,
            login_per_window: 10,
            shards: 64,
            max_keys: 100_000,
            window_secs: 60,
            cleanup_interval_secs: 60,
            skip_paths: vec![
                "/assets/".to_string(),
                "/healthz".to_string(),
                "/favicon".to_string(),
                "/internal/".to_string(),
            ],
            backend: "memory".to_string(),
            redis_url: None,
        }
    }
}

/// trace采样配置
///
/// `trace.sample_rate`，与`log.sample_rate`相互独立，log按1/n序号取模，
/// trace按百分比语义seq%100<rate时采用
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct TraceCfg {
    /// traceId采样率，默认100，100%
    ///
    /// 百分比语义，100=全量生成透传完整traceId（默认），10=确定性采样10%，
    /// 请求序号seq%100<rate才采样，非随机数可复现，未采样请求不注入`X-Trace-Id`、
    /// 日志`req_id`为空
    pub sample_rate: u32,
}

/// trace采样配置的默认值
impl Default for TraceCfg {
    fn default() -> Self {
        Self { sample_rate: 100 }
    }
}

/// 熔断配置
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct CircuitCfg {
    /// 被动错误率阈值百分比，失败样本数/样本数，默认50
    pub error_threshold_pct: u32,
    /// 最近样本窗口，默认100
    pub sample_window: usize,
    /// 最少样本数，不足不触发，防冷启动误熔断，默认20
    pub min_samples: usize,
    /// OPEN持续时间，默认30s
    pub open_timeout_secs: u64,
    /// HALF-OPEN并发探测放行数，默认5
    pub half_open_probes: u32,
}

/// 熔断配置的默认值
impl Default for CircuitCfg {
    fn default() -> Self {
        Self {
            error_threshold_pct: 50,
            sample_window: 100,
            min_samples: 20,
            open_timeout_secs: 30,
            half_open_probes: 5,
        }
    }
}

/// cors配置
///
/// 空origin白名单则拒绝全部跨域
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct CorsCfg {
    /// 允许的`Origin`白名单
    ///
    /// 不支持通配符，空则拒绝全部跨域，仅允许同源
    pub allowed_origins: Vec<String>,
    /// 允许的http方法
    ///
    /// 预检回显`Access-Control-Allow-Methods`，默认GET/POST/PUT/DELETE/PATCH/OPTIONS
    pub allowed_methods: Vec<String>,
    /// 允许的请求头
    ///
    /// 预检回显`Access-Control-Allow-Headers`，默认Authorization/Content-Type/X-Trace-Id/X-Requested-With
    pub allowed_headers: Vec<String>,
    /// 允许暴露给前端读取的响应头
    ///
    /// 回显`Access-Control-Expose-Headers`，默认X-Trace-Id
    pub expose_headers: Vec<String>,
    /// 预检缓存秒数，默认86400
    pub max_age_secs: u64,
}

/// cors配置的默认值
impl Default for CorsCfg {
    fn default() -> Self {
        Self {
            allowed_origins: Vec::new(),
            allowed_methods: vec![
                "GET".to_string(),
                "POST".to_string(),
                "PUT".to_string(),
                "DELETE".to_string(),
                "PATCH".to_string(),
                "OPTIONS".to_string(),
            ],
            allowed_headers: vec![
                "Authorization".to_string(),
                "Content-Type".to_string(),
                "X-Trace-Id".to_string(),
                "X-Requested-With".to_string(),
            ],
            expose_headers: vec!["X-Trace-Id".to_string()],
            max_age_secs: 86_400,
        }
    }
}

/// websocket配置
///
/// `config/fms_rgw.yaml`的`ws:`段
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct WsCfg {
    /// 升级等待超时时间
    ///
    /// 上游返回101后对端迟迟未完成升级时清理任务与连接，
    /// 防连接耗尽dos，慢客户端/弱网场景可调，默认10s
    pub upgrade_timeout_secs: u64,
}

/// websocket配置的默认值
impl Default for WsCfg {
    fn default() -> Self {
        Self {
            upgrade_timeout_secs: 10,
        }
    }
}

/// pgmq投递配置，http中转
///
/// 网关不直连pg/pgmq，访问日志经http批量POST后端`/internal/log-batch`
/// 该路径豁免网关签名但需内部token校验，后端批量`pgmq.send`(`q_log_access`)，
/// 后端cron消费落`log.access_logs`，后端链路已存在，勿改
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct LogPgmqCfg {
    /// 总开关，默认false，开启后访问日志经http中转投递pgmq供审计落库
    pub enabled: bool,
    /// 后端批量接收路径，内部token校验，豁免网关签名，默认 `/internal/log-batch`
    pub endpoint: String,
    /// 内部token(请求头`X-FMS-Int-Token`值)，留空 = 即使enabled也不投递，
    /// 经环境变量`FMS_RGW_LOG_TOKEN`注入(`apply_env_overrides`非空才覆盖)。注意，
    /// `noyalib`直接反序列化，不做`${...}`占位展开，YAML中不得写占位符，
    /// 否则该字面串会被原样作为请求头发出，后端比对失败40300静默丢弃
    pub token: String,
    /// 单次http投递超时，秒，弱网/大batch(`batch_size`调大后单次POST体增大)
    /// 场景需放宽，默认3s，可经`FMS_RGW_LOG_TIMEOUT`环境变量覆盖
    pub timeout_secs: u64,
}

impl Default for LogPgmqCfg {
    fn default() -> Self {
        Self {
            enabled: false,
            endpoint: "/internal/log-batch".to_string(),
            token: String::new(),
            timeout_secs: 3,
        }
    }
}

/// 访问日志配置，stdout + 可选文件双写，后台sink批量落盘
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct LogCfg {
    /// 访问日志文件，双写，`None` = 仅stdout
    pub access_file: Option<String>,
    /// tracing过滤级别，默认 "info"
    pub level: String,
    /// 采样率(1 = 全量保审计，10 = 确定性采样1/10，`AtomicU64`请求序号% rate == 0
    /// 才记录，非随机数，审计可复现)，默认1
    pub sample_rate: u32,
    /// 后台sink批量flush条目数(攒批后一次`write_all` + 一次flush，
    /// 静态小文件高qps的关键)，默认100
    pub batch_size: usize,
    /// 条目不足`batch_size`时的最长攒批等待，毫秒，默认500
    pub batch_interval_ms: u64,
    /// 有界通道容量(满则丢弃并计数`dropped_logs`，保护请求路径，日志可丢，
    /// 请求绝不受影响)，默认10000
    pub channel_capacity: usize,
    /// 日志path字段最大长度，字符，防日志注入/超长query撑爆日志行与pgmq消息，
    /// 默认1024，与后端`fms.log.path-max-len`收敛同值(后端`InternalLogController`
    /// 兜底截断，两端调整须同步)
    pub path_max_len: usize,
    /// 日志ua字段最大长度，字符，默认256，与后端`fms.log.ua-max-len`收敛同值
    pub ua_max_len: usize,
    /// pgmq投递，http中转配置
    pub pgmq: LogPgmqCfg,
}

impl Default for LogCfg {
    fn default() -> Self {
        Self {
            access_file: None,
            level: "info".to_string(),
            sample_rate: 1,
            batch_size: 100,
            batch_interval_ms: 500,
            channel_capacity: 10_000,
            path_max_len: 1024,
            ua_max_len: 256,
            pgmq: LogPgmqCfg::default(),
        }
    }
}

/// 静态资源配置(`FMS_RGW_STATIC_DIR`覆盖`dir`，默认 ./static)
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct StaticCfg {
    /// 静态资源根目录，默认./static
    pub dir: String,
    /// `Cache-Control`分级，带hash文件名资源max-age(默认1年，部署方可按发布节奏
    /// 收紧，immutable语义随hash名不变量保留)，默认31536000s
    pub hash_max_age_secs: u64,
    /// `Cache-Control`分级，普通非hash资源max-age，默认1小时，可按发布节奏收紧，
    /// 默认3600s，`index.html` no-store为spa更新语义不变量保持硬编码不动
    pub asset_max_age_secs: u64,
}

/// 静态资源配置的默认值
impl Default for StaticCfg {
    fn default() -> Self {
        Self {
            dir: "./static".to_string(),
            hash_max_age_secs: 31_536_000,
            asset_max_age_secs: 3600,
        }

    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_spec_conformant() {
        let cfg = Config::default();
        assert_eq!(cfg.server.listen, "0.0.0.0:8080");
        assert_eq!(cfg.upstream.base_url, "http://127.0.0.1:8081");
        // 多上游默认值，servers空 = 单上游，mode轮转，重试2次
        assert!(
            cfg.upstream.servers.is_empty(),
            "servers 默认空(单上游兼容)"
        );
        assert_eq!(cfg.upstream.mode, "round-robin");
        assert_eq!(cfg.upstream.retry_count, 2);
        assert_eq!(cfg.upstream.request_timeout_secs, 30);
        assert_eq!(cfg.auth.leeway_secs, 60);
        assert_eq!(
            cfg.auth.allowed_roles,
            vec!["operator", "viewer", "auditor", "report", "admin"],
            "角色白名单默认值必须一致"
        );
        assert_eq!(cfg.limits.ip_per_window, 100);
        assert_eq!(cfg.limits.shards, 64);
        assert_eq!(cfg.circuit.error_threshold_pct, 50);
        assert_eq!(cfg.cors.max_age_secs, 86_400);
        assert_eq!(cfg.log.level, "info");
        assert_eq!(cfg.static_cfg.dir, "./static");
        assert_eq!(cfg.server.max_connections, 8192);
        assert_eq!(cfg.server.graceful_shutdown_secs, 10);
        assert_eq!(cfg.server.max_body_bytes, 10 * 1024 * 1024);
        assert_eq!(cfg.limits.window_secs, 60);
        assert_eq!(cfg.limits.cleanup_interval_secs, 60);
        // 限流跳过默认列表，保守，静态资源/健康检查/内部端点
        assert_eq!(
            cfg.limits.skip_paths,
            vec!["/assets/", "/healthz", "/favicon", "/internal/"]
        );
        // trace采样默认全量，100%，与log.sample_rate（全量1）相互独立
        assert_eq!(cfg.trace.sample_rate, 100, "trace 默认全量采样");
        assert_eq!(cfg.auth.logout_blacklist_ttl_secs, 7200);
        assert_eq!(cfg.ws.upgrade_timeout_secs, 10);
        assert_eq!(cfg.upstream.pool_max_idle_per_host, 16);
        assert_eq!(cfg.static_cfg.hash_max_age_secs, 31_536_000);
        assert_eq!(cfg.static_cfg.asset_max_age_secs, 3600);
        // 访问日志降噪默认值，采样全量保审计、pgmq默认关闭
        assert_eq!(cfg.log.sample_rate, 1, "默认全量采样保审计");
        assert_eq!(cfg.log.batch_size, 100);
        assert_eq!(cfg.log.batch_interval_ms, 500);
        assert_eq!(cfg.log.channel_capacity, 10_000);
        assert_eq!(cfg.log.path_max_len, 1024);
        assert_eq!(cfg.log.ua_max_len, 256);
        assert!(!cfg.log.pgmq.enabled, "PGMQ 默认关闭");
        assert_eq!(cfg.log.pgmq.endpoint, "/internal/log-batch");
        assert_eq!(cfg.log.pgmq.token, "", "内部 token 默认空(环境变量注入)");
        assert_eq!(cfg.log.pgmq.timeout_secs, 3, "PGMQ 投递超时默认 3s");
    }

    #[test]
    fn load_from_parses_yaml_and_fills_defaults() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-cfg-test-{}.yaml", std::process::id()));
        std::fs::write(
            &path,
            "server:\n  listen: \"127.0.0.1:9999\"\nauth:\n  jwt_secret: \"j\"\nsign:\n  sign_secret: \"s\"\nstatic:\n  dir: \"./web\"\n",
        )
        .expect("测试配置写入");
        let cfg = load_from(&path).expect("解析成功");
        let _ = std::fs::remove_file(&path);
        assert_eq!(cfg.server.listen, "127.0.0.1:9999");
        // static段经serde rename映射到static_cfg
        assert_eq!(cfg.static_cfg.dir, "./web");
        // 未写段回退默认值
        assert_eq!(cfg.upstream.base_url, "http://127.0.0.1:8081");
        assert_eq!(cfg.circuit.half_open_probes, 5);
    }

    #[test]
    fn load_from_invalid_yaml_errors() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-cfg-bad-{}.yaml", std::process::id()));
        std::fs::write(&path, "server:\n\tlisten: [unclosed").expect("测试配置写入");
        let err = load_from(&path).expect_err("非法 YAML 必须报错");
        let _ = std::fs::remove_file(&path);
        assert!(matches!(err, ConfigError::Parse(_)));
    }

    #[test]
    fn load_from_missing_file_errors() {
        assert!(matches!(
            load_from(Path::new("config/definitely-not-exists-fms_rgw.yaml")),
            Err(ConfigError::Io(_))
        ));
    }

    #[test]
    fn load_respects_fms_rgw_config_env_path() {
        // 写入临时YAML，注入FMS_RGW_CONFIG指向它则 load应读取该文件
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-fms_rgwconfig-{}.yaml", std::process::id()));
        std::fs::write(&path, "server:\n  listen: \"127.0.0.1:7001\"\n").expect("测试配置写入");
        let cfg = load_with(&|name| {
            if name == "FMS_RGW_CONFIG" {
                Some(path.to_string_lossy().into_owned())
            } else {
                None
            }
        })
        .expect("FMS_RGW_CONFIG 指定文件应被加载");
        let _ = std::fs::remove_file(&path);
        assert_eq!(cfg.server.listen, "127.0.0.1:7001");
    }

    #[test]
    fn load_fms_rgw_config_missing_path_errors() {
        let err = load_with(&|name| {
            if name == "FMS_RGW_CONFIG" {
                Some("config/fms_rgw-不存在-this-file.yaml".to_string())
            } else {
                None
            }
        })
        .expect_err("FMS_RGW_CONFIG 指向缺失文件必须报错");
        assert!(matches!(err, ConfigError::Io(_)));
    }

    #[test]
    fn load_fms_rgw_config_empty_falls_back_to_default_file() {
        // FMS_RGW_CONFIG为空字符串则忽略，走默认config/fms_rgw.yaml，仓库内存在且合法
        let cfg = load_with(&|name| {
            if name == "FMS_RGW_CONFIG" {
                Some(String::new())
            } else {
                None
            }
        })
        .expect("空 FMS_RGW_CONFIG 应回退默认配置文件");
        assert_eq!(
            cfg.server.listen, "0.0.0.0:8443",
            "应读到仓库默认 config/fms_rgw.yaml"
        );
    }

    #[test]
    fn apply_env_overrides_covers_all_vars_and_ignores_empty() {
        let mut cfg = Config::default();
        apply_env_overrides_with(&mut cfg, &|name| match name {
            "FMS_RGW_LISTEN" => Some("0.0.0.0:9090".to_string()),
            "FMS_RGW_UPSTREAM" => Some("http://10.0.0.2:9000".to_string()),
            "FMS_RGW_UPSTREAM_MODE" => Some("least-conn".to_string()),
            "FMS_RGW_UPSTREAM_SERVERS" => Some("http://10.0.0.2:9000, http://10.0.0.3:9000".to_string()),
            "FMS_RGW_JWT_SECRET" => Some("env-jwt".to_string()),
            "FMS_RGW_SIGN_SECRET" => Some("env-sign".to_string()),
            "FMS_RGW_STATIC_DIR" => Some("./www".to_string()),
            "FMS_RGW_TLS_CERT" => Some("certs/e.crt".to_string()),
            "FMS_RGW_TLS_KEY" => Some("certs/e.key".to_string()),
            "FMS_RGW_LOG_FILE" => Some("logs/env.jsonl".to_string()),
            "FMS_RGW_LOG_TOKEN" => Some("env-int-token".to_string()),
            "FMS_RGW_LOG_TIMEOUT" => Some("7".to_string()),
            "FMS_RGW_TRACE_SAMPLE_RATE" => Some("100".to_string()),
            "FMS_RGW_RATELIMIT_SKIP_PATHS" => Some("/healthz, /favicon".to_string()),
            _ => None,
        });
        assert_eq!(cfg.server.listen, "0.0.0.0:9090");
        assert_eq!(cfg.upstream.base_url, "http://10.0.0.2:9000");
        assert_eq!(
            cfg.upstream.mode, "least-conn",
            "FMS_RGW_UPSTREAM_MODE 覆盖 mode"
        );
        assert_eq!(
            cfg.upstream.servers,
            vec!["http://10.0.0.2:9000", "http://10.0.0.3:9000"],
            "FMS_RGW_UPSTREAM_SERVERS 逗号分隔覆盖 servers(去空白)"
        );
        assert_eq!(cfg.auth.jwt_secret, "env-jwt");
        assert_eq!(cfg.sign.sign_secret, "env-sign");
        assert_eq!(cfg.static_cfg.dir, "./www");
        assert_eq!(cfg.tls.cert.as_deref(), Some("certs/e.crt"));
        assert_eq!(cfg.tls.key.as_deref(), Some("certs/e.key"));
        assert_eq!(cfg.log.access_file.as_deref(), Some("logs/env.jsonl"));
        assert_eq!(
            cfg.log.pgmq.token, "env-int-token",
            "FMS_RGW_LOG_TOKEN 注入 pgmq.token"
        );
        assert_eq!(
            cfg.log.pgmq.timeout_secs, 7,
            "FMS_RGW_LOG_TIMEOUT 覆盖投递超时"
        );
        assert_eq!(
            cfg.trace.sample_rate, 100,
            "FMS_RGW_TRACE_SAMPLE_RATE 覆盖采样率"
        );
        assert_eq!(
            cfg.limits.skip_paths,
            vec!["/healthz", "/favicon"],
            "FMS_RGW_RATELIMIT_SKIP_PATHS 逗号分隔覆盖默认列表(去空白)"
        );
        // 未覆盖字段保持默认
        assert_eq!(cfg.upstream.request_timeout_secs, 30);
        assert_eq!(cfg.limits.ip_per_window, 100);
    }

    #[test]
    fn apply_env_overrides_ignores_empty_and_missing() {
        let mut cfg = Config::default();
        // 全部环境变量为空字符串则全部保持默认，缺失则保持默认
        apply_env_overrides_with(&mut cfg, &|_| Some(String::new()));
        assert_eq!(cfg.server.listen, "0.0.0.0:8080", "空值不得覆盖");
        assert_eq!(cfg.upstream.base_url, "http://127.0.0.1:8081");
        assert_eq!(cfg.upstream.mode, "round-robin", "空 MODE 不得覆盖");
        assert!(cfg.upstream.servers.is_empty(), "空 SERVERS 不得覆盖");
        assert_eq!(cfg.tls.cert, None);
        assert_eq!(cfg.log.access_file, None);
        apply_env_overrides_with(&mut cfg, &|_| None);
        assert_eq!(cfg.server.listen, "0.0.0.0:8080", "缺失变量不得覆盖");
    }

    #[test]
    fn load_from_parses_full_yaml() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-cfg-full-{}.yaml", std::process::id()));
        std::fs::write(
            &path,
            "server:\n  listen: \"0.0.0.0:8080\"\nupstream:\n  base_url: \"http://127.0.0.1:8081\"\n  request_timeout_secs: 30\n  idle_timeout_secs: 60\n  health_path: \"/internal/health\"\n  health_interval_secs: 5\n  health_timeout_secs: 2\nlimits:\n  ip_per_window: 100\n  user_per_window: 200\n  login_per_window: 10\n  shards: 64\n  max_keys: 100000\ncircuit:\n  error_threshold_pct: 50\n  sample_window: 100\n  min_samples: 20\n  open_timeout_secs: 30\n  half_open_probes: 5\n",
        )
        .expect("测试配置写入");
        let cfg = load_from(&path).expect("全量配置解析");
        let _ = std::fs::remove_file(&path);
        assert_eq!(cfg.upstream.health_path, "/internal/health");
        assert_eq!(cfg.limits.max_keys, 100_000);
        assert_eq!(cfg.circuit.half_open_probes, 5);
    }

    #[test]
    fn load_from_parses_log_pgmq_section() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-cfg-logpgmq-{}.yaml", std::process::id()));
        std::fs::write(
            &path,
            "log:\n  access_file: \"logs/access.jsonl\"\n  sample_rate: 10\n  batch_size: 200\n  batch_interval_ms: 1000\n  channel_capacity: 5000\n  path_max_len: 2048\n  ua_max_len: 512\n  pgmq:\n    enabled: true\n    endpoint: \"/internal/log-batch\"\n    token: \"t0k3n\"\n    timeout_secs: 10\n",
        )
        .expect("测试配置写入");
        let cfg = load_from(&path).expect("log 段解析成功");
        let _ = std::fs::remove_file(&path);
        assert_eq!(cfg.log.sample_rate, 10);
        assert_eq!(cfg.log.batch_size, 200);
        assert_eq!(cfg.log.batch_interval_ms, 1000);
        assert_eq!(cfg.log.channel_capacity, 5000);
        assert_eq!(cfg.log.path_max_len, 2048);
        assert_eq!(cfg.log.ua_max_len, 512);
        assert!(cfg.log.pgmq.enabled);
        assert_eq!(cfg.log.pgmq.endpoint, "/internal/log-batch");
        assert_eq!(cfg.log.pgmq.token, "t0k3n");
        assert_eq!(cfg.log.pgmq.timeout_secs, 10);
    }

    #[test]
    fn config_error_display_mentions_stage() {
        let msg = format!(
            "{}",
            ConfigError::Io(io::Error::new(io::ErrorKind::NotFound, "x"))
        );
        assert!(msg.contains("配置读取失败"), "IO 错误显示应说明阶段");
        let parse = format!(
            "{}",
            ConfigError::Parse(noyalib::compat::serde_yaml::from_str::<Config>("a: [unclosed").unwrap_err())
        );
        assert!(parse.contains("配置解析失败"), "解析错误显示应说明阶段");
    }
}
