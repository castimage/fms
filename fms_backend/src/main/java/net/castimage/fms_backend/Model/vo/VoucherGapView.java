package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * 凭证断号视图（GET /api/gl/voucher-gaps 行；镜像 gl.fn_cron_voucher_number_check
 * 期间缺口 SQL 在线化）：gapFrom~gapTo 为缺失号段，gapCount=缺口个数（单号缺口
 * gapFrom==gapTo 且 gapCount=1）；口径与 cron 一致——仅统计 POSTED/REVERSED 且
 * 已取号凭证，RLS 收敛可见集合。
 */
public record VoucherGapView(UUID companyId, String period,
                             Integer gapFrom, Integer gapTo, Integer gapCount) {
}
