package com.mall.search;

import com.mall.common.result.Result;
import com.mall.vo.SpuSearchPageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品搜索接口
 *
 * @author mall
 */
@Tag(name = "05-商品搜索")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService searchService;

    @Operation(summary = "商品搜索（关键词/分类/价格区间/排序/高亮）")
    @GetMapping("/search/spu")
    public Result<SpuSearchPageVO> search(@ModelAttribute SpuSearchQuery query) {
        return Result.success(searchService.search(query));
    }

    @Operation(summary = "全量重建商品索引（管理端）")
    @PreAuthorize("hasAuthority('pms:spu:update')")
    @PostMapping("/admin/search/reindex")
    public Result<Map<String, Object>> reindex() {
        long count = searchService.reindexAll();
        return Result.success(Map.of("indexed", count));
    }
}
