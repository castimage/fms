package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对账运行批次实体（cash.reconcile_runs）：每次自动对账/人工对账落一条运行统计，
 * auto_matched/manual_matched 为配对计数、diff_count 为本次运行后仍未匹配的差异行数
 * （流水+对账单行，对账差异调整的量化口径）。
 */
@TableName(schema = "cash", value = "reconcile_runs")
@Data
public class ReconcileRun {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 运行期间（char(7)，YYYY-MM） */
    private String period;

    /** 运行时间（缺省 now()） */
    private LocalDateTime runAt;

    /** 自动配对计数 */
    private Integer autoMatched;

    /** 人工配对计数 */
    private Integer manualMatched;

    /** 差异计数（本次运行后仍未匹配：流水+对账单行） */
    private Integer diffCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
