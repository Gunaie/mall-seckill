package com.mall.controller;

import com.mall.common.page.PageResult;
import com.mall.common.result.Result;
import com.mall.dto.SpuPageQuery;
import com.mall.service.PmsSpuService;
import com.mall.vo.SpuDetailVO;
import com.mall.vo.SpuPageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家端商品接口
 *
 * @author mall
 */
@Tag(name = "05-商品浏览")
@RestController
@RequestMapping("/api/spu")
@RequiredArgsConstructor
public class SpuController {

    private final PmsSpuService spuService;

    @Operation(summary = "上架商品分页（支持关键词/分类）")
    @GetMapping("/page")
    public Result<PageResult<SpuPageVO>> page(@ModelAttribute SpuPageQuery query) {
        return Result.success(spuService.pageOnSale(query));
    }

    @Operation(summary = "商品详情（Redis 缓存，含 SKU 规格与价格）")
    @GetMapping("/{id}")
    public Result<SpuDetailVO> detail(
            @Parameter(description = "SPU ID") @PathVariable Long id) {
        return Result.success(spuService.getDetail(id));
    }
}
