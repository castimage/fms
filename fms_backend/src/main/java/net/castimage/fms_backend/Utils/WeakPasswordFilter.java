package net.castimage.fms_backend.Utils;

import jakarta.annotation.PostConstruct;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.config.FmsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 弱密码布隆过滤器，自实现 long 位数组 + 双哈希，免 Guava，设置密码路径，注册/重置/找回预检加速，
 * 布隆命中仅表示"可能弱"，必须经内置集合精确确认才拒绝，误判零误伤，构建期填充 + volatile 引用
 * copy-on-write 增补，查询路径无锁，登录并发安全。总开关 false 时恒放行，接入点无需条件分支。
 */
@Component
public class WeakPasswordFilter {

    /** FNV-1a 64 位偏移基 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    /** FNV-1a 64 位质数 */
    private static final long FNV_PRIME = 0x100000001b3L;

    /** Murmur3 简化变体种子 */
    private static final int MURMUR_SEED = 0x9747b28c;

    /** 位数组，long 对齐，volatile 支持 copy-on-write 替换发布 */
    private volatile long[] bits = new long[0];

    /** 位数组大小（bit） */
    private volatile int m;

    /** 哈希函数个数 */
    private volatile int k;

    /** 归一化后的精确弱密码集合，运行期只读 */
    private volatile Set<String> weakSet = Set.of();

    /** 总开关，false 恒放行 */
    private volatile boolean enabled;

    @Autowired
    private FmsProperties fmsProperties;

    /**
     * 测试/程序化构造，按参数构建，内置集合。
     */
    public static WeakPasswordFilter create(int expectedInsertions, double falsePositiveRate) {
        return new WeakPasswordFilter(expectedInsertions, falsePositiveRate,
                WeakPasswordList.WEAK_PASSWORDS, true);
    }

    /**
     * 测试/程序化构造，注入自定义弱密码集合。
     */
    public static WeakPasswordFilter create(int expectedInsertions, double falsePositiveRate,
                                            Set<String> weakPasswords) {
        return new WeakPasswordFilter(expectedInsertions, falsePositiveRate, weakPasswords, true);
    }

    /**
     * 禁用模式，恒放行，供开关关闭时注入。
     */
    public static WeakPasswordFilter disabled() {
        return new WeakPasswordFilter(1024, 0.01, Set.of(), false);
    }

    private WeakPasswordFilter(int expectedInsertions, double falsePositiveRate,
                               Set<String> weakPasswords, boolean enabled) {
        this.fmsProperties = null;
        rebuild(expectedInsertions, falsePositiveRate, weakPasswords, enabled);
    }

    /**
     * Spring 单例构造，@PostConstruct 按 fms.security.weak-password.* 配置构建位数组。
     */
    @PostConstruct
    void init() {
        FmsProperties.WeakPassword wp = fmsProperties.getSecurity().getWeakPassword();
        rebuild(wp.getExpectedInsertions(), wp.getFalsePositiveRate(),
                WeakPasswordList.WEAK_PASSWORDS, wp.isEnabled());
    }

