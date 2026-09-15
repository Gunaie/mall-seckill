package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SKU 展示对象
 *
 * @author mall
 */
@Data
@Schema(description = "SKU 信息")
public class SkuVO {

    @Schema(description = "SKU ID")
    private Long id;

    @Schema(description = "SKU 编码")
    private String skuCode;

    @Schema(description = "SKU 名称")
    private String name;

    @Schema(description = "规格键值对")
    private Map<String, String> spec;

    @Schema(description = "销售价")
    private BigDecimal price;

    @Schema(description = "库存")
    private Integer stock;

    @Schema(description = "SKU 图片")
    private String image;
}
