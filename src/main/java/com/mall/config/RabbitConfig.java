package com.mall.config;

import com.mall.common.constant.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置：
 * 1. 订单超时关单：TTL 延迟队列（无消费者）+ 死信交换机 + 关单队列
 * 2. 消息体用 JSON 序列化
 * 3. 发送方确认（publisher confirm / return）回调，失败可感知
 *
 * @author mall
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /**
     * 延迟队列：消息存活 timeout 秒后过期成为死信。
     * 队列级 TTL 对所有订单统一（本项目超时时间一致）；
     * 若需每单不同超时，可改为发送时设置消息级 expiration（注意队头阻塞特性）。
     */
    @Bean
    public Queue orderDelayQueue(OrderProperties orderProperties) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", orderProperties.getTimeoutSeconds() * 1000);
        args.put("x-dead-letter-exchange", MqConstants.ORDER_CLOSE_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstants.ORDER_CLOSE_ROUTING_KEY);
        return QueueBuilder.durable(MqConstants.ORDER_DELAY_QUEUE).withArguments(args).build();
    }

    /**
     * 死信交换机（Direct）
     */
    @Bean
    public DirectExchange orderCloseExchange() {
        return new DirectExchange(MqConstants.ORDER_CLOSE_EXCHANGE, true, false);
    }

    /**
     * 关单队列：过期死信最终路由到此，由消费者处理
     */
    @Bean
    public Queue orderCloseQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_CLOSE_QUEUE).build();
    }

    @Bean
    public Binding orderCloseBinding(Queue orderCloseQueue, DirectExchange orderCloseExchange) {
        return BindingBuilder.bind(orderCloseQueue).to(orderCloseExchange).with(MqConstants.ORDER_CLOSE_ROUTING_KEY);
    }

    /**
     * 秒杀下单交换机（Direct）：Redis 预减库存成功后投递，MQ 削峰，消费端异步落单
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_EXCHANGE, true, false);
    }

    /**
     * 秒杀下单队列：消费者在此完成 DB 条件扣减 + 真实订单落库。
     * 配置死信交换机：消费失败（basicNack requeue=false）的消息自动路由到 DLQ，
     * 避免消息直接丢失；DLQ 消息可人工排查后重试或转入告警流程。
     */
    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstants.SECKILL_ORDER_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstants.SECKILL_ORDER_DLX_ROUTING_KEY);
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_QUEUE).withArguments(args).build();
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillOrderExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillOrderExchange).with(MqConstants.SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * 秒杀下单死信交换机（Direct）：消费失败消息的兜底交换机
     */
    @Bean
    public DirectExchange seckillOrderDlxExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_DLX_EXCHANGE, true, false);
    }

    /**
     * 秒杀下单死信队列：存放消费失败的消息，便于人工介入或重试
     */
    @Bean
    public Queue seckillOrderDlxQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_DLX_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderDlxBinding(Queue seckillOrderDlxQueue, DirectExchange seckillOrderDlxExchange) {
        return BindingBuilder.bind(seckillOrderDlxQueue).to(seckillOrderDlxExchange).with(MqConstants.SECKILL_ORDER_DLX_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitAdmin：启动时自动声明队列/交换机/绑定，同时提供队列状态查询
     * （监控指标采集消息积压量）。显式声明避免环境差异导致 Bean 缺失。
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /**
     * 定制 RabbitTemplate：JSON 转换 + 发送确认/不可达回调日志
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter);
        // 消息到达 broker，exchange 处理结果（ack/nack）异步回调
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (Boolean.TRUE.equals(ack)) {
                log.debug("MQ消息投递成功, correlationData={}", correlationData);
            } else {
                log.error("MQ消息投递失败, correlationData={}, cause={}", correlationData, cause);
            }
        });
        // mandatory=true 时消息无法路由到队列会触发 return 回调
        template.setReturnsCallback(returned -> {
            Message msg = returned.getMessage();
            log.error("MQ消息不可达被退回, exchange={}, routingKey={}, replyText={}, body={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyText(), new String(msg.getBody()));
        });
        return template;
    }
}
