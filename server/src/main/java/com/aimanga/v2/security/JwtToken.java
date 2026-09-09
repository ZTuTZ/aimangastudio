package com.aimanga.v2.security;

import org.apache.shiro.authc.AuthenticationToken;

/** 携带前端提交的 JWT 字符串,交由 AuthRealm 校验 */
public class JwtToken implements AuthenticationToken {

    private final String token;

    public JwtToken(String token) {
        this.token = token;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }
}
