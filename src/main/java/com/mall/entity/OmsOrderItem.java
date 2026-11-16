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
 * 订单明细实体（商品信息为下单瞬间快照）
 *
 * @author mall
 */
@Data
@TableName("oms_order_item")
public class OmsOrderItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分片后各物理表独立自增会产生重复 ID，统一由 MyBatis-Plus 雪花算法在客户端生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    private String orderSn;

    private Long userId;

    private Long skuId;

    private Long spuId;

    private String spuName;

    private String skuName;

    private String specText;

    private String skuImage;

    /**
     * 下单单价快照
     */
    private BigDecimal price;

    private Integer quantity;

    private BigDecimal totalPrice;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
