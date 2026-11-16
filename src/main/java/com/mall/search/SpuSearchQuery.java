package com.mall.search;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品搜索条件
 *
 * @author mall
 */
@Data
@Schema(description = "商品搜索条件")
public class SpuSearchQuery {

    @Schema(description = "关键词（商品名称，IK 分词）")
    private String keyword;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "最低价格")
    private BigDecimal minPrice;

    @Schema(description = "最高价格")
    private BigDecimal maxPrice;

    @Schema(description = "排序：default 综合 / priceAsc 价格升序 / priceDesc 价格降序")
    private String sort = "default";

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;
}
