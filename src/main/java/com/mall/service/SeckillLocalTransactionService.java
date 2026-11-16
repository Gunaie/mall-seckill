package com.mall.service;

import com.mall.common.constant.CacheConstants;
import com.mall.config.RocketMqProperties;
import com.mall.mq.SeckillDispatchStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀下单「本地事务」：Redis Lua 原子预减库存 + 一人一单判重。
 * <p>
 * RocketMQ 事务消息模型中的本地事务就是这一步：半消息发送成功后执行，
 * 成功返回 QUEUED（生产端 commit 半消息），库存不足/重复则返回 SOLD_OUT/REPEATED（rollback）。
 * 同时把本次事务结果写入 Redis 标记，供 Broker 回查 {@code checkLocalTransaction} 使用，
 * 回查时不再重复执行 Lua，也不会因为「已购集合」里存在该用户而误判本次事务成功。
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillLocalTransactionService {

    /**
     * Redis 预减库存 Lua（原子性保证不超卖）：
     * KEYS[1]=库存计数, KEYS[2]=已购集合, KEYS[3]=结果 key；ARGV[1]=购买数量, ARGV[2]=用户ID
     * 返回 -1 重复购买 / 0 库存不足 / 1 预减成功（计数减 1 + 用户加入已购集合 + 清空上次结果）
     * <p>
     * 预减成功时顺带删除上次的抢购结果 key，避免前端轮询读到旧结果，
     * 同时省掉一次独立 Redis 往返（P5 热路径优化）。
     */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then " +
                "    return -1 " +
                "end " +
                "local stock = tonumber(redis.call('get', KEYS[1])) " +
                "if stock == nil or stock < tonumber(ARGV[1]) then " +
                "    return 0 " +
                "end " +
                "redis.call('decrby', KEYS[1], ARGV[1]) " +
                "redis.call('sadd', KEYS[2], ARGV[2]) " +
                "redis.call('del', KEYS[3]) " +
                "return 1");
        script.setResultType(Long.class);
        PRE_DEDUCT_SCRIPT = script;
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMqProperties rocketMqProperties;

    /**
     * 执行本地事务：Lua 原子预减库存 + 一人一单判重
     *
     * @return QUEUED 预减成功 / SOLD_OUT 库存不足 / REPEATED 重复抢购 / UNKNOWN 脚本执行异常
     */
    public SeckillDispatchStatus executePreDeduct(Long userId, Long seckillSkuId) {
        Long result = stringRedisTemplate.execute(PRE_DEDUCT_SCRIPT,
                List.of(stockKey(seckillSkuId), orderedSetKey(seckillSkuId), resultKey(userId, seckillSkuId)),
                "1", String.valueOf(userId));
        if (result == null) {
            log.error("秒杀库存预减脚本无返回, userId={}, seckillSkuId={}", userId, seckillSkuId);
            return SeckillDispatchStatus.UNKNOWN;
        }
        if (result == 0L) {
            return SeckillDispatchStatus.SOLD_OUT;
        }
        if (result == -1L) {
            return SeckillDispatchStatus.REPEATED;
        }
        // 预减成功：结果 key 已在 Lua 中删除，避免前端轮询读到上次结果
        return SeckillDispatchStatus.QUEUED;
    }

    /**
     * 记录本地事务结果标记（Broker 事务回查时读取）
     */
    public void markTxResult(String messageId, SeckillDispatchStatus status) {
        try {
            stringRedisTemplate.opsForValue().set(CacheConstants.SECKILL_TX_RESULT_KEY + messageId,
                    status.name(), rocketMqProperties.getTxResultTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // 标记写入失败不影响本地事务已提交的事实（库存已预减），仅影响回查准确性
            log.error("事务结果标记写入失败, messageId={}, status={}", messageId, status, e);
        }
    }

    /**
     * 读取本地事务结果标记，为空表示本地事务未执行或标记已过期
     */
    public SeckillDispatchStatus getTxResult(String messageId) {
        String value = stringRedisTemplate.opsForValue().get(CacheConstants.SECKILL_TX_RESULT_KEY + messageId);
        if (value == null) {
            return null;
        }
        try {
            return SeckillDispatchStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("事务结果标记内容非法, messageId={}, value={}", messageId, value);
            return null;
        }
    }

    private String stockKey(Long seckillSkuId) {
        return CacheConstants.SECKILL_STOCK_KEY + seckillSkuId;
    }

    private String orderedSetKey(Long seckillSkuId) {
        return CacheConstants.SECKILL_ORDERED_SET_KEY + seckillSkuId;
    }

    private String resultKey(Long userId, Long seckillSkuId) {
        return CacheConstants.SECKILL_RESULT_KEY + userId + ":" + seckillSkuId;
    }
}
