//! `AppState`装配根，main构建一次`Arc`共享，全部只读后通过锁/原子/`DashMap`提供内部可变性，
//! 另提供测试构造`test_state` / `test_state_with`供各模块单测复用。
//! `types.rs`保留纯叶子，本模块为装配根(其余模块经本模块反向依赖`AppState`，无环)
#![forbid(unsafe_code)]

use crate::circuit::CircuitBreaker;
use crate::config::Config;
#[cfg(test)]
use crate::config::CircuitCfg;
use crate::lb::UpstreamPool;
use crate::log::AccessLogger;
use crate::ratelimit::RateLimitBackend;
#[cfg(test)]
use crate::ratelimit::RateLimiter;
use crate::types::now_secs;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::Mutex;

/// 登出黑名单远端存储(多网关实例共享撤销，`auth.deny_store`=memory时为`None`)。
/// 与后端refresh撤销黑名单同键前缀，fms:blacklist:，键随ttl自动过期。
pub trait DenyRemote: Send + Sync {
    /// 写入黑名单(ttl秒，失败返回`Err`，调用方按尽力而为处理，不阻断登出主流程)
    fn set(&self, key: &str, expire_secs: i64) -> Result<(), String>;
    /// 查询黑名单，存在返回true
    fn get(&self, key: &str) -> Result<bool, String>;
}

/// Redis登出黑名单共享实现，SET key 1 EX ttl / GET key，任意网关实例写入、
/// 全部实例可见，ttl与进程内到期语义对齐，令牌剩余寿命
pub struct RedisDenyRemote {
    client: redis::Client,
}

impl RedisDenyRemote {
    pub fn new(redis_url: &str) -> Result<Self, redis::RedisError> {
        Ok(Self {
            client: redis::Client::open(redis_url)?,
        })
    }
}

impl DenyRemote for RedisDenyRemote {
    fn set(&self, key: &str, expire_secs: i64) -> Result<(), String> {
        let conn = self.client.get_connection().map_err(|e| e.to_string())?;
        redis::cmd("SET")
            .arg(key)
            .arg("1")
            .arg("EX")
            .arg(expire_secs.max(1))
            .query(&conn)
            .map_err(|e| e.to_string())
    }

    fn get(&self, key: &str) -> Result<bool, String> {
        let conn = self.client.get_connection().map_err(|e| e.to_string())?;
        let v: Option<String> = redis::cmd("GET")
            .arg(key)
            .query(&conn)
            .map_err(|e| e.to_string())?;
        Ok(v.is_some())
    }
}

/// 登出令牌黑名单，网关侧，token 原文映射到期 unix 秒。
///
/// jwt验签是无状态的(`auth.rs` `verify_jwt`仅查签名/exp)，登出后access token
/// 在剩余ttl内天然仍可验签通过，本表为登出撤销提供兜底，登出请求经网关时把
/// access/refresh原值写入，后续携带同token的请求在验签前命中即拒绝，40101，
/// 到期条目由`lib.rs` `periodic_cleanup`每60s清扫。
///
/// 多网关共享(`auth.deny_store`=redis)，revoke双写，进程内表 + Redis远端，
/// `is_revoked`先查进程内再查远端，任意实例登出全实例生效。故障语义，
/// Redis不可达时撤销/查询为尽力而为(进程内表兜底本实例 + WARN，不阻断登出
/// 主流程，refresh撤销权威在后端Redis黑名单)，与后端`CachePort` fail-closed
/// 语义不同，网关侧撤销是ttl内的尽力失效层。
///
/// 容量语义，条目ttl = 令牌自身exp，access默认2h量级，表大小与ttl内
/// 登出令牌数成正比，上限有限，进程重启即清空(与后端内存黑名单同语义，
/// 登出后服务重启的残余窗口可接受，令牌ttl本身有限)。
pub struct TokenDenyList {
    inner: Mutex<HashMap<String, i64>>,
    /// 多网关共享远端(memory模式`None`)
    remote: Option<Arc<dyn DenyRemote>>,
}

