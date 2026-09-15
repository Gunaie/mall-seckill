package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 秒杀下单入参（收货信息，下单瞬间快照）
 *
 * @author mall
 */
@Data
public class SeckillExecuteDTO {

    @NotBlank(message = "收货人不能为空")
    private String receiverName;

    @NotBlank(message = "收货电话不能为空")
    private String receiverPhone;

    @NotBlank(message = "收货地址不能为空")
    private String receiverAddress;

    private String remark;
}
