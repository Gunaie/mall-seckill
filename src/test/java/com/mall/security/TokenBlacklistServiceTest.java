package com.mall.security;

import com.mall.common.constant.CacheConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 黑名单服务单元测试：
 * 验证黑名单写入（key=token MD5 + TTL=剩余有效期）、查询、空 token / 过期 TTL 边界处理。
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.test.signature";

    private String blacklistKey(String token) {
        return CacheConstants.JWT_BLACKLIST_KEY
                + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("blacklist: 正常 token + 有效 TTL -> 写入 Redis，key 为 token MD5，value=1")
    void blacklist_validToken_writesToRedis() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        tokenBlacklistService.blacklist(TOKEN, 3600);

        verify(valueOps).set(eq(blacklistKey(TOKEN)), eq("1"), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("blacklist: token 为 null -> 不写入 Redis")
    void blacklist_nullToken_skips() {
        tokenBlacklistService.blacklist(null, 3600);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("blacklist: token 为空字符串 -> 不写入 Redis")
    void blacklist_blankToken_skips() {
        tokenBlacklistService.blacklist("", 3600);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("blacklist: TTL <= 0 -> 不写入 Redis（token 已过期无需拉黑）")
    void blacklist_nonPositiveTtl_skips() {
        tokenBlacklistService.blacklist(TOKEN, 0);
        tokenBlacklistService.blacklist(TOKEN, -10);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("isBlacklisted: key 存在 -> 返回 true")
    void isBlacklisted_keyExists_returnsTrue() {
        when(stringRedisTemplate.hasKey(blacklistKey(TOKEN))).thenReturn(true);

        boolean result = tokenBlacklistService.isBlacklisted(TOKEN);

        assertTrue(result);
    }

    @Test
    @DisplayName("isBlacklisted: key 不存在 -> 返回 false")
    void isBlacklisted_keyMissing_returnsFalse() {
        when(stringRedisTemplate.hasKey(blacklistKey(TOKEN))).thenReturn(false);

        boolean result = tokenBlacklistService.isBlacklisted(TOKEN);

        assertFalse(result);
    }

    @Test
    @DisplayName("isBlacklisted: null/空 token -> 返回 false（不查 Redis）")
    void isBlacklisted_blankToken_returnsFalseWithoutRedis() {
        assertFalse(tokenBlacklistService.isBlacklisted(null));
        assertFalse(tokenBlacklistService.isBlacklisted(""));
        verify(stringRedisTemplate, never()).hasKey(anyString());
    }
}
