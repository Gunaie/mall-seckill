package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细 VO（快照数据）
 *
 * @author mall
 */
@Data
@Schema(description = "订单明细")
public class OrderItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "明细ID")
    private Long id;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "SPU ID")
    private Long spuId;

    @Schema(description = "SPU 名称快照")
    private String spuName;

    @Schema(description = "SKU 名称快照")
    private String skuName;

    @Schema(description = "规格快照文本")
    private String specText;

    @Schema(description = "SKU 图片快照")
    private String skuImage;

    @Schema(description = "下单单价快照")
    private BigDecimal price;

    @Schema(description = "购买数量")
    private Integer quantity;

    @Schema(description = "明细小计")
    private BigDecimal totalPrice;
}
