package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统管理-系统信息视图，运行时模块状态，Java/os/Spring Boot/数据库/Redis +
 * 集群综合信息（对等后端实例列表 + PostgreSQL 主从节点列表 + Redis 集群节点列表）
 * + 关键运行配置摘要。
 *
 * 全部为只读展示数据，密钥类（jwt/sign/master_key）与口令一律不出现在摘要。
 */
public record SystemInfoView(
        String javaVersion,
        String osName,
        String osArch,
        LocalDateTime startedAt,
        long uptimeSecs,
        String springBootVersion,
        DbInfo db,
        RedisInfo redis,
        List<BackendInfo> backends,
        List<RedisNodeInfo> redisNodes,
        List<DbNodeInfo> dbNodes,
        Map<String, String> configSummary) {

    /** 数据库运行时信息，pg 版本 + 连接串主机，不含口令 */
    public record DbInfo(String version, String host, boolean reachable) {
    }

    /** Redis 运行时信息，session-store=in-memory 时 mode=in-memory 且 reachable=false */
    public record RedisInfo(String mode, boolean reachable, String version,
                            Long keys, Long usedMemoryBytes, double usedMemoryPct) {
    }

    /** 对等后端实例信息，经 fms.cluster.peers 列表探测 /internal/cluster-info 聚合 */
    public record BackendInfo(String instance, String host, boolean reachable,
                              String javaVersion, String springBootVersion,
                              long uptimeSecs, String redisMode) {
    }

    /** Redis 集群节点信息，哨兵模式下聚合主/从/哨兵全部节点，单节点模式仅当前节点 */
    public record RedisNodeInfo(String role, String name, String host, int port,
                                String status, String version, Long keys, Long usedMemoryBytes) {
    }

    /** PostgreSQL 节点信息，主库 + pg_stat_replication 发现的从库，replayLagBytes 为 WAL 复制延迟，-1 表示未知 */
    public record DbNodeInfo(String role, String name, String host, String state,
                             String syncState, long replayLagBytes) {
    }

    /** 本实例自身信息，纯本机采集，/internal/cluster-info 与聚合视图复用，避免递归探测 */
    public record SelfInfo(String javaVersion, String osName, String osArch,
                           LocalDateTime startedAt, long uptimeSecs, String springBootVersion,
                           DbInfo db, RedisInfo redis, Map<String, String> configSummary) {
    }
}
