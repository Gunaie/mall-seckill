package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UmsUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联 Mapper
 *
 * @author mall
 */
@Mapper
public interface UmsUserRoleMapper extends BaseMapper<UmsUserRole> {
}
