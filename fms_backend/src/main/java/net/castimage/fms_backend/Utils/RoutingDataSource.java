package net.castimage.fms_backend.Utils;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 读写分离路由数据源（从库只读分流）：按当前事务的只读标志路由——
 * {@code @Transactional(readOnly=true)} 走只读源（从库/pgbouncer-ro），写事务与
 * 非事务查询走写源（主库）。只读事务内任何写会由从库拒绝（standby 只读），
 * 恰好暴露误标 readOnly 的写路径；报表等查询类事务全部 readOnly=true。
 *
 * 禁用语义（fms.datasource-ro.enabled=false，单机/多机形态）：装配时直接返回
 * 写源（见 RoutingDataSourceConfig），本类不被实例化，零路由开销。
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    /** 只读源键（readOnly=true 事务） */
    public static final String READ = "read";

    /** 写源键（写事务/非事务查询） */
    public static final String WRITE = "write";

    @Override
    protected Object determineCurrentLookupKey() {
        // 无事务时 isCurrentTransactionReadOnly()=false 则写源（默认行为与直连一致）
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? READ : WRITE;
    }
}
