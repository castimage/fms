package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 读写分离路由测试，按事务只读标志判定路由键，readOnly=true 事务走只读源，从库、
 * 写事务与非事务走写源，主库，事务上下文清理后回落写源。
 */
class RoutingDataSourceTest {

    /** 暴露 protected determineCurrentLookupKey 供断言 */
    static class TestableRoutingDataSource extends RoutingDataSource {
        Object currentKey() {
            return determineCurrentLookupKey();
        }
    }

    private final TestableRoutingDataSource routing = new TestableRoutingDataSource();

    @AfterEach
    void tearDown() {
        // 无条件清理，setActualTransactionActive 不激活 synchronization，
        // 条件判断会漏掉残留的 readOnly 标记污染下一个测试
        TransactionSynchronizationManager.clear();
    }

    @Test
    void noTransactionRoutesToWrite() {
        assertEquals(RoutingDataSource.WRITE, routing.currentKey());
    }

    @Test
    void readOnlyTransactionRoutesToRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertEquals(RoutingDataSource.READ, routing.currentKey());
    }

    @Test
    void writeTransactionRoutesToWrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertEquals(RoutingDataSource.WRITE, routing.currentKey());
    }

    @Test
    void clearedTransactionFallsBackToWrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertEquals(RoutingDataSource.READ, routing.currentKey());
        TransactionSynchronizationManager.clear();
        assertEquals(RoutingDataSource.WRITE, routing.currentKey());
    }
}
