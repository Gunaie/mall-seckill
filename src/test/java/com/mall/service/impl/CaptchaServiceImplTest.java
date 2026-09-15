package com.mall.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.config.SeckillProperties;
import com.mall.vo.CaptchaVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码服务单元测试：生成（base64 图片）、校验三种返回（正确/错误/过期）、空参数拒绝。
 */
@ExtendWith(MockitoExtension.class)
class CaptchaServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SeckillProperties seckillProperties;

    @InjectMocks
    private CaptchaServiceImpl captchaService;

    @Test
    @DisplayName("generate 返回 token 与 base64 PNG 图片，并写入 Redis")
    void generate() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(seckillProperties.getCaptchaTtlSeconds()).thenReturn(120L);

        CaptchaVO vo = captchaService.generate();
        assertNotNull(vo.getCaptchaToken());
        assertTrue(vo.getImg().startsWith("data:image/png;base64,"));
        verify(valueOps).set(anyString(), anyString(), eq(120L), any());
    }

    @Test
    @DisplayName("verifyAndConsume 空 token -> 拒绝")
    void verify_blankToken() {
        assertThrows(BusinessException.class, () -> captchaService.verifyAndConsume("", "123"));
    }

    @Test
    @DisplayName("verifyAndConsume Lua 返回 0 过期 -> 拒绝")
    void verify_expired() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(0L);
        assertThrows(BusinessException.class, () -> captchaService.verifyAndConsume("tok", "123"),
                "验证码已过期");
    }

    @Test
    @DisplayName("verifyAndConsume Lua 返回 -1 错误 -> 拒绝")
    void verify_wrong() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(-1L);
        assertThrows(BusinessException.class, () -> captchaService.verifyAndConsume("tok", "123"),
                "验证码错误");
    }

    @Test
    @DisplayName("verifyAndConsume Lua 返回 1 正确 -> 通过")
    void verify_correct() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        // 不应抛异常
        captchaService.verifyAndConsume("tok", "5");
    }
}
