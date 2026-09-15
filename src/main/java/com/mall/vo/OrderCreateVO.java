package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单结果
 *
 * @author mall
 */
@Data
@Schema(description = "下单结果")
public class OrderCreateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderSn;

    @Schema(description = "订单总金额")
    private BigDecimal totalAmount;

    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;
}
