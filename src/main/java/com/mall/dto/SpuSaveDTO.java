package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * SPU 新增/修改请求（级联保存 SKU 列表）
 *
 * @author mall
 */
@Data
@Schema(description = "商品新增/修改请求")
public class SpuSaveDTO {

    @Schema(description = "SPU ID（修改时必传）")
    private Long id;

    @Schema(description = "商品名称", example = "星河 X5 Pro 智能手机")
    @NotBlank(message = "商品名称不能为空")
    private String name;

    @Schema(description = "三级分类ID", example = "10101")
    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @Schema(description = "副标题/卖点")
    private String subtitle;

    @Schema(description = "主图地址")
    private String mainImage;

    @Schema(description = "轮播图地址列表")
    private List<String> images;

    @Schema(description = "商品详情（富文本）")
    private String detail;

    @Schema(description = "状态：1 上架 0 下架", example = "1")
    private Integer status = 1;

    @Schema(description = "SKU 列表（至少一个）")
    @NotEmpty(message = "至少需要一个 SKU")
    @Valid
    private List<SkuSaveDTO> skus;
}
