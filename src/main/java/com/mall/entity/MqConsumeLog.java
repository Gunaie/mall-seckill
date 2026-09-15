package com.mall.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 消费幂等日志实体。
 * <p>
 * 以 message_id 唯一索引实现「同一条消息只消费一次」，
 * 配合 {@link com.mall.mapper.MqConsumeLogMapper#tryConsume} 的 INSERT IGNORE 使用。
 *
 * @author mall
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一ID（生产端生成 UUID）
     */
    private String messageId;

    /**
     * 消费队列名
     */
    private String queueName;

    /**
     * 状态：1 已消费
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
