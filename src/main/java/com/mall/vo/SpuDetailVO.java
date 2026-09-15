package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品详情（Redis 缓存对象，需保证可被 JSON 反序列化）
 *
 * @author mall
 */
@Data
@Schema(description = "商品详情")
public class SpuDetailVO {

    @Schema(description = "SPU ID")
    private Long id;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图")
    private String mainImage;

    @Schema(description = "轮播图")
    private List<String> images;

    @Schema(description = "商品详情")
    private String detail;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "状态：1 上架 0 下架")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "SKU 列表")
    private List<SkuVO> skus;
}
