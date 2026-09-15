package com.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户管理列表展示对象（不含密码）
 *
 * @author mall
 */
@Data
@Schema(description = "用户列表项")
public class UmsUserVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "状态：1 启用 0 禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
