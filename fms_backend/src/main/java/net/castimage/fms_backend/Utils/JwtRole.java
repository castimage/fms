package net.castimage.fms_backend.Utils;

/** JWT 角色短名枚举（X-FMS-Role 头五选一）：from 未知角色返回 null 由调用方按 40100 拒绝，pgRole 白名单映射绝不受请求注入 */
public enum JwtRole {

    /** 录入/记账/审批操作类 */
    OPERATOR("operator"),
    /** 只读查询 */
    VIEWER("viewer"),
    /** 审计只读 */
    AUDITOR("auditor"),
    /** 报表只读 */
    REPORT("report"),
    /** 管理员（PG 层职责角色同样为 fms_operator，全量数据范围由 scope 注入保证） */
    ADMIN("admin");

    private final String shortName;

    JwtRole(String shortName) {
        this.shortName = shortName;
    }

    /** 角色短名（JWT Claims.role / X-FMS-Role 头值） */
    public String shortName() {
        return shortName;
    }

    /**
     * 按短名解析 JWT 角色：仅接受 X-FMS-Role 头五个白名单短名（operator/viewer/auditor/
     * report/admin）；null、空白、未知值一律返回 null，由调用方按 40100 拒绝——fail-closed
     * 语义，绝不猜测或降级默认。字符串常量 switch 分派免 values() 线性扫描。用于网关签名
     * 头身份采信与服务端角色解析两处。
     *
     * 安全语义：这是 X-FMS-Role 头值进入系统的唯一解析点（GatewaySignatureFilter
     * 采信身份时调用），未知字符串返回 null 即触发 40100 拒绝——任意字符串不得映射到
     * 任何有效角色，杜绝"未知角色值经 X-FMS-Role 注入后 SET ROLE 落空或误映射"的
     * 提权路径。白名单与网关 auth.allowed_roles 配置保持一致（五角色），新增角色须
     * 两端同步。大小写敏感：头值为网关注入的标准短名，客户端伪造的大小写变体不命中。
     *
     * @param value 角色短名（JWT Claims.role 或 X-FMS-Role 头值）
     * @return 匹配的 JwtRole 枚举；非法输入（null/空白/未知）为 null
     */
    public static JwtRole from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // 字符串常量 switch 分派，免 values() 线性扫描，
        return switch (value) {
            case "operator" -> OPERATOR;
            case "viewer" -> VIEWER;
            case "auditor" -> AUDITOR;
            case "report" -> REPORT;
            case "admin" -> ADMIN;
            default -> null;
        };
    }

    /**
     * 角色短名映射 PG 职责角色：operator/admin 并入 fms_operator（admin 的全量数据范围
     * 由 scope 注入保证），viewer/auditor/report 一一对应。映射为编译期白名单常量，返回
     * 值直接拼入 SET ROLE 语句（SandboxSessionInterceptor），绝不接受请求输入，杜绝
     * 角色注入。
     *
     * 映射语义：PG 侧只有四个职责角色（fms_operator/fms_viewer/fms_auditor/
     * fms_report），五枚举收敛为四值；admin 与 operator 共用 fms_operator 登录角色，
     * 管理员的"全量数据可见"不由角色区分，而由 RLS 策略按 auth.set_session 预载的
     * scope（admin 常被授予全公司/全部门范围）生效——因此 X-FMS-Role 头即使被替换为
     * admin，若 scope 未授予照样只读有限范围，双层防御。返回值经 switch 穷举（无
     * default 分支，新增枚举会编译失败强制补齐映射）。
     *
     * @return PG 职责角色名（fms_operator/fms_viewer/fms_auditor/fms_report 四选一）
     */
    public String pgRole() {
        return switch (this) {
            case OPERATOR, ADMIN -> "fms_operator"; // admin 与 operator 共用 fms_operator，全量范围由 scope 注入保证
            case VIEWER -> "fms_viewer";
            case AUDITOR -> "fms_auditor";
            case REPORT -> "fms_report";
        };
    }
}
