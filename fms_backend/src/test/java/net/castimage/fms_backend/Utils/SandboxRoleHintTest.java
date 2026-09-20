package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 沙箱角色提示测试，匿名请求按端点语义注入最小职责角色，PUBLIC_READ 公开读口 /
 * INTERNAL_OPERATOR 内部投递 / BACKGROUND_APP 后台定时任务，请求结束由 Controller
 * 或调度方法 finally 清理。
 */
class SandboxRoleHintTest {

    @AfterEach
    void tearDown() {
        SandboxRoleHint.clear();
    }

    @Test
    void initiallyNull() {
        assertNull(SandboxRoleHint.get());
    }

    @Test
    void setAndGetRoundTrip() {
        SandboxRoleHint.set(SandboxRoleHint.Hint.PUBLIC_READ);
        assertEquals(SandboxRoleHint.Hint.PUBLIC_READ, SandboxRoleHint.get());
        SandboxRoleHint.set(SandboxRoleHint.Hint.INTERNAL_OPERATOR);
        assertEquals(SandboxRoleHint.Hint.INTERNAL_OPERATOR, SandboxRoleHint.get());
    }

    @Test
    void clearResetsToNull() {
        SandboxRoleHint.set(SandboxRoleHint.Hint.PUBLIC_READ);
        SandboxRoleHint.clear();
        assertNull(SandboxRoleHint.get());
    }

    @Test
    void backgroundAppRoundTrip() {
        SandboxRoleHint.set(SandboxRoleHint.Hint.BACKGROUND_APP);
        assertEquals(SandboxRoleHint.Hint.BACKGROUND_APP, SandboxRoleHint.get());
    }
}
