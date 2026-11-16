package com.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息参数（mall.rocketmq）
 * <p>
 * enabled=false 时秒杀下单回退到 RabbitMQ 发送（本地开发/单测环境无需启动 Broker）。
 *
 * @author mall
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.rocketmq")
public class RocketMqProperties {

    /**
     * 事务消息开关：true 时使用 RocketMQ 事务消息替换 RabbitMQ 秒杀下单投递
     */
    private boolean enabled = false;

    /**
     * NameServer 地址，多个用分号分隔
     */
    private String nameServer = "localhost:9876";

    /**
     * 秒杀下单主题
     */
    private String topic = "seckill-order-topic";

    /**
     * 消息 Tag，便于按业务过滤订阅
     */
    private String tag = "seckill-order";

    /**
     * 事务生产者组（同一组内 Broker 回查请求会落在任一存活实例）
     */
    private String producerGroup = "seckill-order-tx-producer";

    /**
     * 消费者组，消费重试耗尽后的死信主题为 %DLQ%seckill-order-consumer
     */
    private String consumerGroup = "seckill-order-consumer";

    /**
     * 消费者并发线程上限（单个实例）
     */
    private int consumeThreadMax = 20;

    /**
     * 本地事务结果标记 TTL（秒），需大于 Broker 事务回查的最大时间窗口
     */
    private long txResultTtlSeconds = 3600;
}
