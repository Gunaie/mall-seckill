package com.mall.controller.admin;

import com.mall.common.result.Result;
import com.mall.dto.SeckillActivitySaveDTO;
import com.mall.dto.SeckillSessionSaveDTO;
import com.mall.dto.SeckillSkuSaveDTO;
import com.mall.entity.SmsSeckillActivity;
import com.mall.service.SeckillService;
import com.mall.vo.SeckillSkuVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端秒杀管理接口：活动/场次维护、场次加品、预热上架
 *
 * @author mall
 */
@Tag(name = "13-后台-秒杀管理")
@RestController
@RequestMapping("/api/admin/seckill")
@RequiredArgsConstructor
public class AdminSeckillController {

    private final SeckillService seckillService;

    @Operation(summary = "创建秒杀活动")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @PostMapping("/activities")
    public Result<Void> createActivity(@Valid @RequestBody SeckillActivitySaveDTO dto) {
        seckillService.createActivity(dto);
        return Result.success();
    }

    @Operation(summary = "活动列表")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @GetMapping("/activities")
    public Result<List<SmsSeckillActivity>> activities() {
        return Result.success(seckillService.listActivities());
    }

    @Operation(summary = "创建秒杀场次")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @PostMapping("/sessions")
    public Result<Void> createSession(@Valid @RequestBody SeckillSessionSaveDTO dto) {
        seckillService.createSession(dto);
        return Result.success();
    }

    @Operation(summary = "场次添加秒杀商品（秒杀价 + 独立秒杀库存）")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @PostMapping("/skus")
    public Result<Void> createSeckillSku(@Valid @RequestBody SeckillSkuSaveDTO dto) {
        seckillService.createSeckillSku(dto);
        return Result.success();
    }

    @Operation(summary = "场次商品列表（管理端视图）")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @GetMapping("/skus")
    public Result<List<SeckillSkuVO>> skus(@RequestParam Long sessionId) {
        return Result.success(seckillService.listSessionSkus(sessionId));
    }

    @Operation(summary = "预热上架：商品信息与库存写入 Redis（秒杀前必做）")
    @PreAuthorize("hasAuthority('sms:seckill:manage')")
    @PostMapping("/skus/{id}/upload")
    public Result<Void> upload(@PathVariable Long id) {
        seckillService.uploadToRedis(id);
        return Result.success();
    }
}
