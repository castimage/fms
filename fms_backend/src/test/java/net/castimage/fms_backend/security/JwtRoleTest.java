package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.JwtRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JwtRole 角色短名与 pg 职责角色映射单元测试。
 */
class JwtRoleTest {

    @Test
    void fiveAllowedShortNamesParse() {
        assertEquals(JwtRole.OPERATOR, JwtRole.from("operator"));
        assertEquals(JwtRole.VIEWER, JwtRole.from("viewer"));
        assertEquals(JwtRole.AUDITOR, JwtRole.from("auditor"));
        assertEquals(JwtRole.REPORT, JwtRole.from("report"));
        assertEquals(JwtRole.ADMIN, JwtRole.from("admin"));
    }

    @Test
    void unknownOrBlankRoleReturnsNullFailClosed() {
        assertNull(JwtRole.from("root"));
        assertNull(JwtRole.from(""));
        assertNull(JwtRole.from("  "));
        assertNull(JwtRole.from(null));
        assertNull(JwtRole.from("Operator")); // 大小写敏感，未知即拒绝
    }

    @Test
    void pgRoleMappingMatchesSpec() {
        // operator 映射 fms_operator、viewer 映射 fms_viewer、auditor 映射 fms_auditor、
        // report 映射 fms_report、admin 映射 fms_operator（fms_admin 为 SUPERUSER 仅限初始化期）
        assertEquals("fms_operator", JwtRole.OPERATOR.pgRole());
        assertEquals("fms_viewer", JwtRole.VIEWER.pgRole());
        assertEquals("fms_auditor", JwtRole.AUDITOR.pgRole());
        assertEquals("fms_report", JwtRole.REPORT.pgRole());
        assertEquals("fms_operator", JwtRole.ADMIN.pgRole());
    }
}
