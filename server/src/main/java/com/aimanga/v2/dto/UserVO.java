package com.aimanga.v2.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理端用户行(含作品数,不含密码哈希) */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String role;

    private Integer status;

    private LocalDateTime createTime;

    private Long projectCount;
}
