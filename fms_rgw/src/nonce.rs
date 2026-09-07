//! nonce去重表(`X-FMS-Signature`防重放)，`DashMap`<`String`, `Instant`> 内存实现，
//! 容量上限fail-closed防内存耗尽，去重窗口即ttl，60s周期cleanup移除过期条目。
//! 签名生成/校验主体在`sign.rs`(`mw_sign`不参与客户端nonce校验，网关不实例化本表，
//! 防重放由后端侧同构实现负责，本模块供后端/测试复用)
#![forbid(unsafe_code)]

use dashmap::DashMap;
use std::time::{Duration, Instant};

/// nonce去重表，内存实现，`DashMap`<`String`, `Instant`>，容量上限，满时拒绝，fail-closed防内存耗尽，去重窗口即ttl
pub struct NonceStore {
    map: DashMap<String, Instant>,
    ttl: Duration,
    capacity: usize,
}

impl NonceStore {
    pub fn new(capacity: usize, ttl: Duration) -> Self {
        Self {
            map: DashMap::new(),
            ttl,
            capacity: capacity.max(1),
        }
    }

    /// true = 首次见到，放行，false = 已存在，重放拒绝
    pub fn check_and_insert(&self, nonce: &str) -> bool {
        // 容量满时拒绝，fail-closed，防重放优先于可用性，误伤就调大容量而非放宽语义
        if self.map.len() >= self.capacity {
            tracing::warn!(
                nonce_capacity = self.capacity,
                "nonce 表容量已达上限，拒绝(fail-closed)"
            );
            return false;
        }

        let now = Instant::now();
        match self.map.entry(nonce.to_string()) {
            dashmap::mapref::entry::Entry::Occupied(mut o) => {
                let inserted_at = *o.get();
                if now.saturating_duration_since(inserted_at) > self.ttl {
                    // 已过期，更新时间戳并放行
                    o.insert(now);
                    true
                } else {
                    false
                }
            }
            dashmap::mapref::entry::Entry::Vacant(v) => {
                v.insert(now);
                true
            }
        }
    }

    /// 移除过期条目，main每60s定时调用
    pub fn cleanup(&self) {
        let now = Instant::now();
        self.map
            .retain(|_, inserted_at| now.saturating_duration_since(*inserted_at) <= self.ttl);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nonce_store_dedup_and_expiry() {
        let store = NonceStore::new(100, Duration::from_millis(100));
        assert!(store.check_and_insert("n1"));
        assert!(!store.check_and_insert("n1"), "重放拒绝");
        assert!(store.check_and_insert("n2"));
        std::thread::sleep(Duration::from_millis(150));
        assert!(store.check_and_insert("n1"), "过期后允许重新使用");
    }

    #[test]
    fn nonce_store_capacity_fail_closed() {
        let store = NonceStore::new(1, Duration::from_secs(60));
        assert!(store.check_and_insert("a"));
        // 容量满则拒绝，fail-closed，防重放优先于可用性
        assert!(!store.check_and_insert("b"));
        assert!(!store.check_and_insert("c"), "容量满时全部拒绝");
    }

    #[test]
    fn nonce_store_cleanup_removes_expired() {
        let store = NonceStore::new(10, Duration::from_millis(80));
        assert!(store.check_and_insert("n1"));
        assert!(store.check_and_insert("n2"));
        std::thread::sleep(Duration::from_millis(120));
        store.cleanup();
        // 全部过期条目被移除则两个 nonce 均可重新使用，同一次插入后立即去重
        assert!(
            store.check_and_insert("n1"),
            "cleanup 后过期 nonce 应可复用"
        );
        assert!(
            store.check_and_insert("n2"),
            "cleanup 后其余过期条目也应被移除"
        );
        assert!(!store.check_and_insert("n1"), "重新插入后立即去重");
    }
}
