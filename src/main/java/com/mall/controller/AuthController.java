package com.mall.controller;

import com.mall.common.result.Result;
import com.mall.dto.LoginDTO;
import com.mall.dto.RegisterDTO;
import com.mall.security.JwtProperties;
import com.mall.security.SecurityUtils;
import com.mall.service.UmsUserService;
import com.mall.vo.LoginVO;
import com.mall.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 刷新令牌 / 当前用户信息
 * <p>
 * Token 存储策略：
 * <ul>
 *     <li>access token：短期，通过响应体返回，前端存内存（Pinia state），请求时放 Authorization 头</li>
 *     <li>refresh token：长期，通过 httpOnly Cookie 下发，前端 JS 不可读（防 XSS 窃取），
 *         浏览器自动携带；SameSite=Lax 防 CSRF</li>
 * </ul>
 *
 * @author mall
 */
@Tag(name = "02-认证登录")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UmsUserService userService;
    private final JwtProperties jwtProperties;

    /** refresh token Cookie 名称 */
    private static final String REFRESH_COOKIE_NAME = "refresh_token";

    @Operation(summary = "用户注册（默认授予 USER 角色）")
    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.register(dto);
        setRefreshCookie(response, vo.getRefreshToken());
        // 响应体不返回 refresh token，仅通过 Cookie 下发
        vo.setRefreshToken(null);
        return Result.success(vo);
    }

    @Operation(summary = "用户名密码登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletResponse response) {
        LoginVO vo = userService.login(dto);
        setRefreshCookie(response, vo.getRefreshToken());
        vo.setRefreshToken(null);
        return Result.success(vo);
    }

    @Operation(summary = "refreshToken 刷新令牌（令牌轮换：旧 refreshToken 立即作废）")
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = resolveRefreshCookie(request);
        if (!StringUtils.hasText(refreshToken)) {
            return Result.fail(401, "未登录或登录已过期");
        }
        LoginVO vo = userService.refresh(refreshToken);
        setRefreshCookie(response, vo.getRefreshToken());
        vo.setRefreshToken(null);
        return Result.success(vo);
    }

    @Operation(summary = "用户登出（当前 accessToken 立即失效，清除 refresh Cookie）")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        userService.logout(resolveToken(request));
        clearRefreshCookie(response);
        return Result.success();
    }

    @Operation(summary = "获取当前登录用户信息（含角色与权限）")
    @GetMapping("/info")
    public Result<UserInfoVO> info() {
        return Result.success(userService.getUserInfo(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 将 refresh token 写入 httpOnly Cookie。
     * - httpOnly：JS 不可读，防 XSS 窃取
     * - SameSite=Lax：防 CSRF（跨站 POST 不带 Cookie）
     * - secure：生产环境 HTTPS 下设为 true
     * - maxAge：与 refresh token 有效期一致
     */
    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtProperties.getRefreshTokenExpiration())
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /**
     * 登出时清除 refresh Cookie（maxAge=0）
     */
    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private String resolveRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring("Bearer ".length());
        }
        return null;
    }
}
