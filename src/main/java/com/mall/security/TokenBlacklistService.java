package com.mall.security;

import com.mall.common.constant.CacheConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务：基于 Redis 的 token 主动作废机制。
 * <p>
 * 适用场景：
 * <ul>
 *     <li>用户登出：将当前 access token 加入黑名单，使其立即失效</li>
 *     <li>refresh token 轮换：refresh 成功后将旧 refresh token 加入黑名单，防止重复使用</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *     <li>key 取 token 的 MD5 摘要，避免超长 JWT 直接作为 Redis key</li>
 *     <li>TTL 取 token 剩余有效期，token 自然过期后记录自动消失，无需额外清理任务</li>
 *     <li>仅做「存在性」判断，value 固定为 "1"</li>
 * </ul>
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 将 token 加入黑名单。
     *
     * @param token       原始 JWT 字符串
     * @param ttlSeconds  剩余有效期（秒），<=0 时不写入（token 已过期无需拉黑）
     */
    public void blacklist(String token, long ttlSeconds) {
        if (token == null || token.isEmpty() || ttlSeconds <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(blacklistKey(token), "1", ttlSeconds, TimeUnit.SECONDS);
        log.debug("token 已加入黑名单, ttl={}s", ttlSeconds);
    }

    /**
     * 判断 token 是否在黑名单中。
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey(token)));
    }

    private String blacklistKey(String token) {
        return CacheConstants.JWT_BLACKLIST_KEY
                + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
    }
}
