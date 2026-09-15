package com.mall.vo;

import lombok.Data;

/**
 * 秒杀下单结果 VO（execute 立即返回 / 轮询接口返回共用）
 *
 * @author mall
 */
@Data
public class SeckillResultVO {

    public static final String STATUS_QUEUING = "QUEUING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAIL = "FAIL";

    /**
     * QUEUING 排队中 / SUCCESS 下单成功 / FAIL 下单失败
     */
    private String status;

    /**
     * 成功时的订单ID
     */
    private Long orderId;
}
