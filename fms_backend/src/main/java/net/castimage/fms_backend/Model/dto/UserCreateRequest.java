package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 用户创建请求（经 auth.create_user SECURITY DEFINER 闸口）。
 */
public record UserCreateRequest(
        /** 登录名（唯一） */
        @NotBlank(message = "登录名不能为空")
        @Size(max = 64, message = "登录名最长 64 字符")
        String loginName,
        /** 初始密码（服务端 BCrypt 后写入；建议初始必改密） */
        @NotBlank(message = "初始密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度须在 8-64 位之间")
        String password,
        /** 显示名 */
        @Size(max = 128, message = "显示名最长 128 字符")
        String displayName,
        /** 邮箱（加密存储，经闸口内部 pgp_sym_encrypt） */
        @Email(message = "邮箱格式错误")
        @Size(max = 128, message = "邮箱最长 128 字符")
        String email,
        /** 所属公司 */
        @NotNull(message = "companyId 不能为空")
        UUID companyId,
        /** 首次登录须改密（默认 true） */
        Boolean mustChangePwd) { // 初始密码策略，
}
