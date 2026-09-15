package com.mall.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成、解析与校验
 * <p>
 * 采用双 Token：
 * <ul>
 *     <li>access token：短期，type=access，用于访问业务接口</li>
 *     <li>refresh token：长期，type=refresh，仅用于换取新的 access token</li>
 * </ul>
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";

    private final JwtProperties jwtProperties;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 access token
     */
    public String generateAccessToken(Long userId, String username) {
        return buildToken(userId, username, TOKEN_TYPE_ACCESS, jwtProperties.getAccessTokenExpiration());
    }

    /**
     * 生成 refresh token
     */
    public String generateRefreshToken(Long userId) {
        return buildToken(userId, null, TOKEN_TYPE_REFRESH, jwtProperties.getRefreshTokenExpiration());
    }

    private String buildToken(Long userId, String username, String type, Long expirationSeconds) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationSeconds * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 校验 access token（签名正确 + 未过期 + 类型匹配）
     */
    public boolean validateAccessToken(String token) {
        return validate(token, TOKEN_TYPE_ACCESS);
    }

    /**
     * 校验 refresh token
     */
    public boolean validateRefreshToken(String token) {
        return validate(token, TOKEN_TYPE_REFRESH);
    }

    private boolean validate(String token, String expectedType) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return expectedType.equals(jws.getPayload().get(CLAIM_TYPE, String.class));
        } catch (Exception e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 token 中解析用户 ID
     */
    public Long getUserId(String token) {
        String subject = parseClaims(token).getSubject();
        return Long.valueOf(subject);
    }

    /**
     * 获取 token 剩余有效期（秒）。
     * 用于黑名单 TTL：将已作废 token 写入 Redis 时，TTL 设为剩余有效期，
     * token 自然过期后黑名单记录随之消失，无需额外清理。
     * 解析失败（签名错误/已过期）返回 0，调用方据此跳过黑名单写入。
     */
    public long getRemainingSeconds(String token) {
        try {
            Date exp = parseClaims(token).getExpiration();
            if (exp == null) {
                return 0;
            }
            long remaining = (exp.getTime() - System.currentTimeMillis()) / 1000;
            return Math.max(0, remaining);
        } catch (Exception e) {
            return 0;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
