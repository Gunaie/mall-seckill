package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 购物车全选/取消全选请求
 *
 * @author mall
 */
@Data
@Schema(description = "购物车全选请求")
public class CartSelectAllDTO {

    @Schema(description = "true全选 false取消全选", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "勾选状态不能为空")
    private Boolean selected;
}
