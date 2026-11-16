package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.service.SeckillLocalTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 版秒杀下单投递（mall.rocketmq.enabled=false，本地开发/单测默认）。
 * <p>
 * 与 RocketMQ 事务消息版共用同一个本地事务（Redis Lua 预减），只是消息通道不同：
 * 预减成功后投递到秒杀下单交换机，消费端完成 DB 扣减与订单落库。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class SeckillOrderSender implements SeckillOrderDispatcher {

    private final RabbitTemplate rabbitTemplate;
    private final SeckillLocalTransactionService localTransactionService;

    @Override
    public SeckillDispatchStatus dispatch(SeckillOrderMessage message) {
        SeckillDispatchStatus status =
                localTransactionService.executePreDeduct(message.getUserId(), message.getSeckillSkuId());
        if (status != SeckillDispatchStatus.QUEUED) {
            return status;
        }
        CorrelationData correlationData =
                new CorrelationData(message.getUserId() + ":" + message.getSeckillSkuId() + ":" + System.nanoTime());
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_ORDER_EXCHANGE,
                MqConstants.SECKILL_ORDER_ROUTING_KEY, message, correlationData);
        log.info("秒杀下单消息已发送, userId={}, seckillSkuId={}", message.getUserId(), message.getSeckillSkuId());
        return SeckillDispatchStatus.QUEUED;
    }
}
