package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品分页列表项
 *
 * @author mall
 */
@Data
@Schema(description = "商品列表项")
public class SpuPageVO {

    @Schema(description = "SPU ID")
    private Long id;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "起售价（最低 SKU 价格）")
    private BigDecimal minPrice;

    @Schema(description = "状态：1 上架 0 下架")
    private Integer status;
}
