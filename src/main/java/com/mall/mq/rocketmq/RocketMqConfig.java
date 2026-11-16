package com.mall.mq.rocketmq;

import com.mall.config.RocketMqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ 事务消息生产者配置（mall.rocketmq.enabled=true 时生效）。
 * <p>
 * 事务消息链路：
 * <pre>
 * sendMessageInTransaction
 *   -> 1.发送半消息（对消费者不可见）
 *   -> 2.executeLocalTransaction：Redis Lua 预减库存（本地事务）
 *   -> 3.commit / rollback 半消息
 *   -> 4.Broker 长时间未收到二次确认时回查 checkLocalTransaction（读取 Redis 事务结果标记）
 * </pre>
 *
 * @author mall
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqConfig {

    @Bean(destroyMethod = "shutdown")
    public TransactionMQProducer seckillTransactionProducer(RocketMqProperties properties,
                                                            SeckillOrderTransactionListener transactionListener)
            throws MQClientException {
        TransactionMQProducer producer = new TransactionMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendFailed(2);
        // 事务回查线程池：Broker 回查请求由该线程池处理，避免与业务线程互相阻塞
        producer.setExecutorService(new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000),
                runnable -> {
                    Thread thread = new Thread(runnable, "seckill-tx-check");
                    thread.setDaemon(true);
                    return thread;
                }));
        producer.setTransactionListener(transactionListener);
        producer.start();
        log.info("RocketMQ 事务生产者已启动, group={}, nameServer={}",
                properties.getProducerGroup(), properties.getNameServer());
        return producer;
    }
}
