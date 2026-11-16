package com.mall.common.sentinel;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mall.common.result.Result;
import com.mall.common.result.ResultCode;
import com.mall.dto.SeckillExecuteDTO;
import com.mall.vo.SeckillResultVO;

/**
 * 秒杀接口 Sentinel 限流/熔断兜底。
 * <p>
 * 只处理 BlockException（流控、热点参数、熔断），业务异常（售罄/重复）不走这里，
 * 避免把正常业务拒绝误计入降级指标。
 *
 * @author mall
 */
public final class SeckillBlockHandler {

    private SeckillBlockHandler() {
    }

    public static Result<SeckillResultVO> executeBlocked(Long seckillSkuId, String path,
                                                         SeckillExecuteDTO dto, BlockException ex) {
        return Result.fail(ResultCode.TOO_MANY_REQUESTS, "活动太火爆，请稍后重试");
    }
}
