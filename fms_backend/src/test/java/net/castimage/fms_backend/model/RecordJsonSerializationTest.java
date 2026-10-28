package net.castimage.fms_backend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.castimage.fms_backend.Model.dto.LoginRequest;
import net.castimage.fms_backend.Model.vo.CompanyView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * record dto/vo json 序列化/反序列化契约测试。
 *
 * 运行时（Spring Boot 4）为 Jackson 3（tools.jackson.*），本测试同时覆盖
 * Jackson 3，真实运行时路径与 Jackson 2，与既有序列化契约测试同栈，
 * 字段名按 record 组件名（camelCase）渲染，反序列化经规范构造器回填。
 */
class RecordJsonSerializationTest {

    /** Jackson 3，Boot 4 运行时实际使用的序列化器 */
    private final tools.jackson.databind.ObjectMapper jackson3 = new tools.jackson.databind.ObjectMapper();

    /** Jackson 2，与 ApiResponseSerializationTest 同栈，test 依赖 2.18.3 */
    private final ObjectMapper jackson2 = new ObjectMapper();

    @Test
    void loginRequestRoundTripsViaJackson3RuntimeSerializer() {
        // 序列化，字段名必须按 record 组件名 camelCase 渲染且保持组件顺序
        String json = jackson3.writeValueAsString(new LoginRequest("admin", "P@ssw0rd"));
        JsonNode node = jackson3.readTree(json);
        Iterator<Map.Entry<String, JsonNode>> names = node.properties().iterator();
        assertEquals("loginName", names.next().getKey());
        assertEquals("password", names.next().getKey());
        assertFalse(names.hasNext(), "record DTO 序列化不得输出额外字段");
        assertEquals("admin", node.get("loginName").asString());

        // 反序列化，经规范构造器回填，含 @NotBlank 校验注解的组件
        LoginRequest back = jackson3.readValue(json, LoginRequest.class);
        assertEquals(new LoginRequest("admin", "P@ssw0rd"), back);
        assertEquals("admin", back.loginName());
        assertEquals("P@ssw0rd", back.password());
    }

    @Test
    void companyViewWithUuidAndTimeRoundTripsViaJackson3() {
        UUID id = UUID.fromString("0197f2a4-6b3c-4e2f-8a1d-000000000001");
        LocalDate fiscalStart = LocalDate.of(2026, 1, 1);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 9, 10, 30, 0);
        CompanyView view = new CompanyView(id, "C001", "甲公司", "CNY", fiscalStart, "ACTIVE", createdAt);

        String json = jackson3.writeValueAsString(view);
        JsonNode node = jackson3.readTree(json);
        // uuid 序列化为标准字符串，非时间戳，日期为 ISO-8601
        assertEquals("0197f2a4-6b3c-4e2f-8a1d-000000000001", node.get("id").asString());
        assertEquals("2026-01-01", node.get("fiscalYearStart").asString());
        assertEquals("2026-08-09T10:30:00", node.get("createdAt").asString());
        assertEquals("C001", node.get("code").asString());

        CompanyView back = jackson3.readValue(json, CompanyView.class);
        assertEquals(view, back);
        assertEquals(id, back.id());
        assertEquals(fiscalStart, back.fiscalYearStart());
        assertEquals(createdAt, back.createdAt());
    }

    @Test
    void loginRequestRoundTripsViaJackson2TestStack() throws Exception {
        // Jackson 2 兼容路径，既有契约测试同栈，record 支持不依赖 -parameters
        String json = jackson2.writeValueAsString(new LoginRequest("viewer", "secret"));
        assertEquals("{\"loginName\":\"viewer\",\"password\":\"secret\"}", json);
        LoginRequest back = jackson2.readValue(json, LoginRequest.class);
        assertEquals(new LoginRequest("viewer", "secret"), back);
    }
}
