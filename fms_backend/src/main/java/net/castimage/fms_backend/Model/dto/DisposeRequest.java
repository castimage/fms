package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 资产处置/报废请求：ACTIVE/DEPRECIATING 转 DISPOSED/SCRAPPED + disposal_date；
 * trg_asset_immutable 终审（处置/报废后禁改禁删，RAISE 则 40900）。
 * reason 仅供调用方审计日志记录（fa.assets 无原因列，不落库）。
 */
public record DisposeRequest(
        /** 处置/报废日期（缺省当日） */
        LocalDate disposeDate,
        /** 处置原因（仅日志，不落库） */
        @Size(max = 500, message = "处置原因最长 500 字符")
        String reason) {
}
