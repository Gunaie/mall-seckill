package com.mall.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体
 *
 * @author mall
 */
@Data
@TableName("oms_order")
public class OmsOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分片后各物理表独立自增会产生重复 ID，统一由 MyBatis-Plus 雪花算法在客户端生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 订单号
     */
    private String orderSn;

    private Long userId;

    private BigDecimal totalAmount;

    /**
     * 1 待支付 2 已支付 3 已取消 4 已发货 5 已完成
     */
    private Integer status;

    /**
     * 订单类型：1 普通订单 2 秒杀订单（取消/关单时回补秒杀库存）
     */
    private Integer orderType;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String remark;

    private String cancelReason;

    /**
     * 支付截止时间
     */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime receiveTime;

    private LocalDateTime cancelTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
