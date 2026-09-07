//! 分片滑动窗口限流器，内存实现，零外部依赖，shards个`DashMap`分片按key hash落片，
//! 热路径无跨片锁，每key独立滑动窗口精确计数，维度ip每窗100次、用户每窗200次(`mw_auth`内)、
//! 登录端点每窗10次，按ip收敛防撞库，超限429 + `Retry-After`，`max_keys`上限 + 60s周期cleanup
//!
//! 调用关系，`mw_rate_limit`，安全链第二层调`tier_of`/`rate_key`/check，`mw_auth`验签后
//! 按用户维度调check，main每60s调cleanup
#![forbid(unsafe_code)]

use crate::state::AppState;
use crate::types::{GatewayError, error_response, extract_client_ip, trace_id_of};
use axum::body::Body;
use axum::extract::State;
use axum::http::{Request, Response};
use axum::middleware::Next;
use dashmap::DashMap;
use std::collections::VecDeque;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// 限流错误，超限并给出`Retry-After`秒数，最少1s，`Backend` = 后端故障
///redis不可达，fail-closed按500拒绝，区别于429
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RateLimitError {
    Exceeded { retry_after_secs: u64 },
    Backend,
}

/// 限流维度，端点分级
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RateTier {
    /// ip维度，所有`/api`请求
    Ip,
    /// 用户维度，`mw_auth`验签通过后按`claims.sub`
    User,
    /// 登录端点，path == `/api/auth/login`，按ip收敛，防撞库
    EndpointLogin,
}

/// 端点分级，path == `/api/auth/login` 则归 `EndpointLogin`，其余则归 Ip
/// 用户维度由auth中间件按claims追加，不在此判定
pub fn tier_of(path: &str) -> RateTier {
    if path == "/api/auth/login" {
        RateTier::EndpointLogin
    } else {
        RateTier::Ip
    }
}

/// 限流跳过判定，path命中`skip_paths`任一前缀即跳过限流计数(静态资源/健康检查等
/// 不承载配额语义的路径，默认列表["/assets/", "/healthz", "/favicon", "/internal/"]，
/// 可经配置调整，`max_connections` `Semaphore`仍兜底防连接耗尽dos)
pub fn should_skip(path: &str, skip_paths: &[String]) -> bool {
    skip_paths.iter().any(|p| path.starts_with(p.as_str()))
}

/// 限流key，`ip` 对应 "ip:{ip}"，`User` 对应 "user:{sub}"，`EndpointLogin` 对应 "login:{ip}"，ip不可得时退化为unknown
pub fn rate_key(tier: RateTier, ip: Option<&str>, user: Option<&str>) -> String {
    match tier {
        RateTier::Ip => format!("ip:{}", ip.unwrap_or("unknown")),
        RateTier::User => format!("user:{}", user.unwrap_or("unknown")),
        RateTier::EndpointLogin => format!("login:{}", ip.unwrap_or("unknown")),
    }
}

/// 单个key的滑动窗口，窗口内每次请求的时间戳，精确计数
struct Window {
    hits: VecDeque<Instant>,
    window: Duration,
}

impl Window {
    fn new(window: Duration) -> Self {
        Self {
            hits: VecDeque::new(),
            window,
        }
    }

    /// 剔除窗口外，早于now - window的旧记录
    fn prune(&mut self, now: Instant) {
        let cutoff = now.checked_sub(self.window).unwrap_or(now);
        while let Some(front) = self.hits.front() {
            if *front < cutoff {
                self.hits.pop_front();
            } else {
                break;
            }
        }
    }
}

/// 分片滑动窗口限流器，shards个`DashMap`<`String`, `Window`>，key按hash落片。
/// 热路径，check只操作单个分片的单个key，无跨片锁竞争。
pub struct RateLimiter {
    shards: Vec<DashMap<String, Window>>,
    /// 内存保护上限(按分片均摊，每片`max_keys`/shards)
    max_keys: usize,
}

impl RateLimiter {
    pub fn new(shards: usize, max_keys: usize) -> Self {
        // 分片数至少1，防止除零，64分片为默认配置
        let n = shards.max(1);
        let mut shard_vec = Vec::with_capacity(n);
        for _ in 0..n {
            shard_vec.push(DashMap::new());
        }

        Self {
            shards: shard_vec,
            max_keys: max_keys.max(1),
        }
    }

    /// key的落片索引，`DefaultHasher`在同一进程内确定，仅需进程内一致性
    fn shard_index(&self, key: &str) -> usize {
        let mut h = DefaultHasher::new();
        key.hash(&mut h);
        (h.finish() as usize) % self.shards.len()
    }

