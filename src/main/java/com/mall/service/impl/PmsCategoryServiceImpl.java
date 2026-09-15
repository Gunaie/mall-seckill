package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.config.CacheProperties;
import com.mall.dto.CategorySaveDTO;
import com.mall.entity.PmsCategory;
import com.mall.entity.PmsSpu;
import com.mall.mapper.PmsCategoryMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.service.PmsCategoryService;
import com.mall.vo.CategoryTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 商品分类业务实现
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmsCategoryServiceImpl extends ServiceImpl<PmsCategoryMapper, PmsCategory>
        implements PmsCategoryService {

    private static final int MAX_LEVEL = 3;

    private final PmsSpuMapper spuMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheProperties cacheProperties;

    @Override
    @SuppressWarnings("unchecked")
    public List<CategoryTreeVO> tree() {
        Object cached = redisTemplate.opsForValue().get(CacheConstants.CATEGORY_TREE_KEY);
        if (cached instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof CategoryTreeVO) {
            log.debug("分类树命中缓存");
            return (List<CategoryTreeVO>) cached;
        }

        // 未命中：查全量启用分类，内存组装树（分类数据量小，一次查询即可）
        List<PmsCategory> categories = baseMapper.selectList(Wrappers.<PmsCategory>lambdaQuery()
                .eq(PmsCategory::getStatus, 1)
                .orderByAsc(PmsCategory::getSort)
                .orderByAsc(PmsCategory::getId));
        List<CategoryTreeVO> tree = buildTree(categories);
        redisTemplate.opsForValue().set(CacheConstants.CATEGORY_TREE_KEY, tree,
                cacheProperties.getCategoryTreeTtl(), TimeUnit.SECONDS);
        log.debug("分类树未命中缓存，已重建并写入 Redis");
        return tree;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveOrUpdateCategory(CategorySaveDTO dto) {
        PmsCategory category = new PmsCategory();
        BeanUtils.copyProperties(dto, category);

        if (dto.getParentId() == 0L) {
            category.setLevel(1);
        } else {
            PmsCategory parent = baseMapper.selectById(dto.getParentId());
            if (parent == null || parent.getStatus() == null) {
                throw new BusinessException("父分类不存在");
            }
            if (parent.getLevel() >= MAX_LEVEL) {
                throw new BusinessException("分类最多支持 " + MAX_LEVEL + " 级");
            }
            category.setLevel(parent.getLevel() + 1);
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        }

        if (dto.getId() == null) {
            baseMapper.insert(category);
        } else {
            PmsCategory exist = baseMapper.selectById(dto.getId());
            if (exist == null) {
                throw new BusinessException("待修改的分类不存在");
            }
            // 简化处理：不允许把分类挂到自己的子孙节点下
            if (dto.getParentId().equals(dto.getId())) {
                throw new BusinessException("不能将分类挂载到自身下");
            }
            category.setId(dto.getId());
            baseMapper.updateById(category);
        }
        evictTreeCache();
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        PmsCategory category = baseMapper.selectById(id);
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        Long childCount = baseMapper.selectCount(Wrappers.<PmsCategory>lambdaQuery()
                .eq(PmsCategory::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException("存在子分类，请先删除子分类");
        }
        Long spuCount = spuMapper.selectCount(Wrappers.<PmsSpu>lambdaQuery()
                .eq(PmsSpu::getCategoryId, id));
        if (spuCount > 0) {
            throw new BusinessException("该分类下存在商品，无法删除");
        }
        baseMapper.deleteById(id);
        evictTreeCache();
    }

    private List<CategoryTreeVO> buildTree(List<PmsCategory> categories) {
        Map<Long, List<CategoryTreeVO>> childrenMap = categories.stream().map(c -> {
            CategoryTreeVO vo = new CategoryTreeVO();
            BeanUtils.copyProperties(c, vo);
            return vo;
        }).collect(Collectors.groupingBy(CategoryTreeVO::getParentId));

        List<CategoryTreeVO> roots = childrenMap.getOrDefault(0L, Collections.emptyList());
        fillChildren(roots, childrenMap);
        return roots;
    }

    private void fillChildren(List<CategoryTreeVO> nodes, Map<Long, List<CategoryTreeVO>> childrenMap) {
        for (CategoryTreeVO node : nodes) {
            List<CategoryTreeVO> children = childrenMap.getOrDefault(node.getId(), Collections.emptyList());
            node.setChildren(children);
            fillChildren(children, childrenMap);
        }
    }

    private void evictTreeCache() {
        Boolean deleted = redisTemplate.delete(CacheConstants.CATEGORY_TREE_KEY);
        log.info("分类变更，删除分类树缓存: {}", Boolean.TRUE.equals(deleted));
    }
}
