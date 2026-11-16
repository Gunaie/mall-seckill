package com.mall.mq;

import com.mall.common.constant.MqConstants;
import com.mall.mq.message.OrderDelayMessage;
import com.mall.service.OrderService;
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
 * 订单超时关单消费者：
 * 消费死信队列中的延迟消息，对仍处于待支付的订单执行 CAS 关单并回补库存。
 * 业务天然幂等（只有 status=1 能被关闭），消息重复投递安全。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseListener {

    private final OrderService orderService;

    @RabbitListener(queues = MqConstants.ORDER_CLOSE_QUEUE)
    public void onMessage(@Payload OrderDelayMessage message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            orderService.closeExpiredOrder(message.getOrderId(), message.getUserId(), message.getMessageId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单超时关单消费失败, orderId={}, orderSn={}",
                    message.getOrderId(), message.getOrderSn(), e);
            // 不重回队列，避免异常消息无限循环；生产环境应转入异常队列并告警人工介入
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
