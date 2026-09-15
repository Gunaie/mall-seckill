package com.mall.common.exception;

import com.mall.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 业务规则不满足时抛出，由 {@link GlobalExceptionHandler} 统一捕获并转换为标准响应
 *
 * @author mall
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
