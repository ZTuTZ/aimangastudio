package com.aimanga.v2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 2, max = 64, message = "用户名长度需 2-64 位")
        @Pattern(regexp = "^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$", message = "用户名仅支持中英文、数字、下划线和连字符")
        String username,
        @NotBlank(message = "初始密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需 6-64 位")
        String password,
        @NotBlank(message = "角色不能为空")
        @Pattern(regexp = "ADMIN|USER", message = "角色必须为 ADMIN 或 USER")
        String role) {
}
