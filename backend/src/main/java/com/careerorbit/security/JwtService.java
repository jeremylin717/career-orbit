package com.careerorbit.security;

import com.careerorbit.entity.UserEntity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具：签发与校验 Token。
 * Token 的 subject 是邮箱，另带 role 与 uid 声明；用 HS256 对称密钥签名。
 * 无状态：服务端不存会话，校验签名即可信任。
 */
@Service
public class JwtService {

    /** HS256 对称签名密钥（由配置的 secret 生成）。 */
    private final SecretKey key;

    /** Token 有效期。 */
    private final Duration expiration;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.expiration-minutes}") long minutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(minutes);
    }

    /** 为某个用户签发 Token。 */
    public String issue(UserEntity user) {
        var now = Instant.now();
        return Jwts.builder()
                .subject(user.email)
                .claim("role", user.role)
                .claim("uid", user.id)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    /** 校验签名并取出 subject（邮箱）；签名无效或过期会抛异常。 */
    public String subject(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
