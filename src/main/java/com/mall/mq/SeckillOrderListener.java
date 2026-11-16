package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.metrics.SeckillMetrics;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.service.SeckillService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀下单消费者（RabbitMQ 版，mall.rocketmq.enabled=false 时生效）：DB 条件扣减 + 一人一单兜底 + 订单落库。
 * 消费失败时先补偿 Redis 库存，再 basicNack(requeue=false) 将消息路由至死信队列(DLQ)，
 * 由 {@link SeckillOrderDlxListener} 记录告警，避免消息静默丢失或毒消息循环。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class SeckillOrderListener {

    private final SeckillService seckillService;
    private final SeckillMetrics seckillMetrics;

    @RabbitListener(queues = MqConstants.SECKILL_ORDER_QUEUE)
    public void onMessage(@Payload SeckillOrderMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            seckillService.handleSeckillOrder(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("秒杀下单消费失败, userId={}, seckillSkuId={}",
                    message.getUserId(), message.getSeckillSkuId(), e);
            seckillMetrics.recordConsume(SeckillMetrics.CONSUME_FAIL);
            // 补偿：归还 Redis 预减库存并移除一人一单标记，让用户可重试
            seckillService.compensateOnConsumeFailure(message);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
