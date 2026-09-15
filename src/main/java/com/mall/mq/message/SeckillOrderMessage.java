package com.mall.mq.message;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * 秒杀下单消息：Redis 预减库存成功后投递，消费端异步落单
 *
 * @author mall
 */
@Data
@NoArgsConstructor
public class SeckillOrderMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一ID（生产端构造时自动生成 UUID），用于 mq_consume_log 消费幂等去重。
     * Jackson 反序列化时若 JSON 中携带 messageId 则以 JSON 为准，保证跨进程一致。
     */
    private String messageId = UUID.randomUUID().toString();

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 秒杀商品ID（sms_seckill_sku.id）
     */
    private Long seckillSkuId;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String remark;

    /**
     * 业务构造器（保持原有调用签名不变），messageId 由字段默认值生成。
     */
    public SeckillOrderMessage(Long userId, Long seckillSkuId, String receiverName,
                               String receiverPhone, String receiverAddress, String remark) {
        this.userId = userId;
        this.seckillSkuId = seckillSkuId;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverAddress = receiverAddress;
        this.remark = remark;
    }
}
