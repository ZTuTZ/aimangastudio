package com.aimanga.v2.security;

import com.aimanga.v2.model.User;
import com.aimanga.v2.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.stereotype.Component;

/**
 * JWT Realm:认证时解析并校验 JWT、检查用户状态;授权时从用户角色装载。
 * 无状态:不查密码、不开会话。
 */
@Component
@RequiredArgsConstructor
public class AuthRealm extends AuthorizingRealm {

    private final JwtUtil jwtUtil;
    private final UserService userService;

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    {
        // 提交的 credentials 与 AuthenticationInfo 中保存的 JWT 相等即通过(签名已在 parse 时验证)
        setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String jwt = (String) token.getCredentials();
        Claims claims;
        try {
            claims = jwtUtil.parseOfType(jwt, JwtUtil.TYPE_ACCESS);
        } catch (JwtException e) {
            throw new IncorrectCredentialsException("登录已过期,请重新登录");
        }
        long userId = Long.parseLong(claims.getSubject());
        User user = userService.getById(userId);
        if (user == null) {
            throw new UnknownAccountException("账号不存在");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new IncorrectCredentialsException("账号已被停用");
        }
        return new SimpleAuthenticationInfo(userId, jwt, getName());
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        long userId = (long) principals.getPrimaryPrincipal();
        User user = userService.getById(userId);
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (user != null && user.getRole() != null) {
            info.addRole(user.getRole());
        }
        return info;
    }
}
