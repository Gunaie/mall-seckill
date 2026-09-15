package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MQ 消费幂等日志 Mapper。
 * <p>
 * 核心方法 {@link #tryConsume} 利用 INSERT IGNORE + message_id 唯一索引实现原子幂等：
 * <ul>
 *     <li>返回 1：首次消费，可执行业务逻辑</li>
 *     <li>返回 0：重复消息，应直接跳过并 ACK</li>
 * </ul>
 * 注意：该写入须与业务逻辑处于同一事务，业务失败回滚时日志记录一并回滚，允许消息重试。
 *
 * @author mall
 */
@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    /**
     * 尝试消费消息。INSERT IGNORE 在唯一索引冲突时静默返回 0 行。
     *
     * @param messageId 消息唯一ID
     * @param queueName 消费队列名
     * @return 1=首次消费 0=重复消息
     */
    @Insert("INSERT IGNORE INTO mq_consume_log (message_id, queue_name, status) " +
            "VALUES (#{messageId}, #{queueName}, 1)")
    int tryConsume(@Param("messageId") String messageId, @Param("queueName") String queueName);
}
