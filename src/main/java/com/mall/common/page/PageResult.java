package com.mall.common.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 分页结果封装
 *
 * @author mall
 */
@Data
@Schema(description = "分页结果")
public class PageResult<T> {

    @Schema(description = "当前页")
    private Long pageNum;

    @Schema(description = "每页条数")
    private Long pageSize;

    @Schema(description = "总条数")
    private Long total;

    @Schema(description = "总页数")
    private Long pages;

    @Schema(description = "数据列表")
    private List<T> list;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setTotal(page.getTotal());
        result.setPages(page.getPages());
        result.setList(page.getRecords());
        return result;
    }

    /**
     * Entity 分页转 VO 分页
     */
    public static <E, V> PageResult<V> of(IPage<E> page, List<V> voList) {
        PageResult<V> result = new PageResult<>();
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setTotal(page.getTotal());
        result.setPages(page.getPages());
        result.setList(voList);
        return result;
    }
}
