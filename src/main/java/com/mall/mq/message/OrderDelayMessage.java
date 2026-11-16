package com.mall.mq.message;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * 订单超时关单延迟消息
 *
 * @author mall
 */
@Data
@NoArgsConstructor
public class OrderDelayMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一ID（生产端构造时自动生成 UUID），用于 mq_consume_log 消费幂等去重。
     */
    private String messageId = UUID.randomUUID().toString();

    /**
     * 订单ID
     */
    private Long orderId;

    /**
     * 下单用户ID（订单表分片键，关单时据此路由到唯一物理分片）
     */
    private Long userId;

    /**
     * 订单号（便于日志排查，不参与业务判断）
     */
    private String orderSn;

    /**
     * 业务构造器（messageId 由字段默认值生成）
     */
    public OrderDelayMessage(Long orderId, String orderSn, Long userId) {
        this.orderId = orderId;
        this.orderSn = orderSn;
        this.userId = userId;
    }
}
