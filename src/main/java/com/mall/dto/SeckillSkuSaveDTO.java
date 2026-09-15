package com.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 场次添加秒杀商品入参
 *
 * @author mall
 */
@Data
public class SeckillSkuSaveDTO {

    @NotNull(message = "场次ID不能为空")
    private Long sessionId;

    @NotNull(message = "SKU ID不能为空")
    private Long skuId;

    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于 0")
    private BigDecimal seckillPrice;

    @NotNull(message = "秒杀库存不能为空")
    @Min(value = 1, message = "秒杀库存至少为 1")
    private Integer seckillStock;
}
