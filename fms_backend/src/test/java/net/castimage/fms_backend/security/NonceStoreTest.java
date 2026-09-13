package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.InMemoryNonceStore;
import net.castimage.fms_backend.Utils.NonceStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NonceStore 防重放去重表单元测试，
 * 首次放行/重放拒绝，第二道闸、ttl 过期后重新接受、容量上限 fail-closed、非法容量拒绝。
 */
class NonceStoreTest {

    @Test
    void firstSeenNonceAcceptedReplayRejected() {
        NonceStore store = new InMemoryNonceStore(1000, 60_000L);
        String nonce = "replay-nonce-1";
        assertTrue(store.checkAndInsert(nonce), "首次见到应放行");
        assertFalse(store.checkAndInsert(nonce), "原样重放应拒绝（X4-1 第二道闸）");
        assertFalse(store.checkAndInsert(nonce), "TTL 内重复重放仍应拒绝");
    }

    @Test
    void distinctNoncesAreAllAccepted() {
        NonceStore store = new InMemoryNonceStore(1000, 60_000L);
        for (int i = 0; i < 100; i++) {
            assertTrue(store.checkAndInsert("n-" + i), "不同 nonce 应全部放行");
        }
    }

    @Test
    void expiredNonceIsAcceptedAgain() throws InterruptedException {
        NonceStore store = new InMemoryNonceStore(1000, 30L);
        String nonce = "expiring-nonce";
        assertTrue(store.checkAndInsert(nonce));
        assertFalse(store.checkAndInsert(nonce));
        Thread.sleep(60);
        assertTrue(store.checkAndInsert(nonce), "TTL 过期后同 nonce 应重新接受");
    }

    @Test
    void capacityOverflowFailsClosed() {
        NonceStore store = new InMemoryNonceStore(2, 60_000L);
        assertTrue(store.checkAndInsert("a"));
        assertTrue(store.checkAndInsert("b"));
        // 超过容量上限，新 nonce 拒绝，fail-closed，防内存耗尽
        assertFalse(store.checkAndInsert("c"));
        // 已登记条目不受影响
        assertFalse(store.checkAndInsert("a"));
    }

    @Test
    void invalidCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryNonceStore(0, 60_000L));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryNonceStore(-1, 60_000L));
    }

    @Test
    void dynamicCapacityFormula() {
        // 未显式配置，0 哨兵则按 peak_qps × window 动态推导（1000 × 300 = 300000）
        assertEquals(300_000, NonceStore.effectiveCapacity(0, 1000, 300),
                "未配置时容量 = noncePeakQps × replayWindowSecs");
        // 显式 capacity 优先，兼容既有配置，yaml 显式 nonce-capacity 10000 时取该值
        assertEquals(10_000, NonceStore.effectiveCapacity(10_000, 1000, 300),
                "显式 nonce-capacity 优先于动态推导");
        // 其它峰值/窗口组合按公式推导，推导结果封顶 int（1000 万 × 300 = 30 亿 > int 上限）
        assertEquals(600, NonceStore.effectiveCapacity(0, 2, 300));
        assertEquals(Integer.MAX_VALUE, NonceStore.effectiveCapacity(0, 10_000_000, 300));
    }

    @Test
    void usageRatioEmptyAndFull() {
        NonceStore store = new InMemoryNonceStore(2, 60_000L);
        assertEquals(0.0, store.usageRatio(), 0.0001, "空表水位 0.0");
        assertTrue(store.checkAndInsert("a"));
        assertTrue(store.checkAndInsert("b"));
        assertEquals(1.0, store.usageRatio(), 0.0001, "满表水位 1.0");
    }
}
