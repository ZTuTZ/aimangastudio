package com.aimanga.v2.config;

import com.aimanga.v2.model.User;
import com.aimanga.v2.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 初始管理员引导:库中不存在任何 ADMIN 时创建 admin / admin123(请登录后立即修改密码)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserService userService;

    @Override
    public void run(ApplicationArguments args) {
        boolean hasAdmin = userService.list().stream()
                .anyMatch(u -> User.ROLE_ADMIN.equals(u.getRole()));
        if (!hasAdmin) {
            userService.createUser("admin", "admin123", User.ROLE_ADMIN);
            log.warn("[bootstrap] 已创建初始管理员账号: admin / admin123 (请尽快登录修改密码)");
        }
    }
}
