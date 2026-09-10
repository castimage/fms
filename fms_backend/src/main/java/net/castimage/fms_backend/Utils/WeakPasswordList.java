package net.castimage.fms_backend.Utils;

import java.util.List;
import java.util.Set;

/**
 * 内置弱密码集合（175 项，中英文，统一归一化小写存储；查询/构建均由 WeakPasswordFilter 归一化后比对，杜绝大小写变体绕过）。
 *
 * 维护元数据：版本 1.0 / 更新 2026-08-09 / 来源基线：SecLists 10k-most-common 前 200、
 * RockYou 高频弱口令、国内常见弱密码清单（数字谐音 + 祝福语 + 拼音姓名）。增补条目直接合入本清单，
 * 服务重启后位数组自动重建（fms.security.weak-password.expected-insertions=1024 留有 ~5 倍余量）。
 */
public final class WeakPasswordList {

    /** 集合版本号（合入增补时 +1） */
    public static final String VERSION = "1.0";

    /** 最近更新日期 */
    public static final String LAST_UPDATED = "2026-08-09";

    /** 归一化（小写）后的弱密码集合，构建期铺入布隆位数组 */
    public static final Set<String> WEAK_PASSWORDS = Set.copyOf(List.of(
            // ---- 数字/键盘序（40 项） ----
            "123456", "12345678", "123456789", "1234567890", "1234567", "12345", "1234",
            "111111", "000000", "666666", "888888", "999999", "654321", "123321", "5201314",
            "1314520", "123123", "112233", "121212", "147258", "159357",
            "520520", "131313", "7758521", "741852963",
            "123qwe", "qwe123", "1qaz2wsx", "1q2w3e4r", "q1w2e3r4t5", "qwerty", "qwertyuiop",
            "asdfgh", "asdfghjkl", "zxcvbn", "zxcvbnm", "qazwsx", "zaq12wsx", "12qwaszx", "123qwerty",
            // ---- 英文弱密码（53 项） ----
            "password", "password1", "password123", "password1234", "passw0rd",
            "p@ssw0rd", "abc123", "abcd1234", "admin", "admin123", "admin888",
            "administrator", "root", "root123", "test", "test123", "letmein",
            "welcome", "welcome1", "iloveyou", "iloveu", "love", "princess",
            "dragon", "monkey", "shadow", "superman", "batman",
            "football", "baseball", "soccer", "hunter", "sunshine", "michael", "jennifer",
            "qwerty123", "secret", "hello", "freedom", "whatever",
            "trustno1", "abc123456", "admin1234", "master",
            "computer", "internet", "hello123", "love123", "hunter2", "killer", "cookie",
            "liverpool", "google",
            // ---- 中文弱密码（拼音/谐音/祝福语，41 项） ----
            "mima", "kouling", "woaini", "woainima", "woshishui", "nihao123", "caonima",
            "wodemima", "zhongguo", "xiexie", "nihao", "haoren", "mima123", "aini",
            "qin", "qinqin", "laogong", "laopo", "aiqing", "yuanfen", "yongyuan",
            "baobei", "baobao", "shengrikuaile", "gongxifacai", "wanshiruyi",
            "xinxiangshicheng", "caiyuangungun", "yishengpingan", "xingfumeiman",
            "meinv", "shuai", "xingfu", "jixiang", "longma", "wangchao", "shenhua",
            "mengxiang", "jiayou", "gongzhu", "wangzi",
            // ---- 高频姓名/拼音（41 项） ----
            "zhang", "wang", "li", "liu", "chen", "yang", "zhao", "huang", "wu", "xu",
            "sun", "ma", "zhu", "hu", "guo", "lin", "gao", "luo", "tian", "lihua",
            "xiaoming", "xiaohong", "wang123", "zhang123", "li123",
            "chen123", "yang123", "zhao123", "liu123", "wangwei", "zhangwei", "liwei",
            "wangfang", "zhangfang", "liqiang", "wangqiang", "zhangsan", "lisi",
            "wangwu", "zhaoliu", "sunqi"
    ));

    private WeakPasswordList() { // 工具类禁止实例化，
    }
}
