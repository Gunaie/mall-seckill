package com.mall.controller;

import com.mall.common.result.Result;
import com.mall.dto.CartAddDTO;
import com.mall.dto.CartQuantityDTO;
import com.mall.dto.CartSelectAllDTO;
import com.mall.dto.CartSelectDTO;
import com.mall.service.CartService;
import com.mall.vo.CartVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车接口（登录用户）
 *
 * @author mall
 */
@Tag(name = "04-购物车")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "查询购物车（价格/库存实时）")
    @GetMapping
    public Result<CartVO> list() {
        return Result.success(cartService.listCart());
    }

    @Operation(summary = "加入购物车（同 SKU 数量累加）")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody CartAddDTO dto) {
        cartService.addToCart(dto);
        return Result.success();
    }

    @Operation(summary = "修改购物车项数量")
    @PutMapping("/quantity")
    public Result<Void> updateQuantity(@Valid @RequestBody CartQuantityDTO dto) {
        cartService.updateQuantity(dto);
        return Result.success();
    }

    @Operation(summary = "勾选/取消勾选单个商品")
    @PutMapping("/selected")
    public Result<Void> selectItem(@Valid @RequestBody CartSelectDTO dto) {
        cartService.selectItem(dto);
        return Result.success();
    }

    @Operation(summary = "全选/取消全选")
    @PutMapping("/selected-all")
    public Result<Void> selectAll(@Valid @RequestBody CartSelectAllDTO dto) {
        cartService.selectAll(dto.getSelected());
        return Result.success();
    }

    @Operation(summary = "删除购物车项")
    @DeleteMapping("/{skuId}")
    public Result<Void> remove(@PathVariable Long skuId) {
        cartService.removeItem(skuId);
        return Result.success();
    }
}
