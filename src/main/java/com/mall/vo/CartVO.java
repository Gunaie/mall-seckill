package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车整体展示对象
 *
 * @author mall
 */
@Data
@Schema(description = "购物车")
public class CartVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "购物车项列表")
    private List<CartItemVO> items;

    @Schema(description = "购物车商品总件数")
    private Integer totalCount;

    @Schema(description = "勾选商品件数")
    private Integer selectedCount;

    @Schema(description = "勾选且有效商品的合计金额")
    private BigDecimal selectedAmount;
}
