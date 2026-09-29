package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Utils.WeakPasswordFilter;
import net.castimage.fms_backend.Utils.WeakPasswordList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弱密码布隆过滤器单测，容量/误判率构造、全量命中、随机强密码未命中、误判率上界、
 * 大小写归一化、线程安全、自定义集合注入、禁用开关与 copy-on-write 增补。
 */
class WeakPasswordFilterTest {

    @Test
    void createAcceptsDefaultAndStrictParams() {
        // 容量/误判率参数交由 Guava 推导，默认档与严格档内置弱密码均命中
        WeakPasswordFilter defaultFilter = WeakPasswordFilter.create(1024, 0.01);
        assertTrue(defaultFilter.isWeak("123456"));
        WeakPasswordFilter strictFilter = WeakPasswordFilter.create(1024, 0.001);
        assertTrue(strictFilter.isWeak("123456"));
    }

    @Test
    void allBuiltinWeakPasswordsHit() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        assertTrue(WeakPasswordList.WEAK_PASSWORDS.size() >= 150, "内置集合应 ≥150 项");
        for (String weak : WeakPasswordList.WEAK_PASSWORDS) {
            assertTrue(filter.isWeak(weak), "内置弱密码应命中: " + weak);
        }
    }

    @Test
    void randomStrongPasswordsNeverRejected() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        Random rnd = new Random(42); // 确定性种子，
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        for (int i = 0; i < 2000; i++) {
            int len = 10 + rnd.nextInt(11); // 10-20 位，含大小写数字符号
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            assertFalse(filter.isWeak(sb.toString()), "强密码不应判弱: " + sb);
        }
    }

    @Test
    void falsePositiveRateBelowUpperBound() {
        // 布隆误判率上界 ≤ 2%，蒙特卡洛，留裕量于 1% 设计值，误判仅多一次 HashSet 查询，不拒绝
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        Random rnd = new Random(7);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        int samples = 5000;
        int falsePositives = 0;
        for (int i = 0; i < samples; i++) {
            int len = 10 + rnd.nextInt(11);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String pwd = sb.toString();
            if (filter.mightBeWeak(pwd) && !filter.isConfirmedWeak(pwd)) {
                falsePositives++;
            }
        }
        double fpRate = (double) falsePositives / samples;
        assertTrue(fpRate <= 0.02, "误判率应 ≤2%，实际 " + fpRate);
    }

    @Test
    void caseAndWhitespaceVariantsHit() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        assertTrue(filter.isWeak("PASSWORD"));       // 全大写变体
        assertTrue(filter.isWeak(" Password "));     // 首尾空白变体
        assertTrue(filter.isWeak("AdMiN123"));       // 混合大小写
        assertTrue(filter.isConfirmedWeak("123456"));
        assertFalse(filter.isConfirmedWeak("ADMINSTRATOR")); // 非精确命中，集合无此词，布隆误判被纠正
    }

    @Test
    void concurrentReadsMatchSingleThreadBaseline() throws Exception {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        List<String> samples = List.of("password", "123456", "Kx#9mQ2vLpZ4sD!2026", "zxcvbnm",
                "Wf!9kQ2vLpZ4sD", "iloveyou", "S3cur3-P@ss!42");
        List<Boolean> baseline = samples.stream().map(filter::isWeak).toList();

        int threads = 8;
        int iterations = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    boolean ok = true;
                    for (int i = 0; i < iterations; i++) {
                        for (int s = 0; s < samples.size(); s++) {
                            if (filter.isWeak(samples.get(s)) != baseline.get(s)) {
                                ok = false;
                            }
                        }
                    }
                    return ok;
                });
            }
            List<Future<Boolean>> results = pool.invokeAll(tasks);
            for (Future<Boolean> f : results) {
                assertTrue(f.get(), "并发结果应与单线程基线一致");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void customWeakSetInjection() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01, Set.of("customweak", "  padded  "));
        assertTrue(filter.isWeak("customweak"));
        assertTrue(filter.isWeak("CUSTOMWEAK")); // 归一化后命中
        assertTrue(filter.isWeak("padded"));     // 构建期 trim，
        assertFalse(filter.isWeak("password"));  // 自定义集合不含内置词
    }

    @Test
    void disabledSwitchAlwaysPasses() {
        WeakPasswordFilter disabled = WeakPasswordFilter.disabled();
        disabled.rejectIfWeak("123456"); // 不抛
        assertFalse(disabled.isWeak("123456"));
        assertFalse(disabled.mightBeWeak("123456"));
    }

    @Test
    void addIfAbsentCopyOnWrite() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01, Set.of());
        assertFalse(filter.isWeak("brandnewweak"));
        filter.addIfAbsent("brandnewweak");
        assertTrue(filter.isWeak("brandnewweak"));      // 增补后命中
        assertTrue(filter.isWeak("BRANDNEWWEAK"));      // 归一化一致
        filter.addIfAbsent("brandnewweak");             // 重复增补幂等
        assertTrue(filter.isWeak("brandnewweak"));
    }

    @Test
    void rejectIfWeakThrowsBadRequest40001() {
        WeakPasswordFilter filter = WeakPasswordFilter.create(1024, 0.01);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> filter.rejectIfWeak("123456"));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("常见弱密码"));
    }
}
