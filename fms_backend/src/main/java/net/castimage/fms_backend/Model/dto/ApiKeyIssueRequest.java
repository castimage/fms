package net.castimage.fms_backend.Model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * API 密钥签发请求（POST /api/admin/api-keys）：scope 仅 read/read_write（服务端白名单复检），
 * expiresAt 可空 = 永不过期（前端 yyyy-MM-dd HH:mm:ss 提交，Jackson 按全局日期格式解析）。
 */
public record ApiKeyIssueRequest(
        @NotBlank(message = "密钥名称必填")
        @Size(max = 64, message = "密钥名称最长 64 字符")
        String name,

        @NotBlank(message = "作用域必填")
        @Pattern(regexp = "read|read_write", message = "作用域仅支持 read/read_write")
        String scope,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime expiresAt) {
}
