package com.aimanga.v2.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authz.AuthorizationFilter;

import java.io.IOException;

/**
 * 管理员角色过滤器:/api/admin/** 专用。
 * 未登录 → 401;已登录非 ADMIN → 403(均返回 JSON,不做重定向)。
 * 不能注册为 Spring bean(会被 Boot 自动挂到 /* 上),仅在 ShiroConfig 内部实例化。
 */
public class AdminRoleFilter extends AuthorizationFilter {

    private final JwtAuthFilter jwtAuthFilter;

    public AdminRoleFilter(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        Subject subject = getSubject(request, response);
        return subject.isAuthenticated() && subject.hasRole("ADMIN");
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
        Subject subject = getSubject(request, response);
        if (!subject.isAuthenticated()) {
            jwtAuthFilter.writeJson(response, 401, "未登录或登录已过期");
        } else {
            jwtAuthFilter.writeJson(response, 403, "需要管理员权限");
        }
        return false;
    }
}
