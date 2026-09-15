package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单参数（mall.order）
 *
 * @author mall
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.order")
public class OrderProperties {

    /**
     * 待支付订单超时时间（秒），到期未支付由 MQ 延迟消息触发自动关单
     */
    private long timeoutSeconds = 1800;

    /**
     * 下单幂等令牌有效期（秒）
     */
    private long tokenTtlSeconds = 600;
}
