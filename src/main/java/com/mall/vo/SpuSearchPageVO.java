package com.mall.vo;

import com.mall.common.page.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 商品搜索分页结果（带降级标记）
 *
 * @author mall
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商品搜索分页结果")
public class SpuSearchPageVO extends PageResult<SpuSearchVO> {

    @Schema(description = "是否降级走数据库（ES 不可用时为 true）")
    private boolean degraded;

    @Schema(description = "搜索耗时（毫秒）")
    private long tookMs;

    @Schema(description = "实际使用的分词器（降级时为 standard）")
    private String analyzer;

    public static SpuSearchPageVO of(PageResult<SpuSearchVO> page, boolean degraded,
                                     long tookMs, String analyzer) {
        SpuSearchPageVO result = new SpuSearchPageVO();
        result.setPageNum(page.getPageNum());
        result.setPageSize(page.getPageSize());
        result.setTotal(page.getTotal());
        result.setPages(page.getPages());
        List<SpuSearchVO> list = page.getList();
        result.setList(list);
        result.setDegraded(degraded);
        result.setTookMs(tookMs);
        result.setAnalyzer(analyzer);
        return result;
    }
}
