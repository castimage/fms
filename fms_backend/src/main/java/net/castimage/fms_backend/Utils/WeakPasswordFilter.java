package net.castimage.fms_backend.Utils;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Config.FmsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 弱密码过滤器，两段式判定，布隆预筛（Guava `BloomFilter`）加速 + 内置集合精确确认，
 * 设置密码路径统一走本类。布隆命中仅表示可能弱，必须经精确集合确认才拒绝，误判零误伤，
 * 构建期填充 + volatile 引用 copy-on-write 增补，查询路径无锁，登录并发安全。
 * 总开关 false 时恒放行，接入点无需条件分支。
 */
@Component
public class WeakPasswordFilter {

    /** 布隆预筛，含全部已归一化弱密码，volatile 支持 copy-on-write 替换发布 */
    private volatile BloomFilter<String> bloom;

    /** 归一化后的精确弱密码集合，运行期只读 */
    private volatile Set<String> weakSet = Set.of();

    /** 总开关，false 恒放行 */
    private volatile boolean enabled;

    /** 配置源，Spring 注入 */
    private final FmsProperties fmsProperties;

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

    /**
     * Spring 单例构造，本类另有静态工厂使用的私有构造器，多构造器并存时
     * 须显式标注注入入口，否则容器按无参构造器查找而失败。
     */
    @Autowired
    public WeakPasswordFilter(FmsProperties fmsProperties) {
        this.fmsProperties = fmsProperties;
    }

    private WeakPasswordFilter(int expectedInsertions, double falsePositiveRate,
                               Set<String> weakPasswords, boolean enabled) {
        this.fmsProperties = null;
        rebuild(expectedInsertions, falsePositiveRate, weakPasswords, enabled);
    }

    /**
     * Spring 单例构造，@PostConstruct 按 fms.security.weak-password.* 配置构建布隆与精确集合。
     */
    @PostConstruct
    void init() {
        FmsProperties.WeakPassword wp = fmsProperties.getSecurity().getWeakPassword();
        rebuild(wp.getExpectedInsertions(), wp.getFalsePositiveRate(),
                WeakPasswordList.WEAK_PASSWORDS, wp.isEnabled());
    }

    /**
     * 重建布隆与精确集合，参数由 Guava 按 expectedInsertions/falsePositiveRate 推导。
     */
    private void rebuild(int expectedInsertions, double falsePositiveRate,
                         Set<String> weakPasswords, boolean enabled) {
        if (expectedInsertions < 1) {
            throw new IllegalArgumentException("expectedInsertions 必须 ≥ 1，实际: " + expectedInsertions);
        }
        if (!(falsePositiveRate > 0 && falsePositiveRate < 1)) {
            throw new IllegalArgumentException("falsePositiveRate 必须在 (0,1) 开区间，实际: " + falsePositiveRate);
        }

        Set<String> normalized = normalizeAll(weakPasswords);
        BloomFilter<String> fresh = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), expectedInsertions, falsePositiveRate);
        if (enabled) {
            for (String w : normalized) {
                fresh.put(w);
            }
        }
        this.weakSet = normalized;
        this.bloom = fresh; // 先发布预筛与集合，最后置开关，读方见到 enabled=true 必见完整快照
        this.enabled = enabled;
    }

    /**
     * 布隆预检，弱密码判定第一道闸，对归一化，trim + 小写 Locale.ROOT 后的密码做
     * Guava 布隆探测，未命中即确定非弱直接返回 false，命中仅代表可能弱，须再经精确集合
     * 确认才拒绝，位残留只增误判不增漏判。禁用开关、null/空白密码恒返回 false。
     * 查询路径无锁，volatile 读布隆引用，登录并发安全。
     *
     * @param password 待检原始密码，未归一化
     * @return true 表示"可能弱"，需 isConfirmedWeak 精确确认，false 表示确定非弱
     */
    public boolean mightBeWeak(String password) {
        if (!enabled || password == null || password.isBlank()) {
            return false;
        }
        return bloom.mightContain(normalize(password));
    }

    /**
     * 精确确认，第二道闸，归一化，trim + 小写后查内置弱密码集合，布隆误判在此被纠正，
     * 只有归一化结果真实命中集合才算弱密码，对正常密码零误伤。集合为构建期/增补期生成的
     * 不可变快照（Set.copyOf），查询路径无锁，禁用开关下恒返回 false。
     *
     * @param password 待检原始密码
     * @return 归一化后精确命中内置弱密码集合才为 true
     */
    public boolean isConfirmedWeak(String password) {
        return enabled && weakSet.contains(normalize(password));
    }

    /**
     * 完整判定，布隆预检与精确确认合取，先无锁预筛，绝大部分正常密码在此直接放行，
     * 命中任一 0 位即 false 短路，仅预检命中的可疑项进入集合查询做最终裁决，兼顾
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
     * 运行期增补，copy-on-write，synchronized 克隆布隆与集合后替换 volatile 引用，只增不删，
     * 删除仅影响精确集合，位残留只增误判不增漏判。Guava 布隆克隆仅拷贝位数组，参数不变。
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
        BloomFilter<String> next = bloom.copy();
        next.put(normalized);
        this.weakSet = Set.copyOf(nextSet);
        this.bloom = next;
    }

    // 内部实现

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
}
