package com.mall.common.enums;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

/**
 * 订单状态机：
 * <pre>
 * 1 待支付 --支付--> 2 已支付 --发货--> 4 已发货 --确认收货--> 5 已完成
 *    |
 *    +--用户取消/超时关单--> 3 已取消（终态）
 * </pre>
 *
 * @author mall
 */
@Getter
public enum OrderStatus {

    PENDING_PAY(1, "待支付"),
    PAID(2, "已支付"),
    CANCELLED(3, "已取消"),
    SHIPPED(4, "已发货"),
    COMPLETED(5, "已完成");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 合法状态流转关系，非法流转一律拒绝（如已支付订单不能再次支付、已取消订单不能发货）
     */
    public boolean canTransitTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAY -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED;
            case SHIPPED -> target == COMPLETED;
            default -> false;
        };
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public static String descOf(Integer code) {
        OrderStatus status = of(code);
        return status == null ? "未知状态" : status.getDesc();
    }

    /**
     * 管理端可查询的状态集合（参数校验用）
     */
    public static final Set<Integer> VALID_CODES =
            EnumSet.allOf(OrderStatus.class).stream().map(OrderStatus::getCode).collect(java.util.stream.Collectors.toSet());
}
