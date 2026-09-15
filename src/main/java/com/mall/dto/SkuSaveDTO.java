package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SKU 保存项
 *
 * @author mall
 */
@Data
@Schema(description = "SKU 保存项")
public class SkuSaveDTO {

    @Schema(description = "SKU 编码（唯一）", example = "XH-X5PRO-BK-256")
    @NotBlank(message = "SKU 编码不能为空")
    private String skuCode;

    @Schema(description = "SKU 名称", example = "星河 X5 Pro 曜石黑 256G")
    @NotBlank(message = "SKU 名称不能为空")
    private String name;

    @Schema(description = "规格键值对", example = "{\"颜色\":\"曜石黑\",\"内存\":\"256G\"}")
    private Map<String, String> spec;

    @Schema(description = "销售价（元）", example = "3999.00")
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    @Schema(description = "库存", example = "500")
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @Schema(description = "SKU 图片")
    private String image;
}
