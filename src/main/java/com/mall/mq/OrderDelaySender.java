package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.mq.message.OrderDelayMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单延迟消息生产者：发送到 TTL 延迟队列（默认交换机，routingKey 即队列名），
 * 消息在延迟队列中等待订单支付超时，过期后经死信交换机路由到关单队列。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelaySender {

    private final RabbitTemplate rabbitTemplate;

    public void sendDelayClose(OrderDelayMessage message) {
        // CorrelationData 用于 publisher confirm 回调中定位消息
        CorrelationData correlationData = new CorrelationData(message.getOrderSn());
        rabbitTemplate.convertAndSend("", MqConstants.ORDER_DELAY_QUEUE, message, correlationData);
        log.info("订单超时关单延迟消息已发送, orderId={}, orderSn={}", message.getOrderId(), message.getOrderSn());
    }
}
