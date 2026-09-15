package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 登录/注册成功返回的令牌信息
 *
 * @author mall
 */
@Data
@Builder
@Schema(description = "登录令牌信息")
public class LoginVO {

    @Schema(description = "访问令牌（放在 Authorization 请求头）")
    private String accessToken;

    @Schema(description = "刷新令牌（用于换新 accessToken）")
    private String refreshToken;

    @Schema(description = "令牌类型")
    private String tokenType;

    @Schema(description = "accessToken 有效期（秒）")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserInfoVO userInfo;
}
