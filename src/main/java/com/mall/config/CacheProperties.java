package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存参数（mall.cache）
 *
 * @author mall
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.cache")
public class CacheProperties {

    /**
     * 商品详情缓存基础 TTL（秒）
     */
    private long spuDetailTtl = 1800;

    /**
     * 商品详情 TTL 随机抖动上限（秒），实际 TTL = 基础值 + [0, jitter]，防雪崩
     */
    private long spuDetailTtlJitter = 600;

    /**
     * 商品详情本地一级缓存（Caffeine）TTL（秒）。
     * 应小于 Redis 二级缓存 TTL，避免本地持有过久导致与 Redis/DB 不一致。
     */
    private long spuDetailLocalTtl = 300;

    /**
     * 空值缓存 TTL（秒），防穿透
     */
    private long nullValueTtl = 120;

    /**
     * 分类树缓存 TTL（秒）
     */
    private long categoryTreeTtl = 3600;
}
