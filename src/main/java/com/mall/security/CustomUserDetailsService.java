package com.mall.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.result.ResultCode;
import com.mall.common.exception.BusinessException;
import com.mall.entity.UmsUser;
import com.mall.mapper.UmsMenuMapper;
import com.mall.mapper.UmsRoleMapper;
import com.mall.mapper.UmsUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 用户认证信息加载：用户 -> 角色 -> 权限
 *
 * @author mall
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UmsUserMapper userMapper;
    private final UmsRoleMapper roleMapper;
    private final UmsMenuMapper menuMapper;

    @Override
    public UserDetails loadUserByUsername(String username) {
        UmsUser user = userMapper.selectOne(Wrappers.<UmsUser>lambdaQuery()
                .eq(UmsUser::getUsername, username));
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return buildUserDetails(user);
    }

    /**
     * JWT 过滤器按 userId 加载（无需再查用户名）
     */
    public CustomUserDetails loadUserById(Long userId) {
        UmsUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已被删除");
        }
        return buildUserDetails(user);
    }

    private CustomUserDetails buildUserDetails(UmsUser user) {
        List<String> roles = roleMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = menuMapper.selectPermissionsByUserId(user.getId());
        if (roles == null) {
            roles = Collections.emptyList();
        }
        if (permissions == null) {
            permissions = Collections.emptyList();
        }
        return new CustomUserDetails(user, roles, permissions);
    }
}
