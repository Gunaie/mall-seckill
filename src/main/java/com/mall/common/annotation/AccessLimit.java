package com.mall.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口访问限流注解（Redis ZSET 滑动窗口实现）
 * <p>
 * 标注在 Controller 方法上，由 {@code AccessLimitAspect} 切面拦截：
 * 在 {@link #seconds()} 秒的滑动窗口内，同一身份（登录用户 / 客户端 IP）最多访问 {@link #maxCount()} 次，
 * 超出后返回 429。典型场景：秒杀下单防连点、验证码/动态路径接口防刷、轮询接口限速。
 *
 * @author mall
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessLimit {

    /**
     * 统计窗口（秒）
     */
    int seconds();

    /**
     * 窗口内最大请求次数
     */
    int maxCount();

    /**
     * 限流维度，默认按登录用户 ID 统计
     */
    LimitKeyType keyType() default LimitKeyType.USER;

    /**
     * 限流身份维度
     */
    enum LimitKeyType {
        /** 登录用户 ID（接口需认证） */
        USER,
        /** 客户端 IP（穿透代理取 X-Forwarded-For / X-Real-IP） */
        IP
    }
}
