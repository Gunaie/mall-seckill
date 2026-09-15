package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 验证码视图：令牌（一次性，提交校验时回传）+ 图片 base64
 *
 * @author mall
 */
@Data
@Schema(description = "秒杀验证码")
public class CaptchaVO {

    @Schema(description = "验证码令牌，校验时原样回传，有效期内仅可使用一次")
    private String captchaToken;

    @Schema(description = "算术题验证码图片（data:image/png;base64 前缀，可直接放入 img src）")
    private String img;
}
