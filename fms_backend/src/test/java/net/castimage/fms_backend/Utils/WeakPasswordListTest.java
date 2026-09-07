package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置弱密码集合测试，清单非空且覆盖数字/英文/中文/姓名四类高频弱口令，
 * 集合为不可变快照，构建期铺入布隆位数组，防运行期被篡改。
 */
class WeakPasswordListTest {

    @Test
    void listNonEmptyWithVersionMetadata() {
        assertFalse(WeakPasswordList.WEAK_PASSWORDS.isEmpty());
        assertFalse(WeakPasswordList.VERSION.isBlank());
        assertFalse(WeakPasswordList.LAST_UPDATED.isBlank());
    }

    @Test
    void coversFourWeakPasswordCategories() {
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.contains("123456"));   // 数字
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.contains("password")); // 英文
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.contains("woaini"));   // 中文拼音
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.contains("zhangsan")); // 姓名拼音
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.contains("admin123"));
    }

    @Test
    void setIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> WeakPasswordList.WEAK_PASSWORDS.add("hacked"));
    }
}
