package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单列表项 VO
 *
 * @author mall
 */
@Data
@Schema(description = "订单列表项")
public class OrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单号")
    private String orderSn;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名（管理端展示）")
    private String username;

    @Schema(description = "订单总金额")
    private BigDecimal totalAmount;

    @Schema(description = "订单状态码")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "支付截止时间")
    private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "订单明细")
    private List<OrderItemVO> items;
}
