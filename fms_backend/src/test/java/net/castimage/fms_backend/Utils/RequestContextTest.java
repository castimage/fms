package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求身份上下文测试，ThreadLocal 存取/清理、认证判定、三道角色预检闸口
 * （requireWriteRole/requireAdminRole/requireAuditRole）与不可变快照防御。
 */
class RequestContextTest {

    private static final UUID UID = UuidV7.next();
    private static final UUID COMPANY = UuidV7.next();

    @AfterEach
    void tearDown() {
        RequestContext.clear(); // ThreadLocal 跨测试清理，防串扰
    }

    @Test
    void initiallyNoContextUnauthenticated() {
        assertNull(RequestContext.get());
        assertFalse(RequestContext.authenticated());
        assertNull(RequestContext.userId());
        assertNull(RequestContext.role());
        assertNull(RequestContext.traceId());
    }

    @Test
    void setPopulatesFieldsAndClearResets() {
        RequestContext ctx = new RequestContext(UID, JwtRole.OPERATOR, null, null, "trace-1");
        RequestContext.set(ctx);
        assertTrue(RequestContext.authenticated());
        assertEquals(UID, RequestContext.userId());
        assertEquals(JwtRole.OPERATOR, RequestContext.role());
        assertEquals("trace-1", RequestContext.traceId());

        RequestContext.clear();
        assertNull(RequestContext.get());
        assertFalse(RequestContext.authenticated());
    }

    @Test
    void anonymousContextUnauthenticated() {
        RequestContext.set(new RequestContext(null, null, null, null, "t"));
        assertFalse(RequestContext.authenticated());
    }

    @Test
    void requireUserIdThrows40100WhenUnauthenticated() {
        assertThrows(UnauthorizedException.class, RequestContext::requireUserId);
        RequestContext.set(new RequestContext(null, null, null, null, "t"));
        assertThrows(UnauthorizedException.class, RequestContext::requireUserId);
    }

    @Test
    void requireUserIdReturnsIdWhenAuthenticated() {
        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, null, null, "t"));
        assertEquals(UID, RequestContext.requireUserId());
    }

    /** 写角色闸，只读三角色拒绝、operator/admin 放行、未认证 40100 */
    @Test
    void requireWriteRoleRoleMatrix() {
        RequestContext.set(new RequestContext(UID, JwtRole.VIEWER, null, null, "t"));
        assertThrows(ForbiddenException.class, RequestContext::requireWriteRole);
        RequestContext.set(new RequestContext(UID, JwtRole.AUDITOR, null, null, "t"));
        assertThrows(ForbiddenException.class, RequestContext::requireWriteRole);
        RequestContext.set(new RequestContext(UID, JwtRole.REPORT, null, null, "t"));
        assertThrows(ForbiddenException.class, RequestContext::requireWriteRole);

        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, null, null, "t"));
        RequestContext.requireWriteRole();
        RequestContext.set(new RequestContext(UID, JwtRole.ADMIN, null, null, "t"));
        RequestContext.requireWriteRole();
    }

    @Test
    void requireAdminRoleOnlyAdminAllowed() {
        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, null, null, "t"));
        assertThrows(ForbiddenException.class, RequestContext::requireAdminRole);
        RequestContext.set(new RequestContext(UID, JwtRole.ADMIN, null, null, "t"));
        RequestContext.requireAdminRole();
    }

    @Test
    void requireAuditRoleAuditorAndAdminAllowed() {
        RequestContext.set(new RequestContext(UID, JwtRole.VIEWER, null, null, "t"));
        assertThrows(ForbiddenException.class, RequestContext::requireAuditRole);
        RequestContext.set(new RequestContext(UID, JwtRole.AUDITOR, null, null, "t"));
        RequestContext.requireAuditRole();
        RequestContext.set(new RequestContext(UID, JwtRole.ADMIN, null, null, "t"));
        RequestContext.requireAuditRole();
    }

    @Test
    void nullRoleSameAsUnauthenticated() {
        RequestContext.set(new RequestContext(null, null, null, null, "t"));
        assertThrows(UnauthorizedException.class, RequestContext::requireWriteRole);
        assertThrows(UnauthorizedException.class, RequestContext::requireAdminRole);
        assertThrows(UnauthorizedException.class, RequestContext::requireAuditRole);
    }

    /** 防御拷贝，构造后修改外部 List 不影响上下文快照，静态访问器读 ThreadLocal，须先 set */
    @Test
    void orgIdsDeptIdsImmutableSnapshot() {
        List<UUID> orgs = new ArrayList<>(List.of(COMPANY));
        List<UUID> depts = new ArrayList<>(List.of(UuidV7.next()));
        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, orgs, depts, "t"));
        orgs.add(UuidV7.next());
        depts.add(UuidV7.next());

        assertEquals(1, RequestContext.orgIds().size());
        assertEquals(1, RequestContext.deptIds().size());
        assertThrows(UnsupportedOperationException.class, () -> RequestContext.orgIds().add(UuidV7.next()));
    }

    @Test
    void nullScopeSnapshotStaysNull() {
        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, null, null, "t"));
        assertNull(RequestContext.orgIds());
        assertNull(RequestContext.deptIds());
    }
}
