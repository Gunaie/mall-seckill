package com.mall.controller;

import com.mall.common.result.Result;
import com.mall.service.PmsCategoryService;
import com.mall.vo.CategoryTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家端分类接口
 *
 * @author mall
 */
@Tag(name = "04-商品分类")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final PmsCategoryService categoryService;

    @Operation(summary = "三级分类树（带缓存）")
    @GetMapping("/tree")
    public Result<List<CategoryTreeVO>> tree() {
        return Result.success(categoryService.tree());
    }
}
