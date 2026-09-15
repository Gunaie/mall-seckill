package com.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品 VO（用户端场次商品列表 / 管理端场次商品列表共用）
 *
 * @author mall
 */
@Data
public class SeckillSkuVO {

    /**
     * 秒杀商品ID（sms_seckill_sku.id，秒杀下单入参）
     */
    private Long id;

    private Long sessionId;

    private Long skuId;

    private String spuName;

    private String skuName;

    private String image;

    /**
     * 原价
     */
    private BigDecimal originalPrice;

    /**
     * 秒杀价
     */
    private BigDecimal seckillPrice;

    /**
     * 剩余可抢数量
     */
    private Integer stockRemaining;

    private Integer maxPerUser;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
