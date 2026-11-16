package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品搜索结果项
 *
 * @author mall
 */
@Data
@Schema(description = "商品搜索结果项")
public class SpuSearchVO {

    @Schema(description = "SPU ID")
    private Long id;

    @Schema(description = "商品名称（纯文本）")
    private String name;

    @Schema(description = "高亮后的商品名称（含 em 标签；无关键词时为空）")
    private String highlightName;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "起售价")
    private BigDecimal minPrice;

    @Schema(description = "状态：1 上架 0 下架")
    private Integer status;
}
