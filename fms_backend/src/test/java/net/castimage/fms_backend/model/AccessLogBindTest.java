package net.castimage.fms_backend.model;

import com.alibaba.fastjson2.JSON;
import net.castimage.fms_backend.Model.dto.AccessLogItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网关到后端协议对齐测试，rgw 侧 AccessLogEntry 以 serde 默认字段名序列化，snake_case，req_id / latency_ms，后端运行路径用 fastjson2 解析
 * （InternalLogController json.parseArray），record 组件为 camelCase（reqId/latencyMs），
 * 经 @JSONField(name=...) 显式映射，本测试喂入的 RGW_PAYLOAD 即 rgw 实际序列化键名，与 fms_rgw log.rs entries_serialize_as_json_array_for_pgmq 单测断言一致，
 * 若绑定失效 reqId/latencyMs 恒 null 则 q_log_access 的 req_id/trace_id/latency
 * 审计列落空。
 */
class AccessLogBindTest {

    /** rgw AccessLogEntry 实际序列化键名，snake_case，与 fms_rgw 单测断言同构 */
    private static final String RGW_PAYLOAD = "[{\"ts\":\"2026-08-16T00:00:00.000Z\",\"req_id\":\"trace-123\","
            + "\"method\":\"GET\",\"path\":\"/healthz\",\"status\":200,\"latency_ms\":1.5,\"ip\":\"1.2.3.4\","
            + "\"user\":\"u-1\",\"ua\":\"curl/8.0\"}]";

    @Test
    void snakeCaseKeysBindToCamelCaseComponents() {
        List<AccessLogItem> items = JSON.parseArray(RGW_PAYLOAD, AccessLogItem.class);
        assertEquals(1, items.size(), "应解析 1 条");
        AccessLogItem it = items.get(0);
        assertNotNull(it.reqId(), "req_id 应绑定 reqId");
        assertNotNull(it.latencyMs(), "latency_ms 应绑定 latencyMs");
        assertEquals("trace-123", it.reqId());
        assertEquals(1.5, it.latencyMs(), 0.001);
        assertEquals(200, it.status());
    }
}
