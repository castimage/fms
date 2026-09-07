package net.castimage.fms_backend.Model.dto;

import com.alibaba.fastjson2.annotation.JSONField;

/**
 * 网关访问日志批量条目（POST /internal/log-batch 数组单元素）：JSON 键与 fms_rgw log.rs
 * AccessLogEntry 序列化输出逐字对齐（ts/req_id/method/path/status/latency_ms/ip/user/ua——
 * rgw 侧 serde 未配置 rename_all，字段名默认 = Rust 标识符即 snake_case；fms_rgw log.rs
 * entries_serialize_as_json_array_for_pgmq 单测断言这 9 个键）。其中 req_id = trace_id、
 * user = 验签通过 claims.sub、匿名/未认证为 null。
 * Java record 组件保持驼峰命名（reqId/latencyMs），经 @JsonProperty 显式映射 JSON 键
 * req_id / latency_ms（fastjson2：record 组件注解即属性映射；未知键默认
 * 静默忽略——两端口径必须逐字一致，否则 reqId/latencyMs 绑 null，q_log_access 的
 * req_id/trace_id/latency 审计列恒空）。
 * 全部字段可空（网关 Option 字段 JSON 序列化为 null）——后端侧按 SPEC §8.2
 * q_log_access 约定重映射后批量投递 PGMQ，缺省由消费链兜底（07 fn_consume_log_queues：
 * method 缺省 GET、path 缺省 /，status/latency/ip 非法置 NULL）。
 */
public record AccessLogItem(
        /** RFC3339 UTC（毫秒精度） */
        String ts,
        /** = trace_id（网关日志 req_id 注释"= trace_id"；JSON 键 req_id） */
        @JSONField(name = "req_id") String reqId,
        String method,
        /** 原始请求路径（含 query 字符串；网关侧已按 log.path_max_len 截断——默认 1024——并脱敏 token，
         *  后端 InternalLogController 按 fms.log.path-max-len 兜底同值截断，两端调整须同步） */
        String path,
        Integer status,
        /** started 到响应完成的毫秒（浮点，后端重映射为整数 latency 供消费链 ::int 落库；JSON 键 latency_ms） */
        @JSONField(name = "latency_ms") Double latencyMs,
        String ip,
        /** claims.sub（匿名请求为 null） */
        String user,
        /** User-Agent（网关侧已按 log.ua_max_len 截断——默认 256；后端 fms.log.ua-max-len 兜底同值） */
        String ua) {
}
