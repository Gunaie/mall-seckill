package com.mall.controller;

import com.mall.common.page.PageResult;
import com.mall.common.result.Result;
import com.mall.dto.UserPageQuery;
import com.mall.service.UmsUserService;
import com.mall.vo.UmsUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 用户管理接口（演示 RBAC 方法级鉴权）
 *
 * @author mall
 */
@Tag(name = "03-用户管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UmsUserController {

    private final UmsUserService userService;

    @Operation(summary = "用户分页列表（需要 ums:user:list 权限）")
    @PreAuthorize("hasAuthority('ums:user:list')")
    @GetMapping("/page")
    public Result<PageResult<UmsUserVO>> page(@ModelAttribute UserPageQuery query) {
        return Result.success(userService.pageUsers(query));
    }
}
