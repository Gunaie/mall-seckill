package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.mq.message.SeckillOrderMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀下单死信队列消费者。
 * <p>
 * 当 {@link SeckillOrderListener} 消费失败时（basicNack requeue=false），
 * 消息经死信交换机路由至此队列。本消费者负责记录失败消息详情并告警，
 * 避免消息静默丢失。生产环境可在此接入告警系统（钉钉/飞书/邮件），
 * 或提供人工重试入口将消息重新投递回秒杀下单队列。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderDlxListener {

    @RabbitListener(queues = MqConstants.SECKILL_ORDER_DLX_QUEUE)
    public void onDeadLetter(@Payload SeckillOrderMessage message,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                             @Header(value = "x-death", required = false) java.util.List<java.util.Map<String, Object>> xDeath) throws IOException {
        try {
            // 记录死信原因与消息详情，便于人工排查
            String reason = xDeath != null && !xDeath.isEmpty()
                    ? String.valueOf(xDeath.get(0).get("reason"))
                    : "unknown";
            log.error("【秒杀下单死信告警】消息消费失败转入 DLQ, messageId={}, userId={}, seckillSkuId={}, reason={}",
                    message.getMessageId(), message.getUserId(), message.getSeckillSkuId(), reason);

            // TODO 生产环境：接入告警系统（钉钉/飞书 Webhook），通知运维人工介入
            // TODO 或提供管理端「重试」按钮，将消息重新投递至 seckill.order.queue

            // 死信消息直接 ACK 确认（已记录，不再重试，避免毒消息无限循环）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("秒杀死信处理异常, messageId={}", message.getMessageId(), e);
            // 异常时也 ACK，避免 DLQ 自身出现毒消息循环
            channel.basicAck(deliveryTag, false);
        }
    }
}
