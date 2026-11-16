package com.mall.mq;

/**
 * 秒杀下单投递结果，由本地事务（Redis Lua 预减）的直接结果决定，
 * 调用方据此映射成排队中 / 售罄 / 重复抢购 / 系统繁忙。
 *
 * @author mall
 */
public enum SeckillDispatchStatus {

    /** 预减成功，消息已投递（RocketMQ 半消息已 commit / RabbitMQ 已发送） */
    QUEUED,

    /** Lua 返回库存不足 */
    SOLD_OUT,

    /** Lua 返回该用户已购 */
    REPEATED,

    /** 本地事务未执行或投递失败，库存未预减 */
    UNKNOWN
}
