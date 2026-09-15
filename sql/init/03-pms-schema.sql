-- =============================================================
-- mall-seckill 商品模块（PMS）表结构：三级分类 + SPU + SKU
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 1. 商品分类（三级树）
-- ----------------------------
DROP TABLE IF EXISTS pms_category;
CREATE TABLE pms_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0 为一级分类',
    name        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    level       TINYINT      NOT NULL DEFAULT 1 COMMENT '层级：1/2/3',
    icon        VARCHAR(255)          DEFAULT NULL COMMENT '分类图标',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商品分类表';

-- ----------------------------
-- 2. SPU（标准化产品单元，商品公共属性）
-- ----------------------------
DROP TABLE IF EXISTS pms_spu;
CREATE TABLE pms_spu (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'SPU ID',
    name        VARCHAR(200) NOT NULL COMMENT '商品名称',
    category_id BIGINT       NOT NULL COMMENT '三级分类ID',
    subtitle    VARCHAR(255)          DEFAULT NULL COMMENT '副标题/卖点',
    main_image  VARCHAR(500)          DEFAULT NULL COMMENT '主图地址',
    images      TEXT                  DEFAULT NULL COMMENT '轮播图地址 JSON 数组',
    detail      MEDIUMTEXT            DEFAULT NULL COMMENT '商品详情（富文本）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 上架 0 下架',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：1 已删除 0 未删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='SPU 商品表';

-- ----------------------------
-- 3. SKU（最小库存单元，规格 + 价格 + 库存）
-- ----------------------------
DROP TABLE IF EXISTS pms_sku;
CREATE TABLE pms_sku (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'SKU ID',
    spu_id      BIGINT        NOT NULL COMMENT '所属 SPU ID',
    sku_code    VARCHAR(64)   NOT NULL COMMENT 'SKU 编码（唯一）',
    name        VARCHAR(200)  NOT NULL COMMENT 'SKU 名称，如 星河X5 Pro 黑色 256G',
    spec_json   VARCHAR(500)           DEFAULT NULL COMMENT '规格键值对 JSON，如 {"颜色":"黑色","内存":"256G"}',
    price       DECIMAL(10,2) NOT NULL COMMENT '销售价（元）',
    stock       INT           NOT NULL DEFAULT 0 COMMENT '库存数量',
    image       VARCHAR(500)           DEFAULT NULL COMMENT 'SKU 图片',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    KEY idx_spu_id (spu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='SKU 规格库存表';
