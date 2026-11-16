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
 * 秒杀订单关联实体（一人一单 DB 级兜底：uk_user_sku 唯一索引）
 *
 * @author mall
 */
@Data
@TableName("sms_seckill_order")
public class SmsSeckillOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分片后各物理表独立自增会产生重复 ID，统一由 MyBatis-Plus 雪花算法在客户端生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long seckillSkuId;

    private Long sessionId;

    private Long userId;

    private Long orderId;

    private String orderSn;

    /**
     * 成交秒杀价快照
     */
    private BigDecimal seckillPrice;

    private Integer quantity;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
