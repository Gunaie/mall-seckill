package com.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mall.dto.CategorySaveDTO;
import com.mall.entity.PmsCategory;
import com.mall.vo.CategoryTreeVO;

import java.util.List;

/**
 * 商品分类业务接口
 *
 * @author mall
 */
public interface PmsCategoryService extends IService<PmsCategory> {

    /**
     * 查询启用状态的三级分类树（带 Redis 缓存）
     */
    List<CategoryTreeVO> tree();

    /**
     * 新增/修改分类（id 为空新增）
     *
     * @return 分类ID
     */
    Long saveOrUpdateCategory(CategorySaveDTO dto);

    /**
     * 删除分类（存在子分类或商品时拒绝）
     */
    void deleteCategory(Long id);
}
