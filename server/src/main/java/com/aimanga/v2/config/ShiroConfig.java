package com.aimanga.v2.config;

import com.aimanga.v2.security.AdminRoleFilter;
import com.aimanga.v2.security.AuthRealm;
import com.aimanga.v2.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shiro 无状态配置(Shiro 2.0 API):Subject 不落会话 + 禁用 Session Cookie,
 * 认证全部走 JWT。过滤链顺序敏感:精确路径在前,/api/** 兜底在后。
 */
@Configuration
public class ShiroConfig {

    @Bean
    public DefaultWebSecurityManager securityManager(AuthRealm realm) {
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager(realm);
        // 无状态:Subject 不写入会话(Shiro 2.0 经 SubjectDAO + SessionStorageEvaluator 控制)
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        DefaultSessionStorageEvaluator evaluator = new DefaultSessionStorageEvaluator();
        evaluator.setSessionStorageEnabled(false);
        subjectDAO.setSessionStorageEvaluator(evaluator);
        manager.setSubjectDAO(subjectDAO);
        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();
        sessionManager.setSessionIdCookieEnabled(false);
        sessionManager.setSessionIdUrlRewritingEnabled(false);
        manager.setSessionManager(sessionManager);
        return manager;
    }

    /** bean 名必须为 shiroFilterFactoryBean:Shiro 2.0 自动注册器按此名称查找 */
    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(DefaultWebSecurityManager securityManager,
                                                         ObjectMapper objectMapper) {
        // 过滤器在链内手动装配,不注册为 Spring bean(否则会被 Boot 全局挂载)
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(objectMapper);
        AdminRoleFilter adminRoleFilter = new AdminRoleFilter(jwtAuthFilter);
        ShiroFilterFactoryBean factory = new ShiroFilterFactoryBean();
        factory.setSecurityManager(securityManager);
        factory.getFilters().put("jwt", jwtAuthFilter);
        factory.getFilters().put("admin", adminRoleFilter);

        Map<String, String> chain = new LinkedHashMap<>();
        chain.put("/api/health", "anon");
        chain.put("/api/auth/login", "anon");
        chain.put("/api/auth/refresh", "anon");
        chain.put("/api/auth/logout", "anon");
        chain.put("/api/admin/**", "jwt,admin");
        chain.put("/api/**", "jwt");
        chain.put("/**", "anon");
        factory.setFilterChainDefinitionMap(chain);
        return factory;
    }
}
