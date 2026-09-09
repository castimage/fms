package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeakPasswordFilter 布隆过滤器单元测试，
 * 容量/误判率构造、全量命中/强密码未命中、误判率上界（蒙特卡洛 ≤ 2×p）、两段式确认零误伤、
 * 大小写归一化、自定义集合注入、运行时增补（copy-on-write）、8 线程并发查询、非法参数拒绝、禁用开关。
 */
class WeakPasswordFilterTest {

    /** 强密码字符集，含大小写/数字/符号 */
    private static final String STRONG_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{};:,.<>?";

    /** 确定性随机强密码，长度 12-20，含全字符类，固定 seed 保证可复现 */
    private static String strongPassword(Random random) {
        int len = 12 + random.nextInt(9);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(STRONG_CHARS.charAt(random.nextInt(STRONG_CHARS.length())));
        }
        return sb.toString();
    }

    @Test
    void createAcceptsExpectedInsertionsAndFpp() {
        // 容量/误判率参数交由 Guava 推导，行为验证，默认档与严格档内置弱密码均命中
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        assertTrue(f.isWeak("123456"));
        WeakPasswordFilter g = WeakPasswordFilter.create(1024, 0.001);
        assertTrue(g.isWeak("123456"));
    }

    @Test
    void builtinListSizeInRangeAndAllHit() {
        int size = WeakPasswordList.WEAK_PASSWORDS.size();
        assertTrue(size >= 100 && size <= 200, "内置弱密码集合规模应在 100-200，实际 " + size);
        // 存储侧归一化不变量，全小写、无首尾空白
        for (String p : WeakPasswordList.WEAK_PASSWORDS) {
            assertEquals(p, p.trim().toLowerCase(Locale.ROOT), "集合元素应归一化存储: " + p);
        }
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        for (String p : WeakPasswordList.WEAK_PASSWORDS) {
            assertTrue(f.isWeak(p), "内置条目应全量命中: " + p);
        }
    }

    @Test
    void strongPasswordsNeverHit() {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        Random random = new Random(42); // 固定 seed，确定性随机
        for (int i = 0; i < 2000; i++) {
            String pwd = strongPassword(random);
            assertFalse(f.isConfirmedWeak(pwd), "随机强密码不应在精确集合内: " + pwd);
            assertFalse(f.isWeak(pwd), "随机强密码不应判定为弱: " + pwd);
        }
    }

    @Test
    void falsePositiveRateBelowUpperBoundWithTwoStageConfirmation() {
        // 空集合注入，插入 n=1024 个随机串后，对 2000 个全新随机串测布隆误判率
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01, Set.of());
        Random random = new Random(7);
        for (int i = 0; i < 1024; i++) {
            f.addIfAbsent("seed-a-" + Long.toHexString(random.nextLong()) + "-" + i);
        }
        int bloomHits = 0;
        for (int i = 0; i < 2000; i++) {
            String probe = "seed-b-" + Long.toHexString(random.nextLong()) + "-" + i; // 前缀与插入批互斥，必不在集合
            assertFalse(f.isConfirmedWeak(probe), "探测串不应在精确集合内");
            if (f.mightBeWeak(probe)) {
                bloomHits++;
            }
            assertFalse(f.isWeak(probe), "两段式：布隆误判不得被判定为弱密码（零误伤）");
        }
        double rate = bloomHits / 2000.0;
        // 理论 p=1%，插入满 n 时实际约 0.9%，蒙特卡洛留裕量取 2×p（规格 ）
        assertTrue(rate <= 0.02, "误判率 " + rate + " 应 ≤ 2×p");
    }

    @Test
    void normalizationHandlesCaseAndWhitespace() {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        assertTrue(f.isWeak("PASSWORD"));
        assertTrue(f.isWeak(" Password "));
        assertTrue(f.isWeak("password123"));
        assertTrue(f.isWeak("P@SSW0RD"));
        assertTrue(f.isConfirmedWeak(" Password123 "));
        assertTrue(f.isWeak(" 123456 "));
    }

    @Test
    void falsePositiveIsRejectedByExactSetCheck() {
        // 高误判率档下穷举探测，凡命中预筛但不在精确集合的样本，isWeak 恒 false，拒绝路径零误伤
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.5, Set.of("baseword"));
        assertTrue(f.isWeak("baseword"));
        for (int i = 0; i < 5000; i++) {
            String candidate = "collide-" + i;
            assertFalse(f.isConfirmedWeak(candidate), "探测串不应在精确集合内");
            if (f.mightBeWeak(candidate)) {
                assertFalse(f.isWeak(candidate), "预筛误判不得触发拒绝");
            }
        }
    }

    @Test
    void customWeakSetInjection() {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01, Set.of("myweak", "myweak123", "AbC xYz"));
        assertTrue(f.isWeak("myweak"));
        assertTrue(f.isWeak("MYWEAK"));
        assertTrue(f.isWeak(" AbC xYz "), "自定义集合元素同样归一化存储");
        assertFalse(f.isWeak("somethingelse"));
    }

    @Test
    void rejectIfWeakThrowsBadRequest40001() {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        BadRequestException ex = assertThrows(BadRequestException.class, () -> f.rejectIfWeak("123456"));
        assertEquals(40001, ex.getCode(), "弱密码拒绝应抛 40001");
        assertTrue(ex.getMessage().contains("常见弱密码"), "拒绝消息应含「常见弱密码」");
        f.rejectIfWeak("Kx#9mQ2vLpZ4sD!2026"); // 强密码不抛，
    }

    @Test
    void addIfAbsentTakesEffectAndIdempotent() {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        assertFalse(f.isWeak("brandnewweak123"));
        f.addIfAbsent("brandnewweak123");
        assertTrue(f.mightBeWeak("brandnewweak123"));
        assertTrue(f.isWeak("brandnewweak123"));
        assertTrue(f.isWeak("BrandNewWeak123"), "增补条目同样归一化判定");
        f.addIfAbsent("BRANDNEWWEAK123"); // 重复增补幂等，不抛错不破坏既有位
        assertTrue(f.isWeak("brandnewweak123"));
        assertTrue(f.isWeak("123456"), "内置命中不受增补影响");
    }

    @Test
    void threadSafeConcurrentQueriesMatchBaseline() throws InterruptedException {
        WeakPasswordFilter f = WeakPasswordFilter.create(1024, 0.01);
        // 混合样本，前 30 条内置弱密码 + 170 条确定性随机强密码
        List<String> samples = new ArrayList<>(WeakPasswordList.WEAK_PASSWORDS.stream().limit(30).toList());
        Random random = new Random(1);
        for (int i = 0; i < 170; i++) {
            samples.add(strongPassword(random));
        }
        boolean[] baseline = new boolean[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            baseline[i] = f.isWeak(samples.get(i));
        }
        int threads = 8;
        int rounds = 500; // 200 样本 × 500 轮 = 每线程 10 万次查询
        AtomicInteger mismatch = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int round = 0; round < rounds; round++) {
                    for (int i = 0; i < samples.size(); i++) {
                        if (f.isWeak(samples.get(i)) != baseline[i]) {
                            mismatch.incrementAndGet();
                        }
                    }
                }
                done.countDown();
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "8 线程并发查询应在 60s 内完成");
        pool.shutdown();
        assertEquals(0, mismatch.get(), "并发结果应与单线程基线一致");
    }

    @Test
    void invalidParametersRejected() {
        assertThrows(IllegalArgumentException.class, () -> WeakPasswordFilter.create(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> WeakPasswordFilter.create(-1, 0.01));
        assertThrows(IllegalArgumentException.class, () -> WeakPasswordFilter.create(1024, 0));
        assertThrows(IllegalArgumentException.class, () -> WeakPasswordFilter.create(1024, 1));
        assertThrows(IllegalArgumentException.class, () -> WeakPasswordFilter.create(1024, -0.5));
    }

    @Test
    void disabledAlwaysAllows() {
        WeakPasswordFilter f = WeakPasswordFilter.disabled();
        assertFalse(f.mightBeWeak("123456"));
        assertFalse(f.isConfirmedWeak("123456"));
        assertFalse(f.isWeak("123456"));
        f.rejectIfWeak("123456"); // 总开关关闭时恒放行，接入点无需条件分支
    }
}
