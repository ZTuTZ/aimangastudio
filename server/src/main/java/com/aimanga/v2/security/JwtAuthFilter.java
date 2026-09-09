package com.aimanga.v2.security;

import com.aimanga.v2.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器:从 Authorization: Bearer 或 ?token=(EventSource 用)解析 JWT,
 * 未认证则尝试 login;失败统一返回 401 JSON。
 * 注意:不能注册为 Spring bean(Filter 会被 Boot 自动挂到 /* 上绕过 Shiro 链),
 * 仅在 ShiroConfig 内部实例化并纳入 shiro 过滤链。
 */
@RequiredArgsConstructor
public class JwtAuthFilter extends AuthenticatingFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        if (request instanceof HttpServletRequest httpReq && "OPTIONS".equalsIgnoreCase(httpReq.getMethod())) {
            return true;
        }
        return getSubject(request, response).isAuthenticated();
    }

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        String jwt = resolveToken((HttpServletRequest) request);
        return jwt == null ? null : new JwtToken(jwt);
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        if (resolveToken((HttpServletRequest) request) == null) {
            return fail(response, 401, "未登录或登录已过期");
        }
        return executeLogin(request, response);
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException e,
                                     ServletRequest request, ServletResponse response) {
        return fail(response, 401, e.getMessage() == null ? "未登录或登录已过期" : e.getMessage());
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // EventSource 无法设置请求头,允许 ?token= 透传(SSE 端点用)
        String query = request.getParameter("token");
        return query == null || query.isBlank() ? null : query.trim();
    }

    private boolean fail(ServletResponse response, int status, String message) {
        writeJson(response, status, message);
        return false;
    }

    void writeJson(ServletResponse response, int status, String message) {
        try {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(status);
            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resp.getWriter().write(objectMapper.writeValueAsString(Result.fail(status, message)));
        } catch (Exception ignored) {
            // 响应已提交,无法写入
        }
    }
}
