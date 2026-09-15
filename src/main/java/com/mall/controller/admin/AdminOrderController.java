package com.mall.controller.admin;

import com.mall.common.page.PageResult;
import com.mall.common.result.Result;
import com.mall.dto.OrderPageQuery;
import com.mall.service.OrderService;
import com.mall.vo.OrderDetailVO;
import com.mall.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单接口
 *
 * @author mall
 */
@Tag(name = "12-后台-订单管理")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    @Operation(summary = "订单分页（全部用户，可按状态/订单号过滤）")
    @PreAuthorize("hasAuthority('oms:order:list')")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(@ModelAttribute OrderPageQuery query) {
        return Result.success(orderService.adminPage(query));
    }

    @Operation(summary = "订单详情（无归属限制）")
    @PreAuthorize("hasAuthority('oms:order:list')")
    @GetMapping("/{id}")
    public Result<OrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(orderService.adminDetail(id));
    }

    @Operation(summary = "订单发货（已支付 -> 已发货）")
    @PreAuthorize("hasAuthority('oms:order:ship')")
    @PostMapping("/{id}/ship")
    public Result<Void> ship(@PathVariable Long id) {
        orderService.ship(id);
        return Result.success();
    }
}
