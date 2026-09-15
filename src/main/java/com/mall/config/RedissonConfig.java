package com.mall.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置（复用 spring.data.redis 连接参数）
 * <p>
 * 用于分布式锁：缓存击穿互斥重建、秒杀一人一单等场景
 *
 * @author mall
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setPassword(StringUtils.hasText(redisProperties.getPassword())
                        ? redisProperties.getPassword() : null);
        return Redisson.create(config);
    }
}
