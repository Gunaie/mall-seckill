package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UmsRoleMenu;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-菜单关联 Mapper
 *
 * @author mall
 */
@Mapper
public interface UmsRoleMenuMapper extends BaseMapper<UmsRoleMenu> {
}
