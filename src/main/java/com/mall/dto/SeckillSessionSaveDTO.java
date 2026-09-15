package com.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀场次创建入参
 *
 * @author mall
 */
@Data
public class SeckillSessionSaveDTO {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotBlank(message = "场次名称不能为空")
    private String name;

    @NotNull(message = "开抢时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
}
