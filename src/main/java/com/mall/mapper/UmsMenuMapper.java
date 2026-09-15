package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UmsMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜单/权限 Mapper
 *
 * @author mall
 */
@Mapper
public interface UmsMenuMapper extends BaseMapper<UmsMenu> {

    /**
     * 查询用户拥有的权限标识（用户 -> 角色 -> 菜单，取按钮上的 permission）
     */
    @Select("""
            SELECT DISTINCT m.permission
            FROM ums_menu m
            INNER JOIN ums_role_menu rm ON rm.menu_id = m.id
            INNER JOIN ums_user_role ur ON ur.role_id = rm.role_id
            INNER JOIN ums_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND m.status = 1
              AND m.permission IS NOT NULL
              AND m.permission <> ''
            """)
    List<String> selectPermissionsByUserId(@Param("userId") Long userId);
}
