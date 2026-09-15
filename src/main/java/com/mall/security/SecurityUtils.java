package com.mall.security;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具：业务层获取当前登录用户，无需层层透传 userId
 *
 * @author mall
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户主体
     */
    public static CustomUserDetails getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userDetails;
    }

    public static Long getCurrentUserId() {
        return getCurrentUser().getId();
    }
}