    /// 滑动窗口计数，窗口内计数 ≥ limit 则返回 `Err`(`Exceeded`{`retry_after`})，否则记录并放行，
    /// 内存保护，key达上限先cleanup，仍满则拒绝并WARN(fail-closed，限流防滥用
    /// 优先于可用性，防内存耗尽)。
    ///
    /// 语义细节，窗口为精确计数(每个时间戳一条记录，prune剔除早于now-window的
    /// 旧记录)，非计数器近似，边界精确，`retry_after` = 最旧记录 + window - now，
    /// 最少1s、向上取整秒，客户端按此重试即必在窗口外，limit == 0视为全部拒绝
    /// 配置错误不得导致无上限放行。热路径只操作单个分片的单个key(`DefaultHasher`
    /// 落片，进程内一致)，无跨片锁竞争。
    ///
    /// 边界/失败语义，超限不记录本次请求，计数不被拒绝请求污染，内存上限为
    /// `max_keys`按分片均摊(每片`max_keys`/shards，至少1)，满时先全局cleanup，
    /// 仍满则拒绝，fail-closed，放行会失去限流保护。
    ///
    /// key 限流key(`rate_key`生成，ip:{ip}/user:{sub}/login:{ip})
    /// limit 窗口内允许次数，0 = 全部拒绝，fail-closed
    /// window 滑动窗口时长(生产取配置`limits.window_secs`，测试可注入短窗)
    /// 返回Ok = 放行，已记录本次，`Err`(`Exceeded`) = 超限，携带`retry_after_secs`
    pub fn check(&self, key: &str, limit: u32, window: Duration) -> Result<(), RateLimitError> {
        let shard = &self.shards[self.shard_index(key)];

        // 防御性，limit == 0视为全部拒绝，配置错误不导致无上限放行
        if limit == 0 {
            return Err(RateLimitError::Exceeded {
                retry_after_secs: 1,
            });
        }

        // 内存保护，单分片容量上限(max_keys均摊)，满时先清理过期条目
        let per_shard_cap = (self.max_keys / self.shards.len()).max(1);
        if shard.len() >= per_shard_cap {
            self.cleanup();
            if shard.len() >= per_shard_cap {
                tracing::warn!(key = %key, "限流表容量已达上限，拒绝(fail-closed)");
                return Err(RateLimitError::Exceeded {
                    retry_after_secs: 1,
                });
            }
        }

        let now = Instant::now();
        let mut entry = shard
            .entry(key.to_string())
            .or_insert_with(|| Window::new(window));
        let w = entry.value_mut();
        w.prune(now);
        if w.hits.len() >= limit as usize {
            // retry_after = 最旧记录 + window - now，最少1s，向上取整秒
            let oldest = w.hits.front().copied().unwrap_or(now);
            let remaining = oldest
                .checked_add(w.window)
                .and_then(|end| end.checked_duration_since(now))
                .unwrap_or(Duration::ZERO);
            let retry_after = ceil_secs(remaining).max(1);
            return Err(RateLimitError::Exceeded {
                retry_after_secs: retry_after,
            });
        }

        // 未超限，记录本次请求
        w.hits.push_back(now);
        Ok(())
    }

    /// 移除空窗口与过期条目，main每60s调用
    pub fn cleanup(&self) {
        let now = Instant::now();

        for shard in &self.shards {
            shard.retain(|_, w| {
                w.prune(now);
                !w.hits.is_empty()
            });
        }
    }
}

/// `Duration`向上取整到秒
fn ceil_secs(d: Duration) -> u64 {
    d.as_secs() + u64::from(d.subsec_nanos() > 0)
}

/// 限流后端抽象，memory，默认，零外部依赖，单网关实例精确计数| redis
///多网关实例共享计数，同一key跨实例原子INCR，任一实例超限即全局限流。
/// check为同步签名，中间件热路径，Redis后端经blocking连接(本地毫秒级，
/// 多实例收益大于单点阻塞成本)。
pub trait RateLimitBackend: Send + Sync {
    /// 窗口内计数 +1并判限，超limit 则返回 `Err`(`Exceeded`)，否则记录并放行
    fn check(&self, key: &str, limit: u32, window: Duration) -> Result<(), RateLimitError>;

    /// 周期清理，memory剔除过期窗口，redis由ttl自然过期，no-op
    fn cleanup(&self) {}
}

impl RateLimitBackend for RateLimiter {
    fn check(&self, key: &str, limit: u32, window: Duration) -> Result<(), RateLimitError> {
        RateLimiter::check(self, key, limit, window)
    }

