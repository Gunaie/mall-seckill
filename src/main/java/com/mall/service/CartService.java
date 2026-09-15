package com.mall.service;

import com.mall.dto.CartAddDTO;
import com.mall.dto.CartQuantityDTO;
import com.mall.dto.CartSelectDTO;
import com.mall.model.CartItem;
import com.mall.vo.CartVO;

import java.util.List;

/**
 * 购物车服务（Redis Hash 存储）
 *
 * @author mall
 */
public interface CartService {

    /**
     * 加入购物车（同 SKU 数量累加）
     */
    void addToCart(CartAddDTO dto);

    /**
     * 修改购物车项数量
     */
    void updateQuantity(CartQuantityDTO dto);

    /**
     * 勾选/取消勾选单个 SKU
     */
    void selectItem(CartSelectDTO dto);

    /**
     * 全选/取消全选
     */
    void selectAll(Boolean selected);

    /**
     * 删除购物车项
     */
    void removeItem(Long skuId);

    /**
     * 查询购物车（价格、库存等实时拼装）
     */
    CartVO listCart();

    /**
     * 获取指定用户已勾选的购物车项（下单结算用）
     */
    List<CartItem> getSelectedItems(Long userId);

    /**
     * 清空指定用户已勾选的购物车项（下单成功后调用）
     */
    void removeSelected(Long userId);
}
