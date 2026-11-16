package com.mall.mq.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.config.RocketMqProperties;
import com.mall.mq.SeckillDispatchStatus;
import com.mall.mq.SeckillOrderDispatcher;
import com.mall.mq.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息版秒杀下单投递（mall.rocketmq.enabled=true）。
 * <p>
 * 以 messageId 作为消息 Key，消费端 mq_consume_log 幂等与 Broker 回查都依赖它。
 *
 * @author mall
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class RocketSeckillOrderSender implements SeckillOrderDispatcher {

    private final TransactionMQProducer seckillTransactionProducer;
    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public SeckillDispatchStatus dispatch(SeckillOrderMessage message) {
        SeckillTxContext context = new SeckillTxContext(message);
        try {
            Message rocketMessage = new Message(properties.getTopic(), properties.getTag(),
                    message.getMessageId(), objectMapper.writeValueAsBytes(message));
            TransactionSendResult result = seckillTransactionProducer.sendMessageInTransaction(rocketMessage, context);
            if (context.getStatus() == null) {
                // 半消息发送失败/超时，本地事务未执行，库存未预减
                log.warn("半消息发送后本地事务未执行, sendStatus={}, messageId={}, userId={}, seckillSkuId={}",
                        result.getSendStatus(), message.getMessageId(),
                        message.getUserId(), message.getSeckillSkuId());
                return SeckillDispatchStatus.UNKNOWN;
            }
            // 每单一条，压测下会挤占日志 IO，正常成功链路降到 debug
            log.debug("秒杀事务消息投递完成, localTxState={}, messageId={}, userId={}, seckillSkuId={}",
                    result.getLocalTransactionState(), message.getMessageId(),
                    message.getUserId(), message.getSeckillSkuId());
            return context.getStatus();
        } catch (Exception e) {
            // 本地事务已执行（库存已预减）时，仍把真实结果返回给调用方，
            // 半消息由 Broker 回查 Redis 标记决定 commit/rollback，最终与本地事务保持一致
            log.error("秒杀事务消息投递异常, messageId={}, userId={}, seckillSkuId={}, localTxStatus={}",
                    message.getMessageId(), message.getUserId(), message.getSeckillSkuId(), context.getStatus(), e);
            return context.getStatus() != null ? context.getStatus() : SeckillDispatchStatus.UNKNOWN;
        }
    }
}
