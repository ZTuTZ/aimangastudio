package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.ChangePasswordRequest;
import com.aimanga.v2.dto.LoginRequest;
import com.aimanga.v2.dto.LoginResponse;
import com.aimanga.v2.dto.RefreshRequest;
import com.aimanga.v2.model.User;
import com.aimanga.v2.security.CurrentUser;
import com.aimanga.v2.security.JwtUtil;
import com.aimanga.v2.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口:login / refresh / logout 匿名;me / password 需 JWT。
 * refresh token 的 jti 存 Redis(aimanga:v2:refresh:{jti} = userId),可吊销、可轮换。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_KEY_PREFIX = "aimanga:v2:refresh:";

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final RedissonClient redissonClient;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByUsername(request.username());
        if (user == null || !userService.verifyPassword(user, request.password())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被停用,请联系管理员");
        }
        return Result.ok(issueTokens(user));
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody RefreshRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new BusinessException(401, "缺少 refreshToken");
        }
        Claims claims;
        try {
            claims = jwtUtil.parseOfType(request.refreshToken(), JwtUtil.TYPE_REFRESH);
        } catch (JwtException e) {
            throw new BusinessException(401, "登录已过期,请重新登录");
        }
        String jti = claims.getId();
        RBucket<Long> bucket = redissonClient.getBucket(REFRESH_KEY_PREFIX + jti);
        Long storedUserId = bucket.get();
        if (storedUserId == null || !storedUserId.equals(Long.parseLong(claims.getSubject()))) {
            throw new BusinessException(401, "登录已失效,请重新登录");
        }
        User user = userService.getById(storedUserId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            bucket.delete();
            throw new BusinessException(401, "登录已失效,请重新登录");
        }
        // 轮换:旧 jti 立即作废
        bucket.delete();
        return Result.ok(issueTokens(user));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            try {
                Claims claims = jwtUtil.parseOfType(request.refreshToken(), JwtUtil.TYPE_REFRESH);
                redissonClient.getBucket(REFRESH_KEY_PREFIX + claims.getId()).delete();
            } catch (JwtException ignored) {
                // 无效 token 直接视为已登出
            }
        }
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<User> me() {
        User user = userService.getById(CurrentUser.id());
        if (user == null) {
            throw new BusinessException(401, "账号不存在");
        }
        user.setPasswordHash(null);
        return Result.ok(user);
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        User user = userService.getById(CurrentUser.id());
        if (user == null) {
            throw new BusinessException(401, "账号不存在");
        }
        if (!userService.verifyPassword(user, request.oldPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        userService.updatePassword(user.getId(), request.newPassword());
        log.info("[auth] 用户 {} 修改了密码", user.getUsername());
        return Result.ok();
    }

    private LoginResponse issueTokens(User user) {
        String access = jwtUtil.createAccessToken(user.getId(), user.getUsername(), user.getRole());
        String jti = JwtUtil.newJti();
        String refresh = jwtUtil.createRefreshToken(user.getId(), user.getUsername(), user.getRole(), jti);
        redissonClient.getBucket(REFRESH_KEY_PREFIX + jti)
                .set(user.getId(), Duration.ofDays(jwtUtil.refreshTtlDays()));
        return LoginResponse.of(access, refresh, user);
    }
}
