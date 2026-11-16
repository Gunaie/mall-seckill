package com.mall.mq.rocketmq;

import com.mall.mq.SeckillDispatchStatus;
import com.mall.mq.message.SeckillOrderMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 事务消息发送上下文：作为 sendMessageInTransaction 的 arg 传入，
 * 由 {@link SeckillOrderTransactionListener} 执行本地事务后回填结果，
 * 从而把「本地事务是否预减成功」同步带回调用线程（无需 ThreadLocal，避免多实例共享生产者时串号）。
 *
 * @author mall
 */
@Getter
@RequiredArgsConstructor
public class SeckillTxContext {

    private final SeckillOrderMessage message;

    /**
     * 本地事务执行结果；为 null 表示本地事务尚未执行（半消息发送失败/超时）
     */
    @Setter
    private volatile SeckillDispatchStatus status;
}
