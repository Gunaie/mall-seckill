package com.mall.metrics;

import com.mall.common.constant.MqConstants;
import com.mall.common.constant.CacheConstants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时导出秒杀可观测性 Gauge：
 * <ul>
 *     <li>mall_seckill_stock_remaining{skuId}：Redis 预热剩余库存（SCAN 扫描，避免 KEYS 阻塞）</li>
 *     <li>mall_rabbitmq_queue_messages{queue}：秒杀/关单相关队列消息积压量（passive declare 获取）</li>
 * </ul>
 * 双实例部署时两个实例分别上报相同逻辑值，PromQL 用 max/sum 聚合即可，instance 标签可区分来源。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillMetricsExporter {

    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitAdmin rabbitAdmin;
    private final MeterRegistry meterRegistry;

    /** skuId -> 库存 Gauge 持有器（Gauge 不保持对象引用，必须自行持有避免被 GC） */
    private final Map<Long, AtomicLong> stockHolders = new ConcurrentHashMap<>();

    /** queue -> 队列消息数 Gauge 持有器 */
    private final Map<String, AtomicLong> queueHolders = new ConcurrentHashMap<>();

    /** 重点观测的队列：秒杀异步下单 / 订单延迟 / 死信关单 */
    private static final List<String> WATCHED_QUEUES = List.of(
            MqConstants.SECKILL_ORDER_QUEUE,
            MqConstants.ORDER_DELAY_QUEUE,
            MqConstants.ORDER_CLOSE_QUEUE);

    /**
     * 启动 10s 后首次采集，之后每 15s 一次。
     * Redis 与 RabbitMQ 两段互不影响，单点异常不影响另一指标更新。
     */
    @Scheduled(fixedDelay = 15_000L, initialDelay = 10_000L)
    public void export() {
        exportRedisStock();
        exportQueueMessages();
    }

    private void exportRedisStock() {
        try {
            Set<String> keys = new HashSet<>();
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                        ScanOptions.scanOptions()
                                .match(CacheConstants.SECKILL_STOCK_KEY + "*")
                                .count(200)
                                .build())) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return null;
            });

            if (keys.isEmpty()) {
                return;
            }
            List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
            int i = 0;
            for (String key : keys) {
                String value = values == null ? null : values.get(i++);
                if (value == null) {
                    continue;
                }
                long remaining;
                try {
                    remaining = Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                Long skuId = Long.valueOf(key.substring(CacheConstants.SECKILL_STOCK_KEY.length()));
                stockHolders.computeIfAbsent(skuId, id -> {
                    AtomicLong holder = new AtomicLong();
                    Gauge.builder("mall.seckill.stock.remaining", holder, AtomicLong::doubleValue)
                            .tag("skuId", String.valueOf(id))
                            .description("Seckill stock remaining in Redis")
                            .register(meterRegistry);
                    return holder;
                }).set(remaining);
            }
        } catch (Exception e) {
            log.warn("导出秒杀库存指标失败", e);
        }
    }

    private void exportQueueMessages() {
        for (String queue : WATCHED_QUEUES) {
            try {
                QueueInformation info = rabbitAdmin.getQueueInfo(queue);
                long count = info == null ? 0L : info.getMessageCount();
                queueHolders.computeIfAbsent(queue, q -> {
                    AtomicLong holder = new AtomicLong();
                    Gauge.builder("mall.rabbitmq.queue.messages", holder, AtomicLong::doubleValue)
                            .tag("queue", q)
                            .description("RabbitMQ ready message count")
                            .register(meterRegistry);
                    return holder;
                }).set(count);
            } catch (Exception e) {
                log.warn("导出队列积压指标失败, queue={}", queue, e);
            }
        }
    }
}
