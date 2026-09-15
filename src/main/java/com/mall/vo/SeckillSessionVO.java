package com.mall.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀场次 VO
 *
 * @author mall
 */
@Data
public class SeckillSessionVO {

    private Long id;

    private String name;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**
     * 未开始 / 进行中 / 已结束
     */
    private String statusDesc;
}
