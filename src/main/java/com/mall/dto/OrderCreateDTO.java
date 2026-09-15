package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建订单请求：
 * 商品与价格不接受前端入参，一律取自服务端购物车勾选项并实时重算，
 * 防止篡改价格、超量购买；orderToken 保证接口幂等。
 *
 * @author mall
 */
@Data
@Schema(description = "创建订单请求")
public class OrderCreateDTO {

    @Schema(description = "下单幂等令牌（先调用 GET /api/orders/token 获取，一次性）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "下单令牌不能为空")
    private String orderToken;

    @Schema(description = "收货人", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "收货人不能为空")
    @Size(max = 64, message = "收货人长度不能超过64")
    private String receiverName;

    @Schema(description = "收货电话", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "收货电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    @Schema(description = "收货地址", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "收货地址不能为空")
    @Size(max = 256, message = "收货地址长度不能超过256")
    private String receiverAddress;

    @Schema(description = "订单备注")
    @Size(max = 256, message = "备注长度不能超过256")
    private String remark;
}