    /**
     * 重建位数组与精确集合（m = ceil(-n·ln(p)/(ln2)²)，k = round(m/n·ln2)，按公式实算不写死）。
     */
    private void rebuild(int expectedInsertions, double falsePositiveRate,
                         Set<String> weakPasswords, boolean enabled) {
        if (expectedInsertions < 1) {
            throw new IllegalArgumentException("expectedInsertions 必须 ≥ 1，实际: " + expectedInsertions);
        }
        if (!(falsePositiveRate > 0 && falsePositiveRate < 1)) {
            throw new IllegalArgumentException("falsePositiveRate 必须在 (0,1) 开区间，实际: " + falsePositiveRate);
        }

        this.enabled = enabled;
        this.weakSet = normalizeAll(weakPasswords); // 归一化，trim+小写后不可变快照，与查询侧规则一致，
        this.m = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate)
                / (Math.log(2) * Math.log(2)));
        this.k = Math.max(1, (int) Math.round((double) m / expectedInsertions * Math.log(2)));
        long[] fresh = new long[(m + 63) / 64];
        if (enabled) {
            for (String w : weakSet) {
                setBits(fresh, w);
            }
        }
        this.bits = fresh; // volatile 尾部写发布，查询路径无锁
    }

    /**
     * 布隆预检，弱密码判定的第一道闸，对归一化，trim + 小写 Locale.ROOT 后的密码做
     * k 次双哈希索引探测（index_i = floorMod(h1 + i·h2, m)），任一索引位为 0 即确定非弱
     * 密码，直接返回 false，全部命中仅代表"可能弱"，必须再经精确集合确认才拒绝，位残留
     * 只增误判、不增漏判。禁用开关、空位数组、null/空白密码恒返回 false。查询路径无锁，volatile 读位数组引用，登录并发安全。
     *
     * <p>语义细节，双哈希 h2 强制奇数位（| 1L），保证与 m 互质，索引序列
     * h1 + i·h2 遍历不塌缩，标准布隆双哈希退化问题，h1/h2 分别取 FNV-1a 与
     * Murmur3 简化变体，两个独立哈希族降低索引碰撞相关度。归一化规则，trim +
     * 小写与构建期 setBits 完全一致，查询与置位必须同规则，否则同值不同索引，
     * 预检会漏判。单次查询 O(k) 位探测，无锁无分配，字节数组除外。
     *
     * @param password 待检原始密码，未归一化
     * @return true 表示"可能弱"，需 isConfirmedWeak 精确确认，false 表示确定非弱
     */
    public boolean mightBeWeak(String password) {
        if (!enabled || m <= 0 || password == null || password.isBlank()) {
            return false;
        }

        byte[] bytes = normalize(password).getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a64(bytes);
        long h2 = murmur3mix32(bytes) | 1L; // 保奇数，防与 m 不互质索引塌缩
        long[] current = bits;
        for (int i = 0; i < k; i++) {
            long idx = Math.floorMod(h1 + (long) i * h2, m);
            if ((current[(int) (idx >>> 6)] & (1L << (idx & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 精确确认，第二道闸，归一化，trim + 小写后查内置弱密码集合，布隆预检的误判在
     * 此被纠正，只有归一化结果真实命中集合才算弱密码，对正常密码零误伤。集合为构建期/
     * 增补期生成的不可变快照（Set.copyOf），查询路径无锁，禁用开关下恒返回 false。
     *
     * <p>语义要点，这是最终裁决，唯一拒绝依据，布隆命中只是预筛，集合与位数组
     * 由同一份归一化规则构建（normalizeAll），大小写/首尾空白变体（"Password"、
     * "password "）在两侧都被规整为同一形态，不存在"预检命中但集合漏掉"的路径。
     * 运行期增补（addIfAbsent）同步更新集合与位数组，快照发布经 volatile 引用，
     * 查询方读到新或旧快照均自洽。
     *
     * @param password 待检原始密码
     * @return 归一化后精确命中内置弱密码集合才为 true
     */
    public boolean isConfirmedWeak(String password) {
        return enabled && weakSet.contains(normalize(password));
    }

    /**
     * 完整判定，布隆预检与精确确认合取，先 O(k) 无锁预筛，绝大部分正常密码在此直接放行，命中任一 0 位即 false，短路，仅预检命中的可疑项进入集合查询做最终裁决，兼顾
     * 高吞吐与零误伤。注册/重置/找回等所有弱密码检查点统一走本方法。
     *
     * @param password 待检原始密码
     * @return 预检命中且精确集合确认命中才为 true
     */
    public boolean isWeak(String password) {
        return mightBeWeak(password) && isConfirmedWeak(password);
    }

    /**
     * 弱密码拒绝入口，注册/创建用户/重置/找回等所有写密码路径统一调用，isWeak 确认
     * 命中即抛 BadRequestException（40001「密码为常见弱密码」），命中不落库，总开关
     * 关闭时恒通过，接入点无需条件分支。调用方应先做长度等基础校验，本方法只负责弱密码
     * 语义。
     *
     * @param password 待写库的原始密码
     * @throws BadRequestException 确认命中弱密码集合时抛出，40001
     */
    public void rejectIfWeak(String password) {
        if (isWeak(password)) {
            throw new BadRequestException("密码为常见弱密码，请更换后重试");
        }
    }

    /**
     * 运行期增补，copy-on-write，synchronized 克隆重建并替换 volatile 引用，只增不删，删除仅影响精确集合，位残留只增误判不增漏判。
     */
    public synchronized void addIfAbsent(String password) {
        if (!enabled) {
            return;
        }

        String normalized = normalize(password);
        if (weakSet.contains(normalized)) {
            return;
        }

        Set<String> nextSet = new HashSet<>(weakSet);
        nextSet.add(normalized);
        long[] next = bits.clone();
        setBits(next, normalized);
        this.weakSet = Set.copyOf(nextSet);
        this.bits = next;
    }

    /** 位数组大小，bit，测试/监控用 */
    public int bitSize() {
        return m;
    }

    /** 哈希函数个数，测试/监控用 */
    public int hashCount() {
        return k;
    }

    // ---------------- 内部实现 ----------------

    /** 归一化（trim + 小写 Locale.ROOT，防 "Password"/"PASSWORD " 变体绕过） */
    private static String normalize(String password) {
        return password == null ? "" : password.trim().toLowerCase(Locale.ROOT);
    }

    /** 集合整体归一化，trim + 小写 + 跳过空白项，不可变包装，存储侧与查询侧规则一致 */
    private static Set<String> normalizeAll(Set<String> passwords) {
        Set<String> normalized = new HashSet<>();
        for (String p : passwords) {
            if (p != null && !p.isBlank()) {
                normalized.add(normalize(p));
            }
        }
        return Set.copyOf(normalized);
    }

    /** 置位，双哈希索引 index_i = floorMod(h1 + i·h2, m)（实例 m/k 按构建参数实算） */
    private void setBits(long[] target, String normalized) {
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a64(bytes);
        long h2 = murmur3mix32(bytes) | 1L;
        for (int i = 0; i < k; i++) {
            long idx = Math.floorMod(h1 + (long) i * h2, m);
            target[(int) (idx >>> 6)] |= 1L << (idx & 63);
        }
    }

    /** FNV-1a 64 位，逐 utf-8 字节 */
    private static long fnv1a64(byte[] data) {
        long h = FNV_OFFSET_BASIS;
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= FNV_PRIME;
        }
        return h;
    }

    /** Murmur3 简化变体，32 位，种子 0x9747b28c 逐字节累加 + 尾数雪崩混合，与 FNV-1a 独立 */
    private static long murmur3mix32(byte[] data) {
        int h = MURMUR_SEED;
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & 0xFFFFFFFFL;
    }
}
