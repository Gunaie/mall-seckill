package com.mall.controller;

import com.mall.common.annotation.AccessLimit;
import com.mall.common.result.Result;
import com.mall.common.sentinel.SeckillBlockHandler;
import com.mall.dto.SeckillExecuteDTO;
import com.mall.dto.SeckillPathDTO;
import com.mall.service.CaptchaService;
import com.mall.service.SeckillService;
import com.mall.vo.CaptchaVO;
import com.mall.vo.SeckillResultVO;
import com.mall.vo.SeckillSessionVO;
import com.mall.vo.SeckillSkuVO;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户端秒杀接口
 * <p>
 * 标准抢购三步：① 获取验证码 ② 验证码换取动态路径 ③ 携带动态路径下单，后轮询结果
 *
 * @author mall
 */
@Tag(name = "06-秒杀")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final CaptchaService captchaService;

    @Operation(summary = "当日秒杀场次列表（不传日期默认今天）")
    @GetMapping("/sessions")
    public Result<List<SeckillSessionVO>> sessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(seckillService.listSessions(date == null ? LocalDate.now() : date));
    }

    @Operation(summary = "场次秒杀商品列表（含剩余可抢数量）")
    @GetMapping("/skus")
    public Result<List<SeckillSkuVO>> skus(@RequestParam Long sessionId) {
        return Result.success(seckillService.listSessionSkus(sessionId));
    }

    @Operation(summary = "抢购第①步：获取算术验证码（图片 base64，答案一次性有效）")
    @AccessLimit(seconds = 60, maxCount = 10)
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.success(captchaService.generate());
    }

    @Operation(summary = "抢购第②步：校验验证码并换取动态下单路径（MD5 地址，60s 有效）")
    @AccessLimit(seconds = 5, maxCount = 5)
    @PostMapping("/{seckillSkuId}/path")
    public Result<String> path(@PathVariable Long seckillSkuId,
                               @Valid @RequestBody SeckillPathDTO dto) {
        return Result.success(seckillService.createSeckillPath(seckillSkuId, dto));
    }

    @Operation(summary = "抢购第③步：携带动态路径立即抢购（异步下单，返回排队中后请轮询结果）")
    @SentinelResource(value = "seckillExecute", blockHandler = "executeBlocked",
            blockHandlerClass = SeckillBlockHandler.class)
    @AccessLimit(seconds = 3, maxCount = 5)
    @PostMapping("/{seckillSkuId}/{path}/execute")
    public Result<SeckillResultVO> execute(@PathVariable Long seckillSkuId,
                                           @PathVariable String path,
                                           @Valid @RequestBody SeckillExecuteDTO dto) {
        return Result.success(seckillService.execute(seckillSkuId, path, dto));
    }

    @Operation(summary = "轮询秒杀结果（排队中/成功/失败），接口限速防止轮询打爆")
    @AccessLimit(seconds = 2, maxCount = 10)
    @GetMapping("/{seckillSkuId}/result")
    public Result<SeckillResultVO> result(@PathVariable Long seckillSkuId) {
        return Result.success(seckillService.result(seckillSkuId));
    }
}
