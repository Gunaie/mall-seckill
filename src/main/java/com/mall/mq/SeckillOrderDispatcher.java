package com.mall.mq;

import com.mall.mq.message.SeckillOrderMessage;

/**
 * 秒杀下单消息投递抽象。
 * <p>
 * 两种实现由 mall.rocketmq.enabled 二选一：RocketMQ 事务消息（生产/压测）或 RabbitMQ（本地开发/单测），
 * 业务侧只关心「库存是否预减成功 + 消息是否投递」，不感知具体中间件。
 *
 * @author mall
 */
public interface SeckillOrderDispatcher {

    /**
     * 执行本地事务（Redis 预减库存/一人一单判重）并投递下单消息
     *
     * @param message 下单消息（messageId 由构造时生成，用于消费幂等与事务回查）
     * @return 本地事务结果
     */
    SeckillDispatchStatus dispatch(SeckillOrderMessage message);
}
