-- =============================================================
-- mall-seckill 秒杀模块（SMS）表结构：活动 + 场次 + 秒杀SKU关联 + 秒杀订单
-- 秒杀库存与普通库存隔离（seckill_stock 独立于 pms_sku.stock），
-- 库存扣减走 DB 条件更新（seckill_count < seckill_stock）防超卖
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 1. 秒杀活动
-- ----------------------------
DROP TABLE IF EXISTS sms_seckill_activity;
CREATE TABLE sms_seckill_activity (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    name        VARCHAR(128) NOT NULL COMMENT '活动名称，如 618 年中大促',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 上线 0 下线',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀活动表';

-- ----------------------------
-- 2. 秒杀场次（隶属活动，限定开抢时间窗口）
-- ----------------------------
DROP TABLE IF EXISTS sms_seckill_session;
CREATE TABLE sms_seckill_session (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '场次ID',
    activity_id BIGINT      NOT NULL COMMENT '所属活动ID',
    name        VARCHAR(128) NOT NULL COMMENT '场次名称，如 6月18日 20:00 场',
    start_time  DATETIME    NOT NULL COMMENT '开抢时间',
    end_time    DATETIME    NOT NULL COMMENT '结束时间',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_activity_id (activity_id),
    KEY idx_start_time (start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀场次表';

-- ----------------------------
-- 3. 场次-秒杀SKU 关联（秒杀价 + 秒杀库存隔离）
-- seckill_count 为已秒数量，扣减 CAS：seckill_count + n <= seckill_stock
-- ----------------------------
DROP TABLE IF EXISTS sms_seckill_sku;
CREATE TABLE sms_seckill_sku (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '秒杀商品ID（对外秒杀下单入参）',
    activity_id   BIGINT        NOT NULL COMMENT '冗余活动ID，便于统计',
    session_id    BIGINT        NOT NULL COMMENT '所属场次ID',
    sku_id        BIGINT        NOT NULL COMMENT '关联 PMS SKU ID',
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价（原价取 pms_sku.price）',
    seckill_stock INT           NOT NULL COMMENT '秒杀库存（与普通库存隔离）',
    seckill_count INT           NOT NULL DEFAULT 0 COMMENT '已秒杀数量',
    max_per_user  INT           NOT NULL DEFAULT 1 COMMENT '每人限购数量（当前版本固定 1，见 uk_user_sku）',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_sku (session_id, sku_id),
    KEY idx_sku_id (sku_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀商品关联表';

-- ----------------------------
-- 4. 秒杀订单关联（一人一单 DB 级兜底：唯一索引）
-- 秒杀成功后生成普通 oms_order（order_type=2），此表记录秒杀上下文
-- ----------------------------
DROP TABLE IF EXISTS sms_seckill_order;
CREATE TABLE sms_seckill_order (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '秒杀订单ID',
    seckill_sku_id BIGINT        NOT NULL COMMENT '秒杀商品ID',
    session_id     BIGINT        NOT NULL COMMENT '场次ID',
    user_id        BIGINT        NOT NULL COMMENT '用户ID',
    order_id       BIGINT        NOT NULL COMMENT '关联 oms_order.id',
    order_sn       VARCHAR(32)   NOT NULL COMMENT '订单号（冗余）',
    seckill_price  DECIMAL(10,2) NOT NULL COMMENT '成交秒杀价快照',
    quantity       INT           NOT NULL DEFAULT 1 COMMENT '数量（当前固定 1）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sku (user_id, seckill_sku_id),
    KEY idx_order_id (order_id),
    KEY idx_session_id (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀订单关联表';
