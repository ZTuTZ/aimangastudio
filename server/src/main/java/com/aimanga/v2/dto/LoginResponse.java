package com.aimanga.v2.dto;

import com.aimanga.v2.model.User;

/** 登录/刷新响应 */
public record LoginResponse(String accessToken, String refreshToken, UserVO user) {

    public static LoginResponse of(String accessToken, String refreshToken, User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        vo.setProjectCount(0L);
        return new LoginResponse(accessToken, refreshToken, vo);
    }
}
