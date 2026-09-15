package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀参数（mall.seckill）
 *
 * @author mall
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.seckill")
public class SeckillProperties {

    /**
     * 本地内存售罄标记开关（true 时售罄请求不再打到 Redis，用于压测对比）
     */
    private boolean localMarkEnabled = true;

    /**
     * 秒杀结果 key 有效期（秒），供前端轮询
     */
    private long resultTtlSeconds = 600;

    /**
     * 当日场次列表缓存 TTL（秒）
     */
    private long sessionsCacheTtlSeconds = 60;

    /**
     * 秒杀验证码有效期（秒），过期需重新获取
     */
    private long captchaTtlSeconds = 120;

    /**
     * 秒杀动态下单路径有效期（秒），路径一次性换取、短时效防重放探测
     */
    private long pathTtlSeconds = 60;

    /**
     * 动态路径 MD5 签名盐值，生产环境通过环境变量覆盖
     */
    private String pathSalt = "mall-seckill-path-salt-2026";

    /**
     * 秒杀验证码开关：默认开启；压测时关闭以便压测脚本直接换取动态路径
     */
    private boolean captchaEnabled = true;
}