impl Default for TokenDenyList {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenDenyList {
    /// 进程内黑名单(`auth.deny_store`=memory默认)
    pub fn new() -> Self {
        Self::with_remote(None)
    }

    /// 带共享远端构造(redis模式，remote内层必须`Arc`<dyn `DenyRemote`>)
    pub fn with_remote(remote: Option<Arc<dyn DenyRemote>>) -> Self {
        Self {
            inner: Mutex::new(HashMap::new()),
            remote,
        }
    }

    /// 注销token，到期unix秒，空串忽略，进程内表 + 共享远端双写
    pub async fn revoke(&self, token: &str, expire_at_secs: i64) {
        if token.is_empty() {
            return;
        }

        self.inner
            .lock()
            .await
            .insert(token.to_string(), expire_at_secs);

        if let Some(r) = &self.remote
            && let Err(e) = r.set(&deny_key(token), expire_at_secs - now_secs())
        {
            // 尽力而为，本实例进程内已生效，远端失败仅WARN，多实例撤销窗口
            // 由后端refresh黑名单，Redis权威兜底
            tracing::warn!(token_prefix = %&token[..token.len().min(8)], "登出撤销 Redis 写入失败(尽力而为): {e}");
        }
    }

    /// 是否已注销，未过期，进程内命中即拒绝，未命中且配置共享远端则查 Redis
    /// 他实例登出的token全实例生效，过期条目顺带惰性清理
    pub async fn is_revoked(&self, token: &str) -> bool {
        {
            let mut m = self.inner.lock().await;
            match m.get(token) {
                Some(&exp) if exp > now_secs() => return true,
                Some(_) => {
                    m.remove(token);
                }
                None => {}
            }
        }

        if let Some(r) = &self.remote {
            match r.get(&deny_key(token)) {
                Ok(true) => return true,
                Ok(false) => {}
                Err(e) => {
                    // Redis不可达，回退本实例进程内表，尽力而为，不误伤正常请求
                    tracing::warn!(token_prefix = %&token[..token.len().min(8)], "登出撤销 Redis 查询失败(回退进程内表): {e}");
                }
            }
        }
        false
    }

    /// 清扫全部过期条目(`periodic_cleanup`每60s调用，远端由ttl自动过期)
    pub async fn cleanup(&self) {
        let now = now_secs();
        self.inner.lock().await.retain(|_, exp| *exp > now);
    }
}

/// 远端黑名单键，与后端fms:blacklist:同前缀，refresh撤销两侧同键，无冲突且互补
fn deny_key(token: &str) -> String {
    format!("fms:blacklist:{token}")
}

/// 全局状态，main构建一次，`Arc`共享
pub struct AppState {
    pub cfg: Arc<Config>,
    /// 限流后端，memory | redis共享计数，按配置装配
    pub limiter: Arc<dyn RateLimitBackend>,
    pub circuit: Arc<CircuitBreaker>,
    /// 多上游负载均衡池，servers空 = 单上游，等价vec![base_url]
    pub upstream: Arc<UpstreamPool>,
    pub logger: Arc<AccessLogger>,
    pub deny: Arc<TokenDenyList>,
    pub started_at: Instant,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// 测试用共享远端，内存map模拟Redis键表
    struct FakeRemote {
        revoked: Mutex<HashMap<String, bool>>,
    }

    impl DenyRemote for FakeRemote {
        fn set(&self, key: &str, _expire_secs: i64) -> Result<(), String> {
            self.revoked.lock().unwrap().insert(key.to_string(), true);
            Ok(())
        }

        fn get(&self, key: &str) -> Result<bool, String> {
            Ok(self.revoked.lock().unwrap().contains_key(key))
        }
    }

    /// 恒失败远端，模拟Redis不可达
    struct FailingRemote;

    impl DenyRemote for FailingRemote {
        fn set(&self, _key: &str, _expire_secs: i64) -> Result<(), String> {
            Err("redis down".to_string())
        }

