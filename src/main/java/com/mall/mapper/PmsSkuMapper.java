package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.PmsSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SKU Mapper
 *
 * @author mall
 */
@Mapper
public interface PmsSkuMapper extends BaseMapper<PmsSku> {

    /**
     * 条件扣减库存：仅当库存充足且 SKU 在售时成功，影响行数 0 即库存不足（SQL 层防超卖）
     */
    @Update("UPDATE pms_sku SET stock = stock - #{quantity}, update_time = NOW() " +
            "WHERE id = #{skuId} AND status = 1 AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 回补库存（订单取消 / 超时关单）
     */
    @Update("UPDATE pms_sku SET stock = stock + #{quantity}, update_time = NOW() WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}
