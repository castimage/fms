package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 凭证红字冲销请求（POST /api/gl/vouchers/{id}/reverse）：调 PG gl.fn_reverse_voucher 生成借贷互换的反向凭证（POSTED）并互引，原凭证置 REVERSED；冲销期间（reverseDate 所在期间）与原期间均须 OPEN。
 */
public record ReverseRequest(
        LocalDate reverseDate, // 缺省当前日期，
        /** 冲销原因 */
        @Size(max = 500, message = "冲销原因最长 500 字符")
        String reason) {
}
