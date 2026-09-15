package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分类新增/修改请求（id 为空表示新增）
 *
 * @author mall
 */
@Data
@Schema(description = "分类新增/修改请求")
public class CategorySaveDTO {

    @Schema(description = "分类ID（修改时必传）")
    private Long id;

    @Schema(description = "父分类ID，0 表示一级分类", example = "0")
    @NotNull(message = "父分类ID不能为空")
    private Long parentId;

    @Schema(description = "分类名称", example = "智能手机")
    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Schema(description = "图标地址")
    private String icon;

    @Schema(description = "排序", example = "1")
    private Integer sort = 0;

    @Schema(description = "状态：1 启用 0 禁用", example = "1")
    private Integer status = 1;
}
