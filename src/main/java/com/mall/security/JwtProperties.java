package com.mall.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性，对应 application-*.yml 中的 mall.jwt
 *
 * @author mall
 */
@Data
@ConfigurationProperties(prefix = "mall.jwt")
public class JwtProperties {

    /**
     * HS256 签名密钥
     */
    private String secret;

    /**
     * access token 有效期（秒）
     */
    private Long accessTokenExpiration;

    /**
     * refresh token 有效期（秒）
     */
    private Long refreshTokenExpiration;

    /**
     * refresh token Cookie 的 Secure 属性。
     * 生产环境（HTTPS）应设为 true；开发环境（HTTP）设为 false，否则浏览器不发送 Cookie。
     */
    private boolean refreshCookieSecure = false;
}
