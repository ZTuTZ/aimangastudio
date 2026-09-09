package com.aimanga.v2.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验(无状态)。
 * access:2h;refresh:7d(jti 存 Redis 可吊销,见 AuthController)。
 * JWT_SECRET 未配置时生成临时密钥并告警(仅限开发:重启后全部登录失效)。
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    @Value("${aimanga.jwt-secret:}")
    private String secret;

    @Value("${aimanga.jwt-access-ttl-minutes:120}")
    private long accessTtlMinutes;

    @Value("${aimanga.jwt-refresh-ttl-days:7}")
    private long refreshTtlDays;

    private SecretKey key;

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            byte[] bytes = new byte[64];
            new SecureRandom().nextBytes(bytes);
            this.key = Keys.hmacShaKeyFor(bytes);
            log.warn("[jwt] JWT_SECRET 未配置,使用临时随机密钥(重启后所有登录失效)——仅限本地开发,生产必须通过环境变量注入");
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET 至少 32 字节(256 位)");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
    }

    public String createAccessToken(long userId, String username, String role) {
        return build(userId, username, role, TYPE_ACCESS, null,
                Duration.ofMinutes(accessTtlMinutes));
    }

    public String createRefreshToken(long userId, String username, String role, String jti) {
        return build(userId, username, role, TYPE_REFRESH, jti,
                Duration.ofDays(refreshTtlDays));
    }

    private String build(long userId, String username, String role, String type, String jti, Duration ttl) {
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim("typ", type)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(ttl)));
        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(key).compact();
    }

    /** 校验签名与有效期,非法抛出 JwtException */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    /** 解析并要求指定类型(typ),不匹配抛 JwtException */
    public Claims parseOfType(String token, String expectedType) throws JwtException {
        Claims claims = parse(token);
        if (!expectedType.equals(claims.get("typ", String.class))) {
            throw new JwtException("token 类型不匹配");
        }
        return claims;
    }

    public static String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public long refreshTtlDays() {
        return refreshTtlDays;
    }
}
