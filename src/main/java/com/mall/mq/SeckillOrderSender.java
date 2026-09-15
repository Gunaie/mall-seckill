package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.mq.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消息生产者：投递到秒杀下单交换机，MQ 削峰填谷，
 * 消费端完成 DB 扣减与订单落库后，用户通过结果轮询接口感知成败。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderSender {

    private final RabbitTemplate rabbitTemplate;

    public void sendSeckillOrder(SeckillOrderMessage message) {
        CorrelationData correlationData =
                new CorrelationData(message.getUserId() + ":" + message.getSeckillSkuId() + ":" + System.nanoTime());
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_ORDER_EXCHANGE,
                MqConstants.SECKILL_ORDER_ROUTING_KEY, message, correlationData);
        log.info("秒杀下单消息已发送, userId={}, seckillSkuId={}", message.getUserId(), message.getSeckillSkuId());
    }
}
