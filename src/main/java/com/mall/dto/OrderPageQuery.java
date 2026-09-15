package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 订单分页查询条件
 *
 * @author mall
 */
@Data
@Schema(description = "订单分页查询条件")
public class OrderPageQuery {

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "订单状态：1待支付 2已支付 3已取消 4已发货 5已完成")
    private Integer status;

    @Schema(description = "订单号（模糊匹配，管理端用）")
    private String orderSn;
}
