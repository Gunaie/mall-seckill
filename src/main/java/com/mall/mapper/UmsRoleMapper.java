package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UmsRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色 Mapper
 *
 * @author mall
 */
@Mapper
public interface UmsRoleMapper extends BaseMapper<UmsRole> {

    /**
     * 查询用户拥有的角色编码（仅启用状态的角色）
     */
    @Select("""
            SELECT r.role_code
            FROM ums_role r
            INNER JOIN ums_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
