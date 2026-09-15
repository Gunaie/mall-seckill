-- =============================================================
-- mall-seckill 用户权限模块（RBAC）表结构
-- =============================================================
-- 容器 entrypoint 初始化客户端默认字符集可能为 latin1，
-- 必须显式声明连接字符集，否则脚本中的中文会双重编码乱码
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 1. 用户表
-- ----------------------------
DROP TABLE IF EXISTS ums_user;
CREATE TABLE ums_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    password    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    nickname    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    email       VARCHAR(100)          DEFAULT NULL COMMENT '邮箱',
    avatar      VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1 启用 0 禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：1 已删除 0 未删除',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ----------------------------
-- 2. 角色表
-- ----------------------------
DROP TABLE IF EXISTS ums_role;
CREATE TABLE ums_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    role_name   VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code   VARCHAR(50) NOT NULL COMMENT '角色编码，如 ADMIN/USER',
    description VARCHAR(200)         DEFAULT NULL COMMENT '角色描述',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

-- ----------------------------
-- 3. 菜单/权限表（权限标识挂在按钮类型节点上）
-- ----------------------------
DROP TABLE IF EXISTS ums_menu;
CREATE TABLE ums_menu (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID，0 为根节点',
    menu_name   VARCHAR(50)  NOT NULL COMMENT '菜单名称',
    menu_type   TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：0 目录 1 菜单 2 按钮',
    permission  VARCHAR(100)          DEFAULT NULL COMMENT '权限标识，如 ums:user:list',
    path        VARCHAR(200)          DEFAULT NULL COMMENT '前端路由地址',
    icon        VARCHAR(100)          DEFAULT NULL COMMENT '图标',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='菜单/权限表';

-- ----------------------------
-- 4. 用户-角色关联表
-- ----------------------------
DROP TABLE IF EXISTS ums_user_role;
CREATE TABLE ums_user_role (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户角色关联表';

-- ----------------------------
-- 5. 角色-菜单(权限)关联表
-- ----------------------------
DROP TABLE IF EXISTS ums_role_menu;
CREATE TABLE ums_role_menu (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_menu_id (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色菜单关联表';
