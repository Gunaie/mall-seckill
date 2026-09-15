package com.mall.common.aspect;

import com.mall.common.annotation.AccessLimit;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.common.util.RequestUtils;
import com.mall.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * 接口限流切面：Redis ZSET 滑动窗口
 * <p>
 * 以「请求 URI + 身份」为 key，ZSET 成员为单次请求、score 为毫秒时间戳：
 * 每次请求先剔除窗口外旧成员，再判断 ZCARD 是否达到阈值，未达阈值才放行并写入本次请求。
 * 相比固定窗口，滑动窗口没有临界双倍流量突刺问题；Lua 保证「清理+计数+写入」原子执行。
 *
 * @author mall
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AccessLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 限流总开关，生产环境必须开启（mall.access-limit.enabled=true）；
     * 压测环境关闭以排除限流层干扰，单独测量业务漏斗吞吐
     */
    @Value("${mall.access-limit.enabled:true}")
    private boolean enabled;

    /**
     * KEYS[1]=限流 ZSET
     * ARGV[1]=当前时间戳ms, ARGV[2]=窗口ms, ARGV[3]=最大次数, ARGV[4]=本次请求唯一成员
     * 返回 0 已被限流 / 1 放行
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "redis.call('zremrangebyscore', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2])) " +
                "if tonumber(redis.call('zcard', KEYS[1])) >= tonumber(ARGV[3]) then " +
                "    return 0 " +
                "end " +
                "redis.call('zadd', KEYS[1], ARGV[1], ARGV[4]) " +
                "redis.call('pexpire', KEYS[1], ARGV[2]) " +
                "return 1");
        script.setResultType(Long.class);
        SLIDING_WINDOW_SCRIPT = script;
    }

    @Around("@annotation(accessLimit)")
    public Object around(ProceedingJoinPoint pjp, AccessLimit accessLimit) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder
                .currentRequestAttributes()).getRequest();

        String identity = accessLimit.keyType() == AccessLimit.LimitKeyType.USER
                ? "u:" + SecurityUtils.getCurrentUserId()
                : "ip:" + RequestUtils.getClientIp(request);
        // 同一接口、同一身份共用一个窗口；秒杀 execute 路径含动态 path，但同用户同商品 path 恒定，不受影响
        String key = CacheConstants.ACCESS_LIMIT_KEY + request.getRequestURI() + ":" + identity;

        Long allowed = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(accessLimit.seconds() * 1000L),
                String.valueOf(accessLimit.maxCount()),
                UUID.randomUUID().toString());

        if (allowed == null || allowed == 0L) {
            log.warn("接口限流触发, uri={}, identity={}, window={}s, max={}",
                    request.getRequestURI(), identity, accessLimit.seconds(), accessLimit.maxCount());
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS);
        }
        return pjp.proceed();
    }
}
