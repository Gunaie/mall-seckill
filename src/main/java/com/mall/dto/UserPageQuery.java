package com.mall.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户分页查询条件
 *
 * @author mall
 */
@Data
@Schema(description = "用户分页查询条件")
public class UserPageQuery {

    @Schema(description = "页码", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "搜索关键词（用户名/昵称/手机号）")
    private String keyword;
}
