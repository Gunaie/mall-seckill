package com.mall.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 购物车项（Redis Hash 中每个 field 的 value）。
 * 只存储用户意图（SKU、数量、是否勾选），价格/名称/库存等实时信息不缓存，
 * 列表与结算时一律以数据库为准，避免商品改价/改名后购物车展示脏数据。
 *
 * @author mall
 */
@Data
public class CartItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SKU ID
     */
    private Long skuId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 是否勾选：1 勾选 0 未勾选
     */
    private Integer selected;

    /**
     * 加入购物车时间（毫秒）
     */
    private Long addTime;
}
