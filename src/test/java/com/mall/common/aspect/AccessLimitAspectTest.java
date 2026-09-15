package com.mall.common.aspect;

import com.mall.TestSecurityHelper;
import com.mall.common.annotation.AccessLimit;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流切面单元测试：开关关闭直接放行、滑动窗口 Lua 返回 1 放行、返回 0 抛 429。
 */
@ExtendWith(MockitoExtension.class)
class AccessLimitAspectTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private AccessLimit accessLimit;
    @Mock
    private HttpServletRequest request;

    private AccessLimitAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AccessLimitAspect(stringRedisTemplate);
        TestSecurityHelper.loginAs(1001L);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
        org.mockito.Mockito.lenient().when(request.getRequestURI()).thenReturn("/api/seckill/execute");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        TestSecurityHelper.clear();
    }

    private void setEnabled(boolean enabled) {
        ReflectionTestUtils.setField(aspect, "enabled", enabled);
    }

    @Test
    @DisplayName("限流开关关闭 -> 直接放行，不执行 Lua")
    void disabled_proceed() throws Throwable {
        setEnabled(false);
        Object expected = new Object();
        when(pjp.proceed()).thenReturn(expected);

        Object result = aspect.around(pjp, accessLimit);
        assertEquals(expected, result);
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("Lua 返回 1 放行 -> 执行业务方法")
    void allowed_proceed() throws Throwable {
        setEnabled(true);
        when(accessLimit.seconds()).thenReturn(5);
        when(accessLimit.maxCount()).thenReturn(10);
        when(accessLimit.keyType()).thenReturn(AccessLimit.LimitKeyType.USER);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(1L);
        Object expected = new Object();
        when(pjp.proceed()).thenReturn(expected);

        Object result = aspect.around(pjp, accessLimit);
        assertEquals(expected, result);
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("Lua 返回 0 限流 -> 抛 TOO_MANY_REQUESTS(429)")
    void denied_throw429() throws Throwable {
        setEnabled(true);
        when(accessLimit.seconds()).thenReturn(5);
        when(accessLimit.maxCount()).thenReturn(10);
        when(accessLimit.keyType()).thenReturn(AccessLimit.LimitKeyType.USER);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aspect.around(pjp, accessLimit));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(pjp, never()).proceed();
    }
}
