package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.SmsSeckillSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀商品 Mapper
 *
 * @author mall
 */
@Mapper
public interface SmsSeckillSkuMapper extends BaseMapper<SmsSeckillSku> {

    /**
     * 条件扣减秒杀库存（DB 层防超卖）：seckill_count + quantity <= seckill_stock 才成功，
     * 影响行数 0 即秒杀库存不足
     */
    @Update("UPDATE sms_seckill_sku SET seckill_count = seckill_count + #{quantity}, update_time = NOW() " +
            "WHERE id = #{id} AND seckill_count + #{quantity} <= seckill_stock")
    int deductSeckillStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 回补秒杀库存（秒杀订单取消 / 超时关单）
     */
    @Update("UPDATE sms_seckill_sku SET seckill_count = GREATEST(seckill_count - #{quantity}, 0), update_time = NOW() " +
            "WHERE id = #{id}")
    int restoreSeckillStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
