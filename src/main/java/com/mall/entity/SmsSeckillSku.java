package com.mall.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品关联实体（场次 x SKU，秒杀库存与普通库存隔离）
 *
 * @author mall
 */
@Data
@TableName("sms_seckill_sku")
public class SmsSeckillSku implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long sessionId;

    private Long skuId;

    /**
     * 秒杀价
     */
    private BigDecimal seckillPrice;

    /**
     * 秒杀库存（与 pms_sku.stock 隔离）
     */
    private Integer seckillStock;

    /**
     * 已秒杀数量
     */
    private Integer seckillCount;

    /**
     * 每人限购（当前版本固定 1，uk_user_sku 唯一索引兜底）
     */
    private Integer maxPerUser;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
