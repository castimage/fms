//! 熔断器状态机，CLOSED、OPEN、HALF-OPEN 之间互相流转，被动路径按滑动窗口错误率触发，
//! 主动路径`health_probe_loop`周期探测推进，OPEN拒绝503 + `Retry-After`，计时到期自动
//! HALF-OPEN(放行 ≤ `half_open_probes`个探测，任一成功回CLOSED，失败回OPEN重新计时)
//!
//! 热路径无锁，状态用原子，仅样本队列用`Mutex`，常数开销，proxy/ws调`is_ready` + record，
//! main spawn `health_probe_loop`，`/healthz`调`state`
#![forbid(unsafe_code)]

use crate::config::CircuitCfg;
use crate::state::AppState;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU8, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use thiserror::Error;
use tracing::{info, warn};

/// 熔断器状态(`/healthz`展示用)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CircuitState {
    Closed,
    HalfOpen,
    Open,
}

/// 熔断错误，OPEN，含HALF-OPEN探测位占满时拒绝，503 + `Retry-After`，剩余秒数，最少1s
#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
pub enum CircuitError {
    #[error("upstream circuit open, retry after {retry_after_secs}s")]
    Open { retry_after_secs: u64 },
}

// 状态机编码(AtomicU8)，0=CLOSED 1=HALF-OPEN 2=OPEN
const STATE_CLOSED: u8 = 0;
const STATE_HALF_OPEN: u8 = 1;
const STATE_OPEN: u8 = 2;

/// 熔断器，状态用原子，热路径无锁，样本队列用`Mutex`保护，常数开销。
pub struct CircuitBreaker {
    cfg: CircuitCfg,
    state: AtomicU8,
    /// OPEN截止时刻(unix纳秒，`now_nanos` + `open_timeout`)
    open_until: AtomicU64,
    /// 最近样本，true=成功，最近`sample_window`个
    results: Mutex<VecDeque<bool>>,
    /// HALF-OPEN并发探测占用数
    half_open_inflight: AtomicU32,
}

/// 当前时刻的unix纳秒，时钟回拨时退化为0，仅影响熔断计时，安全
fn now_nanos() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_nanos() as u64
}

impl CircuitBreaker {
    pub fn new(cfg: CircuitCfg) -> Self {
        Self {
            cfg,
            state: AtomicU8::new(STATE_CLOSED),
            open_until: AtomicU64::new(0),
            results: Mutex::new(VecDeque::new()),
            half_open_inflight: AtomicU32::new(0),
        }
    }

    /// 进入OPEN，重新计时`open_timeout`，清零探测占用
    fn set_open(&self) {
        // 清零探测占用先于发布OPEN，try_half_open的fetch_add必须观察到清零后的计数
        self.half_open_inflight.store(0, Ordering::Relaxed);
        self.open_until.store(
            now_nanos().saturating_add(self.cfg.open_timeout_secs.saturating_mul(1_000_000_000)),
            Ordering::Relaxed,
        );
        self.state.store(STATE_OPEN, Ordering::Release);
        warn!("熔断器 OPEN(错误率超阈值或半开探测失败)");
    }

    /// OPEN计时到期则转 HALF-OPEN，CAS防并发重复转换，重复调用无副作用
    fn maybe_expire(&self) -> bool {
        if self.state.load(Ordering::Acquire) == STATE_OPEN
            && now_nanos() >= self.open_until.load(Ordering::Relaxed)
        {
            // 清零探测占用先于CAS发布HALF-OPEN，避免并发try_half_open的fetch_add
            // 落在清零之前被覆盖，导致短暂多放行探测位，窄竞态窗口
            self.half_open_inflight.store(0, Ordering::Relaxed);
            match self.state.compare_exchange(
                STATE_OPEN,
                STATE_HALF_OPEN,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => {
                    info!("熔断器 OPEN -> HALF-OPEN(计时到期，放行探测)");
                    true
                }
                Err(_) => false,
            }
        } else {
            false
        }
    }