        fn get(&self, _key: &str) -> Result<bool, String> {
            Err("redis down".to_string())
        }
    }

    #[tokio::test]
    async fn revoke_writes_local_and_remote() {
        let remote = Arc::new(FakeRemote { revoked: Mutex::new(HashMap::new()) });
        let deny = TokenDenyList::with_remote(Some(remote.clone()));
        deny.revoke("tok-1", now_secs() + 3600).await;
        assert!(deny.is_revoked("tok-1").await, "本地表应命中");
        // 远端同步写入，键前缀与后端黑名单一致，模拟另一实例可查
        assert!(remote.revoked.lock().unwrap().contains_key("fms:blacklist:tok-1"));
    }

    #[tokio::test]
    async fn remote_revoked_token_visible_across_instances() {
        // 本实例无本地记录，但远端已有，他实例登出写入则 is_revoked 命中
        let remote = Arc::new(FakeRemote {
            revoked: Mutex::new(HashMap::from([("fms:blacklist:tok-9".to_string(), true)])),
        });
        let deny = TokenDenyList::with_remote(Some(remote));
        assert!(deny.is_revoked("tok-9").await, "跨实例撤销应全实例生效");
        assert!(!deny.is_revoked("tok-8").await, "远端无记录应放行");
    }

    #[tokio::test]
    async fn failing_remote_falls_back_to_local() {
        let deny = TokenDenyList::with_remote(Some(Arc::new(FailingRemote)));
        deny.revoke("tok-2", now_secs() + 3600).await; // 尽力而为不panic
        assert!(deny.is_revoked("tok-2").await, "远端写失败后本地表仍生效");
        assert!(!deny.is_revoked("tok-unknown").await, "远端查失败回退放行(尽力而为)");
    }

    #[tokio::test]
    async fn memory_mode_local_only_behavior() {
        let deny = TokenDenyList::new();
        deny.revoke("tok-3", now_secs() + 3600).await;
        assert!(deny.is_revoked("tok-3").await);
        assert!(!deny.is_revoked("tok-4").await);
    }

    #[tokio::test]
    async fn expired_entry_lazily_cleaned_and_empty_token_ignored() {
        let deny = TokenDenyList::new();
        deny.revoke("tok-exp", now_secs() - 10).await;
        assert!(!deny.is_revoked("tok-exp").await, "过期条目视为未撤销并惰性清理");
        deny.revoke("", now_secs() + 3600).await;
        assert!(!deny.is_revoked("").await, "空 token 忽略");
    }
}

/// 测试用`AppState`构造，cfg，test 仅测试构建可见，默认密钥非空 + cors白名单非空，
/// 避免`build_router`启动期panic
#[cfg(test)]
pub(crate) fn test_state_with(cfg: Config) -> Arc<AppState> {
    // 池构造，servers默认空则等价 vec![base_url]，单上游
    let upstream = UpstreamPool::new(&cfg.upstream).expect("测试上游构造：默认 base_url 合法");
    let logger = AccessLogger::new(&cfg.log, &cfg.upstream.base_url).expect("仅 stdout 日志");
    Arc::new(AppState {
        cfg: Arc::new(cfg),
        limiter: Arc::new(RateLimiter::new(4, 10_000)) as Arc<dyn RateLimitBackend>,
        circuit: Arc::new(CircuitBreaker::new(CircuitCfg::default())),
        upstream: Arc::new(upstream),
        logger: Arc::new(logger),
        deny: Arc::new(TokenDenyList::default()),
        started_at: Instant::now(),
    })
}

/// 默认测试状态，jwt/sign密钥非空、cors白名单非空
#[cfg(test)]
pub(crate) fn test_state() -> Arc<AppState> {
    let mut cfg = Config::default();
    // 补齐密钥与白名单，避免build_router启动期panic
    cfg.auth.jwt_secret = "test-jwt-secret".to_string();
    cfg.sign.sign_secret = "test-sign-secret".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    test_state_with(cfg)
}
