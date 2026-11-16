package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.OmsOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订单 Mapper：状态流转全部使用带前置状态的条件更新（CAS），
 * 并发场景下只有一个请求能成功，天然保证幂等。
 * <p>
 * 订单表按 user_id 分库分表，所有状态更新都必须带 user_id，否则 ShardingSphere
 * 会把 UPDATE 广播到 8 个物理分片（管理端按订单 ID 发货时先查出 userId 再更新）。
 *
 * @author mall
 */
@Mapper
public interface OmsOrderMapper extends BaseMapper<OmsOrder> {

    /**
     * 待支付 -> 已支付
     */
    @Update("UPDATE oms_order SET status = 2, pay_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND deleted = 0")
    int payIfPending(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 待支付 -> 已取消（用户取消 / 超时关单共用）
     */
    @Update("UPDATE oms_order SET status = 3, cancel_reason = #{reason}, cancel_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND deleted = 0")
    int cancelIfPending(@Param("id") Long id, @Param("userId") Long userId, @Param("reason") String reason);

    /**
     * 已支付 -> 已发货
     */
    @Update("UPDATE oms_order SET status = 4, ship_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 2 AND deleted = 0")
    int shipIfPaid(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 已发货 -> 已完成
     */
    @Update("UPDATE oms_order SET status = 5, receive_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 4 AND deleted = 0")
    int completeIfShipped(@Param("id") Long id, @Param("userId") Long userId);
}
