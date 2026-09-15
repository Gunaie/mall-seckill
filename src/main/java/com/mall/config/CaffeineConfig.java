package com.mall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地一级缓存配置（Caffeine）。
 * <p>
 * 与 Redis 二级缓存组成多级缓存：
 * <pre>
 *   查询: Caffeine(L1, 命中即返回) -> Redis(L2) -> DB(回源重建)
 *   失效: DB 更新 -> 先删 Redis 再删 Caffeine(Cache Aside)
 * </pre>
 * <p>
 * TTL 取自 {@link CacheProperties#getSpuDetailLocalTtl()}，不硬编码，确保配置变更生效。
 *
 * @author mall
 */
@Configuration
@RequiredArgsConstructor
public class CaffeineConfig {

    private final CacheProperties cacheProperties;

    /**
     * 通用本地缓存 Bean：用于商品详情等热数据。
     * value 类型为 Object，存储 SpuDetailVO 或空值占位符。
     */
    @Bean(name = "localCache")
    public Cache<String, Object> localCache() {
        return Caffeine.newBuilder()
                // 容量上限，防止本地内存无限增长
                .maximumSize(1000)
                // 写入后过期，TTL 来自配置（默认 300s），小于 Redis 二级缓存 TTL
                .expireAfterWrite(Duration.ofSeconds(cacheProperties.getSpuDetailLocalTtl()))
                .build();
    }
}
