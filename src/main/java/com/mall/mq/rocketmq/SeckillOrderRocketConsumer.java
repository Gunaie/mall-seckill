package com.mall.mq.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.config.RocketMqProperties;
import com.mall.metrics.SeckillMetrics;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.service.SeckillService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀下单 RocketMQ 消费者（mall.rocketmq.enabled=true）。
 * <p>
 * 消费语义：
 * <ul>
 *     <li>业务成功 / 重复消费：CONSUME_SUCCESS（幂等由 mq_consume_log + DB 唯一索引保证）</li>
 *     <li>业务失败（BusinessException，落单事务已回滚）：补偿 Redis 预减库存后丢弃，
 *         语义与 RabbitMQ 版 listener 的「补偿 + 进死信」一致，用户轮询结果可见失败</li>
 *     <li>系统异常：RECONSUME_LATER 由 Broker 重投，重试耗尽后进入 %DLQ%seckill-order-consumer</li>
 * </ul>
 * 启动时 Broker 可能尚未就绪（docker compose 并行启动），失败后每 10s 重建消费者重试。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class SeckillOrderRocketConsumer {

    private static final int MAX_START_ATTEMPTS = 12;

    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;
    private final SeckillService seckillService;
    private final SeckillMetrics seckillMetrics;

    private DefaultMQPushConsumer consumer;
    private ScheduledExecutorService retryExecutor;

    @PostConstruct
    public void start() {
        retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seckill-consumer-start");
            thread.setDaemon(true);
            return thread;
        });
        startWithRetry(1);
    }

    private void startWithRetry(int attempt) {
        try {
            consumer = createConsumer();
            consumer.start();
            log.info("RocketMQ 秒杀下单消费者已启动, group={}, topic={}, tag={}",
                    properties.getConsumerGroup(), properties.getTopic(), properties.getTag());
        } catch (Exception e) {
            if (attempt >= MAX_START_ATTEMPTS) {
                log.error("RocketMQ 消费者启动失败已达 {} 次，停止重试", MAX_START_ATTEMPTS, e);
                return;
            }
            log.warn("RocketMQ 消费者启动失败（第 {} 次），10s 后重试", attempt, e);
            retryExecutor.schedule(() -> startWithRetry(attempt + 1), 10, TimeUnit.SECONDS);
        }
    }

    private DefaultMQPushConsumer createConsumer() throws Exception {
        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        pushConsumer.setNamesrvAddr(properties.getNameServer());
        pushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        pushConsumer.setConsumeThreadMin(5);
        pushConsumer.setConsumeThreadMax(properties.getConsumeThreadMax());
        pushConsumer.subscribe(properties.getTopic(), properties.getTag());
        pushConsumer.setMessageListener((MessageListenerConcurrently) this::consume);
        return pushConsumer;
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages, Object context) {
        for (MessageExt msg : messages) {
            SeckillOrderMessage message = null;
            try {
                message = objectMapper.readValue(msg.getBody(), SeckillOrderMessage.class);
                seckillService.handleSeckillOrder(message);
            } catch (BusinessException e) {
                log.warn("秒杀下单业务失败，补偿 Redis 预减, msgId={}, reconsumeTimes={}",
                        msg.getMsgId(), msg.getReconsumeTimes(), e);
                seckillMetrics.recordConsume(SeckillMetrics.CONSUME_FAIL);
                if (message != null) {
                    seckillService.compensateOnConsumeFailure(message);
                }
            } catch (Exception e) {
                log.error("秒杀下单消费异常，稍后由 Broker 重投, msgId={}, reconsumeTimes={}",
                        msg.getMsgId(), msg.getReconsumeTimes(), e);
                seckillMetrics.recordConsume(SeckillMetrics.CONSUME_FAIL);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
