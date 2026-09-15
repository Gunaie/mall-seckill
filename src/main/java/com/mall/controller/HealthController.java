package com.mall.controller;

import com.mall.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口
 *
 * @author mall
 */
@Tag(name = "01-健康检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("application", "mall-seckill");
        data.put("version", "1.0.0");
        data.put("serverTime", LocalDateTime.now().toString());
        return Result.success(data);
    }
}
