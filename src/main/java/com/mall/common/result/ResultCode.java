package com.mall.common.result;

import lombok.Getter;

/**
 * 统一响应状态码
 *
 * @author mall
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),

    BUSINESS_ERROR(10000, "业务处理失败"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后再试");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
