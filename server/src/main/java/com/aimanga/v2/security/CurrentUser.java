package com.aimanga.v2.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/** 当前登录用户便捷访问(principal = user.id) */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Subject subject = SecurityUtils.getSubject();
        Object principal = subject.getPrincipal();
        if (principal instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("未登录");
    }

    public static boolean isAdmin() {
        return SecurityUtils.getSubject().hasRole("ADMIN");
    }
}
