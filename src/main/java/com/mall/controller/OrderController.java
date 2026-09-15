package com.mall.controller;

import com.mall.common.page.PageResult;
import com.mall.common.result.Result;
import com.mall.dto.OrderCreateDTO;
import com.mall.dto.OrderPageQuery;
import com.mall.service.OrderService;
import com.mall.vo.OrderCreateVO;
import com.mall.vo.OrderDetailVO;
import com.mall.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端订单接口（登录用户，仅能操作自己的订单）
 *
 * @author mall
 */
@Tag(name = "05-我的订单")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "获取下单幂等令牌（进入结算页时调用）")
    @GetMapping("/token")
    public Result<String> token() {
        return Result.success(orderService.issueToken());
    }

    @Operation(summary = "创建订单（结算购物车勾选项）")
    @PostMapping
    public Result<OrderCreateVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(orderService.createOrder(dto));
    }

    @Operation(summary = "我的订单分页（可按状态过滤）")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(@ModelAttribute OrderPageQuery query) {
        return Result.success(orderService.myPage(query));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(orderService.detail(id));
    }

    @Operation(summary = "模拟支付")
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        orderService.pay(id);
        return Result.success();
    }

    @Operation(summary = "取消订单（仅待支付可取消，库存回补）")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id);
        return Result.success();
    }

    @Operation(summary = "确认收货")
    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        orderService.confirm(id);
        return Result.success();
    }
}
