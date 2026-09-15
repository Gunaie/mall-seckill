-- =============================================================
-- mall-seckill 用户权限模块种子数据
-- 默认账号：
--   管理员 admin / admin123 （拥有全部权限）
--   普通用户 demo  / user123  （无用户管理权限，用于演示 403）
-- 密码为 BCrypt(strength=10) 哈希
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 角色
-- ----------------------------
INSERT INTO ums_role (id, role_name, role_code, description, status) VALUES
    (1, '系统管理员', 'ADMIN', '拥有系统全部权限', 1),
    (2, '普通用户',   'USER',  '注册用户默认角色',   1);

-- ----------------------------
-- 菜单/权限（当前阶段为按钮级权限标识）
-- ----------------------------
INSERT INTO ums_menu (id, parent_id, menu_name, menu_type, permission, sort, status) VALUES
    (1,  0, '用户列表',   2, 'ums:user:list',      1, 1),
    (2,  0, '新建用户',   2, 'ums:user:create',    2, 1),
    (3,  0, '修改用户',   2, 'ums:user:update',    3, 1),
    (4,  0, '删除用户',   2, 'ums:user:delete',    4, 1),
    (5,  0, '角色列表',   2, 'ums:role:list',      5, 1),
    (6,  0, '分类管理',   2, 'pms:category:manage', 6, 1),
    (7,  0, '商品列表',   2, 'pms:spu:list',       7, 1),
    (8,  0, '新建商品',   2, 'pms:spu:create',     8, 1),
    (9,  0, '修改商品',   2, 'pms:spu:update',     9, 1),
    (10, 0, '删除商品',   2, 'pms:spu:delete',    10, 1);

-- ----------------------------
-- 角色-权限：ADMIN 拥有全部权限，USER 默认无管理权限
-- ----------------------------
INSERT INTO ums_role_menu (role_id, menu_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5),
    (1, 6), (1, 7), (1, 8), (1, 9), (1, 10);

-- ----------------------------
-- 用户
-- ----------------------------
INSERT INTO ums_user (id, username, password, nickname, status) VALUES
    (1, 'admin',
     '$2a$10$xdHLd23Y5WgfBhz/VYs6QeOydBh09cl4XngnWFLjdiEqSLP/iIqku',
     '系统管理员', 1),
    (2, 'demo',
     '$2a$10$rdjnIL7MxpVkYmIwt2KGIO4kI5Z3hSOPBAk0CfrVwXecESyAzLGiK',
     '演示用户', 1);

-- ----------------------------
-- 用户-角色
-- ----------------------------
INSERT INTO ums_user_role (user_id, role_id) VALUES
    (1, 1),
    (2, 2);
