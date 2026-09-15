-- =============================================================
-- mall-seckill 秒杀模块菜单/权限种子数据
-- =============================================================
SET NAMES utf8mb4;
USE mall_seckill;

-- ----------------------------
-- 菜单/权限（按钮级权限标识）
-- ----------------------------
INSERT INTO ums_menu (id, parent_id, menu_name, menu_type, permission, sort, status) VALUES
    (13, 0, '秒杀管理', 2, 'sms:seckill:manage', 13, 1);

-- ----------------------------
-- 角色-权限：ADMIN 拥有秒杀管理权限，USER 无
-- ----------------------------
INSERT INTO ums_role_menu (role_id, menu_id) VALUES
    (1, 13);
