package com.mall.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品 Redis 缓存对象（管理端预热时写入，秒杀执行时全程读缓存、不打 DB）
 *
 * @author mall
 */
@Data
public class SeckillSkuCache implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 秒杀商品ID（sms_seckill_sku.id）
     */
    private Long seckillSkuId;

    private Long sessionId;

    private Long skuId;

    private Long spuId;

    /**
     * SPU 名称快照
     */
    private String spuName;

    /**
     * SKU 名称快照
     */
    private String skuName;

    private String image;

    /**
     * 原价（pms_sku.price）
     */
    private BigDecimal originalPrice;

    /**
     * 秒杀价
     */
    private BigDecimal seckillPrice;

    /**
     * 秒杀总库存（仅展示用，实际扣减以 Redis 计数为准）
     */
    private Integer seckillStock;

    private Integer maxPerUser;

    /**
     * 开抢时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}
