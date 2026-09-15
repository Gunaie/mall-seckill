package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 换取秒杀动态路径入参：提交一次性验证码，校验通过后服务端才下发 MD5 下单地址
 * <p>
 * 不加 Bean 非空校验：压测环境关闭验证码后允许传空对象，验证码开启时由服务层统一校验。
 *
 * @author mall
 */
@Data
public class SeckillPathDTO {

    @Schema(description = "验证码令牌（验证码接口下发）")
    private String captchaToken;

    @Schema(description = "用户填写的验证码答案")
    private String captchaCode;
}
