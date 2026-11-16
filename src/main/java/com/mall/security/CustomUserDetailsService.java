package com.mall.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 用户认证信息加载：用户 -> 角色 -> 权限
 * <p>
 * JWT 无状态鉴权每个请求都要还原用户身份，直接查库会产生 3 次 SQL（用户/角色/权限）。
 * 这里加一层短 TTL 本地缓存，命中后鉴权变成纯内存操作；
 * 代价是禁用用户、角色/权限变更最长延迟 {@link #USER_CACHE_TTL_SECONDS} 秒生效。
 *
 * @author mall
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    /**
     * 认证信息本地缓存 TTL：压测热路径优化，权限变更延迟秒级生效
     */
    private static final long USER_CACHE_TTL_SECONDS = 30;

    private final Cache<Long, CustomUserDetails> userCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(USER_CACHE_TTL_SECONDS))
            .build();

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
     * JWT 过滤器按 userId 加载（无需再查用户名），带本地缓存
     */
    public CustomUserDetails loadUserById(Long userId) {
        CustomUserDetails cached = userCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        UmsUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已被删除");
        }
        CustomUserDetails details = buildUserDetails(user);
        userCache.put(userId, details);
        return details;
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