    /// 恢复CLOSED，样本清零，计数清零，避免旧样本拖慢恢复后的再次判定
    fn reset_to_closed(&self) {
        if let Ok(mut results) = self.results.lock() {
            results.clear();
        }

        // 清零探测占用先于发布CLOSED(与set_open/maybe_expire同一次序约定)
        self.half_open_inflight.store(0, Ordering::Relaxed);
        self.state.store(STATE_CLOSED, Ordering::Release);
        info!("熔断器 HALF-OPEN -> CLOSED(探测成功，上游恢复)");
    }

    /// HALF-OPEN，CAS占用一个探测位(≤ `half_open_probes`)，占满则拒绝
    fn try_half_open(&self) -> Result<(), CircuitError> {
        let prev = self.half_open_inflight.fetch_add(1, Ordering::Relaxed);
        if prev < self.cfg.half_open_probes {
            Ok(())
        } else {
            self.half_open_inflight.fetch_sub(1, Ordering::Relaxed);
            Err(CircuitError::Open {
                retry_after_secs: self.remaining_open_secs().max(1),
            })
        }
    }

    /// OPEN剩余秒数(最少0，调用方自行max，1)
    fn remaining_open_secs(&self) -> u64 {
        self.open_until
            .load(Ordering::Relaxed)
            .saturating_sub(now_nanos())
            .div_ceil(1_000_000_000)
    }

    /// 记录一次结果，被动路径proxy/ws响应后，OPEN 期间成功则转 HALF-OPEN（仅计时到期时），
    /// HALF-OPEN 期间成功则转 CLOSED，失败则回 OPEN，CLOSED 状态按滑动窗口错误率判定。
    /// 主动探测结果请走`record_probe`，OPEN 期间成功无条件推进到 HALF-OPEN。
    ///
    /// 状态语义，CLOSED维护最近`sample_window`个样本，true=成功，样本数 ≥ `min_samples`
    /// 且fails*100 ≥ threshold*n，整数比较防浮点则调 `set_open`(重新计时`open_timeout`)，
    /// HALF-OPEN 任一成功则立即转 CLOSED(`reset_to_closed`清空样本，避免旧样本拖慢
    /// 恢复后的再次判定)，任一失败则立即回 OPEN 重新计时，OPEN 期间失败不改变状态，
    /// 成功仅当计时已到期才推进到 HALF-OPEN。
    ///
    /// 竞态防护，被动路径滞留请求(`is_ready`放行后回包前状态又回OPEN)的record，true
    /// 不得在计时到期前推进HALF-OPEN，否则快速失败窗口被击穿，主动探测无此竞态，
    /// 见`record_probe`。样本队列为`Mutex`保护，常数开销，状态读写走原子。
    ///
    /// `ok`，本次转发/泵的结果，true = 上游正常响应或长连接存续
    pub fn record(&self, ok: bool) {
        match self.state.load(Ordering::Acquire) {
            STATE_OPEN => {
                // 被动路径滞留请求竞态，is_ready放行的请求回包前若状态又回OPEN，
                // 其record，true 不得立即推进HALF-OPEN，击穿快速失败窗口，
                // 仅当OPEN计时已到期才允许推进，主动探测不受此限
                if ok && now_nanos() >= self.open_until.load(Ordering::Relaxed) {
                    // 清零探测占用先于CAS发布HALF-OPEN(与maybe_expire同一次序约定)
                    self.half_open_inflight.store(0, Ordering::Relaxed);
                    let _ = self.state.compare_exchange(
                        STATE_OPEN,
                        STATE_HALF_OPEN,
                        Ordering::AcqRel,
                        Ordering::Acquire,
                    );
                    info!("熔断器 OPEN -> HALF-OPEN(计时到期且滞留请求成功)");
                }
            }
            STATE_HALF_OPEN => {
                // 任一成功则转 CLOSED，任一失败则立即回 OPEN，重新计时
                if ok {
                    self.reset_to_closed();
                } else {
                    self.set_open();
                }
            }
            _ => {
                // CLOSED，滑动窗口错误率。fails/n ≥ threshold% ⇔ fails*100 ≥ threshold*n，整数比较
                let threshold = self.cfg.error_threshold_pct;
                let min_samples = self.cfg.min_samples;
                let window = self.cfg.sample_window;
                let mut results = match self.results.lock() {
                    Ok(g) => g,
                    Err(poisoned) => poisoned.into_inner(),
                };
                if results.len() >= window {
                    results.pop_front();
                }
                results.push_back(ok);
                let n = results.len();

                if n >= min_samples {
                    let fails = results.iter().filter(|r| !**r).count();
                    if fails.saturating_mul(100) >= (threshold as usize).saturating_mul(n) {
                        drop(results);
                        self.set_open();
                    }
                }
            }
        }
    }

