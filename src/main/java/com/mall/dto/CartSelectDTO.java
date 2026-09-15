package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 购物车勾选请求（单个 SKU）
 *
 * @author mall
 */
@Data
@Schema(description = "购物车勾选请求")
public class CartSelectDTO {

    @Schema(description = "SKU ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "SKU ID不能为空")
    private Long skuId;

    @Schema(description = "true勾选 false取消勾选", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "勾选状态不能为空")
    private Boolean selected;
}
