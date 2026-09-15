package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mall.common.exception.BusinessException;
import com.mall.common.page.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.dto.LoginDTO;
import com.mall.dto.RegisterDTO;
import com.mall.dto.UserPageQuery;
import com.mall.entity.UmsRole;
import com.mall.entity.UmsUser;
import com.mall.entity.UmsUserRole;
import com.mall.mapper.UmsRoleMapper;
import com.mall.mapper.UmsUserMapper;
import com.mall.mapper.UmsUserRoleMapper;
import com.mall.security.CustomUserDetails;
import com.mall.security.CustomUserDetailsService;
import com.mall.security.JwtProperties;
import com.mall.security.JwtTokenProvider;
import com.mall.security.TokenBlacklistService;
import com.mall.service.UmsUserService;
import com.mall.vo.LoginVO;
import com.mall.vo.UmsUserVO;
import com.mall.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户业务实现
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UmsUserServiceImpl extends ServiceImpl<UmsUserMapper, UmsUser> implements UmsUserService {

    private static final String DEFAULT_ROLE_CODE = "USER";

    private final UmsRoleMapper roleMapper;
    private final UmsUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final CustomUserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterDTO dto) {
        Long existCount = baseMapper.selectCount(Wrappers.<UmsUser>lambdaQuery()
                .eq(UmsUser::getUsername, dto.getUsername()));
        if (existCount > 0) {
            throw new BusinessException("用户名已存在");
        }
        UmsRole defaultRole = roleMapper.selectOne(Wrappers.<UmsRole>lambdaQuery()
                .eq(UmsRole::getRoleCode, DEFAULT_ROLE_CODE));
        if (defaultRole == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "默认角色不存在，请联系管理员");
        }

        UmsUser user = new UmsUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(1);
        baseMapper.insert(user);

        UmsUserRole userRole = new UmsUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(defaultRole.getId());
        userRoleMapper.insert(userRole);

        // 注册成功直接签发令牌，免去再登录一次
        return buildLoginVO(user.getId(), user.getUsername());
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword()));
            CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
            return buildLoginVO(userDetails.getId(), userDetails.getUsername());
        } catch (BadCredentialsException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        } catch (DisabledException e) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        } catch (AuthenticationException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录失败：" + e.getMessage());
        }
    }

    @Override
    public LoginVO refresh(String refreshToken) {
        if (!tokenProvider.validateRefreshToken(refreshToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "refreshToken 无效或已过期，请重新登录");
        }
        // 令牌轮换：将旧 refresh token 加入黑名单，防止被盗用的 refresh token 重复续签
        tokenBlacklistService.blacklist(refreshToken, tokenProvider.getRemainingSeconds(refreshToken));

        Long userId = tokenProvider.getUserId(refreshToken);
        // 重新加载用户，确保账号仍存在且启用
        CustomUserDetails userDetails = userDetailsService.loadUserById(userId);
        if (!userDetails.isEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        log.info("refresh token 轮换成功, userId={}", userId);
        return buildLoginVO(userId, userDetails.getUsername());
    }

    @Override
    public void logout(String accessToken) {
        // 将当前 access token 加入黑名单，使其立即失效（无需等待自然过期）
        tokenBlacklistService.blacklist(accessToken, tokenProvider.getRemainingSeconds(accessToken));
        log.info("用户登出，access token 已加入黑名单");
    }

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        CustomUserDetails userDetails = userDetailsService.loadUserById(userId);
        UserInfoVO vo = new UserInfoVO();
        vo.setId(userDetails.getId());
        vo.setUsername(userDetails.getUsername());
        vo.setRoles(userDetails.getRoles());
        vo.setPermissions(userDetails.getPermissions());
        UmsUser user = baseMapper.selectById(userId);
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        return vo;
    }

    @Override
    public PageResult<UmsUserVO> pageUsers(UserPageQuery query) {
        Page<UmsUser> page = new Page<>(query.getPageNum(), query.getPageSize());
        String keyword = query.getKeyword();
        baseMapper.selectPage(page, Wrappers.<UmsUser>lambdaQuery()
                // 用嵌套条件保证 (username LIKE ? OR nickname LIKE ? OR phone LIKE ?)，
                // 避免与逻辑删除条件 deleted=0 组合时因 AND/OR 优先级漏数据
                .and(StringUtils.hasText(keyword), w -> w
                        .like(UmsUser::getUsername, keyword)
                        .or().like(UmsUser::getNickname, keyword)
                        .or().like(UmsUser::getPhone, keyword))
                .orderByDesc(UmsUser::getId));

        List<UmsUserVO> voList = page.getRecords().stream().map(user -> {
            UmsUserVO vo = new UmsUserVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            vo.setPhone(user.getPhone());
            vo.setEmail(user.getEmail());
            vo.setAvatar(user.getAvatar());
            vo.setStatus(user.getStatus());
            vo.setCreateTime(user.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(page, voList);
    }

    private LoginVO buildLoginVO(Long userId, String username) {
        return LoginVO.builder()
                .accessToken(tokenProvider.generateAccessToken(userId, username))
                .refreshToken(tokenProvider.generateRefreshToken(userId))
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getAccessTokenExpiration())
                .userInfo(getUserInfo(userId))
                .build();
    }
}
