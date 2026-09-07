package net.castimage.fms_backend.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 访问日志队列投递 Mapper（PGMQ q_log_access）：批量投递经 log.fn_send_access_batch
 * SECURITY DEFINER 函数（属主 fms_admin，init/log_access_batch.sql）单次调用完成——PGMQ 无批量
 * API（SPEC §8.3 仅逐条 send），循环移入 DB 内避免 N 次 JDBC 往返；函数内部逐条
 * pgmq.send 容错（单条失败 NOTICE 继续，best-effort），返回成功 send 条数。
 *
 * <p>权限模型：/internal 路径免网关签名（GatewaySignatureFilter shouldNotFilter），
 * 请求无身份采信则 SandboxSessionInterceptor 跳过注入，连接保持 fms_app 空壳，函数
 * SECURITY DEFINER 以属主 fms_admin 身份执行绕过角色限制（08 文件已授 fms_app log
 * schema USAGE），EXECUTE 仅授 fms_app（27 文件 REVOKE PUBLIC 兜底，08 的 REVOKE 先于
 * 27 执行，新建函数必须自查）喵。
 */
public interface LogQueueMapper {

    /**
     * 批量投递访问日志到 q_log_access（一次函数调用，循环在 DB 内）。
     *
     * @param items JSON 数组字符串，元素为 SPEC §8.2 约定的 jsonb 对象
     *              （{method,path,status,latency,ip,ts} + req_id/user/ua 扩展 + trace_id/user_id 消费别名）
     * @return 成功 send 条数（函数内逐条容错；整批失败时由调用方 catch 兜底为 0）
     */
    @Select("SELECT log.fn_send_access_batch(#{items}::jsonb)")
    int sendAccessBatch(@Param("items") String items);
}
