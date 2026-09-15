-- =============================================================
-- mall-seckill 订单模块（OMS）表结构：订单主表 + 订单明细
-- 状态机：1 待支付 -> 2 已支付 -> 4 已发货 -> 5 已完成
--                  \-> 3 已取消（用户主动取消 / 超时未支付系统自动关单）
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 1. 订单主表
-- ----------------------------
DROP TABLE IF EXISTS oms_order;
CREATE TABLE oms_order (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_sn         VARCHAR(32)  NOT NULL COMMENT '订单号（业务唯一，雪花算法生成）',
    user_id          BIGINT       NOT NULL COMMENT '下单用户ID',
    total_amount     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额（服务端按实时价重算）',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '订单状态：1待支付 2已支付 3已取消 4已发货 5已完成',
    order_type       TINYINT      NOT NULL DEFAULT 1 COMMENT '订单类型：1普通订单 2秒杀订单（取消/关单时回补秒杀库存）',
    -- 收货信息快照（本项目不做地址簿，下单时直接填写）
    receiver_name    VARCHAR(64)  NOT NULL COMMENT '收货人',
    receiver_phone   VARCHAR(32)  NOT NULL COMMENT '收货电话',
    receiver_address VARCHAR(256) NOT NULL COMMENT '收货地址',
    remark           VARCHAR(256) DEFAULT NULL COMMENT '订单备注',
    cancel_reason    VARCHAR(256) DEFAULT NULL COMMENT '取消原因',
    expire_time      DATETIME     NOT NULL COMMENT '支付截止时间（与延迟消息TTL一致）',
    pay_time         DATETIME     DEFAULT NULL COMMENT '支付时间',
    ship_time        DATETIME     DEFAULT NULL COMMENT '发货时间',
    receive_time     DATETIME     DEFAULT NULL COMMENT '确认收货时间',
    cancel_time      DATETIME     DEFAULT NULL COMMENT '取消时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sn (order_sn),
    KEY idx_user_status (user_id, status),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单主表';

-- ----------------------------
-- 2. 订单明细表（商品信息为下单瞬间快照，不受后续商品改价/改名影响）
-- ----------------------------
DROP TABLE IF EXISTS oms_order_item;
CREATE TABLE oms_order_item (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    order_id    BIGINT       NOT NULL COMMENT '订单ID',
    order_sn    VARCHAR(32)  NOT NULL COMMENT '订单号（冗余，方便排查）',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    sku_id      BIGINT       NOT NULL COMMENT 'SKU ID',
    spu_id      BIGINT       NOT NULL COMMENT 'SPU ID',
    spu_name    VARCHAR(128) NOT NULL COMMENT 'SPU 名称快照',
    sku_name    VARCHAR(128) NOT NULL COMMENT 'SKU 名称快照',
    spec_text   VARCHAR(256) NOT NULL DEFAULT '' COMMENT '规格快照，如 颜色:曜石黑;内存:256G',
    sku_image   VARCHAR(512) DEFAULT NULL COMMENT 'SKU 图片快照',
    price       DECIMAL(10,2) NOT NULL COMMENT '下单单价快照',
    quantity    INT          NOT NULL COMMENT '购买数量',
    total_price DECIMAL(10,2) NOT NULL COMMENT '该明细小计',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细表';
