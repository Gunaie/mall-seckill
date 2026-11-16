-- =============================================================
-- 订单域分片表结构（ShardingSphere-JDBC，ds1=mall_order_0 / ds2=mall_order_1）
-- 每个订单库包含 3 类订单表的 4 个物理分片：
--   oms_order_0..3 / oms_order_item_0..3 / sms_seckill_order_0..3
-- 分片键 user_id：库 = user_id % 2，表 = user_id % 4
--
-- 说明：
--   1. 本脚本由 mysql-order-0 / mysql-order-1 容器初始化时自动执行
--      （docker-entrypoint 会以 MYSQL_DATABASE 作为默认库）；
--      手工重建时执行：mysql -uroot -p*** -D mall_order_0 < order-shard-schema.sql
--   2. id 不做 AUTO_INCREMENT：分片后各物理表独立自增会产生重复主键，
--      统一由 MyBatis-Plus 雪花算法（IdType.ASSIGN_ID）在客户端生成。
-- =============================================================
SET NAMES utf8mb4;

-- ============================ 订单主表 ============================

CREATE TABLE IF NOT EXISTS oms_order_0 (
    id               BIGINT        NOT NULL COMMENT '订单ID（雪花算法）',
    order_sn         VARCHAR(32)   NOT NULL COMMENT '订单号（业务唯一，雪花算法生成）',
    user_id          BIGINT        NOT NULL COMMENT '下单用户ID（分片键）',
    total_amount     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额（服务端按实时价重算）',
    status           TINYINT       NOT NULL DEFAULT 1 COMMENT '订单状态：1待支付 2已支付 3已取消 4已发货 5已完成',
    order_type       TINYINT       NOT NULL DEFAULT 1 COMMENT '订单类型：1普通订单 2秒杀订单',
    receiver_name    VARCHAR(64)   NOT NULL COMMENT '收货人',
    receiver_phone   VARCHAR(32)   NOT NULL COMMENT '收货电话',
    receiver_address VARCHAR(256)  NOT NULL COMMENT '收货地址',
    remark           VARCHAR(256)  DEFAULT NULL COMMENT '订单备注',
    cancel_reason    VARCHAR(256)  DEFAULT NULL COMMENT '取消原因',
    expire_time      DATETIME      NOT NULL COMMENT '支付截止时间（与延迟消息TTL一致）',
    pay_time         DATETIME      DEFAULT NULL COMMENT '支付时间',
    ship_time        DATETIME      DEFAULT NULL COMMENT '发货时间',
    receive_time     DATETIME      DEFAULT NULL COMMENT '确认收货时间',
    cancel_time      DATETIME      DEFAULT NULL COMMENT '取消时间',
    deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sn (order_sn),
    KEY idx_user_status (user_id, status),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单主表分片';

CREATE TABLE IF NOT EXISTS oms_order_1 LIKE oms_order_0;
CREATE TABLE IF NOT EXISTS oms_order_2 LIKE oms_order_0;
CREATE TABLE IF NOT EXISTS oms_order_3 LIKE oms_order_0;

-- ============================ 订单明细表 ============================

CREATE TABLE IF NOT EXISTS oms_order_item_0 (
    id          BIGINT        NOT NULL COMMENT '明细ID（雪花算法）',
    order_id    BIGINT        NOT NULL COMMENT '订单ID',
    order_sn    VARCHAR(32)   NOT NULL COMMENT '订单号（冗余，方便排查）',
    user_id     BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    sku_id      BIGINT        NOT NULL COMMENT 'SKU ID',
    spu_id      BIGINT        NOT NULL COMMENT 'SPU ID',
    spu_name    VARCHAR(128)  NOT NULL COMMENT 'SPU 名称快照',
    sku_name    VARCHAR(128)  NOT NULL COMMENT 'SKU 名称快照',
    spec_text   VARCHAR(256)  NOT NULL DEFAULT '' COMMENT '规格快照',
    sku_image   VARCHAR(512)  DEFAULT NULL COMMENT 'SKU 图片快照',
    price       DECIMAL(10,2) NOT NULL COMMENT '下单单价快照',
    quantity    INT           NOT NULL COMMENT '购买数量',
    total_price DECIMAL(10,2) NOT NULL COMMENT '该明细小计',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细表分片';

CREATE TABLE IF NOT EXISTS oms_order_item_1 LIKE oms_order_item_0;
CREATE TABLE IF NOT EXISTS oms_order_item_2 LIKE oms_order_item_0;
CREATE TABLE IF NOT EXISTS oms_order_item_3 LIKE oms_order_item_0;

-- ============================ 秒杀订单关联表 ============================

CREATE TABLE IF NOT EXISTS sms_seckill_order_0 (
    id             BIGINT        NOT NULL COMMENT '秒杀订单ID（雪花算法）',
    seckill_sku_id BIGINT        NOT NULL COMMENT '秒杀商品ID',
    session_id     BIGINT        NOT NULL COMMENT '场次ID',
    user_id        BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    order_id       BIGINT        NOT NULL COMMENT '关联 oms_order.id',
    order_sn       VARCHAR(32)   NOT NULL COMMENT '订单号（冗余）',
    seckill_price  DECIMAL(10,2) NOT NULL COMMENT '成交秒杀价快照',
    quantity       INT           NOT NULL DEFAULT 1 COMMENT '数量（当前固定 1）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sku (user_id, seckill_sku_id),
    KEY idx_order_id (order_id),
    KEY idx_session_id (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀订单关联表分片';

CREATE TABLE IF NOT EXISTS sms_seckill_order_1 LIKE sms_seckill_order_0;
CREATE TABLE IF NOT EXISTS sms_seckill_order_2 LIKE sms_seckill_order_0;
CREATE TABLE IF NOT EXISTS sms_seckill_order_3 LIKE sms_seckill_order_0;
