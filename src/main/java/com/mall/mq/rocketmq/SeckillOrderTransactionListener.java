package com.mall.mq.rocketmq;

import com.mall.mq.SeckillDispatchStatus;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.service.SeckillLocalTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单事务监听器：实现 RocketMQ 事务消息的「本地事务」与「事务回查」。
 * <p>
 * 本地事务 = Redis Lua 原子预减库存 + 一人一单判重，结果同时写入 Redis 标记
 * （{@code seckill:tx:result:{messageId}}），供回查使用：
 * <ul>
 *     <li>executeLocalTransaction：QUEUED -> COMMIT，SOLD_OUT/REPEATED -> ROLLBACK，异常 -> UNKNOW 触发回查</li>
 *     <li>checkLocalTransaction：标记为 QUEUED 才 COMMIT，无标记（本地事务未执行/标记过期）一律 ROLLBACK，
 *         避免「已购集合」里存在该用户就误判本次事务成功（下单失败时用户不会被移出集合）</li>
 * </ul>
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class SeckillOrderTransactionListener implements TransactionListener {

    private final SeckillLocalTransactionService localTransactionService;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        if (!(arg instanceof SeckillTxContext context)) {
            log.error("事务消息上下文缺失，回滚半消息, msgId={}", msg.getKeys());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        SeckillOrderMessage message = context.getMessage();
        try {
            SeckillDispatchStatus status =
                    localTransactionService.executePreDeduct(message.getUserId(), message.getSeckillSkuId());
            context.setStatus(status);
            localTransactionService.markTxResult(message.getMessageId(), status);
            if (status == SeckillDispatchStatus.QUEUED) {
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            log.info("秒杀本地事务未通过，回滚半消息, userId={}, seckillSkuId={}, status={}",
                    message.getUserId(), message.getSeckillSkuId(), status);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception e) {
            // 本地事务结果未知：返回 UNKNOW，由 Broker 回查按 Redis 标记决定提交或回滚
            log.error("秒杀本地事务执行异常，等待 Broker 回查, userId={}, seckillSkuId={}",
                    message.getUserId(), message.getSeckillSkuId(), e);
            return LocalTransactionState.UNKNOW;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String messageId = msg.getKeys();
        SeckillDispatchStatus status = localTransactionService.getTxResult(messageId);
        if (status == SeckillDispatchStatus.QUEUED) {
            log.info("事务回查确认本地事务成功，提交消息, transactionId={}, messageId={}",
                    msg.getTransactionId(), messageId);
            return LocalTransactionState.COMMIT_MESSAGE;
        }
        log.warn("事务回查未找到预减成功标记，回滚消息, transactionId={}, messageId={}, status={}",
                msg.getTransactionId(), messageId, status);
        return LocalTransactionState.ROLLBACK_MESSAGE;
    }
}
