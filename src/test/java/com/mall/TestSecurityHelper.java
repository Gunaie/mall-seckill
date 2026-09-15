package com.mall;

import com.mall.entity.UmsUser;
import com.mall.security.CustomUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 单元测试辅助：把指定用户塞入 SecurityContext，供 SecurityUtils.getCurrentUserId() 读取。
 * 纯 Mockito 单测不启动 Spring，需要手动构造 CustomUserDetails。
 */
public final class TestSecurityHelper {

    private TestSecurityHelper() {
    }

    public static void loginAs(Long userId, String username) {
        UmsUser user = new UmsUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("");
        user.setStatus(1);
        CustomUserDetails details = new CustomUserDetails(user, List.of("USER"), List.of());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    public static void loginAs(Long userId) {
        loginAs(userId, "test-user-" + userId);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
