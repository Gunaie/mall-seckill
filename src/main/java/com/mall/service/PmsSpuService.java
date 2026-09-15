package com.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mall.common.page.PageResult;
import com.mall.dto.SpuPageQuery;
import com.mall.dto.SpuSaveDTO;
import com.mall.entity.PmsSpu;
import com.mall.vo.SpuDetailVO;
import com.mall.vo.SpuPageVO;

/**
 * SPU 商品业务接口
 *
 * @author mall
 */
public interface PmsSpuService extends IService<PmsSpu> {

    /**
     * 买家端商品分页（仅上架商品）
     */
    PageResult<SpuPageVO> pageOnSale(SpuPageQuery query);

    /**
     * 管理端商品分页（含下架商品）
     */
    PageResult<SpuPageVO> pageForAdmin(SpuPageQuery query);

    /**
     * 查询商品详情（含 SKU），带缓存（穿透/击穿/雪崩防护）
     */
    SpuDetailVO getDetail(Long id);

    /**
     * 新增商品（SPU + SKU 级联保存）
     *
     * @return SPU ID
     */
    Long createSpu(SpuSaveDTO dto);

    /**
     * 修改商品（SPU 更新 + SKU 全量替换）
     */
    void updateSpu(SpuSaveDTO dto);

    /**
     * 逻辑删除商品
     */
    void deleteSpu(Long id);
}
