package com.mall.controller;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.mall.common.result.Result;
import com.mall.config.SeckillProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 治理状态观测接口：用于验证 Nacos 动态配置与 Sentinel 规则是否生效。
 *
 * @author mall
 */
@Tag(name = "服务治理")
@RestController
@RequestMapping("/api/admin/governance")
@RequiredArgsConstructor
public class GovernanceController {

    private final SeckillProperties seckillProperties;

    @Operation(summary = "查看当前动态配置与 Sentinel 规则状态")
    @GetMapping("/state")
    public Result<Map<String, Object>> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("localMarkEnabled", seckillProperties.isLocalMarkEnabled());
        state.put("pathTtlSeconds", seckillProperties.getPathTtlSeconds());
        state.put("captchaEnabled", seckillProperties.isCaptchaEnabled());
        state.put("flowRules", FlowRuleManager.getRules().size());
        state.put("flowLimit", FlowRuleManager.getRules().stream()
                .findFirst().map(FlowRule::getCount).orElse(null));
        state.put("paramFlowRules", ParamFlowRuleManager.getRules().size());
        state.put("degradeRules", DegradeRuleManager.getRules().size());
        return Result.success(state);
    }
}
