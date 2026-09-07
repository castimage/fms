package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.vo.SystemInfoView;

/**
 * 系统信息服务，聚合运行时模块状态，JVM/os/Spring Boot/数据库/Redis 与关键运行配置摘要，
 * 供管理端「系统管理」页只读预览，不暴露任何密钥/口令，configSummary 白名单键。
 */
public interface SystemInfoService {

    /** 当前进程运行时系统信息，每次调用实时采集，Redis 不可达/未启用按 reachable=false 呈现 */
    SystemInfoView current();

    /** 本实例自身信息，纯本机采集，不触发对等探测，供 /internal/cluster-info 与聚合视图复用 */
    SystemInfoView.SelfInfo selfInfo();
}
