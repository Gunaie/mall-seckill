package com.mall.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一响应结构
 *
 * @author mall
 */
@Data
@Schema(description = "统一响应结构")
public class Result<T> {

    @Schema(description = "状态码，200 表示成功")
    private Integer code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "业务数据")
    private T data;

    @Schema(description = "响应时间戳")
    private Long timestamp = System.currentTimeMillis();

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(ResultCode.SUCCESS.getMessage());
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.getCode(), message);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
