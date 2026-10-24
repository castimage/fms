package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 客户端 ip 提取与规范化：来源优先级、IPv6 等价写法归一、方括号/端口/zone 兼容与非法值兜底 */
class RequestUtilTest {

    private static final String V6 = "2001:db8:0:0:0:0:0:1";

    @Test
    void xRealIpWinsOverForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        req.addHeader("X-Real-IP", "2001:db8::1");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        req.setRemoteAddr("10.0.0.8");
        assertEquals(V6, RequestUtil.getClientIp(req));
    }

    @Test
    void forwardedForUsesLastSegment() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 2001:db8::2");
        req.setRemoteAddr("10.0.0.8");
        assertEquals("2001:db8:0:0:0:0:0:2", RequestUtil.getClientIp(req));
    }

    @Test
    void remoteAddrFallback() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        req.setRemoteAddr("::1");
        assertEquals("0:0:0:0:0:0:0:1", RequestUtil.getClientIp(req));
    }

    @Test
    void ipv6EquivalentSpellingsNormalizeToSameValue() {
        assertEquals(V6, RequestUtil.sanitizeIp("2001:db8::1"));
        assertEquals(V6, RequestUtil.sanitizeIp("2001:0DB8:0:0:0:0:0:1"));
        assertEquals(V6, RequestUtil.sanitizeIp(" 2001:db8::1 "));
    }

    @Test
    void ipv4MappedNormalizesToIpv4() {
        assertEquals("192.0.2.1", RequestUtil.sanitizeIp("::ffff:192.0.2.1"));
    }

    @Test
    void bracketAndPortFormsAccepted() {
        assertEquals(V6, RequestUtil.sanitizeIp("[2001:db8::1]:443"));
        assertEquals(V6, RequestUtil.sanitizeIp("[2001:db8::1]"));
        assertEquals("192.0.2.1", RequestUtil.sanitizeIp("192.0.2.1:443"));
        assertEquals("192.0.2.1", RequestUtil.sanitizeIp("\"192.0.2.1\""));
    }

    @Test
    void zoneSuffixStripped() {
        assertEquals("fe80:0:0:0:0:0:0:1", RequestUtil.sanitizeIp("fe80::1%eth0"));
        assertEquals("fe80:0:0:0:0:0:0:1", RequestUtil.sanitizeIp("[fe80::1%eth0]:443"));
    }

    @Test
    void malformedValuesFallBackToUnknown() {
        for (String ip : new String[]{"::::", "1:2:3", "dead:beef", "192.0.2.1:abc",
                "example.com", "[2001:db8::1]junk", "[2001:db8::1]:abc", "[2001:db8::1", null, "", "   "}) {
            assertEquals("0.0.0.0", RequestUtil.sanitizeIp(ip), "非法值应兜底: " + ip);
        }
    }
}
