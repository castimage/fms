package net.castimage.fms_backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.castimage.fms_backend.Model.ApiResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FMS 统一响应体序列化契约测试，协议 7.1，
 * 字段名恒为 code/message/data/traceId 且按此序渲染，
 * 成功 code=0/message="ok"，data 与 traceId 恒渲染（@JsonInclude(ALWAYS)，
 * 即使全局 NON_NULL 也不可省略），traceId 优先取 MDC。
 */
class ApiResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successResponseSerializesFourProtocolFieldsInOrder() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(
                ApiResponse.ok(Map.of("accessToken", "jwt-xxx"))));

        Iterator<String> fields = node.fieldNames();
        assertEquals("code", fields.next());
        assertEquals("message", fields.next());
        assertEquals("data", fields.next());
        assertEquals("traceId", fields.next());
        assertFalse(fields.hasNext(), "响应体只允许协议四字段");

        assertEquals(0, node.get("code").asInt());
        assertEquals("ok", node.get("message").asText());
        assertEquals("jwt-xxx", node.get("data").get("accessToken").asText());
        assertTrue(node.get("traceId").asText().matches("[0-9a-f-]{36}"));
    }

    @Test
    void errorResponseCarriesCodeMessageNullDataAndTraceId() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(
                ApiResponse.error(40100, "invalid signature", "trace-abc-123")));

        assertEquals(40100, node.get("code").asInt());
        assertEquals("invalid signature", node.get("message").asText());
        assertTrue(node.get("data").isNull(), "错误路径 data 恒为 null");
        assertEquals("trace-abc-123", node.get("traceId").asText());
    }

    @Test
    void dataAndTraceIdAlwaysRenderEvenUnderNonNullInclusion() throws Exception {
        // 模拟应用全局 Jackson 配置（yaml: default-property-inclusion: non_null）
        ObjectMapper nonNull = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        JsonNode node = nonNull.readTree(nonNull.writeValueAsString(ApiResponse.ok(null)));

        assertTrue(node.has("data"), "data 恒渲染（成功路径 null 也输出）");
        assertTrue(node.get("data").isNull());
        assertTrue(node.has("traceId"), "traceId 恒渲染（协议要求全链路日志关联）");
        assertFalse(node.get("traceId").asText().isBlank());
    }

    @Test
    void traceIdPrefersMdcValue() throws Exception {
        MDC.put("traceId", "mdc-trace-0001");
        try {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(ApiResponse.ok("x")));
            assertEquals("mdc-trace-0001", node.get("traceId").asText());
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void fieldNamesMatchProtocolExactly() throws Exception {
        // 防止未来重命名字段（如 traceId 变 trace_id）破坏协议
        List<String> names = new ArrayList<>();
        mapper.readTree(mapper.writeValueAsString(ApiResponse.ok(null))).fieldNames()
                .forEachRemaining(names::add);
        assertEquals(List.of("code", "message", "data", "traceId"), names);
    }
}
