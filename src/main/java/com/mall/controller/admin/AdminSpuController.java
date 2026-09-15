package com.mall.controller.admin;

import com.mall.common.page.PageResult;
import com.mall.common.result.Result;
import com.mall.dto.SpuPageQuery;
import com.mall.dto.SpuSaveDTO;
import com.mall.service.PmsSpuService;
import com.mall.vo.SpuPageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端商品接口
 *
 * @author mall
 */
@Tag(name = "11-后台-商品管理")
@RestController
@RequestMapping("/api/admin/spu")
@RequiredArgsConstructor
public class AdminSpuController {

    private final PmsSpuService spuService;

    @Operation(summary = "商品分页（含下架商品）")
    @PreAuthorize("hasAuthority('pms:spu:list')")
    @GetMapping("/page")
    public Result<PageResult<SpuPageVO>> page(@ModelAttribute SpuPageQuery query) {
        return Result.success(spuService.pageForAdmin(query));
    }

    @Operation(summary = "新增商品（SPU + SKU 级联保存）")
    @PreAuthorize("hasAuthority('pms:spu:create')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody SpuSaveDTO dto) {
        return Result.success(spuService.createSpu(dto));
    }

    @Operation(summary = "修改商品（SKU 全量替换，自动失效缓存）")
    @PreAuthorize("hasAuthority('pms:spu:update')")
    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody SpuSaveDTO dto) {
        spuService.updateSpu(dto);
        return Result.success();
    }

    @Operation(summary = "逻辑删除商品")
    @PreAuthorize("hasAuthority('pms:spu:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        spuService.deleteSpu(id);
        return Result.success();
    }
}
