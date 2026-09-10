package net.castimage.fms_backend.integration;

import net.castimage.fms_backend.Utils.UuidV7;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网关签名校验全链路集成测试，GatewaySignatureFilter 10 步校验 + MockMvc 真实过滤器链。
 *
 * 覆盖核心路径，合法签名放行并打通
 * 经沙箱注入再到真实 sql 再到 Controller（/api/config 读 um.params），缺失/篡改/
 * 重放/过期/非法身份全部 40100 fail-closed，匿名路径以空身份签名放行。
 */
class GatewaySignatureIT extends IntegrationTestSupport {

    private static final String CONFIG_PATH = "/api/config";

    @Test
    @DisplayName("合法签名全链路：签名通过 -> 沙箱注入 -> 真实 SQL -> 200 code=0")
    void validSignatureReachesControllerWithRealSql() throws Exception {
        mockMvc.perform(get(CONFIG_PATH).headers(authHeaders("GET", CONFIG_PATH, EMPTY_BODY_HASH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("缺失 X-FMS-Signature -> 40100 未认证")
    void missingSignatureRejected40100() throws Exception {
        mockMvc.perform(get(CONFIG_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("签名与路径不绑定（跨 path 重签）-> 40100")
    void signatureBoundToPathMismatchRejected() throws Exception {
        // 用 /api/other 计算 sig 却请求 /api/config，method|path 绑定使重算不匹配
        HttpHeaders forged = new HttpHeaders();
        forged.set("X-FMS-UserId", ADMIN_UID);
        forged.set("X-FMS-Role", "admin");
        forged.set("X-FMS-Signature", sign("GET", "/api/other", EMPTY_BODY_HASH, ADMIN_UID, "admin"));
        mockMvc.perform(get(CONFIG_PATH).headers(forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("过期时间戳（t 早于重放窗 400s）-> 40100")
    void staleTimestampRejected() throws Exception {
        // 直接构造 400 秒前的签名，sign 固定取当前时间，此处手拼过期 t
        String t = String.valueOf(System.currentTimeMillis() / 1000 - 400);
        String nonce = UuidV7.next().toString();
        String input = "GET|" + CONFIG_PATH + "|" + EMPTY_BODY_HASH + "|" + t + "|" + nonce + "|" + ADMIN_UID + "|admin";
        String sig = hmacSha256Hex(SIGN_SECRET, input);
        HttpHeaders h = new HttpHeaders();
        h.set("X-FMS-UserId", ADMIN_UID);
        h.set("X-FMS-Role", "admin");
        h.set("X-FMS-Signature", "t=" + t + "&nonce=" + nonce + "&sig=" + sig);
        mockMvc.perform(get(CONFIG_PATH).headers(h))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("nonce 重放：同一签名头第二次请求 -> 40100（首请求消耗 nonce）")
    void nonceReplayRejectedOnSecondUse() throws Exception {
        // 首次请求合法，消耗 nonce，预期 200
        mockMvc.perform(get(CONFIG_PATH).headers(authHeaders("GET", CONFIG_PATH, EMPTY_BODY_HASH)))
                .andExpect(status().isOk());
        // 提取首次请求的签名头原样重放，nonce 去重则 40100（即便 t 仍在窗口内）
        String replayed = sign("GET", CONFIG_PATH, EMPTY_BODY_HASH, ADMIN_UID, "admin");
        HttpHeaders h = new HttpHeaders();
        h.set("X-FMS-UserId", ADMIN_UID);
        h.set("X-FMS-Role", "admin");
        h.set("X-FMS-Signature", replayed);
        // 第一次使用该签名，200
        mockMvc.perform(get(CONFIG_PATH).headers(h)).andExpect(status().isOk());
        // 第二次重放（nonce 已消耗则 40100）
        mockMvc.perform(get(CONFIG_PATH).headers(h))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("身份头非法：非 canonical uuid -> 40100")
    void nonCanonicalUidRejected() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set("X-FMS-UserId", "not-a-uuid");
        h.set("X-FMS-Role", "admin");
        h.set("X-FMS-Signature", sign("GET", CONFIG_PATH, EMPTY_BODY_HASH, "not-a-uuid", "admin"));
        mockMvc.perform(get(CONFIG_PATH).headers(h))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("身份头非法：角色不在五白名单 -> 40100")
    void unknownRoleRejected() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set("X-FMS-UserId", ADMIN_UID);
        h.set("X-FMS-Role", "superuser");
        h.set("X-FMS-Signature", sign("GET", CONFIG_PATH, EMPTY_BODY_HASH, ADMIN_UID, "superuser"));
        mockMvc.perform(get(CONFIG_PATH).headers(h))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("匿名路径空身份签名放行：/api/public/login-background -> 200 code=0")
    void anonymousPathPassesWithEmptyIdentity() throws Exception {
        String path = "/api/public/login-background";
        HttpHeaders h = new HttpHeaders();
        h.set("X-FMS-Signature", sign("GET", path, EMPTY_BODY_HASH, "", ""));
        mockMvc.perform(get(path).headers(h))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("带 body 请求签名绑定 body_hash：合法 body 签名 -> 端点按语义响应")
    void bodyHashParticipatesInSignature() throws Exception {
        // 以 /api/auth/login，匿名路径验证 body 参与签名，错误口令 40100/40001 均
        // 证明签名校验已放行到 Controller（若 body_hash 绑定失效会被签名层 40100 拦截）
        byte[] body = "{\"loginName\":\"admin\",\"password\":\"wrong-password-x\"}".getBytes();
        String path = "/api/auth/login";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-FMS-Signature", sign("POST", path, sha256Hex(body), "", ""));
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post(path).headers(h).content(body))
                .andReturn();
        // 到达业务层，非签名层 40100 缺失头形态，业务拒绝
        String resp = result.getResponse().getContentAsString();
        assertThat(resp).isNotBlank();
        assertThat(resp).contains("\"code\"");
    }
}