    /// 记录主动健康探测结果，OPEN 期间成功则立即推进到 HALF-OPEN，不等计时到期，
    /// 其余状态与record一致。被动路径滞留请求仅计时到期后可推进，主动探测无此竞态
    pub fn record_probe(&self, ok: bool) {
        if self.state.load(Ordering::Acquire) == STATE_OPEN && ok {
            // 清零探测占用先于CAS发布HALF-OPEN(与maybe_expire同一次序约定)
            self.half_open_inflight.store(0, Ordering::Relaxed);
            let _ = self.state.compare_exchange(
                STATE_OPEN,
                STATE_HALF_OPEN,
                Ordering::AcqRel,
                Ordering::Acquire,
            );
            info!("熔断器 OPEN -> HALF-OPEN(健康探测成功)");
        } else {
            self.record(ok);
        }
    }

    /// 转发前调用，CLOSED 返回 Ok，OPEN 返回 `Err`(`Open`{剩余秒数})，HALF-OPEN 探测位未满放行，否则返回 `Err`。
    /// 每次调用都会先执行`maybe_expire`(OPEN计时到期自动转HALF-OPEN，CAS防并发
    /// 重复转换)，因此调用方无需等待外部信号，到期即恢复探测。
    ///
    /// HALF-OPEN的探测位(`half_open_probes`个并发额度)由`try_half_open` CAS占用，
    /// 放行上限个探测请求验证上游，占满后其余请求立即`Err`(`Open`{`retry_after`})，
    /// 探测期间的拒绝仍是503 + `Retry-After`，剩余open秒数，最少1s，客户端按
    /// `Retry-After`重试即可。原子操作(`Relaxed`级足够，状态机推进有CAS兜底)。
    ///
    /// @return Ok = 允许转发，`Err`(`CircuitError::Open`) = 熔断拒绝，携带`retry_after_secs`
    pub fn is_ready(&self) -> Result<(), CircuitError> {
        match self.state.load(Ordering::Acquire) {
            STATE_CLOSED => Ok(()),
            STATE_OPEN => {
                self.maybe_expire();
                // 计时到期已转换则按HALF-OPEN处理
                if self.state.load(Ordering::Acquire) == STATE_HALF_OPEN {
                    self.try_half_open()
                } else {
                    Err(CircuitError::Open {
                        retry_after_secs: self.remaining_open_secs().max(1),
                    })
                }
            }
            _ => self.try_half_open(),
        }
    }

    /// 当前状态(`/healthz`展示，附带到期检查)
    pub fn state(&self) -> CircuitState {
        self.maybe_expire();
        match self.state.load(Ordering::Acquire) {
            STATE_OPEN => CircuitState::Open,
            STATE_HALF_OPEN => CircuitState::HalfOpen,
            _ => CircuitState::Closed,
        }
    }
}

