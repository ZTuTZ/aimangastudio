package com.aimanga.v2.config;

import com.aimanga.v2.model.User;
import com.aimanga.v2.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 初始管理员引导:仅在显式提供账号与密码时创建首个 ADMIN，绝不内置生产默认口令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserService userService;

    @Value("${aimanga.bootstrap.admin-username:}")
    private String username;

    @Value("${aimanga.bootstrap.admin-password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        boolean hasAdmin = userService.list().stream()
                .anyMatch(u -> User.ROLE_ADMIN.equals(u.getRole()));
        if (!hasAdmin) {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                log.warn("[bootstrap] 未创建初始管理员：请通过 aimanga.bootstrap.admin-username 和 admin-password 显式配置");
                return;
            }
            userService.createUser(username.trim(), password, User.ROLE_ADMIN);
            log.warn("[bootstrap] 已创建初始管理员账号: {}", username.trim());
        }
    }
}
