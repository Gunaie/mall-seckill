package com.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mall.common.page.PageResult;
import com.mall.dto.LoginDTO;
import com.mall.dto.RegisterDTO;
import com.mall.dto.UserPageQuery;
import com.mall.entity.UmsUser;
import com.mall.vo.LoginVO;
import com.mall.vo.UmsUserVO;
import com.mall.vo.UserInfoVO;

/**
 * 用户业务接口
 *
 * @author mall
 */
public interface UmsUserService extends IService<UmsUser> {

    /**
     * 用户注册，默认授予 USER 角色，注册成功直接签发令牌
     */
    LoginVO register(RegisterDTO registerDTO);

    /**
     * 用户名密码登录
     */
    LoginVO login(LoginDTO loginDTO);

    /**
     * 用 refreshToken 换取新的双令牌（令牌轮转：旧 refresh token 立即作废）
     */
    LoginVO refresh(String refreshToken);

    /**
     * 用户登出：将当前 access token 加入黑名单，使其立即失效
     *
     * @param accessToken 请求头中的 Bearer token
     */
    void logout(String accessToken);

    /**
     * 查询用户信息（含角色、权限）
     */
    UserInfoVO getUserInfo(Long userId);

    /**
     * 管理端用户分页
     */
    PageResult<UmsUserVO> pageUsers(UserPageQuery query);
}
