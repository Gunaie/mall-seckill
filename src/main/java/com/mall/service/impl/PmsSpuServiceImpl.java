package com.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.mall.common.constant.CacheConstants;
import com.mall.common.exception.BusinessException;
import com.mall.common.page.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.config.CacheProperties;
import com.mall.dto.SkuSaveDTO;
import com.mall.dto.SpuPageQuery;
import com.mall.dto.SpuSaveDTO;
import com.mall.entity.PmsCategory;
import com.mall.entity.PmsSku;
import com.mall.entity.PmsSpu;
import com.mall.mapper.PmsCategoryMapper;
import com.mall.mapper.PmsSkuMapper;
import com.mall.mapper.PmsSpuMapper;
import com.mall.search.SpuChangedEvent;
import com.mall.service.PmsSpuService;
import com.mall.vo.SkuVO;
import com.mall.vo.SpuDetailVO;
import com.mall.vo.SpuPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SPU 商品业务实现
 *
 * @author mall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmsSpuServiceImpl extends ServiceImpl<PmsSpuMapper, PmsSpu> implements PmsSpuService {

    private final PmsSkuMapper skuMapper;
    private final PmsCategoryMapper categoryMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;
    /** 本地一级缓存（Caffeine），热数据命中后零网络 IO */
    private final Cache<String, Object> localCache;
    /** 商品变更事件：事务提交后异步同步 ES */
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PageResult<SpuPageVO> pageOnSale(SpuPageQuery query) {
        return doPage(query, true);
    }

    @Override
    public PageResult<SpuPageVO> pageForAdmin(SpuPageQuery query) {
        return doPage(query, false);
    }

    @Override
    public SpuDetailVO getDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品ID非法");
        }
        String cacheKey = CacheConstants.SPU_DETAIL_KEY + id;

        // 0. 本地一级缓存（Caffeine）：命中即返回，零网络 IO，大幅降低 Redis 带宽
        Object local = localCache.getIfPresent(cacheKey);
        if (local != null) {
            if (local instanceof SpuDetailVO vo) {
                log.debug("命中本地缓存, spuId={}", id);
                return vo;
            }
            if (CacheConstants.NULL_PLACEHOLDER.equals(local)) {
                log.debug("命中本地空值缓存, spuId={}", id);
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
            }
        }

        // 1. 二级缓存（Redis）
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if (CacheConstants.NULL_PLACEHOLDER.equals(cached)) {
                // 命中空值缓存：请求被挡在 Redis 层，不会打到数据库（防穿透），同时回填本地缓存
                localCache.put(cacheKey, CacheConstants.NULL_PLACEHOLDER);
                log.debug("命中 Redis 空值缓存, spuId={}", id);
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
            }
            if (cached instanceof SpuDetailVO vo) {
                // Redis 命中：回填本地缓存
                localCache.put(cacheKey, vo);
                log.debug("命中 Redis 商品详情缓存, spuId={}", id);
                return vo;
            }
        }

        // 2. 未命中：Redisson 分布式锁保证同一时刻只有一个线程回源重建（防击穿）
        RLock lock = redissonClient.getLock(CacheConstants.SPU_DETAIL_LOCK_KEY + id);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!locked) {
                // 未抢到锁：短暂等待重建线程写缓存后重试一次，避免所有等待线程直接压库
                TimeUnit.MILLISECONDS.sleep(150);
                cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof SpuDetailVO vo) {
                    localCache.put(cacheKey, vo);
                    return vo;
                }
                throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "当前访问人数较多，请稍后再试");
            }

            // 3. DCL：拿到锁后二次检查缓存，可能已被前一个持锁线程重建
            cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof SpuDetailVO vo) {
                localCache.put(cacheKey, vo);
                return vo;
            }
            if (CacheConstants.NULL_PLACEHOLDER.equals(cached)) {
                localCache.put(cacheKey, CacheConstants.NULL_PLACEHOLDER);
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
            }

            // 4. 回源数据库并重建缓存
            SpuDetailVO detail = loadDetailFromDb(id);
            if (detail == null) {
                redisTemplate.opsForValue().set(cacheKey, CacheConstants.NULL_PLACEHOLDER,
                        cacheProperties.getNullValueTtl(), TimeUnit.SECONDS);
                localCache.put(cacheKey, CacheConstants.NULL_PLACEHOLDER);
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在或已下架");
            }
            // 基础 TTL + 随机抖动，打散过期时间（防雪崩）
            long ttl = cacheProperties.getSpuDetailTtl()
                    + ThreadLocalRandom.current().nextLong(cacheProperties.getSpuDetailTtlJitter() + 1);
            redisTemplate.opsForValue().set(cacheKey, detail, ttl, TimeUnit.SECONDS);
            localCache.put(cacheKey, detail);
            log.info("商品详情缓存重建完成, spuId={}, ttl={}s", id, ttl);
            return detail;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "查询被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSpu(SpuSaveDTO dto) {
        validateCategory(dto.getCategoryId());
        PmsSpu spu = new PmsSpu();
        BeanUtils.copyProperties(dto, spu, "skus");
        spu.setImages(toJson(dto.getImages()));
        if (spu.getStatus() == null) {
            spu.setStatus(1);
        }
        try {
            baseMapper.insert(spu);
            saveSkus(spu.getId(), dto.getSkus());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("SKU 编码重复，请检查后重试");
        }
        publishSpuChanged(spu.getId(), SpuChangedEvent.Type.UPSERT);
        return spu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSpu(SpuSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品ID不能为空");
        }
        PmsSpu exist = baseMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        validateCategory(dto.getCategoryId());

        PmsSpu spu = new PmsSpu();
        BeanUtils.copyProperties(dto, spu, "skus");
        spu.setImages(toJson(dto.getImages()));
        try {
            baseMapper.updateById(spu);
            // SKU 采用全量替换策略（后台编辑商品的常见做法）
            skuMapper.delete(Wrappers.<PmsSku>lambdaQuery().eq(PmsSku::getSpuId, dto.getId()));
            saveSkus(dto.getId(), dto.getSkus());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("SKU 编码重复，请检查后重试");
        }
        // Cache Aside：先更新库，再删除缓存，下次查询触发重建
        evictDetailCache(dto.getId());
        publishSpuChanged(dto.getId(), SpuChangedEvent.Type.UPSERT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSpu(Long id) {
        PmsSpu exist = baseMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        baseMapper.deleteById(id);
        skuMapper.delete(Wrappers.<PmsSku>lambdaQuery().eq(PmsSku::getSpuId, id));
        evictDetailCache(id);
        publishSpuChanged(id, SpuChangedEvent.Type.DELETE);
    }

    // ============================== 私有方法 ==============================

    private PageResult<SpuPageVO> doPage(SpuPageQuery query, boolean onlyOnSale) {
        Page<PmsSpu> page = new Page<>(query.getPageNum(), query.getPageSize());
        String keyword = query.getKeyword();
        baseMapper.selectPage(page, Wrappers.<PmsSpu>lambdaQuery()
                .eq(onlyOnSale, PmsSpu::getStatus, 1)
                .eq(query.getCategoryId() != null, PmsSpu::getCategoryId, query.getCategoryId())
                .like(StringUtils.hasText(keyword), PmsSpu::getName, keyword)
                .orderByDesc(PmsSpu::getId));

        List<PmsSpu> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(page, Collections.emptyList());
        }

        // 批量查询分类名与 SKU 最低价，避免分页 N+1
        Set<Long> categoryIds = records.stream().map(PmsSpu::getCategoryId).collect(Collectors.toSet());
        Map<Long, String> categoryNameMap = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(PmsCategory::getId, PmsCategory::getName));

        List<Long> spuIds = records.stream().map(PmsSpu::getId).toList();
        Map<Long, BigDecimal> minPriceMap = skuMapper.selectList(Wrappers.<PmsSku>lambdaQuery()
                        .in(PmsSku::getSpuId, spuIds)
                        .eq(PmsSku::getStatus, 1)).stream()
                .collect(Collectors.groupingBy(PmsSku::getSpuId,
                        Collectors.mapping(PmsSku::getPrice,
                                Collectors.minBy(BigDecimal::compareTo))))
                .entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        List<SpuPageVO> voList = records.stream().map(spu -> {
            SpuPageVO vo = new SpuPageVO();
            BeanUtils.copyProperties(spu, vo, "images", "detail");
            vo.setCategoryName(categoryNameMap.get(spu.getCategoryId()));
            vo.setMinPrice(minPriceMap.get(spu.getId()));
            return vo;
        }).toList();
        return PageResult.of(page, voList);
    }

    private SpuDetailVO loadDetailFromDb(Long id) {
        PmsSpu spu = baseMapper.selectById(id);
        // 买家端只展示上架商品，下架/删除统一视为不存在并做空值缓存
        if (spu == null || spu.getStatus() == null || spu.getStatus() != 1) {
            return null;
        }
        SpuDetailVO vo = new SpuDetailVO();
        BeanUtils.copyProperties(spu, vo, "images");
        vo.setImages(fromJsonList(spu.getImages()));

        PmsCategory category = categoryMapper.selectById(spu.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getName());
        }

        List<PmsSku> skus = skuMapper.selectList(Wrappers.<PmsSku>lambdaQuery()
                .eq(PmsSku::getSpuId, id)
                .eq(PmsSku::getStatus, 1)
                .orderByAsc(PmsSku::getId));
        vo.setSkus(skus.stream().map(this::toSkuVO).toList());
        return vo;
    }

    private void validateCategory(Long categoryId) {
        PmsCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("所选分类不存在");
        }
        if (category.getLevel() == null || category.getLevel() != 3) {
            throw new BusinessException("商品必须挂载在三级分类下");
        }
    }

    private void saveSkus(Long spuId, List<SkuSaveDTO> skuList) {
        for (SkuSaveDTO item : skuList) {
            PmsSku sku = new PmsSku();
            BeanUtils.copyProperties(item, sku, "spec");
            sku.setId(null);
            sku.setSpuId(spuId);
            sku.setSpecJson(toJson(item.getSpec()));
            sku.setStatus(1);
            skuMapper.insert(sku);
        }
    }

    private SkuVO toSkuVO(PmsSku sku) {
        SkuVO vo = new SkuVO();
        BeanUtils.copyProperties(sku, vo, "specJson");
        vo.setSpec(fromJsonMap(sku.getSpecJson()));
        return vo;
    }

    private void evictDetailCache(Long id) {
        String cacheKey = CacheConstants.SPU_DETAIL_KEY + id;
        // Cache Aside：先删 Redis 再删本地缓存，避免本地读到旧值
        Boolean deleted = redisTemplate.delete(cacheKey);
        localCache.invalidate(cacheKey);
        log.info("商品变更，删除详情缓存 spuId={}, redisDeleted={}", id, Boolean.TRUE.equals(deleted));
    }

    /**
     * 发布商品变更事件：事务提交后由 SpuIndexSyncService 异步同步 ES
     */
    private void publishSpuChanged(Long id, SpuChangedEvent.Type type) {
        eventPublisher.publishEvent(new SpuChangedEvent(id, type));
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> fromJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("图片 JSON 反序列化失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("规格 JSON 反序列化失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
