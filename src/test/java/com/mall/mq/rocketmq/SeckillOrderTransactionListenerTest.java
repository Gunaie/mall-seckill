package com.mall.mq.rocketmq;

import com.mall.metrics.SeckillMetrics;
import com.mall.mq.SeckillDispatchStatus;
import com.mall.mq.message.SeckillOrderMessage;
import com.mall.service.SeckillLocalTransactionService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RocketMQ 事务监听器单元测试：本地事务提交/回滚/未知，以及 Broker 回查决策。
 *
 * @author mall
 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderTransactionListenerTest {

    private static final String MESSAGE_ID = "msg-1";

    @Mock
    private SeckillLocalTransactionService localTransactionService;

    @Mock
    private SeckillMetrics metrics;

    @InjectMocks
    private SeckillOrderTransactionListener listener;

    private Message buildMessage() {
        return new Message("seckill-order-topic", "seckill-order", MESSAGE_ID,
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    private SeckillTxContext context() {
        return new SeckillTxContext(new SeckillOrderMessage(1L, 2L, "n", "p", "a", null));
    }

    @Test
    @DisplayName("本地事务预减成功 -> 提交半消息，并写入事务结果标记")
    void executeLocalTransaction_commit() {
        when(localTransactionService.executePreDeduct(1L, 2L)).thenReturn(SeckillDispatchStatus.QUEUED);
        SeckillTxContext ctx = context();

        LocalTransactionState state = listener.executeLocalTransaction(buildMessage(), ctx);

        assertEquals(LocalTransactionState.COMMIT_MESSAGE, state);
        assertEquals(SeckillDispatchStatus.QUEUED, ctx.getStatus());
        // 标记 key 使用消息自带的业务 messageId（生产端以它为消息 Key，回查时按该 Key 读取）
        verify(localTransactionService).markTxResult(ctx.getMessage().getMessageId(), SeckillDispatchStatus.QUEUED);
    }

    @Test
    @DisplayName("库存不足 -> 回滚半消息")
    void executeLocalTransaction_soldOut() {
        when(localTransactionService.executePreDeduct(1L, 2L)).thenReturn(SeckillDispatchStatus.SOLD_OUT);
        SeckillTxContext ctx = context();

        LocalTransactionState state = listener.executeLocalTransaction(buildMessage(), ctx);

        assertEquals(LocalTransactionState.ROLLBACK_MESSAGE, state);
        assertEquals(SeckillDispatchStatus.SOLD_OUT, ctx.getStatus());
    }

    @Test
    @DisplayName("本地事务异常 -> 返回 UNKNOW，等待 Broker 回查")
    void executeLocalTransaction_unknownOnException() {
        when(localTransactionService.executePreDeduct(1L, 2L)).thenThrow(new IllegalStateException("redis down"));

        LocalTransactionState state = listener.executeLocalTransaction(buildMessage(), context());

        assertEquals(LocalTransactionState.UNKNOW, state);
        verify(localTransactionService, never()).markTxResult(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("回查命中预减成功标记 -> 提交消息")
    void checkLocalTransaction_commit() {
        MessageExt msg = new MessageExt();
        msg.setKeys(MESSAGE_ID);
        when(localTransactionService.getTxResult(MESSAGE_ID)).thenReturn(SeckillDispatchStatus.QUEUED);

        assertEquals(LocalTransactionState.COMMIT_MESSAGE, listener.checkLocalTransaction(msg));
    }

    @Test
    @DisplayName("回查无标记（本地事务未执行/已过期）-> 回滚，避免误提交")
    void checkLocalTransaction_rollbackWithoutMarker() {
        MessageExt msg = new MessageExt();
        msg.setKeys(MESSAGE_ID);
        when(localTransactionService.getTxResult(MESSAGE_ID)).thenReturn(null);

        assertEquals(LocalTransactionState.ROLLBACK_MESSAGE, listener.checkLocalTransaction(msg));
    }
}