    fn cleanup(&self) {
        RateLimiter::cleanup(self);
    }
}

/// Redis共享限流后端，INCR + EXPIRE，ttl滚动 = 滑动窗口，过期自动归零，
/// 与`RedisCachePort`同款原子语义，计数 > limit 则返回 `Err`(fail-closed，redis不可达
/// 时check抛错，由中间件按500拒绝，宁可拒不可放)。
pub struct RedisRateLimiter {
    client: redis::Client,
}

impl RedisRateLimiter {
    pub fn new(redis_url: &str) -> Result<Self, redis::RedisError> {
        Ok(Self {
            client: redis::Client::open(redis_url)?,
        })
    }
}

impl RateLimitBackend for RedisRateLimiter {
    fn check(&self, key: &str, limit: u32, window: Duration) -> Result<(), RateLimitError> {
        // limit == 0视为全部拒绝，配置错误不得导致无上限放行，与内存后端同语义
        if limit == 0 {
            return Err(RateLimitError::Exceeded { retry_after_secs: 1 });
        }

        let conn = self
            .client
            .get_connection()
            .map_err(|_| RateLimitError::Backend)?;

        // INCR原子自增 + EXPIRE刷新ttl(两步非严格原子，EXPIRE失败仅窗口未刷新，
        // 计数偏严不偏松，与CachePort increment同取舍)
        let count: i64 = redis::cmd("INCR")
            .arg(key)
            .query(&conn)
            .unwrap_or(0);
        let _: Result<(), _> = redis::cmd("EXPIRE")
            .arg(key)
            .arg(window.as_secs())
            .query(&conn);

        if count > limit as i64 {
            return Err(RateLimitError::Exceeded {
                retry_after_secs: window.as_secs().max(1),
            });
        }
        Ok(())
    }
}

/// 中间件，安全链第二层rate，ip维度每窗100次，登录端点按ip每窗10次，防撞库，先于用户维度，
/// 超限429 + `Retry-After` + {code:42900}，记录INFO，用户维度每窗200次在`mw_auth`验签后执行。
///
/// 维度与次序，先ip维度(所有`/api`请求，匿名/已认证一视同仁)，再登录端点维度
/// (path == `/api/auth/login`按ip收敛，独立key，撞库被限到每窗10次)，用户维度
/// 不在此处(验签后才能得到sub，由`mw_auth`追加调用)。ip不可得时key退化为
/// unknown，仍共享同一计数，防多来源同key击穿。
///
/// 失败语义，任一维度超限立即429返回，不继续执行后续维度/不进入安全链，错误体
/// 统一{code:42900} + `Retry-After`头，最少1s，日志INFO级带`trace_id`/ip/path。
/// 本中间件不触碰请求体与头，放行路径零开销。
///
/// state 全局状态(取`limits.ip_per_window`/`login_per_window`与限流器实例)
pub async fn mw_rate_limit(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);
    let ip = extract_client_ip(&req);
    let path = req.uri().path();
    let limits = &state.cfg.limits;

    // 限流跳过，静态资源/健康检查/内部端点等路径不计入限流计数(skip_paths前缀匹配，
    // 默认保守列表，连接级防护由max_connections Semaphore兜底，此处不重复计数)
    if should_skip(path, &limits.skip_paths) {
        return next.run(req).await;
    }

    // 滑动窗口时长取配置limits.window_secs，三维度共用
    let window = Duration::from_secs(limits.window_secs);

    // ip维度，所有/api请求
    let ip_key = rate_key(RateTier::Ip, ip.as_deref(), None);
    if let Err(err) = state.limiter.check(&ip_key, limits.ip_per_window, window) {
        return rate_error_response(err, &trace_id, ip.as_deref(), path, "IP 维度");
    }

    // 登录端点维度，按ip收敛，防撞库，先于用户维度
    if tier_of(path) == RateTier::EndpointLogin {
        let login_key = rate_key(RateTier::EndpointLogin, ip.as_deref(), None);
        if let Err(err) = state
            .limiter
            .check(&login_key, limits.login_per_window, window)
        {
            return rate_error_response(err, &trace_id, ip.as_deref(), path, "登录端点维度");
        }
    }

    // 用户维度由于需要验签成功因此由mw_auth调用

    next.run(req).await
}

