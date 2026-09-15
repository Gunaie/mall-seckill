package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求
 *
 * @author mall
 */
@Data
@Schema(description = "刷新令牌请求")
public class RefreshTokenDTO {

    @Schema(description = "登录时返回的 refreshToken")
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
