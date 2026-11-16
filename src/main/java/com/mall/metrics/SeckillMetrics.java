package com.mall.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 秒杀链路自定义指标（Micrometer，输出到 Prometheus）：
 * <ul>
 *     <li>mall_seckill_execute_total{result=...}：execute 各出口计数（排队/售罄/重复/非法路径等）</li>
 *     <li>mall_seckill_execute_duration_seconds_bucket{result=...}：execute 耗时直方图（支持 P95/P99）</li>
 *     <li>mall_seckill_consume_total{result=...}：MQ 消费结果计数（成功/幂等跳过/失败）</li>
 *     <li>rocketmq_tx_commit_total / rocketmq_tx_rollback_total / rocketmq_tx_check_total：
 *         RocketMQ 事务消息的最终提交、最终回滚与 Broker 回查次数</li>
 * </ul>
 *
 * @author mall
 */
@Component
public class SeckillMetrics {

    // ============================== execute 出口结果 ==============================

    /** Redis 预减成功，已投递 MQ 排队 */
    public static final String EXECUTE_QUEUED = "queued";
    /** 命中本地内存售罄标记，0 网络 IO 拒绝 */
    public static final String EXECUTE_SOLD_OUT_LOCAL = "sold_out_local";
    /** Redis Lua 返回库存不足 */
    public static final String EXECUTE_SOLD_OUT = "sold_out";
    /** 一人一单判重命中 */
    public static final String EXECUTE_REPEATED = "repeated";
    /** 动态路径缺失/过期/伪造 */
    public static final String EXECUTE_INVALID_PATH = "invalid_path";
    /** 商品未上架/不在秒杀时间窗口 */
    public static final String EXECUTE_INVALID = "invalid";
    /** 未预期异常 */
    public static final String EXECUTE_ERROR = "error";

    // ============================== MQ 消费结果 ==============================

    public static final String CONSUME_SUCCESS = "success";
    /** 重复消息命中幂等检查，跳过落单 */
    public static final String CONSUME_DUPLICATE = "duplicate";
    /** 消费抛出异常（已触发库存补偿） */
    public static final String CONSUME_FAIL = "fail";

    private final MeterRegistry meterRegistry;

    public SeckillMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 开启计时采样，与 {@link #recordExecute} 成对使用
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 记录一次 execute 调用：出口计数 + 分结果耗时直方图。
     * 放在 finally 中执行，业务异常（BusinessException）也能被统计。
     */
    public void recordExecute(Timer.Sample sample, String result) {
        sample.stop(Timer.builder("mall.seckill.execute.duration")
                .description("Seckill execute latency")
                .tag("result", result)
                // 输出 Prometheus histogram bucket，Grafana 可算 P95/P99
                .publishPercentileHistogram()
                .register(meterRegistry));
        meterRegistry.counter("mall.seckill.execute.total", "result", result).increment();
    }

    /**
     * 记录一次秒杀 MQ 消费结果
     */
    public void recordConsume(String result) {
        meterRegistry.counter("mall.seckill.consume.total", "result", result).increment();
    }

    // ============================== RocketMQ 事务消息 ==============================

    /**
     * 半消息最终提交（本地事务成功，或 Broker 回查确认成功后提交）
     */
    public void recordTxCommit() {
        meterRegistry.counter("rocketmq.tx.commit").increment();
    }

    /**
     * 半消息最终回滚（售罄/重复/本地事务标记缺失）
     */
    public void recordTxRollback() {
        meterRegistry.counter("rocketmq.tx.rollback").increment();
    }

    /**
     * Broker 触发一次事务回查
     */
    public void recordTxCheck() {
        meterRegistry.counter("rocketmq.tx.check").increment();
    }
}
