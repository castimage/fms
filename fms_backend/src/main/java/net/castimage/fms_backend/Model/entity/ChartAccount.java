package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 会计科目实体（gl.chart_accounts，ltree 科目树）：path 为科目编码路径（如 1001.01），主键 uuidv7 由 Java 侧生成（与 PG DEFAULT uuidv7() 等价）；code/path 创建后禁改。
 */
@TableName(schema = "gl", value = "chart_accounts")
@Data
public class ChartAccount {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 科目编码（公司内唯一，ltree 标签：字母/数字/下划线） */
    private String code;

    /** 科目名称 */
    private String name;

    /** 科目树路径（ltree 文本：父.path + '.' + code） */
    private String path;

    /** 父科目 id（根科目为 null） */
    private UUID parentId;

    /** 层级（根为 1） */
    private Integer level;

    /** 是否末级科目（有子科目为 false） */
    private Boolean isLeaf;

    /** 科目类型：ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE */
    private String accountType;

    /** 余额方向：DEBIT/CREDIT */
    private String direction;

    /** 是否外币科目 */
    private Boolean isForeignCurrency;

    /** 币种（CHAR(3)，如 CNY） */
    private String currency;

    /** 状态：ACTIVE/DISABLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt; // 审计时间戳，

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间（RLS 恒过滤 deleted_at IS NULL），

}
