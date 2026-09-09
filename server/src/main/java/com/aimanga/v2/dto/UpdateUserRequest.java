package com.aimanga.v2.dto;

import jakarta.validation.constraints.Size;

/** 更新用户:status 1启用 0停用;newPassword 重置密码(至少一项) */
public record UpdateUserRequest(
        Integer status,
        @Size(min = 6, max = 64, message = "密码长度需 6-64 位")
        String newPassword) {
}
