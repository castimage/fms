package net.castimage.fms_backend.Controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Mapper.LogQueueMapper;
import net.castimage.fms_backend.Utils.SandboxRoleHint;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.dto.AccessLogItem;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * 网关内部端点，/internal，免网关签名但需内部 token，POST /internal/log-batch 批量接收
 * 访问日志并经 pgmq q_log_access 中转落库。
 *
 * 网关零外部基础设施依赖，访问日志仅写
 * jsonl 到 stdout+文件，不直连 pg/pgmq，pgmq 投递必须经 http 中转，网关批量 POST 本端点，该路径豁免网关签名，但需头 X-FMS-Int-Token 与配置 fms.security.internal-token 一致，随后
 * 本端点批量 pgmq.send("q_log_access")，由后端 cron（log.fn_consume_log_queues 每分钟，
 * 07 文件，链路已存在勿改）消费落 log.access_logs。
 *
 * 安全模型（fail-closed），internal-token 未配置，默认空=端点禁用或头不匹配一律 40300，
 * 请求无身份采信，db 连接保持 fms_app 空壳，投递经 log.fn_send_access_batch SECURITY
 * DEFINER 函数，属主 fms_admin 单次调用完成，函数内逐条 pgmq.send 容错，单条失败 NOTICE
 * 继续，返回成功条数，整批失败，如 db 不可达记录 WARN 后 accepted=0，不阻断网关请求路径。
 *
 * payload 映射，网关 9 字段转 q_log_access 约定 {method,path,status,latency,ip,ts}
 * + 扩展 req_id/user/ua，消费链（07 fn_consume_log_queues）固定读取 user_id/trace_id 键，
 * 故同值双写别名保证 log.access_logs.user_id/trace_id 审计列落库，链路勿改，别名在发送侧完成。
 * latency 由浮点毫秒重映射为整数（消费链 NULLIF，...::int 落库，浮点文本 cast 必失败）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal")
public class InternalLogController {

    private static final Logger log = LoggerFactory.getLogger(InternalLogController.class);

    /** 内部端点 token 请求头，与网关内部中转约定一致 */
    private static final String HEADER_INT_TOKEN = "X-FMS-Int-Token";

    // 单批上限 / 兜底截断长度均收敛 fms.log.* 配置（yaml 键 fms.log.batch-max-items /
    // path-max-len / ua-max-len，默认 1000 / 1024 / 256），与网关侧 log.batch_size /
    // path_max_len / ua_max_len 联动收敛，任一侧调整另一侧须同步，AccessLogItem 注释已声明

    private final FmsProperties fmsProperties;
    private final LogQueueMapper logQueueMapper;


    /**
     * 批量接收访问日志并投递 pgmq q_log_access（best-effort）。
     *
     * @param token 内部 token（头 X-FMS-Int-Token，缺失/不匹配/未配置则 40300 fail-closed）
     * @param items 日志条目数组（[{ts,req_id,method,path,status,latency_ms,ip,user,ua}...]，
     *              与 rgw AccessLogEntry serde 序列化逐字一致，Java 组件经 @JsonProperty 映射）
     * @return {@code {accepted: N}}，N 为成功 send 条数，整批失败为 0，不抛错
     */
    @PostMapping("/log-batch")
    public ApiResponse<Map<String, Integer>> logBatch(
            @RequestHeader(name = HEADER_INT_TOKEN, required = false) String token,
            @RequestBody String rawBody) {
        // 内部投递角色提示，匿名请求经 SandboxSessionInterceptor 注入 fms_operator（日志投递函数
        // EXECUTE 授 fms_operator，fms_app 仅 CONNECT + 认证函数，用户 2026-08-19 明确）
        SandboxRoleHint.set(SandboxRoleHint.Hint.INTERNAL_OPERATOR);
        try {
            // 解析（fastjson2 手动，与项目统一，@RequestBody 自动绑定走 Boot 默认 Jackson 3
            //    tools.jackson，不认 fastjson2 @JSONField，record 组件 camelCase 绑 snake_case
            //    键必 null，实测 reqId/latencyMs 恒空，故手动 parseArray 与回归测试同路径）
            List<AccessLogItem> items;
            try {
                items = JSON.parseArray(rawBody, AccessLogItem.class);
            } catch (Exception e) {
                throw new BadRequestException("日志批量 JSON 解析失败");
            }
            if (items == null) {
                items = List.of();
            }

            // 内部 token 校验（fail-closed），未配置 = 端点禁用，头缺失/不匹配一律 40300
            String expected = fmsProperties.getSecurity().getInternalToken();
            if (expected == null || expected.isBlank() || !expected.equals(token)) {
                log.warn("内部端点 token 校验失败（endpoint={} configured={}）", "/internal/log-batch", expected != null && !expected.isBlank());
                throw new ForbiddenException("内部端点访问被拒绝");
            }

            // 单批上限，超限 40001，读取 fms.log.batch-max-items，默认 1000
            int maxBatchItems = fmsProperties.getLog().getBatchMaxItems();
            if (items.size() > maxBatchItems) {
                throw new BadRequestException("日志批量超过上限 " + maxBatchItems + " 条");
            }

            // 字段重映射为 json 数组字符串，单次函数调用批量投递，循环在 db 内
            List<JSONObject> payloads = items.stream().map(this::toQueuePayload).toList();
            String json = JSON.toJSONString(payloads);
            int accepted;
            try {
                accepted = logQueueMapper.sendAccessBatch(json);
            } catch (Exception e) {
                // 整批失败，如 db/队列不可用，WARN + accepted=0，保护网关请求路径
                log.warn("log-batch PGMQ 投递失败（best-effort，本批丢弃）: n={} msg={}", items.size(), e.getMessage());
                accepted = 0;
            }
            return ApiResponse.ok(Map.of("accepted", accepted));
        } finally {
            SandboxRoleHint.clear();
        }
    }

    /**
     * 网关条目转 q_log_access 消息体，约定 + 扩展 + 消费链别名。
     */
    private JSONObject toQueuePayload(AccessLogItem item) {
        JSONObject o = new JSONObject();
        // 核心字段（约定，null 由消费链 COALESCE 兜底 method 缺省 GET/path 缺省 /）
        o.put("method", item.method());
        o.put("path", truncate(item.path(), fmsProperties.getLog().getPathMaxLen()));
        o.put("status", item.status());

        // latency 必须为整数，消费链 NULLIF(->>'latency','')::int，浮点文本 cast 报错整条跳过
        o.put("latency", item.latencyMs() == null ? null : Math.round(item.latencyMs()));
        o.put("ip", item.ip());
        o.put("ts", item.ts());

        // 扩展字段，约定 req_id/user/ua
        o.put("req_id", item.reqId());
        o.put("user", item.user());
        o.put("ua", truncate(item.ua(), fmsProperties.getLog().getUaMaxLen()));

        // 消费链别名，07 fn_consume_log_queues 固定读 user_id/trace_id 键落
        // log.access_logs.user_id/trace_id（reqId 即 trace_id、user 即 claims.sub）
        o.put("trace_id", item.reqId());
        o.put("user_id", item.user());
        return o;
    }

    /** 防御性截断，null 原样返回 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