/// 主动健康探测任务(main spawn，周期`health_interval_secs`)，遍历池内全部实例，
/// 每实例独立探测(GET {`base_url`}{`health_path`}，超时`health_timeout_secs`，直连不经
/// 安全链)，成功`mark_healthy`，true / 失败`mark_healthy`，false，故障自动剔除、
/// 恢复自动重新纳入，"一定程度上自动选用"。
///
/// 全局熔断联动，探测后健康实例数 > 0视为整体可用，经`record_probe`推进状态机
/// 多实例下熔断粒度降为整体兜底，实例级健康由池内探测管理
pub async fn health_probe_loop(state: Arc<AppState>) {
    let interval = Duration::from_secs(state.cfg.upstream.health_interval_secs.max(1));
    let timeout = Duration::from_secs(state.cfg.upstream.health_timeout_secs.max(1));
    info!(interval_secs = interval.as_secs(), "健康探测任务启动");

    loop {
        tokio::time::sleep(interval).await;
        let healthy = state
            .upstream
            .probe_all(&state.cfg.upstream.health_path, timeout)
            .await;
        state.circuit.record_probe(healthy > 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread::sleep;
    use std::time::Duration;

    /// 构造小参数配置以便测试状态转换(`open_timeout` 1s、样本窗10、阈值50%、最少样本3、探测位2)
    fn test_cfg() -> CircuitCfg {
        CircuitCfg {
            error_threshold_pct: 50,
            sample_window: 10,
            min_samples: 3,
            open_timeout_secs: 1,
            half_open_probes: 2,
        }
    }

    #[test]
    fn initially_closed() {
        let cb = CircuitBreaker::new(test_cfg());
        assert_eq!(cb.state(), CircuitState::Closed);
        assert!(cb.is_ready().is_ok());
    }

    #[test]
    fn error_rate_opens_circuit() {
        let cb = CircuitBreaker::new(test_cfg());
        // 3个样本3个失败(≥50%且 ≥min_samples)
        cb.record(false);
        cb.record(false);
        cb.record(false);
        assert_eq!(cb.state(), CircuitState::Open);
        // OPEN，is_ready拒绝且Retry-After ≥ 1s
        match cb.is_ready() {
            Err(CircuitError::Open { retry_after_secs }) => assert!(retry_after_secs >= 1),
            Ok(_) => panic!("OPEN 状态不应放行"),
        }
    }

    #[test]
    fn below_min_samples_does_not_open() {
        let cb = CircuitBreaker::new(test_cfg());
        cb.record(false);
        cb.record(false); // 样本2 < min_samples 3
        assert_eq!(cb.state(), CircuitState::Closed);
    }

    #[test]
    fn below_threshold_does_not_open() {
        let cb = CircuitBreaker::new(test_cfg());
        cb.record(false);
        cb.record(true);
        cb.record(true); // 1/3失败 < 50%
        assert_eq!(cb.state(), CircuitState::Closed);
    }

    #[test]
    fn open_expires_to_half_open() {
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        assert_eq!(cb.state(), CircuitState::Open);
        sleep(Duration::from_millis(1100)); // open_timeout 1s
        assert_eq!(cb.state(), CircuitState::HalfOpen);
        assert!(cb.is_ready().is_ok(), "HALF-OPEN 应放行探测请求");
    }

    #[test]
    fn half_open_success_closes() {
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        sleep(Duration::from_millis(1100));
        assert!(cb.is_ready().is_ok());
        cb.record(true); // 探测成功则转 CLOSED
        assert_eq!(cb.state(), CircuitState::Closed);
    }

    #[test]
    fn half_open_failure_reopens() {
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        sleep(Duration::from_millis(1100));
        assert!(cb.is_ready().is_ok());
        cb.record(false); // 探测失败则立即回 OPEN，重新计时
        assert_eq!(cb.state(), CircuitState::Open);
        assert!(cb.is_ready().is_err());
        // 重新计时，1s内仍OPEN
        sleep(Duration::from_millis(300));
        assert_eq!(cb.state(), CircuitState::Open);
    }

    #[test]
    fn half_open_limits_concurrent_probes() {
        let cb = CircuitBreaker::new(test_cfg()); // half_open_probes = 2
        for _ in 0..3 {
            cb.record(false);
        }
        sleep(Duration::from_millis(1100));
        assert!(cb.is_ready().is_ok(), "第 1 个探测位");
        assert!(cb.is_ready().is_ok(), "第 2 个探测位");
        assert!(cb.is_ready().is_err(), "探测位占满应拒绝");
    }

    #[test]
    fn open_probe_success_advances_to_half_open() {
        // 主动路径，OPEN 期间探测成功则推进到 HALF-OPEN，不等计时到期
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        assert_eq!(cb.state(), CircuitState::Open);
        cb.record_probe(true); // 主动探测成功
        assert_eq!(cb.state(), CircuitState::HalfOpen);
        assert!(cb.is_ready().is_ok());
    }

    #[test]
    fn open_failure_stays_open() {
        // OPEN 期间探测失败则保持 OPEN，仅成功可推进到 HALF-OPEN
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        assert_eq!(cb.state(), CircuitState::Open);
        cb.record(false);
        assert_eq!(cb.state(), CircuitState::Open, "OPEN 期间失败不得改变状态");
        assert!(cb.is_ready().is_err());
    }

    #[test]
    fn stranded_request_success_does_not_skip_open_timer() {
        // HALF-OPEN放行的滞留请求在状态回OPEN后，record(true) 不得立即推进
        // HALF-OPEN，快速失败窗口不得被击穿，仅计时到期才允许，主动探测不受此限
        let cfg = CircuitCfg {
            error_threshold_pct: 50,
            sample_window: 10,
            min_samples: 3,
            open_timeout_secs: 1,
            half_open_probes: 2,
        };
        let cb = CircuitBreaker::new(cfg);
        for _ in 0..3 {
            cb.record(false);
        }
        sleep(Duration::from_millis(1100)); // OPEN 计时到期则推进到 HALF-OPEN
        assert!(cb.is_ready().is_ok(), "HALF-OPEN 放行滞留请求");
        // 另一探测失败则回 OPEN，重新计时1s
        cb.record_probe(false);
        assert_eq!(cb.state(), CircuitState::Open);
        // 滞留请求此时才回包成功则 record 置 true，计时未到期，不得推进到 HALF-OPEN
        cb.record(true);
        assert_eq!(
            cb.state(),
            CircuitState::Open,
            "F8：滞留请求成功不得在 open_timeout 到期前推进 HALF-OPEN"
        );
        assert!(cb.is_ready().is_err(), "仍处于快速失败窗口");
        // 计时到期后，滞留成功允许推进，被动路径的正常恢复语义
        sleep(Duration::from_millis(1100));
        cb.record(true);
        assert_eq!(
            cb.state(),
            CircuitState::HalfOpen,
            "计时到期后滞留成功可推进 HALF-OPEN"
        );
    }

    #[test]
    fn exact_threshold_opens_and_under_threshold_does_not() {
        // 阈值恰为50%，4样本中2失败则 opens，1失败则不 open
        let open_cfg = CircuitCfg {
            error_threshold_pct: 50,
            sample_window: 10,
            min_samples: 4,
            open_timeout_secs: 1,
            half_open_probes: 2,
        };
        let cb = CircuitBreaker::new(open_cfg.clone());
        cb.record(false);
        cb.record(true);
        cb.record(true);
        cb.record(false); // 2/4 = 50% ≥ 50%
        assert_eq!(cb.state(), CircuitState::Open, "恰好等于阈值应触发熔断");
        // 同配置1/4 = 25% < 50% 则保持 CLOSED
        let cb2 = CircuitBreaker::new(open_cfg);
        cb2.record(false);
        cb2.record(true);
        cb2.record(true);
        cb2.record(true);
        assert_eq!(cb2.state(), CircuitState::Closed);
    }

    #[test]
    fn error_rate_opens_and_closes_cycle_via_probes() {
        // 完整循环，CLOSED 起错误率超阈值转 OPEN，计时到期转 HALF-OPEN，探测成功回 CLOSED
        let cb = CircuitBreaker::new(test_cfg());
        for _ in 0..3 {
            cb.record(false);
        }
        assert_eq!(cb.state(), CircuitState::Open);
        sleep(Duration::from_millis(1100));
        assert_eq!(cb.state(), CircuitState::HalfOpen);
        assert!(cb.is_ready().is_ok());
        cb.record(true);
        assert_eq!(cb.state(), CircuitState::Closed);
        assert!(cb.is_ready().is_ok());
    }
}
