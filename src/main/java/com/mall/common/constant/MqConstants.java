package com.mall.common.constant;

/**
 * RabbitMQ 交换机 / 队列 / 路由键统一管理
 * <p>
 * 订单超时关单采用「TTL 延迟队列 + 死信交换机」方案（无需延迟插件）：
 * <pre>
 * 生产者 -> order.delay.queue（消息存活 timeout 秒，无消费者）
 *              消息过期成为死信
 *           -> order.close.exchange（DLX）
 *           -> order.close.queue
 *           -> 关单消费者（CAS 幂等关单 + 回补库存）
 * </pre>
 *
 * @author mall
 */
public final class MqConstants {

    private MqConstants() {
    }

    /** 延迟队列：消息在此等待订单支付超时（不设消费者） */
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";

    /** 死信交换机：延迟队列消息过期后投递至此 */
    public static final String ORDER_CLOSE_EXCHANGE = "order.close.exchange";

    /** 死信路由键 */
    public static final String ORDER_CLOSE_ROUTING_KEY = "order.close.rk";

    /** 关单队列：真正被消费的队列 */
    public static final String ORDER_CLOSE_QUEUE = "order.close.queue";

    // ============================== 秒杀异步下单 ==============================

    /** 秒杀下单交换机（Direct）：Redis 预减库存成功后投递，削峰填谷 */
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";

    /** 秒杀下单路由键 */
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.rk";

    /** 秒杀下单队列：消费者完成 DB 扣减 + 真实订单落库 */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    // ============================== 秒杀下单死信队列（消费失败兜底） ==============================

    /** 秒杀下单死信交换机：消费失败（basicNack requeue=false）的消息路由至此 */
    public static final String SECKILL_ORDER_DLX_EXCHANGE = "seckill.order.dlx.exchange";

    /** 秒杀下单死信路由键 */
    public static final String SECKILL_ORDER_DLX_ROUTING_KEY = "seckill.order.dlx.rk";

    /** 秒杀下单死信队列：存放消费失败的消息，便于人工介入/重试，避免消息直接丢失 */
    public static final String SECKILL_ORDER_DLX_QUEUE = "seckill.order.dlx.queue";
}
