package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 商品分页查询条件
 *
 * @author mall
 */
@Data
@Schema(description = "商品分页查询条件")
public class SpuPageQuery {

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "商品名称关键词")
    private String keyword;

    @Schema(description = "分类ID（精确匹配某一级分类）")
    private Long categoryId;
}