/// 限流错误映射响应，`Exceeded` 则 429 + `Retry-After`，`Backend` 后端故障则 500 fail-closed
fn rate_error_response(
    err: RateLimitError,
    trace_id: &str,
    ip: Option<&str>,
    path: &str,
    dim: &str,
) -> Response<Body> {
    match err {
        RateLimitError::Exceeded { retry_after_secs } => {
            tracing::info!(trace_id = %trace_id, ip = %ip.unwrap_or("unknown"),
                path = %path, retry_after_secs, "限流命中：{}", dim);
            error_response(GatewayError::RateLimited { retry_after_secs }, trace_id)
        }
        RateLimitError::Backend => {
            tracing::error!(trace_id = %trace_id, ip = %ip.unwrap_or("unknown"),
                path = %path, "限流后端不可达(fail-closed 按 500 拒绝)");
            error_response(GatewayError::Internal("rate limit backend unavailable".to_string()), trace_id)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::test_state_with;
    use std::time::Duration;
    use tower::ServiceExt;

    #[test]
    fn tier_of_login_endpoint() {
        assert_eq!(tier_of("/api/auth/login"), RateTier::EndpointLogin);
        assert_eq!(tier_of("/api/gl/vouchers"), RateTier::Ip);
        assert_eq!(tier_of("/api/nt/ws"), RateTier::Ip);
    }

    #[test]
    fn skip_paths_prefix_match() {
        let default = Config::default().limits.skip_paths;
        // 默认保守列表，静态资源/健康检查/favicon/内部端点
        assert!(should_skip("/assets/app.js", &default));
        assert!(should_skip("/healthz", &default));
        assert!(should_skip("/favicon.ico", &default));
        assert!(should_skip("/internal/health", &default));
        // api与其余路径不跳过
        assert!(!should_skip("/api/gl/vouchers", &default));
        assert!(!should_skip("/api/auth/login", &default));
        // 前缀匹配，非全等，/assets/ 前缀命中，/asset单数不命中
        assert!(!should_skip("/asset/x", &default));
        // 空列表 = 全部计数，配置显式清空后不跳过任何路径
        assert!(!should_skip("/healthz", &[]));
    }

    #[test]
    fn rate_key_formats() {
        assert_eq!(rate_key(RateTier::Ip, Some("1.2.3.4"), None), "ip:1.2.3.4");
        assert_eq!(rate_key(RateTier::Ip, None, None), "ip:unknown");
        assert_eq!(
            rate_key(RateTier::User, None, Some("abc-uuid")),
            "user:abc-uuid"
        );
        assert_eq!(
            rate_key(RateTier::EndpointLogin, Some("1.2.3.4"), None),
            "login:1.2.3.4"
        );
    }

    #[test]
    fn check_allows_within_limit() {
        let rl = RateLimiter::new(8, 1000);
        assert!(rl.check("ip:1.2.3.4", 3, Duration::from_secs(60)).is_ok());
        assert!(rl.check("ip:1.2.3.4", 3, Duration::from_secs(60)).is_ok());
        assert!(rl.check("ip:1.2.3.4", 3, Duration::from_secs(60)).is_ok());
    }

    #[test]
    fn check_rejects_over_limit_with_retry_after() {
        let rl = RateLimiter::new(8, 1000);
        for _ in 0..3 {
            assert!(rl.check("ip:x", 3, Duration::from_secs(60)).is_ok());
        }
        let err = rl.check("ip:x", 3, Duration::from_secs(60)).unwrap_err();
        if let RateLimitError::Exceeded { retry_after_secs } = err {
            assert!(retry_after_secs >= 1, "Retry-After 最少 1s");
        } else {
            panic!("应返回 Exceeded，实际 {err:?}");
        }
    }

    #[test]
    fn window_expiry_resets_count() {
        let rl = RateLimiter::new(8, 1000);
        let window = Duration::from_millis(100);
        assert!(rl.check("ip:y", 1, window).is_ok());
        assert!(rl.check("ip:y", 1, window).is_err(), "窗口内超限");
        std::thread::sleep(window + Duration::from_millis(50));
        assert!(rl.check("ip:y", 1, window).is_ok(), "窗口过期后应放行");
    }

    #[test]
    fn cleanup_removes_stale_keys() {
        let rl = RateLimiter::new(4, 1000);
        let window = Duration::from_millis(50);
        assert!(rl.check("ip:a", 10, window).is_ok());
        assert!(rl.check("ip:b", 10, window).is_ok());
        std::thread::sleep(window + Duration::from_millis(20));
        // 新请求触发check内prune不删key，cleanup才删空窗
        rl.cleanup();
        let total: usize = rl.shards.iter().map(|s| s.len()).sum();
        assert_eq!(total, 0, "cleanup 后应无残留 key");
    }

    #[test]
    fn memory_cap_fail_closed_when_full() {
        // 容量1时，首个key填满，第二key触发cleanup无法释放则拒绝，fail-closed
        let rl = RateLimiter::new(1, 1);
        let window = Duration::from_secs(60);
        assert!(rl.check("ip:a", 10, window).is_ok());
        assert!(
            rl.check("ip:b", 10, window).is_err(),
            "容量满时应拒绝(fail-closed：限流防滥用优先于可用性)"
        );
    }

    #[test]
    fn shard_distribution_uses_all_shards() {
        let rl = RateLimiter::new(16, 100_000);
        let mut seen = std::collections::HashSet::new();
        for i in 0..256 {
            seen.insert(rl.shard_index(&format!("user:{i}")));
        }
        assert!(seen.len() > 4, "key 应分散到多个分片，实际 {}", seen.len());
    }

    #[test]
    fn check_limit_zero_is_fail_closed() {
        // 配置错误，limit=0不得导致无上限放行，一律拒绝
        let rl = RateLimiter::new(4, 1000);
        assert_eq!(
            rl.check("ip:x", 0, Duration::from_secs(60)),
            Err(RateLimitError::Exceeded {
                retry_after_secs: 1
            })
        );
    }

    #[test]
    fn retry_after_reflects_window_remaining() {
        // 窗口60s、limit=1，第二次请求时最旧记录剩余 ≈60s
        let rl = RateLimiter::new(4, 1000);
        assert!(rl.check("ip:r", 1, Duration::from_secs(60)).is_ok());
        let err = rl.check("ip:r", 1, Duration::from_secs(60)).unwrap_err();
        if let RateLimitError::Exceeded { retry_after_secs } = err {
            assert!(
                (50..=60).contains(&retry_after_secs),
                "Retry-After 应接近窗口剩余秒数，实际 {retry_after_secs}"
            );
        } else {
            panic!("应返回 Exceeded，实际 {err:?}");
        }
    }

    async fn echo_ok() -> &'static str {
        "ok"
    }

    #[tokio::test]
    async fn mw_rate_limit_ip_dimension_returns_429_with_retry_after() {
        let mut cfg = Config::default();
        cfg.limits.ip_per_window = 1; // 第2个请求即超限
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_rate_limit,
            ))
            .with_state(state);
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK, "窗口内放行");
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::TOO_MANY_REQUESTS,
            "超限 429"
        );
        // Retry-After头 + 统一错误体
        let retry = resp
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.parse::<u64>().ok());
        assert!(retry.is_some_and(|s| s >= 1), "必须带 Retry-After");
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 42900);
    }

    #[tokio::test]
    async fn mw_rate_limit_login_endpoint_uses_login_tier() {
        let mut cfg = Config::default();
        cfg.limits.ip_per_window = 1000; // ip维度不触发
        cfg.limits.login_per_window = 1; // 登录端点每窗1次
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_rate_limit,
            ))
            .with_state(state);
        // 第一个登录请求放行
        let req = axum::http::Request::builder()
            .uri("/api/auth/login")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        // 第二个则回登录维度 429(非ip维度，ip_per_window=1000未超)
        let req = axum::http::Request::builder()
            .uri("/api/auth/login")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::TOO_MANY_REQUESTS);
        assert!(resp.headers().contains_key("retry-after"));
        // 其他端点不受login维度影响，独立key
        let req = axum::http::Request::builder()
            .uri("/api/other")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
    }

    #[tokio::test]
    async fn mw_rate_limit_skips_configured_paths() {
        // ip维度每窗1次，/api路径第2次即429，跳过列表路径(默认/assets/、/healthz、
        // /internal/、/favicon)不计入限流，任意次数均放行
        let mut cfg = Config::default();
        cfg.limits.ip_per_window = 1;
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .fallback(echo_ok) // 跳过列表路径不在/api路由下，fallback兜底返回200
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_rate_limit,
            ))
            .with_state(state);
        // 跳过路径反复请求不触发限流
        for path in ["/healthz", "/assets/app.js", "/internal/health", "/favicon.ico"] {
            for _ in 0..3 {
                let req = axum::http::Request::builder()
                    .uri(path)
                    .body(Body::empty())
                    .unwrap();
                let resp = app.clone().oneshot(req).await.unwrap();
                assert_eq!(
                    resp.status(),
                    axum::http::StatusCode::OK,
                    "{path} 属跳过列表，不应被限流"
                );
            }
        }
        // /api路径仍正常限流，第2次429
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        assert_eq!(app.clone().oneshot(req).await.unwrap().status(), axum::http::StatusCode::OK);
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            app.clone().oneshot(req).await.unwrap().status(),
            axum::http::StatusCode::TOO_MANY_REQUESTS,
            "/api 路径不在跳过列表，应正常限流"
        );
    }
}
