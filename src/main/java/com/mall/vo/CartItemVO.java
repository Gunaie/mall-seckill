package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车项展示对象（价格/库存/名称均为查询时刻的实时数据）
 *
 * @author mall
 */
@Data
@Schema(description = "购物车项")
public class CartItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "SKU ID")
    private Long skuId;

    @Schema(description = "SPU ID")
    private Long spuId;

    @Schema(description = "SKU 编码")
    private String skuCode;

    @Schema(description = "SKU 名称")
    private String skuName;

    @Schema(description = "规格文本，如 颜色:曜石黑;内存:256G")
    private String specText;

    @Schema(description = "SKU 图片")
    private String image;

    @Schema(description = "实时单价")
    private BigDecimal price;

    @Schema(description = "实时库存")
    private Integer stock;

    @Schema(description = "购买数量")
    private Integer quantity;

    @Schema(description = "是否勾选")
    private Boolean selected;

    @Schema(description = "是否有效（SKU 与 SPU 均存在且上架）")
    private Boolean onSale;

    @Schema(description = "库存是否充足")
    private Boolean stockEnough;
}
