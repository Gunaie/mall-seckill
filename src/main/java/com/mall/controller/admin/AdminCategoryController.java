package com.mall.controller.admin;

import com.mall.common.result.Result;
import com.mall.dto.CategorySaveDTO;
import com.mall.service.PmsCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端分类接口
 *
 * @author mall
 */
@Tag(name = "10-后台-分类管理")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final PmsCategoryService categoryService;

    @Operation(summary = "新增/修改分类（id 为空表示新增）")
    @PreAuthorize("hasAuthority('pms:category:manage')")
    @PostMapping
    public Result<Long> save(@Valid @RequestBody CategorySaveDTO dto) {
        return Result.success(categoryService.saveOrUpdateCategory(dto));
    }

    @Operation(summary = "删除分类（存在子分类或商品时拒绝）")
    @PreAuthorize("hasAuthority('pms:category:manage')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return Result.success();
    }
}
