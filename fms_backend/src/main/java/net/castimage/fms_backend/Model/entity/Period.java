package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 会计期间实体（gl.periods）：期间仅允许前向锁定 OPEN 转 CLOSED（trg_period_guard）；已结账期间禁止创建凭证（trg_period_voucher_guard）与提交/过账（trg_voucher_submit_check）。
 */
@TableName(schema = "gl", value = "periods")
@Data
public class Period {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 期间（CHAR(7)，YYYY-MM） */
    private String period;

    /** 状态：OPEN/CLOSED */
    private String status;

    /** 结账人 */
    private UUID closedBy;

    /** 结账时间 */
    private LocalDateTime closedAt;

    /** 创建时间 */
    private LocalDateTime createdAt; // 审计时间戳，

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt; // 软删时间戳，

}
