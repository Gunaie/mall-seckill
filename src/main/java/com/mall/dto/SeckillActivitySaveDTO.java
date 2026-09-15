package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 秒杀活动创建入参
 *
 * @author mall
 */
@Data
public class SeckillActivitySaveDTO {

    @NotBlank(message = "活动名称不能为空")
    private String name;
}
