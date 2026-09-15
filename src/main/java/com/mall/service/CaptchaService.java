package com.mall.service;

import com.mall.vo.CaptchaVO;

/**
 * 验证码服务：生成算术题图形验证码并完成一次性校验
 * <p>
 * 用于秒杀链路最前端拦截机器刷单，同时人为拉长/分散下单请求，起到削峰作用。
 *
 * @author mall
 */
public interface CaptchaService {

    /**
     * 生成一道算术题验证码（如 3 + 5 = ?），答案存 Redis 并设置短 TTL
     */
    CaptchaVO generate();

    /**
     * 校验验证码并立即作废（无论对错只能使用一次）；不通过时抛出业务异常
     *
     * @param captchaToken 生成接口下发的令牌
     * @param code         用户填写的答案
     */
    void verifyAndConsume(String captchaToken, String code);
}
