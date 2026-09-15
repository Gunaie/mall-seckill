-- =============================================================
-- mall-seckill MQ 消费幂等表：以 messageId 唯一约束保证「同一条消息只被消费一次」
--
-- 设计要点（面试话术）：
-- 1. message_id 唯一索引是幂等的最终兜底——INSERT IGNORE 原子写入，重复消息返回 0 行直接跳过
-- 2. 日志写入与业务处理处于同一数据库事务：
--    - 业务成功提交 -> 日志记录留存，后续重复投递被唯一索引拦截
--    - 业务失败回滚 -> 日志记录一并回滚，消息可被重新消费（允许重试）
-- 3. 解决的问题：消费者处理成功但 ACK 前宕机，MQ 重投同一消息导致重复落单/重复关单
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

DROP TABLE IF EXISTS mq_consume_log;
CREATE TABLE mq_consume_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    message_id  VARCHAR(64)  NOT NULL COMMENT '消息唯一ID（生产端生成 UUID）',
    queue_name  VARCHAR(128) NOT NULL COMMENT '消费队列名，便于排查',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 已消费',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_id (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='MQ 消费幂等日志表';
